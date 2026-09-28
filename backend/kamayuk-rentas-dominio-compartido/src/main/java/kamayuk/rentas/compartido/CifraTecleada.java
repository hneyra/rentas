package kamayuk.rentas.compartido;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * La regla de un importe o un area TECLEADOS: <b>dos decimales como mucho</b>, y se rechaza, no se
 * redondea (#395).
 *
 * <p>Es la escala de {@code dinero numeric(15,2)} y {@code area_m2 numeric(12,2)} (V1). Lo que
 * lleva un tercer decimal no se puede guardar: la columna lo redondea al escribir, y a partir de
 * ahi el calculo, el papel y el libro dicen cifras distintas. Redondearlo aqui seria decidir por
 * quien lo tecleo; lo que se redondea con su politica sellada son los importes que produce el
 * CALCULO (ADR-0018), no los de entrada. Los ceros de la derecha no son un decimal mas: {@code
 * "10.500"} entra.
 *
 * <h2>Una regla, tres puertas (#629)</h2>
 *
 * <p>Lo tecleado entra por tres sitios, y los tres leen con esta clase: el parametro de un
 * controlador ({@code kamayuk.rentas.web.EntradaNumerica}, que traduce el rechazo a un 422), el
 * archivo que se importa (los importadores de la siembra, que lo convierten en fila rechazada) y el
 * cuerpo JSON (el deserializador de {@link kamayuk.rentas.dominio.Dinero Dinero} y {@link
 * kamayuk.rentas.dominio.AreaM2 AreaM2}). Hasta #629 solo la primera la aplicaba, y las otras dos
 * dejaban que la columna redondeara en silencio.
 *
 * <h2>Por que vive aqui</h2>
 *
 * <p>En el modulo del dominio compartido y no en la capa web porque el importador es de {@code
 * aplicacion} y no puede depender del borde HTTP. No dentro de {@link kamayuk.rentas.dominio.Dinero
 * Dinero} ni de {@link kamayuk.rentas.dominio.AreaM2 AreaM2} porque esos tipos siguen sin escala a
 * proposito: transportan intermedios, y la regla es de lo que alguien teclea, no de toda cifra.
 *
 * <p>Y en {@code kamayuk.rentas.compartido}, no en {@code kamayuk.rentas.dominio}: es una lectura
 * de texto, como {@link MarcoGeografico}, y devuelve el {@code BigDecimal} que cada puerta envuelve
 * en su objeto de valor. En {@code ..dominio..} lo medido fue que {@code verificarArquitectura} lo
 * rechaza —«expone BigDecimal desnudo»—, y con razon: esa regla es para las reglas de calculo, cuya
 * escala vive en {@link kamayuk.rentas.dominio.Dinero Dinero}. Esta clase no calcula nada, y se
 * aplica antes de que exista ningun importe.
 */
public final class CifraTecleada {

    /** Los decimales que admite un importe o un area tecleados (ADR-0018). */
    public static final int DECIMALES = 2;

    private CifraTecleada() {}

    /**
     * La cifra tecleada, con dos decimales como mucho.
     *
     * @param texto lo que llego
     * @param campo el nombre del campo, para decir cual se rechaza
     * @param siNoEsCifra la frase cuando el texto no es un numero, la de cada puerta
     * @throws IllegalArgumentException con {@code siNoEsCifra} si el texto no es una cifra
     * @throws DecimalesDeMas si lleva mas de {@link #DECIMALES} decimales
     */
    public static BigDecimal leer(String texto, String campo, String siNoEsCifra) {
        Objects.requireNonNull(texto, "No hay cifra que leer");
        String limpio = texto.strip();
        BigDecimal cifra;
        try {
            cifra = new BigDecimal(limpio);
        } catch (NumberFormatException noEsCifra) {
            throw new IllegalArgumentException(siNoEsCifra, noEsCifra);
        }
        int decimales = Math.max(0, cifra.stripTrailingZeros().scale());
        if (decimales > DECIMALES) {
            throw new DecimalesDeMas(campo, decimales, limpio);
        }
        return cifra;
    }

    /** La cifra lleva mas decimales de los que se guardan: se rechaza, no se redondea. */
    public static final class DecimalesDeMas extends IllegalArgumentException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String motivo;

        DecimalesDeMas(String campo, int decimales, String tecleado) {
            this(
                    "El campo '"
                            + campo
                            + "' lleva "
                            + decimales
                            + " decimales ('"
                            + tecleado
                            + "'): un importe o un area se teclea con "
                            + DECIMALES
                            + " como mucho, que es lo que se guarda. Redondearlo aqui seria"
                            + " decidir por quien lo tecleo");
        }

        private DecimalesDeMas(String motivo) {
            super(motivo);
            this.motivo = motivo;
        }

        /** La frase del rechazo: nombra el campo, cuantos decimales trae y lo que se tecleo. */
        public String motivo() {
            return motivo;
        }
    }
}
