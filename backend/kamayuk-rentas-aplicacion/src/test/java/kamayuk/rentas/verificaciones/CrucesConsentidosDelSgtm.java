package kamayuk.rentas.verificaciones;

import java.util.List;
import kamayuk.comun.verificaciones.ConfiguracionDeLasVerificaciones.CruceConsentido;

/**
 * Los cruces de SQL que hoy atraviesan una frontera de sistema y todavia no se pueden cerrar.
 *
 * <p><b>Desde P5D esta lista esta VACIA, y esa es la noticia.</b> No es una lista de excepciones
 * toleradas: es lo que queda por hacer, escrito donde se pone rojo cuando alguien lo hace. Que
 * llegara a cero era el criterio de que la separacion termino, y llego.
 *
 * <p>Se conserva declarada, y no se borra el mecanismo. Lo que permite es una excepcion <b>temporal
 * y con dueno</b>, y con la lista vacia un cruce nuevo no tiene donde esconderse: hay que anadirlo
 * con su issue, y el diff lo dice. Es la misma decision que #429 tomo con la lista de hojas
 * pendientes al quedarse sin entradas.
 *
 * <p>Cada entrada nombra su issue. Una excepcion sin issue no se acepta —{@code CruceConsentido} la
 * rechaza al construirla— porque una entrada sin dueno no es una excepcion sino un olvido con
 * permiso, y ya no habria a quien preguntarle.
 *
 * <p>Y ninguna puede sobrar: {@code FronteraDeSistemaTest} comprueba que cada entrada sigue
 * eximiendo un cruce de verdad. Una que ya no aplique se queda dentro para siempre y la lista deja
 * de decir cuanto falta — con la lista vacia esa comprobacion pasa sin revisar nada, que es lo
 * correcto: no hay nada que revisar.
 *
 * <h2>Los seis que hubo, y como se cerro cada uno</h2>
 *
 * <p>Se dejan escritos porque el valor de esta lista no era tenerlos sino <b>haberlos encontrado
 * antes del corte</b>, que era la unica ventana en la que arreglarlos costaba barato. Los
 * identificadores eran {@code PENDIENTE-CRUCE-nn} y no numeros de GitHub a proposito: los
 * repositorios nuevos no tenian issues abiertos, e inventar un {@code #642} que pareciera real
 * habria sido peor que no poner nada.
 *
 * <ul>
 *   <li><b>-01, -04 y -05, cerrados en P5C.</b> `V6` retiro las quince tablas de {@code catastro}.
 *       {@code DeteccionRepositoryJdbc} y {@code ConciliacionRepositoryJdbc} pasaron a leer {@code
 *       predio_ref} y {@code ficha_ref}, la proyeccion local de `V4`; {@code
 *       TitularPrincipalRepositoryJdbc} desaparecio y lo sustituyo {@code
 *       TitularPrincipalPorElPuerto}; y {@code CuotaDeArbitrioRepositoryJdbc} traduce el codigo
 *       predial contra la proyeccion, sin salir de la base.
 *   <li><b>-06, el ultimo, cerrado en P5D.</b> Era {@code ReciboRepositoryJdbc} leyendo {@code
 *       contribuyente} —la caja preguntandole al padron quien pago— y se cierra <b>porque esa clase
 *       ya no esta aqui</b>: `V7` retiro {@code recibo} y sus nueve tablas hermanas, y el
 *       repositorio se fue con ellas al repositorio {@code caja}.
 * </ul>
 *
 * <h2>El de {@code caja} no se cerro mudandolo de sitio, y hay que decirlo entero</h2>
 *
 * <p>Que la clase se vaya de aqui cierra el cruce <b>en este repositorio</b> y no lo resuelve: el
 * recibo sigue necesitando decir a quien se le cobro. En {@code caja} se cerro <b>copiando el
 * pagador en el propio recibo</b> —{@code recibo.pagador_documento} y {@code
 * recibo.pagador_nombre}, su `V2`— en vez de consultarlo: un recibo es un papel que se entrega, y
 * el nombre que lleva impreso es el del dia en que se cobro, no el que el padron diga cinco anos
 * despues.
 *
 * <p><b>Con D-17 todavia abierta</b>, y no es un descuido. Lo que se decidio no es si {@code caja}
 * tendra registro propio de pagadores —eso sigue sin decidirse— sino que <b>esa pregunta deje de
 * bloquear la separacion</b>: copiar dos columnas al emitir no compromete ninguna de las dos
 * salidas de D-17, y no copiarlas habria obligado a resolverla antes de poder cortar.
 */
final class CrucesConsentidosDelSgtm {

    private CrucesConsentidosDelSgtm() {}

    static final List<CruceConsentido> LISTA = List.of();
}
