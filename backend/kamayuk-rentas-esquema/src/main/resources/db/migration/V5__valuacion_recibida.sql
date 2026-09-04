-- ============================================================================
--  V5 — LA VALUACION QUE LLEGA DE CATASTRO, Y EL CANDADO ANTES DE EMITIR
--       (P5C, ADR-0027)
--
--  QUE SE GUARDA
--  -------------
--  Dos cosas, y la segunda es la que importa:
--
--    - `valuacion_predio`: el hecho sellado de cada predio en un ejercicio, con la
--      identidad de TODOS sus insumos —que ficha, que conjunto de parametros, que
--      version del catalogo de reglas, que reglas corrieron y con que titulares—.
--      No es «el valor del predio»: es la valuacion de un predio EN UN EJERCICIO,
--      que es la regla 9 aplicada al autovaluo (ADR-0027 §1).
--    - `valuacion_corrida`: que catastro CERRO la corrida de ese ejercicio, con su
--      conteo y su huella agregada.
--
--  POR QUE EL CANDADO VA ANTES DE EMITIR Y NO DESPUES
--  --------------------------------------------------
--  Porque un padron emitido con el 3 % de las valuaciones sin llegar produce miles de
--  recibos mal calculados y NO SE DESCUBRE HASTA VENTANILLA: cada recibo es plausible
--  por separado, y lo unico que falla es que a algunos contribuyentes les falta un
--  predio en la base. Comprobar despues seria descubrirlo con los papeles ya
--  notificados y el plazo del art. 137 corriendo.
--
--  Por eso el conteo y la huella viajan CON el cierre y no se calculan aqui: si
--  `rentas` los derivara de lo que recibio, comprobaria que lo que tiene es igual a lo
--  que tiene. Lo que se compara es lo que catastro dice que emitio contra lo que aqui
--  llego.
--
--  DE SOLO LECTURA, IGUAL QUE LA PROYECCION DE `V4`
--  -----------------------------------------------
--  `sgtm_app` no escribe ninguna de las dos. Y hay un motivo mas fuerte que en `V4`:
--  `valuacion_predio` es un HECHO SELLADO. Corregir una valuacion es publicar otra
--  (ADR-0027 §1), nunca un `UPDATE` — y por eso el ingestor tampoco recibe `UPDATE`
--  sobre ella, a diferencia de lo que si tiene sobre `predio_ref`.
-- ============================================================================

CREATE TABLE valuacion_corrida (
    municipalidad_id bigint      NOT NULL REFERENCES municipalidad(id),
    ejercicio        ejercicio   NOT NULL,
    corrida_id       bigint      NOT NULL,
    conjunto_id      bigint      NOT NULL,
    fecha_de_corte   date        NOT NULL,
    reglas_version   varchar(40) NOT NULL,
    conteo           integer     NOT NULL,
    huella           char(64)    NOT NULL,
    cerrada_en       timestamptz NOT NULL,
    recibida_en      timestamptz NOT NULL,
    CONSTRAINT valuacion_corrida_pk PRIMARY KEY (municipalidad_id, ejercicio),
    CONSTRAINT valuacion_corrida_conteo_ck CHECK (conteo >= 0)
);

COMMENT ON TABLE valuacion_corrida IS
    'El cierre de la corrida de valuacion de un ejercicio, tal como `catastro` lo emitio '
    '(ADR-0027 §2). UNA por ejercicio: recalcular es abrir otra corrida, y entonces esta '
    'fila se reemplaza entera con su conteo y su huella nuevos';
COMMENT ON COLUMN valuacion_corrida.conjunto_id IS
    'Lo FIJA la corrida, no lo resuelve cada sistema por su cuenta (ADR-0027 §2): si catastro '
    'resolviera el suyo y rentas el suyo, un sellado publicado entre las dos resoluciones '
    'produciria un padron calculado con dos conjuntos y ningun error visible';
COMMENT ON COLUMN valuacion_corrida.huella IS
    'La huella AGREGADA que catastro emitio. No se recalcula aqui sobre lo recibido: eso '
    'comprobaria que lo que tenemos es igual a lo que tenemos';

