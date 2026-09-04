package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /rentas/api/v1/pagos} (P5D, ADR-0026 §3). <b>Lista blanca</b>: lo que no
 * esta aqui no entra.
 *
 * <h2>Es un record, y eso costo una decision</h2>
 *
 * <p>La primera version leia el cuerpo como {@code String} para <b>congelarlo tal como llego</b>:
 * si un pago no cuadra, lo que hay que poder mirar es lo que el otro sistema dijo y no lo que este
 * entendio. Pero un cuerpo leido como texto <b>no tiene campos que enumerar</b>, y la regla de
 * arquitectura que compara la lista blanca de cada controlador con los parametros del contrato
 * pasaria en VERDE sin mirar nada — que es exactamente la forma en que esa regla dejaria de
 * proteger sin que nadie se entere.
 *
 * <p>Gana la lista blanca. Lo que se guarda en {@code pago_recibido.cuerpo} es este record
 * <b>reserializado</b>, y hay que decir qué se pierde: el byte exacto del emisor. Lo que se
 * conserva —y es lo que la conciliacion necesita— es cada campo que este sistema usa, con su valor.
 * Un campo que la caja mandara y este record no declarara se perderia en silencio, y por eso la
 * forma del evento se comprueba del otro lado, en las pruebas de la caja.
 *
 * @param pagoId el identificador que <b>genero la caja</b>; con el se deduplica
 * @param tipo {@code PAGO_REGISTRADO} o {@code PAGO_ANULADO}
 * @param pagoOriginalId el pago que una anulacion deshace
 * @param sistemaOrigen a que sistema iba, tal como la caja lo registro
 * @param total lo que el pago dice que se cobro, <b>como cadena</b> (RNF-055)
 * @param actualizadoA a que fecha estaba ese importe (regla 9)
 * @param motivo por que se anulo el recibo, en las palabras de quien lo autorizo en ventanilla;
 *     <b>solo en {@code PAGO_ANULADO}</b>, donde la caja lo exige (RNF-052)
 * @param fecha el dia en que se anulo; solo en {@code PAGO_ANULADO}. No es la del recibo, que viaja
 *     en {@code recibo.fechaDePago}: es la fecha valor con la que se reversa
 */
public record PeticionDePago(
        @Nullable String pagoId,
        @Nullable String tipo,
        @Nullable String pagoOriginalId,
        @Nullable String sistemaOrigen,
        @Nullable String total,
        @Nullable String actualizadoA,
        // LOS DOS DE LA ANULACION (C-1, desajustes 8 y 9). `ComponedorDeEventosJson.pagoAnulado`
        // los escribe desde siempre y este record no los declaraba, asi que Jackson los
        // descartaba y la caja recibia 201: el evento se marcaba ENTREGADO, el buzon se
        // vaciaba y el dato no llegaba. Y tampoco sobrevivian en el `cuerpo` congelado, que se
        // reserializa DESDE ESTE RECORD.
        @Nullable String motivo,
        @Nullable String fecha,
        @Nullable DatosDelRecibo recibo,
        @Nullable DatosDelPagador pagador,
        @Nullable List<LineaDeOrden> ordenes) {

    /** El papel que el administrado tiene en la mano. */
    public record DatosDelRecibo(
            @Nullable String numero,
            @Nullable String serie,
            @Nullable String fechaDePago,
            @Nullable String cajero,
            @Nullable String formaDePago) {}

    /**
     * Quien pago, como la caja lo conoce.
     *
     * <p>{@code idExterno} es el {@code contribuyente_id} de ESTE padron: es lo unico que permite
     * imputar sin volver a resolver a nadie. Anulable, porque la caja admite un pagador anonimo — y
     * un pago anonimo no se puede imputar, asi que queda RECHAZADO con su motivo.
     */
    public record DatosDelPagador(
            @Nullable String documento, @Nullable String nombre, @Nullable Long idExterno) {}

    /**
     * Una orden cobrada.
     *
     * <p>{@code referenciaExterna} es <b>opaca para la caja</b> y la compuso este sistema: es
     * {@code TRIBUTO|EJERCICIO|PREDIO|VEHICULO|FECHA}, y la lee {@code ReferenciaDeObligacion}. La
     * fecha va dentro porque es la regla 9 aplicada a la identidad de la orden: el mismo predial a
     * dos fechas distintas son dos importes y son dos ordenes.
     */
    public record LineaDeOrden(
            @Nullable Long ordenId,
            @Nullable String referenciaExterna,
            @Nullable String importe,
            @Nullable String actualizadoA) {}
}
