package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;

/**
 * El ofrecimiento de garantia del convenio, si lo hubo ({@code convenio_garantia_ck}, V31).
 *
 * <p>Es <b>constancia</b>, no efecto: cuando una garantia es exigible, con que monto y en que
 * plazo, lo fija la ordenanza de fraccionamiento y por tanto D-02b (#191). Aqui se guarda lo que se
 * ofrecio y nada mas; hacerla obligatoria por encima de un importe seria decidir esa ordenanza por
 * descuido.
 *
 * <p>Los cinco valores son los de la pantalla {@code fraccionamiento} del prototipo.
 */
public enum TipoDeGarantia {
    NO_REQUIERE,
    CARTA_FIANZA,
    HIPOTECA,
    AVAL,
    PRENDA;

    /** El tipo con ese nombre, admitiendo el rotulo de la pantalla («CARTA FIANZA»). */
    public static TipoDeGarantia porNombre(String texto) {
        return valueOf(texto.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
