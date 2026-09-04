-- ============================================================================
--  V4 — LA PROYECCION LOCAL DE CATASTRO (P5C, ADR-0027 y ADR-0029)
--
--  QUE ES ESTO, Y POR QUE NO ES UNA CACHE
--  --------------------------------------
--  `V3` cachea normativa y este archivo NO hace lo mismo, aunque las dos palabras se
--  parezcan. Un conjunto de parametros sellado es INMUTABLE: se descarga una vez y no
--  vuelve a cambiar nunca, y por eso alli no hay invalidacion que disenar. Un predio
--  cambia: se versiona su ficha, se transfiere su titularidad, se da de baja. Lo que
--  hay aqui es una PROYECCION alimentada por eventos, con todo lo que eso arrastra —un
--  buzon, una secuencia, un desfase— y ADR-0029 lo nombra sin adornos: «se paga la
--  consulta cruzada en SQL».
--
--  POR QUE HACE FALTA, Y POR QUE NO BASTA UN PUERTO HTTP
--  ----------------------------------------------------
--  `DeteccionRepositoryJdbc` —los omisos— lee `predio`, `sector` y `ficha_catastral` en
--  la MISMA consulta que pagina y cuenta lo filtrado. Con dos bases eso desaparece, y
--  componerlo en memoria YA SE PROBO Y FALLO: la conciliacion contestaba «722 paginas,
--  14 422 elementos» y CERO FILAS EN TODAS (#631). El motivo es estructural y no de
--  implementacion: la pagina la trae un sistema y el predicado lo conoce el otro, asi
--  que el `count(*)` cuenta una cosa y la pagina ensena otra.
--
--  La salida es que el predicado vuelva a caber en un solo `WHERE`, y para eso los
--  hechos de catastro que el predicado necesita tienen que estar aqui.
--
--  QUE SE PROYECTA, Y POR QUE DOS TABLAS Y NO UNA
--  ---------------------------------------------
--  `predio_ref` es el predio: su codigo, su direccion, su sector y su estado. No
--  cambia con la fecha.
--
--  `ficha_ref` son las VERSIONES de su ficha, con su rango de vigencia. Hacen falta
--  aparte porque la pregunta que el padron hace no es «cual es la ficha» sino «cual
--  regia el 31 de diciembre», y eso es la regla 9 otra vez: una proyeccion con UNA
--  ficha «la vigente» estaria fechada el dia que se proyecto y contestaria con ella una
--  reclamacion de 2024. Con las versiones y su rango, la resolucion por fecha se queda
--  donde estaba: en el `WHERE`.
--
--  LO QUE NO SE PROYECTA, Y ES LA MITAD DE LA DECISION
--  --------------------------------------------------
--  Ni construcciones, ni instalaciones, ni el detalle por tipo de ficha, ni la
--  geometria, ni el arancel. Nada de eso entra en ningun predicado de `rentas`: lo que
--  se necesita puntualmente —el area de UNA ficha, los titulares de UN predio— se
--  pregunta por HTTP, que es para lo que estan los puertos y lo que no obliga a
--  replicar el catastro entero.
--
--  Y NO SE PROYECTA LA TITULARIDAD. Es tentador —la fila de omisos lleva el nombre del
--  titular— pero se resuelve por lote DESPUES de paginar (`TitularesDelPredio.deVarios`,
--  una lectura por pagina), asi que nunca entra en el predicado. Proyectarla seria
--  replicar la tabla que mas se mueve del catastro para no usarla en ningun `WHERE`.
--
--  DE SOLO LECTURA, Y LO SOSTIENE EL MOTOR
--  ---------------------------------------
--  `sgtm_app` recibe SELECT y nada mas. Quien escribe es `rol_ingestor_catastro`, que
--  no atiende ninguna peticion. No es disciplina del repositorio: es un privilegio, la
--  misma mecanica con que `V54` protege el estado de la declaracion jurada y con la que
--  `V3` protege la copia de normativa. Una proyeccion que la aplicacion pueda escribir
--  deja de ser una proyeccion el dia que alguien «arregle» una fila a mano.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. El buzon: que evento se aplico, y en que orden
--
--  Deduplicado por `evento_id` y descartado por `secuencia`. Los dos hacen falta y no
--  son lo mismo: el primero impide aplicar dos veces el MISMO hecho —un reenvio—, y el
--  segundo impide que un hecho VIEJO que llega tarde pise a uno nuevo ya aplicado, que
--  es el defecto que no se ve porque la fila queda plausible.
-- ----------------------------------------------------------------------------

CREATE TABLE catastro_evento_aplicado (
    municipalidad_id bigint      NOT NULL REFERENCES municipalidad(id),
    evento_id        uuid        NOT NULL,
    secuencia        bigint      NOT NULL,
    tipo             varchar(40) NOT NULL,
    predio_id        bigint,
    aplicado_en      timestamptz NOT NULL,
    CONSTRAINT catastro_evento_pk PRIMARY KEY (municipalidad_id, evento_id),
    CONSTRAINT catastro_evento_secuencia_ck CHECK (secuencia >= 0)
);

CREATE INDEX catastro_evento_secuencia_ix
    ON catastro_evento_aplicado (municipalidad_id, secuencia DESC);

COMMENT ON TABLE catastro_evento_aplicado IS
    'Buzon de la proyeccion de catastro (P5C). Una fila por evento APLICADO: el '
    'ingestor consulta esta tabla antes de escribir y por eso reprocesar la cola no '
    'duplica nada. No se borra ninguna fila (regla 4): es la unica forma de contestar '
    '"por que esta proyeccion dice esto"';

-- ----------------------------------------------------------------------------
--  2. El predio
-- ----------------------------------------------------------------------------

CREATE TABLE predio_ref (
    municipalidad_id bigint       NOT NULL REFERENCES municipalidad(id),
    predio_id        bigint       NOT NULL,
    codigo_ref_catastral cod_catastral NOT NULL,
    direccion        varchar(300) NOT NULL,
    sector_codigo    varchar(20),
    estado           varchar(20)  NOT NULL,
    secuencia        bigint       NOT NULL,
    proyectado_en    timestamptz  NOT NULL,
    CONSTRAINT predio_ref_pk PRIMARY KEY (municipalidad_id, predio_id),
    CONSTRAINT predio_ref_codigo_uq UNIQUE (municipalidad_id, codigo_ref_catastral)
);

-- El indice del prefijo se escribe con `text_pattern_ops` por lo que DAT-01 §0 hallazgo 3
-- mide: bajo RLS un `LIKE 'prefijo%'` NO llega nunca al indice, porque `textlike` no es
-- leakproof y PostgreSQL no lo evalua antes de la politica. Toda busqueda por prefijo de
-- este esquema se escribe como rango con `~>=~` / `~<~`, y ese es el operador que este
-- indice sirve.
CREATE INDEX predio_ref_codigo_prefijo_ix
    ON predio_ref (municipalidad_id, codigo_ref_catastral text_pattern_ops);
CREATE INDEX predio_ref_sector_ix ON predio_ref (municipalidad_id, sector_codigo);

COMMENT ON TABLE predio_ref IS
    'Proyeccion local del predio de `catastro` (P5C). De solo lectura para `sgtm_app`: '
    'quien la escribe es `rol_ingestor_catastro`';
COMMENT ON COLUMN predio_ref.sector_codigo IS
    'El CODIGO del sector, no su identificador: lo que los filtros de `rentas` teclean es '
    'el codigo, y traer el id obligaria a proyectar tambien la tabla `sector` para poder '
    'traducirlo';

-- ----------------------------------------------------------------------------
--  3. Las versiones de su ficha
-- ----------------------------------------------------------------------------

CREATE TABLE ficha_ref (
    municipalidad_id bigint      NOT NULL REFERENCES municipalidad(id),
    ficha_id         bigint      NOT NULL,
    predio_id        bigint      NOT NULL,
    tipo             varchar(20) NOT NULL,
    version          integer     NOT NULL,
    vigencia_desde   date        NOT NULL,
    vigencia_hasta   date,
    area_terreno     area_m2,
    uso              varchar(60),
    secuencia        bigint      NOT NULL,
    proyectado_en    timestamptz NOT NULL,
    CONSTRAINT ficha_ref_pk PRIMARY KEY (municipalidad_id, ficha_id),
    CONSTRAINT ficha_ref_version_ck CHECK (version >= 1),
    CONSTRAINT ficha_ref_vigencia_ck
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde)
);

