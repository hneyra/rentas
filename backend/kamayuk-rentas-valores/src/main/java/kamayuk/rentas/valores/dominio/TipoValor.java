package kamayuk.rentas.valores.dominio;

import java.util.Locale;

/**
 * Los tres documentos que formalizan una deuda tributaria (RF-090, ARQ-01 §3.9).
 *
 * <p>{@link #baseLegal()} es una cita de norma —articulo del TUO del Codigo Tributario—, no una
 * cifra tributaria: la regla 5 prohibe compilar UIT, tramos, alicuotas, valores unitarios,
 * aranceles y tablas de depreciacion, no el numero de un articulo de una ley que no cambia por
 * ejercicio ni afecta ningun calculo.
 */
public enum TipoValor {
    /** Se emite cuando el contribuyente ya autoliquido o declaro la deuda (TUO CT art. 78). */
    ORDEN_DE_PAGO("OP", "TUO del Codigo Tributario, D.S. N.° 133-2013-EF, art. 78"),

    /** Se emite cuando la administracion determina o modifica la deuda (TUO CT arts. 76 y 77). */
    RESOLUCION_DE_DETERMINACION(
            "RD", "TUO del Codigo Tributario, D.S. N.° 133-2013-EF, arts. 76 y 77"),

    /** Se emite para imponer una multa tributaria (TUO CT art. 180). */
    RESOLUCION_DE_MULTA("RM", "TUO del Codigo Tributario, D.S. N.° 133-2013-EF, art. 180");

    private final String codigo;
    private final String baseLegal;

    TipoValor(String codigo, String baseLegal) {
        this.codigo = codigo;
        this.baseLegal = baseLegal;
    }

    /** El codigo de tres letras que identifica el tipo en el numero del valor: OP, RD o RM. */
    public String codigo() {
        return codigo;
    }

    /** La norma que sustenta este tipo de valor, tal como se cita en el documento. */
    public String baseLegal() {
        return baseLegal;
    }

    /**
     * El tipo cuyo {@link #codigo} coincide, sin distinguir mayusculas.
     *
     * @throws IllegalArgumentException si el codigo no es OP, RD ni RM
     */
    public static TipoValor porCodigo(String codigo) {
        String normalizado = codigo.strip().toUpperCase(Locale.ROOT);
        for (TipoValor tipo : values()) {
            if (tipo.codigo.equals(normalizado)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de valor desconocido: '" + codigo + "'. Se admite OP, RD o RM");
    }
}
