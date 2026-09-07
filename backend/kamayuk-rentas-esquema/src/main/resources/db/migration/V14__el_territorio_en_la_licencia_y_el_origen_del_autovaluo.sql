-- ============================================================================
--  V14 — de donde salio la zona de una licencia, y de donde salio un autovaluo
--
--  QUE ANADE, Y POR QUE LAS DOS COSAS EN LA MISMA MIGRACION
--  -------------------------------------------------------
--  Son dos columnas de procedencia sobre dos tablas distintas, y las dos
--  responden a la misma pregunta: **de que dato salio este acto**. Hasta hoy la
--  respuesta no estaba escrita en ningun sitio y, en los dos casos, `catastro`
--  publicaba el hecho y este sistema no lo leia (#38, #43).
--
--  1. `licencia_funcionamiento` gana la zona que contesto el TERRITORIO, al
--     lado de la que se DECLARO, mas cual de las dos sostiene el acto y que
--     paso al comprobarlo.
--  2. `determinacion_predio_detalle` gana de donde salio el autovaluo de cada
--     predio, y —cuando salio de una valuacion sellada— con que conjunto y con
--     que huella se calculo.
--
--  NINGUNA COLUMNA SE RELLENA HACIA ATRAS, Y ES UNA DECISION
--  --------------------------------------------------------
--  Las licencias y las determinaciones que ya existen se emitieron SIN
--  comprobar el territorio y CON un autovaluo declarado. Rellenarlas con el
--  valor de hoy diria que se comprobo lo que nadie comprobo, que es peor que
--  no decir nada: quien revise una licencia de 2025 tiene que poder ver que su
--  zona no se contrasto contra ningun plan.
--
--  Por eso `zona_origen` nace en 'DECLARADA' y `autovaluo_origen` en
--  'DECLARADO': es lo que esas filas son, literalmente, y no un valor por
--  omision elegido por comodidad.
--
--  NO SE TOCA `zonificacion`
--  -------------------------
--  Sigue siendo la zona DECLARADA, la que teclea quien atiende y la que sale
--  impresa. Sustituirla por la del territorio cambiaria el fundamento de un
--  acto administrativo sin que quedara rastro, y ese acto se impugna (#43,
--  AC-3). Las dos se guardan; `zona_origen` dice cual manda.
--
--  Y `licencia_funcionamiento` NO ADMITE `UPDATE` (V37 del monolito, y el
--  escaner de fuentes): estas columnas se escriben al insertar y no despues.
--  Anadir columnas es DDL del dueno del esquema y no toca ese privilegio.
-- ============================================================================

ALTER TABLE licencia_funcionamiento
    ADD COLUMN zona_del_territorio      varchar(20),
    ADD COLUMN ordenanza_de_la_zona     varchar(60),
    ADD COLUMN zona_origen              varchar(20)  NOT NULL DEFAULT 'DECLARADA',
    ADD COLUMN comprobacion_territorio  varchar(400);

ALTER TABLE licencia_funcionamiento
    ADD CONSTRAINT licencia_zona_origen_ck
        CHECK (zona_origen IN ('DECLARADA', 'TERRITORIO', 'NO_COMPROBADA'));

-- Si el acto se sostiene en la zona del territorio, la zona del territorio
-- tiene que estar. Sin esta guarda, `zona_origen = 'TERRITORIO'` con la columna
-- vacia diria que se comprobo contra un plan y no dejaria ni el codigo con que
-- se comprobo — que es la forma de mentira que esta migracion existe para
-- impedir.
ALTER TABLE licencia_funcionamiento
    ADD CONSTRAINT licencia_zona_del_territorio_ck
        CHECK (zona_origen <> 'TERRITORIO' OR zona_del_territorio IS NOT NULL);

COMMENT ON COLUMN licencia_funcionamiento.zona_del_territorio IS
    'La zona que `catastro` contesto cortando el lote contra el plan vigente el dia de la '
    'emision (`catastro`#4). NULA cuando no se pudo comprobar: no consta el predio, no tiene '
    'poligono, ningun plan lo cubre, o no se pudo preguntar. NULA NO significa «sin zona»';
COMMENT ON COLUMN licencia_funcionamiento.ordenanza_de_la_zona IS
    'La ordenanza que aprobo el plan de zonificacion. Sin ella una denegacion por zona no se '
    'puede notificar, porque no cita la norma que la sustenta';
COMMENT ON COLUMN licencia_funcionamiento.zona_origen IS
    'Cual de las dos zonas sostiene el acto: TERRITORIO si `catastro` contesto, DECLARADA si '
    'no se pudo comprobar y una persona lo autorizo por escrito, NO_COMPROBADA si no habia '
    'predio que consultar. Las filas anteriores a V14 son DECLARADA porque eso es lo que '
    'fueron: nadie comprobo nada';
COMMENT ON COLUMN licencia_funcionamiento.comprobacion_territorio IS
    'Que contestaron las tres consultas al territorio —zona, riesgo del suelo e ITSE— y, si '
    'alguna no contesto, cual y por que. Es lo que hace que «se autorizo sin comprobar» sea '
    'legible dentro de dos anos, en vez de indistinguible de «se comprobo y salio bien»';

-- ----------------------------------------------------------------------------
--  El autovaluo con el que se determino: declarado, o sellado por `catastro`
-- ----------------------------------------------------------------------------

ALTER TABLE determinacion_predio_detalle
    ADD COLUMN autovaluo_origen      varchar(12) NOT NULL DEFAULT 'DECLARADO',
    ADD COLUMN valuacion_conjunto_id bigint,
    ADD COLUMN valuacion_huella      char(64);

ALTER TABLE determinacion_predio_detalle
    ADD CONSTRAINT determinacion_detalle_autovaluo_origen_ck
        CHECK (autovaluo_origen IN ('DECLARADO', 'SELLADO'));

-- Un autovaluo SELLADO trae de donde salio, o no es sellado. `catastro` firma
-- cada valuacion con su `conjuntoId` y su `huella` (ADR-0027); guardar la cifra
-- sin ellos dejaria un recibo que dice venir de una valuacion y no permite
-- decir de cual — y ese es justo el rastro que hace falta dentro de un ano.
ALTER TABLE determinacion_predio_detalle
    ADD CONSTRAINT determinacion_detalle_procedencia_ck
        CHECK ((autovaluo_origen = 'SELLADO'
                  AND valuacion_conjunto_id IS NOT NULL
                  AND valuacion_huella IS NOT NULL)
            OR (autovaluo_origen = 'DECLARADO'
                  AND valuacion_conjunto_id IS NULL
                  AND valuacion_huella IS NULL));

COMMENT ON COLUMN determinacion_predio_detalle.autovaluo_origen IS
    'De donde salio el autovaluo de este predio: DECLARADO si lo fijo una declaracion jurada, '
    'SELLADO si salio de la valuacion que `catastro` publico para el ejercicio (ADR-0027). '
    'Sin esta columna, dentro de un ano nadie puede decir si un recibo salio de una '
    'declaracion o de una valuacion, y son dos actos que se impugnan de maneras distintas';
COMMENT ON COLUMN determinacion_predio_detalle.valuacion_conjunto_id IS
    'El conjunto de parametros que fijo LA CORRIDA de valuacion (ADR-0027 §2), no el que este '
    'sistema resolveria hoy: recalcular en 2037 tiene que dar el mismo centimo (regla 6)';
COMMENT ON COLUMN determinacion_predio_detalle.valuacion_huella IS
    'La huella con que `catastro` sello ESA valuacion. Es lo que permite decir despues que la '
    'cifra del recibo es la que aquel sistema emitio y no otra que llego despues';
