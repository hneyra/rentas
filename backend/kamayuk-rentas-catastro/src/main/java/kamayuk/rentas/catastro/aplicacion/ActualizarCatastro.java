package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La actualizacion del catastro como <b>un solo acto</b>: la correccion de los datos propios del
 * predio y la version siguiente de su ficha, en una transaccion y con una sola observacion.
 *
 * <h2>Por que existe, si ya estaba {@link ActualizarFichaCatastral}</h2>
 *
 * <p>Hasta aqui, los datos <b>del predio</b> —direccion, via, numero municipal, sector, manzana,
 * lote, ubigeo y tipo— solo se podian escribir al inscribirlo: {@code PeticionDeAlta} los lleva
 * todos y {@code PeticionDeActualizacion} no llevaba ninguno. Una direccion mal tecleada al fichar
 * era para siempre, y el unico camino era dar de baja el predio y volver a inscribirlo con otro
 * codigo, que es perder el historial de un predio que nunca dejo de existir.
 *
 * <p>Y no podia hacerlo el controlador llamando a dos casos de uso: {@link RegistrarPredio} y
 * {@link ActualizarFichaCatastral} son {@code @Transactional} cada uno por su lado, asi que dos
 * llamadas desde la capa web son <b>dos transacciones</b>. Con eso, una correccion que confirma y
 * una ficha que falla dejan el predio movido y su ficha sin versionar, sin nada que lo diga. Es
 * exactamente lo que {@code TransferirARentas} (#52) demostro midiendolo: con {@code REQUIRES_NEW}
 * sobrevivian 12 fichas donde debia haber 11.
 *
 * <h2>La resolucion de codigos entra aqui dentro, no en el controlador</h2>
 *
 * <p>Leer el predio por su codigo, o la via, el sector y la manzana por los suyos, es <b>una
 * consulta</b>, y una consulta fuera de transaccion corre sin el {@code SET LOCAL
 * app.municipalidad_id} que la politica RLS exige: falla con «invalid input syntax for type bigint:
 * ""». {@code ActualizacionController} lo hacia desde fuera —el mismo hueco que la marcha blanca de
 * #400 encontro en las cuatro fichas individuales (#486) y que {@code ConsultaDeVias} ya habia
 * cerrado una vez—, y por eso el predio se resuelve ahora aqui.
 *
 * <h2>Trivaluado, y por que no basta con «lo que llega, se guarda»</h2>
 *
 * <p>En {@link CorreccionDelPredio}, {@code null} es <b>«no cambia»</b> y la cadena vacia es <b>«se
 * borra»</b>. La alternativa —tomar el cuerpo entero como el estado nuevo, que es lo que {@code
 * PUT} sugiere— convierte cualquier campo que el cliente olvide mandar en un borrado silencioso,
 * sin que ningun {@code DELETE} aparezca en el diff. Es la misma regla que {@code
 * DeclaracionDeFicha} ya aplica a las listas de la ficha, dicha para escalares.
 *
 * <p>Ningun metodo recibe el identificador de municipalidad (regla 2).
 */
@Service
public class ActualizarCatastro {

    private final CatastroRepository catastro;
    private final ViaRepository vias;
    private final RegistrarPredio predios;
    private final ActualizarFichaCatastral fichas;

    public ActualizarCatastro(
            CatastroRepository catastro,
            ViaRepository vias,
            RegistrarPredio predios,
            ActualizarFichaCatastral fichas) {
        this.catastro = catastro;
        this.vias = vias;
        this.predios = predios;
        this.fichas = fichas;
    }

    /**
     * Corrige el predio si el acto trae correccion y versiona su ficha, todo junto.
     *
     * @param correccion nula cuando el acto solo versiona la ficha, que es el caso corriente
     */
    @Transactional
    public FichaCatastral actualizar(
            CodigoReferenciaCatastral codigo,
            TipoFicha tipo,
            DatosDeLaVersion version,
            @Nullable CorreccionDelPredio correccion,
            Observacion observacion) {

        Predio predio = predioPorCodigo(codigo);
        long predioId = Objects.requireNonNull(predio.id(), "El predio leido tiene identificador");

        if (correccion != null && !correccion.vacia()) {
            predios.registrar(corregido(predio, correccion), observacion);
        }

        return fichas.actualizar(
                predioId,
                tipo,
                version.desde(),
                version.origen(),
                version.documentoOrigen(),
                version.construcciones(),
                version.instalaciones(),
                version.detalle(),
                observacion);
    }

    /**
     * Retira el predio del padron. No se borra: aparece en determinaciones ya emitidas (regla 4,
     * RNF-051), y sus fichas y su historial quedan enteros.
     */
    @Transactional
    public Predio darDeBaja(long predioId, Observacion observacion) {
        Predio predio = predioPorId(predioId);
        if (!predio.estaActivo()) {
            throw new EstadoQueYaTiene(predioId, "dado de baja");
        }
        return predios.darDeBaja(predio, observacion);
    }

    /**
     * Devuelve al padron un predio retirado.
     *
     * <p>Existe porque sin ella la baja seria una <b>puerta de un solo sentido</b>: {@link
     * InscribirFicha} rechaza a proposito inscribir sobre un predio dado de baja —«reactivarlo es
     * otro acto, con su propia observacion», dice su javadoc desde #290— y hasta hoy ese otro acto
     * no existia en ninguna parte. Publicar la baja sin ella dejaria un predio retirado por error
     * sin ningun camino de vuelta.
     */
    @Transactional
    public Predio reactivar(long predioId, Observacion observacion) {
        Predio predio = predioPorId(predioId);
        if (predio.estaActivo()) {
            throw new EstadoQueYaTiene(predioId, "activo");
        }
        return predios.reactivar(predio, observacion);
    }

    // ------------------------------------------------------------------

    private Predio predioPorCodigo(CodigoReferenciaCatastral codigo) {
        return catastro.predioPorCodigo(codigo)
                .orElseThrow(
                        () ->
                                new PredioInexistente(
                                        "el codigo de referencia catastral '"
                                                + codigo.valor()
                                                + "'"));
    }

    private Predio predioPorId(long predioId) {
        return catastro.predio(predioId)
                .orElseThrow(() -> new PredioInexistente("el identificador " + predioId));
    }

    /**
     * El predio con la correccion aplicada.
     *
     * <p>Sector y manzana se resuelven juntos porque estan acoplados: una manzana pertenece a un
     * sector, asi que mover el predio de sector sin decir a que manzana lo dejaria colgando de una
     * manzana del sector anterior. Eso se rechaza nombrandolo, en vez de arrastrar la manzana vieja
     * o borrarla sin avisar.
     */
    private Predio corregido(Predio predio, CorreccionDelPredio correccion) {
        Ubicacion ubicacion = ubicacionDe(predio, correccion);
        return new Predio(
                predio.id(),
                predio.codigo(),
                correccion.tipo() == null ? predio.tipo() : correccion.tipo(),
                ubicacion.viaId(),
                cambiado(correccion.numeroMunicipal(), predio.numeroMunicipal()),
                correccion.direccion() == null ? predio.direccion() : correccion.direccion(),
                ubicacion.sectorId(),
                ubicacion.manzanaId(),
                cambiado(correccion.lote(), predio.lote()),
                cambiado(correccion.ubigeo(), predio.ubigeo()),
                predio.estado());
    }

    /** {@code null} deja lo que habia; la cadena vacia borra; cualquier otra cosa sustituye. */
    private static @Nullable String cambiado(@Nullable String declarado, @Nullable String actual) {
        if (declarado == null) {
            return actual;
        }
        return declarado.isBlank() ? null : declarado.strip();
    }

    private Ubicacion ubicacionDe(Predio predio, CorreccionDelPredio correccion) {
        Long viaId = predio.viaId();
        String codigoDeVia = correccion.codigoDeVia();
        if (codigoDeVia != null) {
            viaId = codigoDeVia.isBlank() ? null : viaPorCodigo(codigoDeVia.strip());
        }

        String codigoDeSector = correccion.codigoDeSector();
        String codigoDeManzana = correccion.codigoDeManzana();

        if (codigoDeSector == null) {
            if (codigoDeManzana == null) {
                return new Ubicacion(viaId, predio.sectorId(), predio.manzanaId());
            }
            if (codigoDeManzana.isBlank()) {
                return new Ubicacion(viaId, predio.sectorId(), null);
            }
            Long sectorId = predio.sectorId();
            if (sectorId == null) {
                throw new IllegalArgumentException(
                        "Se declaro la manzana '"
                                + codigoDeManzana
                                + "' y el predio no tiene sector: la manzana pertenece a uno, asi"
                                + " que hay que declarar tambien el sector");
            }
            return new Ubicacion(viaId, sectorId, manzanaDe(sectorId, codigoDeManzana.strip()));
        }

        if (codigoDeSector.isBlank()) {
            if (codigoDeManzana == null || !codigoDeManzana.isBlank()) {
                throw new IllegalArgumentException(
                        "Se quita el sector del predio y no se quita su manzana: la manzana"
                                + " pertenece a un sector, asi que hay que quitarla en el mismo"
                                + " acto mandando su codigo en blanco");
            }
            return new Ubicacion(viaId, null, null);
        }

        long sectorId = sectorPorCodigo(codigoDeSector.strip());
        if (codigoDeManzana == null) {
            // Mover el predio de sector conservando la manzana anterior la dejaria apuntando a
            // una manzana de otro sector. Si el sector no cambia, lo que habia sigue valiendo.
            if (!Objects.equals(predio.sectorId(), sectorId) && predio.manzanaId() != null) {
                throw new IllegalArgumentException(
                        "El predio cambia al sector '"
                                + codigoDeSector.strip()
                                + "' y conserva una manzana del sector anterior: hay que declarar"
                                + " la manzana nueva, o mandar su codigo en blanco para quitarla");
            }
            return new Ubicacion(viaId, sectorId, predio.manzanaId());
        }
        if (codigoDeManzana.isBlank()) {
            return new Ubicacion(viaId, sectorId, null);
        }
        return new Ubicacion(viaId, sectorId, manzanaDe(sectorId, codigoDeManzana.strip()));
    }

    private long viaPorCodigo(String codigo) {
        Via via =
                vias.findByCodigo(codigo)
                        .orElseThrow(() -> new InscribirFicha.ReferenciaInexistente("via", codigo));
        return Objects.requireNonNull(via.id(), "La via leida tiene identificador");
    }

    private long sectorPorCodigo(String codigo) {
        Sector sector =
                catastro.sectorPorCodigo(codigo)
                        .orElseThrow(
                                () -> new InscribirFicha.ReferenciaInexistente("sector", codigo));
        return Objects.requireNonNull(sector.id(), "El sector leido tiene identificador");
    }

    private long manzanaDe(long sectorId, String codigo) {
        for (Manzana manzana : catastro.manzanasDe(sectorId)) {
            if (manzana.codigo().equals(codigo)) {
                return Objects.requireNonNull(manzana.id(), "La manzana leida tiene identificador");
            }
        }
        throw new InscribirFicha.ReferenciaInexistente("manzana", codigo);
    }

    private record Ubicacion(
            @Nullable Long viaId, @Nullable Long sectorId, @Nullable Long manzanaId) {}

    // ------------------------------------------------------------------

    /**
     * Los datos de la version que se inscribe. Es lo que {@link
     * ActualizarFichaCatastral#actualizar} ya recibia, agrupado para que la firma de {@link
     * #actualizar} no tenga nueve parametros.
     */
    public record DatosDeLaVersion(
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            @Nullable List<Construccion> construcciones,
            @Nullable List<OtraInstalacion> instalaciones,
            @Nullable DetalleDeLaFicha detalle) {}

    /**
     * Lo que se corrige del predio. <b>Trivaluado</b>: {@code null} es «no cambia» y la cadena
     * vacia es «se borra».
     *
     * <p>El codigo de referencia catastral <b>no esta</b>, y no es un olvido: es lo que identifica
     * al predio ({@code predio_codigo_uq}), y cambiarlo no es corregir un predio sino declarar
     * otro. Un codigo mal compuesto se arregla dando de baja el predio e inscribiendo el correcto,
     * que es justo el rastro que debe quedar.
     *
     * <p>Via, sector y manzana entran por <b>codigo</b> —lo que el tecnico tiene delante—, igual
     * que en {@link InscribirFicha.DatosDelPredio}.
     */
    public record CorreccionDelPredio(
            @Nullable TipoPredio tipo,
            @Nullable String direccion,
            @Nullable String codigoDeVia,
            @Nullable String numeroMunicipal,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            @Nullable String ubigeo) {

        /** Cierto cuando el bloque llego sin un solo campo: no hay nada que corregir. */
        public boolean vacia() {
            return tipo == null
                    && direccion == null
                    && codigoDeVia == null
                    && numeroMunicipal == null
                    && codigoDeSector == null
                    && codigoDeManzana == null
                    && lote == null
                    && ubigeo == null;
        }
    }

    /** No hay ningun predio con ese codigo o ese identificador. */
    public static final class PredioInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioInexistente(String comoSePidio) {
            super("No hay ningun predio con " + comoSePidio);
        }
    }

    /** Se pide un cambio de estado que el predio ya tiene: la baja de uno retirado, o al reves. */
    public static final class EstadoQueYaTiene extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EstadoQueYaTiene(long predioId, String estado) {
            super("El predio " + predioId + " ya esta " + estado);
        }
    }
}
