package kamayuk.rentas.verificaciones;

import java.util.Set;

/**
 * Las tablas de {@code sgtm} que el escaner de fuentes protege, tal como estaban en #724.
 *
 * <p>Salen del cuerpo de {@code RevisorDeCodigoFuente} y entran aqui porque el escaner viajo a
 * {@code comun-verificaciones} y <b>estas listas no son las mismas en los cuatro sistemas</b>:
 * {@code recibo} es de {@code caja}, {@code cuenta_corriente_asiento} es de {@code rentas} y {@code
 * parametro_tributario} es de {@code normativa}. Una lista unica obligaria a los cuatro a llevar
 * dentro el vocabulario de los otros tres, y entonces deja de leerse como el inventario de lo que
 * hay que cuidar — que es justo lo que la hace util.
 *
 * <p>Aqui estan las 132 juntas porque {@code sgtm} es el monolito. Al separarse, cada sistema se
 * lleva las suyas segun GOB-05 §2.
 */
final class TablasDelSgtm {

    private TablasDelSgtm() {}

    /**
     * RNF-051: no se borra deuda, pagos, recibos, valores, papeletas, asientos ni auditoria.
     *
     * <p>La lista es la de las tablas cuyo borrado destruiria constancia de un acto administrativo.
     * Al agregar una tabla de esa naturaleza, agregarla aqui.
     */
    static final Set<String> PROTEGIDAS =
            Set.of(
                    "cuenta_corriente_asiento",
                    "determinacion",
                    // Con #9, y era un hueco medido: `determinacion` estaba desde el monolito y su
                    // hermana de arbitrios NO, ni aqui ni en INMUTABLES —aunque su GRANT del
                    // baseline ya sea `INSERT, SELECT`, sin UPDATE ni DELETE, o sea que la base ya
                    // habia decidido lo que el escaner no vigilaba—. Es una determinacion: dice
                    // cuanto se determino de limpieza publica, parques o serenazgo por un predio y
                    // un periodo, y de esa cifra sale un valor que se notifica. Borrarla dejaria
                    // el cargo en el libro sin la determinacion que lo justifica, y corregirla en
                    // el sitio dejaria al papel notificado y a la base diciendo cosas distintas.
                    // Se reversa determinando otra vez, y las dos quedan (regla 4).
                    "determinacion_arbitrio",
                    "saldo_proyectado",
                    "parametro_tributario",
                    "recibo",
                    "recibo_detalle",
                    "recibo_movimiento",
                    "valor",
                    "valor_detalle",
                    "valor_movimiento",
                    "notificacion",
                    "prescripcion",
                    "papeleta",
                    "convenio",
                    // Con #35: el cronograma congelado, la deuda que el convenio acogio -con la
                    // fase a la que vuelve si se quiebra- y los actos sobre el. Borrar
                    // convenio_deuda seria borrar la unica traza de que se fracciono, y con ella
                    // la fase de origen: el quiebre no sabria a donde devolver la deuda.
                    "convenio_cuota",
                    "convenio_deuda",
                    "convenio_movimiento",
                    // Con #36: el arqueo de un turno de caja y su desglose por medio de pago.
                    // Borrar un cierre seria borrar la constancia de cuanto se recaudo un dia y
                    // de cuanto declaro haber contado el cajero -que no esta en ningun otro
                    // sitio-, y con ella la unica cifra contra la que se puede conciliar el
                    // deposito.
                    "cierre_turno",
                    "cierre_turno_detalle",
                    "expediente_coactivo",
                    // Con #40: los valores que el expediente agrupa y su historial. Borrar una
                    // fila de expediente_valor seria borrar la unica traza de que ese valor entro
                    // en cobranza coactiva -y con ella el motivo por el que su deuda dejo de
                    // cobrarse por la via ordinaria-; borrar un movimiento seria borrar el estado
                    // del procedimiento, que no esta en ninguna otra parte.
                    "expediente_valor",
                    "expediente_movimiento",
                    "acto_coactivo",
                    // Con #42: la liquidacion de costas, su detalle y la fila que dice de que
                    // expediente son las costas de una obligacion. Borrar una liquidacion seria
                    // borrar la unica explicacion de un cargo que ya esta en el libro; borrar
                    // `costa_obligacion` dejaria a dos expedientes compartiendo la obligacion de
                    // costas del mismo obligado, que es justo lo que esa tabla existe para impedir.
                    "liquidacion_costas",
                    "costa_procesal",
                    "costa_obligacion",
                    // Con #44: la licencia de funcionamiento, sus duplicados y su historial.
                    // Borrar una licencia seria borrar la unica constancia de que el
                    // establecimiento estuvo autorizado —y con ella el sustento de los arbitrios
                    // que se le cobraron—; borrar un duplicado o un movimiento seria borrar el
                    // acto que la reimprimio o la dejo sin efecto, que no esta en ninguna otra
                    // parte. Una licencia se cancela con su resolucion (regla 4, AC de #44).
                    "licencia_funcionamiento",
                    "licencia_duplicado",
                    "licencia_movimiento",
                    // Con #48: el FUE de edificacion, sus cinco secciones, sus movimientos y sus
                    // vigencias. Borrar un expediente seria borrar la unica constancia de que una
                    // obra estuvo autorizada —y con ella el sustento del derecho de tramite que se
                    // cobro—; borrar una version de seccion seria borrar lo que el administrado
                    // declaro antes de corregirlo, que es justo lo que explica una observacion del
                    // evaluador; y borrar una vigencia dejaria una licencia sin plazo o con el
                    // plazo de la revalidacion como si fuera el original (AC 4).
                    "licencia_edificacion",
                    "edificacion_terreno",
                    "edificacion_proyecto",
                    "edificacion_estructura",
                    "edificacion_profesional",
                    "edificacion_requisito",
                    "edificacion_movimiento",
                    "edificacion_vigencia",
                    // Con #50: el escrito que el administrado presento, la resolucion que la
                    // gerencia dicto sobre su multa, y el paso del vehiculo por el deposito.
                    // Borrar un descargo seria borrar la constancia de que alguien recurrio -y con
                    // ella el computo del plazo-; borrar una resolucion, la del acto que ordeno la
                    // cobranza o dejo la multa sin efecto; borrar un internamiento, la de que un
                    // vehiculo estuvo retenido y devengo custodia.
                    "descargo",
                    "resolucion_gerencia",
                    "internamiento",
                    "internamiento_movimiento",
                    // Con #51: la autorizacion de anuncio y su historial. Borrar un anuncio seria
                    // borrar la unica explicacion de un cargo que YA ESTA EN EL LIBRO -registrar la
                    // autorizacion genera la deuda por su tasa-, y borrar un movimiento seria
                    // ademas borrar la referencia con la que ese cargo entro, que es lo que impide
                    // que se pida dos veces. Un anuncio no se borra: se cesa (regla 4, AC de #51).
                    "anuncio",
                    "anuncio_movimiento",
                    // Con #53: el criterio congelado de una generacion masiva de valores por
                    // papeletas y la constancia libre de infracciones. Borrar una corrida seria
                    // borrar la unica explicacion de por que salieron cuatro mil resoluciones de
                    // multa el mismo dia -y con que fecha se evaluo la deuda de cada una-; borrar
                    // una constancia, la del papel que la municipalidad entrego acreditando que un
                    // vehiculo no debia nada.
                    "papeleta_masivo",
                    "constancia_libre",
                    "ficha_catastral",
                    "acta_fiscalizacion",
                    // Con #49: la liquidacion de fiscalizacion, su contraste linea a linea y
                    // su historial. Borrar una liquidacion seria borrar la constancia de que
                    // se determino de oficio una diferencia -y de cuanto se le dijo al
                    // contribuyente que debia-, que no esta en ningun otro sitio; borrar una
                    // linea dejaria la liquidacion afirmando un total que su detalle ya no
                    // sostiene.
                    "liquidacion_fiscalizacion",
                    "liquidacion_detalle",
                    "liquidacion_movimiento",
                    // Con #52: la transferencia a rentas y su resolucion de determinacion.
                    // Borrarla seria borrar el unico acto que explica por que el padron cambio
                    // -y con el, la version de ficha que se inscribio y el cargo que se le
                    // asento a alguien-. Es la constancia de la frontera delicada del sistema.
                    "resolucion_determinacion",
                    // Con #54: el certificado de numeracion y zonificacion. Borrarlo seria borrar
                    // la constancia de que la municipalidad certifico un numero municipal o unos
                    // parametros urbanisticos -y el administrado tiene el papel en la mano, o lo
                    // presento ante un notario-. Uno equivocado no se borra: se sustituye emitiendo
                    // otro, con su numero y su derecho de tramite, y los dos quedan (regla 4).
                    "certificado",
                    // Con #365: la declaracion jurada. Es el documento que el contribuyente firma
                    // y se lleva, el sustento de toda la determinacion predial y —desde
                    // ADR-0015— lo UNICO que mete al predio en el padron afecto. Borrarla saca al
                    // predio de la conciliacion sin dejar acto que lo explique, o sea un omiso
                    // fabricado. Una DJ equivocada no se borra: se anula, o se rectifica, y en los
                    // dos casos las filas quedan.
                    //
                    // No entra en INMUTABLES, y es deliberado: su `estado` SI cambia en el
                    // sitio —observar, anular y sustituir son eso, y no llevan mas contenido que
                    // quien, cuando y por que, que es una fila de auditoria—. Lo que impide tocar
                    // las demas columnas no es este escaner sino V54, que le concede a kamayuk_app
                    // el
                    // UPDATE sobre `estado` y sobre ninguna otra.
                    "declaracion_jurada",
                    "auditoria");

