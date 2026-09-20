package kamayuk.rentas.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * La zona en la que ocurren los hechos que este sistema fecha. <b>El identificador de la zona se
 * escribe aqui y en ningun otro sitio del codigo de produccion</b>: la constante de abajo es su
 * unica ocurrencia en {@code backend/*&#47;src/main}, y eso es comprobable con un {@code grep}.
 *
 * <p><b>De donde sale.</b> La direccion del proyecto decidio el <b>2026-09-20</b> que el producto
 * tiene <b>una sola zona horaria, la del Peru</b> ({@code rentas}#224). Antes de esa decision la
 * zona iba a ser una columna de {@code municipalidad}, con su dueno entre los cuatro sistemas y su
 * migracion para las filas ya existentes; la decision disuelve las tres cosas. No hay columna, no
 * hay replica y no hay migracion: hay <b>una constante del producto</b>.
 *
 * <p><b>Por que un identificador de la IANA y no un desfase fijo.</b> Escribir {@code -05:00}
 * parece equivalente y no lo es: un desfase deja de valer el dia que la zona cambie, y en el Peru
 * ya paso —medido contra la base de husos de la JVM: el pais estuvo a {@code -04:00} del 1 de enero
 * al 1 de abril de 1986, 1987, 1990 y 1994, y tambien en 1938, 1939 y 1940—. Un {@link ZoneId} de
 * la IANA arrastra esa historia; un {@code ZoneOffset} la pierde en silencio y fecha mal cualquier
 * hecho de aquellos anios. {@code ZonaHorariaTest} lo comprueba contra 1990 en lugar de darlo por
 * sabido.
 *
 * <p><b>Por que esto NO contradice que la municipalidad sea un dato.</b> El nombre, el tipo y el
 * ubigeo varian entre municipalidades y por eso son dato ({@code sgtm}#555). La zona horaria, por
 * la decision de arriba, no varia. Lo unico que quedaba de aquel argumento era el riesgo de que el
 * identificador acabara esparcido por el arbol —el remedio no podia ser cambiar nueve literales por
 * nueve literales distintos—, y de eso se ocupa que la cadena viva aqui y solo aqui.
 *
 * <p><b>Por que esto no rompe la regla 6.</b> Las reglas tributarias siguen siendo funciones puras:
 * esto no es un reloj ni configuracion global, sino una constante del mismo rango que «sabado y
 * domingo no son habiles» en {@link CalendarioHabil}. El instante sigue entrando como argumento; lo
 * unico que esta clase aporta es con que zona se lee. Recalcular en 2037 el dia de un hecho de 2027
 * da el mismo dia.
 *
 * <p><b>Que NO resuelve.</b> La hora que la API <i>publica</i> sigue saliendo en UTC; cambiarla es
 * {@code rentas}#188, que toca el contrato. Y los cuatro {@code ZoneOffset.UTC} que convierten un
 * {@link Instant} a {@code OffsetDateTime} —el mismo instante, sin truncar— se quedan donde estan y
 * lo dicen en su javadoc.
 */
public final class ZonaHoraria {

    /**
     * La zona del producto, como identificador de la IANA.
     *
     * <p>Se lee siempre desde aqui. Los cinco sitios que truncaban un {@link Instant} a un {@link
     * LocalDate} usaban {@code ZoneOffset.UTC}, y dos de ellos con consecuencia medible ({@code
     * rentas}#273): la custodia de un vehiculo internado entre las 19:00 y la medianoche se
     * devengaba con <b>un dia de menos</b>, y ese mismo vehiculo <b>no se podia liberar esa misma
     * noche</b> porque su ingreso constaba con la fecha del dia siguiente.
     */
    public static final ZoneId DEL_PRODUCTO = ZoneId.of("America/Lima");

    private ZonaHoraria() {}

    /**
     * El dia en que ocurrio un instante.
     *
     * <p>Es la conversion que hay que hacer siempre que un {@code timestamptz} se compare con una
     * fecha, se imprima en un documento o entre en una cuenta de dias: las dos puntas del intervalo
     * tienen que salir de la misma zona, o la cuenta sale corrida.
     */
    public static LocalDate diaDe(Instant instante) {
        Objects.requireNonNull(instante, "No hay dia sin instante");
        return LocalDate.ofInstant(instante, DEL_PRODUCTO);
    }

    /**
     * El instante en que empieza un dia.
     *
     * <p>Es {@link #diaDe(Instant)} al reves, y por eso va con ella: cuando la pantalla manda un
     * dia donde el modelo espera un instante, el instante que le corresponde es su medianoche
     * <b>local</b>. Tomar la medianoche UTC lo situaria a las 19:00 del dia anterior, que es el dia
     * anterior para quien lo lea.
     */
    public static Instant comienzoDelDia(LocalDate dia) {
        Objects.requireNonNull(dia, "No hay comienzo sin dia");
        return dia.atStartOfDay(DEL_PRODUCTO).toInstant();
    }
}
