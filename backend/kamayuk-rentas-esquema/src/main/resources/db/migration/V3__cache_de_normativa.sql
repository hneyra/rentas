-- ============================================================================
--  V3 — LA CACHE LOCAL DEL CONJUNTO SELLADO (P5B, ADR-0025 §1)
--
--  Va aparte de `V2` a proposito: aquella dice QUE SE VA y esta dice QUE LLEGA.
--  Leidas juntas cuentan la extraccion; leida cada una sola sigue siendo
--  entendible, que es lo que no consigue una migracion que hace las dos cosas.
--
--  QUE ES ESTO, Y QUE NO ES
--  ------------------------
--  Es la copia local de un conjunto de parametros YA SELLADO, descargada una vez
--  de `normativa` y verificada por su `sha256`. NO es una replica por eventos de
--  las tablas de `normativa` —eso es lo que ADR-0025 descarta expresamente, «es
--  cachear sin decirlo, con el agravante de que la copia se puede escribir»—, y
--  las dos diferencias que lo hacen otra cosa estan escritas en el esquema:
--
--    1. Solo entra lo SELLADO. `normativa` no sirve un conjunto abierto (su
--       `ComponerSnapshot` lo rechaza), asi que aqui no puede haber nada que
--       manana sea distinto. Es lo que hace legitimo cachear PARA SIEMPRE: no hay
--       invalidacion que disenar, ni ventana de inconsistencia, ni TTL.
--    2. La copia NO SE PUEDE EDITAR. `sgtm_app` recibe `INSERT` y `SELECT`, y
--       nada mas: sin `UPDATE` y sin `DELETE`. Una cifra normativa que se pudiera
--       corregir en la copia local seria un padron calculado con valores que
--       ninguna ordenanza respalda, y ninguna consulta lo delataria.
--
--  POR QUE FILAS Y NO UN `jsonb`
--  ------------------------------
--  Se midio la alternativa: guardar el snapshot entero como un `jsonb` por
--  conjunto. Se descarto por la forma en que el calculo LEE los cuadros —no por
--  gusto—: `ValorReferencialRepositoryJdbc` resuelve UN vehiculo por marca,
--  modelo y ano dentro de un anexo de 54 000 filas, y hacerlo dentro de un
--  documento de varios megabytes obliga a deserializarlo entero en cada consulta.
--  Con filas, la consulta es la MISMA que antes salvo el nombre de la tabla, y
--  sigue teniendo su indice.
--
--  Lo que la forma cuesta es que la huella no se puede recalcular despues sobre
--  estas filas: se verifica AL DESCARGAR y se guarda en `normativa_conjunto`. Eso
--  queda dicho aqui y en `SnapshotDescargado`.
--
--  SON TABLAS DE TENANT
--  ---------------------
--  `conjunto_parametros` es de tenant en `normativa`: cada municipalidad abre y
--  sella el suyo, asi que un `conjunto_id` solo es unico dentro de su
--  municipalidad. La copia lleva por tanto `municipalidad_id NOT NULL` y su
--  politica RLS, como cualquier otra tabla de este esquema — y la prueba de
--  aislamiento se lo exige sola: una tabla nueva con esa columna que no lleve RLS
--  la pone en rojo.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. La identidad y la prueba
-- ----------------------------------------------------------------------------

CREATE TABLE normativa_conjunto (
    municipalidad_id bigint      NOT NULL REFERENCES municipalidad(id),
    conjunto_id      bigint      NOT NULL,
    ejercicio        ejercicio   NOT NULL,
    version          integer     NOT NULL,
    ambito           varchar(20) NOT NULL,
    sha256           char(64)    NOT NULL,
    filas            integer     NOT NULL,
    origen           varchar(300) NOT NULL,
    descargado_en    timestamptz NOT NULL,
    CONSTRAINT normativa_conjunto_pk PRIMARY KEY (municipalidad_id, conjunto_id, ambito),
    CONSTRAINT normativa_conjunto_version_ck CHECK (version >= 1),
    CONSTRAINT normativa_conjunto_filas_ck CHECK (filas >= 0),
    CONSTRAINT normativa_conjunto_ambito_ck
        CHECK (ambito IN ('VALUACION', 'OBLIGACION'))
);

