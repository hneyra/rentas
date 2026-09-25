package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.autorizacion.RequiereIdentidadDeServicio;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.ZonaHoraria;
import kamayuk.rentas.tesoreria.pagos.ConciliacionDePagos;
import kamayuk.rentas.tesoreria.pagos.PagoRecibido;
import kamayuk.rentas.tesoreria.pagos.PagoRecibidoRepository;
import kamayuk.rentas.tesoreria.pagos.RecibirPago;
import kamayuk.rentas.tesoreria.pagos.ReferenciaDeObligacion;
import kamayuk.rentas.tesoreria.pagos.TipoDePagoRecibido;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * El buzon de entrada de pagos y su conciliacion (P5D, ADR-0026 §3).
 *
 * <h2>Quien llama aqui, y por que no es una pantalla</h2>
 *
 * <p>Lo llama el publicador de la caja, despues de su {@code COMMIT}. No hay ningun funcionario
 * delante y no lo va a haber: esto es el otro extremo del outbox, y la unica pantalla relacionada
 * es la que muestra los pagos en transito dentro de la consulta de deuda.
 *
 * <p>Y desde #429 no es solo una descripcion: las dos operaciones exigen la cuenta de servicio de
 * la caja ({@code @RequiereIdentidadDeServicio}), y un token de usuario —aunque tenga {@code
 * caja_tributaria} con los siete privilegios— recibe 403 {@code SIN_IDENTIDAD_DE_SERVICIO} antes de
 * que se lea el cuerpo.
 *
 * <h2>El codigo de estado dice si el pago era nuevo, y eso importa</h2>
 *
 * <p><b>201</b> cuando se recibio por primera vez, <b>409</b> cuando ya estaba. El 409 no es un
 * error: es «ya lo tengo», y el cliente lo trata como exito —la caja lo hace explicitamente—. Que
 * se distingan es lo que permite que el publicador reintente sin miedo y que la conciliacion pueda
 * decir cuantos reintentos hubo.
 *
 * <p>Devolver 201 siempre haria que un reintento se leyera como un pago nuevo, y contar filas seria
 * la unica forma de saber la verdad.
 */
@RestController
@RequestMapping(Api.RAIZ + "/pagos")
public class PagoController {

    /**
     * El acceso con el que se recibe un pago, que es la <b>segunda</b> condicion y no la primera.
     *
     * <p>Es {@code caja_tributaria}, y no un acceso propio: un permiso nuevo seria una opcion de
     * menu que nadie abre, y {@code identidad} la concederia a quien el administrador decidiera,
     * persona incluida. Pero el permiso <b>no basta</b>, y hasta #429 era lo unico que se pedia. La
     * premisa de entonces —«este endpoint hace lo que la ventanilla hacia: asentar el abono»— era
     * falsa: en la ventanilla el abono nacia en la misma transaccion que el recibo, con su
     * correlativo y dentro del arqueo; aqui nace de la palabra de quien llama —el numero del
     * recibo, su fecha, el total y las ordenes vienen en el cuerpo—. Con solo el permiso, un cajero
     * con {@code caja_tributaria} podia extinguir deuda sin dinero, eligiendo una fecha anterior al
     * vencimiento para que saliera sin interes, o anular un cobro real y revivir la deuda.
     *
     * <p>Por eso la primera condicion es {@link #SISTEMA}: quien llama tiene que ser la caja.
     */
    private static final String ACCESO = "caja_tributaria";

    /**
     * El unico sistema que llama a este borde, con su cuenta de servicio {@code
     * kamayuk-caja-servicio-<ubigeo>} por {@code client_credentials} (#429, ADR-0028 §2).
     */
    private static final String SISTEMA = "caja";

    private final RecibirPago recibir;
    private final ConciliacionDePagos conciliacion;
    private final JsonMapper json;
    private final Clock reloj;

    public PagoController(
            RecibirPago recibir, ConciliacionDePagos conciliacion, JsonMapper json, Clock reloj) {
        this.recibir = recibir;
        this.conciliacion = conciliacion;
        this.json = json;
        this.reloj = reloj;
    }

