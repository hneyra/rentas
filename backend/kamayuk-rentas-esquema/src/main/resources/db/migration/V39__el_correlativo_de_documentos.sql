-- ============================================================================
--  V39 — EL CORRELATIVO DE DOCUMENTOS (#427)
--
--  QUE PASABA
--  ----------
--  `DocumentoRepositoryJdbc.siguienteCorrelativo` era un `count(*) + 1` sobre
--  `documento_emitido`, sin candado. `EmitirDocumento` tomaba ese numero, dibujaba
--  y firmaba el papel —cientos de milisegundos— y solo despues lo insertaba. Dos
--  emisiones simultaneas del mismo tipo y ejercicio leian la misma cuenta, y la
--  segunda chocaba en `documento_numero_uq` al confirmar la primera: 500
--  «ERROR_INTERNO» con incidencia, y se deshacia el acto entero que la emitia —el
--  internamiento con su movimiento y su auditoria, la REC-1, el alta de deuda—.
--
--  Era la unica numeracion del sistema que no se serializaba en el motor. Las
--  otras diez (`*_correlativo`) se leen con un UPSERT de una sola sentencia.
--
--  Medido en #427 contra PostgreSQL 16 (`ElCorrelativoDelDocumentoJdbcTest`): diez
--  emisiones simultaneas de ACTA_INTERNAMIENTO en 2026, con 3 ya emitidas, dejaban
--  1 y nueve `DuplicateKeyException`.
--
--  QUE HACE
--  --------
--  La fila contador, calcada de `valor_correlativo` (V1) y `dj_correlativo`: una
--  por municipalidad, tipo y ejercicio, con el ultimo numero repartido. Es la
--  UNICA fuente del numero desde #427. Se lee y se incrementa en una sola
--  sentencia `INSERT … ON CONFLICT DO UPDATE … RETURNING`; nunca con un SELECT
--  seguido de un UPDATE.
--
--  EL COSTE, DICHO: el candado de la fila dura hasta el COMMIT del acto que emite,
--  renderizado incluido. Dos emisiones del mismo tipo y ejercicio en una
--  municipalidad se esperan una a otra. Es lo que exige un numero correlativo; las
--  de tipos o ejercicios distintos no se tocan.
--
--  POR QUE NACE VACIA
--  ------------------
--  La primera peticion de cada (tipo, ejercicio) crea la fila arrancando por encima
--  del mayor sufijo numerico ya emitido. Sembrarla aqui es imposible por lo mismo
--  que `dj_correlativo` (V1): `documento_emitido` tiene RLS con FORCE, el migrador
--  corre como kamayuk_owner SIN contexto de tenant, y un SELECT sobre ella durante
--  la migracion falla con «unrecognized configuration parameter
--  app.municipalidad_id» (DAT-01 §0, cuarto hallazgo).
--
--  Lo que NO hace: cambiar el formato del numero (D-09). La fila reparte el entero;
--  `EmitirDocumento` lo sigue escribiendo como `TIPO-EJERCICIO-000042`.
-- ============================================================================

CREATE TABLE documento_correlativo (
    municipalidad_id bigint                NOT NULL REFERENCES municipalidad (id),
    tipo             character varying(40) NOT NULL,
    ejercicio        ejercicio             NOT NULL,
    ultimo           bigint                DEFAULT 0 NOT NULL,

    CONSTRAINT documento_correlativo_pk PRIMARY KEY (municipalidad_id, tipo, ejercicio),
    CONSTRAINT documento_correlativo_ultimo_check CHECK (ultimo >= 0)
);

COMMENT ON TABLE documento_correlativo IS
    'El ultimo correlativo de documento emitido por municipalidad, tipo y ejercicio (#427). Se '
    'lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. La fila NO se '
    'siembra en la migracion: la crea la primera emision del tipo y el ejercicio, arrancando por '
    'encima del mayor sufijo numerico ya emitido en documento_emitido, que tiene RLS con FORCE y '
    'no se puede leer sin contexto de tenant (el mismo motivo que dj_correlativo).';

-- ----------------------------------------------------------------------------
--  RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE documento_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE documento_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY documento_correlativo_tenant ON documento_correlativo FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  Privilegios: los de los otros diez contadores. El UPDATE es el del UPSERT, que
--  es infraestructura de numeracion y no un acto administrativo. Sin DELETE, como
--  los otros diez.
-- ----------------------------------------------------------------------------

GRANT INSERT, SELECT, UPDATE ON documento_correlativo TO kamayuk_app;
GRANT SELECT ON documento_correlativo TO kamayuk_readonly;
