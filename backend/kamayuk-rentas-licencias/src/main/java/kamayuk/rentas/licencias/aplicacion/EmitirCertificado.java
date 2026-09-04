package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.CertificadoRepository;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeCertificado;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import kamayuk.rentas.tesoreria.TasaCobrada;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite un certificado de numeracion, zonificacion, parametros urbanisticos o jurisdiccion, con su
 * papel; y lo vuelve a sacar identico cuando se pide (#54, RF-115, RF-132).
 *
 * <h2>Lo que la base decide, y lo que decide este codigo</h2>
 *
 * <ul>
 *   <li><b>La base</b>: que haya recibo ({@code recibo_id NOT NULL}), que haya papel ({@code
 *       documento_id NOT NULL}), que el numero no se repita ({@code certificado_numero_uq}), que el
 *       papel no se comparta ({@code certificado_documento_uq}), que no caduque antes de emitirse
 *       ({@code certificado_vigencia_ck}) y que un reintento no produzca un segundo certificado
 *       ({@code certificado_idempotencia_uq}). Son las que un indice o un {@code CHECK} pueden
 *       expresar, y por eso van ahi: diez peticiones simultaneas pasan las diez por cualquier
 *       {@code if}.
 *   <li><b>Este codigo</b>: que el recibo sea de caja de tasas, no este anulado, sea del
 *       solicitante y cubra el concepto del TUPA que el conjunto sellado nombra —eso exige leer
 *       otro contexto, y un {@code CHECK} no puede hacerlo—; y que el predio sea de quien lo pide.
 * </ul>
 *
 * <h2>El derecho se comprueba contra {@code tesoreria}, por sus DOS puertos publicos</h2>
 *
 * <p>Y hacen falta los dos, que no es una duplicacion:
 *
 * <ul>
 *   <li>{@link RecibosDeTramite} da el <b>identificador</b> del recibo —{@code
 *       certificado.recibo_id} es una clave foranea a {@code recibo}, asi que quien enlaza lo
 *       necesita— y permite distinguir las cuatro causas de rechazo con mensajes distintos, que es
 *       lo que {@link ComprobacionDelDerecho} escribio una sola vez para #44 y #48.
 *   <li>{@link CobrosDeTasas} da el <b>importe</b> que ese recibo cobro por ese concepto, con su
 *       fecha. Es la columna «Derecho S/» de la grilla y la unica cifra del papel, y es lo que #44
 *       no necesitaba: una licencia no imprime importes.
 * </ul>
 *
 * <p>Ninguno de los dos lee la tabla {@code recibo}: eso obligaria a este contexto a saber que la
 * anulacion vive en {@code recibo_movimiento} desde #34, y el primero que lo olvidara emitiria un
 * certificado con un recibo anulado.
 *
 * <h2>Ningun cargo en la cuenta corriente</h2>
 *
 * <p>Emitir un certificado <b>no</b> genera deuda, por lo mismo que no la genera emitir una
 * licencia (#44): el derecho de tramite ya se pago en caja de tasas —y por eso hay un recibo que
 * comprobar— y un derecho de tramite no es deuda tributaria. No se determina, no devenga interes y
 * no prescribe.
 *
 * <h2>La reimpresion no vuelve a componer el papel</h2>
 *
 * <p>{@link #reimprimir} pasa por {@link EmitirDocumento#reimprimir}, que <b>vuelve a dibujar los
 * datos guardados</b> y comprueba el SHA-256 antes de entregarlos. No recompone el modelo con lo
 * que hoy dicen el padron, el plano de zonificacion o el TUPA: si lo hiciera, el papel de 2034
 * diria cosas distintas del que el administrado tiene en la mano, con el mismo numero encima.
 */
@Service
public class EmitirCertificado {

    /** El {@code tipo} con que se guarda el papel del certificado en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "CERTIFICADO";

    private final CertificadoRepository certificados;
    private final DirectorioDeContribuyentes contribuyentes;
    private final PrediosDelContribuyente predios;
    private final RecibosDeTramite recibos;
    private final CobrosDeTasas cobros;
    private final DerechosDeTramiteParametrizados derechos;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeCertificado plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirCertificado(
            CertificadoRepository certificados,
            DirectorioDeContribuyentes contribuyentes,
            PrediosDelContribuyente predios,
            RecibosDeTramite recibos,
            CobrosDeTasas cobros,
            DerechosDeTramiteParametrizados derechos,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeCertificado plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.certificados = certificados;
        this.contribuyentes = contribuyentes;
        this.predios = predios;
        this.recibos = recibos;
        this.cobros = cobros;
        this.derechos = derechos;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite el certificado y su papel, en la misma transaccion.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Solicitud}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @param claveDeIdempotencia la cabecera {@code idempotency-key} del cliente; opcional
     * @throws SolicitanteDesconocido si el codigo de contribuyente no esta en el padron
     * @throws PredioAjeno si el predio no existe o no es del solicitante a esa fecha
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto cobra el derecho o cuantos meses vale el certificado
     */
    @Transactional
    public Emision emitir(
            Solicitud solicitud,
            @Nullable String claveDeIdempotencia,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(solicitud, "No se emite sin solicitud");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        // El reintento del cliente, atendido antes de consumir un correlativo. La GARANTIA sigue
        // siendo `certificado_idempotencia_uq`: entre esta lectura y el INSERT cabe otra peticion,
        // y por eso el indice esta ademas de la lectura.
        if (claveDeIdempotencia != null && !claveDeIdempotencia.isBlank()) {
            Optional<Certificado> yaEmitido =
                    certificados.porClaveDeIdempotencia(claveDeIdempotencia.strip());
            if (yaEmitido.isPresent()) {
                Certificado anterior = yaEmitido.get();
                // SIN papel, y a proposito. Volver a dibujarlo aqui exigiria pasar por
                // `EmitirDocumento.reimprimir`, que lo marcaria «DUPLICADO N.o 1» y contaria una
                // reimpresion que nadie pidio: el cliente no pidio un duplicado, reintento la misma
                // peticion. Quien de verdad perdio el papel lo pide por su ruta de impresion, y
                // entonces si sale marcado —que es lo correcto, porque puede haber otra copia—.
                return new Emision(
                        anterior,
                        null,
                        /* yaExistia= */ true,
                        resumenDelSolicitante(anterior.contribuyenteId()));
            }
        }

        ResumenDeContribuyente solicitante =
                contribuyentes
                        .porCodigo(solicitud.codigoContribuyente())
                        .orElseThrow(
                                () -> new SolicitanteDesconocido(solicitud.codigoContribuyente()));

        PredioDelContribuyente predio =
                predioDe(solicitante.id(), solicitud.codigoPredial(), solicitud.fechaEmision());

        DerechosDeTramiteParametrizados.Vigentes vigentes =
                derechos.aLaFechaDe(solicitud.fechaEmision());
        String concepto = vigentes.paraElCertificado(solicitud.tipo());
        int meses = vigentes.mesesDeVigenciaDelCertificado(solicitud.tipo());

        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        solicitud.numeroDeRecibo(),
                        solicitante.id(),
                        concepto,
                        solicitud.tipo().etiqueta().toLowerCase(java.util.Locale.ROOT));

        TasaCobrada cobrado =
                cobros.acreditar(recibo.numero(), concepto)
                        .orElseThrow(
                                () ->
                                        new ComprobacionDelDerecho.DerechoNoPagado(
                                                "El recibo "
                                                        + recibo.numero()
                                                        + " figura con el concepto "
                                                        + concepto
                                                        + " pero la caja no lo acredita como una"
                                                        + " linea de tasa vigente: sin importe"
                                                        + " acreditado no se puede consignar el"
                                                        + " derecho en el certificado"));

        Ejercicio ejercicio = Ejercicio.de(solicitud.fechaEmision());
        String numero =
                plantilla.componer(
                        solicitud.tipo(),
                        ejercicio,
                        certificados.siguienteCorrelativo(solicitud.tipo(), ejercicio));

        Instant ahora = reloj.instant();
        Certificado sinGuardar =
                new Certificado(
                        null,
                        numero,
                        solicitud.tipo(),
                        predio.predioId(),
                        solicitante.id(),
                        predio.codigoReferenciaCatastral(),
                        predio.direccion(),
                        solicitud.expediente(),
                        solicitud.fechaEmision(),
                        solicitud.fechaEmision().plusMonths(meses),
                        recibo.reciboId(),
                        cobrado.importe(),
                        cobrado.fecha(),
                        // Se rellenan abajo con el documento recien emitido: el papel se dibuja con
                        // los datos del certificado, asi que el certificado tiene que existir como
                        // objeto antes que el, y el documento antes que la fila.
                        0L,
                        "",
                        solicitud.parametros(),
                        vacioAnulo(claveDeIdempotencia),
                        ahora,
                        null,
                        observacion);

        EmitirDocumento.Emision papel =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        ejercicio,
                        numero,
                        ModeloDelCertificado.de(
                                sinGuardar, solicitante.nombre(), solicitante.codigo()),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        papel.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        Certificado guardado =
                certificados.emitir(conPapel(sinGuardar, documentoId, papel.registro().numero()));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                solicitud.fechaEmision(),
                                "certificado",
                                String.valueOf(guardado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado, recibo, concepto)));

        return new Emision(guardado, papel, /* yaExistia= */ false, solicitante);
    }

    /**
     * Vuelve a sacar un certificado ya emitido, con su numero original (AC 2 de #54).
     *
     * <p>Lleva {@link Observacion} porque <b>escribe</b>: {@code EmitirDocumento.reimprimir}
     * incrementa el contador de reimpresiones del documento y deja su fila de auditoria. Sin la
     * observacion, un certificado se podria volver a entregar sin que nadie tuviera que decir por
     * que, y el papel duplicado circula igual que el original (regla 10, RNF-052).
     *
     * <p>El formato se elige al reimprimir y <b>no</b> tiene que ser el de la emision: quien
     * recibio un PDF tiene derecho a pedir la misma emision en hoja de calculo (RF-132). Lo que no
     * cambia es el contenido, que sale de los datos guardados.
     *
     * @throws CertificadoInexistente si no hay ningun certificado con ese numero
     * @throws EmitirDocumento.LaReimpresionNoCoincide si dibujar los datos guardados ya no da los
     *     mismos bytes que cuando se emitio
     */
    @Transactional
    public Emision reimprimir(String numero, FormatoDeDocumento formato, Observacion observacion) {
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Certificado certificado =
                certificados
                        .porNumero(numero == null ? "" : numero.strip())
                        .orElseThrow(() -> new CertificadoInexistente(numero));

        EmitirDocumento.Emision papel =
                documentos.reimprimir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(certificado.fechaEmision()),
                        certificado.documentoNumero(),
                        formato,
                        observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                // La fecha de la fila de auditoria es la de HOY —la reimpresion
                                // ocurre hoy—, y sale del reloj inyectado para que caiga en la
                                // particion del ejercicio correcto. Lo que NO sale del reloj es la
                                // fecha impresa en el papel: esa es la de la emision, guardada.
                                LocalDate.now(reloj),
                                "certificado",
                                String.valueOf(certificado.identificador()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(null, descripcionDeLaReimpresion(certificado, papel)));

        return new Emision(
                certificado,
                papel,
                /* yaExistia= */ true,
                resumenDelSolicitante(certificado.contribuyenteId()));
    }

    // ------------------------------------------------------------------

    /**
     * El predio del solicitante con ese codigo, a la fecha del tramite.
     *
     * <p>Se resuelve contra {@code catastro} por su puerto publico y <b>filtrando por el
     * solicitante</b>, no por el codigo suelto. Los dos motivos son distintos y los dos cuentan:
     * este contexto no puede unir {@code certificado} con {@code predio} en un {@code JOIN} sin
     * cruzar el limite que Spring Modulith vigila; y un certificado de numeracion se le entrega al
     * <b>titular</b> del predio, no a quien teclee su codigo.
     */
    private PredioDelContribuyente predioDe(
            long solicitanteId, String codigoPredial, LocalDate fecha) {
        String buscado = codigoPredial.strip();
        for (PredioDelContribuyente predio : predios.de(solicitanteId, fecha)) {
            if (predio.codigoReferenciaCatastral().equals(buscado)) {
                return predio;
            }
        }
        throw new PredioAjeno(buscado, fecha);
    }

    private ResumenDeContribuyente resumenDelSolicitante(long contribuyenteId) {
        return contribuyentes
                .porIds(java.util.Set.of(contribuyenteId))
                .getOrDefault(
                        contribuyenteId, new ResumenDeContribuyente(contribuyenteId, "", "", ""));
    }

    private static Certificado conPapel(
            Certificado certificado, long documentoId, String documentoNumero) {
        return new Certificado(
                certificado.id(),
                certificado.numero(),
                certificado.tipo(),
                certificado.predioId(),
                certificado.contribuyenteId(),
                certificado.codigoPredial(),
                certificado.direccion(),
                certificado.expediente(),
                certificado.fechaEmision(),
                certificado.vigenciaHasta(),
                certificado.reciboId(),
                certificado.derecho(),
                certificado.derechoA(),
                documentoId,
                documentoNumero,
                certificado.parametros(),
                certificado.claveIdempotencia(),
                certificado.registradoEn(),
                certificado.usuarioRegistro(),
                certificado.observacion());
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            Certificado certificado, ReciboDeTramite recibo, String concepto) {
        return "{\"numero\":\""
                + certificado.numero()
                + "\",\"tipo\":\""
                + certificado.tipo()
                + "\",\"recibo\":\""
                + recibo.numero()
                + "\",\"concepto\":\""
                + concepto
                + "\",\"vigenciaHasta\":\""
                + certificado.vigenciaHasta()
                + "\",\"derecho\":\""
                + certificado.derecho().valor().toPlainString()
                + "\",\"derechoA\":\""
                + certificado.derechoA()
                + "\"}";
    }

    private static String descripcionDeLaReimpresion(
            Certificado certificado, EmitirDocumento.Emision papel) {
        return "{\"numero\":\""
                + certificado.numero()
                + "\",\"documento\":\""
                + papel.registro().numero()
                + "\",\"reimpresiones\":"
                + papel.registro().reimpresiones()
                + "}";
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    // ------------------------------------------------------------------

    /**
     * Lo que se pide para emitir un certificado.
     *
     * @param tipo que se certifica
     * @param codigoContribuyente el solicitante, tal como lo teclea la pantalla
     * @param codigoPredial el codigo de referencia catastral del predio
     * @param expediente el numero del expediente del tramite; opcional
     * @param fechaEmision el dia de la emision; entra como argumento (regla 6)
     * @param numeroDeRecibo el numero impreso del recibo del derecho, como esta en el papel
     * @param parametros los parametros urbanisticos que se certifican; vacios en numeracion
     */
    public record Solicitud(
            TipoDeCertificado tipo,
            String codigoContribuyente,
            String codigoPredial,
            @Nullable String expediente,
            LocalDate fechaEmision,
            String numeroDeRecibo,
            ParametrosUrbanisticos parametros) {

        public Solicitud {
            Objects.requireNonNull(tipo, "Hay que decir que se certifica");
            Objects.requireNonNull(codigoContribuyente, "El certificado es de un solicitante");
            Objects.requireNonNull(codigoPredial, "El certificado es sobre un predio");
            Objects.requireNonNull(fechaEmision, "La fecha entra como argumento (regla 6)");
            Objects.requireNonNull(numeroDeRecibo, "Sin recibo no hay certificado (RF-115)");
            Objects.requireNonNull(parametros, "Los parametros son vacios, no nulos");
        }
    }

    /**
     * El certificado y su papel.
     *
     * @param certificado la fila guardada
     * @param documento los bytes del papel y el registro que los respalda; <b>nulo</b> en el
     *     reintento idempotente, donde no se dibuja nada nuevo
     * @param yaExistia si el certificado no se emitio ahora: un reintento idempotente o una
     *     reimpresion
     * @param solicitante el resumen del padron, para que el borde no lo tenga que releer
     */
    public record Emision(
            Certificado certificado,
            EmitirDocumento.@Nullable Emision documento,
            boolean yaExistia,
            ResumenDeContribuyente solicitante) {}

    /** El codigo de contribuyente no esta en el padron de esta municipalidad. */
    public static final class SolicitanteDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SolicitanteDesconocido(String codigo) {
            super(
                    "No hay ningun contribuyente con codigo '"
                            + codigo
                            + "' en esta municipalidad: un certificado se emite a un solicitante"
                            + " del padron");
        }
    }

    /** El predio no existe, o a esa fecha no era del solicitante. */
    public static final class PredioAjeno extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioAjeno(String codigoPredial, LocalDate fecha) {
            super(
                    "El predio "
                            + codigoPredial
                            + " no figura como del solicitante al "
                            + fecha
                            + ". Un certificado de numeracion o de zonificacion se le entrega al"
                            + " titular del predio: si la titularidad cambio, el certificado lo"
                            + " pide el titular vigente");
        }
    }

    /** Se pidio reimprimir un certificado que no existe. */
    public static final class CertificadoInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CertificadoInexistente(@Nullable String numero) {
            super(
                    "No hay ningun certificado numero '"
                            + (numero == null ? "" : numero)
                            + "' en esta municipalidad");
        }
    }
}