    /**
     * Recibe un pago de la caja y lo imputa.
     *
     * <p><b>503 si es una anulacion que llego antes que su cobro</b> (#428). No es 201 —para la
     * caja un 201 es «entregado», y la anulacion no se registro— ni 409 —«ya lo tengo», que tampoco
     * es cierto— ni 422 —la peticion esta bien, y reintentarla SI cambia el resultado—. El
     * publicador de la caja reintenta cualquier 5xx y entrega el cobro antes que su anulacion, asi
     * que la vuelta siguiente la encuentra con su cobro ya imputado; y si agota los intentos, la da
     * por muerta con alerta, que deja el caso a la vista en vez de cerrado en falso.
     */
    @PostMapping
    @RequiereIdentidadDeServicio(sistema = SISTEMA)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<PagoResource> recibir(@RequestBody PeticionDePago peticion) {
        PagoRecibido pago = leer(peticion);
        RecibirPago.Recibido recibido;
        try {
            recibido = recibir.recibir(pago);
        } catch (RecibirPago.AnulacionAntesQueSuCobro todaviaNo) {
            throw new ProblemaDeNegocio(CodigoDeError.SERVICIO_NO_DISPONIBLE, mensajeDe(todaviaNo));
        }
        HttpStatus estado = recibido.nuevo() ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(estado)
                .body(PagoResource.de(recibido.pago(), recibido.nuevo()));
    }

