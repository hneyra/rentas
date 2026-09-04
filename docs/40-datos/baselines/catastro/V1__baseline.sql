-- ============================================================================
--  CATASTRO — V1__baseline.sql
--
--  El esquema de Catastro Fiscal, generado del esquema de `sgtm` restringido a las
--  tablas que le pertenecen (ADR-0032 §1). 28 tablas.
--
--  Predio, ficha versionada, construcciones, otras instalaciones, titularidad, inquilinos,
--  el catalogo vial, las cuatro clases de ficha y el ARANCEL de terreno. 15 tablas
--  propias mas las 13 comunes.
--
--  El arancel esta aqui y no en `normativa` porque su fuente es CARTOGRAFICA: llega en un
--  GeoPackage de planos graficos por via del MEF -no en tabla de texto- y se llavea por
--  `via_id`, que es de catastro. Su importador ya vive aqui
--  (`catastro.aplicacion.ImportarArancel`).
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

CREATE TABLE actividad_economica (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    conductor character varying(200) NOT NULL,
    nombre_comercial character varying(200),
    ciiu character varying(10),
    area_ocupada area_m2,
    licencia_numero character varying(20),
    licencia_fecha date,
    anuncio_numero character varying(20),
    anuncio_fecha date,
    vigencia_desde date
);