CREATE TABLE valuacion_predio (
    municipalidad_id   bigint      NOT NULL REFERENCES municipalidad(id),
    ejercicio          ejercicio   NOT NULL,
    predio_id          bigint      NOT NULL,
    fecha_de_corte     date        NOT NULL,
    valor_terreno      dinero,
    valor_construccion dinero,
    valor_obras        dinero,
    valor_del_predio   dinero,
    motivo             varchar(300),
    llave_que_falta    varchar(120),
    ficha_catastral_id bigint,
    conjunto_id        bigint      NOT NULL,
    reglas_version     varchar(40) NOT NULL,
    reglas_aplicadas   varchar(200) NOT NULL,
    huella             char(64)    NOT NULL,
    evento_id          uuid        NOT NULL,
    recibida_en        timestamptz NOT NULL,
    CONSTRAINT valuacion_predio_pk PRIMARY KEY (municipalidad_id, ejercicio, predio_id),
    -- O trae las cuatro cifras, o trae el motivo por el que no se pudo valorizar. Nunca las
    -- dos cosas, y nunca ninguna: un cero en `valor_del_predio` es indistinguible de un
    -- predio que de verdad no vale nada, y eso es lo que #48 midio con la licencia de obra
    -- que salia con «valor de obra 0,00».
    CONSTRAINT valuacion_predio_cifra_o_motivo_ck
        CHECK ((valor_del_predio IS NOT NULL AND motivo IS NULL)
            OR (valor_del_predio IS NULL AND motivo IS NOT NULL))
);

CREATE INDEX valuacion_predio_ejercicio_ix
    ON valuacion_predio (municipalidad_id, ejercicio);

COMMENT ON TABLE valuacion_predio IS
    'El hecho sellado de ADR-0027 §1, proyectado aqui. INMUTABLE: ni `sgtm_app` ni el '
    'ingestor tienen UPDATE. Corregir una valuacion es publicar otra';
COMMENT ON COLUMN valuacion_predio.motivo IS
    'Por que este predio no se pudo valorizar, cuando no se pudo. Hoy es el caso NORMAL: '
    'el sistema no sabe valorizar un predio todavia -faltan el cuadro de valores unitarios '
    'y la depreciacion (GOB-03 H-14/H-15), los aranceles de la ordenanza (D-02b) y el '
    '% actualizacion, que sigue sin fuente (D-11)-. Decirlo es lo que impide que un cero '
    'inventado llegue a un recibo';

-- ----------------------------------------------------------------------------
--  RLS
-- ----------------------------------------------------------------------------

ALTER TABLE valuacion_corrida ENABLE ROW LEVEL SECURITY;
ALTER TABLE valuacion_corrida FORCE ROW LEVEL SECURITY;
CREATE POLICY valuacion_corrida_tenant ON valuacion_corrida FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE valuacion_predio ENABLE ROW LEVEL SECURITY;
ALTER TABLE valuacion_predio FORCE ROW LEVEL SECURITY;
CREATE POLICY valuacion_predio_tenant ON valuacion_predio FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  Privilegios
--
--  `valuacion_corrida` SI admite UPDATE del ingestor: una corrida nueva del mismo
--  ejercicio la reemplaza. `valuacion_predio` NO, y esa asimetria es ADR-0027 §1: el
--  cierre de una corrida se sustituye; un hecho sellado, no.
-- ----------------------------------------------------------------------------

GRANT SELECT ON valuacion_corrida TO sgtm_app;
GRANT SELECT ON valuacion_predio TO sgtm_app;

GRANT SELECT, INSERT, UPDATE ON valuacion_corrida TO rol_ingestor_catastro;
GRANT SELECT, INSERT ON valuacion_predio TO rol_ingestor_catastro;

GRANT SELECT ON valuacion_corrida TO sgtm_readonly;
GRANT SELECT ON valuacion_predio TO sgtm_readonly;
