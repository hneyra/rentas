-- ============================================================================
--  V18 — LO QUE DEJA EL CONSUMIDOR DEL BUZON DE `identidad` (etapa 4 de
--        `infrastructure`#52, ADR-0039, ADR-0028 §3)
--
--  Desde ADR-0039 la autorizacion es un SISTEMA y su dueño es `identidad`. Las
--  cuatro tablas de la copia local —`usuario`, `grupo`, `miembro`, `permiso`—
--  siguen en este esquema y el guardia las sigue leyendo sin un viaje de red
--  (D-N5); lo que cambia en esta etapa es DE DONDE SALE esa copia: hasta hoy la
--  escribia la administracion de ESTE sistema, y desde hoy la escribe un
--  consumidor que trae los hechos del buzon de `identidad`, los aplica uno a
--  uno —una transaccion por evento— y los acusa DESPUES de confirmar. Estas dos
--  tablas son lo que ese consumidor necesita recordar, y nada mas. Tienen la
--  MISMA forma que en `caja` (`V3`), `catastro` y `normativa`, a proposito: el
--  consumidor es el mismo en los cuatro y lo que deja tambien.
--
--  1. `identidad_evento_aplicado`: que evento ya se aplico. La entrega es AL
--     MENOS UNA VEZ —un acuse que se pierde reentrega— y quien deduplica es el
--     receptor, por `evento_id`: un evento que ya esta aqui se acusa sin volver
--     a escribir la copia. Es la misma tabla que este esquema tiene para
--     `catastro` (`catastro_evento_aplicado`, `V4`), por el mismo motivo.
--
--  2. `identidad_evento_muerto`: el evento que NO SE PODRA APLICAR NUNCA —cuerpo
--     ilegible, tipo desconocido, un hecho que contradice lo que la copia ya
--     tiene—, apartado con su motivo. Se aparta, SE ACUSA y se avisa a una
--     persona: reintentarlo no lo arregla y bloquearia la cola detras de el, con
--     la copia local congelada sin un solo error visible (la leccion de `V12`).
--     Lo que NO se aparta aqui es lo que no se puede aplicar AHORA —la base
--     caida, el grupo que todavia no llego—: eso no se acusa y se reintenta.
--
--  `cuerpo` ES `text` Y NO `jsonb`, A PROPOSITO. Es la leccion de `V12` de este
--  mismo esquema: `jsonb` reordena las claves y descarta los espacios, asi que
--  lo que se lee no es byte a byte lo que se escribio, y un cuerpo que se aparto
--  porque NO SE PUDO LEER como JSON no cabe en una columna que exige que lo
--  sea — el motivo por el que se aparta es exactamente lo que impediria
--  guardarlo.
--
--  QUIEN ESCRIBE ES `kamayuk_app`, Y NO UN ROL INGESTOR APARTE. La proyeccion de
--  `catastro` la escribe `rol_ingestor_catastro` porque `kamayuk_app` solo LEE
--  esas tablas; la copia local de la autorizacion la escribe `kamayuk_app`
--  desde el baseline —`INSERT, SELECT, UPDATE` sobre las cuatro— y el consumidor
--  escribe con esa misma credencial. Un rol nuevo seria un rol del CLUSTER, que
--  los cinco sistemas comparten, y se crea en los cinco `crear-roles.sql` a la
--  vez o en ninguno.
--
--  NI `UPDATE` NI `DELETE` PARA `kamayuk_app`, EN NINGUNA DE LAS DOS. Un acuse
--  local no cambia: o se aplico o no. Y un muerto no se borra: es la constancia
--  de un permiso que `identidad` concedio y esta copia no tiene, que es lo unico
--  que le dice a quien atiende por que alguien no puede abrir una pantalla
--  (regla 4, RNF-051). Explicarlo es un acto con observacion y es de otra etapa.
-- ============================================================================


-- ----------------------------------------------------------------------------
--  1. LO YA APLICADO
-- ----------------------------------------------------------------------------

CREATE TABLE identidad_evento_aplicado (
    municipalidad_id bigint                   NOT NULL,
    evento_id        uuid                     NOT NULL,
    secuencia        bigint                   NOT NULL,
    tipo             character varying(40)    NOT NULL,
    sujeto_id        bigint                   NOT NULL,
    huella           character varying(64)    NOT NULL,
    aplicado_en      timestamp with time zone NOT NULL,

    CONSTRAINT identidad_evento_aplicado_pk
        PRIMARY KEY (municipalidad_id, evento_id),
    CONSTRAINT identidad_evento_aplicado_municipalidad_fk
        FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id),
    CONSTRAINT identidad_evento_aplicado_secuencia_ck CHECK (secuencia >= 0)
);

COMMENT ON TABLE identidad_evento_aplicado IS
    'Que evento del buzon de `identidad` ya se aplico a la copia local (etapa 4, ADR-0039). Es la '
    'deduplicacion del receptor: la entrega es al menos una vez, y un evento que ya esta aqui se '
    'acusa sin volver a escribir. Lo escribe solo el consumidor, en la misma transaccion que la '
    'fila que aplica.';
