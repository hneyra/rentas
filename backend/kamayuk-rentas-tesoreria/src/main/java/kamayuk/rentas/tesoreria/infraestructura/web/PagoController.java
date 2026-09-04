package kamayuk.rentas.tesoreria.infraestructura.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
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

/**
 * El buzon de entrada de pagos y su conciliacion (P5D, ADR-0026 §3).
 *
 * <h2>Quien llama aqui, y por que no es una pantalla</h2>
 *
 * <p>Lo llama el publicador de la caja, despues de su {@code COMMIT}. No hay ningun funcionario
 * delante y no lo va a haber: esto es el otro extremo del outbox, y la unica pantalla relacionada
 * es la que muestra los pagos en transito dentro de la consulta de deuda.
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
     * El acceso con el que se recibe un pago.
     *
     * <p>Es {@code caja_tributaria} con {@code REGISTRO}: lo que este endpoint hace es exactamente
     * lo que la ventanilla hacia cuando el cobro era una sola transaccion —asentar el abono—, y
     * darle un permiso propio crearia una opcion de menu que nadie abre y que nadie administra.
     */
    private static final String ACCESO = "caja_tributaria";

    private final RecibirPago recibir;
    private final ConciliacionDePagos conciliacion;
    private final ObjectMapper json;
    private final Clock reloj;

    public PagoController(
            RecibirPago recibir, ConciliacionDePagos conciliacion, ObjectMapper json, Clock reloj) {
        this.recibir = recibir;
        this.conciliacion = conciliacion;
        this.json = json;
        this.reloj = reloj;
    }

    /** Recibe un pago de la caja y lo imputa. */
    @PostMapping
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<PagoResource> recibir(@RequestBody PeticionDePago peticion) {
        PagoRecibido pago = leer(peticion);
        RecibirPago.Recibido recibido = recibir.recibir(pago);
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
            return PagoRecibido.enTransito(
                    UUID.fromString(exigir(peticion.pagoId(), "pagoId")),
                    tipo,
                    tipo == TipoDePagoRecibido.PAGO_ANULADO
                            ? UUID.fromString(exigir(peticion.pagoOriginalId(), "pagoOriginalId"))
                            : null,
                    Objects.requireNonNullElse(peticion.sistemaOrigen(), "caja"),
                    exigir(recibo.numero(), "recibo.numero"),
                    pagador.idExterno(),
                    LocalDate.parse(exigir(recibo.fechaDePago(), "recibo.fechaDePago")),
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException noSePuede) {
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
     */
    public record PagoResource(
            String pagoId,
            String estado,
            int asientos,
            @Nullable String motivo,
            String recibidoEn,
            @Nullable String aplicadoEn,
            boolean nuevo) {

        static PagoResource de(PagoRecibido pago, boolean nuevo) {
            return new PagoResource(
                    pago.pagoId().toString(),
                    pago.estado().name(),
                    pago.asientos(),
                    pago.motivo(),
                    pago.recibidoEn().toString(),
                    pago.aplicadoEn() == null ? null : pago.aplicadoEn().toString(),
                    nuevo);
        }
    }

    /** Lo que la caja compara contra su cierre de turno. */
    public record ConciliacionResource(
            String fecha, int recibidos, int aplicados, int rechazados, String importeAplicado) {}
}
