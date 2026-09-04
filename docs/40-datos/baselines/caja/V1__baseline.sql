-- ============================================================================
--  CAJA — V1__baseline.sql
--
--  El esquema de Caja, generado del esquema de `sgtm` restringido a las
--  tablas que le pertenecen (ADR-0032 §1). 23 tablas.
--
--  Ventanilla, recibo, turno, cierre, arqueo y el catalogo de conceptos cobrables.
--  10 tablas propias mas las 13 comunes.
--
--  ESTO ES UNA MIGRACION DE FLYWAY, NO UN `esquema.sql` SUELTO, y el motivo NO
--  es la migracion de datos (ADR-0032 §2). Son tres, y ninguna es hipotetica:
--    - el CHECKSUM sobre DDL ya aplicado. El modo de fallo real no es «falta una
--      migracion»: es que alguien edite una que ya corrio en su maquina y la
--      base de al lado quede distinta sin que nada se ponga rojo;
--    - el Job de implantacion ESPERA al de migracion consultando
--      `flyway_schema_history` (`V21`), porque en Kubernetes no hay equivalente
--      de `service_completed_successfully`;
--    - las pruebas de persistencia corren las migraciones reales contra un motor
--      real, y sin version no se puede decir contra QUE esquema pasaron.
--
--  NO SE COPIA NINGUNA MIGRACION DE `sgtm`. Sus `V1..V78` estan entrelazadas a
--  proposito -`V1` crea nucleo y catastro juntos, `V6` aplica RLS a todo el
--  esquema de una vez, `V7` reparte los privilegios de TODOS los roles- y no hay
--  reparto posible: `V1` pertenece a dos sistemas y `V6` y `V7` a los cuatro. La
--  historia se queda en `sgtm`, que no se borra, y ahi sigue contestando por que
--  una columna es como es.
--
--  A PARTIR DE AQUI, CADA CAMBIO ES UNA MIGRACION NUEVA DE ESTE REPOSITORIO:
--  `V2`, `V3`, y asi. En cuanto haya una base en `stg` que alguien no quiera
--  rehacer, este archivo deja de poder editarse.
--
--  ANTES DE ESTE ARCHIVO hay que haber corrido `crear-roles.sql`: los roles y
--  las extensiones se provisionan con una conexion de superusuario, porque las
--  politicas de §5 NOMBRAN roles que deben existir, y `sgtm_owner` no puede
--  instalar una extension ni crearse a si mismo.

--  ----------------------------------------------------------------------------
--  LOS CINCO HALLAZGOS DE RLS (DAT-01 §0), VERIFICADOS EJECUTANDO
--  ----------------------------------------------------------------------------
--
--  Van en el encabezado de los cuatro baselines a proposito: los cuatro sistemas
--  van a tropezar con ellos, y en un repositorio nuevo NO HAY `git log` donde
--  encontrarlos. Los dos primeros se heredaron verificados del SRTM; los otros
--  tres salieron aqui, midiendo planes y migraciones.
--
--  1. UN SUPERUSUARIO OMITE RLS.
--     `FORCE ROW LEVEL SECURITY` protege del PROPIETARIO de la tabla, no del
--     SUPERUSUARIO. Consecuencias, todas obligatorias: el rol de aplicacion se
--     crea `NOSUPERUSER NOBYPASSRLS`; la aplicacion no se conecta como
--     propietario; y una prueba de aislamiento escrita sobre la conexion por
--     omision de Testcontainers -que es de superusuario- PASA EN VERDE SIN
--     VERIFICAR NADA.
--
--  2. UNA PARTICION NO HEREDA LA POLITICA DEL PADRE.
--     Consultar una particion directamente evade la politica de su padre. Dos
--     mitigaciones, y la segunda es la que cierra el hueco: RLS explicita en
--     cada particion (§5 de este archivo), y LA APLICACION NO TIENE NINGUN
--     PRIVILEGIO SOBRE NINGUNA PARTICION. Por eso aqui no hay
--     `GRANT ... ON ALL TABLES IN SCHEMA`: una particion nueva no recibe
--     privilegios salvo que alguien se los conceda, y eso se ve en el diff.
--
--  3. BAJO RLS, UN `LIKE 'prefijo%'` NO LLEGA NUNCA AL INDICE.
--     `textlike` no es *leakproof*, asi que PostgreSQL no lo evalua antes de la
--     politica y la condicion se queda en el `Filter`. Toda busqueda por prefijo
--     se escribe como RANGO, con `~>=~` / `~<~` y un indice `text_pattern_ops`.
--     Y una funcion no leakproof envolviendo la columna -`lower`, `unaccent`-
--     tampoco llega: por eso hay columnas GENERADAS de busqueda.
--
--  4. UNA CLAVE FORANEA NUEVA SOBRE UNA TABLA CON RLS NO SE PUEDE VALIDAR.
--     Validar lanza una consulta, la consulta queda sujeta a la politica, y el
--     migrador corre sin contexto de tenant -correctamente: migrar no es atender
--     a ninguna municipalidad-. La migracion entera muere con
--     `unrecognized configuration parameter "app.municipalidad_id"`. Por eso hay
--     restricciones `NOT VALID`, que SIGUEN comprobando cada INSERT y cada
--     UPDATE. Medido aparte: un `CHECK` validado SI pasa -su escaneo no
--     atraviesa la politica-, asi que un `NOT VALID` sobre un CHECK es por
--     DATOS, no por RLS.
--
--  5. BAJO RLS, EL OPERADOR ESPACIAL TAMPOCO LLEGA AL INDICE.
--     Es el hallazgo 3 con otro operador: `geography_overlaps` tampoco es
--     *leakproof*. Y el sintoma engana mas, porque EL PLAN SIGUE DICIENDO
--     "Index": usa uno por la condicion de la propia politica y lee la tabla
--     entera del inquilino. Por eso el marco del lote se escribe con `<=` / `>=`
--     sobre cuatro columnas GENERADAS en `double precision` -y no `numeric`,
--     porque `numeric_le` tampoco es leakproof-.
-- ============================================================================