COMMENT ON COLUMN identidad_evento_aplicado.evento_id IS
    'El `evento_id` que `identidad` emitio: es lo que viaja y lo que vuelve en el acuse. Es '
    'ALEATORIO en el emisor —dos fijaciones iguales de la misma matriz son dos actos—, asi que '
    'aqui no se deriva de nada: se guarda tal cual.';
COMMENT ON COLUMN identidad_evento_aplicado.secuencia IS
    'El orden en que el emisor lo escribio. Se guarda para poder decir hasta donde llego esta '
    'copia, no para deduplicar: eso lo hace `evento_id`.';
COMMENT ON COLUMN identidad_evento_aplicado.huella IS
    'El sha256 del cuerpo canonico, tal como lo mando el emisor. No se recalcula aqui sobre lo '
    'que ya se escribio —seria comparar lo que se tiene con lo que se tiene—: se guarda para '
    'poder decir «esto que aplique es exactamente esto».';


-- ----------------------------------------------------------------------------
--  2. LO QUE NO SE PODRA APLICAR NUNCA
-- ----------------------------------------------------------------------------

CREATE TABLE identidad_evento_muerto (
    municipalidad_id bigint                   NOT NULL,
    evento_id        uuid                     NOT NULL,
    secuencia        bigint                   NOT NULL,
    tipo             character varying(40)    NOT NULL,
    sujeto_id        bigint                   NOT NULL,
    cuerpo           text                     NOT NULL,
    huella           character varying(64)    NOT NULL,
    motivo           character varying(400)   NOT NULL,
    apartado_en      timestamp with time zone NOT NULL,

    CONSTRAINT identidad_evento_muerto_pk
        PRIMARY KEY (municipalidad_id, evento_id),
    CONSTRAINT identidad_evento_muerto_municipalidad_fk
        FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id),
    CONSTRAINT identidad_evento_muerto_secuencia_ck CHECK (secuencia >= 0),
    -- Un motivo en blanco es un muerto sin causa: quien lo mire tendria que ir a
    -- buscarla al registro del servidor de esa noche (la misma guarda de `V12`).
    CONSTRAINT identidad_evento_muerto_motivo_ck CHECK (length(btrim(motivo)) >= 5)
);

COMMENT ON TABLE identidad_evento_muerto IS
    'Los eventos del buzon de `identidad` que este consumidor NO PODRA APLICAR NUNCA, apartados '
    'con su motivo (etapa 4, ADR-0039). Mientras haya filas aqui, `identidad` dice de quien puede '
    'hacer que algo que esta copia no dice, y la unica constancia es esta tabla: por eso se avisa '
    'al responsable (ADR-0026 §4) y por eso no se borra (regla 4).';
COMMENT ON COLUMN identidad_evento_muerto.tipo IS
    'El tipo TAL COMO EL EMISOR LO ESCRIBIO, sin pasarlo por el enumerado: un tipo que este '
    'consumidor no conoce es uno de los motivos para acabar aqui, y el nombre que llego es lo '
    'unico que dice cual falta.';
COMMENT ON COLUMN identidad_evento_muerto.cuerpo IS
    '`text` y no `jsonb` (la leccion de `V12`): un cuerpo que se aparto por no ser JSON no cabe '
    'en una columna que exija que lo sea, y `jsonb` ademas reordena las claves, asi que lo '
    'guardado dejaria de ser lo que se recibio.';
COMMENT ON COLUMN identidad_evento_muerto.motivo IS
    'Por que no se pudo aplicar, en las palabras del consumidor. Recortado a 400: lo que una '
    'maquina lee —`evento_id`, `tipo`, `secuencia`— viaja en sus columnas y no se recorta.';


-- ----------------------------------------------------------------------------
--  3. RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE identidad_evento_aplicado ENABLE ROW LEVEL SECURITY;
ALTER TABLE identidad_evento_aplicado FORCE ROW LEVEL SECURITY;
CREATE POLICY identidad_evento_aplicado_tenant ON identidad_evento_aplicado FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

ALTER TABLE identidad_evento_muerto ENABLE ROW LEVEL SECURITY;
ALTER TABLE identidad_evento_muerto FORCE ROW LEVEL SECURITY;
CREATE POLICY identidad_evento_muerto_tenant ON identidad_evento_muerto FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);


-- ----------------------------------------------------------------------------
--  4. PRIVILEGIOS
--
--  `INSERT` y `SELECT`, y nada mas: un acuse local no cambia y un muerto no se
--  borra. Que `kamayuk_app` no pueda hacer `UPDATE` ni `DELETE` es lo que hace
--  que la inmutabilidad se apoye en el motor y no solo en el escaner de fuentes.
-- ----------------------------------------------------------------------------

GRANT INSERT, SELECT ON identidad_evento_aplicado TO kamayuk_app;
GRANT SELECT         ON identidad_evento_aplicado TO kamayuk_readonly;
GRANT INSERT, SELECT ON identidad_evento_muerto TO kamayuk_app;
GRANT SELECT         ON identidad_evento_muerto TO kamayuk_readonly;