COMMENT ON TABLE normativa_conjunto IS
    'Copia local de un conjunto sellado de `normativa` (ADR-0025 §1). Inmutable: '
    '`sgtm_app` solo puede insertarla y leerla';
COMMENT ON COLUMN normativa_conjunto.ambito IS
    'VALUACION u OBLIGACION (ADR-0024). Forma parte de la clave porque el snapshot '
    'se pide por ambito y cada mitad tiene su propia huella; la IDENTIDAD -conjunto, '
    'ejercicio y version- es la misma en las dos';
COMMENT ON COLUMN normativa_conjunto.sha256 IS
    'La huella de los bytes servidos, verificada AL DESCARGAR. No se puede recalcular '
    'despues sobre las filas de abajo: lo que esta fila afirma es que en `descargado_en` '
    'el servidor entrego exactamente ese contenido';

-- ----------------------------------------------------------------------------
--  2. El contenido
--
--  Las cuatro tablas repiten la forma de su original en `normativa` salvo dos
--  cosas: llevan `(municipalidad_id, conjunto_id)` en vez de colgar de una
--  publicacion, y no llevan `id` propio. Lo primero es lo que las hace cacheables
--  por conjunto; lo segundo, que aqui no hay a quien referenciar.
-- ----------------------------------------------------------------------------

CREATE TABLE normativa_parametro (
    municipalidad_id bigint       NOT NULL,
    conjunto_id      bigint       NOT NULL,
    tipo             varchar(40)  NOT NULL,
    clave            varchar(120),
    valor_numerico   monto_calc,
    valor_texto      text,
    vigencia_desde   date,
    vigencia_hasta   date,
    documento_fuente varchar(200) NOT NULL
);

COMMENT ON TABLE normativa_parametro IS
    'Los parametros del conjunto, UNA sola vez aunque se descarguen los dos ambitos: '
    'el snapshot los lleva en los dos y aqui se escriben con el primero que llegue. '
    'Que no se escriban dos veces lo garantiza el candado de transaccion que toma '
    '`SnapshotRepositoryJdbc.guardar` -pg_advisory_xact_lock sobre (municipalidad, '
    'conjunto)-, y no una restriccion de unicidad: `parametro_tributario` NO la tiene '
    'sobre (tipo, clave, vigencia_desde) -V1 no la puso- y un conjunto lleva a proposito '
    'VARIAS vigencias de la misma llave (el historico de la UIT, #659), asi que ponerla '
    'aqui inventaria un invariante que el origen no tiene';

COMMENT ON COLUMN normativa_parametro.vigencia_desde IS
    'La vigencia viaja SIN resolver, igual que en el origen: cual rige lo decide el '
    'lector contra el ejercicio del conjunto (#659). Resolverlo al descargar moveria '
    'esa decision al servidor y la haria invisible';

CREATE TABLE normativa_valor_unitario (
    municipalidad_id        bigint       NOT NULL,
    conjunto_id             bigint       NOT NULL,
    partida                 varchar(20)  NOT NULL,
    categoria               text         NOT NULL,
    anio_construccion_desde ejercicio    NOT NULL,
    anio_construccion_hasta ejercicio,
    valor_m2                monto_calc   NOT NULL,
    documento_fuente        varchar(200) NOT NULL
);

CREATE TABLE normativa_depreciacion (
    municipalidad_id    bigint       NOT NULL,
    conjunto_id         bigint       NOT NULL,
    uso                 char(2)      NOT NULL,
    material            varchar(30)  NOT NULL,
    estado_conservacion varchar(20)  NOT NULL,
    antiguedad_hasta    smallint,
    porcentaje          alicuota     NOT NULL,
    documento_fuente    varchar(200) NOT NULL
);

COMMENT ON COLUMN normativa_depreciacion.antiguedad_hasta IS
    'Nulo en el tramo abierto -«mas de 50 anios»-. Leerlo como cero convertiria ese '
    'tramo en uno que no cubre nada, sin ningun error de por medio (#188 H-15)';

CREATE TABLE normativa_valor_referencial (
    municipalidad_id bigint       NOT NULL,
    conjunto_id      bigint       NOT NULL,
    ejercicio        ejercicio    NOT NULL,
    categoria        varchar(20)  NOT NULL,
    marca            varchar(60)  NOT NULL,
    modelo           varchar(80)  NOT NULL,
    anio_fabricacion ejercicio    NOT NULL,
    valor            dinero       NOT NULL,
    documento_fuente varchar(200) NOT NULL
);

