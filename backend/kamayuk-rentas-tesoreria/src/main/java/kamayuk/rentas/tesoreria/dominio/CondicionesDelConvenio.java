package kamayuk.rentas.tesoreria.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;

/**
 * Bajo que condiciones se fracciona: el interes mensual, el maximo de cuotas y el porcentaje de
 * cuota inicial (#35, RF-084).
 *
 * <h2>Aqui no hay ni un numero</h2>
 *
 * <p>Regla 5. El interes de fraccionamiento y el numero maximo de cuotas son cifras de la ordenanza
 * de fraccionamiento —valores de <b>ordenanza local</b> con su ratificacion provincial, que es
 * exactamente D-02b (#191)—, y compilarlas tendria dos consecuencias: no se podrian cambiar sin
 * desplegar, y los convenios firmados antes se recalcularian con las nuevas.
 *
 * <p>Por eso este tipo <b>no las conoce</b>: las recibe. Quien las lee del conjunto sellado es
 * {@code CondicionesParametrizadas}, y el convenio guarda ademas el {@code conjunto_id} del que
 * salieron, para que revisar dentro de diez anios por que el cronograma es el que es no resuelva
 * «el vigente» y de otro interes sin avisar (ARQ-09 §3).
 *
 * <p>Tampoco hay valor por omision. Un interes que faltara y se sustituyera por cero regalaria el
 * financiamiento de toda la cartera fraccionada; uno «razonable» produciria convenios con un
 * interes que ninguna ordenanza respalda, y eso se descubre cuando el primero se impugna.
 *
 * <h2>Que <b>si</b> decide este tipo</h2>
 *
 * <p>Que el numero de cuotas pedido quepa en el maximo, y que el porcentaje de inicial este entre 0
 * y 100. Son comprobaciones de forma, no cifras: no dicen cuanto vale el maximo, dicen que hay que
 * respetarlo.
 *
 * @param interesMensual el interes de fraccionamiento mensual, en tanto por ciento
 * @param maximoDeCuotas cuantas cuotas admite como mucho, sin contar la inicial
 * @param porcentajeInicial que parte de lo acogido se paga como cuota inicial
 * @param conjuntoId el conjunto sellado del que salieron las dos primeras (ARQ-09 §3)
 */
public record CondicionesDelConvenio(
        Alicuota interesMensual, int maximoDeCuotas, Alicuota porcentajeInicial, long conjuntoId) {

    public CondicionesDelConvenio {
        Objects.requireNonNull(
                interesMensual,
                "El interes de fraccionamiento entra como parametro; no hay valor por omision"
                        + " (regla 5, D-02b)");
        Objects.requireNonNull(
                porcentajeInicial, "El porcentaje de cuota inicial entra como parametro");
        if (maximoDeCuotas < 1) {
            throw new IllegalArgumentException(
                    "El maximo de cuotas de un convenio es al menos 1; llego " + maximoDeCuotas);
        }
        if (conjuntoId < 1) {
            throw new IllegalArgumentException(
                    "Las condiciones dicen de que conjunto sellado salieron (ARQ-09 §3): "
                            + conjuntoId);
        }
    }

    /**
     * Comprueba que ese numero de cuotas sea admisible.
     *
     * @throws DemasiadasCuotas si excede el maximo parametrizado
     */
    public void exigirQueQuepa(int cuotas) {
        if (cuotas < 1) {
            throw new IllegalArgumentException(
                    "Un convenio se paga al menos en una cuota; llego " + cuotas);
        }
        if (cuotas > maximoDeCuotas) {
            throw new DemasiadasCuotas(cuotas, maximoDeCuotas);
        }
    }

    /** Se pidieron mas cuotas de las que la ordenanza admite. */
    public static final class DemasiadasCuotas extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DemasiadasCuotas(int pedidas, int maximo) {
            super(
                    "Se pidieron "
                            + pedidas
                            + " cuotas y el maximo vigente es "
                            + maximo
                            + ": un convenio por encima del maximo no lo respalda ninguna"
                            + " ordenanza");
        }
    }
}
