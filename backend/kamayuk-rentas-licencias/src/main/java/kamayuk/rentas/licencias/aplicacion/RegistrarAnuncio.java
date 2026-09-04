package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeAnuncio;
import kamayuk.rentas.licencias.dominio.TipoDeAnuncio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra una autorizacion de anuncio y <b>genera su deuda</b> por la tasa (#51, RF-114).
 *
 * <h2>Es el caso donde el manual pide que el registro genere la deuda, y donde se prueba la regla
 * del contexto</h2>
 *
 * <p>La deuda <b>no se asienta aqui</b>. Se le pide a {@code cuentacorriente} por {@link
 * GeneradorDeCargos}, que es su API publica (ARQ-01 §4 regla 2). Este modulo no conoce {@code
 * cuenta_corriente_asiento} ni {@code saldo_proyectado}, no tiene privilegio para escribir en ellas
 * y Spring Modulith rechaza la dependencia si alguien intenta entrar por {@code
 * cuentacorriente.dominio} o {@code .infraestructura}. Es exactamente lo contrario del camino
 * comodo, que seria un {@code INSERT} de dos lineas.
 *
 * <p>Fue tambien un cambio de postura del modulo: hasta #44 el {@code package-info} de {@code
 * licencias} decia «NO DETERMINA DEUDA, Y HOY NO GENERA NINGUNA», porque el derecho de tramite de
 * una licencia se paga <b>antes</b> en caja de tasas y no es deuda tributaria. La tasa de un
 * anuncio si lo es: se determina al autorizar, se cobra despues y se puede quedar impaga.
 *
 * <h2>Un cargo, exactamente uno, y lo decide la base</h2>
 *
 * <p>Es el primer criterio de aceptacion de #51, y esta sostenido por <b>dos</b> indices unicos, no
 * por un {@code if}:
 *
 * <ol>
 *   <li>{@code anuncio_idempotencia_uq} sobre la cabecera {@code idempotency-key} que el frontend
 *       ya manda en toda escritura. Si la clave se repite, este caso de uso devuelve el anuncio de
 *       la primera vez —con {@link Registro#yaExistia()} en cierto— y <b>no pide ningun cargo</b>.
 *       La lectura previa esta para dar una respuesta util; la garantia es el indice, porque entre
 *       leer y escribir cabe otra peticion.
 *   <li>{@code anuncio_movimiento_cargo_uq} sobre {@code referencia_cargo}, que es la misma cadena
 *       que viaja al libro como {@code referencia_externa}. El movimiento se registra <b>antes</b>
 *       de pedir el cargo, de modo que un segundo devengo del mismo anuncio y el mismo ejercicio
 *       revienta sin llegar a escribir en el libro.
 * </ol>
 *
 * <h2>Ninguna cifra</h2>
 *
 * <p>La tasa sale de {@link TasaDeAnunciosParametrizada}, del conjunto sellado vigente a la fecha
 * de la autorizacion. No hay ningun importe en la firma ni en el codigo: la ordenanza que la fija
 * es D-02b y el issue #199 la espera. Sin el parametro, el registro <b>falla nombrando la llave</b>
 * (regla 5).
 */
@Service
public class RegistrarAnuncio {

    /**
     * El tributo con el que la tasa entra en el libro.
     *
     * <p>Es el vocabulario que {@code determinacion.tributo} ya declaraba en V2 —{@code
     * 'ANUNCIOS'}—, no uno nuevo: la deuda por publicidad tiene un solo nombre en todo el sistema o
     * la consulta de deuda del contribuyente la parte en dos.
     */
    public static final String TRIBUTO = TributoDelLibro.ANUNCIOS.texto();

    private static final String TABLA_AUDITADA = "anuncio";

    private final AnuncioRepository anuncios;
    private final MovimientoDeAnuncioRepository movimientos;
    private final LicenciaRepository licencias;
    private final DirectorioDeContribuyentes contribuyentes;
    private final TasaDeAnunciosParametrizada tasas;
    private final GeneradorDeCargos cargos;
    private final PlantillaDeNumeroDeAnuncio plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarAnuncio(
            AnuncioRepository anuncios,
            MovimientoDeAnuncioRepository movimientos,
            LicenciaRepository licencias,
            DirectorioDeContribuyentes contribuyentes,
            TasaDeAnunciosParametrizada tasas,
            GeneradorDeCargos cargos,
            PlantillaDeNumeroDeAnuncio plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.anuncios = anuncios;
        this.movimientos = movimientos;
        this.licencias = licencias;
        this.contribuyentes = contribuyentes;
        this.tasas = tasas;
        this.cargos = cargos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Autoriza el anuncio y genera el cargo por su tasa.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Solicitud}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @param solicitud lo que el administrado declara
     * @param claveDeIdempotencia la cabecera {@code idempotency-key}; opcional
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws TitularDesconocido si el codigo de contribuyente no esta en el padron
     * @throws EstablecimientoDesconocido si se nombra una licencia que no existe
     * @throws TasaDeAnunciosParametrizada.TasaSinParametrizar si la ordenanza sellada no tarifa esa
     *     clase de elemento (regla 5, D-02b, #199)
     */
    @Transactional
    public Registro registrar(
            Solicitud solicitud, @Nullable String claveDeIdempotencia, Observacion observacion) {

        Objects.requireNonNull(solicitud, "No se autoriza sin solicitud");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        String clave = limpiar(claveDeIdempotencia);
        if (clave != null) {
            Optional<Anuncio> yaRegistrado = anuncios.porClaveDeIdempotencia(clave);
            if (yaRegistrado.isPresent()) {
                // El reenvio: mismo doble clic, misma respuesta, NINGUN segundo cargo. La lectura
                // esta para poder contestar algo util; quien impide de verdad el duplicado es
                // `anuncio_idempotencia_uq`, porque entre este SELECT y el INSERT cabe otra
                // peticion.
                Anuncio anuncio = yaRegistrado.get();
                return new Registro(
                        anuncio,
                        movimientos.deAnuncio(anuncio.identificador()).stream()
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Un anuncio registrado siempre tiene su"
                                                                + " movimiento de autorizacion")),
                        titularDe(anuncio.contribuyenteId()),
                        true);
            }
        }

        ResumenDeContribuyente titular =
                contribuyentes
                        .porCodigo(solicitud.codigoContribuyente())
                        .orElseThrow(() -> new TitularDesconocido(solicitud.codigoContribuyente()));

        LicenciaDeFuncionamiento establecimiento = establecimientoDe(solicitud);

        // La tasa, del conjunto sellado vigente a la fecha del acto. Se resuelve ANTES de escribir
        // nada: una autorizacion cuya clase la ordenanza no tarifa no se registra a medias.
        Dinero tasa =
                tasas.aLaFechaDe(solicitud.fechaAutorizacion()).paraLaClase(solicitud.clase());

        Ejercicio ejercicio = Ejercicio.de(solicitud.fechaAutorizacion());
        String numero = plantilla.componer(ejercicio, anuncios.siguienteCorrelativo(ejercicio));

        // El predio sale del establecimiento cuando lo hay: un anuncio colgado de un local esta
        // donde el local, y dejar que la peticion declarara otro permitiria imputarle la tasa a un
        // predio que no es. Sin licencia, el que la solicitud declare.
        Long predioId = establecimiento == null ? solicitud.predioId() : establecimiento.predioId();

        Instant ahora = reloj.instant();
        Anuncio guardado =
                anuncios.autorizar(
                        new Anuncio(
                                null,
                                numero,
                                titular.id(),
                                predioId,
                                establecimiento == null ? null : establecimiento.identificador(),
                                solicitud.clase(),
                                solicitud.tipo(),
                                solicitud.emplazamiento(),
                                solicitud.forma(),
                                solicitud.denominacion(),
                                solicitud.ubicacion(),
                                solicitud.area(),
                                solicitud.lados(),
                                solicitud.cantidad(),
                                solicitud.fechaAutorizacion(),
                                solicitud.vigenciaHasta(),
                                solicitud.expediente(),
                                solicitud.fechaExpediente(),
                                clave,
                                ahora,
                                null,
                                observacion));

        String referencia = MovimientoDeAnuncio.referenciaDelCargo(numero, ejercicio);

        // EL ORDEN IMPORTA: primero el movimiento, despues el cargo. `anuncio_movimiento_cargo_uq`
        // rechaza el segundo devengo del mismo anuncio y ejercicio ANTES de que el libro reciba
        // nada. Al reves seria correcto tambien —la transaccion se desharia entera— pero por
        // accidente, y dejaria en el libro un asiento que hubo que retirar.
        MovimientoDeAnuncio autorizacion =
                movimientos.registrar(
                        MovimientoDeAnuncio.autorizacion(
                                guardado.identificador(),
                                solicitud.fechaAutorizacion(),
                                ejercicio,
                                referencia,
                                tasa,
                                solicitud.vigenciaHasta(),
                                ahora,
                                observacion));

        cargos.generarCargo(
                ejercicio,
                titular.id(),
                TRIBUTO,
                null,
                predioId,
                null,
                referencia,
                tasa,
                solicitud.fechaAutorizacion(),
                "AUTORIZACION-" + numero,
                observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                solicitud.fechaAutorizacion(),
                                TABLA_AUDITADA,
                                String.valueOf(guardado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado, tasa, referencia)));

        return new Registro(guardado, autorizacion, titular, false);
    }

    // ------------------------------------------------------------------

    private @Nullable LicenciaDeFuncionamiento establecimientoDe(Solicitud solicitud) {
        String numeroDeLicencia = limpiar(solicitud.numeroDeLicencia());
        if (numeroDeLicencia == null) {
            return null;
        }
        return licencias
                .porNumero(numeroDeLicencia)
                .orElseThrow(() -> new EstablecimientoDesconocido(numeroDeLicencia));
    }

    private ResumenDeContribuyente titularDe(long contribuyenteId) {
        ResumenDeContribuyente titular =
                contribuyentes.porIds(java.util.Set.of(contribuyenteId)).get(contribuyenteId);
        if (titular == null) {
            throw new IllegalStateException(
                    "El anuncio es de un contribuyente que el padron ya no tiene: "
                            + contribuyenteId);
        }
        return titular;
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Anuncio anuncio, Dinero tasa, String referencia) {
        return "{\"numero\":\""
                + anuncio.numero()
                + "\",\"clase\":\""
                + anuncio.clase()
                + "\",\"area\":"
                + anuncio.area().valor().toPlainString()
                + ",\"lados\":"
                + anuncio.lados()
                + ",\"tasa\":"
                + tasa.valor().toPlainString()
                + ",\"referenciaDelCargo\":\""
                + referencia
                + "\"}";
    }

    // ------------------------------------------------------------------

    /**
     * Lo que se declara para autorizar un anuncio.
     *
     * @param codigoContribuyente el titular, tal como lo teclea la pantalla
     * @param numeroDeLicencia el establecimiento asociado, por el numero de su licencia; opcional
     * @param predioId el predio, cuando no hay establecimiento; opcional
     * @param clase la clase del elemento; de ella sale la tasa
     * @param tipo si el aviso es simple, luminoso, iluminado o electronico
     * @param emplazamiento donde se emplaza; descriptivo
     * @param forma la forma del soporte; descriptiva
     * @param denominacion lo que el anuncio exhibe
     * @param ubicacion la direccion donde se instala
     * @param area la superficie declarada
     * @param lados cuantas caras tiene
     * @param cantidad cuantos elementos ampara
     * @param fechaAutorizacion el dia del acto; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando rige
     * @param expediente el numero del expediente del tramite
     * @param fechaExpediente su fecha
     */
    public record Solicitud(
            String codigoContribuyente,
            @Nullable String numeroDeLicencia,
            @Nullable Long predioId,
            ClaseDeAnuncio clase,
            TipoDeAnuncio tipo,
            @Nullable String emplazamiento,
            @Nullable String forma,
            @Nullable String denominacion,
            String ubicacion,
            AreaM2 area,
            int lados,
            int cantidad,
            LocalDate fechaAutorizacion,
            @Nullable LocalDate vigenciaHasta,
            @Nullable String expediente,
            @Nullable LocalDate fechaExpediente) {

        public Solicitud {
            Objects.requireNonNull(codigoContribuyente, "La autorizacion es de un titular");
            Objects.requireNonNull(clase, "El anuncio necesita su clase: de ella sale la tasa");
            Objects.requireNonNull(tipo, "El anuncio necesita su tipo");
            Objects.requireNonNull(ubicacion, "El anuncio necesita la direccion donde se instala");
            Objects.requireNonNull(area, "El anuncio necesita el area declarada");
            Objects.requireNonNull(fechaAutorizacion, "La fecha entra como argumento (regla 6)");
        }
    }

    /**
     * La autorizacion recien registrada, su acto y su titular.
     *
     * @param anuncio la fila guardada
     * @param autorizacion el movimiento que la creo, con la referencia del cargo y el importe
     * @param titular el resumen del padron, para que el borde no lo tenga que releer
     * @param yaExistia si esta respuesta es el reenvio de una peticion ya atendida. Viaja para que
     *     el borde pueda contestar {@code 200} en vez de {@code 201}: son cosas distintas y quien
     *     reintenta merece saber cual le paso
     */
    public record Registro(
            Anuncio anuncio,
            MovimientoDeAnuncio autorizacion,
            ResumenDeContribuyente titular,
            boolean yaExistia) {}

    /** El codigo de contribuyente no esta en el padron de esta municipalidad. */
    public static final class TitularDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TitularDesconocido(String codigo) {
            super(
                    "No hay ningun contribuyente con codigo '"
                            + codigo
                            + "' en esta municipalidad: una autorizacion de anuncio se emite a un"
                            + " titular del padron, y la tasa se le carga a el");
        }
    }

    /** Se nombro un establecimiento que no existe. */
    public static final class EstablecimientoDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        EstablecimientoDesconocido(String numero) {
            super(
                    "No hay ninguna licencia de funcionamiento "
                            + numero
                            + " en esta municipalidad. El anuncio puede no tener establecimiento"
                            + " —una valla en un terreno privado no lo tiene—, pero si se declara"
                            + " uno tiene que existir");
        }
    }
}