-- ==========================================================================
--  1. DOMINIOS DE TIPO
--  Un importe es `dinero`, no `numeric(15,2)`: el dominio lleva su CHECK y
--  hace que la restriccion viva en un solo sitio.
-- ==========================================================================

CREATE DOMAIN alicuota AS numeric(7,4)
    CONSTRAINT alicuota_check CHECK (((VALUE >= (0)::numeric) AND (VALUE <= (100)::numeric)));
CREATE DOMAIN area_m2 AS numeric(12,2)
    CONSTRAINT area_m2_check CHECK ((VALUE >= (0)::numeric));
CREATE DOMAIN cod_catastral AS character varying(25)
    CONSTRAINT cod_catastral_check CHECK (((VALUE)::text ~ '^[0-9]{18,25}$'::text));
CREATE DOMAIN dinero AS numeric(15,2);
CREATE DOMAIN ejercicio AS smallint
    CONSTRAINT ejercicio_check CHECK (((VALUE >= 1990) AND (VALUE <= 2100)));
CREATE DOMAIN monto_calc AS numeric(18,6);
CREATE DOMAIN porcentaje AS numeric(7,4)
    CONSTRAINT porcentaje_check CHECK (((VALUE > (0)::numeric) AND (VALUE <= (100)::numeric)));

-- ==========================================================================
--  2. FUNCIONES
--  Van antes que las tablas porque una columna GENERADA las usa:
--  `nombre_normalizado` es la de `via`, y sin ella el `CREATE TABLE` falla.
--  Las de disparador estan aqui tambien; un disparador sin su funcion no
--  protege nada.
-- ==========================================================================

CREATE OR REPLACE FUNCTION public.conjunto_sellado_es_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.estado = 'SELLADO' THEN
        RAISE EXCEPTION
            'El conjunto de parametros % del ejercicio % esta sellado y no se modifica;'
            ' cree una version nueva (ADR-0007)', OLD.id, OLD.ejercicio
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.declaracion_jurada_estado_es_terminal()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.estado IN ('ANULADA', 'SUSTITUIDA') THEN
        RAISE EXCEPTION
            'La declaracion jurada % esta % y no admite mas actos: presente otra (#365)',
            OLD.numero, OLD.estado
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.detalle_de_conjunto_sellado_es_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    estado_actual text;
    conjunto      bigint;
BEGIN
    conjunto := COALESCE(NEW.conjunto_id, OLD.conjunto_id);
    SELECT c.estado INTO estado_actual
      FROM conjunto_parametros c
     WHERE c.municipalidad_id = COALESCE(NEW.municipalidad_id, OLD.municipalidad_id)
       AND c.id = conjunto;

    IF estado_actual = 'SELLADO' THEN
        RAISE EXCEPTION
            'El conjunto de parametros % esta sellado: su contenido no cambia (ADR-0007)',
            conjunto
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.documento_solo_cuenta_reimpresiones()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.tipo IS DISTINCT FROM OLD.tipo
       OR NEW.numero IS DISTINCT FROM OLD.numero
       OR NEW.ejercicio IS DISTINCT FROM OLD.ejercicio
       OR NEW.referencia IS DISTINCT FROM OLD.referencia
       OR NEW.datos IS DISTINCT FROM OLD.datos
       OR NEW.formato IS DISTINCT FROM OLD.formato
       OR NEW.resumen IS DISTINCT FROM OLD.resumen
       OR NEW.fecha_emision IS DISTINCT FROM OLD.fecha_emision
    THEN
        RAISE EXCEPTION
          'Un documento emitido no se edita: lo unico que cambia es cuantas veces se reimprimio. '
          'Si los datos estaban mal, se emite otro y se anula este';
    END IF;
    RETURN NEW;
END
$function$
;

