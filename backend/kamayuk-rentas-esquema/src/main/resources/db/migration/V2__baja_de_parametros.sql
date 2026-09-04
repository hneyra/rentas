-- ============================================================================
--  V2 — LA BAJA DE LOS VALORES NORMATIVOS (P5B, ADR-0025)
--
--  Seis tablas se van a `normativa`, que desde esta etapa es un repositorio y un
--  despliegue aparte:
--
--    parametro_tributario          la cifra con su doble firma (ADR-0007)
--    conjunto_parametros           el juego sellado por ejercicio
--    conjunto_parametro_detalle    la bisagra: que contiene un conjunto sellado
--    valor_unitario_edificacion    ┐
--    depreciacion                  ├ los tres cuadros NACIONALES (ADR-0017, D-13)
--    valor_referencial_vehiculo    ┘
--
--  `arancel` NO se va, y no es un olvido: es MUNICIPAL —se carga por via y se
--  corrige por municipalidad— asi que se queda con `via`, o sea con `catastro`
--  (GOB-05 ✅ D-N4).
--
--  NO HAY MIGRACION DE DATOS, y se puede decir con seguridad: ADR-0032 §2 y
--  GOB-05 §7.1 lo miden — no hay datos reales en `prod`, en `stg` ni en el
--  compose local. Lo que hay es la instalacion de demostracion, que se vuelve a
--  sembrar. Por eso esto es un DROP y no un traslado.
--
--  LO QUE LAS OTRAS TABLAS CONSERVAN, Y POR QUE
--  --------------------------------------------
--  Ocho tablas guardan un `conjunto_id`: `arancel`, `conjunto_parametro_detalle`
--  -que se va-, `descargo`, `determinacion`, `determinacion_arbitrio`,
--  `liquidacion_detalle`, `notificacion` y `prescripcion`. La COLUMNA se queda en
--  todas; lo que se retira es su CLAVE FORANEA, porque la tabla a la que apunta
--  ya no esta en esta base.
--
--  Que se quede la columna no es inercia: ADR-0025 §3 lo exige. «Toda valuacion y
--  toda determinacion guardan el conjuntoId con que se calcularon; sin eso, un
--  recalculo no es una verificacion, es un calculo nuevo que casualmente se
--  parece». El `conjunto_id` sigue siendo la unica forma de recuperar EL conjunto
--  concreto que una determinacion uso, y ahora se recupera de la cache local de
--  `V3` o, en su defecto, de `normativa`.
--
--  LO QUE SE PIERDE, DICHO AQUI Y NO DESCUBIERTO MAS TARDE
--  -------------------------------------------------------
--  `arancel_de_conjunto_sellado_inmutable` (V18) impedia escribir un arancel
--  cuyo conjunto ya estuviera sellado, consultando `conjunto_parametros`. Esa
--  tabla se va, asi que el disparador NO PUEDE seguir: una funcion que consulta
--  una tabla inexistente no protege nada, revienta en el primer INSERT.
--
--  Se retira, y la garantia que daba QUEDA ABIERTA. No se sustituye aqui por una
--  contra la cache de `V3` por dos motivos: la cache guarda solo lo SELLADO —de
--  modo que «no esta en la cache» significa las dos cosas a la vez, «no esta
--  sellado» y «no se ha descargado»— y, sobre todo, `arancel` se va a `catastro`
--  en P5C y la proteccion tiene que reconstruirse alli, donde estara la tabla.
--  Queda escrito en `docs/00-gobierno/P5B-extraccion.md` §7 como hueco declarado.
--
--  Las otras dos funciones de disparador que consultaban lo que se va
--  -`detalle_de_conjunto_sellado_es_inmutable` y
--  `valuacion_de_publicacion_sellada_es_inmutable`- se van CON sus tablas, y en
--  `normativa` siguen puestas: alli protegen lo mismo que protegian aqui.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. Las claves foraneas, ANTES que las tablas
--
--  El orden importa y no es una preferencia: `DROP TABLE` sin `CASCADE` falla
--  mientras exista una FK que apunte a la tabla, y con `CASCADE` se llevaria por
--  delante lo que apunte a ella SIN QUE SE VEA EN EL DIFF. Retirarlas una a una
--  y por su nombre es lo que hace que esta migracion diga exactamente que deja
--  de estar garantizado.
-- ----------------------------------------------------------------------------