-- ----------------------------------------------------------------------------
--  3. Los indices
--
--  El de valores referenciales reproduce la consulta que ya existia:
--  `ValorReferencialRepositoryJdbc` acota por conjunto, marca, modelo y ano. Los
--  otros tres cuadros se leen ENTEROS por conjunto -asi los pide `TablasDeValuacion`
--  al abrir una corrida-, asi que les basta el prefijo del conjunto.
--
--  Todos empiezan por `municipalidad_id`, que es lo que la politica RLS acota: bajo
--  RLS la condicion de la politica y la del filtro tienen que poder entrar juntas en
--  el `Index Cond`, y para eso el tenant va delante (#313, #536).
-- ----------------------------------------------------------------------------

CREATE INDEX normativa_parametro_ix
    ON normativa_parametro (municipalidad_id, conjunto_id);
CREATE INDEX normativa_valor_unitario_ix
    ON normativa_valor_unitario (municipalidad_id, conjunto_id);
CREATE INDEX normativa_depreciacion_ix
    ON normativa_depreciacion (municipalidad_id, conjunto_id);
CREATE INDEX normativa_valor_referencial_ix
    ON normativa_valor_referencial
       (municipalidad_id, conjunto_id, marca, modelo, anio_fabricacion);

-- ----------------------------------------------------------------------------
--  4. RLS
--
--  El mismo bloque que `V6` le pone a toda tabla de tenant, escrito tabla a tabla.
--  `FORCE` incluido: sin el, el dueno de la tabla la ve entera (DAT-01 §0 hallazgo 1).
-- ----------------------------------------------------------------------------

ALTER TABLE normativa_conjunto ENABLE ROW LEVEL SECURITY;
ALTER TABLE normativa_conjunto FORCE ROW LEVEL SECURITY;
CREATE POLICY normativa_conjunto_tenant ON normativa_conjunto FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE normativa_parametro ENABLE ROW LEVEL SECURITY;
ALTER TABLE normativa_parametro FORCE ROW LEVEL SECURITY;
CREATE POLICY normativa_parametro_tenant ON normativa_parametro FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE normativa_valor_unitario ENABLE ROW LEVEL SECURITY;
ALTER TABLE normativa_valor_unitario FORCE ROW LEVEL SECURITY;
CREATE POLICY normativa_valor_unitario_tenant ON normativa_valor_unitario FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE normativa_depreciacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE normativa_depreciacion FORCE ROW LEVEL SECURITY;
CREATE POLICY normativa_depreciacion_tenant ON normativa_depreciacion FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE normativa_valor_referencial ENABLE ROW LEVEL SECURITY;
ALTER TABLE normativa_valor_referencial FORCE ROW LEVEL SECURITY;
CREATE POLICY normativa_valor_referencial_tenant ON normativa_valor_referencial FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  5. Privilegios: se inserta y se lee, y nada mas
--
--  Sin `UPDATE` y sin `DELETE`, que es la mitad de lo que separa esto de una
--  replica editable. Y no es redundante con la politica: son DOS guardas
--  independientes y basta una para parar la escritura, pero solo el privilegio
--  se puede leer del catalogo — las dos dan el mismo 42501 y el sintoma no las
--  distingue (#435).
-- ----------------------------------------------------------------------------

GRANT INSERT, SELECT ON normativa_conjunto TO sgtm_app;
GRANT INSERT, SELECT ON normativa_parametro TO sgtm_app;
GRANT INSERT, SELECT ON normativa_valor_unitario TO sgtm_app;
GRANT INSERT, SELECT ON normativa_depreciacion TO sgtm_app;
GRANT INSERT, SELECT ON normativa_valor_referencial TO sgtm_app;

GRANT SELECT ON normativa_conjunto TO sgtm_readonly;
GRANT SELECT ON normativa_parametro TO sgtm_readonly;
GRANT SELECT ON normativa_valor_unitario TO sgtm_readonly;
GRANT SELECT ON normativa_depreciacion TO sgtm_readonly;
GRANT SELECT ON normativa_valor_referencial TO sgtm_readonly;