    /**
     * Lo que este sistema aplico un dia, para que la caja lo concilie contra su cierre.
     *
     * <p>Es la <b>unica</b> lectura que la caja hace de este sistema, y no esta en el camino del
     * cobro: si no contesta, la conciliacion de ese dia no se cierra y la ventanilla sigue cobrando
     * igual.
     */
    @GetMapping("/conciliacion")
    @RequiereIdentidadDeServicio(sistema = SISTEMA)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.LECTURA)
    public ConciliacionResource conciliacion(@RequestParam String fecha) {
        LocalDate dia;
        try {
            dia = LocalDate.parse(fecha.strip());
        } catch (DateTimeParseException malEscrita) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El parametro 'fecha' no es una fecha ISO: " + fecha);
        }
        PagoRecibidoRepository.Recuento recuento = conciliacion.delDia(dia);
        return new ConciliacionResource(
                dia.toString(),
                recuento.recibidos(),
                recuento.aplicados(),
                recuento.rechazados(),
                recuento.importeAplicado().valor().toPlainString());
    }

    // ------------------------------------------------------------------

    /**
     * Lee el evento que la caja publico.
     *
     * <p>El cuerpo se guarda <b>reserializado desde el record</b>, no tal como llego. Ver el
     * javadoc de {@link PeticionDePago}: gana la lista blanca del borde, y lo que se pierde es el
     * byte exacto del emisor.
     */
    private PagoRecibido leer(PeticionDePago peticion) {
        try {
            TipoDePagoRecibido tipo = TipoDePagoRecibido.valueOf(exigir(peticion.tipo(), "tipo"));
            PeticionDePago.DatosDelRecibo recibo =
                    Objects.requireNonNullElse(
                            peticion.recibo(),
                            new PeticionDePago.DatosDelRecibo(null, null, null, null, null));
            List<ReferenciaDeObligacion> obligaciones = new ArrayList<>();
            for (PeticionDePago.LineaDeOrden orden :
                    Objects.requireNonNullElse(
                            peticion.ordenes(), List.<PeticionDePago.LineaDeOrden>of())) {
                obligaciones.add(
                        ReferenciaDeObligacion.leer(
                                exigir(orden.referenciaExterna(), "ordenes[].referenciaExterna")));
            }
            PeticionDePago.DatosDelPagador pagador =
                    Objects.requireNonNullElse(
                            peticion.pagador(),
                            new PeticionDePago.DatosDelPagador(null, null, null));
            boolean esAnulacion = tipo == TipoDePagoRecibido.PAGO_ANULADO;
            return PagoRecibido.enTransito(
                    UUID.fromString(exigir(peticion.pagoId(), "pagoId")),
                    tipo,
                    esAnulacion
                            ? UUID.fromString(exigir(peticion.pagoOriginalId(), "pagoOriginalId"))
                            : null,
                    Objects.requireNonNullElse(peticion.sistemaOrigen(), "caja"),
                    exigir(recibo.numero(), "recibo.numero"),
                    pagador.idExterno(),
                    LocalDate.parse(exigir(recibo.fechaDePago(), "recibo.fechaDePago")),
                    // Los dos SOLO de la anulacion, y EXIGIDOS ahi (C-1). Este es el borde
                    // donde llega el cuerpo de la caja, o sea el sitio donde el invariante se
                    // puede sostener en las dos direcciones: `PagoRecibido` solo puede
                    // sostener una, porque tambien reconstruye filas anteriores a `V10`.
                    esAnulacion ? exigir(peticion.motivo(), "motivo") : null,
                    esAnulacion ? LocalDate.parse(exigir(peticion.fecha(), "fecha")) : null,
                    // El importe llega como CADENA (RNF-055): leerlo como numero de coma flotante
                    // volveria a introducir por la puerta de atras el defecto que el serializador
                    // evita.
                    Dinero.de(exigir(peticion.total(), "total")),
                    obligaciones,
                    congelar(peticion),
                    reloj.instant());
        } catch (ReferenciaDeObligacion.ReferenciaIlegible ilegible) {
            // Sale como 422: si la referencia no se puede leer, el pago no se puede ni guardar con
            // sentido, y el publicador tiene que saber que reintentarlo no sirve.
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(ilegible));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malFormado) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El cuerpo del pago no tiene la forma que publica la caja: "
                            + mensajeDe(malFormado));
        }
    }

    private String congelar(PeticionDePago peticion) {
        try {
            return json.writeValueAsString(peticion);
        } catch (tools.jackson.core.JacksonException noSePuede) {
            // No puede pasar con un record de campos simples. Si pasara, el pago NO se guarda: un
            // pago sin cuerpo no se puede conciliar ni explicar.
            throw new IllegalStateException("No se pudo congelar el cuerpo del pago", noSePuede);
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static String mensajeDe(Exception problema) {
        String mensaje = problema.getMessage();
        return mensaje == null ? problema.getClass().getSimpleName() : mensaje;
    }

    /**
     * @param nuevo si este pago llego por primera vez. Va en el cuerpo ADEMAS de en el codigo de
     *     estado: un cliente que solo mire el cuerpo tiene que poder distinguirlo igual
     * @param recibidoEn cuando llego, con el desfase de la zona del producto —{@code
     *     2026-03-15T19:00:00-05:00}—. Hasta {@code rentas}#327 era un {@code String} escrito con
     *     {@code Instant.toString()}, o sea UTC con una {@code Z}: el contrato lo tipaba «texto» y
     *     la guarda de #188, que mira el tipo, no lo veia
     * @param aplicadoEn cuando se aplico, igual; nulo mientras no se aplique
     */
    public record PagoResource(
            String pagoId,
            String estado,
            int asientos,
            @Nullable String motivo,
            OffsetDateTime recibidoEn,
            @Nullable OffsetDateTime aplicadoEn,
            boolean nuevo) {

        static PagoResource de(PagoRecibido pago, boolean nuevo) {
            Instant aplicadoEn = pago.aplicadoEn();
            return new PagoResource(
                    pago.pagoId().toString(),
                    pago.estado().name(),
                    pago.asientos(),
                    pago.motivo(),
                    ZonaHoraria.conSuDesfase(pago.recibidoEn()),
                    aplicadoEn == null ? null : ZonaHoraria.conSuDesfase(aplicadoEn),
                    nuevo);
        }
    }

    /** Lo que la caja compara contra su cierre de turno. */
    public record ConciliacionResource(
            String fecha, int recibidos, int aplicados, int rechazados, String importeAplicado) {}
}