ALTER TABLE arancel DROP CONSTRAINT IF EXISTS arancel_conjunto_fk;
ALTER TABLE descargo DROP CONSTRAINT IF EXISTS descargo_conjunto_fk;
ALTER TABLE determinacion DROP CONSTRAINT IF EXISTS determinacion_conjunto_fk;
ALTER TABLE determinacion_arbitrio DROP CONSTRAINT IF EXISTS det_arbitrio_conjunto_fk;
ALTER TABLE liquidacion_detalle DROP CONSTRAINT IF EXISTS liquidacion_detalle_conjunto_fk;
ALTER TABLE notificacion DROP CONSTRAINT IF EXISTS notificacion_conjunto_fk;
ALTER TABLE prescripcion DROP CONSTRAINT IF EXISTS prescripcion_conjunto_fk;

-- ----------------------------------------------------------------------------
--  2. El disparador del arancel, y su funcion
-- ----------------------------------------------------------------------------

DROP TRIGGER IF EXISTS arancel_de_conjunto_sellado_inmutable ON arancel;
DROP FUNCTION IF EXISTS valuacion_de_conjunto_sellado_es_inmutable();

-- ----------------------------------------------------------------------------
--  3. Las seis tablas
--
--  En orden de dependencia: primero las que apuntan, despues las apuntadas. Sus
--  disparadores, politicas RLS, indices y privilegios se van con ellas -PostgreSQL
--  los borra con la tabla-, y por eso no hay aqui un DROP POLICY por cada una.
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS conjunto_parametro_detalle;
DROP TABLE IF EXISTS conjunto_parametros;
DROP TABLE IF EXISTS valor_unitario_edificacion;
DROP TABLE IF EXISTS depreciacion;
DROP TABLE IF EXISTS valor_referencial_vehiculo;
DROP TABLE IF EXISTS parametro_tributario;

DROP FUNCTION IF EXISTS detalle_de_conjunto_sellado_es_inmutable();
DROP FUNCTION IF EXISTS conjunto_sellado_es_inmutable();
DROP FUNCTION IF EXISTS valuacion_de_publicacion_sellada_es_inmutable();

-- ----------------------------------------------------------------------------
--  4. El rol de carga deja de tener nada que cargar aqui
--
--  `rol_carga_parametros` es la unica credencial que puede escribir un valor
--  normativo (ADR-0007 §5), y desde P5B eso pasa en la base de `normativa`. El
--  ROL NO SE BORRA -es del CLUSTER, que los cuatro sistemas comparten, y sigue
--  haciendo su trabajo alli-; lo que se retira es su `CONNECT` a ESTA base, que
--  es lo unico que le quedaba y que ya no le sirve para nada.
--
--  Una credencial que puede conectarse a una base donde no tiene nada que hacer
--  es una credencial de mas, que es exactamente lo que #155 midio con el rol del
--  respaldo.
--
--  PERO EL `REVOKE CONNECT` NO SE HACE AQUI, Y HAY QUE DECIR POR QUE: es un
--  privilegio sobre la BASE, no sobre una tabla, y solo lo puede retirar quien la
--  posee. `sgtm_owner` -que es quien corre esta migracion- a proposito NO es
--  dueno de la base (lo midio #722 al intentar crear un esquema: «permission
--  denied for database»), asi que la sentencia fallaria y dejaria la instalacion
--  sin migrar. Le toca a `crear-roles.sql`, que corre como superusuario y es
--  donde el `GRANT CONNECT` se dio.
--
--  Queda como hueco declarado en `docs/00-gobierno/P5B-extraccion.md` §7: hasta
--  que se retire, `rol_carga_parametros` puede conectarse a la base de `rentas`
--  y no tiene ni una tabla sobre la que escribir.
-- ----------------------------------------------------------------------------

COMMENT ON COLUMN determinacion.conjunto_id IS
    'El conjunto de parametros con que se calculo. Desde P5B vive en `normativa`: '
    'aqui es un identificador sin clave foranea, y se resuelve por la cache local '
    'de `normativa_conjunto` (ADR-0025 §1 y §3)';