CREATE INDEX ficha_ref_predio_ix
    ON ficha_ref (municipalidad_id, predio_id, tipo, vigencia_desde);

COMMENT ON TABLE ficha_ref IS
    'Proyeccion local de las VERSIONES de ficha catastral (P5C). Con su rango de '
    'vigencia, para que la resolucion "cual regia a esta fecha" siga cabiendo en el '
    'WHERE de la deteccion de omisos: es lo que #631 midio que no se puede componer '
    'en memoria';

-- ----------------------------------------------------------------------------
--  4. RLS
--
--  Las tres llevan `municipalidad_id NOT NULL`, asi que la prueba de aislamiento les
--  exige RLS sola. `FORCE` incluido: sin el, el dueno de la tabla la ve entera
--  (DAT-01 §0 hallazgo 1).
-- ----------------------------------------------------------------------------

ALTER TABLE catastro_evento_aplicado ENABLE ROW LEVEL SECURITY;
ALTER TABLE catastro_evento_aplicado FORCE ROW LEVEL SECURITY;
CREATE POLICY catastro_evento_tenant ON catastro_evento_aplicado FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE predio_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE predio_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY predio_ref_tenant ON predio_ref FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE ficha_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE ficha_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY ficha_ref_tenant ON ficha_ref FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  5. Privilegios
--
--  `sgtm_app` LEE Y NO ESCRIBE, y esa es la mitad de ADR-0027 §3 que no es una
--  promesa. `rol_ingestor_catastro` existe para escribirla y no atiende peticiones:
--  el proceso que consume la cola corre en el perfil `batch`.
--
--  El rol se crea en `crear-roles.sql` como los otros cuatro —un rol es del CLUSTER y
--  una migracion no puede crearlo con `LOGIN`—, y por eso aqui se comprueba antes de
--  concederle nada: si no esta, la migracion falla diciendo que hay que provisionarlo,
--  en vez de dejar la proyeccion sin quien la escriba.
-- ----------------------------------------------------------------------------

DO $privilegios$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rol_ingestor_catastro') THEN
        RAISE EXCEPTION
            'Falta el rol `rol_ingestor_catastro`. Los roles son del CLUSTER y los crea '
            '`crear-roles.sql` con una conexion de superusuario, antes de migrar: una '
            'migracion no puede crearlos ni darles LOGIN (#435)'
            USING ERRCODE = 'invalid_authorization_specification';
    END IF;
END
$privilegios$;

GRANT SELECT ON catastro_evento_aplicado TO sgtm_app;
GRANT SELECT ON predio_ref TO sgtm_app;
GRANT SELECT ON ficha_ref TO sgtm_app;

GRANT SELECT, INSERT, UPDATE ON catastro_evento_aplicado TO rol_ingestor_catastro;
GRANT SELECT, INSERT, UPDATE ON predio_ref TO rol_ingestor_catastro;
GRANT SELECT, INSERT, UPDATE ON ficha_ref TO rol_ingestor_catastro;

GRANT SELECT ON catastro_evento_aplicado TO sgtm_readonly;
GRANT SELECT ON predio_ref TO sgtm_readonly;
GRANT SELECT ON ficha_ref TO sgtm_readonly;
