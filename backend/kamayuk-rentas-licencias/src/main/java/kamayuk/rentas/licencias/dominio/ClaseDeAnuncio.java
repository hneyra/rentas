package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * La clase del elemento publicitario (#51, RF-114).
 *
 * <p>Es <b>la unica</b> de las cuatro caracteristicas que la pantalla {@code anuncios} ofrece como
 * desplegable —clase, emplazamiento, tipo, forma— que esta modelada como vocabulario cerrado, y el
 * motivo es concreto: de ella sale la llave del parametro sellado que tarifa el anuncio ({@code
 * TASA_ANUNCIO:<CLASE>}). Las otras tres describen; esta decide cuanto se cobra, y una clase mal
 * escrita seria una tasa que no se encuentra.
 *
 * <p>Los nombres son los del desplegable «Clase Anuncio» del prototipo, sin tildes y con guion bajo
 * donde el prototipo pone un espacio: son identificadores, y Checkstyle rechaza las tildes (ARQ-04
 * §3).
 */
public enum ClaseDeAnuncio {

    /** Letrero: el rotulo del propio establecimiento. */
    LETRERO("Letrero"),

    /** Panel publicitario. */
    PANEL("Panel"),

    /** Toldo con publicidad. */
    TOLDO("Toldo"),

    /** Banderola. */
    BANDEROLA("Banderola"),

    /** Pantalla digital. */
    PANTALLA_DIGITAL("Pantalla digital"),

    /** Globo aerostatico. */
    GLOBO_AEROSTATICO("Globo aerostatico");

    private final String etiqueta;

    ClaseDeAnuncio(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Como se lee en la pantalla y en el papel. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * La clave con la que el conjunto sellado tarifa esta clase.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: aqui no hay ninguna cifra, y no puede
     * haberla mientras D-02b siga abierta (#199).
     */
    public String claveDeLaTasa() {
        return name();
    }

    public static ClaseDeAnuncio porNombre(String nombre) {
        return valueOf(nombre.strip().replace(' ', '_').toUpperCase(Locale.ROOT));
    }
}
