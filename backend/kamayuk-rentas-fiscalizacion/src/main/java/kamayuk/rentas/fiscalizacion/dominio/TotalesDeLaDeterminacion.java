package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Lo que una determinación de oficio suma: el insoluto omitido, la multa y el total, sobre las
 * líneas de la liquidación que transfiere (#193, RF-057).
 *
 * <p><b>Función pura</b> (regla 6): entran las líneas y sale la suma. Sin base de datos, sin reloj
 * y sin configuración global, de modo que volver a sumar la resolución de 2026 en 2036 da el mismo
 * céntimo.
 *
 * <h2>Por qué esto lo suma el backend y no la pantalla</h2>
 *
 * <p>Porque una resolución de determinación es el papel que convierte una diferencia en <b>deuda
 * exigible</b>, y sumar sus líneas en el navegador da una cifra al céntimo indistinguible de una
 * publicada — con la diferencia de que sólo una de las dos se puede cuadrar contra lo que se
 * asienta en el libro. Está medido: la rotura R2 de #179 sumó la muestra cifrada y dio <b>290.20 al
 * céntimo</b>, o sea exactamente el total real. Es la regla de {@code conectores.ts}, y aquí se
 * cumple publicando el agregado.
 *
 * <h2>Una ausencia no se suma como cero, y cada columna se decide sola</h2>
 *
 * <p>Si <b>cualquier</b> línea trae su insoluto en {@code null}, el insoluto total sale {@code
 * null}; lo mismo la multa por su lado. Sumar lo que hay y callar lo que falta produciría un total
 * que no incluye lo que falta, y sobre un valor notificable eso se lee como «esto es todo lo que
 * debe».
 *
 * <p>Las dos columnas se deciden por separado <b>a propósito</b>: el insoluto es D-02a y la multa
 * es D-02c, y son dos decisiones distintas que pueden cerrarse en momentos distintos. Anularlas
 * juntas escondería el insoluto ya determinado de toda una resolución sólo porque la multa siga
 * pendiente. El {@code total} sí necesita las dos, y por eso es el que manda en {@link
 * #esperaSusCifras} — la misma regla que {@code ResolucionResource.LineaDeterminadaResource.total}
 * aplica dentro de una línea, un escalón más arriba, y la misma que el papel impreso aplica.
 *
 * <p>Mientras D-02a y D-02c sigan abiertas (#198) <b>los tres salen nulos</b> en toda liquidación
 * que este sistema emita, porque los cuatro importes de toda línea nacen nulos. Eso es correcto y
 * es lo que {@link #esperaSusCifras} dice, para que la interfaz escriba «sin cifra» en vez de
 * dibujar un cero. <b>Y no es un campo constante</b> (la lección de #194): el día que las líneas
 * lleguen con cifra, estos tres se llenan solos sin tocar una línea de código.
 *
 * <h2>Lo que este tipo NO tiene, y por qué</h2>
 *
 * <p><b>Ningún interés.</b> Ninguna de las dieciséis operaciones de fiscalización publica uno, y no
 * es un descuido del DTO: no hay en este sistema ninguna tasa de interés moratorio sellada con la
 * que calcularlo, y ponerle una sería inventar un valor normativo (regla 5). La pantalla del
 * prototipo dibuja dos sitios de interés —el campo «Interés» y la columna «Interés S/»— y el cuadro
 * que <b>se imprime</b> lleva {@code Multa S/} donde decía Interés. Cuál de las dos cosas es la
 * buena es una decisión, no una implementación: es #213.
 *
 * @param insolutoOmitido la suma de {@link LineaDeLiquidacion#insolutoOmitido} de todas las líneas
 * @param multaTributaria la suma de {@link LineaDeLiquidacion#multaTributaria} de todas las líneas
 * @param total la suma de las dos anteriores; nulo si falta cualquiera
 */
public record TotalesDeLaDeterminacion(
        @Nullable Dinero insolutoOmitido,
        @Nullable Dinero multaTributaria,
        @Nullable Dinero total) {

    /** Nada que sumar: los tres pendientes. Es lo que devuelve una lista sin ninguna línea. */
    private static final TotalesDeLaDeterminacion PENDIENTES =
            new TotalesDeLaDeterminacion(null, null, null);

    public TotalesDeLaDeterminacion {
        if (total != null && (insolutoOmitido == null || multaTributaria == null)) {
            throw new IllegalArgumentException(
                    "Un total con un sumando ausente no es un total: es una cifra que no incluye"
                            + " lo que falta, y esto se notifica");
        }
    }

    /**
     * Los totales de esas líneas.
     *
     * <p>Una lista vacía devuelve los tres nulos y no tres ceros: una resolución sin ninguna línea
     * no determinó cero, no determinó nada. No debería existir —{@code LiquidarFiscalizacion}
     * escribe la cabecera y su detalle en un solo acto— y aun así se contesta lo honesto en vez de
     * lanzar.
     */
    public static TotalesDeLaDeterminacion de(List<LineaDeLiquidacion> lineas) {
        Objects.requireNonNull(lineas, "Los totales se suman sobre las lineas de la liquidacion");
        if (lineas.isEmpty()) {
            return PENDIENTES;
        }

        Dinero insoluto = Dinero.CERO;
        Dinero multa = Dinero.CERO;
        boolean faltaInsoluto = false;
        boolean faltaMulta = false;
        for (LineaDeLiquidacion linea : lineas) {
            Dinero suyoInsoluto = linea.insolutoOmitido();
            Dinero suyaMulta = linea.multaTributaria();
            if (suyoInsoluto == null) {
                faltaInsoluto = true;
            } else {
                insoluto = insoluto.mas(suyoInsoluto);
            }
            if (suyaMulta == null) {
                faltaMulta = true;
            } else {
                multa = multa.mas(suyaMulta);
            }
        }

        Dinero insolutoTotal = faltaInsoluto ? null : insoluto;
        Dinero multaTotal = faltaMulta ? null : multa;
        return new TotalesDeLaDeterminacion(
                insolutoTotal,
                multaTotal,
                insolutoTotal == null || multaTotal == null ? null : insolutoTotal.mas(multaTotal));
    }

    /**
     * Si los totales siguen esperando a D-02a y D-02c (#198), para que la interfaz escriba «sin
     * cifra» en vez de un cero — que un contribuyente leería como «no debe nada».
     *
     * <p>Mismo campo y mismo motivo que {@code LiquidacionResource.esperaSusCifras}.
     */
    public boolean esperaSusCifras() {
        return total == null;
    }
}
