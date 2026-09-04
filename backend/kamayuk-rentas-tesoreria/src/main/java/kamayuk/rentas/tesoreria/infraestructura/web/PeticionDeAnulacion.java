package kamayuk.rentas.tesoreria.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/tesoreria/recibos/{nro}/anulacion} (RF-083).
 *
 * <p>Los nombres son los de los campos que la pantalla del prototipo declara —{@code motivo},
 * {@code autorizadoPor}, {@code nDeMemorando}— y el «Detalle» de esa misma pantalla es la {@code
 * observacion} que la regla 10 exige a toda escritura: no se publican los dos, porque serian dos
 * cajas de texto libre pidiendo lo mismo y una de las dos acabaria vacia.
 *
 * <p><b>El motivo y la observacion si son cosas distintas, y por eso son dos campos.</b> El motivo
 * es el sustento del acto administrativo: queda en el recibo y se imprime en su duplicado, para que
 * quien tenga el papel sepa por que dejo de valer. La observacion explica la operacion a quien lea
 * la bitacora.
 *
 * <p>Lo que <b>no</b> hay aqui es la casilla «devuelve la deuda a cuenta corriente» del prototipo.
 * No es un olvido: no es una opcion. Anular un recibo sin deshacer sus abonos dejaria el pago
 * asentado sobre un documento que ya no vale, y el contribuyente figuraria al corriente sin haber
 * pagado. La reversion va siempre.
 *
 * <p>Todo campo llega anulable a proposito: un cuerpo JSON puede venir incompleto, y el borde HTTP
 * es donde eso se rechaza con un mensaje que dice cual falta, no donde revienta un {@code
 * NullPointerException}.
 *
 * @param motivo el sustento del acto; obligatorio
 * @param autorizadoPor quien autorizo la anulacion, si consta
 * @param nDeMemorando el documento que la sustenta, si consta
 * @param observacion por que se anula (regla 10, RNF-052); obligatorio
 */
public record PeticionDeAnulacion(
        @Nullable String motivo,
        @Nullable String autorizadoPor,
        @Nullable String nDeMemorando,
        @Nullable String observacion) {}
