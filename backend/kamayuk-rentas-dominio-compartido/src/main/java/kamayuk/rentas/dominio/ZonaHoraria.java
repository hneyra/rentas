package kamayuk.rentas.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * <p><b>Que resuelve ya, y que no.</b> Desde {@code rentas}#188 la hora que la API <i>publica</i>
 * tambien sale de aqui: {@link #conSuDesfase(Instant)} es lo que convierte el instante en el {@code
 * OffsetDateTime} que viaja en el JSON, con {@code -05:00} a la vista en vez de una {@code Z}. Lo
 * que <b>no</b> cambio son los cuatro {@code ZoneOffset.UTC} que #273 dejo: ninguno de los cuatro
 * sale por HTTP —tres son parametros de un {@code INSERT} y el cuarto mapea una columna a un record
 * de dominio que ningun controlador devuelve—, asi que se quedan donde estan y lo dicen en su
 * javadoc.
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

    /**
     * El mismo instante, visto desde la zona del producto y <b>llevando su desfase encima</b>.
     *
     * <p>Es lo unico que un {@code Resource} tiene que llamar para publicar una hora ({@code
     * rentas}#188). Jackson serializa el {@link OffsetDateTime} que devuelve como {@code
     * 2026-08-13T09:41:12-05:00}, y esos digitos ya son la hora de aqui: quien los lee no tiene que
     * saber donde esta la municipalidad ni mover nada. Un {@link Instant} salia como {@code
     * 2026-08-13T14:41:12Z} y dejaba esa resta al cliente — que es como la bitacora de auditoria
     * acabo enseniando las 14:41 de un recibo anulado a las 09:41.
     *
     * <p><b>Por que un {@code OffsetDateTime} y no un {@code LocalDateTime} ni una cadena
     * redactada.</b> Un {@code LocalDateTime} publica los mismos digitos y <b>pierde el
     * desfase</b>: el cliente vuelve a adivinar, y esta vez sin saber siquiera que esta adivinando.
     * Una cadena redactada —{@code "13/08/2026 09:41"}— se pinta mas barato y no se ordena ni se
     * filtra sin volver a parsearla, ademas de meter el formato de presentacion en el contrato.
     *
     * <p><b>No convierte nada.</b> El instante que entra y el que sale son el mismo punto en la
     * linea del tiempo; lo unico que cambia es con que desfase se presenta. Por eso esto no es
     * aritmetica sobre un instante y no puede correr un dia.
     */
    public static OffsetDateTime conSuDesfase(Instant instante) {
        Objects.requireNonNull(instante, "No hay hora publicada sin instante");
        return instante.atZone(DEL_PRODUCTO).toOffsetDateTime();
    }

    /**
     * El mismo instante, escrito como texto con el desfase de la zona del producto: {@code
     * 2026-03-04T20:00:00-05:00}.
     *
     * <p>Es lo que se imprime cuando la hora no viaja en un JSON sino <b>en un documento</b> —el
     * acta de internamiento que se entrega al conductor ({@code rentas}#327)—. Hasta entonces el
     * acta la escribia con {@code Instant.toString()}, que es UTC: un ingreso de las 20:00 del 4
     * constaba como {@code 2026-03-05T01:00:00Z}, un dia despues del «Datos al 2026-03-04» que el
     * mismo papel dice arriba.
     *
     * <p><b>Por que el formato ISO y no {@code 04/03/2026 20:00}.</b> Es el que ya imprimen las
     * demas fechas de los documentos —{@code LocalDate.toString()}— y <b>el mismo texto</b> que
     * Jackson escribe para {@link #conSuDesfase(Instant)} en la API, segundos incluidos: la hora
     * que consta en el papel y la que ensena la pantalla se comparan caracter a caracter. Un
     * formato de presentacion distinto es una decision de la interfaz del documento, no de la zona.
     *
     * <p><b>Por que vive aqui.</b> Porque es el unico sitio de produccion donde una hora se
     * convierte en texto: {@code NingunaHoraSePublicaSinSuDesfaseTest} prohibe el {@code
     * toString()} de {@link Instant}, {@link OffsetDateTime}, {@code ZonedDateTime} y {@code
     * LocalDateTime} en todo {@code backend/*&#47;src/main}, y esta llamada no lo usa.
     */
    public static String textoConSuDesfase(Instant instante) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(conSuDesfase(instante));
    }
}
