-- ============================================================================
--  NORMATIVA — V1__baseline.sql
--
--  El esquema de Valores normativos, generado del esquema de `sgtm` restringido a las
--  tablas que le pertenecen (ADR-0032 §1). 19 tablas.
--
--  Parametros versionados, conjuntos sellados y los tres cuadros de valuacion, que desde
--  `V55` son NACIONALES (ADR-0017). 6 tablas propias mas las 13 comunes.
--
--  El arancel NO esta aqui: es municipal, su fuente es un GeoPackage y se llavea por via,
--  asi que vive en `catastro` con la tabla `via` a la que referencia.
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

CREATE TABLE conjunto_parametro_detalle (
    municipalidad_id bigint NOT NULL,
    conjunto_id bigint NOT NULL,
    parametro_id bigint NOT NULL
);

CREATE TABLE conjunto_parametros (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ejercicio ejercicio NOT NULL,
    version smallint NOT NULL,
    estado character varying(10) DEFAULT 'ABIERTO'::character varying NOT NULL,
    fecha_sellado timestamp with time zone,
    usuario_sellado character varying(60)
);

CREATE TABLE depreciacion (
    municipalidad_id bigint,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    material character varying(20) NOT NULL,
    estado_conservacion character varying(20) NOT NULL,
    antiguedad_hasta smallint,
    porcentaje alicuota NOT NULL,
    documento_fuente character varying(200) NOT NULL,
    publicacion_id bigint NOT NULL,
    uso character varying(2) NOT NULL
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

CREATE TABLE parametro_tributario (
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    municipalidad_id bigint,
    tipo character varying(40) NOT NULL,
    clave character varying(120),
    valor_numerico monto_calc,
    valor_texto character varying(200),
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_fuente character varying(200) NOT NULL,
    sellado boolean DEFAULT false NOT NULL,
    usuario_carga character varying(60) NOT NULL,
    usuario_aprueba character varying(60),
    fecha_carga timestamp with time zone DEFAULT now() NOT NULL
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

CREATE TABLE valor_referencial_vehiculo (
    municipalidad_id bigint,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ejercicio ejercicio NOT NULL,
    marca character varying(60) NOT NULL,
    modelo character varying(80) NOT NULL,
    anio_fabricacion ejercicio NOT NULL,
    valor dinero NOT NULL,
    documento_fuente character varying(200) NOT NULL,
    categoria character varying(20) NOT NULL,
    publicacion_id bigint NOT NULL
);

CREATE TABLE valor_unitario_edificacion (
    municipalidad_id bigint,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    partida character varying(20) NOT NULL,
    categoria character(1) NOT NULL,
    valor_m2 monto_calc NOT NULL,
    documento_fuente character varying(200) NOT NULL,
    anio_construccion_desde ejercicio NOT NULL,
    anio_construccion_hasta ejercicio,
    publicacion_id bigint NOT NULL
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
ALTER TABLE auditoria ADD CONSTRAINT auditoria_observacion_ck CHECK ((length(btrim((observacion)::text)) >= 5));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_operacion_check CHECK (((operacion)::text = ANY ((ARRAY['ALTA'::character varying, 'MODIFICACION'::character varying, 'BAJA'::character varying, 'ANULACION'::character varying, 'REVERSION'::character varying, 'PERMISO'::character varying, 'ACCESO'::character varying])::text[])));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_detalle_pk PRIMARY KEY (municipalidad_id, conjunto_id, parametro_id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_parametros_estado_check CHECK (((estado)::text = ANY ((ARRAY['ABIERTO'::character varying, 'SELLADO'::character varying])::text[])));
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_sellado_ck CHECK ((((estado)::text = 'ABIERTO'::text) OR ((fecha_sellado IS NOT NULL) AND (usuario_sellado IS NOT NULL))));
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_uq UNIQUE (municipalidad_id, ejercicio, version);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_antiguedad_hasta_check CHECK ((antiguedad_hasta > 0));
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_nacional_ck CHECK ((municipalidad_id IS NULL));
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_pk PRIMARY KEY (id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_uq UNIQUE NULLS NOT DISTINCT (publicacion_id, uso, material, estado_conservacion, antiguedad_hasta);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_uso_check CHECK (((uso)::text ~ '^0[1-4]$'::text));
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
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_doble_verificacion_ck CHECK (((usuario_aprueba IS NULL) OR ((usuario_aprueba)::text <> (usuario_carga)::text)));
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_tributario_pkey PRIMARY KEY (id);
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_valor_ck CHECK (((valor_numerico IS NOT NULL) OR (valor_texto IS NOT NULL)));
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE permiso ADD CONSTRAINT permiso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_sujeto_ck CHECK ((((grupo_id IS NOT NULL) AND (usuario_id IS NULL)) OR ((grupo_id IS NULL) AND (usuario_id IS NOT NULL))));
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
ALTER TABLE usuario ADD CONSTRAINT usuario_cuenta_uq UNIQUE (municipalidad_id, cuenta);
ALTER TABLE usuario ADD CONSTRAINT usuario_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_sujeto_uq UNIQUE (municipalidad_id, sujeto_oidc);
ALTER TABLE usuario ADD CONSTRAINT usuario_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_nacional_ck CHECK ((municipalidad_id IS NULL));
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_pk PRIMARY KEY (id);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_uq UNIQUE (publicacion_id, categoria, marca, modelo, anio_fabricacion);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_vehiculo_valor_check CHECK (((valor)::numeric >= (0)::numeric));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_anio_ck CHECK (((anio_construccion_hasta IS NULL) OR ((anio_construccion_hasta)::smallint >= (anio_construccion_desde)::smallint)));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_edificacion_categoria_check CHECK ((categoria ~ '^[A-J]$'::text));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_edificacion_partida_check CHECK (((partida)::text = ANY ((ARRAY['MUROS'::character varying, 'TECHOS'::character varying, 'PUERTAS'::character varying])::text[])));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_edificacion_valor_m2_check CHECK (((valor_m2)::numeric >= (0)::numeric));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_nacional_ck CHECK ((municipalidad_id IS NULL));
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_pk PRIMARY KEY (id);
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_uq UNIQUE (publicacion_id, partida, categoria, anio_construccion_desde);
ALTER TABLE acceso ADD CONSTRAINT acceso_modulo_fk FOREIGN KEY (municipalidad_id, modulo_id) REFERENCES modulo_sistema(municipalidad_id, id);
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_detalle_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_parametro_detalle_parametro_id_fkey FOREIGN KEY (parametro_id) REFERENCES parametro_tributario(id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_parametros_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE grupo ADD CONSTRAINT grupo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE miembro ADD CONSTRAINT miembro_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_sistema_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_tributario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE permiso ADD CONSTRAINT permiso_acceso_fk FOREIGN KEY (municipalidad_id, acceso_id) REFERENCES acceso(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE sesion ADD CONSTRAINT sesion_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_vehiculo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_edificacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);

-- ==========================================================================
--  5. INDICES
--  Empezando por municipalidad_id. Los de una tabla particionada se propagan
--  solos a sus particiones, asi que aqui no se repiten.
-- ==========================================================================

CREATE INDEX acceso_modulo_ix ON public.acceso USING btree (municipalidad_id, modulo_id, tipo);
CREATE INDEX auditoria_tabla_ix ON public.auditoria USING btree (municipalidad_id, tabla, clave);
CREATE INDEX auditoria_usuario_ix ON public.auditoria USING btree (municipalidad_id, usuario_id, fecha);
CREATE INDEX conjunto_sellado_vigente_ix ON public.conjunto_parametros USING btree (municipalidad_id, ejercicio, version DESC) WHERE ((estado)::text = 'SELLADO'::text);
CREATE INDEX documento_referencia_ix ON public.documento_emitido USING btree (municipalidad_id, tipo, referencia);
CREATE INDEX miembro_usuario_ix ON public.miembro USING btree (municipalidad_id, usuario_id);
CREATE INDEX permiso_acceso_ix ON public.permiso USING btree (municipalidad_id, acceso_id);
CREATE UNIQUE INDEX permiso_grupo_uq ON public.permiso USING btree (municipalidad_id, acceso_id, grupo_id) WHERE (grupo_id IS NOT NULL);
CREATE UNIQUE INDEX permiso_usuario_uq ON public.permiso USING btree (municipalidad_id, acceso_id, usuario_id) WHERE (usuario_id IS NOT NULL);
CREATE INDEX respaldo_inicio_ix ON public.respaldo USING btree (inicio DESC);
CREATE INDEX sesion_abierta_ix ON public.sesion USING btree (municipalidad_id, usuario_id) WHERE (fin IS NULL);
CREATE INDEX valor_referencial_catalogo_ix ON public.valor_referencial_vehiculo USING btree (publicacion_id, marca, modelo);

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
ALTER TABLE conjunto_parametro_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE conjunto_parametro_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY conjunto_parametro_detalle_tenant ON conjunto_parametro_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE conjunto_parametros ENABLE ROW LEVEL SECURITY;
ALTER TABLE conjunto_parametros FORCE ROW LEVEL SECURITY;
CREATE POLICY conjunto_parametros_tenant ON conjunto_parametros FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE depreciacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE depreciacion FORCE ROW LEVEL SECURITY;
CREATE POLICY depreciacion_escritura ON depreciacion FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY depreciacion_lectura ON depreciacion FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));
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
ALTER TABLE parametro_tributario ENABLE ROW LEVEL SECURITY;
ALTER TABLE parametro_tributario FORCE ROW LEVEL SECURITY;
CREATE POLICY parametro_escritura ON parametro_tributario FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY parametro_lectura ON parametro_tributario FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));
ALTER TABLE permiso ENABLE ROW LEVEL SECURITY;
ALTER TABLE permiso FORCE ROW LEVEL SECURITY;
CREATE POLICY permiso_tenant ON permiso FOR ALL TO PUBLIC
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
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario FORCE ROW LEVEL SECURITY;
CREATE POLICY usuario_tenant ON usuario FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_referencial_vehiculo ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_referencial_vehiculo FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_referencial_escritura ON valor_referencial_vehiculo FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY valor_referencial_lectura ON valor_referencial_vehiculo FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));
ALTER TABLE valor_unitario_edificacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_unitario_edificacion FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_unitario_escritura ON valor_unitario_edificacion FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY valor_unitario_lectura ON valor_unitario_edificacion FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));

