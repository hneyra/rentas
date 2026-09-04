-- ============================================================================
--  V7 — LA BAJA DE CAJA (P5D, ADR-0026 y ADR-0029)
--
--  Va aparte de las anteriores por lo mismo que `V6` iba aparte de `V4` y `V5`:
--  aquellas dicen QUE LLEGA y esta dice QUE SE VA. Leidas juntas cuentan la
--  extraccion; leida cada una sola sigue siendo entendible.
--
--  QUE SE VA, Y A DONDE
--  --------------------
--  Las diez tablas por las que entra el dinero, que desde P5D viven en el repositorio
--  `caja` con su propio esquema:
--
--    recibo, recibo_detalle, recibo_movimiento, recibo_correlativo   el papel y lo que le pasa
--    caja, cierre_caja, cierre_turno, cierre_turno_detalle           la ventanilla, su turno y su arqueo
--    tasa                                                            el catalogo del TUPA que cobra
--    area                                                            la unidad organica a la que se recauda
--
--  Lo que se queda de `kamayuk-rentas-tesoreria` NO es nada: es el CONVENIO DE
--  FRACCIONAMIENTO entero —`convenio`, `convenio_cuota`, `convenio_movimiento`,
--  `convenio_correlativo` y `convenio_deuda`, con su dominio y sus dos repositorios—,
--  porque un convenio es DEUDA REPROGRAMADA: tiene interes, tiene quiebre y tiene
--  consecuencias coactivas (ADR-0026 §5). Si viajara a `caja`, `caja` adquiriria reglas
--  tributarias y dejaria de poder cobrar un puesto de mercado.
--
--  Y se quedan los TRES PUERTOS que el resto de `rentas` ya usaba para preguntarle a la
--  caja —`RecibosDeTramite`, `AvanceDeCaja` y `CobrosDeTasas`—, que ahora los implementa
--  un cliente HTTP. Ni una de las clases de `licencias`, `sanciones`, `coactiva` o
--  `indicadores` que los consumen cambio una linea: eran el contrato y ahora se cobra.
--
--  EL ORDEN IMPORTA, Y NO ES ESTETICO
--  ----------------------------------
--  Primero las CLAVES FORANEAS y despues las tablas. Al reves, PostgreSQL exigiria
--  `CASCADE` sobre cada `DROP TABLE`, y eso se llevaria por delante lo que apunte a
--  ella SIN QUE SE VEA EN EL DIFF. Es la misma decision que `V2` tomo en P5B y `V6` en
--  P5C, y por el mismo motivo: aqui cada linea dice exactamente que garantia se retira.
--
--  SON CINCO Y NO OCHO
--  -------------------
--  El enunciado de esta etapa hablaba de OCHO claves foraneas hacia `recibo` —desde
--  `convenio`, `convenio_cuota`, `convenio_movimiento`, `licencia_funcionamiento`,
--  `licencia_duplicado`, `licencia_edificacion`, `edificacion_movimiento` y
--  `certificado`—. Medidas contra el baseline con `grep -n "REFERENCES recibo"` son
--  SIETE, y dos de esas siete son de tablas que se van en este mismo archivo
--  (`recibo_detalle` y `recibo_movimiento`), asi que las que hay que retirar a mano son
--  CINCO.
--
--  Las tres que faltan NO EXISTEN, y no es que se les olvidara la clave foranea: es que
--  esas tablas **no tienen columna `recibo_id`**. Se comprobo una a una:
--
--    convenio              su recibo es el de la FORMALIZACION, y vive en
--                          `convenio_movimiento.recibo_id` — la cabecera no lo repite,
--                          porque un convenio se formaliza una vez y se cierra con otro
--                          movimiento
--    convenio_cuota        una cuota del cronograma es una PROMESA de pago; el recibo
--                          que la cancela llega por la cuenta corriente, no por aqui
--    licencia_edificacion  su recibo esta en `edificacion_movimiento.recibo_id`, una fila
--                          por EMISION y por REVALIDACION (V43), porque una licencia de
--                          obra se paga mas de una vez
--
--  No se inventa aqui un `DROP` de algo que no hay. Es el mismo hallazgo que `V6` dejo
--  escrito para `cuenta_corriente_asiento.predio_id`.
--
--  QUE GARANTIA SE PIERDE, DICHA UNA VEZ
--  -------------------------------------
--  Las cinco acreditaban exactamente lo mismo: **«esto se pago antes de emitirse»**. El
--  motor garantizaba que el `recibo_id` guardado en un certificado, en una licencia, en
--  su duplicado, en un movimiento de edificacion o en la formalizacion de un convenio
--  apuntara a un recibo que existe. Desde hoy no lo garantiza nadie.
--
--  Lo que lo sustituye no es una comprobacion mas debil de lo mismo, sino DOS COSAS:
--
--    1. La comprobacion pasa a ser una llamada al puerto `RecibosDeTramite`, QUE YA
--       EXISTE desde #44 y no se inventa aqui. Y ese puerto siempre comprobo MAS de lo
--       que la clave foranea podia: que el recibo sea del titular, que sea de caja de
--       tasas, que NO ESTE ANULADO y que cubra el concepto del TUPA. Un `FOREIGN KEY` no
--       sabe hacer ese `JOIN` —lo dice ya el `COMMENT` de `licencia_funcionamiento.recibo_id`
--       en `V1`—, asi que lo que se pierde es la mitad barata de la comprobacion, no la
--       que decide.
--    2. El evento `PagoRegistrado` (ADR-0026 §3) mas la CONCILIACION DIARIA. Con la caja
--       en otra base, el cierre de turno de `caja` y los abonos aplicados en `rentas`
--       tienen que cuadrar todos los dias, y si no cuadran **el dia no cierra**. Eso deja
--       de ser buena practica y pasa a ser obligacion operativa: es literalmente lo que
--       ADR-0026 §3 dice que se paga por que la ventanilla pueda cobrar con `rentas`
--       caido.
--
--  Es PEOR que una clave foranea, y por eso se escribe aqui y no en un comentario de
--  codigo.
--
--  LAS COLUMNAS SE QUEDAN
--  ----------------------
--  `certificado.recibo_id`, `licencia_funcionamiento.recibo_id`,
--  `licencia_duplicado.recibo_id`, `edificacion_movimiento.recibo_id` y
--  `convenio_movimiento.recibo_id` NO se retiran: son la prueba de con que pago se
--  emitio cada papel, y perderlas seria perder por que se emitio lo que se emitio. Lo
--  unico que se retira es la garantia del motor. Es el mismo reparto que `V2` hizo con
--  `determinacion.conjunto_id` y `V6` con `declaracion_jurada.predio_id`.
--
--  Tampoco se toca ningun `CHECK`: `convenio_movimiento_formalizacion_ck` sigue exigiendo
--  que una FORMALIZACION traiga su `recibo_id` y su cuota, y
--  `edificacion_movimiento_recibo_ck` sigue exigiendo que lo traigan la EMISION y la
--  REVALIDACION y solo ellas. Esas dos reglas son de ESTE sistema —dicen cuando hace
--  falta un recibo, no que el recibo exista— y se pueden seguir comprobando sin la caja
--  delante.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. Las cinco claves foraneas contra `recibo` desde tablas que se quedan
--
--  Una a una y por nombre. Nunca `CASCADE`. Los nombres se leyeron del baseline, no se
--  dedujeron del patron: `licencia_funcionamiento` la llama `licencia_recibo_fk` y no
--  `licencia_funcionamiento_recibo_fk`, que es lo que un patron habria escrito.
-- ----------------------------------------------------------------------------

