package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La inscripcion de un predio en el catastro: <b>el predio, su primera ficha y su titular, en un
 * solo acto</b>.
 *
 * <h2>Por que el predio nace aqui</h2>
 *
 * <p>{@code ficha_catastral.predio_id} es {@code NOT NULL}: no hay ficha sin predio. Y el predio
 * solo existia hasta ahora por el camino de importacion, asi que sin esto la pantalla de alta de
 * ficha —las cuatro— no tendria a que colgarse. Registrar el predio y despues la ficha desde el
 * controlador, en dos llamadas, dejaria el padron con predios sin ficha cada vez que la segunda
 * falle: un predio huerfano no se ve en ninguna pantalla y no lo encuentra nadie hasta que el
 * codigo de referencia catastral se quiere reutilizar y sale un {@code 409} que nadie entiende.
 *
 * <p>Si el predio <b>ya existe</b> con ese codigo, se usa el que hay y no se toca: el codigo de
 * referencia catastral lo identifica, y un predio puede tener una ficha de cada tipo. Lo que si
 * rechaza es inscribir sobre un predio <b>dado de baja</b>: reactivarlo es otro acto, con su propia
 * observacion, y ficharlo sin reactivarlo dejaria una ficha vigente sobre algo que el padron da por
 * retirado.
 *
 * <h2>Una transaccion, tres escrituras y una sola observacion</h2>
 *
 * <p>El metodo lleva {@code @Transactional} y llama a los casos de uso que ya existen —{@link
 * RegistrarPredio} y {@link ActualizarFichaCatastral}—, que se anotan {@code REQUIRED} y por tanto
 * <b>se unen a esta transaccion</b> en vez de abrir la suya. Las tres filas de auditoria —{@code
 * predio}, {@code ficha_catastral} y {@code titularidad}— llevan la <b>misma</b> observacion: es un
 * acto, no tres (regla 10, RNF-052).
 *
 * <p>El titular se resuelve al final, donde se necesita. Da igual donde falle —el acto es atomico—,
 * pero que falle despues de dos escrituras es lo que hace visible que las tres van juntas: si el
 * codigo de contribuyente no existe, no queda ni el predio ni la ficha.
 *
 * <p><b>Los codigos se resuelven aqui dentro</b>, no en el controlador: leer el sector, la manzana,
 * la via o el contribuyente es una consulta, y una consulta fuera de transaccion corre sin el
 * {@code SET LOCAL app.municipalidad_id} que la politica RLS exige. Es el defecto que la marcha
 * blanca destapo en {@code GET /catastro/vias}.
 *
 * <p>Ningun argumento es el identificador de municipalidad (regla 2).
 */
@Service
public class InscribirFicha {

    private final CatastroRepository catastro;
    private final ViaRepository vias;
    private final DirectorioDeContribuyentes padron;
    private final RegistrarPredio predios;
    private final ActualizarFichaCatastral fichas;

    public InscribirFicha(
            CatastroRepository catastro,
            ViaRepository vias,
            DirectorioDeContribuyentes padron,
            RegistrarPredio predios,
            ActualizarFichaCatastral fichas) {
        this.catastro = catastro;
        this.vias = vias;
        this.padron = padron;
        this.predios = predios;
        this.fichas = fichas;
    }

    /**
     * Inscribe la primera version de la ficha, dando de alta el predio si hace falta.
     *
     * @param titular la titularidad inicial; nula cuando el predio se ficha antes de identificar a
     *     su propietario, que en un levantamiento catastral es lo normal
     */
    @Transactional
    public FichaCatastral inscribir(
            DatosDelPredio datosDelPredio,
            DatosDeLaFicha datosDeLaFicha,
            @Nullable DatosDelTitular titular,
            Observacion observacion) {

        Predio predio = predioDelActo(datosDelPredio, observacion);
        long predioId =
                Objects.requireNonNull(predio.id(), "El predio guardado tiene identificador");

        FichaCatastral ficha =
                fichas.registrarPrimera(
                        primeraVersion(predioId, datosDeLaFicha, observacion), observacion);

        if (titular != null) {
            predios.registrarTitularidad(
                    titularidadDe(predioId, titular, datosDeLaFicha.vigenciaDesde()), observacion);
        }
        return ficha;
    }