-- ==========================================================================
--  7. PRIVILEGIOS
--  El rol de la aplicacion NO es dueno ni superusuario, y NO recibe
--  privilegios sobre las particiones: se los da el padre. Los privilegios
--  POR COLUMNA son los que sostienen que un acto mueva solo lo suyo (V54);
--  un volcado descuidado los devuelve enteros.
-- ==========================================================================

GRANT INSERT, SELECT, UPDATE ON acceso TO sgtm_app;
GRANT SELECT ON acceso TO sgtm_readonly;
GRANT INSERT, SELECT ON auditoria TO sgtm_app;
GRANT SELECT ON auditoria TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON conjunto_parametro_detalle TO sgtm_app;
GRANT SELECT ON conjunto_parametro_detalle TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON conjunto_parametros TO sgtm_app;
GRANT SELECT ON conjunto_parametros TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON depreciacion TO rol_carga_parametros;
GRANT SELECT ON depreciacion TO sgtm_app;
GRANT SELECT ON depreciacion TO sgtm_readonly;
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
GRANT INSERT, SELECT, UPDATE ON parametro_tributario TO rol_carga_parametros;
GRANT SELECT ON parametro_tributario TO sgtm_app;
GRANT SELECT ON parametro_tributario TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON permiso TO sgtm_app;
GRANT SELECT ON permiso TO sgtm_readonly;
GRANT SELECT ON respaldo TO sgtm_app;
GRANT SELECT ON respaldo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sesion TO sgtm_app;
GRANT SELECT ON sesion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON usuario TO sgtm_app;
GRANT SELECT ON usuario TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_referencial_vehiculo TO rol_carga_parametros;
GRANT SELECT ON valor_referencial_vehiculo TO sgtm_app;
GRANT SELECT ON valor_referencial_vehiculo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_unitario_edificacion TO rol_carga_parametros;
GRANT SELECT ON valor_unitario_edificacion TO sgtm_app;
GRANT SELECT ON valor_unitario_edificacion TO sgtm_readonly;