ALTER TABLE certificado DROP CONSTRAINT certificado_recibo_fk;
ALTER TABLE convenio_movimiento DROP CONSTRAINT convenio_movimiento_recibo_fk;
ALTER TABLE edificacion_movimiento DROP CONSTRAINT edificacion_movimiento_recibo_fk;
ALTER TABLE licencia_duplicado DROP CONSTRAINT licencia_duplicado_recibo_fk;
ALTER TABLE licencia_funcionamiento DROP CONSTRAINT licencia_recibo_fk;

-- ----------------------------------------------------------------------------
--  2. Hacia `caja`, `tasa`, `area`, `cierre_caja` y `cierre_turno`: NINGUNA, y se midio
--
--  El enunciado pedia buscarlas. Se busco —`REFERENCES caja(`, `REFERENCES tasa(`,
--  `REFERENCES area(`, `REFERENCES cierre_caja(` y `REFERENCES cierre_turno(` sobre el
--  baseline— y las trece que salen son TODAS de tablas que se van en el bloque 4:
--  `caja_area_fk`, `tasa_area_fk`, `cierre_caja_fk`, `cierre_turno_turno_fk`,
--  `cierre_turno_revierte_fk`, `cierre_turno_detalle_cierre_fk`, `recibo_caja_fk`,
--  `recibo_turno_fk`, `recibo_detalle_tasa_fk`, `recibo_detalle_recibo_fk`,
--  `recibo_movimiento_caja_fk`, `recibo_movimiento_recibo_fk` y
--  `recibo_movimiento_turno_fk`. Se van con su tabla; retirarlas antes seria escribir
--  trece lineas que no cambian nada.
--
--  O sea: NINGUNA tabla que se queda apunta a la ventanilla, a su turno, a su arqueo, al
--  catalogo del TUPA ni al area. La caja se enlaza con el resto del sistema **por el
--  recibo y solo por el recibo**, que es exactamente lo que el bloque 1 acaba de
--  desatar, y esa es la medida de lo acoplada que estaba.
--
--  `recibo.contribuyente_id` -> `contribuyente` va en la direccion contraria —de `caja`
--  hacia `rentas`— y se va con `recibo`. Era el ultimo cruce vivo de la lista de
--  `CrucesConsentidosDelSgtm` (PENDIENTE-CRUCE-06) y en `caja` se cerro copiando el
--  pagador en el propio recibo. Ver la nota de cierre de esa clase.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
--  3. Disparadores: NINGUNO, y tambien se midio
--
--  El baseline declara ocho `CREATE TRIGGER` y ninguno esta sobre una de las diez
--  tablas: son los de conjunto sellado, valuacion, declaracion jurada y documento
--  emitido. La inmutabilidad del recibo y del cierre NO la sostiene un disparador sino
--  privilegios —`REVOKE UPDATE` (V29, V30)— y el escaner de fuentes, que es la unica
--  guarda de `cierre_caja`: ahi el `REVOKE` no se pudo hacer porque `SELECT ... FOR
--  UPDATE` exige el privilegio de UPDATE y esa fila es donde se serializa la ventanilla
--  (DAT-01 §6, #36).
--
--  Los privilegios y las politicas RLS de las diez se van con su tabla; no hay que
--  revocarlos a mano.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
--  4. Las diez tablas, en orden de dependencia
--
--  El detalle del arqueo antes que el arqueo, el arqueo antes que el turno, lo que le
--  pasa al recibo antes que el recibo, el recibo antes que su serie, y al final la
--  ventanilla, el catalogo del TUPA y el area, que es de quien cuelgan las dos.
--
--  Sin `CASCADE`: si quedara algo apuntando a una de ellas, el `DROP` tiene que fallar y
--  decirlo, no llevarselo por delante.
-- ----------------------------------------------------------------------------

DROP TABLE cierre_turno_detalle;
DROP TABLE cierre_turno;

DROP TABLE recibo_movimiento;
DROP TABLE recibo_detalle;
DROP TABLE recibo_correlativo;
DROP TABLE recibo;

DROP TABLE cierre_caja;
DROP TABLE caja;
DROP TABLE tasa;
DROP TABLE area;

-- ----------------------------------------------------------------------------
--  5. Funciones que solo ellas usaban: NINGUNA, y se comprobo antes de escribirlo
--
--  El baseline declara nueve funciones —`conjunto_sellado_es_inmutable`,
--  `declaracion_jurada_estado_es_terminal`, `detalle_de_conjunto_sellado_es_inmutable`,
--  `documento_solo_cuenta_reimpresiones`, `nombre_normalizado`,
--  `valuacion_de_conjunto_sellado_es_inmutable`,
--  `valuacion_de_publicacion_sellada_es_inmutable`,
--  `verificar_participacion_no_excede` y `verificar_titularidad_no_excede`— y las dos
--  ultimas ya las retiro `V6`. De las siete que quedan, **ninguna la usaba una tabla de
--  caja**: no hay un solo `CREATE TRIGGER` sobre las diez (bloque 3) y ninguna consulta
--  de este esquema las llama desde una de ellas.
--
--  Asi que aqui no va ningun `DROP FUNCTION`. Escribir uno «por simetria» con `V6`
--  fallaria con «function does not exist» y dejaria la instalacion sin migrar, o —peor,
--  si acertara con una que se comparte— se llevaria por delante un indice de otro
--  sistema, que es exactamente lo que le paso a `V6` con `nombre_normalizado`: el motor
--  lo dijo antes que ninguna revision.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
--  6. Lo que NO se hace aqui, y por que
--
--  No se revoca `CONNECT` a nadie: el `REVOKE CONNECT` es un privilegio SOBRE LA BASE y
--  solo lo puede retirar su dueno; `sgtm_owner` a proposito no lo es (#722, «permission
--  denied for database»), asi que la sentencia fallaria y dejaria la instalacion sin
--  migrar. Es la misma nota que `V6`.
--
--  No se toca ninguna columna `recibo_id` ni ningun `CHECK` que la mire: ver la cabecera.
--
--  Y no se borra ni una fila. Las diez tablas se van enteras porque su contenido vive
--  ahora en otra base —y hoy no hay datos reales en `prod`, en `stg` ni en el compose
--  local (ADR-0032 §2)—. Lo que la regla 4 prohibe es borrar FILAS de deuda, pagos,
--  recibos, valores, asientos o auditoria; `recibo`, `recibo_detalle` y
--  `recibo_movimiento` estan en `TABLAS_PROTEGIDAS` por eso, y ese invariante viaja con
--  ellas a `caja`, donde la lista lo vuelve a declarar.
-- ----------------------------------------------------------------------------

COMMENT ON COLUMN licencia_funcionamiento.recibo_id IS
    'El recibo de caja de tasas con que se pago el derecho de tramite. NOT NULL desde V37: '
    'sin el pago del derecho no se emite (RF-110). Desde P5D es un identificador de OTRO '
    'SISTEMA: `caja` es su dueno y aqui ya no hay clave foranea que garantice que exista (V7). '
    'Lo que lo sostiene es el evento `PagoRegistrado` (ADR-0026 §3) mas la conciliacion diaria, '
    'y la comprobacion de que ademas sea del titular, no este anulado y cubra el concepto del '
    'TUPA la sigue haciendo EmitirLicenciaDeFuncionamiento contra el puerto RecibosDeTramite, '
    'igual que antes: eso nunca lo pudo hacer un CHECK ni una clave foranea';
COMMENT ON COLUMN certificado.recibo_id IS
    'El recibo con que se pago el derecho del certificado. Identificador de `caja` desde P5D, '
    'sin clave foranea (V7): lo sostienen el evento `PagoRegistrado` (ADR-0026 §3) y la '
    'conciliacion diaria. La columna se queda porque es la prueba de con que pago se emitio '
    'este papel, y el correlativo ya esta gastado';
COMMENT ON COLUMN licencia_duplicado.recibo_id IS
    'El recibo del derecho de duplicado. Identificador de `caja` desde P5D, sin clave foranea '
    '(V7): lo sostienen el evento `PagoRegistrado` (ADR-0026 §3) y la conciliacion diaria';
COMMENT ON COLUMN edificacion_movimiento.recibo_id IS
    'El recibo del derecho de tramite de la EMISION o de la REVALIDACION —el CHECK '
    'edificacion_movimiento_recibo_ck lo exige en esas dos y solo en esas—. Identificador de '
    '`caja` desde P5D, sin clave foranea (V7): lo sostienen el evento `PagoRegistrado` '
    '(ADR-0026 §3) y la conciliacion diaria. El CHECK se queda porque dice CUANDO hace falta un '
    'recibo, no que el recibo exista, y eso se comprueba sin la caja delante';
COMMENT ON COLUMN convenio_movimiento.recibo_id IS
    'El recibo que cobro la cuota inicial al formalizar. Identificador de `caja` desde P5D, sin '
    'clave foranea (V7): lo sostienen el evento `PagoRegistrado` (ADR-0026 §3) y la conciliacion '
    'diaria. El convenio se QUEDA en rentas —es deuda reprogramada, ADR-0026 §5— y por eso esta '
    'columna es hoy el unico enlace del fraccionamiento con la ventanilla que lo cobro';