CREATE OR REPLACE FUNCTION public.nombre_normalizado(texto text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE PARALLEL SAFE STRICT
AS $function$
    SELECT regexp_replace(
               lower(unaccent('unaccent'::regdictionary, coalesce(texto, ''))),
               '\s+', ' ', 'g');
$function$
;

CREATE OR REPLACE FUNCTION public.valuacion_de_conjunto_sellado_es_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    estado_actual text;
    v_conjunto    bigint;
BEGIN
    v_conjunto := COALESCE(NEW.conjunto_id, OLD.conjunto_id);
    SELECT c.estado INTO estado_actual
      FROM conjunto_parametros c
     WHERE c.municipalidad_id = COALESCE(NEW.municipalidad_id, OLD.municipalidad_id)
       AND c.id = v_conjunto;
    IF estado_actual = 'SELLADO' THEN
        RAISE EXCEPTION
            'El conjunto de parametros % esta sellado: su contenido no cambia (ADR-0007)',
            v_conjunto
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.valuacion_de_publicacion_sellada_es_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    esta_sellada boolean;
    v_publicacion bigint;
BEGIN
    v_publicacion := COALESCE(NEW.publicacion_id, OLD.publicacion_id);
    SELECT p.sellado INTO esta_sellada
      FROM parametro_tributario p
     WHERE p.id = v_publicacion;

    -- La clave foranea ya lo impediria; esto lo dice con un mensaje que nombra
    -- la causa en vez de un codigo de restriccion.
    IF esta_sellada IS NULL THEN
        RAISE EXCEPTION
            'La publicacion % no existe o no es visible para este rol: una fila de'
            ' valuacion sin edicion no se puede reproducir', v_publicacion
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF esta_sellada THEN
        RAISE EXCEPTION
            'La publicacion % esta sellada: su contenido no cambia. Corregir un cuadro'
            ' normativo es publicar otra edicion, no editar la que ya se uso (ADR-0007)',
            v_publicacion
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.verificar_participacion_no_excede()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_muni  bigint := COALESCE(NEW.municipalidad_id, OLD.municipalidad_id);
    v_ficha bigint := COALESCE(NEW.ficha_id, OLD.ficha_id);
    v_total numeric(7,4);
BEGIN
    SELECT COALESCE(sum(porcentaje), 0) INTO v_total
      FROM participacion_comun
     WHERE municipalidad_id = v_muni
       AND ficha_id = v_ficha;
    IF v_total > 100 THEN
        RAISE EXCEPTION
          'Las participaciones de la ficha % suman %, no pueden exceder 100',
          v_ficha, v_total;
    END IF;
    RETURN NULL;
END
$function$
;

CREATE OR REPLACE FUNCTION public.verificar_titularidad_no_excede()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_muni   bigint := COALESCE(NEW.municipalidad_id, OLD.municipalidad_id);
    v_predio bigint := COALESCE(NEW.predio_id, OLD.predio_id);
    v_total  numeric(7,4);
BEGIN
    SELECT COALESCE(sum(porcentaje), 0) INTO v_total
      FROM titularidad
     WHERE municipalidad_id = v_muni
       AND predio_id = v_predio
       AND vigencia_hasta IS NULL;
    IF v_total > 100 THEN
        RAISE EXCEPTION
          'Los porcentajes de titularidad vigentes del predio % suman %, no pueden exceder 100',
          v_predio, v_total;
    END IF;
    RETURN NULL;
END
$function$
;


-- ==========================================================================
--  3. TABLAS
--  Las particionadas van antes que sus particiones.
-- ==========================================================================

CREATE TABLE acceso (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    modulo_id bigint NOT NULL,
    tipo character varying(12) NOT NULL,
    codigo character varying(60) NOT NULL,
    nombre character varying(160) NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE area (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(20) NOT NULL,
    nombre character varying(160) NOT NULL,
    activa boolean DEFAULT true NOT NULL
);

CREATE TABLE auditoria (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    tabla character varying(60) NOT NULL,
    clave character varying(120) NOT NULL,
    operacion character varying(15) NOT NULL,
    usuario_id character varying(60) NOT NULL,
    origen_equipo character varying(80),
    origen_ip inet,
    fecha timestamp with time zone DEFAULT now() NOT NULL,
    observacion character varying(1000) NOT NULL,
    datos_anteriores jsonb,
    datos_nuevos jsonb
) PARTITION BY LIST (ejercicio);

CREATE TABLE caja (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(10) NOT NULL,
    nombre character varying(80) NOT NULL,
    area_id bigint,
    activa boolean DEFAULT true NOT NULL,
    serie character varying(5) NOT NULL
);

CREATE TABLE cierre_caja (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    caja_id bigint NOT NULL,
    cajero character varying(60) NOT NULL,
    fecha date NOT NULL,
    fecha_apertura timestamp with time zone NOT NULL,
    usuario_apertura character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE cierre_turno (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    turno_id bigint NOT NULL,
    tipo character varying(9) NOT NULL,
    secuencia smallint NOT NULL,
    fecha date NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    total_cobrado dinero,
    total_anulado dinero,
    neto dinero,
    total_declarado dinero,
    diferencia dinero,
    recibos_emitidos integer,
    recibos_anulados integer,
    revierte_a_id bigint,
    motivo character varying(80),
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE cierre_turno_detalle (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    cierre_id bigint NOT NULL,
    forma_pago character varying(20) NOT NULL,
    cobrado dinero NOT NULL,
    anulado dinero NOT NULL,
    neto dinero NOT NULL,
    declarado dinero NOT NULL
);

CREATE TABLE documento_emitido (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    tipo character varying(40) NOT NULL,
    numero character varying(40) NOT NULL,
    ejercicio ejercicio NOT NULL,
    referencia character varying(80) NOT NULL,
    datos jsonb NOT NULL,
    formato character varying(10) NOT NULL,
    resumen character(64) NOT NULL,
    fecha_emision date NOT NULL,
    reimpresiones integer DEFAULT 0 NOT NULL,
    usuario_emision character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE grupo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    nombre character varying(80) NOT NULL,
    descripcion character varying(300),
    habilitado boolean DEFAULT true NOT NULL,
    vigencia_desde date,
    vigencia_hasta date
);

CREATE TABLE miembro (
    municipalidad_id bigint NOT NULL,
    grupo_id bigint NOT NULL,
    usuario_id bigint NOT NULL,
    fecha_alta timestamp with time zone DEFAULT now() NOT NULL,
    usuario_alta character varying(60) NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    fecha_baja timestamp with time zone,
    usuario_baja character varying(60)
);

CREATE TABLE modulo_sistema (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(30) NOT NULL,
    nombre character varying(120) NOT NULL,
    orden smallint DEFAULT 0 NOT NULL,
    activo boolean DEFAULT true NOT NULL
);

CREATE TABLE municipalidad (
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ubigeo character(6) NOT NULL,
    nombre character varying(160) NOT NULL,
    tipo character varying(20) NOT NULL,
    activa boolean DEFAULT true NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    es_demostracion boolean DEFAULT false NOT NULL
);

CREATE TABLE permiso (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    acceso_id bigint NOT NULL,
    grupo_id bigint,
    usuario_id bigint,
    ejecucion boolean DEFAULT false NOT NULL,
    lectura boolean DEFAULT false NOT NULL,
    registro boolean DEFAULT false NOT NULL,
    modificacion boolean DEFAULT false NOT NULL,
    eliminacion boolean DEFAULT false NOT NULL,
    impresion boolean DEFAULT false NOT NULL,
    especial boolean DEFAULT false NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    usuario_registro character varying(60) NOT NULL
);

CREATE TABLE recibo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    serie character varying(5) NOT NULL,
    numero integer NOT NULL,
    caja_id bigint NOT NULL,
    cajero character varying(60) NOT NULL,
    contribuyente_id bigint NOT NULL,
    fecha timestamp with time zone DEFAULT now() NOT NULL,
    forma_pago character varying(20) NOT NULL,
    tipo_pago character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    campania_beneficio character varying(80),
    total dinero NOT NULL,
    turno_id bigint,
    actualizado_a date NOT NULL,
    clave_idempotencia character varying(64),
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE recibo_correlativo (
    municipalidad_id bigint NOT NULL,
    serie character varying(5) NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE recibo_detalle (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    recibo_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    concepto character varying(20) NOT NULL,
    ejercicio ejercicio,
    periodo smallint,
    tasa_id bigint,
    predio_id bigint,
    vehiculo_id bigint,
    referencia_externa character varying(40),
    monto dinero NOT NULL,
    insoluto dinero DEFAULT 0 NOT NULL,
    reajuste dinero DEFAULT 0 NOT NULL,
    interes dinero DEFAULT 0 NOT NULL,
    gasto dinero DEFAULT 0 NOT NULL,
    cantidad integer,
    precio_unitario dinero
);

CREATE TABLE recibo_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    recibo_id bigint NOT NULL,
    tipo character varying(9) NOT NULL,
    fecha date NOT NULL,
    caja_id bigint NOT NULL,
    turno_id bigint NOT NULL,
    motivo character varying(80),
    autorizado_por character varying(80),
    documento_autorizacion character varying(40),
    importe dinero,
    resumen character(64),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE respaldo (
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    inicio timestamp with time zone NOT NULL,
    fin timestamp with time zone,
    resultado character varying(12) NOT NULL,
    destino character varying(200) NOT NULL,
    tamano_bytes bigint,
    detalle character varying(500),
    ultima_restauracion_verificada timestamp with time zone,
    ultima_restauracion_verificada_por character varying(200)
);

CREATE TABLE sesion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    usuario_id bigint NOT NULL,
    inicio timestamp with time zone DEFAULT now() NOT NULL,
    fin timestamp with time zone,
    origen_equipo character varying(80),
    origen_ip inet,
    agente character varying(200),
    ejercicio_trabajo ejercicio
);

CREATE TABLE tasa (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(20) NOT NULL,
    descripcion character varying(240) NOT NULL,
    area_id bigint NOT NULL,
    partida_presupuestal character varying(30) NOT NULL,
    importe dinero NOT NULL,
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_fuente character varying(200) NOT NULL
);

CREATE TABLE usuario (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    cuenta character varying(60) NOT NULL,
    sujeto_oidc character varying(120),
    nombre character varying(160) NOT NULL,
    correo character varying(160),
    habilitado boolean DEFAULT true NOT NULL,
    vigencia_desde date,
    vigencia_hasta date,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE auditoria_2026 PARTITION OF auditoria FOR VALUES IN ('2026');
CREATE TABLE auditoria_2027 PARTITION OF auditoria FOR VALUES IN ('2027');

-- ==========================================================================
--  4. RESTRICCIONES
--  Las foraneas al final para no depender del orden. Las que el esquema
--  tiene NOT VALID se emiten NOT VALID: validarlas es una consulta y el
--  migrador corre sin contexto de tenant (DAT-01 §0, hallazgo 4).
-- ==========================================================================

ALTER TABLE acceso ADD CONSTRAINT acceso_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE acceso ADD CONSTRAINT acceso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acceso ADD CONSTRAINT acceso_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['OPCION_MENU'::character varying, 'POLITICA'::character varying])::text[])));
ALTER TABLE area ADD CONSTRAINT area_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE area ADD CONSTRAINT area_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE auditoria ADD CONSTRAINT auditoria_observacion_ck CHECK ((length(btrim((observacion)::text)) >= 5));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_operacion_check CHECK (((operacion)::text = ANY ((ARRAY['ALTA'::character varying, 'MODIFICACION'::character varying, 'BAJA'::character varying, 'ANULACION'::character varying, 'REVERSION'::character varying, 'PERMISO'::character varying, 'ACCESO'::character varying])::text[])));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE caja ADD CONSTRAINT caja_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE caja ADD CONSTRAINT caja_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_serie_uq UNIQUE (municipalidad_id, serie);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_uq UNIQUE (municipalidad_id, caja_id, cajero, fecha);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_cierre_ck CHECK ((((tipo)::text <> 'CIERRE'::text) OR ((total_cobrado IS NOT NULL) AND (total_anulado IS NOT NULL) AND (neto IS NOT NULL) AND (total_declarado IS NOT NULL) AND (diferencia IS NOT NULL) AND (recibos_emitidos IS NOT NULL) AND (recibos_anulados IS NOT NULL) AND (revierte_a_id IS NULL))));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_diferencia_ck CHECK (((diferencia IS NULL) OR ((diferencia)::numeric = ((total_declarado)::numeric - (neto)::numeric))));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_neto_ck CHECK (((neto IS NULL) OR ((neto)::numeric = ((total_cobrado)::numeric - (total_anulado)::numeric))));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_recibos_anulados_check CHECK ((recibos_anulados >= 0));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_recibos_emitidos_check CHECK ((recibos_emitidos >= 0));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_reversion_ck CHECK ((((tipo)::text <> 'REVERSION'::text) OR ((revierte_a_id IS NOT NULL) AND (motivo IS NOT NULL) AND (btrim((motivo)::text) <> ''::text) AND (total_cobrado IS NULL) AND (total_anulado IS NULL) AND (neto IS NULL) AND (total_declarado IS NULL) AND (diferencia IS NULL) AND (recibos_emitidos IS NULL) AND (recibos_anulados IS NULL))));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_secuencia_check CHECK ((secuencia > 0));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_secuencia_uq UNIQUE (municipalidad_id, turno_id, secuencia);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['CIERRE'::character varying, 'REVERSION'::character varying])::text[])));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_total_anulado_check CHECK (((total_anulado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_total_cobrado_check CHECK (((total_cobrado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_total_declarado_check CHECK (((total_declarado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_anulado_check CHECK (((anulado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_cobrado_check CHECK (((cobrado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_declarado_check CHECK (((declarado)::numeric >= (0)::numeric));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_forma_pago_check CHECK (((forma_pago)::text = ANY ((ARRAY['EFECTIVO'::character varying, 'CHEQUE'::character varying, 'DEPOSITO'::character varying, 'TARJETA'::character varying, 'TRANSFERENCIA'::character varying])::text[])));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_neto_ck CHECK (((neto)::numeric = ((cobrado)::numeric - (anulado)::numeric)));
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_uq UNIQUE (municipalidad_id, cierre_id, forma_pago);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_formato_check CHECK (((formato)::text = ANY ((ARRAY['PDF'::character varying, 'XLS'::character varying, 'RTF'::character varying])::text[])));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_reimpresiones_check CHECK ((reimpresiones >= 0));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_numero_uq UNIQUE (municipalidad_id, tipo, ejercicio, numero);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE grupo ADD CONSTRAINT grupo_nombre_uq UNIQUE (municipalidad_id, nombre);
ALTER TABLE grupo ADD CONSTRAINT grupo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE grupo ADD CONSTRAINT grupo_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE miembro ADD CONSTRAINT miembro_pk PRIMARY KEY (municipalidad_id, grupo_id, usuario_id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_pkey PRIMARY KEY (id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['DISTRITAL'::character varying, 'PROVINCIAL'::character varying])::text[])));
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_ubigeo_key UNIQUE (ubigeo);
ALTER TABLE permiso ADD CONSTRAINT permiso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_sujeto_ck CHECK ((((grupo_id IS NOT NULL) AND (usuario_id IS NULL)) OR ((grupo_id IS NULL) AND (usuario_id IS NOT NULL))));
ALTER TABLE recibo ADD CONSTRAINT recibo_forma_pago_check CHECK (((forma_pago)::text = ANY ((ARRAY['EFECTIVO'::character varying, 'CHEQUE'::character varying, 'DEPOSITO'::character varying, 'TARJETA'::character varying, 'TRANSFERENCIA'::character varying])::text[])));
ALTER TABLE recibo ADD CONSTRAINT recibo_numero_uq UNIQUE (municipalidad_id, serie, numero);
ALTER TABLE recibo ADD CONSTRAINT recibo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE recibo ADD CONSTRAINT recibo_tipo_pago_check CHECK (((tipo_pago)::text = ANY ((ARRAY['NORMAL'::character varying, 'A_CUENTA'::character varying, 'PRECONVENIO'::character varying, 'CUOTA_CONVENIO'::character varying, 'TASA'::character varying])::text[])));
ALTER TABLE recibo ADD CONSTRAINT recibo_total_check CHECK (((total)::numeric >= (0)::numeric));
ALTER TABLE recibo_correlativo ADD CONSTRAINT recibo_correlativo_pk PRIMARY KEY (municipalidad_id, serie);
ALTER TABLE recibo_correlativo ADD CONSTRAINT recibo_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_desglose_ck CHECK (((monto)::numeric = ((((insoluto)::numeric + (reajuste)::numeric) + (interes)::numeric) + (gasto)::numeric)));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_gasto_check CHECK (((gasto)::numeric >= (0)::numeric));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_insoluto_check CHECK (((insoluto)::numeric >= (0)::numeric));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_interes_check CHECK (((interes)::numeric >= (0)::numeric));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_monto_check CHECK (((monto)::numeric > (0)::numeric));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_reajuste_check CHECK (((reajuste)::numeric >= (0)::numeric));
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_tasa_ck CHECK ((((tasa_id IS NULL) AND (cantidad IS NULL) AND (precio_unitario IS NULL)) OR ((tasa_id IS NOT NULL) AND (cantidad > 0) AND (precio_unitario IS NOT NULL) AND ((monto)::numeric = ((precio_unitario)::numeric * (cantidad)::numeric)))));
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_anulacion_ck CHECK ((((tipo)::text <> 'ANULACION'::text) OR ((motivo IS NOT NULL) AND (btrim((motivo)::text) <> ''::text) AND (importe IS NOT NULL))));
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_duplicado_ck CHECK ((((tipo)::text <> 'DUPLICADO'::text) OR (resumen IS NOT NULL)));
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_importe_check CHECK (((importe)::numeric >= (0)::numeric));
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['ANULACION'::character varying, 'DUPLICADO'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_pkey PRIMARY KEY (id);
ALTER TABLE respaldo ADD CONSTRAINT respaldo_resultado_check CHECK (((resultado)::text = ANY ((ARRAY['EN_CURSO'::character varying, 'EXITOSO'::character varying, 'FALLIDO'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_tamano_bytes_check CHECK (((tamano_bytes IS NULL) OR (tamano_bytes >= 0)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_terminado_ck CHECK ((((resultado)::text = 'EN_CURSO'::text) OR (fin IS NOT NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_completa_ck CHECK (((ultima_restauracion_verificada IS NULL) = (ultima_restauracion_verificada_por IS NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_exitosa_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((resultado)::text = 'EXITOSO'::text)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_posterior_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((fin IS NOT NULL) AND (ultima_restauracion_verificada >= fin))));
ALTER TABLE sesion ADD CONSTRAINT sesion_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE sesion ADD CONSTRAINT sesion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_codigo_uq UNIQUE (municipalidad_id, codigo, vigencia_desde);
ALTER TABLE tasa ADD CONSTRAINT tasa_importe_check CHECK (((importe)::numeric >= (0)::numeric));
ALTER TABLE tasa ADD CONSTRAINT tasa_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_cuenta_uq UNIQUE (municipalidad_id, cuenta);
ALTER TABLE usuario ADD CONSTRAINT usuario_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_sujeto_uq UNIQUE (municipalidad_id, sujeto_oidc);
ALTER TABLE usuario ADD CONSTRAINT usuario_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE acceso ADD CONSTRAINT acceso_modulo_fk FOREIGN KEY (municipalidad_id, modulo_id) REFERENCES modulo_sistema(municipalidad_id, id);
ALTER TABLE area ADD CONSTRAINT area_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE caja ADD CONSTRAINT caja_area_fk FOREIGN KEY (municipalidad_id, area_id) REFERENCES area(municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_caja_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_revierte_fk FOREIGN KEY (municipalidad_id, revierte_a_id) REFERENCES cierre_turno(municipalidad_id, id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id);
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_cierre_fk FOREIGN KEY (municipalidad_id, cierre_id) REFERENCES cierre_turno(municipalidad_id, id);
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE grupo ADD CONSTRAINT grupo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE miembro ADD CONSTRAINT miembro_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_sistema_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE permiso ADD CONSTRAINT permiso_acceso_fk FOREIGN KEY (municipalidad_id, acceso_id) REFERENCES acceso(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE recibo ADD CONSTRAINT recibo_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
--  [CRUZA LA FRONTERA] recibo.recibo_contribuyente_fk: FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
ALTER TABLE recibo ADD CONSTRAINT recibo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo ADD CONSTRAINT recibo_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE recibo_correlativo ADD CONSTRAINT recibo_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_tasa_fk FOREIGN KEY (municipalidad_id, tasa_id) REFERENCES tasa(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id);
ALTER TABLE sesion ADD CONSTRAINT sesion_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_area_fk FOREIGN KEY (municipalidad_id, area_id) REFERENCES area(municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE usuario ADD CONSTRAINT usuario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);

-- ==========================================================================
--  5. INDICES
--  Empezando por municipalidad_id. Los de una tabla particionada se propagan
--  solos a sus particiones, asi que aqui no se repiten.
-- ==========================================================================

CREATE INDEX acceso_modulo_ix ON public.acceso USING btree (municipalidad_id, modulo_id, tipo);
CREATE INDEX auditoria_tabla_ix ON public.auditoria USING btree (municipalidad_id, tabla, clave);
CREATE INDEX auditoria_usuario_ix ON public.auditoria USING btree (municipalidad_id, usuario_id, fecha);
CREATE UNIQUE INDEX cierre_turno_reversion_uq ON public.cierre_turno USING btree (municipalidad_id, revierte_a_id) WHERE ((tipo)::text = 'REVERSION'::text);
CREATE INDEX cierre_turno_turno_ix ON public.cierre_turno USING btree (municipalidad_id, turno_id, id DESC);
CREATE INDEX documento_referencia_ix ON public.documento_emitido USING btree (municipalidad_id, tipo, referencia);
CREATE INDEX miembro_usuario_ix ON public.miembro USING btree (municipalidad_id, usuario_id);
CREATE INDEX permiso_acceso_ix ON public.permiso USING btree (municipalidad_id, acceso_id);
CREATE UNIQUE INDEX permiso_grupo_uq ON public.permiso USING btree (municipalidad_id, acceso_id, grupo_id) WHERE (grupo_id IS NOT NULL);
CREATE UNIQUE INDEX permiso_usuario_uq ON public.permiso USING btree (municipalidad_id, acceso_id, usuario_id) WHERE (usuario_id IS NOT NULL);
CREATE INDEX recibo_contribuyente_ix ON public.recibo USING btree (municipalidad_id, contribuyente_id);
CREATE INDEX recibo_fecha_ix ON public.recibo USING btree (municipalidad_id, fecha);
CREATE UNIQUE INDEX recibo_idempotencia_uq ON public.recibo USING btree (municipalidad_id, clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);
CREATE INDEX recibo_turno_ix ON public.recibo USING btree (municipalidad_id, turno_id);
CREATE INDEX recibo_detalle_recibo_ix ON public.recibo_detalle USING btree (municipalidad_id, recibo_id);
CREATE INDEX recibo_detalle_tasa_ix ON public.recibo_detalle USING btree (municipalidad_id, tasa_id) WHERE (tasa_id IS NOT NULL);
CREATE INDEX recibo_detalle_tributo_ix ON public.recibo_detalle USING btree (municipalidad_id, tributo);
CREATE UNIQUE INDEX recibo_movimiento_anulacion_uq ON public.recibo_movimiento USING btree (municipalidad_id, recibo_id) WHERE ((tipo)::text = 'ANULACION'::text);
CREATE INDEX recibo_movimiento_recibo_ix ON public.recibo_movimiento USING btree (municipalidad_id, recibo_id, tipo);
CREATE INDEX recibo_movimiento_turno_ix ON public.recibo_movimiento USING btree (municipalidad_id, turno_id, tipo);
CREATE INDEX respaldo_inicio_ix ON public.respaldo USING btree (inicio DESC);
CREATE INDEX sesion_abierta_ix ON public.sesion USING btree (municipalidad_id, usuario_id) WHERE (fin IS NULL);
CREATE INDEX tasa_vigencia_ix ON public.tasa USING btree (municipalidad_id, codigo, vigencia_desde DESC);

-- ==========================================================================
--  6. ROW LEVEL SECURITY
--  Sin valor por omision: sin contexto de tenant, la consulta FALLA. Y
--  FORCE, porque sin el el DUENO de la tabla la omite. Cada particion repite
--  su bloque: una particion NO HEREDA la politica de su padre (DAT-01 §0,
--  hallazgo 2).
-- ==========================================================================

ALTER TABLE acceso ENABLE ROW LEVEL SECURITY;
ALTER TABLE acceso FORCE ROW LEVEL SECURITY;
CREATE POLICY acceso_tenant ON acceso FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE area ENABLE ROW LEVEL SECURITY;
ALTER TABLE area FORCE ROW LEVEL SECURITY;
CREATE POLICY area_tenant ON area FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE auditoria ENABLE ROW LEVEL SECURITY;
ALTER TABLE auditoria FORCE ROW LEVEL SECURITY;
CREATE POLICY auditoria_tenant ON auditoria FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE auditoria_2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE auditoria_2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY auditoria_2026_tenant ON auditoria_2026 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE auditoria_2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE auditoria_2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY auditoria_2027_tenant ON auditoria_2027 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE caja ENABLE ROW LEVEL SECURITY;
ALTER TABLE caja FORCE ROW LEVEL SECURITY;
CREATE POLICY caja_tenant ON caja FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cierre_caja ENABLE ROW LEVEL SECURITY;
ALTER TABLE cierre_caja FORCE ROW LEVEL SECURITY;
CREATE POLICY cierre_caja_tenant ON cierre_caja FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cierre_turno ENABLE ROW LEVEL SECURITY;
ALTER TABLE cierre_turno FORCE ROW LEVEL SECURITY;
CREATE POLICY cierre_turno_tenant ON cierre_turno FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cierre_turno_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE cierre_turno_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY cierre_turno_detalle_tenant ON cierre_turno_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE documento_emitido ENABLE ROW LEVEL SECURITY;
ALTER TABLE documento_emitido FORCE ROW LEVEL SECURITY;
CREATE POLICY documento_por_tenant ON documento_emitido FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE grupo ENABLE ROW LEVEL SECURITY;
ALTER TABLE grupo FORCE ROW LEVEL SECURITY;
CREATE POLICY grupo_tenant ON grupo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE miembro ENABLE ROW LEVEL SECURITY;
ALTER TABLE miembro FORCE ROW LEVEL SECURITY;
CREATE POLICY miembro_tenant ON miembro FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE modulo_sistema ENABLE ROW LEVEL SECURITY;
ALTER TABLE modulo_sistema FORCE ROW LEVEL SECURITY;
CREATE POLICY modulo_sistema_tenant ON modulo_sistema FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE municipalidad ENABLE ROW LEVEL SECURITY;
ALTER TABLE municipalidad FORCE ROW LEVEL SECURITY;
CREATE POLICY municipalidad_escritura ON municipalidad FOR ALL TO sgtm_owner
    USING (true)
    WITH CHECK (true);
CREATE POLICY municipalidad_lectura ON municipalidad FOR SELECT TO PUBLIC
    USING (true);
ALTER TABLE permiso ENABLE ROW LEVEL SECURITY;
ALTER TABLE permiso FORCE ROW LEVEL SECURITY;
CREATE POLICY permiso_tenant ON permiso FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE recibo ENABLE ROW LEVEL SECURITY;
ALTER TABLE recibo FORCE ROW LEVEL SECURITY;
CREATE POLICY recibo_tenant ON recibo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE recibo_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE recibo_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY recibo_correlativo_tenant ON recibo_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE recibo_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE recibo_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY recibo_detalle_tenant ON recibo_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE recibo_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE recibo_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY recibo_movimiento_tenant ON recibo_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE respaldo ENABLE ROW LEVEL SECURITY;
ALTER TABLE respaldo FORCE ROW LEVEL SECURITY;
CREATE POLICY respaldo_escritura ON respaldo FOR ALL TO sgtm_owner
    USING (true)
    WITH CHECK (true);
CREATE POLICY respaldo_lectura ON respaldo FOR SELECT TO PUBLIC
    USING (true);
ALTER TABLE sesion ENABLE ROW LEVEL SECURITY;
ALTER TABLE sesion FORCE ROW LEVEL SECURITY;
CREATE POLICY sesion_tenant ON sesion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE tasa ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasa FORCE ROW LEVEL SECURITY;
CREATE POLICY tasa_tenant ON tasa FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario FORCE ROW LEVEL SECURITY;
CREATE POLICY usuario_tenant ON usuario FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));

-- ==========================================================================
--  7. PRIVILEGIOS
--  El rol de la aplicacion NO es dueno ni superusuario, y NO recibe
--  privilegios sobre las particiones: se los da el padre. Los privilegios
--  POR COLUMNA son los que sostienen que un acto mueva solo lo suyo (V54);
--  un volcado descuidado los devuelve enteros.
-- ==========================================================================

GRANT INSERT, SELECT, UPDATE ON acceso TO sgtm_app;
GRANT SELECT ON acceso TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON area TO sgtm_app;
GRANT SELECT ON area TO sgtm_readonly;
GRANT INSERT, SELECT ON auditoria TO sgtm_app;
GRANT SELECT ON auditoria TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON caja TO sgtm_app;
GRANT SELECT ON caja TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON cierre_caja TO sgtm_app;
GRANT SELECT ON cierre_caja TO sgtm_readonly;
GRANT INSERT, SELECT ON cierre_turno TO sgtm_app;
GRANT SELECT ON cierre_turno TO sgtm_readonly;
GRANT INSERT, SELECT ON cierre_turno_detalle TO sgtm_app;
GRANT SELECT ON cierre_turno_detalle TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON documento_emitido TO sgtm_app;
GRANT SELECT ON documento_emitido TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON grupo TO sgtm_app;
GRANT SELECT ON grupo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON miembro TO sgtm_app;
GRANT SELECT ON miembro TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON modulo_sistema TO sgtm_app;
GRANT SELECT ON modulo_sistema TO sgtm_readonly;
GRANT SELECT ON municipalidad TO sgtm_app;
GRANT SELECT ON municipalidad TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON permiso TO sgtm_app;
GRANT SELECT ON permiso TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo TO sgtm_app;
GRANT SELECT ON recibo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON recibo_correlativo TO sgtm_app;
GRANT SELECT ON recibo_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo_detalle TO sgtm_app;
GRANT SELECT ON recibo_detalle TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo_movimiento TO sgtm_app;
GRANT SELECT ON recibo_movimiento TO sgtm_readonly;
GRANT SELECT ON respaldo TO sgtm_app;
GRANT SELECT ON respaldo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sesion TO sgtm_app;
GRANT SELECT ON sesion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON tasa TO sgtm_app;
GRANT SELECT ON tasa TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON usuario TO sgtm_app;
GRANT SELECT ON usuario TO sgtm_readonly;

-- ==========================================================================
--  8. DISPARADORES DE INMUTABILIDAD Y DE INVARIANTE
--  Con sus funciones. Un disparador sin su funcion no protege nada.
-- ==========================================================================

CREATE TRIGGER documento_inmutable_trg BEFORE UPDATE ON public.documento_emitido FOR EACH ROW EXECUTE FUNCTION documento_solo_cuenta_reimpresiones();

-- ==========================================================================
--  9. COMENTARIOS
--  El por que de una columna, que es lo primero que se pierde.
-- ==========================================================================

COMMENT ON COLUMN caja.serie IS 'La serie de sus recibos, unica en la municipalidad (#33). Es lo que impide que dos ventanillas compitan por el mismo correlativo: cada una incrementa su propia fila de recibo_correlativo.';
COMMENT ON TABLE cierre_caja IS 'El turno de una caja: se abre por cajero y fecha (#33) y se cobra contra el. Su fila es donde se serializa la ventanilla —una cobranza la bloquea con FOR UPDATE antes de numerar y de asentar—, y por eso sgtm_app CONSERVA el UPDATE aunque el turno no se edite nunca: PostgreSQL exige ese privilegio para poder bloquear una fila. La inmutabilidad la sostiene el escaner de fuentes (#36, V32 §1.bis). El cierre, su reversion y el estado que de ellos se deriva viven en cierre_turno.';
COMMENT ON COLUMN cierre_caja.fecha_apertura IS 'Cuando se abrio el turno. Sale del reloj INYECTADO de la aplicacion, no de un DEFAULT now() de la base: la fila se audita por ejercicio y el ejercicio tiene que ser el mismo que la aplicacion cree que es.';
COMMENT ON TABLE cierre_turno IS 'El cierre de un turno de caja y su reversion (#36, RF-087). Solo se agrega: un cierre no se modifica ni se borra —se reversa con otro registro que lo deja sin efecto y reabre el turno (regla 4, RNF-051)—. El estado del turno se DERIVA de aqui: hay cierre vigente o no lo hay.';
COMMENT ON COLUMN cierre_turno.secuencia IS 'El orden del movimiento dentro del turno, unico por turno. Es lo que impide dos cierres simultaneos: los dos calculan la misma secuencia y uno recibe 23505. Un indice unico parcial «un solo CIERRE» no serviria, porque despues de una reversion tiene que caber otro.';
COMMENT ON COLUMN cierre_turno.total_anulado IS 'Lo que las anulaciones del dia sacaron del cajon, tomado de recibo_movimiento.importe —el importe congelado, no releido— para los movimientos cuyo turno_id es este. Una anulacion lleva el turno DEL RECIBO (V30 §4): el dinero sale de donde entro.';
COMMENT ON COLUMN cierre_turno.diferencia IS 'Lo declarado menos el neto del sistema. Admite negativo a proposito, y es la unica columna de importe del esquema que lo hace: un arqueo que exigiera diferencia cero haria que el cajero al que le faltan diez soles declarara lo que el sistema diga, y el descuadre desapareceria del papel en vez de quedar escrito.';
COMMENT ON TABLE cierre_turno_detalle IS 'El arqueo del cierre, una fila por medio de pago (#36, RF-087): lo cobrado, lo anulado, el neto del sistema y lo DECLARADO por el cajero. Lo declarado no esta en ningun otro sitio —es lo que se conto en el cajon—, y por eso el desglose se congela aqui en vez de recomponerse sumando recibos.';
COMMENT ON TABLE documento_emitido IS 'Documentos emitidos con los datos que los generaron, para reimprimirlos identicos (RF-132).';
COMMENT ON TABLE municipalidad IS 'Registro de tenants. No es tabla de tenant: la aplicacion la lee entera porque los procesos masivos iteran municipalidad por municipalidad. Solo sgtm_owner escribe.';
COMMENT ON COLUMN municipalidad.es_demostracion IS 'Instalacion de demostracion: todo documento emitido bajo este tenant sale marcado, en los tres formatos. Lo lee la capa de documentos, no cada emisor. Solo sgtm_owner la escribe, como el alta de la municipalidad.';
COMMENT ON COLUMN recibo.campania_beneficio IS 'Que campana de beneficio se declaro en ventanilla. Hoy es SOLO constancia: el importe cobrado es el integro. Aplicarle un descuento esta bloqueado por D-02b, que es la que firma los valores de ordenanza local con su ratificacion provincial (#33).';
COMMENT ON COLUMN recibo.actualizado_a IS 'A que fecha estaban actualizados los importes que este recibo cobro (regla 9, RNF-075). En caja tributaria es la fecha de pago con la que se releyo deudaActualizadaA; en caja de tasas, la fecha a la que la tarifa del TUPA estaba vigente. Sin ella un duplicado no puede explicar por que su interes no es el de hoy.';
COMMENT ON COLUMN recibo.clave_idempotencia IS 'La clave que el cliente manda en la cabecera idempotency-key. Con su indice unico parcial, reenviar la misma cobranza devuelve el recibo de la primera y no emite otro.';
COMMENT ON TABLE recibo_correlativo IS 'El ultimo numero emitido por municipalidad y serie de recibo (#33). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. El UPDATE bloquea la fila, asi que dos cobranzas concurrentes de la misma caja se serializan en el motor y salen con numeros consecutivos, sin huecos ni repetidos.';
COMMENT ON TABLE recibo_movimiento IS 'Lo que le pasa a un recibo despues de emitirse (#34, RF-082, RF-083): ANULACION o DUPLICADO. Solo se agrega. El estado de un recibo se DERIVA de aqui, porque el recibo no se edita (V29); las columnas de anulacion que V3 le habia puesto se retiraron en esta misma migracion por decir EMITIDO para siempre.';
COMMENT ON COLUMN recibo_movimiento.turno_id IS 'El turno DEL RECIBO, no el de quien anula: una anulacion del mismo dia saca dinero del cajon en el que entro, y el arqueo de ese turno (#36) tiene que poder restarla.';
COMMENT ON COLUMN recibo_movimiento.importe IS 'El importe del recibo que deja de estar cobrado, copiado y no releido. Dentro de dos anios el libro dira otra cosa -habra mas asientos- y el acta de anulacion tiene que explicarse sola. Es lo que el arqueo del turno (#36) resta del cajon; en una cobranza tributaria coincide con lo que la reversion devolvio al libro, y la aplicacion lo comprueba.';
COMMENT ON COLUMN recibo_movimiento.resumen IS 'SHA-256 del recibo dibujado a partir de lo congelado. Misma garantia que documento_emitido.resumen (V15): la segunda reimpresion se compara con la primera y FALLA si no coincide, en vez de entregar un papel distinto con el mismo numero.';
COMMENT ON TABLE respaldo IS 'Estado de las copias de seguridad (RF-126). La aplicacion solo lee: quien hace la copia y escribe aqui es el proceso de despliegue, como sgtm_owner.';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada IS 'Instante en que se comprobo, restaurandola de verdad, que esta copia se puede restaurar (RNF-079). NULO significa «nunca se probo», nunca «hoy».';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada_por IS 'Que proceso lo comprobo: el simulacro de restauracion y el ambiente contra el que corrio. No es un usuario de la aplicacion: la aplicacion no restaura.';
