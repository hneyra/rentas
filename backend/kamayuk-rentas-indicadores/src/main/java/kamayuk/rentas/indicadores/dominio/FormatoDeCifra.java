package kamayuk.rentas.indicadores.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Como se escribe una cifra del panel. <b>Funcion pura y sin redondear</b> (#56, RNF-080).
 *
 * <h2>Por que el servidor redacta el texto</h2>
 *
 * <p>Porque la interfaz no calcula: {@code frontend/…/inicio/recaudacion.ts} lo dice en su tipo —el
 * campo se llama {@code cifra} y no {@code importe}, «sobre un importe se podria hacer aritmetica,
 * y sobre esto no hay nada que hacer mas que pintarlo»—. Si el panel mandara numeros y la pantalla
 * los formateara, habria dos verdades sobre lo recaudado y ninguna sustentable.
 *
 * <h2>Ni un redondeo, y no es un detalle</h2>
 *
 * <p>Este formato <b>nunca recorta decimales</b>. Los importes llegan del motor como {@code
 * numeric(15,2)} —el dominio {@code dinero} de V1— y la suma de {@code numeric(15,2)} sigue
 * teniendo dos decimales, asi que en la practica siempre se imprimen dos. Si alguna vez llegara uno
 * con mas, se imprimen todos: mostrarlo largo se ve, y recortarlo no.
 *
 * <p>Recortar seria ademas tomar D-03 por descuido. Redondear un importe exige una {@code
 * PoliticaDeRedondeo} que sale de un conjunto sellado de parametros, y el escaner de fuentes
 * rechaza cualquier {@code setScale(2, HALF_UP)} escrito a mano. Aqui no se necesita ninguna,
 * porque aqui no se redondea nada: se separa el texto que {@link
 * java.math.BigDecimal#toPlainString()} ya produjo.
 *
 * <p>Los separadores son los del prototipo: coma para los miles y punto para los decimales.
 */
public final class FormatoDeCifra {

    /** Prefijo del sol peruano, tal como lo escribe el manual. */
    private static final String MONEDA = "S/ ";

    private static final char MILES = ',';
    private static final String DECIMAL = ".";

    /** Los decimales que se completan si faltan. Nunca los que se recortan si sobran. */
    private static final int DECIMALES_MINIMOS = 2;

    private static final int DIGITOS_POR_GRUPO = 3;

    /** Lo que se escribe donde no hay cifra que dar. */
    public static final String SIN_CIFRA = "—";

    private FormatoDeCifra() {}

    /**
     * Un importe, con su moneda: {@code S/ 1,234,567.89}.
     *
     * <p>El negativo lleva el signo delante de la moneda —{@code -S/ 12.00}— y no entre parentesis:
     * el parentesis es convencion contable y en una pantalla se confunde con una acotacion.
     */
    public static String importe(Dinero importe) {
        Objects.requireNonNull(importe, "No hay cifra que escribir sin importe");
        String plano = importe.valor().toPlainString();
        boolean negativo = plano.startsWith("-");
        String sinSigno = negativo ? plano.substring(1) : plano;

        int punto = sinSigno.indexOf('.');
        String entera = punto < 0 ? sinSigno : sinSigno.substring(0, punto);
        StringBuilder decimales = new StringBuilder(punto < 0 ? "" : sinSigno.substring(punto + 1));
        while (decimales.length() < DECIMALES_MINIMOS) {
            decimales.append('0');
        }

        return (negativo ? "-" : "") + MONEDA + agrupar(entera) + DECIMAL + decimales;
    }

    /** Un recuento, con separador de miles: {@code 24,118}. */
    public static String cantidad(long cuantos) {
        boolean negativo = cuantos < 0;
        String digitos = Long.toString(Math.abs(cuantos));
        return (negativo ? "-" : "") + agrupar(digitos);
    }

    /** Un porcentaje entero, ya calculado: {@code 77 %}. */
    public static String porcentaje(int avance) {
        return avance + " %";
    }

    /** Agrupa de tres en tres desde la derecha, sin tocar el valor. */
    private static String agrupar(String digitos) {
        StringBuilder agrupado = new StringBuilder();
        int pendientes = digitos.length();
        for (int i = 0; i < digitos.length(); i++) {
            if (i > 0 && pendientes % DIGITOS_POR_GRUPO == 0) {
                agrupado.append(MILES);
            }
            agrupado.append(digitos.charAt(i));
            pendientes--;
        }
        return agrupado.toString();
    }
}
