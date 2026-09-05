-- ============================================================================
--  V6 — LA BAJA DE CATASTRO (P5C, ADR-0029)
--
--  Va aparte de `V4` y `V5` a proposito, por lo mismo que `V2` iba aparte de `V3` en
--  P5B: aquellas dicen QUE LLEGA —la proyeccion y la valuacion— y esta dice QUE SE VA.
--  Leidas juntas cuentan la extraccion; leida cada una sola sigue siendo entendible.
--
--  QUE SE VA, Y A DONDE
--  --------------------
--  Las quince tablas del contexto acotado `catastro`, que desde P5C viven en el
--  repositorio `catastro` con su propio esquema (su `V1__baseline.sql`, ADR-0032). Con
--  ellas se van sus disparadores y las tres funciones que solo ellas usaban.
--
--  Lo que queda en su lugar NO es nada: es `predio_ref` y `ficha_ref` (`V4`), la
--  proyeccion local alimentada por evento, y los NUEVE PUERTOS de
--  `kamayuk-rentas-catastro`, que ya eran el contrato y ahora los implementa un cliente
--  HTTP. Ni una de las veintisiete clases de `src/main` que los consumen cambio.
--
--  EL ORDEN IMPORTA, Y NO ES ESTETICO
--  ----------------------------------
--  Primero las CLAVES FORANEAS y despues las tablas. Al reves, PostgreSQL exigiria
--  `CASCADE` sobre cada `DROP TABLE`, y eso se llevaria por delante lo que apunte a
--  ella SIN QUE SE VEA EN EL DIFF. Es la misma decision que `V2` tomo en P5B y por el
--  mismo motivo: aqui cada linea dice exactamente que garantia se retira.
--
--  SON VEINTE Y NO TRES
--  --------------------
--  El enunciado de esta etapa hablaba de tres claves foraneas —`declaracion_jurada`,
--  `determinacion` y `cuenta_corriente_asiento`—. Medidas contra el baseline son
--  VEINTE: quince contra `predio` y cinco contra `ficha_catastral`. Y de las tres que
--  el enunciado nombraba, una NO EXISTE: **`cuenta_corriente_asiento.predio_id` nunca
--  tuvo clave foranea**. `V2` del monolito no la declaro, y #660 ya lo habia dejado
--  escrito al medir por que un asiento puede quedar apuntando a un predio que ya no
--  esta: «V2 no declara clave foranea sobre `predio` ni sobre `vehiculo`». No se
--  inventa aqui un `DROP` de algo que no hay.
--
--  QUE GARANTIA SE PIERDE, DICHA UNA VEZ
--  -------------------------------------
--  El motor deja de garantizar que un `predio_id` guardado en `rentas` exista. Es
--  literalmente el costo que ADR-0029 nombra —«se paga una clave foranea por una
--  invariante»— y lo que lo sustituye es `predio_ref`, que la proyeccion mantiene, mas
--  la verificacion diaria que ese ADR anticipa. Es PEOR que una clave foranea, y por
--  eso se escribe aqui y no en un comentario de codigo.
--
--  LAS COLUMNAS SE QUEDAN
--  ----------------------
--  `declaracion_jurada.predio_id`, `determinacion.predio_id`, `acta_fiscalizacion.ficha_id`
--  y las demas NO se retiran: son la referencia al hecho de catastro que sustenta cada
--  acto, y perderlas seria perder por que se determino lo que se determino. Lo unico que
--  se retira es la garantia del motor. Es el mismo reparto que `V2` hizo con
--  `determinacion.conjunto_id` cuando `normativa` se llevo `conjunto_parametros`.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. Las quince claves foraneas contra `predio`
--
--  Una a una y por nombre. Nunca `CASCADE`.
-- ----------------------------------------------------------------------------

ALTER TABLE acta_fiscalizacion DROP CONSTRAINT acta_fisc_predio_fk;
ALTER TABLE anuncio DROP CONSTRAINT anuncio_predio_fk;
ALTER TABLE beneficio DROP CONSTRAINT beneficio_predio_fk;
ALTER TABLE certificado DROP CONSTRAINT certificado_predio_fk;
ALTER TABLE declaracion_jurada DROP CONSTRAINT dj_predio_fk;
ALTER TABLE determinacion DROP CONSTRAINT determinacion_predio_fk;
ALTER TABLE determinacion_arbitrio DROP CONSTRAINT det_arbitrio_predio_fk;
ALTER TABLE determinacion_predio_detalle DROP CONSTRAINT det_predio_detalle_predio_fk;
ALTER TABLE licencia_edificacion DROP CONSTRAINT edificacion_predio_fk;
ALTER TABLE licencia_funcionamiento DROP CONSTRAINT licencia_predio_fk;
ALTER TABLE liquidacion_detalle DROP CONSTRAINT liquidacion_detalle_predio_fk;
ALTER TABLE notificacion_administrativa DROP CONSTRAINT notif_adm_predio_fk;
ALTER TABLE programa_muestra DROP CONSTRAINT programa_muestra_predio_fk;
ALTER TABLE resolucion_determinacion DROP CONSTRAINT resolucion_determinacion_predio_fk;
ALTER TABLE transferencia DROP CONSTRAINT transferencia_predio_fk;