CREATE TABLE arancel (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    via_id bigint NOT NULL,
    tramo character varying(80),
    valor_m2 monto_calc NOT NULL,
    documento_fuente character varying(200) NOT NULL,
    conjunto_id bigint NOT NULL
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

CREATE TABLE bien_comun (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    descripcion character varying(160) NOT NULL,
    area area_m2 NOT NULL,
    material_estructural character varying(20),
    estado_conservacion character varying(20),
    anio_construccion ejercicio
);

CREATE TABLE colindante_rural (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    orientacion character varying(10) NOT NULL,
    descripcion character varying(200) NOT NULL
);

CREATE TABLE construccion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    piso character varying(10) NOT NULL,
    area_construida area_m2 NOT NULL,
    anio_construccion ejercicio,
    material_estructural character varying(20),
    estado_conservacion character varying(20),
    categoria_muros character(1),
    categoria_techos character(1),
    categoria_pisos character(1),
    categoria_puertas character(1),
    categoria_revestim character(1),
    categoria_banios character(1),
    categoria_instalac character(1),
    porcentaje_construido porcentaje
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

CREATE TABLE ficha_catastral (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    predio_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    version integer NOT NULL,
    area_terreno area_m2 NOT NULL,
    uso character varying(60) NOT NULL,
    frontis numeric(8,2),
    condicion_propiedad character varying(40),
    tipo_edificacion character varying(40),
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    origen character varying(20) NOT NULL,
    documento_origen character varying(80) NOT NULL,
    observacion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    denominacion character varying(160),
    informacion_complementaria character varying(400)
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

CREATE TABLE inquilino (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    predio_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    uso character varying(60),
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_origen character varying(80) NOT NULL
);

CREATE TABLE manzana (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    sector_id bigint NOT NULL,
    codigo character varying(10) NOT NULL
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

CREATE TABLE otra_instalacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    descripcion character varying(160) NOT NULL,
    unidad_medida character varying(20) NOT NULL,
    cantidad numeric(12,2) NOT NULL,
    anio_construccion ejercicio,
    estado_conservacion character varying(20)
);

CREATE TABLE participacion_comun (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    predio_id bigint NOT NULL,
    porcentaje porcentaje NOT NULL
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

CREATE TABLE predio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo_ref_catastral cod_catastral NOT NULL,
    tipo character varying(10) NOT NULL,
    via_id bigint,
    numero_municipal character varying(20),
    direccion character varying(300) NOT NULL,
    sector_id bigint,
    manzana_id bigint,
    lote character varying(10),
    ubigeo character(6),
    estado character varying(20) DEFAULT 'ACTIVO'::character varying NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    geometria geography(MultiPolygon,4326),
    marco_oeste double precision GENERATED ALWAYS AS (st_xmin(((geometria)::geometry)::box3d)) STORED,
    marco_sur double precision GENERATED ALWAYS AS (st_ymin(((geometria)::geometry)::box3d)) STORED,
    marco_este double precision GENERATED ALWAYS AS (st_xmax(((geometria)::geometry)::box3d)) STORED,
    marco_norte double precision GENERATED ALWAYS AS (st_ymax(((geometria)::geometry)::box3d)) STORED
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

CREATE TABLE sector (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(10) NOT NULL,
    nombre character varying(160) NOT NULL,
    zona character varying(80),
    activo boolean DEFAULT true NOT NULL
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

CREATE TABLE tierra_rural (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    clasificacion character varying(60) NOT NULL,
    calidad_agrologica character varying(40),
    riego character varying(20) DEFAULT 'SECANO'::character varying NOT NULL,
    cantidad_hectareas numeric(12,4) NOT NULL,
    cantidad_hectareas_comun numeric(12,4)
);

CREATE TABLE titularidad (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    predio_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    condicion character varying(30) NOT NULL,
    porcentaje porcentaje NOT NULL,
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_origen character varying(80) NOT NULL
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

CREATE TABLE via (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(20) NOT NULL,
    tipo_via character varying(20) NOT NULL,
    nombre character varying(160) NOT NULL,
    ubigeo character(6),
    activa boolean DEFAULT true NOT NULL,
    nombre_busqueda text GENERATED ALWAYS AS (nombre_normalizado((nombre)::text)) STORED
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
ALTER TABLE actividad_economica ADD CONSTRAINT actividad_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE arancel ADD CONSTRAINT arancel_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE arancel ADD CONSTRAINT arancel_uq UNIQUE (municipalidad_id, conjunto_id, via_id, tramo);
ALTER TABLE arancel ADD CONSTRAINT arancel_valor_m2_check CHECK (((valor_m2)::numeric >= (0)::numeric));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_observacion_ck CHECK ((length(btrim((observacion)::text)) >= 5));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_operacion_check CHECK (((operacion)::text = ANY ((ARRAY['ALTA'::character varying, 'MODIFICACION'::character varying, 'BAJA'::character varying, 'ANULACION'::character varying, 'REVERSION'::character varying, 'PERMISO'::character varying, 'ACCESO'::character varying])::text[])));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_estado_conservacion_check CHECK (((estado_conservacion)::text = ANY ((ARRAY['MUY_BUENO'::character varying, 'BUENO'::character varying, 'REGULAR'::character varying, 'MALO'::character varying, 'RUINOSO'::character varying])::text[])));
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_material_estructural_check CHECK (((material_estructural)::text = ANY ((ARRAY['CONCRETO'::character varying, 'LADRILLO'::character varying, 'ADOBE'::character varying, 'MADERA'::character varying, 'QUINCHA'::character varying, 'OTRO'::character varying])::text[])));
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_orientacion_uq UNIQUE (municipalidad_id, ficha_id, orientacion);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_rural_orientacion_check CHECK (((orientacion)::text = ANY ((ARRAY['NORTE'::character varying, 'SUR'::character varying, 'ESTE'::character varying, 'OESTE'::character varying])::text[])));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_banios_check CHECK ((categoria_banios ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_instalac_check CHECK ((categoria_instalac ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_muros_check CHECK ((categoria_muros ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_pisos_check CHECK ((categoria_pisos ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_puertas_check CHECK ((categoria_puertas ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_revestim_check CHECK ((categoria_revestim ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_categoria_techos_check CHECK ((categoria_techos ~ '^[A-J]$'::text));
ALTER TABLE construccion ADD CONSTRAINT construccion_estado_conservacion_check CHECK (((estado_conservacion)::text = ANY ((ARRAY['MUY_BUENO'::character varying, 'BUENO'::character varying, 'REGULAR'::character varying, 'MALO'::character varying, 'RUINOSO'::character varying])::text[])));
ALTER TABLE construccion ADD CONSTRAINT construccion_material_estructural_check CHECK (((material_estructural)::text = ANY ((ARRAY['CONCRETO'::character varying, 'LADRILLO'::character varying, 'ADOBE'::character varying, 'MADERA'::character varying, 'QUINCHA'::character varying, 'OTRO'::character varying])::text[])));
ALTER TABLE construccion ADD CONSTRAINT construccion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_formato_check CHECK (((formato)::text = ANY ((ARRAY['PDF'::character varying, 'XLS'::character varying, 'RTF'::character varying])::text[])));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_reimpresiones_check CHECK ((reimpresiones >= 0));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_numero_uq UNIQUE (municipalidad_id, tipo, ejercicio, numero);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_catastral_origen_check CHECK (((origen)::text = ANY ((ARRAY['DECLARACION_JURADA'::character varying, 'FISCALIZACION'::character varying, 'RESOLUCION'::character varying, 'MIGRACION'::character varying])::text[])));
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_catastral_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['UNICA'::character varying, 'ECONOMICA'::character varying, 'BIENES_COMUNES'::character varying, 'RURAL'::character varying])::text[])));
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_version_uq UNIQUE (municipalidad_id, predio_id, tipo, version);
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_vigencias_no_se_pisan EXCLUDE USING gist (municipalidad_id WITH =, predio_id WITH =, tipo WITH =, daterange(vigencia_desde, COALESCE(vigencia_hasta, 'infinity'::date), '[]'::text) WITH &&) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE grupo ADD CONSTRAINT grupo_nombre_uq UNIQUE (municipalidad_id, nombre);
ALTER TABLE grupo ADD CONSTRAINT grupo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE grupo ADD CONSTRAINT grupo_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE inquilino ADD CONSTRAINT inquilino_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE manzana ADD CONSTRAINT manzana_codigo_uq UNIQUE (municipalidad_id, sector_id, codigo);
ALTER TABLE manzana ADD CONSTRAINT manzana_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_pk PRIMARY KEY (municipalidad_id, grupo_id, usuario_id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_pkey PRIMARY KEY (id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['DISTRITAL'::character varying, 'PROVINCIAL'::character varying])::text[])));
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_ubigeo_key UNIQUE (ubigeo);
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_cantidad_check CHECK ((cantidad > (0)::numeric));
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_unidad_uq UNIQUE (municipalidad_id, ficha_id, predio_id);
ALTER TABLE permiso ADD CONSTRAINT permiso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_sujeto_ck CHECK ((((grupo_id IS NOT NULL) AND (usuario_id IS NULL)) OR ((grupo_id IS NULL) AND (usuario_id IS NOT NULL))));
ALTER TABLE predio ADD CONSTRAINT predio_codigo_uq UNIQUE (municipalidad_id, codigo_ref_catastral);
ALTER TABLE predio ADD CONSTRAINT predio_estado_check CHECK (((estado)::text = ANY ((ARRAY['ACTIVO'::character varying, 'DADO_DE_BAJA'::character varying])::text[])));
ALTER TABLE predio ADD CONSTRAINT predio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['URBANO'::character varying, 'RUSTICO'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_pkey PRIMARY KEY (id);
ALTER TABLE respaldo ADD CONSTRAINT respaldo_resultado_check CHECK (((resultado)::text = ANY ((ARRAY['EN_CURSO'::character varying, 'EXITOSO'::character varying, 'FALLIDO'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_tamano_bytes_check CHECK (((tamano_bytes IS NULL) OR (tamano_bytes >= 0)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_terminado_ck CHECK ((((resultado)::text = 'EN_CURSO'::text) OR (fin IS NOT NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_completa_ck CHECK (((ultima_restauracion_verificada IS NULL) = (ultima_restauracion_verificada_por IS NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_exitosa_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((resultado)::text = 'EXITOSO'::text)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_posterior_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((fin IS NOT NULL) AND (ultima_restauracion_verificada >= fin))));
ALTER TABLE sector ADD CONSTRAINT sector_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE sector ADD CONSTRAINT sector_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE sesion ADD CONSTRAINT sesion_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE sesion ADD CONSTRAINT sesion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_cantidad_hectareas_check CHECK ((cantidad_hectareas > (0)::numeric));
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_cantidad_hectareas_comun_check CHECK ((cantidad_hectareas_comun >= (0)::numeric));
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_riego_check CHECK (((riego)::text = ANY ((ARRAY['BAJO_RIEGO'::character varying, 'SECANO'::character varying])::text[])));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_condicion_check CHECK (((condicion)::text = ANY ((ARRAY['PROPIETARIO_UNICO'::character varying, 'COPROPIETARIO'::character varying, 'CONYUGE'::character varying, 'POSEEDOR'::character varying, 'SUCESION'::character varying, 'USUFRUCTUARIO'::character varying])::text[])));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE titularidad ADD CONSTRAINT titularidad_unico_ck CHECK ((((condicion)::text <> 'PROPIETARIO_UNICO'::text) OR ((porcentaje)::numeric = (100)::numeric)));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_vigencias_no_se_pisan EXCLUDE USING gist (municipalidad_id WITH =, predio_id WITH =, contribuyente_id WITH =, daterange(vigencia_desde, COALESCE(vigencia_hasta, 'infinity'::date), '[]'::text) WITH &&) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE usuario ADD CONSTRAINT usuario_cuenta_uq UNIQUE (municipalidad_id, cuenta);
ALTER TABLE usuario ADD CONSTRAINT usuario_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_sujeto_uq UNIQUE (municipalidad_id, sujeto_oidc);
ALTER TABLE usuario ADD CONSTRAINT usuario_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE via ADD CONSTRAINT via_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE via ADD CONSTRAINT via_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acceso ADD CONSTRAINT acceso_modulo_fk FOREIGN KEY (municipalidad_id, modulo_id) REFERENCES modulo_sistema(municipalidad_id, id);
ALTER TABLE actividad_economica ADD CONSTRAINT actividad_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
--  [CRUZA LA FRONTERA] arancel.arancel_conjunto_fk: FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id) NOT VALID
ALTER TABLE arancel ADD CONSTRAINT arancel_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE arancel ADD CONSTRAINT arancel_via_fk FOREIGN KEY (municipalidad_id, via_id) REFERENCES via(municipalidad_id, id);
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE construccion ADD CONSTRAINT construccion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE grupo ADD CONSTRAINT grupo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
--  [CRUZA LA FRONTERA] inquilino.inquilino_contribuyente_fk: FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
ALTER TABLE inquilino ADD CONSTRAINT inquilino_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE manzana ADD CONSTRAINT manzana_sector_fk FOREIGN KEY (municipalidad_id, sector_id) REFERENCES sector(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_sistema_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_acceso_fk FOREIGN KEY (municipalidad_id, acceso_id) REFERENCES acceso(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_manzana_fk FOREIGN KEY (municipalidad_id, manzana_id) REFERENCES manzana(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE predio ADD CONSTRAINT predio_sector_fk FOREIGN KEY (municipalidad_id, sector_id) REFERENCES sector(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_via_fk FOREIGN KEY (municipalidad_id, via_id) REFERENCES via(municipalidad_id, id);
ALTER TABLE sector ADD CONSTRAINT sector_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE sesion ADD CONSTRAINT sesion_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
--  [CRUZA LA FRONTERA] titularidad.titularidad_contribuyente_fk: FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
ALTER TABLE titularidad ADD CONSTRAINT titularidad_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE via ADD CONSTRAINT via_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);

-- ==========================================================================
--  5. INDICES
--  Empezando por municipalidad_id. Los de una tabla particionada se propagan
--  solos a sus particiones, asi que aqui no se repiten.
-- ==========================================================================

CREATE INDEX acceso_modulo_ix ON public.acceso USING btree (municipalidad_id, modulo_id, tipo);
CREATE INDEX actividad_ficha_ix ON public.actividad_economica USING btree (municipalidad_id, ficha_id);
CREATE INDEX actividad_licencia_ix ON public.actividad_economica USING btree (municipalidad_id, licencia_numero);
CREATE UNIQUE INDEX arancel_sin_tramo_uq ON public.arancel USING btree (municipalidad_id, conjunto_id, via_id) WHERE (tramo IS NULL);
CREATE INDEX auditoria_tabla_ix ON public.auditoria USING btree (municipalidad_id, tabla, clave);
CREATE INDEX auditoria_usuario_ix ON public.auditoria USING btree (municipalidad_id, usuario_id, fecha);
CREATE INDEX bien_comun_ficha_ix ON public.bien_comun USING btree (municipalidad_id, ficha_id);
CREATE INDEX colindante_ficha_ix ON public.colindante_rural USING btree (municipalidad_id, ficha_id);
CREATE INDEX construccion_ficha_ix ON public.construccion USING btree (municipalidad_id, ficha_id);
CREATE INDEX documento_referencia_ix ON public.documento_emitido USING btree (municipalidad_id, tipo, referencia);
CREATE INDEX ficha_predio_ix ON public.ficha_catastral USING btree (municipalidad_id, predio_id, tipo);
CREATE INDEX ficha_vigencia_ix ON public.ficha_catastral USING btree (municipalidad_id, vigencia_desde, tipo) WHERE (vigencia_hasta IS NULL);
CREATE UNIQUE INDEX ficha_vigente_uq ON public.ficha_catastral USING btree (municipalidad_id, predio_id, tipo) WHERE (vigencia_hasta IS NULL);
CREATE INDEX miembro_usuario_ix ON public.miembro USING btree (municipalidad_id, usuario_id);
CREATE INDEX participacion_ficha_ix ON public.participacion_comun USING btree (municipalidad_id, ficha_id);
CREATE INDEX participacion_predio_ix ON public.participacion_comun USING btree (municipalidad_id, predio_id);
CREATE INDEX permiso_acceso_ix ON public.permiso USING btree (municipalidad_id, acceso_id);
CREATE UNIQUE INDEX permiso_grupo_uq ON public.permiso USING btree (municipalidad_id, acceso_id, grupo_id) WHERE (grupo_id IS NOT NULL);
CREATE UNIQUE INDEX permiso_usuario_uq ON public.permiso USING btree (municipalidad_id, acceso_id, usuario_id) WHERE (usuario_id IS NOT NULL);
CREATE INDEX predio_codigo_prefijo_ix ON public.predio USING btree (municipalidad_id, codigo_ref_catastral text_pattern_ops);
CREATE INDEX predio_direccion_ix ON public.predio USING btree (municipalidad_id, direccion);
CREATE INDEX predio_sector_ix ON public.predio USING btree (municipalidad_id, sector_id, manzana_id);
CREATE INDEX predio_geometria_gix ON public.predio USING gist (geometria) WHERE (geometria IS NOT NULL);
CREATE INDEX predio_marco_ix ON public.predio USING btree (municipalidad_id, marco_oeste, marco_sur, marco_este, marco_norte) WHERE (geometria IS NOT NULL);
CREATE INDEX respaldo_inicio_ix ON public.respaldo USING btree (inicio DESC);
CREATE INDEX sesion_abierta_ix ON public.sesion USING btree (municipalidad_id, usuario_id) WHERE (fin IS NULL);
CREATE INDEX tierra_ficha_ix ON public.tierra_rural USING btree (municipalidad_id, ficha_id);
CREATE INDEX titularidad_contribuyente_ix ON public.titularidad USING btree (municipalidad_id, contribuyente_id) WHERE (vigencia_hasta IS NULL);
CREATE INDEX titularidad_predio_ix ON public.titularidad USING btree (municipalidad_id, predio_id, vigencia_desde);
CREATE INDEX titularidad_predio_vigente_ix ON public.titularidad USING btree (municipalidad_id, predio_id, porcentaje DESC) WHERE (vigencia_hasta IS NULL);
CREATE INDEX via_codigo_prefijo_ix ON public.via USING btree (municipalidad_id, codigo text_pattern_ops);
CREATE INDEX via_nombre_busqueda_ix ON public.via USING btree (municipalidad_id, nombre_busqueda text_pattern_ops);

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
ALTER TABLE actividad_economica ENABLE ROW LEVEL SECURITY;
ALTER TABLE actividad_economica FORCE ROW LEVEL SECURITY;
CREATE POLICY actividad_por_tenant ON actividad_economica FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE arancel ENABLE ROW LEVEL SECURITY;
ALTER TABLE arancel FORCE ROW LEVEL SECURITY;
CREATE POLICY arancel_tenant ON arancel FOR ALL TO PUBLIC
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
ALTER TABLE bien_comun ENABLE ROW LEVEL SECURITY;
ALTER TABLE bien_comun FORCE ROW LEVEL SECURITY;
CREATE POLICY bien_comun_por_tenant ON bien_comun FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE colindante_rural ENABLE ROW LEVEL SECURITY;
ALTER TABLE colindante_rural FORCE ROW LEVEL SECURITY;
CREATE POLICY colindante_por_tenant ON colindante_rural FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE construccion ENABLE ROW LEVEL SECURITY;
ALTER TABLE construccion FORCE ROW LEVEL SECURITY;
CREATE POLICY construccion_tenant ON construccion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE documento_emitido ENABLE ROW LEVEL SECURITY;
ALTER TABLE documento_emitido FORCE ROW LEVEL SECURITY;
CREATE POLICY documento_por_tenant ON documento_emitido FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE ficha_catastral ENABLE ROW LEVEL SECURITY;
ALTER TABLE ficha_catastral FORCE ROW LEVEL SECURITY;
CREATE POLICY ficha_catastral_tenant ON ficha_catastral FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE grupo ENABLE ROW LEVEL SECURITY;
ALTER TABLE grupo FORCE ROW LEVEL SECURITY;
CREATE POLICY grupo_tenant ON grupo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE inquilino ENABLE ROW LEVEL SECURITY;
ALTER TABLE inquilino FORCE ROW LEVEL SECURITY;
CREATE POLICY inquilino_tenant ON inquilino FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE manzana ENABLE ROW LEVEL SECURITY;
ALTER TABLE manzana FORCE ROW LEVEL SECURITY;
CREATE POLICY manzana_tenant ON manzana FOR ALL TO PUBLIC
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
ALTER TABLE otra_instalacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE otra_instalacion FORCE ROW LEVEL SECURITY;
CREATE POLICY otra_instalacion_tenant ON otra_instalacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE participacion_comun ENABLE ROW LEVEL SECURITY;
ALTER TABLE participacion_comun FORCE ROW LEVEL SECURITY;
CREATE POLICY participacion_por_tenant ON participacion_comun FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE permiso ENABLE ROW LEVEL SECURITY;
ALTER TABLE permiso FORCE ROW LEVEL SECURITY;
CREATE POLICY permiso_tenant ON permiso FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE predio ENABLE ROW LEVEL SECURITY;
ALTER TABLE predio FORCE ROW LEVEL SECURITY;
CREATE POLICY predio_tenant ON predio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE respaldo ENABLE ROW LEVEL SECURITY;
ALTER TABLE respaldo FORCE ROW LEVEL SECURITY;
CREATE POLICY respaldo_escritura ON respaldo FOR ALL TO sgtm_owner
    USING (true)
    WITH CHECK (true);
CREATE POLICY respaldo_lectura ON respaldo FOR SELECT TO PUBLIC
    USING (true);
ALTER TABLE sector ENABLE ROW LEVEL SECURITY;
ALTER TABLE sector FORCE ROW LEVEL SECURITY;
CREATE POLICY sector_tenant ON sector FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE sesion ENABLE ROW LEVEL SECURITY;
ALTER TABLE sesion FORCE ROW LEVEL SECURITY;
CREATE POLICY sesion_tenant ON sesion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE tierra_rural ENABLE ROW LEVEL SECURITY;
ALTER TABLE tierra_rural FORCE ROW LEVEL SECURITY;
CREATE POLICY tierra_por_tenant ON tierra_rural FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE titularidad ENABLE ROW LEVEL SECURITY;
ALTER TABLE titularidad FORCE ROW LEVEL SECURITY;
CREATE POLICY titularidad_tenant ON titularidad FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario FORCE ROW LEVEL SECURITY;
CREATE POLICY usuario_tenant ON usuario FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE via ENABLE ROW LEVEL SECURITY;
ALTER TABLE via FORCE ROW LEVEL SECURITY;
CREATE POLICY via_tenant ON via FOR ALL TO PUBLIC
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
GRANT INSERT, SELECT ON actividad_economica TO sgtm_app;
GRANT SELECT ON actividad_economica TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON arancel TO sgtm_app;
GRANT SELECT ON arancel TO sgtm_readonly;
GRANT INSERT, SELECT ON auditoria TO sgtm_app;
GRANT SELECT ON auditoria TO sgtm_readonly;
GRANT INSERT, SELECT ON bien_comun TO sgtm_app;
GRANT SELECT ON bien_comun TO sgtm_readonly;
GRANT INSERT, SELECT ON colindante_rural TO sgtm_app;
GRANT SELECT ON colindante_rural TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON construccion TO sgtm_app;
GRANT SELECT ON construccion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON documento_emitido TO sgtm_app;
GRANT SELECT ON documento_emitido TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON ficha_catastral TO sgtm_app;
GRANT SELECT ON ficha_catastral TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON grupo TO sgtm_app;
GRANT SELECT ON grupo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON inquilino TO sgtm_app;
GRANT SELECT ON inquilino TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON manzana TO sgtm_app;
GRANT SELECT ON manzana TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON miembro TO sgtm_app;
GRANT SELECT ON miembro TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON modulo_sistema TO sgtm_app;
GRANT SELECT ON modulo_sistema TO sgtm_readonly;
GRANT SELECT ON municipalidad TO sgtm_app;
GRANT SELECT ON municipalidad TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON otra_instalacion TO sgtm_app;
GRANT SELECT ON otra_instalacion TO sgtm_readonly;
GRANT INSERT, SELECT ON participacion_comun TO sgtm_app;
GRANT SELECT ON participacion_comun TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON permiso TO sgtm_app;
GRANT SELECT ON permiso TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON predio TO sgtm_app;
GRANT SELECT ON predio TO sgtm_readonly;
GRANT SELECT ON respaldo TO sgtm_app;
GRANT SELECT ON respaldo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sector TO sgtm_app;
GRANT SELECT ON sector TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sesion TO sgtm_app;
GRANT SELECT ON sesion TO sgtm_readonly;
GRANT INSERT, SELECT ON tierra_rural TO sgtm_app;
GRANT SELECT ON tierra_rural TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON titularidad TO sgtm_app;
GRANT SELECT ON titularidad TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON usuario TO sgtm_app;
GRANT SELECT ON usuario TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON via TO sgtm_app;
GRANT SELECT ON via TO sgtm_readonly;

-- ==========================================================================
--  8. DISPARADORES DE INMUTABILIDAD Y DE INVARIANTE
--  Con sus funciones. Un disparador sin su funcion no protege nada.
-- ==========================================================================

CREATE TRIGGER arancel_de_conjunto_sellado_inmutable BEFORE INSERT OR UPDATE ON public.arancel FOR EACH ROW EXECUTE FUNCTION valuacion_de_conjunto_sellado_es_inmutable();
CREATE TRIGGER documento_inmutable_trg BEFORE UPDATE ON public.documento_emitido FOR EACH ROW EXECUTE FUNCTION documento_solo_cuenta_reimpresiones();
CREATE CONSTRAINT TRIGGER participacion_no_excede_trg AFTER INSERT OR DELETE OR UPDATE ON public.participacion_comun DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verificar_participacion_no_excede();
CREATE CONSTRAINT TRIGGER titularidad_no_excede_trg AFTER INSERT OR DELETE OR UPDATE ON public.titularidad DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verificar_titularidad_no_excede();

-- ==========================================================================
--  9. COMENTARIOS
--  El por que de una columna, que es lo primero que se pierde.
-- ==========================================================================

COMMENT ON TABLE actividad_economica IS 'Actividad economica declarada en la unidad catastral (RF-002). Referencia la licencia por numero, no por clave ajena: catastro no depende de licencias.';
COMMENT ON TABLE bien_comun IS 'Areas comunes de una edificacion (RF-003), cuyo valor se distribuye entre las unidades.';
COMMENT ON TABLE colindante_rural IS 'Predios colindantes de un predio rustico (RF-004), por orientacion.';
COMMENT ON COLUMN construccion.categoria_muros IS 'Categoria del formulario de ficha catastral del manual, no del cuadro de la norma. La ficha declara siete caracteristicas; el cuadro publica tres partidas. Que se parezcan no las hace la misma cosa (V59).';
COMMENT ON TABLE documento_emitido IS 'Documentos emitidos con los datos que los generaron, para reimprimirlos identicos (RF-132).';
COMMENT ON TABLE municipalidad IS 'Registro de tenants. No es tabla de tenant: la aplicacion la lee entera porque los procesos masivos iteran municipalidad por municipalidad. Solo sgtm_owner escribe.';
COMMENT ON COLUMN municipalidad.es_demostracion IS 'Instalacion de demostracion: todo documento emitido bajo este tenant sale marcado, en los tres formatos. Lo lee la capa de documentos, no cada emisor. Solo sgtm_owner la escribe, como el alta de la municipalidad.';
COMMENT ON TABLE participacion_comun IS 'Porcentaje de participacion de cada unidad en los bienes comunes de la edificacion.';
COMMENT ON TABLE respaldo IS 'Estado de las copias de seguridad (RF-126). La aplicacion solo lee: quien hace la copia y escribe aqui es el proceso de despliegue, como sgtm_owner.';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada IS 'Instante en que se comprobo, restaurandola de verdad, que esta copia se puede restaurar (RNF-079). NULO significa «nunca se probo», nunca «hoy».';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada_por IS 'Que proceso lo comprobo: el simulacro de restauracion y el ambiente contra el que corrio. No es un usuario de la aplicacion: la aplicacion no restaura.';
COMMENT ON TABLE tierra_rural IS 'Grupos de tierra de un predio rustico (RF-004), en hectareas, con su clasificacion y riego.';
COMMENT ON COLUMN via.nombre_busqueda IS 'El nombre en minusculas, sin tildes y sin espacios repetidos (V11). Existe para que la busqueda por prefijo compare una columna desnuda: envuelta en la funcion, la condicion no es leakproof y no llega al indice bajo RLS (#565).';
