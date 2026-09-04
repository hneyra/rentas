package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Escala y modo con los que se redondea un importe.
 *
 * <p>Existe porque <b>D-03 sigue abierta</b> en sus tres partes: no esta decidido con cuantos
 * decimales (D-03a) ni con que modo (D-03b) se redondea, ni —lo que mas pesa— en que <i>puntos</i>
 * del calculo se redondea (D-03c). Mientras no lo este, el codigo no puede fingir que lo sabe.
 *
 * <p>De ahi la forma de este tipo: es un <b>argumento</b>, no una constante. No hay aqui ninguna
 * instancia por omision, ni {@code ESCALA = 2}, ni {@code HALF_UP}. Quien redondea recibe la
 * politica de quien la configura, y el dia que D-03 se cierre habra exactamente un lugar donde
 * escribir la respuesta —los datos de parametrizacion— y ni una constante que buscar en el codigo.
 *
 * @param escala numero de decimales del resultado
 * @param modo modo de redondeo, de {@link RoundingMode}
 */
public record PoliticaDeRedondeo(int escala, RoundingMode modo) {

    public PoliticaDeRedondeo {
        Objects.requireNonNull(modo, "El modo de redondeo es obligatorio");
        if (escala < 0) {
            throw new IllegalArgumentException(
                    "La escala de redondeo no puede ser negativa: " + escala);
        }
        if (modo == RoundingMode.UNNECESSARY) {
            throw new IllegalArgumentException(
                    "UNNECESSARY no es una politica de redondeo: falla en cuanto haya un decimal"
                            + " que descartar, que es justo cuando se necesita redondear");
        }
    }

    /** Aplica la politica. Paquete adentro: quien redondea importes es {@link Dinero}. */
    BigDecimal aplicarA(BigDecimal valor) {
        return valor.setScale(escala, modo);
    }
}