-- ==========================================================================
--  8. DISPARADORES DE INMUTABILIDAD Y DE INVARIANTE
--  Con sus funciones. Un disparador sin su funcion no protege nada.
-- ==========================================================================

CREATE TRIGGER detalle_de_conjunto_sellado_inmutable BEFORE INSERT OR UPDATE ON public.conjunto_parametro_detalle FOR EACH ROW EXECUTE FUNCTION detalle_de_conjunto_sellado_es_inmutable();
CREATE TRIGGER conjunto_sellado_inmutable BEFORE UPDATE ON public.conjunto_parametros FOR EACH ROW EXECUTE FUNCTION conjunto_sellado_es_inmutable();
CREATE TRIGGER depreciacion_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.depreciacion FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();
CREATE TRIGGER documento_inmutable_trg BEFORE UPDATE ON public.documento_emitido FOR EACH ROW EXECUTE FUNCTION documento_solo_cuenta_reimpresiones();
CREATE TRIGGER valor_referencial_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.valor_referencial_vehiculo FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();
CREATE TRIGGER valor_unitario_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.valor_unitario_edificacion FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();

-- ==========================================================================
--  9. COMENTARIOS
--  El por que de una columna, que es lo primero que se pierde.
-- ==========================================================================

COMMENT ON COLUMN depreciacion.municipalidad_id IS 'Siempre nulo: la tabla de depreciacion es nacional (ARQ-09 §2.1, D-13).';
COMMENT ON COLUMN depreciacion.antiguedad_hasta IS 'Extremo superior del tramo de antiguedad, en anios; NULO es «mas de 50 anios», el tramo abierto con que cierra cada tabla del Anexo I. Entra en depreciacion_uq, que por eso se declara NULLS NOT DISTINCT: con la semantica por omision el tramo abierto se podria duplicar con dos porcentajes distintos.';
COMMENT ON COLUMN depreciacion.publicacion_id IS 'La edicion a la que pertenece esta fila (ver valor_unitario_edificacion.publicacion_id).';
COMMENT ON COLUMN depreciacion.uso IS 'La tabla del Anexo I del Reglamento Nacional de Tasaciones a la que pertenece la fila (01 vivienda, 02 tiendas y depositos, 03 oficinas, 04 salud/industria/educacion), con el numero que usa la propia norma. Su titulo verbatim esta en depreciacion.md §1. Que tabla le toca a un predio es criterio y no vive aqui: RT-004 todavia no esta escrita.';
COMMENT ON TABLE documento_emitido IS 'Documentos emitidos con los datos que los generaron, para reimprimirlos identicos (RF-132).';
COMMENT ON TABLE municipalidad IS 'Registro de tenants. No es tabla de tenant: la aplicacion la lee entera porque los procesos masivos iteran municipalidad por municipalidad. Solo sgtm_owner escribe.';
COMMENT ON COLUMN municipalidad.es_demostracion IS 'Instalacion de demostracion: todo documento emitido bajo este tenant sale marcado, en los tres formatos. Lo lee la capa de documentos, no cada emisor. Solo sgtm_owner la escribe, como el alta de la municipalidad.';
COMMENT ON TABLE respaldo IS 'Estado de las copias de seguridad (RF-126). La aplicacion solo lee: quien hace la copia y escribe aqui es el proceso de despliegue, como sgtm_owner.';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada IS 'Instante en que se comprobo, restaurandola de verdad, que esta copia se puede restaurar (RNF-079). NULO significa «nunca se probo», nunca «hoy».';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada_por IS 'Que proceso lo comprobo: el simulacro de restauracion y el ambiente contra el que corrio. No es un usuario de la aplicacion: la aplicacion no restaura.';
COMMENT ON COLUMN valor_referencial_vehiculo.municipalidad_id IS 'Siempre nulo: la tabla de valores referenciales la aprueba el MEF (ARQ-09 §2.1, D-13).';
COMMENT ON COLUMN valor_referencial_vehiculo.categoria IS 'La categoria con que el anexo del MEF publica la fila (A1..A4, BUSES Y OMNIBUSES, CAMIONES, CAMIONETAS, REMOLCADORES). Es parte de la identidad: el anexo publica «OTROS MODELOS» en cada categoria, con un valor distinto en cada una.';
COMMENT ON COLUMN valor_referencial_vehiculo.publicacion_id IS 'La edicion a la que pertenece esta fila (ver valor_unitario_edificacion.publicacion_id).';
COMMENT ON COLUMN valor_unitario_edificacion.municipalidad_id IS 'Siempre nulo: el cuadro de valores unitarios es nacional (ARQ-09 §2.1, D-13). La columna se conserva para que la politica de RLS compare algo y para que admitir una fila municipal exija quitar valor_unitario_nacional_ck y justificarlo.';
COMMENT ON COLUMN valor_unitario_edificacion.partida IS 'Las TRES partidas de apreciacion exterior del Cuadro de Valores Unitarios: MUROS (muros y columnas), TECHOS, PUERTAS (puertas y ventanas). No son las siete de construccion.categoria_*, que son el formulario del manual y otra cosa (V59).';
COMMENT ON COLUMN valor_unitario_edificacion.categoria IS 'La fila del cuadro de valores unitarios, A..J. La J existe solo en el Anexo I.4 (Selva) y solo en la partida de muros y columnas; el rango es unico porque esta columna no sabe de que region es el cuadro (V58).';
COMMENT ON COLUMN valor_unitario_edificacion.anio_construccion_desde IS 'Extremo inferior del ano de construccion al que aplica esta letra (NEG-05 RT-002, ../srtm): el cuadro de valores unitarios es una matriz categoria x ano de construccion, no solo categoria.';
COMMENT ON COLUMN valor_unitario_edificacion.anio_construccion_hasta IS 'Extremo superior del tramo; nulo cuando la tabla no le pone tope (la construccion mas reciente).';
COMMENT ON COLUMN valor_unitario_edificacion.publicacion_id IS 'La edicion a la que pertenece esta fila: un parametro_tributario que es la cabecera del cuadro. El conjunto sellado de una municipalidad la compone por conjunto_parametro_detalle.';