-- ----------------------------------------------------------------------------
--  2. Las cinco contra `ficha_catastral`
--
--  Son cinco y no cuatro porque `resolucion_determinacion` tiene DOS: la ficha
--  anterior y la nueva. Esas dos son la prueba de que la transferencia a rentas
--  versiono el padron, y por eso las columnas se quedan aunque la garantia se vaya.
-- ----------------------------------------------------------------------------

ALTER TABLE acta_fiscalizacion DROP CONSTRAINT acta_fisc_ficha_fk;
ALTER TABLE declaracion_jurada DROP CONSTRAINT dj_ficha_catastral_fk;
ALTER TABLE licencia_funcionamiento DROP CONSTRAINT licencia_ficha_fk;
ALTER TABLE resolucion_determinacion DROP CONSTRAINT resolucion_determinacion_ficha_anterior_fk;
ALTER TABLE resolucion_determinacion DROP CONSTRAINT resolucion_determinacion_ficha_nueva_fk;

-- ----------------------------------------------------------------------------
--  3. Los disparadores de catastro
--
--  `arancel_de_conjunto_sellado_inmutable` ya estaba roto desde P5B —consultaba
--  `conjunto_parametros`, que se fue a `normativa`— y su funcion la retiro `V2`. Lo
--  que queda es el disparador huerfano, que se va con su tabla. La guarda vive ahora
--  en `catastro`, reconstruida contra la copia local de conjuntos sellados (su `V3`).
-- ----------------------------------------------------------------------------

DROP TRIGGER IF EXISTS arancel_de_conjunto_sellado_inmutable ON arancel;
DROP TRIGGER IF EXISTS participacion_no_excede_trg ON participacion_comun;
DROP TRIGGER IF EXISTS titularidad_no_excede_trg ON titularidad;

-- ----------------------------------------------------------------------------
--  4. Las quince tablas, en orden de dependencia
--
--  Las hijas de `ficha_catastral` primero, luego la ficha, luego lo que cuelga de
--  `predio`, luego el predio, y al final el catalogo territorial. Sin `CASCADE`: si
--  quedara algo apuntando a una de ellas, el `DROP` tiene que fallar y decirlo, no
--  llevarselo por delante.
-- ----------------------------------------------------------------------------

DROP TABLE actividad_economica;
DROP TABLE bien_comun;
DROP TABLE colindante_rural;
DROP TABLE construccion;
DROP TABLE otra_instalacion;
DROP TABLE participacion_comun;
DROP TABLE tierra_rural;
DROP TABLE ficha_catastral;

DROP TABLE arancel;
DROP TABLE inquilino;
DROP TABLE titularidad;
DROP TABLE predio;

DROP TABLE manzana;
DROP TABLE sector;
DROP TABLE via;

-- ----------------------------------------------------------------------------
--  5. Las DOS funciones que solo usaban esas tablas
--
--  Eran los invariantes de titularidad y de participacion, y ninguna tiene ya un
--  disparador que la use: una funcion que no puede dispararse no protege nada, y
--  dejarla diria que la regla sigue aqui cuando vive en `catastro`.
--
--  `nombre_normalizado` NO SE VA, y lo dijo el motor antes que ninguna revision:
--  «cannot drop function nombre_normalizado(text) because other objects depend on it —
--  index contribuyente_nombre_trgm_ix depends on it». No es de catastro: la comparten
--  `via.nombre_busqueda` (V66) y el indice de busqueda por aproximacion del PADRON DE
--  CONTRIBUYENTES (V11, RF-014), que se queda aqui. Que los dos sistemas la tengan cada
--  uno en su esquema es lo correcto —es una funcion de texto, no una regla de negocio—,
--  y `catastro` la conserva en su baseline por su lado.
-- ----------------------------------------------------------------------------

DROP FUNCTION verificar_participacion_no_excede();
DROP FUNCTION verificar_titularidad_no_excede();

-- ----------------------------------------------------------------------------
--  6. Lo que NO se hace aqui, y por que
--
--  No se revoca `CONNECT` a nadie: el `REVOKE CONNECT` es un privilegio SOBRE LA BASE
--  y solo lo puede retirar su dueno; `kamayuk_owner` a proposito no lo es (#722,
--  «permission denied for database»), asi que la sentencia fallaria y dejaria la
--  instalacion sin migrar.
--
--  No se toca ninguna columna `predio_id` ni `ficha_id`: ver la cabecera.
--
--  Y no se borra ni una fila. Las tablas se van enteras porque su contenido vive ahora
--  en otra base; lo que la regla 4 prohibe es borrar filas de deuda, pagos, recibos,
--  valores, asientos o auditoria, y ninguna de las quince lo es.
-- ----------------------------------------------------------------------------

COMMENT ON COLUMN declaracion_jurada.predio_id IS
    'El predio que se declara. Desde P5C es un identificador de OTRO SISTEMA: `catastro` '
    'es su dueno y aqui ya no hay clave foranea que lo garantice (V6). Lo que lo sostiene '
    'es `predio_ref`, la proyeccion local de V4';
COMMENT ON COLUMN determinacion.predio_id IS
    'Igual que en `declaracion_jurada`: identificador de `catastro`, sin clave foranea '
    'desde V6. La columna se queda porque es por que se determino lo que se determino';
