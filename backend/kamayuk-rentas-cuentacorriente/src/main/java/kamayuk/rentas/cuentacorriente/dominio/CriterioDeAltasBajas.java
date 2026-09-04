package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code consulta_altas_bajas} (RF-045): los movimientos de alta y baja de deuda de un
 * contribuyente.
 *
 * <p><b>Un alta o una baja es un acto, no cualquier movimiento del libro</b> (#640). Los dos que
 * hay son los de RF-043 —la «nota de abono»— y RF-044 —la «nota de cargo»—, y los estampan {@link
 * MovimientoDeDeuda#enAsientos} y, desde #662, {@code ExtincionDeDeudaCuentaCorriente}, que asienta
 * la misma baja cuando la ordena una resolucion de gerencia. Lo demas que escribe en el libro no
 * entra en esta relacion, y no basta con dejar fuera el concepto {@code PAGO}: el abono de una
 * <b>cobranza</b> es un {@code ABONO} de concepto {@code INSOLUTO}, exactamente el mismo asiento
 * que el de una baja, y el cargo con que esa cobranza cristaliza el interes devengado es igual que
 * el de un alta. Un cobro tiene su propia consulta (RF-048) y la emision no se audita aqui.
 *
 * <p>Consecuencia que conviene tener escrita: los asientos <b>anteriores a V68</b> nacieron sin
 * acto y no se pueden reparar, asi que una baja de antes de esa migracion no sale. Ver {@code
 * AsientoRepositoryJdbc#altasYBajas} para por que no se acota por fecha en su lugar.
 *
 * <p><b>Y desde #684 se puede preguntar por la causal</b>, que es la pregunta de quien audita como
 * se extingue deuda del municipio: «ensename las bajas por prescripcion». Antes no habia forma —la
 * causal viajaba dentro del texto de la observacion— y la unica salida era leerlas a ojo. Lo mismo
 * que con el acto: las bajas anteriores a V77 tienen la causal en nulo y al filtrar por una
 * concreta no aparecen; sin filtro salen todas.
 *
 * @param codigoContribuyente el titular; es lo que teclea quien atiende
 * @param ejercicio filtro opcional de año
 * @param tributo filtro opcional de tributo
 * @param sentido filtro opcional de «Alta / Baja»; {@code null} trae los dos
 * @param causal filtro opcional por la causal de la baja (#684); {@code null} trae todas. Acota
 *     solo bajas, porque un alta no tiene causal — y deja fuera las bajas anteriores a V77, que la
 *     tienen en nulo y no se pueden reparar
 */
public record CriterioDeAltasBajas(
        String codigoContribuyente,
        @Nullable Ejercicio ejercicio,
        @Nullable String tributo,
        @Nullable SentidoDelMovimiento sentido,
        @Nullable CausalDeBaja causal) {

    /** La forma anterior a #684, sin filtro de causal: trae todas. */
    public CriterioDeAltasBajas(
            String codigoContribuyente,
            @Nullable Ejercicio ejercicio,
            @Nullable String tributo,
            @Nullable SentidoDelMovimiento sentido) {
        this(codigoContribuyente, ejercicio, tributo, sentido, null);
    }

    public CriterioDeAltasBajas {
        Objects.requireNonNull(codigoContribuyente, "Las altas y bajas son de un contribuyente");
        codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
        if (codigoContribuyente.isEmpty()) {
            throw new IllegalArgumentException("El codigo de contribuyente no puede estar vacio");
        }
        if (tributo != null) {
            tributo = tributo.strip().toUpperCase(Locale.ROOT);
            if (tributo.isEmpty()) {
                tributo = null;
            }
        }
    }
}
