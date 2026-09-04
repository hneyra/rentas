package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que certifica un certificado (#54, RF-115).
 *
 * <p>Son las cuatro clases que la pantalla {@code certificados} ofrece en su desplegable «Tipo de
 * certificado». Es vocabulario cerrado —y {@code certificado.tipo} lo repite como {@code CHECK}—
 * por un motivo concreto: <b>de aqui salen las dos llaves de parametros sellados</b> que la emision
 * necesita, el concepto del TUPA que cobra su derecho y cuantos meses vale. Un tipo de texto libre
 * seria una llave de texto libre, y la primera falta de ortografia dejaria un certificado sin
 * derecho que comprobar.
 *
 * <p><b>Aqui no hay ni un plazo ni un importe.</b> Cuanto cuesta cada tramite y cuanto vale lo fija
 * el TUPA de cada municipalidad, ratificado por la provincia: es D-02b, y vive en el conjunto
 * sellado. Lo que este enum aporta es el <b>nombre</b> de las dos llaves.
 */
public enum TipoDeCertificado {

    /** Acredita el numero municipal asignado al predio. */
    NUMERACION("Certificado de numeracion"),

    /** Acredita la zonificacion del predio y las vias que lo delimitan. */
    ZONIFICACION_VIAS("Certificado de zonificacion y vias"),

    /** Acredita los parametros urbanisticos y edificatorios aplicables al predio. */
    PARAMETROS_URBANISTICOS("Certificado de parametros urbanisticos y edificatorios"),

    /** Acredita que el predio esta dentro de la jurisdiccion de la municipalidad. */
    JURISDICCION("Certificado de jurisdiccion");

    private final String etiqueta;

    TipoDeCertificado(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Como se llama en el papel y en el desplegable. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * La clave del parametro sellado que dice <b>que concepto del TUPA</b> cobra su derecho.
     *
     * <p>Es el nombre de la llave, no su valor: aqui no hay ningun codigo de TUPA compilado, porque
     * cada municipalidad numera el suyo como quiere.
     */
    public String claveDelDerecho() {
        return "DERECHO_CERTIFICADO_" + name();
    }

    /**
     * La clave del parametro sellado que dice <b>cuantos meses vale</b>.
     *
     * <p>Tambien es solo el nombre. El numero de meses es normativo —el TUPA lo fija y la ordenanza
     * lo ratifica— y no puede estar aqui (regla 5): un certificado emitido con una vigencia
     * inventada es un papel que se rechaza en ventanilla ajena, o peor, uno que se acepta cuando ya
     * habia caducado.
     */
    public String claveDeLaVigencia() {
        return "VIGENCIA_CERTIFICADO_" + name();
    }

    /** El tipo con ese nombre, en cualquier caja y con espacios alrededor. */
    public static TipoDeCertificado porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