    /**
     * Tablas que ademas no se actualizan: el libro de asientos (ADR-0006), la auditoria (ADR-0008)
     * y la traza del cambio de numero de papeleta. Se corrigen agregando, no editando.
     */
    static final Set<String> INMUTABLES =
            Set.of(
                    "cuenta_corriente_asiento",
                    "auditoria",
                    // Con #9. Entra en las DOS listas, al reves que `declaracion_jurada`, que solo
                    // esta en PROTEGIDAS: aquella tiene un `estado` que SI cambia en el sitio
                    // —observar, anular y sustituir son eso—, y esta no tiene ninguna columna de
                    // estado que mover. Lo unico que se podria editar de ella es el monto, que es
                    // la cifra determinada, y esa no se corrige: se determina otra vez.
                    "determinacion_arbitrio",
                    "papeleta_cambio_numero",
                    // Una diligencia de notificacion y un pase a coactiva son actos, no estados de
                    // un proceso: no se corrigen en el sitio. Un intento no hallado se reintenta
                    // con otra fila (#39); un movimiento equivocado se corrige con otro
                    // movimiento. V28 les revoca el privilegio de UPDATE, y esto rompe el build
                    // antes de que nadie lo descubra en ejecucion.
                    "notificacion",
                    "valor_movimiento",
                    "prescripcion",
                    // Y el recibo, con #33. Es el caso mas claro de todos: el contribuyente se
                    // lleva el papel. Corregir el recibo en la base deja al papel y al sistema
                    // diciendo cosas distintas, y quien tenga el papel gana la discusion. Su
                    // desglose esta congelado por el mismo motivo (V29): la reimpresion tiene que
                    // salir identica al original aunque el libro haya seguido moviendose. La
                    // anulacion y el duplicado (#34) se registran agregando, no editando.
                    "recibo",
                    "recibo_detalle",
                    // Y lo que le pasa al recibo, con #34. Una anulacion y un duplicado son
                    // actos sobre un documento, no el estado de un proceso: no se corrigen en
                    // el sitio. V30 le revoca el UPDATE, y esto lo rompe antes, en el build.
                    // Es ademas lo que impide la salida comoda que V29 dejo abierta: en vez de
                    // editar el recibo -que ya no se puede-, editar el movimiento que dice si
                    // esta anulado, que es lo mismo con un rodeo.
                    "recibo_movimiento",
                    // Y el convenio de fraccionamiento con su cronograma y sus actos, con #35.
                    // Mismo caso que el recibo: el contribuyente firma el compromiso de pago y
                    // se lo lleva. V31 les revoca el UPDATE y retira las columnas de estado que
                    // V3 les habia puesto -decian VIGENTE para siempre-; el estado se deriva de
                    // convenio_movimiento. La deuda acogida se congela igual que el desglose del
                    // recibo, y un quiebre registrado por error se corrige con otro convenio, no
                    // reescribiendo el acta.
                    "convenio",
                    "convenio_cuota",
                    "convenio_deuda",
                    "convenio_movimiento",
                    // Y el turno de caja con su cierre, con #36. Tercera vez seguida y por el
                    // mismo camino: V32 le retira a `cierre_caja` las columnas de cierre que V3
                    // le habia puesto -decian ABIERTO para siempre-, y el arqueo pasa a
                    // `cierre_turno`, que solo se agrega. Un cierre no se modifica ni se borra:
                    // se reversa con otro registro que lo deja sin efecto y reabre el turno
                    // (regla 4). Editar el acta dejaria el papel firmado por el cajero y la base
                    // diciendo cosas distintas.
                    //
                    // `cierre_caja` es EL CASO ESPECIAL de esta lista, y conviene saberlo:
                    // conserva el privilegio de UPDATE, y aqui esta el unico sitio que lo
                    // protege. No es un descuido de V32: `SELECT ... FOR UPDATE` exige el
                    // privilegio de UPDATE en PostgreSQL, y esa fila es el punto donde se
                    // serializa la ventanilla desde V29. Revocarlo dejaria la caja sin poder
                    // cobrar. Ver V32 §1.bis.
                    "cierre_caja",
                    "cierre_turno",
                    "cierre_turno_detalle",
                    // Y el expediente coactivo con sus valores y su historial, con #40. Cuarta vez
                    // seguida y por el mismo camino: V33 le retira a `expediente_coactivo` las
                    // columnas de estado que V3 le habia puesto -decian ABIERTO para siempre- y le
                    // revoca el UPDATE junto con el de `expediente_valor`. El estado se deriva de
                    // `expediente_movimiento`, que solo se agrega.
                    //
                    // Aqui el REVOKE SI se pudo, al reves que con `cierre_caja` (V32 §1.bis):
                    // ninguna fila del expediente necesita `FOR UPDATE`, porque lo que se
                    // serializa es el correlativo y eso lo hace su propia tabla con un UPDATE
                    // atomico. Si algun dia hiciera falta bloquear el expediente, esta lista
                    // pasaria a ser lo unico que lo protege, como pasa con la caja.
                    "expediente_coactivo",
                    "expediente_valor",
                    "expediente_movimiento",
                    // Y el acto del procedimiento, con #41. V34 le retira el UPDATE por lo mismo
                    // que V28 se lo retiro a `notificacion`: una REC se NOTIFICA al obligado, que
                    // se lleva el papel. Corregirla en la base deja al papel notificado y al
                    // sistema diciendo cosas distintas, y quien tenga el papel gana la discusion.
                    // Un acto equivocado se deja sin efecto con otro acto -un levantamiento, una
                    // suspension-, y los dos quedan.
                    "acto_coactivo",
                    // Y la liquidacion de costas, con #42. Sexta vez por el mismo camino: V35 no
                    // le concede UPDATE a `liquidacion_costas` ni a `costa_obligacion`, y se lo
                    // retira a `costa_procesal`. El motivo es el de siempre y aqui es literal: el
                    // importe de la liquidacion YA ESTA ASENTADO en el libro como cargo. Corregir
                    // la fila dejaria el cargo diciendo una cifra y la liquidacion otra, y la que
                    // se cobra en ventanilla es la del libro. Una costa mal liquidada se arregla
                    // reversando su asiento y liquidando de nuevo.
                    //
                    // `costa_obligacion` esta aqui ademas por su propio motivo: cambiarle el
                    // expediente en el sitio moveria las costas de un procedimiento a otro sin
                    // dejar rastro, y es la unica fila que sabe de quien son.
                    "liquidacion_costas",
                    "costa_procesal",
                    "costa_obligacion",
                    // Y la licencia de funcionamiento con sus duplicados y su historial, con #44.
                    // Septima vez seguida y por el mismo camino: V37 le retira a
                    // `licencia_funcionamiento` las columnas de estado que V4 le habia puesto
                    // -decian VIGENTE para siempre- y le revoca el UPDATE junto con el de
                    // `licencia_duplicado`. El estado se deriva de `licencia_movimiento`, que solo
                    // se agrega.
                    //
                    // Aqui el REVOKE SI se pudo, al reves que con `cierre_caja` (V32 §1.bis), y no
                    // por casualidad: el ordinal del siguiente duplicado se serializa con
                    // `licencia_duplicado_uq` y no con un `SELECT ... FOR UPDATE` sobre la
                    // licencia, precisamente para que el privilegio se pudiera retirar.
                    "licencia_funcionamiento",
                    "licencia_duplicado",
                    "licencia_movimiento",
                    // Y con #50, la octava vez y por el mismo camino. V41 le retira a `descargo`
                    // las columnas de resultado que V4 le habia puesto -el fallo dentro del
                    // escrito que otro presento- y a `internamiento` la `fecha_salida`, y les
                    // revoca el UPDATE. `resolucion_gerencia` e `internamiento_movimiento` nacen
                    // sin el.
                    //
                    // La resolucion es el caso claro: se NOTIFICA al administrado, que se lleva el
                    // papel. Corregirla en la base deja al papel notificado y al sistema diciendo
                    // cosas distintas, y quien tenga el papel gana la discusion. Una equivocada se
                    // deja sin efecto con otra, y las dos quedan. El internamiento es el otro: su
                    // salida es un acto con su acta, no una fecha que se rellena encima del
                    // ingreso.
                    "descargo",
                    "resolucion_gerencia",
                    "internamiento",
                    "internamiento_movimiento",
                    // Y la liquidacion de fiscalizacion, con #49. Novena vez seguida por el
                    // mismo camino, y aqui aplicado desde el principio: V39 nace SIN columna
                    // de estado y sin conceder UPDATE, en vez de retirarlos despues. Una
                    // liquidacion se NOTIFICA al contribuyente, que se lleva el papel;
                    // corregirla en la base deja al papel y al sistema diciendo cosas
                    // distintas, y quien tenga el papel gana la discusion. Se reliquida -otra
                    // version que la referencia- o se anula con un movimiento.
                    //
                    // El detalle entra por lo mismo que `recibo_detalle`: es el desglose
                    // congelado que explica la cifra notificada. Y el movimiento, por lo mismo
                    // que `recibo_movimiento` (#34): si la cabecera ya no se puede tocar, la
                    // tentacion siguiente es corregir la fila que dice en que estado esta.
                    "liquidacion_fiscalizacion",
                    "liquidacion_detalle",
                    "liquidacion_movimiento",
                    // Y el FUE de edificacion con sus secciones, sus movimientos y sus vigencias,
                    // con #48. Decima vez seguida por el mismo camino: V43 le retira a
                    // `licencia_edificacion` las columnas de estado y de valor de obra que V4 le
                    // habia puesto, y le revoca el UPDATE; las cinco tablas de seccion no lo
                    // reciben nunca, porque se VERSIONAN.
                    //
                    // Aqui hay ademas un motivo propio y es el que mas pesa: `valor_obra` era una
                    // columna. Corregirla en el sitio dejaria el papel que el administrado exhibe
                    // en la obra y la base diciendo cifras distintas, y esa cifra es la base del
                    // derecho de tramite que se le cobro. Se retiro entera: la valorizacion se
                    // calcula contra el cuadro de #17 y no se guarda (AC 2).
                    "licencia_edificacion",
                    "edificacion_terreno",
                    "edificacion_proyecto",
                    "edificacion_estructura",
                    "edificacion_profesional",
                    "edificacion_requisito",
                    "edificacion_movimiento",
                    "edificacion_vigencia",
                    // Y la autorizacion de anuncio con su historial, con #51. Undecima vez seguida
                    // y
                    // por el mismo camino: V45 le retira a `anuncio` la columna de estado que V4 le
                    // habia puesto -decia VIGENTE para siempre- y le revoca el UPDATE. El estado se
                    // deriva de `anuncio_movimiento`, que solo se agrega.
                    //
                    // Aqui hay ademas un motivo que ninguna de las siete anteriores tenia: la fila
                    // del movimiento lleva `referencia_cargo`, que es la MISMA cadena con la que la
                    // tasa entro en el libro, y su indice unico es lo unico que impide cobrarla dos
                    // veces. Poder editarla en el sitio seria poder devengar de nuevo el mismo
                    // ejercicio cambiando una letra.
                    "anuncio",
                    "anuncio_movimiento",
                    // Y la transferencia a rentas con su resolucion, con #52. Duodecima vez por el
                    // mismo camino: V49 nace sin conceder UPDATE. Aqui el motivo es doble y el
                    // segundo no lo tenia ninguna de las nueve anteriores: la resolucion se
                    // NOTIFICA al contribuyente, que se lleva el papel, y ademas su cargo YA ESTA
                    // en el libro y la version nueva de la ficha YA ESTA inscrita. Corregir la fila
                    // dejaria al papel, al libro, al padron y a la base diciendo cuatro cosas
                    // distintas, y la que se cobra en ventanilla es la del libro.
                    "resolucion_determinacion",
                    // Y con #53, la decimotercera vez y por el mismo camino. V47 nace
                    // `papeleta_masivo` y
                    // `constancia_libre` sin UPDATE.
                    //
                    // La constancia es el caso claro: se ENTREGA al administrado, que se lleva el
                    // papel. Corregirla en la base deja al papel y al sistema diciendo cosas
                    // distintas, y quien tenga el papel gana la discusion. Una equivocada se deja
                    // sin efecto con otra, y las dos quedan.
                    //
                    // El criterio de la corrida es el otro, y su motivo es propio: `fecha_criterio`
                    // congela a que dia se evaluo la deuda y el plazo de cada candidato. Editarla
                    // despues de generar dejaria la corrida diciendo que emitio con un criterio que
                    // no es el que uso, y no habria manera de reconstruirlo.
                    //
                    // `papeleta_masivo_item` NO entra, y es deliberado: su estado es la marca de
                    // progreso de un proceso interno -PENDIENTE a GENERADO, SIN_DEUDA o
                    // NO_PROCEDE-, no un acto administrativo. Mismo reparto que V27 hizo entre
                    // `valor_masivo` y `valor_masivo_item`.
                    "papeleta_masivo",
                    "constancia_libre",
                    // Y el certificado de numeracion y zonificacion, con #54. Decimocuarta vez, y
                    // aqui aplicado desde el principio como en V39: V51 crea `certificado` SIN
                    // conceder UPDATE ni DELETE, en vez de retirarlos despues.
                    //
                    // El motivo es el de siempre y aqui es literal: el certificado se ENTREGA al
                    // administrado, que lo presenta ante un notario, un banco o el Ministerio de
                    // Vivienda. Corregirlo en la base deja al papel y al sistema diciendo cosas
                    // distintas, y quien tiene el papel gana la discusion. Uno equivocado se
                    // sustituye emitiendo otro —con su numero y su derecho de tramite—, y los dos
                    // quedan.
                    //
                    // Tiene ademas un motivo propio: `vigencia_hasta` es una fecha COPIADA del
                    // parametro sellado que regia el dia de la emision. Poder moverla en el sitio
                    // seria poder alargar un certificado ya entregado sin que nada lo delate, y
                    // esa fecha es la que decide si una obra se autoriza con los parametros de hoy
                    // o con los de hace diez anios.
                    "certificado");
}
