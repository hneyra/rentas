package kamayuk.rentas.sanciones.dominio;

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
 *   <tr><td>{@link #ANULADA}</td><td><b>No se deriva de nada</b>: es un acto de la administración,
 *       igual que el {@code ANULADA} del acta de fiscalización (#214). Hoy no existe el acto que
 *       la escriba.</td></tr>
 *   <tr><td>{@link #PRESCRITA}</td><td><b>Tampoco se deriva de nada</b>: la declara un acto. Hoy
 *       tampoco existe — {@code DeclararPrescripcion} mueve el <b>valor</b>, no la papeleta.</td></tr>
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
 */
public enum EstadoDePapeleta {

    /** Como nace toda papeleta, y el único valor que este sistema escribe. */
    IMPUESTA,

    /** Nadie lo escribe: lo que consta es la diligencia de la <b>resolución</b> (#222). */
    NOTIFICADA,

    /** Nadie lo escribe: se deriva de que exista su resolución de gerencia. */
    RESUELTA,

    /** Nadie lo escribe: lo cobrado es del libro, y aquí sería una segunda verdad. */
    PAGADA,

    /** Nadie lo escribe: en {@code valores} es un predicado sobre el pase (PCO). */
    COACTIVA,

    /** Nadie lo escribe todavía: es un acto de la administración, y no existe. */
    ANULADA,

    /** Nadie lo escribe todavía: lo declara un acto, y hoy sólo alcanza al valor. */
    PRESCRITA
}
