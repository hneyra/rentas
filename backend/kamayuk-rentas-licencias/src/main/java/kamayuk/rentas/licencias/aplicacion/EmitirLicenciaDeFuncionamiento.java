package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite una licencia de funcionamiento con sus giros CIIU, su papel y su movimiento de emision
 * (#44, RF-110, RF-111).
 *
 * <h2>Lo que la base decide, y lo que decide este codigo</h2>
 *
 * <ul>
 *   <li><b>La base</b>: que haya recibo ({@code recibo_id NOT NULL}), que haya documento ({@code
 *       documento_id NOT NULL}), que el numero no se repita ({@code licencia_numero_uq}), que haya
 *       un solo giro principal ({@code licencia_giro_principal_uq}) y que la emision no se registre
 *       dos veces ({@code licencia_movimiento_emision_uq}). Son las que un indice o un {@code
 *       CHECK} pueden expresar, y por eso van ahi: dos peticiones simultaneas pasan las dos por
 *       cualquier {@code if}.
 *   <li><b>Este codigo</b>: que el recibo sea de caja de tasas, no este anulado, sea del titular y
 *       cubra el concepto del TUPA que el conjunto sellado nombra —eso exige un {@code JOIN} contra
 *       otro contexto, y un {@code CHECK} no puede hacerlo—; y que los giros existan en el
 *       catalogo.
 * </ul>
 *
 * <h2>La licencia y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transaccion</b>, con {@link EmitirDocumento}, y guarda
 * los datos con que se dibujo mas el SHA-256 de lo que salio. Es lo que hace que un duplicado
 * pedido en 2034 sea el <b>mismo</b> papel (RF-132) y no uno nuevo con el mismo numero. Una
 * licencia sin documento no se puede entregar; un documento sin licencia no tiene acto que lo
 * explique.
 *
 * <h2>Ningun cargo en la cuenta corriente, y es una decision</h2>
 *
 * <p>Emitir una licencia <b>no</b> genera deuda. El derecho de tramite ya se pago en caja de tasas
 * —y por eso hay un recibo que comprobar—, y un derecho de tramite no es deuda tributaria: no se
 * determina, no devenga interes y no prescribe (ver {@code CobrarTasa}). Lo unico que una licencia
 * podria generar es la deuda de <b>arbitrios del establecimiento</b>, y esa la determina {@code
 * rentas} con las tablas de la ordenanza, que estan bloqueadas por D-02b. Cuando llegue, entrara
 * por {@code cuentacorriente.GeneradorDeCargos} como entra todo cargo de otro contexto (ARQ-01 §4
 * regla 2) — asi entra ya la tasa de anuncios (#51), que es por lo que el modulo depende hoy de
 * {@code cuentacorriente}; esta emision sigue sin tocarlo.
 */
@Service
public class EmitirLicenciaDeFuncionamiento {

    /** El {@code tipo} con que se guarda el papel de la licencia en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "LICENCIA_FUNCIONAMIENTO";

    private final LicenciaRepository licencias;
    private final MovimientoDeLicenciaRepository movimientos;
    private final CiiuRepository catalogo;
    private final RecibosDeTramite recibos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final LectorDeFichasEconomicas fichas;
    private final DerechosDeTramiteParametrizados derechos;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeLicencia plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirLicenciaDeFuncionamiento(
            LicenciaRepository licencias,
            MovimientoDeLicenciaRepository movimientos,
            CiiuRepository catalogo,
            RecibosDeTramite recibos,
            DirectorioDeContribuyentes contribuyentes,
            LectorDeFichasEconomicas fichas,
            DerechosDeTramiteParametrizados derechos,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeLicencia plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.licencias = licencias;
        this.movimientos = movimientos;
        this.catalogo = catalogo;
        this.recibos = recibos;
        this.contribuyentes = contribuyentes;
        this.fichas = fichas;
        this.derechos = derechos;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite la licencia.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Solicitud}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @throws TitularDesconocido si el codigo de contribuyente no esta en el padron
     * @throws GiroDesconocido si algun giro no esta en el catalogo CIIU
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho (RF-110)
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto del TUPA cobra el derecho
     */
    @Transactional
    public LicenciaEmitida emitir(
            Solicitud solicitud, FormatoDeDocumento formato, Observacion observacion) {

        Objects.requireNonNull(solicitud, "No se emite sin solicitud");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ResumenDeContribuyente titular =
                contribuyentes
                        .porCodigo(solicitud.codigoContribuyente())
                        .orElseThrow(() -> new TitularDesconocido(solicitud.codigoContribuyente()));

        String concepto = derechos.aLaFechaDe(solicitud.fechaEmision()).paraLaLicencia();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        solicitud.numeroDeRecibo(),
                        titular.id(),
                        concepto,
                        "registro de licencia de funcionamiento");

        List<GiroDeLaLicencia> giros = resolverGiros(solicitud);

        Ejercicio ejercicio = Ejercicio.de(solicitud.fechaEmision());
        String numero = plantilla.componer(ejercicio, licencias.siguienteCorrelativo(ejercicio));

        // La ficha economica se pide a `catastro` por su puerto publico, con la fecha de emision:
        // la licencia queda enlazada a la version que regia ese dia, no a «la ultima» (regla 9).
        Long fichaId =
                solicitud.predioId() == null
                        ? null
                        : fichas.fichaEconomicaVigenteEn(
                                        solicitud.predioId(), solicitud.fechaEmision())
                                .orElse(null);

        Instant ahora = reloj.instant();
        LicenciaDeFuncionamiento sinGuardar =
                new LicenciaDeFuncionamiento(
                        null,
                        numero,
                        titular.id(),
                        solicitud.predioId(),
                        fichaId,
                        solicitud.nombreComercial(),
                        solicitud.direccion(),
                        solicitud.areaSolicitada(),
                        solicitud.tipoLicencia(),
                        solicitud.zonificacion(),
                        solicitud.aforo(),
                        solicitud.fechaEmision(),
                        solicitud.vigenciaHasta(),
                        recibo.reciboId(),
                        // Se rellena abajo con el documento recien emitido: el papel se dibuja con
                        // los datos de la licencia, asi que la licencia tiene que existir como
                        // objeto antes que el, y el identificador del documento antes que la fila.
                        0L,
                        solicitud.expediente(),
                        solicitud.fechaExpediente(),
                        ahora,
                        null,
                        observacion,
                        giros);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        ejercicio,
                        numero,
                        ModeloDeLaLicencia.de(
                                sinGuardar,
                                titular.nombre(),
                                titular.codigo(),
                                titular.documento(),
                                recibo.numero(),
                                giros),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        LicenciaDeFuncionamiento guardada = licencias.emitir(conDocumento(sinGuardar, documentoId));

        MovimientoDeLicencia emisionRegistrada =
                movimientos.registrar(
                        MovimientoDeLicencia.emision(
                                guardada.identificador(),
                                solicitud.fechaEmision(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                solicitud.fechaEmision(),
                                "licencia_funcionamiento",
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada, recibo, giros)));

        return new LicenciaEmitida(guardada, emisionRegistrada, emision, titular);
    }

    // ------------------------------------------------------------------

    /**
     * Los giros pedidos, resueltos contra el catalogo.
     *
     * <p>Se leen <b>todos de una vez</b> y se comprueban aqui, antes de escribir nada: un giro que
     * no existe tiene que producir «ese giro no esta en el catalogo» y no un fallo de clave foranea
     * a mitad de la insercion, que no dice cual de los tres era.
     */
    private List<GiroDeLaLicencia> resolverGiros(Solicitud solicitud) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String codigo : solicitud.girosCiiu()) {
            codigos.add(codigo.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (!codigos.contains(solicitud.giroPrincipal())) {
            codigos.add(solicitud.giroPrincipal());
        }

        List<GiroDeLaLicencia> giros = new ArrayList<>(codigos.size());
        for (String codigo : codigos) {
            Ciiu giro = catalogo.porCodigo(codigo).orElseThrow(() -> new GiroDesconocido(codigo));
            if (!giro.activo()) {
                throw new GiroDesconocido(codigo);
            }
            giros.add(
                    new GiroDeLaLicencia(
                            giro.identificador(),
                            giro.codigo(),
                            giro.descripcion(),
                            codigo.equals(solicitud.giroPrincipal()),
                            true));
        }
        return giros;
    }

    private static LicenciaDeFuncionamiento conDocumento(
            LicenciaDeFuncionamiento licencia, long documentoId) {
        return new LicenciaDeFuncionamiento(
                licencia.id(),
                licencia.numero(),
                licencia.contribuyenteId(),
                licencia.predioId(),
                licencia.fichaId(),
                licencia.nombreComercial(),
                licencia.direccion(),
                licencia.areaSolicitada(),
                licencia.tipoLicencia(),
                licencia.zonificacion(),
                licencia.aforo(),
                licencia.fechaEmision(),
                licencia.vigenciaHasta(),
                licencia.reciboId(),
                documentoId,
                licencia.expediente(),
                licencia.fechaExpediente(),
                licencia.registradoEn(),
                licencia.usuarioRegistro(),
                licencia.observacion(),
                licencia.giros());
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            LicenciaDeFuncionamiento licencia,
            ReciboDeTramite recibo,
            List<GiroDeLaLicencia> giros) {
        return "{\"numero\":\""
                + licencia.numero()
                + "\",\"tipo\":\""
                + licencia.tipoLicencia()
                + "\",\"recibo\":\""
                + recibo.numero()
                + "\",\"giros\":"
                + giros.size()
                + ",\"fichaEconomica\":"
                + (licencia.fichaId() == null ? "null" : licencia.fichaId())
                + "}";
    }

    // ------------------------------------------------------------------

    /**
     * Lo que se pide para emitir una licencia.
     *
     * @param codigoContribuyente el titular, tal como lo teclea la pantalla
     * @param predioId el establecimiento; opcional
     * @param nombreComercial la denominacion comercial
     * @param direccion la direccion del establecimiento
     * @param areaSolicitada el area declarada
     * @param tipoLicencia definitiva, temporal o cesionaria
     * @param zonificacion la zona declarada
     * @param aforo el aforo autorizado
     * @param fechaEmision el dia de la emision; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando rige
     * @param numeroDeRecibo el numero impreso del recibo del derecho, como esta en el papel
     * @param girosCiiu los codigos CIIU autorizados
     * @param giroPrincipal cual de ellos es la actividad principal
     * @param expediente el numero del expediente del tramite
     * @param fechaExpediente su fecha
     */
    public record Solicitud(
            String codigoContribuyente,
            @Nullable Long predioId,
            String nombreComercial,
            String direccion,
            AreaM2 areaSolicitada,
            TipoDeLicencia tipoLicencia,
            @Nullable String zonificacion,
            @Nullable Integer aforo,
            LocalDate fechaEmision,
            @Nullable LocalDate vigenciaHasta,
            String numeroDeRecibo,
            List<String> girosCiiu,
            String giroPrincipal,
            @Nullable String expediente,
            @Nullable LocalDate fechaExpediente) {

        public Solicitud {
            Objects.requireNonNull(codigoContribuyente, "La licencia es de un titular");
            Objects.requireNonNull(nombreComercial, "La licencia necesita su denominacion");
            Objects.requireNonNull(direccion, "La licencia necesita su direccion");
            Objects.requireNonNull(areaSolicitada, "La licencia necesita el area declarada");
            Objects.requireNonNull(tipoLicencia, "La licencia necesita su tipo");
            Objects.requireNonNull(fechaEmision, "La fecha entra como argumento (regla 6)");
            Objects.requireNonNull(numeroDeRecibo, "Sin recibo no hay licencia (RF-110)");
            Objects.requireNonNull(girosCiiu, "La lista de giros es vacia, no nula");
            Objects.requireNonNull(giroPrincipal, "Hay que decir cual es la actividad principal");
            girosCiiu = List.copyOf(girosCiiu);
            giroPrincipal = giroPrincipal.strip().toUpperCase(java.util.Locale.ROOT);
            if (giroPrincipal.isEmpty()) {
                throw new IllegalArgumentException(
                        "La actividad principal decide el riesgo de la ITSE: no puede faltar");
            }
        }
    }

    /**
     * La licencia recien emitida, su movimiento, su papel y su titular.
     *
     * @param licencia la fila guardada, con sus giros
     * @param emision el movimiento de emision
     * @param documento los bytes del papel y el registro que los respalda
     * @param titular el resumen del padron, para que el borde no lo tenga que releer
     */
    public record LicenciaEmitida(
            LicenciaDeFuncionamiento licencia,
            MovimientoDeLicencia emision,
            EmitirDocumento.Emision documento,
            ResumenDeContribuyente titular) {}

    /** El codigo de contribuyente no esta en el padron de esta municipalidad. */
    public static final class TitularDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TitularDesconocido(String codigo) {
            super(
                    "No hay ningun contribuyente con codigo '"
                            + codigo
                            + "' en esta municipalidad: una licencia se emite a un titular del"
                            + " padron");
        }
    }

    /** Ese giro no esta en el catalogo CIIU, o esta dado de baja. */
    public static final class GiroDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        GiroDesconocido(String codigo) {
            super(
                    "El giro '"
                            + codigo
                            + "' no esta activo en el catalogo CIIU de esta municipalidad. El"
                            + " catalogo se mantiene en la opcion `ciiu` y es extensible (RF-112)");
        }
    }
}
