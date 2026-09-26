package kamayuk.rentas.sanciones.dominio;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * En qué punto está la papeleta (V4: {@code papeleta.estado}).
 *
 * <h2>Declara siete valores y la producción escribe UNO (#243)</h2>
 *
 * <p>Medido sobre el {@code src/main} de los diecisiete módulos: hay un solo {@code INSERT INTO
 * papeleta}, las dos únicas fábricas —{@code Papeleta.nuevaDeTransito} y {@code
 * Papeleta.nuevaAdministrativa}— ponen {@link #IMPUESTA}, el record no tiene ningún método de
 * transición, y el <b>único</b> {@code UPDATE papeleta} de todo {@code src/main} es {@code SET
 * numero = :numeroNuevo} (#46). Así que esta columna se escribe <b>una vez, en el {@code INSERT}, y
 * siempre {@code IMPUESTA}</b>. Los otros seis valores sólo aparecen leyéndose, como filtro —{@code
 * NO_SE_DEBE}, {@link FaseDelProcedimiento}— o como guarda que hoy no descarta nada.
 *
 * <h2>La decisión, valor por valor</h2>
 *
 * <table>
 *   <tr><th>Valor</th><th>Qué es</th></tr>
 *   <tr><td>{@link #IMPUESTA}</td><td><b>Se escribe.</b> Es como nace toda papeleta, y es el único
 *       valor que este sistema pone.</td></tr>
 *   <tr><td>{@link #NOTIFICADA}</td><td><b>Se deriva</b> de la diligencia de su resolución de
 *       gerencia —{@code notificacion} con {@code objeto = 'RESOLUCION'}, #50—, y eso es lo que el
 *       resumen publica desde #222 como {@code conResolucionNotificada}.</td></tr>
 *   <tr><td>{@link #RESUELTA}</td><td><b>Se deriva</b> de que exista su {@code resolucion_gerencia},
 *       que es lo que {@code GET /transito/papeletas/&#123;numero&#125;/actos} ya contesta.</td></tr>
 *   <tr><td>{@link #PAGADA}</td><td><b>Se deriva del libro</b>, y sólo de ahí. {@code
 *       cuenta_corriente_asiento} no lleva {@code papeleta_id} ni {@code valor_id}, así que hoy no
 *       hay por dónde cruzarlo; y escribirlo <b>aquí</b> dejaría dos verdades sobre el dinero, que
 *       es lo que #214 se negó a introducir. No se añade.</td></tr>
 *   <tr><td>{@link #COACTIVA}</td><td><b>Se deriva</b>, y en {@code valores} ya es un predicado
 *       —{@code v.estado = 'COACTIVA' OR EXISTS(valor_movimiento PCO)}—. Lo que este contexto
 *       posee de esa etapa es que la <b>resolución de multa esté emitida</b>, y eso lo publica el
 *       resumen desde #243 como {@code conResolucionDeMulta}.</td></tr>
 *   <tr><td>{@link #ANULADA}</td><td><b>Se escribe, desde #267.</b> No se deriva de nada: es un
 *       acto de la administración, igual que el {@code ANULADA} del acta de fiscalización (#214),
 *       y ahora existe el acto que la escribe — {@code AnularPapeleta}, publicado como {@code POST
 *       /transito/papeletas/&#123;numero&#125;/anulacion}.</td></tr>
 *   <tr><td>{@link #PRESCRITA}</td><td><b>No se escribe, y #267 lo decidió con la medida.</b>
 *       Tampoco se deriva de nada —la declara un acto—, pero ese acto habla del <b>valor</b> y no
 *       de la papeleta: ver abajo.</td></tr>
 * </table>
 *
 * <h2>Por qué no se retira ninguno del ENUMERADO, y sí se estrecha el privilegio</h2>
 *
 * <p>{@code V19} retiró tres valores de {@code EstadoDeActa} por este mismo razonamiento. Aquí
 * <b>no se hace</b>, y el motivo se midió: este enumerado es también el <b>vocabulario de
 * lectura</b>. {@code EstadoDePapeleta.valueOf(fila.getString("estado"))} se ejecuta en cuatro
 * repositorios, y una fila que trajera {@code 'PAGADA'} —de una instalación con el padrón migrado
 * del sistema anterior— reventaría el padrón entero con un {@code IllegalArgumentException}.
 *
 * <p><b>Y esa fila no se puede normalizar desde una migración</b>: {@code papeleta} tiene {@code
 * FORCE ROW LEVEL SECURITY} y el migrador corre sin contexto de tenant, así que no puede ni leerla
 * ni reescribirla — es la misma razón por la que {@code V10} y {@code V19} ponen sus {@code CHECK}
 * en {@code NOT VALID}. El cero de un panel es seguro en una instalación nueva y <b>probable pero
 * no seguro</b> en una migrada; el enumerado tiene que aguantar las dos.
 *
 * <p>Lo que sí se estrecha es el privilegio: {@code V20} cambia el {@code GRANT UPDATE} sobre la
 * <b>tabla</b> por {@code GRANT UPDATE (numero, estado)}. Lo que el inspector escribió en la calle
 * —la placa, la hora, el lugar, el importe— deja de poder corregirse desde la aplicación, que es el
 * mismo trato que {@code V1} le da a {@code declaracion_jurada} y {@code V19} a {@code
 * acta_fiscalizacion}.
 *
 * <h2>#259 lo volvió a mirar, y la respuesta sigue siendo NO — pero ahora muerde</h2>
 *
 * <p>#243 dejó la retirada abierta con el motivo medido, y #259 la cerró: <b>no se retira ninguno
 * de los siete, y el {@code CHECK} no se estrecha.</b> Lo que #259 encontró es que ese motivo <b>no
 * lo sostenía ni una prueba</b>. Medido sobre el árbol de {@code d084bb6}, con {@code RESUELTA} —el
 * único valor sin una sola referencia en Java fuera de este archivo— borrado del enumerado: {@code
 * ./gradlew build} salió <b>BUILD SUCCESSFUL</b>, 3 080 pruebas, exit 0. Una palabra del
 * vocabulario de lectura se podía borrar y el repositorio entero no se enteraba.
 *
 * <p>Lo que lo fija ahora es {@code PapeletaRepositoryJdbcTest.VocabularioDeLectura}: siembra un
 * <b>padrón migrado</b> —una fila en cada uno de los estados que {@code papeleta_estado_check}
 * admite, y la lista sale del {@code CHECK} y no de {@code values()}, que es lo que hace que la
 * guarda muerda—, lo lee entero por los dos caminos que publican el padrón y lo renumera (#46) fila
 * a fila. Medido: retirar {@code PAGADA} del enumerado pone rojas <b>cuatro</b> pruebas con {@code
 * IllegalArgumentException: No enum constant …PAGADA} más la comparación de los dos vocabularios;
 * estrechar el {@code CHECK} pone roja esa comparación.
 *
 * <p>Y el punto 3 del issue —que un {@code CHECK} estrechado volvería <b>intocables</b> esas filas—
 * también dejó de ser una afirmación: {@code elCheckNotValidVuelveIntocableLaFilaMigrada} lo mide
 * sobre el motor, con una tabla de usar y tirar. Un {@code NOT VALID} no mira la fila que ya está,
 * pero vuelve a comprobarla en cuanto se actualiza <b>cualquier</b> columna suya, así que {@code
 * UPDATE papeleta SET numero} fallaría sobre una papeleta migrada que constase {@code PAGADA}.
 *
 * <p><b>Y esto no es lo mismo que {@code V19} hizo con el acta.</b> Allí se retiraron tres valores
 * que <b>nadie podía haber escrito nunca</b>: {@code acta_fiscalizacion} sólo la llena este
 * sistema. Aquí no. {@code papeleta} es la tabla que un padrón migrado trae llena, y un {@code
 * 'PAGADA'} suyo <b>es un hecho cierto del sistema anterior</b>, no una promesa vacía. Retirar el
 * valor no borraría una columna que nadie llena: dejaría de poder leerse lo que ya está escrito.
 *
 * <h2>#267 añadió UNA escritura, y midió por qué no dos</h2>
 *
 * <p>De los seis valores que nadie escribía, {@link #ANULADA} y {@link #PRESCRITA} eran los dos que
 * <b>no se derivan de nada</b>: los declara un acto. #267 añadió el primero y <b>no</b> el segundo,
 * y no es una media medida sino lo que salió de mirar {@code DeclararPrescripcion}:
 *
 * <ol>
 *   <li>Su firma —{@code declarar(contribuyenteId, tributo, ejercicioDesde, ejercicioHasta, …)}—
 *       <b>no nombra ninguna papeleta</b>, ni su cuerpo tampoco: resuelve ejercicio por ejercicio y
 *       marca {@code PRESCRITO} los valores cuyas lineas prescribieron todas ({@code
 *       CoberturaDeLaPrescripcion}, #337).
 *   <li>Volver del valor a la papeleta —{@code valor} → {@code papeleta_masivo_item.valor_id} →
 *       {@code papeleta}— es un cruce que vive en {@code sanciones}, y {@code valores} no lo puede
 *       hacer: no hay ningún puerto de {@code sanciones} que lo publique.
 *   <li>Y <b>no sería completo</b>: una papeleta sólo tiene valor si pasó la corrida masiva, que
 *       exige su resolución de multa dictada <b>y</b> notificada. La que nunca llegó ahí no
 *       recibiría {@code PRESCRITA} jamás, mientras la deuda que originó prescribe igual.
 *   <li>Y ya hay dos sitios donde la prescripción consta —la fila de {@code prescripcion} y el
 *       estado del valor—, porque #674 decidió que <b>no</b> toca el libro. La columna de la
 *       papeleta sería el tercero.
 * </ol>
 *
 * <p>Así que {@code PRESCRITA} se queda donde están {@link #PAGADA} y {@link #COACTIVA}: un valor
 * que sólo un padrón migrado trae y que este sistema lee pero no escribe.
 *
 * <h2>Y por eso {@link #NO_SE_DEBE} vive aquí, y en un solo sitio de verdad</h2>
 *
 * <p>Hasta #259 la lista de los estados en los que una papeleta ya no se debe estaba <b>tres
 * veces</b>: la constante de {@code PadronDePapeletasRepositoryJdbc} —cuyo docblock decía «Uno
 * solo, y en un solo sitio»—, un literal repetido a mano en {@code PapeletaRepositoryJdbc} y la
 * expresión Java de {@code PapeletaDelPadron.estaPendiente()}, que es la que la API publica como
 * {@code pendiente}. Tres copias que podían divergir, y la que se mira menos —el filtro— dejaría de
 * encontrar lo que la grilla enseña. Ahora la verdad es {@link #seDebe()} y el {@code IN (…)} de
 * SQL se <b>deriva</b> de ella; no hay dónde escribir la segunda.
 */
public enum EstadoDePapeleta {

    /** Como nace toda papeleta, y el único valor que este sistema escribe. */
    IMPUESTA(true),

    /** Nadie lo escribe: lo que consta es la diligencia de la <b>resolución</b> (#222). */
    NOTIFICADA(true),

    /** Nadie lo escribe: se deriva de que exista su resolución de gerencia. */
    RESUELTA(true),

    /** Nadie lo escribe: lo cobrado es del libro, y aquí sería una segunda verdad. */
    PAGADA(false),

    /** Nadie lo escribe: en {@code valores} es un predicado sobre el pase (PCO). */
    COACTIVA(true),

    /** El segundo valor que este sistema escribe: el acto de {@code AnularPapeleta} (#267). */
    ANULADA(false),

    /** Nadie lo escribe: lo declara un acto, y ese acto alcanza al valor y no a la papeleta. */
    PRESCRITA(false);

    /**
     * La lista SQL de los estados en los que ya no se debe nada, para un {@code estado NOT IN …}.
     *
     * <p><b>Se deriva de {@link #seDebe()}, y por eso no puede divergir de ella</b> (#259). Es
     * texto de esta clase y nunca del cliente, igual que {@code FaseDelProcedimiento.EXPRESION} y
     * {@code AgrupacionDelResumen}: se concatena a la consulta, no se parametriza.
     *
     * <p>Que esté aquí y no en un repositorio es la corrección de #259: la misma lista estaba
     * escrita tres veces —dos en SQL y una en Java— y la de Java es la que la API publica como
     * {@code pendiente}. Un cuarto estado que dejara de deberse habría que acordarse de escribirlo
     * en los tres sitios; ahora se escribe {@code false} una vez.
     */
    public static final String NO_SE_DEBE =
            Arrays.stream(values())
                    .filter(estado -> !estado.seDebe())
                    .map(estado -> "'" + estado.name() + "'")
                    .collect(Collectors.joining(", ", "(", ")"));

    /**
     * Que una resolución de gerencia haya dejado la multa sin efecto (#385), para un {@code WHERE}
     * o un {@code CASE} sobre el alias {@code p} de {@code papeleta}.
     *
     * <p><b>Es la otra mitad de «ya no se debe», y no cabe en {@link #seDebe()}.</b> {@code
     * ResolverConResolucionDeGerencia} decide —y con motivo— que el estado de la papeleta <b>no se
     * toca</b> cuando la resolución la deja sin efecto: extingue la obligación en el libro y la
     * situación de la papeleta «se deriva de las resoluciones que tiene». Hasta #385 esa frase no
     * la hacía verdad nadie: la fase decía {@code SANCIONADA}, el padrón la publicaba {@code
     * pendiente}, el estado de cuenta la listaba y la constancia de tránsito se negaba por ella,
     * todo porque {@code p.estado} seguía en {@code IMPUESTA}. Esto es lo que la deriva.
     *
     * <p><b>La lista de efectos se deriva de {@link EfectoSobreLaMulta#extingueLaDeuda()}</b>, con
     * el mismo patrón que {@link #NO_SE_DEBE} sobre {@link #seDebe()} (#259): el día que {@code
     * SE_REDUCE} también extinga, basta con cambiar un {@code boolean}. Mira el <b>efecto</b>, y no
     * el tipo de la resolución ni que resuelva un descargo: la {@code ADMINISTRATIVA} que declara
     * infundado un descargo también resuelve un descargo, y esa multa sigue viva.
     *
     * <p>Mira <b>cualquier</b> resolución con ese efecto, no «la última». Hoy no hay ningún acto
     * que revoque la que dejó la multa sin efecto; si aparece, el predicado cambia aquí, en un
     * sitio.
     */
    public static final String DEJADA_SIN_EFECTO =
            "EXISTS (SELECT 1 FROM resolucion_gerencia rg"
                    + " WHERE rg.papeleta_id = p.id AND rg.efecto IN "
                    + Arrays.stream(EfectoSobreLaMulta.values())
                            .filter(EfectoSobreLaMulta::extingueLaDeuda)
                            .map(efecto -> "'" + efecto.name() + "'")
                            .collect(Collectors.joining(", ", "(", ")"))
                    + ")";

    /**
     * Que la papeleta del alias {@code p} <b>siga debiéndose</b> (#385): ni en un estado que ya no
     * se debe ({@link #NO_SE_DEBE}) ni dejada sin efecto por una resolución ({@link
     * #DEJADA_SIN_EFECTO}).
     *
     * <p>Es <b>la única</b> definición de «pendiente» que leen los padrones, el filtro de
     * pendientes, los {@code FILTER} del resumen y la columna {@code se_debe} que {@code
     * PapeletaDelPadron.estaPendiente()} devuelve. Por eso no hay una versión en Java: {@link
     * #seDebe()} sólo sabe del estado, y volver a calcular en Java lo que la consulta ya calculó es
     * justo la divergencia que cerró #259.
     */
    public static final String SE_DEBE =
            "(p.estado NOT IN " + NO_SE_DEBE + " AND NOT " + DEJADA_SIN_EFECTO + ")";

    private final boolean seDebe;

    EstadoDePapeleta(boolean seDebe) {
        this.seDebe = seDebe;
    }

    /**
     * Si una papeleta en este estado <b>sigue debiéndose</b>.
     *
     * <p>Es la única verdad sobre eso, y de ella sale también {@link #NO_SE_DEBE}. {@code PAGADA}
     * se cobró, {@code ANULADA} no vale y {@code PRESCRITA} ya no se puede exigir; las otras cuatro
     * son puntos del camino de una deuda viva, y {@code COACTIVA} la más viva de todas.
     *
     * <p><b>Habla del estado, no de la papeleta</b> (#385): una {@code IMPUESTA} que una resolución
     * dejó sin efecto ya no se debe, y eso no lo sabe el estado. Lo que se publica como pendiente
     * es {@link #SE_DEBE}, que es esto <b>y</b> lo que dicen las resoluciones.
     */
    public boolean seDebe() {
        return seDebe;
    }
}
