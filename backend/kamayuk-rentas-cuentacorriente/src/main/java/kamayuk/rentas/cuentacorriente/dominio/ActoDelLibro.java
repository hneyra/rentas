package kamayuk.rentas.cuentacorriente.dominio;

/**
 * De que acto nace un asiento, cuando el libro lo sabe (#601, V68; #662).
 *
 * <p>No es el {@link Concepto}. El concepto dice <b>contra que parte</b> se imputa —insoluto,
 * reajuste, interes, gasto— y es lo que netean {@link CalculoDeDeuda} y {@link ProyeccionDelSaldo};
 * esto dice <b>por que</b> existe la fila, que es una pregunta distinta y hasta #601 no la
 * contestaba ninguna columna.
 *
 * <h2>Para que hizo falta</h2>
 *
 * <p>Un abono de una <b>baja de deuda</b> y un abono de una <b>cobranza</b> son, columna a columna,
 * el mismo asiento: {@code ABONO} de concepto {@code INSOLUTO}. Sin distinguirlos, «lo cargado» del
 * panel —la emision del ejercicio, que es el denominador de todas las barras de avance— se quedaba
 * con el cargo de un alta que ya no debe nada, y lo recaudado contaba como dinero que entro una
 * deuda que se habia extinguido.
 *
 * <p>Y no se podia distinguir por el signo: netear cargos contra abonos se llevaria por delante los
 * <b>cobros</b>, y «lo cargado» acabaria valiendo la cartera pendiente. Es lo mismo que #56
 * aprendio de {@link Asiento#reversionDe}, que produce el asiento contrario con el <b>mismo</b>
 * concepto.
 *
 * <h2>Los dos que hay, y el silencio de los demas</h2>
 *
 * <p>Solo estan los dos actos de RF-043 y RF-044. Un asiento sin acto —{@code null}— no es un
 * asiento del que se ignore el origen: es uno que <b>no nacio</b> de un alta ni de una baja de
 * deuda. Una emision masiva, una cobranza, una reversion o un acogimiento a convenio dejan la
 * columna en nulo, y la consulta de lo cargado los trata por lo que son.
 *
 * <h2>Quien los estampa: dos sitios, no uno (#662)</h2>
 *
 * <p>{@link MovimientoDeDeuda#enAsientos} —las dos pantallas de Rentas que registran el alta y la
 * baja a mano— y {@code ExtincionDeDeudaCuentaCorriente}, que asienta la baja cuando una resolucion
 * de gerencia deja una multa sin efecto o se declara fundado un descargo (#50, RF-064). Los dos
 * escriben {@code BAJA_DEUDA}, y ese es el resultado de la pregunta que este javadoc dejo abierta
 * en #601.
 *
 * <h2>La pregunta que #601 dejo abierta, y su respuesta (#662)</h2>
 *
 * <p>Era esta: <b>una deuda extinguida, ¿sigue siendo emision del ejercicio?</b> No es la misma que
 * resolvio #601 —un alta equivocada y su baja se anulan, la deuda nunca debio existir—, porque una
 * deuda prescrita <b>existio, se emitio y se devengo</b>, y lo que ocurre es que el municipio
 * pierde la facultad de exigirla. Las dos posturas son defendibles: contarla mide el fracaso de
 * gestion —dejar prescribir baja el avance de cobranza, y eso es informacion verdadera—; no
 * contarla mide lo que de verdad se puede cobrar.
 *
 * <p><b>La respuesta es que deja de contar</b>, y no por preferencia sino porque el sistema ya la
 * tenia contestada, en dos actos que distingue y trata distinto:
 *
 * <ul>
 *   <li><b>Declarar la prescripcion</b> ({@code DeclararPrescripcion}, #39, RF-094) <b>no toca el
 *       libro</b>: no escribe un solo asiento y la deuda sigue donde estaba, porque la prescripcion
 *       extingue la <i>accion de cobro</i>, no la obligacion —por eso lo pagado sobre deuda
 *       prescrita no se devuelve—. Lo que deja es la fila de {@code prescripcion} con su computo y
 *       el estado {@code PRESCRITO} en los valores alcanzados. Ahi la deuda <b>sigue contando</b>
 *       como emision, y eso no cambia con #662.
 *   <li><b>Dar de baja la deuda</b> (RF-044) si toca el libro. Y el desplegable de causal de esa
 *       pantalla tiene como primera opcion —y como valor por omision— «PRESCRIPCIÓN DECLARADA»,
 *       seguida de «RESOLUCIÓN QUE DEJA SIN EFECTO»: exactamente los dos actos que {@code
 *       ExtincionDeDeuda} asienta desde {@code sanciones}. #601 ya decidio que esa baja no es
 *       emision del ejercicio.
 * </ul>
 *
 * <p>Asi que lo que {@code ExtincionDeDeuda} escribe <b>es</b> una baja de deuda, con los mismos
 * asientos y por las mismas causales; lo unico que cambia es que oficina la tramita. Dejarla sin
 * acto —o estamparle uno propio que se comportara distinto— haria que el panel diera <b>dos cifras
 * distintas para el mismo hecho</b> segun por que pantalla entro, que es la forma exacta del
 * defecto que #56, #601 y #640 cerraron.
 *
 * <h2>Por que no hay un tercer valor, ni migracion</h2>
 *
 * <p>Las tres consultas que leen la columna —{@code altasYBajas}, {@code cargadoPorTributo} y la de
 * recaudacion— tendrian que nombrar los dos valores y comportarse igual en las tres: tres sitios
 * donde olvidar uno, para una distincion que no cambia ninguna cifra. Y el {@code CHECK} de V68 ya
 * admite {@code BAJA_DEUDA}, asi que este cambio no necesita ninguna migracion.
 *
 * <h2>Y la prescripcion declarada tampoco gana uno propio (#674)</h2>
 *
 * <p>#662 dejo dicho de pasada que la deuda prescrita «sigue contando como emision»; #674 lo
 * convirtio en una decision tomada en vez de una consecuencia heredada de que {@code
 * DeclararPrescripcion} no escribiera, y la confirmo: <b>sigue siendo cartera pendiente y emision
 * del ejercicio hasta que la administracion la de de baja</b>. El razonamiento entero, con el art.
 * 43 del TUO del Codigo Tributario delante, esta en el javadoc de {@code DeclararPrescripcion}; en
 * una linea, lo que prescribe es la <b>accion de cobro</b> y este libro es el de la
 * <b>obligacion</b>, asi que un asiento que la cancelara afirmaria algo falso.
 *
 * <p>Por eso tampoco hay un {@code PRESCRIPCION} aqui, y no solo por el coste de arriba: la baja
 * que se registra <i>por</i> una prescripcion declarada <b>es</b> una baja de deuda, y su causal es
 * el sustento del acto —viaja en el {@code motivo}, porque {@code
 * MovimientosDeDeudaController.PeticionDeMovimiento} no tiene ningun campo para ella—, no una clase
 * distinta de asiento.
 *
 * <p>Lo que si es <b>otra pregunta</b> —y no se contesta con esta columna— es de que <i>origen</i>
 * viene el acto: si lo tecleo una persona en las dos pantallas de Rentas o lo produjo un
 * procedimiento. Es lo que el manual llama «Auto / Manual», y meterlo aqui seria hacer que una
 * columna conteste dos preguntas; {@code documento_origen} ya dice cual es el papel que lo ordena.
 * Ver {@code AltasBajasController} para por que ese filtro sigue sin servirse.
 */
public enum ActoDelLibro {

    /**
     * Alta de deuda: la «nota de abono» del manual (RF-043). Incorpora deuda que no vino de la
     * emision masiva.
     */
    ALTA_DEUDA,

    /**
     * Baja de deuda: la «nota de cargo» del manual (RF-044). Extingue deuda por prescripcion,
     * resolucion que la deja sin efecto o error material.
     *
     * <p>Lo estampan <b>dos</b> caminos (#662): la pantalla de RF-044 y {@code
     * ExtincionDeDeudaCuentaCorriente}, que asienta la misma baja cuando la ordena una resolucion
     * de gerencia. Las causales son las mismas —el desplegable de la pantalla empieza por
     * «PRESCRIPCIÓN DECLARADA»—, asi que el acto tambien.
     */
    BAJA_DEUDA
}