    /**
     * Inscribe <b>solo el predio</b>, sin ficha (#489, RF-001).
     *
     * <p>Es el orden natural de ventanilla, que es el contrario del que {@link #inscribir} atiende:
     * primero se identifica el predio —su codigo de referencia catastral, su ubicacion, su tipo— y
     * despues se le levanta la ficha. Hasta aqui un predio solo podia nacer como efecto secundario
     * de inscribir una ficha, o por la carga cartografica del perfil {@code batch}, asi que
     * registrar un lote recien numerado obligaba a inventarle una ficha.
     *
     * <p>Vive en esta clase y no en {@link RegistrarPredio} porque lo que hace falta no es guardar
     * un {@code Predio} ya formado —eso ya lo hace aquel— sino <b>resolver sector, manzana y via
     * por su codigo dentro de la transaccion</b>, que es exactamente lo que {@link #nuevo} hace y
     * lo que un controlador no puede hacer: leerlos es una consulta, y una consulta fuera de
     * transaccion corre sin el {@code SET LOCAL} que RLS exige (#486).
     *
     * <p><b>Un codigo que ya existe es un conflicto, no un no-op.</b> {@link #inscribir} devuelve
     * el predio que ya estaba —tiene sentido: quien inscribe una ficha quiere la ficha, y el predio
     * es el medio—, pero quien pide dar de alta un predio esta afirmando que no existe.
     *
     * <p><b>Y esta comprobacion no es la que lo impide</b>: quitarla deja el {@code 409} en pie,
     * porque {@code predio_codigo_uq} rechaza la fila y el controlador traduce el choque. Se midio.
     * Lo unico que aporta es <b>nombrar el codigo repetido</b>: sin ella el mensaje es generico, y
     * quien atiende no sabe cual de los codigos que tecleo esta tomado. Se deja dicho aqui en vez
     * de fingir que la guarda protege algo (la leccion de #188).
     */
    @Transactional
    public Predio inscribirPredio(DatosDelPredio datos, Observacion observacion) {
        if (catastro.predioPorCodigo(datos.codigo()).isPresent()) {
            throw new PredioYaInscrito(datos.codigo());
        }
        return predios.registrar(nuevo(datos), observacion);
    }

    // ------------------------------------------------------------------

    /** El predio que ya esta, o el que nace en este mismo acto. */
    private Predio predioDelActo(DatosDelPredio datos, Observacion observacion) {
        Optional<Predio> existente = catastro.predioPorCodigo(datos.codigo());
        if (existente.isPresent()) {
            Predio predio = existente.get();
            if (!predio.estaActivo()) {
                throw new PredioDadoDeBaja(datos.codigo());
            }
            return predio;
        }
        return predios.registrar(nuevo(datos), observacion);
    }

    private Predio nuevo(DatosDelPredio datos) {
        String codigoDeSector = datos.codigoDeSector();
        String codigoDeManzana = datos.codigoDeManzana();
        String codigoDeVia = datos.codigoDeVia();

        Long sectorId = null;
        Long manzanaId = null;
        if (codigoDeSector != null) {
            Sector sector =
                    catastro.sectorPorCodigo(codigoDeSector)
                            .orElseThrow(() -> new ReferenciaInexistente("sector", codigoDeSector));
            sectorId = Objects.requireNonNull(sector.id(), "El sector leido tiene identificador");
            if (codigoDeManzana != null) {
                manzanaId = manzanaDe(sectorId, codigoDeManzana);
            }
        } else if (codigoDeManzana != null) {
            // Lo mismo que comprueba el constructor de Predio, dicho antes y con el codigo que
            // el usuario escribio: una manzana pertenece a un sector.
            throw new IllegalArgumentException(
                    "Se declaro la manzana '"
                            + codigoDeManzana
                            + "' sin su sector: la manzana pertenece a uno");
        }

        Long viaId = null;
        if (codigoDeVia != null) {
            Via via =
                    vias.findByCodigo(codigoDeVia)
                            .orElseThrow(() -> new ReferenciaInexistente("via", codigoDeVia));
            viaId = Objects.requireNonNull(via.id(), "La via leida tiene identificador");
        }

        return new Predio(
                null,
                datos.codigo(),
                datos.tipo(),
                viaId,
                datos.numeroMunicipal(),
                datos.direccion(),
                sectorId,
                manzanaId,
                datos.lote(),
                datos.ubigeo(),
                EstadoPredio.ACTIVO);
    }

    private long manzanaDe(long sectorId, String codigo) {
        for (Manzana manzana : catastro.manzanasDe(sectorId)) {
            if (manzana.codigo().equals(codigo)) {
                return Objects.requireNonNull(manzana.id(), "La manzana leida tiene identificador");
            }
        }
        throw new ReferenciaInexistente("manzana", codigo);
    }

    private static FichaCatastral primeraVersion(
            long predioId, DatosDeLaFicha datos, Observacion observacion) {
        return FichaCatastral.primera(
                        predioId,
                        datos.tipo(),
                        datos.areaTerreno(),
                        datos.uso(),
                        datos.vigenciaDesde(),
                        datos.origen(),
                        datos.documentoOrigen(),
                        observacion)
                .conDenominacion(datos.denominacion())
                .con(datos.construcciones())
                .conInstalaciones(datos.instalaciones())
                // conDetalle pasa por el constructor de FichaCatastral, que rechaza un detalle
                // que no sea del tipo de la ficha.
                .conDetalle(datos.detalle());
    }

    private Titularidad titularidadDe(long predioId, DatosDelTitular datos, LocalDate desde) {
        ResumenDeContribuyente contribuyente =
                padron.porCodigo(datos.codigoContribuyente())
                        .orElseThrow(
                                () ->
                                        new ReferenciaInexistente(
                                                "contribuyente", datos.codigoContribuyente()));

        if (datos.condicion().esPorElTotal()) {
            return Titularidad.unico(predioId, contribuyente.id(), desde, datos.documentoOrigen());
        }
        Porcentaje porcentaje = datos.porcentaje();
        if (porcentaje == null) {
            throw new IllegalArgumentException(
                    "Un titular "
                            + datos.condicion()
                            + " necesita su porcentaje: solo el propietario unico lo es por el"
                            + " total");
        }
        return Titularidad.parcial(
                predioId,
                contribuyente.id(),
                datos.condicion(),
                porcentaje,
                desde,
                datos.documentoOrigen());
    }

    // ------------------------------------------------------------------

    /**
     * El predio del acto. {@code codigo} lo identifica; el resto solo se usa si hay que darlo de
     * alta.
     *
     * <p>Sector, manzana y via entran por <b>codigo</b> y no por identificador interno: es lo que
     * el tecnico tiene delante, y resolverlos es parte de la misma transaccion.
     */
    public record DatosDelPredio(
            CodigoReferenciaCatastral codigo,
            TipoPredio tipo,
            String direccion,
            @Nullable String codigoDeVia,
            @Nullable String numeroMunicipal,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            @Nullable String ubigeo) {

        public DatosDelPredio {
            Objects.requireNonNull(codigo, "El predio necesita su codigo de referencia catastral");
            Objects.requireNonNull(tipo, "El predio necesita su tipo");
            Objects.requireNonNull(direccion, "El predio necesita su direccion");
        }
    }

    /**
     * La primera version de la ficha. Las listas son vacias, no nulas: aqui no hay version anterior
     * de la que copiar, asi que «no lo mando» y «no hay ninguna» son lo mismo.
     */
    public record DatosDeLaFicha(
            TipoFicha tipo,
            AreaM2 areaTerreno,
            String uso,
            @Nullable String denominacion,
            LocalDate vigenciaDesde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            List<Construccion> construcciones,
            List<OtraInstalacion> instalaciones,
            @Nullable DetalleDeLaFicha detalle) {

        public DatosDeLaFicha {
            Objects.requireNonNull(tipo, "La ficha necesita su tipo");
            Objects.requireNonNull(construcciones, "La lista de construcciones es vacia, no nula");
            Objects.requireNonNull(instalaciones, "La lista de instalaciones es vacia, no nula");
            construcciones = List.copyOf(construcciones);
            instalaciones = List.copyOf(instalaciones);
        }
    }

    /**
     * El titular inicial, por su <b>codigo de contribuyente</b>.
     *
     * <p>El identificador interno no entra por HTTP y tampoco por aqui: quien inscribe teclea el
     * codigo del padron, y resolverlo es de este contexto.
     *
     * @param porcentaje nulo solo si la condicion es por el total, que es 100 por definicion
     */
    public record DatosDelTitular(
            String codigoContribuyente,
            CondicionDeTitularidad condicion,
            @Nullable Porcentaje porcentaje,
            String documentoOrigen) {

        public DatosDelTitular {
            Objects.requireNonNull(codigoContribuyente, "El titular entra por su codigo");
            Objects.requireNonNull(condicion, "La titularidad necesita su condicion");
            Objects.requireNonNull(documentoOrigen, "La titularidad necesita su documento");
        }
    }

    /**
     * Se nombro algo que no existe en esta municipalidad: un sector, una manzana, una via o un
     * contribuyente.
     *
     * <p>Es una clase y no cuatro porque quien la traduce hace lo mismo con las cuatro —un {@code
     * 404} que nombra lo pedido—, y {@link #que()} dice cual fue sin que el mensaje haya que
     * analizarlo.
     */
    public static final class ReferenciaInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String que;
        private final String codigo;

        ReferenciaInexistente(String que, String codigo) {
            super("No hay ningun " + que + " con codigo '" + codigo + "' en esta municipalidad");
            this.que = que;
            this.codigo = codigo;
        }

        public String que() {
            return que;
        }

        public String codigo() {
            return codigo;
        }
    }

    /**
     * Ya hay un predio con ese codigo de referencia catastral.
     *
     * <p>No dice donde esta ni de quien es: solo que el codigo esta tomado en esta municipalidad.
     */
    public static final class PredioYaInscrito extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioYaInscrito(CodigoReferenciaCatastral codigo) {
            super(
                    "Ya hay un predio con el codigo de referencia catastral "
                            + codigo.valor()
                            + " en esta municipalidad");
        }
    }

    /** El predio existe pero esta retirado del padron: reactivarlo es otro acto. */
    public static final class PredioDadoDeBaja extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioDadoDeBaja(CodigoReferenciaCatastral codigo) {
            super(
                    "El predio "
                            + codigo.valor()
                            + " esta dado de baja; ficharlo sin reactivarlo dejaria una ficha"
                            + " vigente sobre algo que el padron da por retirado");
        }
    }
}
