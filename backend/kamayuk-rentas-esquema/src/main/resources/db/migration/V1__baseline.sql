-- ============================================================================
--  RENTAS — V1__baseline.sql
--
--  El esquema de Rentas, generado del esquema de `sgtm` restringido a las
--  tablas que le pertenecen (ADR-0032 §1). 132 tablas.
--
--  EL ESQUEMA COMPLETO DE HOY, las 132 tablas. En la primera etapa `rentas` ES el
--  monolito modular con los doce contextos dentro y las necesita todas. Cada extraccion
--  posterior le anade SU migracion de baja: `catastro` se lleva 15 tablas, `normativa` 6 y
--  `caja` 10, y las 13 comunes se quedan en los cuatro.
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

CREATE TABLE acta_fiscalizacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    programa_id bigint NOT NULL,
    version integer NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    fecha_visita date NOT NULL,
    fiscalizador character varying(60) NOT NULL,
    hallazgo character varying(20),
    area_hallada area_m2,
    detalle character varying(1000),
    estado character varying(15) DEFAULT 'ABIERTA'::character varying NOT NULL,
    fecha_transferencia timestamp with time zone,
    usuario_transferencia character varying(60),
    observacion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    ficha_id bigint,
    uso_hallado character varying(60)
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

CREATE TABLE acto_coactivo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    expediente_id bigint NOT NULL,
    tipo character varying(30) NOT NULL,
    numero character varying(40) NOT NULL,
    fecha date NOT NULL,
    descripcion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    documento_id bigint NOT NULL,
    medida character varying(20),
    rec1_notificacion_id bigint,
    rec1_exigible_desde date,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE anuncio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    tipo character varying(40) NOT NULL,
    ubicacion character varying(300) NOT NULL,
    area area_m2 NOT NULL,
    cantidad smallint DEFAULT 1 NOT NULL,
    fecha_autorizacion date NOT NULL,
    vigencia_hasta date,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    licencia_id bigint,
    clase character varying(20) NOT NULL,
    emplazamiento character varying(30),
    forma character varying(30),
    denominacion character varying(240),
    lados smallint DEFAULT 1 NOT NULL,
    expediente character varying(20),
    fecha_expediente date,
    clave_idempotencia character varying(64),
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE anuncio_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE anuncio_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    anuncio_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    fecha date NOT NULL,
    ejercicio ejercicio,
    referencia_cargo character varying(40),
    tasa dinero,
    vigencia_hasta date,
    motivo character varying(500),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
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

CREATE TABLE beneficio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    tipo character varying(40) NOT NULL,
    tributo character varying(20) NOT NULL,
    clase character varying(20) NOT NULL,
    porcentaje alicuota,
    monto dinero,
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    base_legal character varying(200) NOT NULL,
    documento_origen character varying(80) NOT NULL,
    observacion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL
);

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

CREATE TABLE caja (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(10) NOT NULL,
    nombre character varying(80) NOT NULL,
    area_id bigint,
    activa boolean DEFAULT true NOT NULL,
    serie character varying(5) NOT NULL
);

CREATE TABLE certificado (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    tipo character varying(30) NOT NULL,
    predio_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    codigo_predial cod_catastral NOT NULL,
    direccion character varying(300) NOT NULL,
    expediente character varying(20),
    fecha_emision date NOT NULL,
    vigencia_hasta date NOT NULL,
    recibo_id bigint NOT NULL,
    derecho dinero NOT NULL,
    derecho_a date NOT NULL,
    documento_id bigint NOT NULL,
    documento_numero character varying(40) NOT NULL,
    zonificacion character varying(60),
    altura_maxima character varying(40),
    area_libre_minima character varying(40),
    retiro_municipal character varying(40),
    coeficiente_edificacion character varying(40),
    clave_idempotencia character varying(64),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE certificado_correlativo (
    municipalidad_id bigint NOT NULL,
    tipo character varying(30) NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
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

CREATE TABLE ciiu (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(10) NOT NULL,
    descripcion character varying(300) NOT NULL,
    extendido boolean DEFAULT false NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    seccion character(1),
    riesgo_itse character varying(10),
    zonificacion_compatible character varying(120),
    requiere_sectorial boolean DEFAULT false NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE codigo_infraccion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    familia character varying(15) NOT NULL,
    codigo character varying(20) NOT NULL,
    descripcion character varying(500) NOT NULL,
    porcentaje_uit alicuota NOT NULL,
    medida character varying(160),
    puntos smallint,
    base_legal character varying(200) NOT NULL,
    vigencia_desde date NOT NULL,
    vigencia_hasta date
);

CREATE TABLE colindante_rural (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ficha_id bigint NOT NULL,
    orientacion character varying(10) NOT NULL,
    descripcion character varying(200) NOT NULL
);

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

CREATE TABLE constancia_libre (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(40) NOT NULL,
    documento_id bigint NOT NULL,
    placa character varying(10) NOT NULL,
    vehiculo_id bigint,
    solicitante_id bigint,
    verificada_al date NOT NULL,
    fecha_emision date NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
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

CREATE TABLE contacto (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    valor character varying(200) NOT NULL,
    nombre character varying(240),
    documento character varying(20),
    observacion character varying(300),
    vigente boolean DEFAULT true NOT NULL
);

CREATE TABLE contribuyente (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo_contribuyente character varying(20) NOT NULL,
    tipo_documento character varying(10) NOT NULL,
    numero_documento character varying(20) NOT NULL,
    tipo_persona character varying(20) NOT NULL,
    nombre_razon_social character varying(240) NOT NULL,
    condicion_especial character varying(30),
    fecha_nacimiento date,
    estado_civil character varying(20),
    conyuge_id bigint,
    activo boolean DEFAULT true NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    usuario_registro character varying(60) NOT NULL
);

CREATE TABLE convenio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    contribuyente_id bigint NOT NULL,
    tipo character varying(12) NOT NULL,
    fecha date NOT NULL,
    monto_total dinero NOT NULL,
    cuota_inicial dinero NOT NULL,
    numero_cuotas smallint NOT NULL,
    resolucion character varying(40),
    fecha_corte date NOT NULL,
    conjunto_id bigint NOT NULL,
    interes_mensual alicuota NOT NULL,
    porcentaje_inicial alicuota NOT NULL,
    maximo_cuotas smallint NOT NULL,
    tipo_garantia character varying(15),
    detalle_garantia character varying(500),
    convenio_origen_id bigint,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    clave_idempotencia character varying(64)
);

CREATE TABLE convenio_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE convenio_cuota (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    convenio_id bigint NOT NULL,
    numero smallint NOT NULL,
    vencimiento date NOT NULL,
    monto dinero NOT NULL,
    capital dinero DEFAULT 0 NOT NULL,
    interes dinero DEFAULT 0 NOT NULL,
    gasto dinero DEFAULT 0 NOT NULL
);

CREATE TABLE convenio_deuda (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    convenio_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    periodo smallint DEFAULT 0 NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    fase_origen character varying(10) NOT NULL,
    insoluto dinero DEFAULT 0 NOT NULL,
    reajuste dinero DEFAULT 0 NOT NULL,
    interes dinero DEFAULT 0 NOT NULL,
    gasto dinero DEFAULT 0 NOT NULL,
    monto dinero NOT NULL,
    fecha_corte date NOT NULL
);

CREATE TABLE convenio_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    convenio_id bigint NOT NULL,
    tipo character varying(14) NOT NULL,
    fecha date NOT NULL,
    recibo_id bigint,
    cuota smallint,
    motivo character varying(80),
    autorizado_por character varying(80),
    documento_autorizacion character varying(40),
    importe dinero NOT NULL,
    asientos integer DEFAULT 0 NOT NULL,
    convenio_nuevo_id bigint,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL,
    clave_idempotencia character varying(64)
);

CREATE TABLE corrida_predial (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    ejercicio ejercicio NOT NULL,
    alcance character varying(20) NOT NULL,
    sector character varying(10),
    modalidad character varying(20) NOT NULL,
    simulacion boolean NOT NULL,
    conjunto character varying(60) NOT NULL,
    leidos integer NOT NULL,
    determinados integer NOT NULL,
    monto_emitido dinero NOT NULL,
    fecha_calculo date NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL,
    codigo_desde character varying(20),
    codigo_hasta character varying(20)
);

CREATE TABLE corrida_predial_observado (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    corrida_id bigint NOT NULL,
    cod_contribuyente character varying(20) NOT NULL,
    nombre character varying(200) NOT NULL,
    motivo character varying(500) NOT NULL
);

CREATE TABLE costa_obligacion (
    municipalidad_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    expediente_id bigint NOT NULL
);

CREATE TABLE costa_procesal (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    expediente_id bigint NOT NULL,
    concepto character varying(160) NOT NULL,
    monto dinero NOT NULL,
    fecha date NOT NULL,
    arancel_fuente character varying(200) NOT NULL,
    liquidacion_id bigint NOT NULL,
    acto_id bigint NOT NULL,
    acto_tipo character varying(20) NOT NULL,
    tributo character varying(20) NOT NULL,
    arancel_conjunto_id bigint NOT NULL
);

CREATE TABLE cuenta_corriente_asiento (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    concepto character varying(20) NOT NULL,
    tipo character(6) NOT NULL,
    fase character varying(12) DEFAULT 'ORDINARIA'::character varying NOT NULL,
    periodo smallint,
    predio_id bigint,
    vehiculo_id bigint,
    referencia_externa character varying(40),
    monto dinero NOT NULL,
    fecha_valor date NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    documento_origen character varying(80) NOT NULL,
    asiento_reversado_id bigint,
    usuario_id character varying(60) NOT NULL,
    motivo character varying(500),
    acto character varying(20),
    unidad_de_titular_anterior boolean DEFAULT false NOT NULL,
    causal character varying(40)
) PARTITION BY LIST (ejercicio);

CREATE TABLE declaracion_jurada (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    contribuyente_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    fecha_presentacion date NOT NULL,
    fecha_limite date NOT NULL,
    fuera_de_plazo boolean DEFAULT false NOT NULL,
    estado character varying(20) DEFAULT 'PRESENTADA'::character varying NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    ficha_catastral_id bigint,
    dj_rectifica_id bigint
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

CREATE TABLE descargo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    papeleta_id bigint NOT NULL,
    fecha date NOT NULL,
    sustento character varying(1000) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    numero_expediente character varying(20) NOT NULL,
    tipo_recurso character varying(20) NOT NULL,
    presentado_hasta date NOT NULL,
    conjunto_id bigint NOT NULL,
    en_plazo boolean NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE determinacion (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    tributo character varying(20) NOT NULL,
    periodo smallint,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    conjunto_id bigint NOT NULL,
    base_imponible dinero NOT NULL,
    monto_determinado dinero NOT NULL,
    reglas_aplicadas character varying(200)[] NOT NULL,
    origen character varying(20) DEFAULT 'ORDINARIA'::character varying NOT NULL,
    estado character varying(15) DEFAULT 'BORRADOR'::character varying NOT NULL,
    fecha_calculo timestamp with time zone DEFAULT now() NOT NULL,
    usuario_calculo character varying(60) NOT NULL
) PARTITION BY LIST (ejercicio);

CREATE TABLE determinacion_arbitrio (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    servicio character varying(20) NOT NULL,
    periodo smallint NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint NOT NULL,
    conjunto_id bigint NOT NULL,
    monto dinero NOT NULL,
    parametro_aplicado character varying(120) NOT NULL,
    fecha_calculo date NOT NULL,
    usuario_calculo character varying(60) NOT NULL
) PARTITION BY LIST (ejercicio);

CREATE TABLE determinacion_predio_detalle (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    determinacion_id bigint NOT NULL,
    predio_id bigint NOT NULL,
    autovaluo dinero NOT NULL,
    porcentaje_propiedad porcentaje NOT NULL,
    base_imponible_predio dinero NOT NULL,
    valuo_exonerado dinero NOT NULL
) PARTITION BY LIST (ejercicio);

CREATE TABLE dj_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
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

CREATE TABLE domicilio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    tipo character varying(10) NOT NULL,
    direccion character varying(300) NOT NULL,
    referencia character varying(200),
    ubigeo character(6),
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_origen character varying(80) NOT NULL
);

CREATE TABLE edificacion_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE edificacion_estructura (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    version smallint NOT NULL,
    piso smallint NOT NULL,
    partida character varying(20) NOT NULL,
    categoria character(1) NOT NULL,
    area area_m2 NOT NULL
);

CREATE TABLE edificacion_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    fecha date NOT NULL,
    numero_licencia character varying(20),
    motivo character varying(500),
    recibo_id bigint,
    documento_id bigint NOT NULL,
    documento_numero character varying(40) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE edificacion_profesional (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    version smallint NOT NULL,
    tipo character varying(30) NOT NULL,
    nombre character varying(200) NOT NULL,
    colegio character varying(10),
    colegiatura character varying(20)
);

CREATE TABLE edificacion_proyecto (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    version smallint NOT NULL,
    uso character varying(40) NOT NULL,
    numero_pisos smallint NOT NULL,
    area_techada area_m2 NOT NULL,
    area_libre area_m2,
    estacionamientos smallint,
    plazo_meses smallint,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE edificacion_requisito (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    version smallint NOT NULL,
    requisito character varying(80) NOT NULL,
    presentado boolean NOT NULL,
    folios smallint
);

CREATE TABLE edificacion_terreno (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    fue_id bigint NOT NULL,
    version smallint NOT NULL,
    cod_catastral character varying(20),
    direccion character varying(300) NOT NULL,
    manzana character varying(10),
    lote character varying(10),
    area_terreno area_m2 NOT NULL,
    zonificacion character varying(60),
    partida_registral character varying(40),
    frente numeric(8,2),
    fondo numeric(8,2),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE edificacion_vigencia (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    licencia_id bigint NOT NULL,
    movimiento_id bigint NOT NULL,
    orden smallint NOT NULL,
    desde date NOT NULL,
    hasta date NOT NULL
);

CREATE TABLE espectaculo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    denominacion character varying(200) NOT NULL,
    tipo character varying(60) NOT NULL,
    lugar character varying(200) NOT NULL,
    fecha_evento date NOT NULL,
    aforo integer,
    valor_entrada dinero,
    base_imponible dinero,
    estado character varying(20) DEFAULT 'REGISTRADO'::character varying NOT NULL,
    usuario_registro character varying(60) NOT NULL
);

CREATE TABLE expediente_coactivo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    contribuyente_id bigint NOT NULL,
    ejecutor character varying(60) NOT NULL,
    auxiliar character varying(60),
    fecha_apertura date NOT NULL,
    direccion_referencial character varying(300),
    observacion character varying(500) NOT NULL,
    ejercicio ejercicio NOT NULL,
    correlativo bigint NOT NULL,
    asunto character varying(300),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE expediente_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE expediente_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    expediente_id bigint NOT NULL,
    tipo character varying(12) NOT NULL,
    estado character varying(20),
    direccion_referencial character varying(300),
    fecha date NOT NULL,
    motivo character varying(200) NOT NULL,
    documento_fecha date,
    documento_numero character varying(40),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE expediente_valor (
    municipalidad_id bigint NOT NULL,
    expediente_id bigint NOT NULL,
    valor_id bigint NOT NULL,
    fecha_importacion date NOT NULL
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

CREATE TABLE internamiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    papeleta_id bigint,
    vehiculo_id bigint,
    placa character varying(10) NOT NULL,
    deposito character varying(160) NOT NULL,
    fecha_ingreso timestamp with time zone NOT NULL,
    acta character varying(40) NOT NULL,
    observacion character varying(500) NOT NULL,
    documento_id bigint NOT NULL,
    tasa_custodia character varying(20) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE internamiento_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    internamiento_id bigint NOT NULL,
    tipo character varying(12) NOT NULL,
    fecha date NOT NULL,
    acta character varying(40) NOT NULL,
    documento_id bigint NOT NULL,
    recibo_custodia character varying(20),
    dias_custodia integer,
    persona_retira character varying(120),
    documento_retira character varying(20),
    soat_acreditado boolean DEFAULT false NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE licencia_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE licencia_duplicado (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    licencia_id bigint NOT NULL,
    numero smallint NOT NULL,
    fecha date NOT NULL,
    motivo character varying(500) NOT NULL,
    recibo_id bigint NOT NULL,
    documento_id bigint NOT NULL,
    reimpresion integer NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE licencia_edificacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    modalidad character varying(10) NOT NULL,
    tipo_obra character varying(40) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    expediente character varying(20) NOT NULL,
    fecha_declaracion date NOT NULL,
    tipo_tramite character varying(30) NOT NULL,
    revision character varying(20),
    expediente_anterior character varying(20),
    licencia_origen_id bigint,
    solicitante_propietario boolean NOT NULL,
    representante_documento character varying(20),
    representante_nombre character varying(200),
    representante_partida character varying(40),
    representante_vigencia_poder date,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE licencia_funcionamiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    nombre_comercial character varying(200) NOT NULL,
    direccion character varying(300) NOT NULL,
    area_solicitada area_m2 NOT NULL,
    tipo_licencia character varying(30) NOT NULL,
    zonificacion character varying(60),
    aforo integer,
    fecha_emision date NOT NULL,
    vigencia_hasta date,
    recibo_id bigint NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    documento_id bigint NOT NULL,
    ficha_id bigint,
    expediente character varying(20),
    fecha_expediente date,
    fecha_registro timestamp with time zone NOT NULL
);

CREATE TABLE licencia_giro (
    municipalidad_id bigint NOT NULL,
    licencia_id bigint NOT NULL,
    ciiu_id bigint NOT NULL,
    principal boolean DEFAULT false NOT NULL,
    activo boolean DEFAULT true NOT NULL
);

CREATE TABLE licencia_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    licencia_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    fecha date NOT NULL,
    motivo character varying(500),
    documento_id bigint NOT NULL,
    documento_numero character varying(40) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE liquidacion_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE liquidacion_costas (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    correlativo bigint NOT NULL,
    expediente_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    fecha date NOT NULL,
    conjunto_id bigint NOT NULL,
    total dinero NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE liquidacion_costas_correlativo (
    municipalidad_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE liquidacion_detalle (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    liquidacion_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    conjunto_id bigint NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    condicion character varying(15) NOT NULL,
    area_declarada area_m2,
    area_hallada area_m2,
    uso_declarado character varying(60),
    uso_hallado character varying(60),
    base_declarada dinero,
    base_hallada dinero,
    insoluto_omitido dinero,
    multa_tributaria dinero
);

CREATE TABLE liquidacion_fiscalizacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(40) NOT NULL,
    ejercicio ejercicio NOT NULL,
    correlativo bigint NOT NULL,
    acta_id bigint NOT NULL,
    version integer NOT NULL,
    liquidacion_anterior_id bigint,
    ejercicio_desde ejercicio NOT NULL,
    ejercicio_hasta ejercicio NOT NULL,
    tipo_fiscalizacion character varying(15) NOT NULL,
    motivo_determinante character varying(1000) NOT NULL,
    fecha date NOT NULL,
    numero_notificacion character varying(40),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE liquidacion_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    liquidacion_id bigint NOT NULL,
    tipo character varying(15) NOT NULL,
    estado character varying(15) NOT NULL,
    fecha date NOT NULL,
    motivo character varying(300) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
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

CREATE TABLE notificacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    objeto character varying(20) NOT NULL,
    objeto_id bigint NOT NULL,
    numero character varying(20) NOT NULL,
    fecha_notificacion date NOT NULL,
    modalidad character varying(30) NOT NULL,
    resultado character varying(20) NOT NULL,
    notificador character varying(60),
    direccion character varying(300),
    acuse character varying(80),
    observacion character varying(500) NOT NULL,
    intento smallint DEFAULT 1 NOT NULL,
    receptor character varying(120),
    documento_receptor character varying(20),
    vinculo character varying(40),
    exigible_desde date,
    conjunto_id bigint,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE notificacion_administrativa (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(20) NOT NULL,
    fecha date NOT NULL,
    contribuyente_id bigint,
    predio_id bigint,
    direccion character varying(300) NOT NULL,
    motivo character varying(500) NOT NULL,
    plazo_dias smallint,
    estado character varying(15) DEFAULT 'EMITIDA'::character varying NOT NULL,
    usuario_registro character varying(60) NOT NULL
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

CREATE TABLE papeleta (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    familia character varying(15) NOT NULL,
    numero character varying(20) NOT NULL,
    codigo_infraccion_id bigint NOT NULL,
    fecha_infraccion date NOT NULL,
    hora_infraccion time without time zone,
    lugar character varying(300) NOT NULL,
    placa character varying(10),
    vehiculo_id bigint,
    licencia_conducir character varying(20),
    infractor_id bigint,
    propietario_id bigint,
    contribuyente_id bigint,
    predio_id bigint,
    notificacion_previa_id bigint,
    base_imponible dinero NOT NULL,
    porcentaje_infraccion alicuota NOT NULL,
    importe_infraccion dinero NOT NULL,
    porcentaje_a_cobrar alicuota NOT NULL,
    importe_a_pagar dinero NOT NULL,
    importe_con_beneficio dinero,
    estado character varying(15) DEFAULT 'IMPUESTA'::character varying NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    obligado_id bigint NOT NULL
);

CREATE TABLE papeleta_cambio_numero (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    papeleta_id bigint NOT NULL,
    numero_anterior character varying(20) NOT NULL,
    numero_nuevo character varying(20) NOT NULL,
    fecha timestamp with time zone DEFAULT now() NOT NULL,
    usuario character varying(60) NOT NULL,
    motivo character varying(500) NOT NULL
);

CREATE TABLE papeleta_masivo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    familia character varying(15) NOT NULL,
    desde date NOT NULL,
    hasta date NOT NULL,
    fecha_criterio date NOT NULL,
    origen character varying(12) NOT NULL,
    total_candidatos integer NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE papeleta_masivo_item (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    corrida_id bigint NOT NULL,
    papeleta_id bigint NOT NULL,
    estado character varying(12) DEFAULT 'PENDIENTE'::character varying NOT NULL,
    valor_id bigint,
    valor_numero character varying(20),
    motivo character varying(200),
    fecha_procesado timestamp with time zone
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

CREATE TABLE prescripcion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    ejercicio_desde ejercicio NOT NULL,
    ejercicio_hasta ejercicio NOT NULL,
    fecha_presentacion date NOT NULL,
    causal character varying(24) NOT NULL,
    plazo_anios smallint NOT NULL,
    conjunto_id bigint NOT NULL,
    resultado character varying(16) NOT NULL,
    resolucion character varying(40),
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE prescripcion_ejercicio (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    prescripcion_id bigint NOT NULL,
    ejercicio ejercicio NOT NULL,
    inicio_computo date NOT NULL,
    inicio_vigente date NOT NULL,
    fecha_prescripcion date NOT NULL,
    prescrita boolean NOT NULL
);

CREATE TABLE prescripcion_hecho (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    prescripcion_id bigint NOT NULL,
    clase character varying(12) NOT NULL,
    causal character varying(120) NOT NULL,
    fecha_desde date NOT NULL,
    fecha_hasta date
);

CREATE TABLE programa_fiscalizacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    codigo character varying(20) NOT NULL,
    descripcion character varying(300) NOT NULL,
    tipo character varying(15) NOT NULL,
    fecha_inicio date NOT NULL,
    fecha_fin date,
    estado character varying(15) DEFAULT 'ABIERTO'::character varying NOT NULL,
    ejercicio ejercicio,
    sector_codigo character varying(10),
    criterio character varying(15),
    fiscalizador character varying(60)
);

CREATE TABLE programa_muestra (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    programa_id bigint NOT NULL,
    predio_id bigint NOT NULL,
    cod_ref_catastral cod_catastral NOT NULL,
    contribuyente_id bigint,
    condicion character varying(15) NOT NULL,
    area_catastral area_m2,
    area_declarada area_m2,
    sector_codigo character varying(10),
    fecha_sorteo date NOT NULL,
    observacion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL
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

CREATE TABLE resolucion_determinacion (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    numero character varying(40) NOT NULL,
    documento_id bigint NOT NULL,
    liquidacion_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    ficha_anterior_id bigint,
    ficha_nueva_id bigint,
    fecha date NOT NULL,
    documento_sustento character varying(80) NOT NULL,
    sustento character varying(1000) NOT NULL,
    base_legal character varying(200) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE resolucion_gerencia (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    papeleta_id bigint NOT NULL,
    tipo character varying(20) NOT NULL,
    numero character varying(40) NOT NULL,
    documento_id bigint NOT NULL,
    fecha date NOT NULL,
    descargo_id bigint,
    sentido character varying(20),
    efecto character varying(20),
    ordinaria_notificacion_id bigint,
    ordinaria_exigible_desde date,
    sancion_accesoria character varying(200),
    sustento character varying(1000) NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone NOT NULL,
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

CREATE TABLE responsable_solidario (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    responsable_id bigint NOT NULL,
    vinculo character varying(20) NOT NULL,
    porcentaje porcentaje,
    vigencia_desde date NOT NULL,
    vigencia_hasta date,
    documento_origen character varying(80) NOT NULL
);

CREATE TABLE saldo_proyectado (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    contribuyente_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    periodo smallint DEFAULT 0 NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    insoluto_saldo dinero DEFAULT 0 NOT NULL,
    fase character varying(12) DEFAULT 'ORDINARIA'::character varying NOT NULL,
    ultimo_asiento_id bigint,
    fecha_calculo timestamp with time zone DEFAULT now() NOT NULL
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

CREATE TABLE transferencia (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    objeto character varying(10) NOT NULL,
    predio_id bigint,
    vehiculo_id bigint,
    transferente_id bigint NOT NULL,
    adquiriente_id bigint NOT NULL,
    tipo_transferencia character varying(40) NOT NULL,
    fecha_transferencia date NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    valor_transferencia dinero NOT NULL,
    porcentaje_transferido porcentaje NOT NULL,
    afecta_alcabala boolean NOT NULL,
    documento_origen character varying(80) NOT NULL,
    observacion character varying(500) NOT NULL,
    usuario_registro character varying(60) NOT NULL
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

CREATE TABLE valor (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    tipo character varying(4) NOT NULL,
    numero character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    contribuyente_id bigint NOT NULL,
    fecha_emision date NOT NULL,
    base_legal character varying(200) NOT NULL,
    monto_insoluto dinero DEFAULT 0 NOT NULL,
    monto_reajuste dinero DEFAULT 0 NOT NULL,
    monto_interes dinero DEFAULT 0 NOT NULL,
    monto_gasto dinero DEFAULT 0 NOT NULL,
    monto_total dinero NOT NULL,
    proyectado_a date NOT NULL,
    estado character varying(15) DEFAULT 'EMITIDO'::character varying NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    observacion character varying(500) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE valor_correlativo (
    municipalidad_id bigint NOT NULL,
    tipo character varying(4) NOT NULL,
    ejercicio ejercicio NOT NULL,
    ultimo bigint DEFAULT 0 NOT NULL
);

CREATE TABLE valor_detalle (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    valor_id bigint NOT NULL,
    tributo character varying(20) NOT NULL,
    ejercicio ejercicio NOT NULL,
    periodo smallint,
    predio_id bigint,
    vehiculo_id bigint,
    referencia_externa character varying(40),
    insoluto dinero DEFAULT 0 NOT NULL,
    reajuste dinero DEFAULT 0 NOT NULL,
    interes dinero DEFAULT 0 NOT NULL,
    gasto dinero DEFAULT 0 NOT NULL
);

CREATE TABLE valor_masivo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    tipo character varying(4) NOT NULL,
    tributo character varying(20),
    ejercicio_desde ejercicio NOT NULL,
    ejercicio_hasta ejercicio NOT NULL,
    fecha_criterio date NOT NULL,
    origen character varying(12) NOT NULL,
    total_candidatos integer NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    observacion character varying(500) NOT NULL
);

CREATE TABLE valor_masivo_item (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    corrida_id bigint NOT NULL,
    contribuyente_id bigint NOT NULL,
    estado character varying(10) DEFAULT 'PENDIENTE'::character varying NOT NULL,
    valor_id bigint,
    fecha_procesado timestamp with time zone
);

CREATE TABLE valor_movimiento (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    valor_id bigint NOT NULL,
    tipo character varying(3) NOT NULL,
    fecha date NOT NULL,
    notificacion_id bigint NOT NULL,
    exigible_desde date NOT NULL,
    usuario_registro character varying(60) NOT NULL,
    fecha_registro timestamp with time zone DEFAULT now() NOT NULL,
    observacion character varying(500) NOT NULL
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

CREATE TABLE vehiculo (
    municipalidad_id bigint NOT NULL,
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    placa character varying(10) NOT NULL,
    contribuyente_id bigint NOT NULL,
    marca character varying(60) NOT NULL,
    modelo character varying(60) NOT NULL,
    categoria character varying(20),
    anio_fabricacion ejercicio NOT NULL,
    anio_inscripcion ejercicio NOT NULL,
    fecha_adquisicion date,
    valor_adquisicion dinero,
    numero_motor character varying(40),
    numero_serie character varying(40),
    estado character varying(20) DEFAULT 'ACTIVO'::character varying NOT NULL,
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
CREATE TABLE cuenta_corriente_asiento_2026 PARTITION OF cuenta_corriente_asiento FOR VALUES IN ('2026');
CREATE TABLE cuenta_corriente_asiento_2027 PARTITION OF cuenta_corriente_asiento FOR VALUES IN ('2027');
CREATE TABLE determinacion_2026 PARTITION OF determinacion FOR VALUES IN ('2026');
CREATE TABLE determinacion_2027 PARTITION OF determinacion FOR VALUES IN ('2027');
CREATE TABLE determinacion_arbitrio_2026 PARTITION OF determinacion_arbitrio FOR VALUES IN ('2026');
CREATE TABLE determinacion_arbitrio_2027 PARTITION OF determinacion_arbitrio FOR VALUES IN ('2027');
CREATE TABLE determinacion_predio_detalle_2026 PARTITION OF determinacion_predio_detalle FOR VALUES IN ('2026');
CREATE TABLE determinacion_predio_detalle_2027 PARTITION OF determinacion_predio_detalle FOR VALUES IN ('2027');

-- ==========================================================================
--  4. RESTRICCIONES
--  Las foraneas al final para no depender del orden. Las que el esquema
--  tiene NOT VALID se emiten NOT VALID: validarlas es una consulta y el
--  migrador corre sin contexto de tenant (DAT-01 §0, hallazgo 4).
-- ==========================================================================

ALTER TABLE acceso ADD CONSTRAINT acceso_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE acceso ADD CONSTRAINT acceso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acceso ADD CONSTRAINT acceso_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['OPCION_MENU'::character varying, 'POLITICA'::character varying])::text[])));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_predio_xor_vehiculo_ck CHECK (((predio_id IS NOT NULL) <> (vehiculo_id IS NOT NULL)));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_transferencia_ck CHECK ((((estado)::text <> 'TRANSFERIDA'::text) OR ((fecha_transferencia IS NOT NULL) AND (usuario_transferencia IS NOT NULL))));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_uso_distinto_ck CHECK ((((hallazgo)::text <> 'USO_DISTINTO'::text) OR (uso_hallado IS NOT NULL)));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_uso_hallado_predial_ck CHECK (((uso_hallado IS NULL) OR (predio_id IS NOT NULL)));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_version_uq UNIQUE NULLS NOT DISTINCT (municipalidad_id, programa_id, contribuyente_id, predio_id, vehiculo_id, version);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fiscalizacion_estado_check CHECK (((estado)::text = ANY ((ARRAY['ABIERTA'::character varying, 'LIQUIDADA'::character varying, 'RELIQUIDADA'::character varying, 'TRANSFERIDA'::character varying, 'ANULADA'::character varying])::text[])));
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fiscalizacion_hallazgo_check CHECK (((hallazgo)::text = ANY ((ARRAY['CONFORME'::character varying, 'OMISO'::character varying, 'SUBVALUADOR'::character varying, 'USO_DISTINTO'::character varying, 'NO_UBICADO'::character varying])::text[])));
ALTER TABLE actividad_economica ADD CONSTRAINT actividad_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_coactivo_medida_check CHECK (((medida)::text = ANY ((ARRAY['RETENCION'::character varying, 'INSCRIPCION'::character varying, 'DEPOSITO'::character varying, 'INTERVENCION'::character varying])::text[])));
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_coactivo_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['REC1'::character varying, 'REC2'::character varying, 'MEDIDA_CAUTELAR'::character varying, 'EMBARGO'::character varying, 'TASACION'::character varying, 'REMATE'::character varying, 'SUSPENSION'::character varying, 'LEVANTAMIENTO'::character varying, 'CONCLUSION'::character varying, 'OTRO'::character varying])::text[])));
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_medida_ck CHECK ((((tipo)::text = 'REC2'::text) = (medida IS NOT NULL)));
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_numero_uq UNIQUE (municipalidad_id, tipo, numero);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_rec2_plazo_ck CHECK (((rec1_exigible_desde IS NULL) OR (fecha >= rec1_exigible_desde)));
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_rec2_sustento_ck CHECK ((((tipo)::text = 'REC2'::text) = ((rec1_notificacion_id IS NOT NULL) AND (rec1_exigible_desde IS NOT NULL))));
ALTER TABLE anuncio ADD CONSTRAINT anuncio_cantidad_check CHECK ((cantidad > 0));
ALTER TABLE anuncio ADD CONSTRAINT anuncio_clase_check CHECK (((clase)::text = ANY ((ARRAY['LETRERO'::character varying, 'PANEL'::character varying, 'TOLDO'::character varying, 'BANDEROLA'::character varying, 'PANTALLA_DIGITAL'::character varying, 'GLOBO_AEROSTATICO'::character varying])::text[])));
ALTER TABLE anuncio ADD CONSTRAINT anuncio_lados_check CHECK ((lados >= 1));
ALTER TABLE anuncio ADD CONSTRAINT anuncio_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE anuncio ADD CONSTRAINT anuncio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE anuncio ADD CONSTRAINT anuncio_tipo_ck CHECK (((tipo)::text = ANY ((ARRAY['AVISO_SIMPLE'::character varying, 'AVISO_LUMINOSO'::character varying, 'AVISO_ILUMINADO'::character varying, 'AVISO_ELECTRONICO'::character varying])::text[])));
ALTER TABLE anuncio ADD CONSTRAINT anuncio_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= fecha_autorizacion)));
ALTER TABLE anuncio_correlativo ADD CONSTRAINT anuncio_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE anuncio_correlativo ADD CONSTRAINT anuncio_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_devengo_ck CHECK ((((tipo)::text = ANY ((ARRAY['AUTORIZACION'::character varying, 'RENOVACION'::character varying])::text[])) = ((referencia_cargo IS NOT NULL) AND (tasa IS NOT NULL) AND (ejercicio IS NOT NULL))));
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_motivo_ck CHECK ((((tipo)::text = ANY ((ARRAY['CESE'::character varying, 'RETIRO'::character varying])::text[])) = (motivo IS NOT NULL)));
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_tasa_check CHECK (((tasa IS NULL) OR ((tasa)::numeric > (0)::numeric)));
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['AUTORIZACION'::character varying, 'RENOVACION'::character varying, 'CESE'::character varying, 'RETIRO'::character varying])::text[])));
ALTER TABLE arancel ADD CONSTRAINT arancel_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE arancel ADD CONSTRAINT arancel_uq UNIQUE (municipalidad_id, conjunto_id, via_id, tramo);
ALTER TABLE arancel ADD CONSTRAINT arancel_valor_m2_check CHECK (((valor_m2)::numeric >= (0)::numeric));
ALTER TABLE area ADD CONSTRAINT area_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE area ADD CONSTRAINT area_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE auditoria ADD CONSTRAINT auditoria_observacion_ck CHECK ((length(btrim((observacion)::text)) >= 5));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_operacion_check CHECK (((operacion)::text = ANY ((ARRAY['ALTA'::character varying, 'MODIFICACION'::character varying, 'BAJA'::character varying, 'ANULACION'::character varying, 'REVERSION'::character varying, 'PERMISO'::character varying, 'ACCESO'::character varying])::text[])));
ALTER TABLE auditoria ADD CONSTRAINT auditoria_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE beneficio ADD CONSTRAINT beneficio_clase_check CHECK (((clase)::text = ANY ((ARRAY['INAFECTACION'::character varying, 'EXONERACION'::character varying, 'DEDUCCION'::character varying, 'DESCUENTO'::character varying])::text[])));
ALTER TABLE beneficio ADD CONSTRAINT beneficio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE beneficio ADD CONSTRAINT beneficio_valor_ck CHECK (((porcentaje IS NOT NULL) OR (monto IS NOT NULL)));
ALTER TABLE beneficio ADD CONSTRAINT beneficio_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_estado_conservacion_check CHECK (((estado_conservacion)::text = ANY ((ARRAY['MUY_BUENO'::character varying, 'BUENO'::character varying, 'REGULAR'::character varying, 'MALO'::character varying, 'RUINOSO'::character varying])::text[])));
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_material_estructural_check CHECK (((material_estructural)::text = ANY ((ARRAY['CONCRETO'::character varying, 'LADRILLO'::character varying, 'ADOBE'::character varying, 'MADERA'::character varying, 'QUINCHA'::character varying, 'OTRO'::character varying])::text[])));
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE caja ADD CONSTRAINT caja_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_serie_uq UNIQUE (municipalidad_id, serie);
ALTER TABLE certificado ADD CONSTRAINT certificado_derecho_check CHECK (((derecho)::numeric >= (0)::numeric));
ALTER TABLE certificado ADD CONSTRAINT certificado_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE certificado ADD CONSTRAINT certificado_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE certificado ADD CONSTRAINT certificado_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE certificado ADD CONSTRAINT certificado_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['NUMERACION'::character varying, 'ZONIFICACION_VIAS'::character varying, 'PARAMETROS_URBANISTICOS'::character varying, 'JURISDICCION'::character varying])::text[])));
ALTER TABLE certificado ADD CONSTRAINT certificado_vigencia_ck CHECK ((vigencia_hasta >= fecha_emision));
ALTER TABLE certificado_correlativo ADD CONSTRAINT certificado_correlativo_pk PRIMARY KEY (municipalidad_id, tipo, ejercicio);
ALTER TABLE certificado_correlativo ADD CONSTRAINT certificado_correlativo_ultimo_check CHECK ((ultimo >= 0));
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
ALTER TABLE ciiu ADD CONSTRAINT ciiu_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE ciiu ADD CONSTRAINT ciiu_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE ciiu ADD CONSTRAINT ciiu_riesgo_itse_check CHECK (((riesgo_itse)::text = ANY ((ARRAY['BAJO'::character varying, 'MEDIO'::character varying, 'ALTO'::character varying, 'MUY_ALTO'::character varying])::text[])));
ALTER TABLE ciiu ADD CONSTRAINT ciiu_seccion_check CHECK (((seccion IS NULL) OR ((seccion >= 'A'::bpchar) AND (seccion <= 'U'::bpchar))));
ALTER TABLE codigo_infraccion ADD CONSTRAINT codigo_infraccion_familia_check CHECK (((familia)::text = ANY ((ARRAY['TRANSITO'::character varying, 'ADMINISTRATIVA'::character varying])::text[])));
ALTER TABLE codigo_infraccion ADD CONSTRAINT codigo_infraccion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE codigo_infraccion ADD CONSTRAINT codigo_infraccion_uq UNIQUE (municipalidad_id, familia, codigo, vigencia_desde);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_orientacion_uq UNIQUE (municipalidad_id, ficha_id, orientacion);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_rural_orientacion_check CHECK (((orientacion)::text = ANY ((ARRAY['NORTE'::character varying, 'SUR'::character varying, 'ESTE'::character varying, 'OESTE'::character varying])::text[])));
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_detalle_pk PRIMARY KEY (municipalidad_id, conjunto_id, parametro_id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_parametros_estado_check CHECK (((estado)::text = ANY ((ARRAY['ABIERTO'::character varying, 'SELLADO'::character varying])::text[])));
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_sellado_ck CHECK ((((estado)::text = 'ABIERTO'::text) OR ((fecha_sellado IS NOT NULL) AND (usuario_sellado IS NOT NULL))));
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_uq UNIQUE (municipalidad_id, ejercicio, version);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_pk PRIMARY KEY (municipalidad_id, id);
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
ALTER TABLE contacto ADD CONSTRAINT contacto_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE contacto ADD CONSTRAINT contacto_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['TELEFONO'::character varying, 'CELULAR'::character varying, 'EMAIL'::character varying, 'GESTOR'::character varying, 'CONTACTO'::character varying])::text[])));
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_codigo_uq UNIQUE (municipalidad_id, codigo_contribuyente);
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_condicion_especial_check CHECK (((condicion_especial)::text = ANY ((ARRAY['PENSIONISTA'::character varying, 'ADULTO_MAYOR'::character varying, 'DISCAPACIDAD'::character varying])::text[])));
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_documento_uq UNIQUE (municipalidad_id, tipo_documento, numero_documento);
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_tipo_documento_check CHECK (((tipo_documento)::text = ANY ((ARRAY['DNI'::character varying, 'RUC'::character varying, 'CE'::character varying, 'PASAPORTE'::character varying, 'PARTIDA'::character varying, 'OTRO'::character varying])::text[])));
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_tipo_persona_check CHECK (((tipo_persona)::text = ANY ((ARRAY['NATURAL'::character varying, 'JURIDICA'::character varying, 'SUCESION_INDIVISA'::character varying, 'SOCIEDAD_CONYUGAL'::character varying])::text[])));
ALTER TABLE convenio ADD CONSTRAINT convenio_cuota_inicial_check CHECK (((cuota_inicial)::numeric >= (0)::numeric));
ALTER TABLE convenio ADD CONSTRAINT convenio_garantia_ck CHECK (((tipo_garantia IS NULL) OR ((tipo_garantia)::text = ANY ((ARRAY['NO_REQUIERE'::character varying, 'CARTA_FIANZA'::character varying, 'HIPOTECA'::character varying, 'AVAL'::character varying, 'PRENDA'::character varying])::text[]))));
ALTER TABLE convenio ADD CONSTRAINT convenio_inicial_ck CHECK (((cuota_inicial)::numeric <= (monto_total)::numeric));
ALTER TABLE convenio ADD CONSTRAINT convenio_maximo_cuotas_check CHECK ((maximo_cuotas > 0));
ALTER TABLE convenio ADD CONSTRAINT convenio_monto_total_check CHECK (((monto_total)::numeric > (0)::numeric));
ALTER TABLE convenio ADD CONSTRAINT convenio_numero_cuotas_check CHECK ((numero_cuotas > 0));
ALTER TABLE convenio ADD CONSTRAINT convenio_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE convenio ADD CONSTRAINT convenio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE convenio ADD CONSTRAINT convenio_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['ORDINARIO'::character varying, 'COACTIVO'::character varying])::text[])));
ALTER TABLE convenio_correlativo ADD CONSTRAINT convenio_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE convenio_correlativo ADD CONSTRAINT convenio_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_capital_check CHECK (((capital)::numeric >= (0)::numeric));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_desglose_ck CHECK (((monto)::numeric = (((capital)::numeric + (interes)::numeric) + (gasto)::numeric)));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_gasto_check CHECK (((gasto)::numeric >= (0)::numeric));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_interes_check CHECK (((interes)::numeric >= (0)::numeric));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_monto_check CHECK (((monto)::numeric > (0)::numeric));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_numero_ck CHECK ((numero >= 0));
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_uq UNIQUE (municipalidad_id, convenio_id, numero);
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_desglose_ck CHECK (((monto)::numeric = ((((insoluto)::numeric + (reajuste)::numeric) + (interes)::numeric) + (gasto)::numeric)));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_fase_origen_check CHECK (((fase_origen)::text = ANY ((ARRAY['ORDINARIA'::character varying, 'VALOR'::character varying, 'COACTIVA'::character varying])::text[])));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_gasto_check CHECK (((gasto)::numeric >= (0)::numeric));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_insoluto_check CHECK (((insoluto)::numeric >= (0)::numeric));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_interes_check CHECK (((interes)::numeric >= (0)::numeric));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_monto_check CHECK (((monto)::numeric > (0)::numeric));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_periodo_check CHECK (((periodo >= 0) AND (periodo <= 12)));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_reajuste_check CHECK (((reajuste)::numeric >= (0)::numeric));
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_unidad_ck CHECK (((predio_id IS NULL) OR (vehiculo_id IS NULL)));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_asientos_check CHECK ((asientos >= 0));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_cierre_ck CHECK ((((tipo)::text = 'FORMALIZACION'::text) OR ((motivo IS NOT NULL) AND (btrim((motivo)::text) <> ''::text))));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_cuota_check CHECK (((cuota IS NULL) OR (cuota >= 0)));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_formalizacion_ck CHECK ((((tipo)::text <> 'FORMALIZACION'::text) OR ((recibo_id IS NOT NULL) AND (cuota IS NOT NULL))));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_importe_check CHECK (((importe)::numeric >= (0)::numeric));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_reformulacion_ck CHECK ((((tipo)::text = 'REFORMULACION'::text) = (convenio_nuevo_id IS NOT NULL)));
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['FORMALIZACION'::character varying, 'ANULACION'::character varying, 'QUIEBRE'::character varying, 'REFORMULACION'::character varying])::text[])));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_alcance_ck CHECK (((alcance)::text = ANY ((ARRAY['TODOS'::character varying, 'SECTOR'::character varying, 'RANGO_DE_CODIGO'::character varying, 'OBSERVADOS'::character varying])::text[])));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_cuenta_ck CHECK ((determinados <= leidos));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_determinados_check CHECK ((determinados >= 0));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_leidos_check CHECK ((leidos >= 0));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_rango_ck CHECK ((((alcance)::text <> 'RANGO_DE_CODIGO'::text) OR ((codigo_desde IS NOT NULL) AND (codigo_hasta IS NOT NULL))));
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_sector_ck CHECK ((((alcance)::text <> 'SECTOR'::text) OR (sector IS NOT NULL)));
ALTER TABLE corrida_predial_observado ADD CONSTRAINT corrida_predial_observado_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE costa_obligacion ADD CONSTRAINT costa_obligacion_pk PRIMARY KEY (municipalidad_id, contribuyente_id, tributo, ejercicio);
ALTER TABLE costa_procesal ADD CONSTRAINT costa_monto_ck CHECK (((monto)::numeric > (0)::numeric));
ALTER TABLE costa_procesal ADD CONSTRAINT costa_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE costa_procesal ADD CONSTRAINT costa_procesal_monto_check CHECK (((monto)::numeric >= (0)::numeric));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_acto_ck CHECK (((acto IS NULL) OR ((acto)::text = ANY ((ARRAY['ALTA_DEUDA'::character varying, 'BAJA_DEUDA'::character varying])::text[]))));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_baja_con_causal_ck CHECK ((((acto)::text IS DISTINCT FROM 'BAJA_DEUDA'::text) OR (causal IS NOT NULL) OR (asiento_reversado_id IS NOT NULL))) NOT VALID NOT VALID;
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_causal_ck CHECK (((causal IS NULL) OR ((causal)::text = ANY ((ARRAY['PRESCRIPCION_DECLARADA'::character varying, 'RESOLUCION_QUE_DEJA_SIN_EFECTO'::character varying, 'ERROR_MATERIAL'::character varying, 'COMPENSACION'::character varying, 'DEUDA_DE_COBRANZA_DUDOSA'::character varying, 'CONDONACION_POR_ORDENANZA'::character varying])::text[]))));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_causal_del_acto_ck CHECK (((causal IS NULL) OR (NOT ((acto)::text IS DISTINCT FROM 'BAJA_DEUDA'::text))));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_motivo_ck CHECK ((((concepto)::text <> ALL ((ARRAY['ANULACION'::character varying, 'CONDONACION'::character varying, 'AJUSTE'::character varying])::text[])) OR (motivo IS NOT NULL)));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_titular_anterior_ck CHECK (((NOT unidad_de_titular_anterior) OR (predio_id IS NOT NULL) OR (vehiculo_id IS NOT NULL)));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_tributo_ck CHECK (((asiento_reversado_id IS NOT NULL) OR ((tributo)::text = ANY ((ARRAY['PREDIAL'::character varying, 'ARBITRIO'::character varying, 'VEHICULAR'::character varying, 'ALCABALA'::character varying, 'ESPECTACULOS'::character varying, 'ANUNCIOS'::character varying, 'JUEGOS'::character varying, 'MULTA_TRIBUTARIA'::character varying, 'MULTA_TRANSITO'::character varying, 'MULTA_ADMINISTRATIVA'::character varying, 'CONVENIO'::character varying, 'COSTAS PROCESALES'::character varying])::text[])))) NOT VALID NOT VALID;
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT cuenta_corriente_asiento_concepto_check CHECK (((concepto)::text = ANY ((ARRAY['INSOLUTO'::character varying, 'REAJUSTE'::character varying, 'INTERES'::character varying, 'GASTO'::character varying, 'PAGO'::character varying, 'COMPENSACION'::character varying, 'ANULACION'::character varying, 'CONDONACION'::character varying, 'AJUSTE'::character varying, 'FRACCIONAMIENTO'::character varying])::text[])));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT cuenta_corriente_asiento_fase_check CHECK (((fase)::text = ANY ((ARRAY['ORDINARIA'::character varying, 'VALOR'::character varying, 'COACTIVA'::character varying, 'CONVENIO'::character varying])::text[])));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT cuenta_corriente_asiento_monto_check CHECK (((monto)::numeric > (0)::numeric));
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT cuenta_corriente_asiento_tipo_check CHECK ((tipo = ANY (ARRAY['CARGO'::bpchar, 'ABONO'::bpchar])));
ALTER TABLE declaracion_jurada ADD CONSTRAINT declaracion_jurada_estado_check CHECK (((estado)::text = ANY ((ARRAY['PRESENTADA'::character varying, 'OBSERVADA'::character varying, 'SUSTITUIDA'::character varying, 'ANULADA'::character varying])::text[])));
ALTER TABLE declaracion_jurada ADD CONSTRAINT declaracion_jurada_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['HR'::character varying, 'PU'::character varying, 'PR'::character varying, 'VEHICULAR'::character varying, 'RECTIFICATORIA'::character varying])::text[])));
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_antiguedad_hasta_check CHECK ((antiguedad_hasta > 0));
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_nacional_ck CHECK ((municipalidad_id IS NULL));
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_pk PRIMARY KEY (id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_uq UNIQUE NULLS NOT DISTINCT (publicacion_id, uso, material, estado_conservacion, antiguedad_hasta);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_uso_check CHECK (((uso)::text ~ '^0[1-4]$'::text));
ALTER TABLE descargo ADD CONSTRAINT descargo_numero_uq UNIQUE (municipalidad_id, numero_expediente);
ALTER TABLE descargo ADD CONSTRAINT descargo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE descargo ADD CONSTRAINT descargo_plazo_ck CHECK ((en_plazo = (fecha <= presentado_hasta)));
ALTER TABLE descargo ADD CONSTRAINT descargo_tipo_recurso_check CHECK (((tipo_recurso)::text = ANY ((ARRAY['DESCARGO'::character varying, 'RECONSIDERACION'::character varying, 'APELACION'::character varying, 'NULIDAD'::character varying])::text[])));
ALTER TABLE determinacion ADD CONSTRAINT determinacion_estado_check CHECK (((estado)::text = ANY ((ARRAY['BORRADOR'::character varying, 'EMITIDA'::character varying, 'ANULADA'::character varying])::text[])));
ALTER TABLE determinacion ADD CONSTRAINT determinacion_monto_determinado_check CHECK (((monto_determinado)::numeric >= (0)::numeric));
ALTER TABLE determinacion ADD CONSTRAINT determinacion_origen_check CHECK (((origen)::text = ANY ((ARRAY['ORDINARIA'::character varying, 'FISCALIZACION'::character varying, 'RECTIFICATORIA'::character varying])::text[])));
ALTER TABLE determinacion ADD CONSTRAINT determinacion_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE determinacion ADD CONSTRAINT determinacion_predial_sin_predio_ck CHECK ((((tributo)::text <> 'PREDIAL'::text) OR (predio_id IS NULL)));
ALTER TABLE determinacion ADD CONSTRAINT determinacion_tributo_check CHECK (((tributo)::text = ANY ((ARRAY['PREDIAL'::character varying, 'ARBITRIO'::character varying, 'VEHICULAR'::character varying, 'ALCABALA'::character varying, 'ESPECTACULOS'::character varying, 'ANUNCIOS'::character varying, 'JUEGOS'::character varying])::text[])));
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT det_arbitrio_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT det_arbitrio_uq UNIQUE (municipalidad_id, ejercicio, servicio, periodo, predio_id);
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT determinacion_arbitrio_monto_check CHECK (((monto)::numeric >= (0)::numeric));
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT determinacion_arbitrio_periodo_check CHECK (((periodo >= 1) AND (periodo <= 12)));
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT determinacion_arbitrio_servicio_check CHECK (((servicio)::text = ANY ((ARRAY['LIMPIEZA_PUBLICA'::character varying, 'PARQUES_JARDINES'::character varying, 'SERENAZGO'::character varying])::text[])));
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_exonerado_cabe_ck CHECK (((valuo_exonerado)::numeric <= (autovaluo)::numeric));
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_exonerado_ck CHECK (((valuo_exonerado)::numeric >= (0)::numeric));
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_pk PRIMARY KEY (municipalidad_id, ejercicio, id);
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_uq UNIQUE (municipalidad_id, ejercicio, determinacion_id, predio_id);
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT determinacion_predio_detalle_autovaluo_check CHECK (((autovaluo)::numeric >= (0)::numeric));
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT determinacion_predio_detalle_base_imponible_predio_check CHECK (((base_imponible_predio)::numeric >= (0)::numeric));
ALTER TABLE dj_correlativo ADD CONSTRAINT dj_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE dj_correlativo ADD CONSTRAINT dj_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_formato_check CHECK (((formato)::text = ANY ((ARRAY['PDF'::character varying, 'XLS'::character varying, 'RTF'::character varying])::text[])));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_reimpresiones_check CHECK ((reimpresiones >= 0));
ALTER TABLE documento_emitido ADD CONSTRAINT documento_numero_uq UNIQUE (municipalidad_id, tipo, ejercicio, numero);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE domicilio ADD CONSTRAINT domicilio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE domicilio ADD CONSTRAINT domicilio_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['FISCAL'::character varying, 'PROCESAL'::character varying])::text[])));
ALTER TABLE domicilio ADD CONSTRAINT domicilio_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE edificacion_correlativo ADD CONSTRAINT edificacion_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE edificacion_correlativo ADD CONSTRAINT edificacion_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_area_check CHECK (((area)::numeric > (0)::numeric));
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_categoria_check CHECK ((categoria ~ '^[A-J]$'::text));
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_partida_check CHECK (((partida)::text = ANY ((ARRAY['MUROS'::character varying, 'TECHOS'::character varying, 'PUERTAS'::character varying])::text[])));
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_piso_check CHECK ((piso >= 1));
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_uq UNIQUE (municipalidad_id, fue_id, version, piso, partida);
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_version_check CHECK ((version >= 1));
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_motivo_ck CHECK ((((tipo)::text = 'ANULACION'::text) = (motivo IS NOT NULL)));
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_numero_ck CHECK ((((tipo)::text = 'EMISION'::text) = (numero_licencia IS NOT NULL)));
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_recibo_ck CHECK ((((tipo)::text = ANY ((ARRAY['EMISION'::character varying, 'REVALIDACION'::character varying])::text[])) = (recibo_id IS NOT NULL)));
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['EMISION'::character varying, 'REVALIDACION'::character varying, 'ANULACION'::character varying])::text[])));
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_colegiatura_ck CHECK (((colegio IS NULL) = (colegiatura IS NULL)));
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_colegio_check CHECK (((colegio)::text = ANY ((ARRAY['CAP'::character varying, 'CIP'::character varying])::text[])));
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['PROYECTISTA_ARQUITECTURA'::character varying, 'PROYECTISTA_ESTRUCTURAS'::character varying, 'PROYECTISTA_INSTALACIONES'::character varying, 'RESPONSABLE_OBRA'::character varying])::text[])));
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_uq UNIQUE (municipalidad_id, fue_id, version, tipo);
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_version_check CHECK ((version >= 1));
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_area_techada_check CHECK (((area_techada)::numeric >= (0)::numeric));
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_estacionamientos_check CHECK ((estacionamientos >= 0));
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_numero_pisos_check CHECK ((numero_pisos > 0));
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_plazo_meses_check CHECK ((plazo_meses > 0));
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_uq UNIQUE (municipalidad_id, fue_id, version);
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_version_check CHECK ((version >= 1));
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_folios_check CHECK ((folios > 0));
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_uq UNIQUE (municipalidad_id, fue_id, version, requisito);
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_version_check CHECK ((version >= 1));
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_area_terreno_check CHECK (((area_terreno)::numeric > (0)::numeric));
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_fondo_check CHECK ((fondo > (0)::numeric));
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_frente_check CHECK ((frente > (0)::numeric));
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_uq UNIQUE (municipalidad_id, fue_id, version);
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_version_check CHECK ((version >= 1));
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_ck CHECK ((hasta >= desde));
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_orden_check CHECK ((orden >= 1));
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_uq UNIQUE (municipalidad_id, licencia_id, orden);
ALTER TABLE espectaculo ADD CONSTRAINT espectaculo_estado_check CHECK (((estado)::text = ANY ((ARRAY['REGISTRADO'::character varying, 'LIQUIDADO'::character varying, 'ANULADO'::character varying])::text[])));
ALTER TABLE espectaculo ADD CONSTRAINT espectaculo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_coactivo_correlativo_check CHECK ((correlativo > 0));
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_correlativo_uq UNIQUE (municipalidad_id, ejercicio, correlativo);
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE expediente_correlativo ADD CONSTRAINT expediente_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE expediente_correlativo ADD CONSTRAINT expediente_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_apertura_ck CHECK ((((tipo)::text <> 'APERTURA'::text) OR ((estado)::text = 'INICIADO'::text)));
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_carga_ck CHECK (((((tipo)::text = ANY ((ARRAY['APERTURA'::character varying, 'ESTADO'::character varying])::text[])) AND (estado IS NOT NULL) AND (direccion_referencial IS NULL)) OR (((tipo)::text = 'DIRECCION'::text) AND (estado IS NULL) AND (direccion_referencial IS NOT NULL))));
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_documento_ck CHECK (((documento_fecha IS NULL) = (documento_numero IS NULL)));
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_estado_check CHECK (((estado)::text = ANY ((ARRAY['INICIADO'::character varying, 'REC1_EMITIDA'::character varying, 'REC1_NOTIFICADA'::character varying, 'REC2_EMITIDA'::character varying, 'MEDIDA_CAUTELAR'::character varying, 'SUSPENDIDO'::character varying, 'CONCLUIDO'::character varying])::text[])));
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['APERTURA'::character varying, 'ESTADO'::character varying, 'DIRECCION'::character varying])::text[])));
ALTER TABLE expediente_valor ADD CONSTRAINT expediente_valor_pk PRIMARY KEY (municipalidad_id, expediente_id, valor_id);
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
ALTER TABLE internamiento ADD CONSTRAINT internamiento_acta_uq UNIQUE (municipalidad_id, acta);
ALTER TABLE internamiento ADD CONSTRAINT internamiento_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE internamiento ADD CONSTRAINT internamiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_liberacion_ck CHECK ((((tipo)::text <> 'LIBERACION'::text) OR ((recibo_custodia IS NOT NULL) AND (persona_retira IS NOT NULL) AND (documento_retira IS NOT NULL) AND (dias_custodia IS NOT NULL))));
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_acta_uq UNIQUE (municipalidad_id, acta);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_dias_custodia_check CHECK (((dias_custodia IS NULL) OR (dias_custodia >= 0)));
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['LIBERACION'::character varying, 'ABANDONO'::character varying])::text[])));
ALTER TABLE licencia_correlativo ADD CONSTRAINT licencia_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE licencia_correlativo ADD CONSTRAINT licencia_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_numero_ck CHECK ((numero >= 1));
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_reimpresion_check CHECK ((reimpresion >= 1));
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_uq UNIQUE (municipalidad_id, licencia_id, numero);
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_expediente_uq UNIQUE (municipalidad_id, expediente);
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_origen_ck CHECK ((((tipo_tramite)::text <> ALL ((ARRAY['AMPLIACION_DE_LICENCIA'::character varying, 'REVALIDACION_DE_LICENCIA'::character varying])::text[])) OR (licencia_origen_id IS NOT NULL)));
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_representante_ck CHECK ((((representante_nombre IS NULL) AND (representante_documento IS NULL) AND (representante_partida IS NULL)) OR ((representante_nombre IS NOT NULL) AND (representante_documento IS NOT NULL) AND (representante_partida IS NOT NULL))));
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_tipo_obra_ck CHECK (((tipo_obra)::text = ANY ((ARRAY['EDIFICACION_NUEVA'::character varying, 'AMPLIACION'::character varying, 'REMODELACION'::character varying, 'DEMOLICION'::character varying, 'CERCO'::character varying, 'PUESTA_EN_VALOR'::character varying])::text[])));
ALTER TABLE licencia_edificacion ADD CONSTRAINT licencia_edificacion_modalidad_check CHECK (((modalidad)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying, 'D'::character varying])::text[])));
ALTER TABLE licencia_edificacion ADD CONSTRAINT licencia_edificacion_revision_check CHECK (((revision)::text = ANY ((ARRAY['REVISORES_URBANOS'::character varying, 'COMISION_TECNICA'::character varying])::text[])));
ALTER TABLE licencia_edificacion ADD CONSTRAINT licencia_edificacion_tipo_tramite_check CHECK (((tipo_tramite)::text = ANY ((ARRAY['ANTEPROYECTO_EN_CONSULTA'::character varying, 'LICENCIA_DE_OBRA'::character varying, 'AMPLIACION_DE_LICENCIA'::character varying, 'REVALIDACION_DE_LICENCIA'::character varying, 'REGULARIZACION_DE_LICENCIA'::character varying])::text[])));
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_tipo_ck CHECK (((tipo_licencia)::text = ANY ((ARRAY['DEFINITIVA'::character varying, 'TEMPORAL'::character varying, 'CESIONARIA'::character varying])::text[])));
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= fecha_emision)));
ALTER TABLE licencia_giro ADD CONSTRAINT licencia_giro_pk PRIMARY KEY (municipalidad_id, licencia_id, ciiu_id);
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_motivo_ck CHECK ((((tipo)::text = 'CANCELACION'::text) = (motivo IS NOT NULL)));
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['EMISION'::character varying, 'CANCELACION'::character varying])::text[])));
ALTER TABLE liquidacion_correlativo ADD CONSTRAINT liquidacion_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE liquidacion_correlativo ADD CONSTRAINT liquidacion_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_correlativo_check CHECK ((correlativo > 0));
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_correlativo_uq UNIQUE (municipalidad_id, ejercicio, correlativo);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_total_check CHECK (((total)::numeric > (0)::numeric));
ALTER TABLE liquidacion_costas_correlativo ADD CONSTRAINT liquidacion_costas_correlativo_pk PRIMARY KEY (municipalidad_id, ejercicio);
ALTER TABLE liquidacion_costas_correlativo ADD CONSTRAINT liquidacion_costas_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_cifras_ck CHECK (((base_declarada IS NULL) = (base_hallada IS NULL)));
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_condicion_check CHECK (((condicion)::text = ANY ((ARRAY['CONFORME'::character varying, 'OMISO'::character varying, 'SUBVALUADOR'::character varying, 'USO_DISTINTO'::character varying, 'NO_UBICADO'::character varying])::text[])));
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_unidad_ck CHECK (((predio_id IS NOT NULL) <> (vehiculo_id IS NOT NULL)));
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_uq UNIQUE NULLS NOT DISTINCT (municipalidad_id, liquidacion_id, ejercicio, predio_id, vehiculo_id);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_correlativo_uq UNIQUE (municipalidad_id, ejercicio, correlativo);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_fiscalizacion_correlativo_check CHECK ((correlativo > 0));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_fiscalizacion_tipo_fiscalizacion_check CHECK (((tipo_fiscalizacion)::text = ANY ((ARRAY['CIERTA'::character varying, 'PRESUNTA'::character varying, 'DE_OFICIO'::character varying, 'GABINETE'::character varying])::text[])));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_fiscalizacion_version_check CHECK ((version > 0));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_no_se_sustituye_ck CHECK (((liquidacion_anterior_id IS NULL) OR (liquidacion_anterior_id <> id)));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_periodo_ck CHECK (((ejercicio_hasta)::smallint >= (ejercicio_desde)::smallint));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_reliquidacion_ck CHECK (((version = 1) = (liquidacion_anterior_id IS NULL)));
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_version_uq UNIQUE (municipalidad_id, acta_id, version);
ALTER TABLE liquidacion_movimiento ADD CONSTRAINT liquidacion_movimiento_apertura_ck CHECK ((((tipo)::text <> 'APERTURA'::text) OR ((estado)::text = 'ABIERTA'::text)));
ALTER TABLE liquidacion_movimiento ADD CONSTRAINT liquidacion_movimiento_estado_check CHECK (((estado)::text = ANY ((ARRAY['ABIERTA'::character varying, 'EN_PROCESO'::character varying, 'LIQUIDADA'::character varying, 'NOTIFICADA'::character varying, 'ANULADA'::character varying])::text[])));
ALTER TABLE liquidacion_movimiento ADD CONSTRAINT liquidacion_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE liquidacion_movimiento ADD CONSTRAINT liquidacion_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['APERTURA'::character varying, 'ESTADO'::character varying])::text[])));
ALTER TABLE manzana ADD CONSTRAINT manzana_codigo_uq UNIQUE (municipalidad_id, sector_id, codigo);
ALTER TABLE manzana ADD CONSTRAINT manzana_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_pk PRIMARY KEY (municipalidad_id, grupo_id, usuario_id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_pkey PRIMARY KEY (id);
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['DISTRITAL'::character varying, 'PROVINCIAL'::character varying])::text[])));
ALTER TABLE municipalidad ADD CONSTRAINT municipalidad_ubigeo_key UNIQUE (ubigeo);
ALTER TABLE notificacion ADD CONSTRAINT notificacion_exigibilidad_ck CHECK ((((resultado)::text = ANY ((ARRAY['NOTIFICADO'::character varying, 'RECHAZADO'::character varying])::text[])) = ((exigible_desde IS NOT NULL) AND (conjunto_id IS NOT NULL))));
ALTER TABLE notificacion ADD CONSTRAINT notificacion_intento_check CHECK ((intento > 0));
ALTER TABLE notificacion ADD CONSTRAINT notificacion_intento_uq UNIQUE (municipalidad_id, objeto, objeto_id, intento);
ALTER TABLE notificacion ADD CONSTRAINT notificacion_modalidad_check CHECK (((modalidad)::text = ANY ((ARRAY['PERSONAL'::character varying, 'CEDULON'::character varying, 'PUBLICACION'::character varying, 'CORREO'::character varying, 'NEGATIVA'::character varying])::text[])));
ALTER TABLE notificacion ADD CONSTRAINT notificacion_numero_uq UNIQUE (municipalidad_id, objeto, numero);
ALTER TABLE notificacion ADD CONSTRAINT notificacion_objeto_check CHECK (((objeto)::text = ANY ((ARRAY['VALOR'::character varying, 'RESOLUCION'::character varying, 'ACTO_COACTIVO'::character varying, 'PAPELETA'::character varying])::text[])));
ALTER TABLE notificacion ADD CONSTRAINT notificacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE notificacion ADD CONSTRAINT notificacion_resultado_ck CHECK (((resultado)::text = ANY ((ARRAY['NOTIFICADO'::character varying, 'NO_UBICADO'::character varying, 'RECHAZADO'::character varying])::text[])));
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notif_adm_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notif_adm_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notificacion_administrativa_estado_check CHECK (((estado)::text = ANY ((ARRAY['EMITIDA'::character varying, 'SUBSANADA'::character varying, 'VENCIDA'::character varying, 'ANULADA'::character varying])::text[])));
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_cantidad_check CHECK ((cantidad > (0)::numeric));
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_estado_check CHECK (((estado)::text = ANY ((ARRAY['IMPUESTA'::character varying, 'NOTIFICADA'::character varying, 'RESUELTA'::character varying, 'PAGADA'::character varying, 'COACTIVA'::character varying, 'ANULADA'::character varying, 'PRESCRITA'::character varying])::text[])));
ALTER TABLE papeleta ADD CONSTRAINT papeleta_familia_check CHECK (((familia)::text = ANY ((ARRAY['TRANSITO'::character varying, 'ADMINISTRATIVA'::character varying])::text[])));
ALTER TABLE papeleta ADD CONSTRAINT papeleta_familia_ck CHECK (((((familia)::text = 'TRANSITO'::text) AND (placa IS NOT NULL)) OR (((familia)::text = 'ADMINISTRATIVA'::text) AND ((contribuyente_id IS NOT NULL) OR (predio_id IS NOT NULL)))));
ALTER TABLE papeleta ADD CONSTRAINT papeleta_numero_uq UNIQUE (municipalidad_id, familia, numero);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE papeleta_cambio_numero ADD CONSTRAINT papeleta_cambio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_familia_check CHECK (((familia)::text = ANY ((ARRAY['TRANSITO'::character varying, 'ADMINISTRATIVA'::character varying])::text[])));
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_origen_check CHECK (((origen)::text = ANY ((ARRAY['SELECCION'::character varying, 'RANGO'::character varying])::text[])));
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_rango_ck CHECK ((desde <= hasta));
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_total_candidatos_check CHECK ((total_candidatos >= 0));
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_estado_check CHECK (((estado)::text = ANY ((ARRAY['PENDIENTE'::character varying, 'GENERADO'::character varying, 'SIN_DEUDA'::character varying, 'NO_PROCEDE'::character varying])::text[])));
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_motivo_ck CHECK ((((estado)::text = 'NO_PROCEDE'::text) = (motivo IS NOT NULL)));
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_uq UNIQUE (municipalidad_id, corrida_id, papeleta_id);
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_valor_ck CHECK (((((estado)::text = 'GENERADO'::text) AND (valor_id IS NOT NULL) AND (valor_numero IS NOT NULL)) OR (((estado)::text <> 'GENERADO'::text) AND (valor_id IS NULL) AND (valor_numero IS NULL))));
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_doble_verificacion_ck CHECK (((usuario_aprueba IS NULL) OR ((usuario_aprueba)::text <> (usuario_carga)::text)));
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_tributario_pkey PRIMARY KEY (id);
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_valor_ck CHECK (((valor_numerico IS NOT NULL) OR (valor_texto IS NOT NULL)));
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_unidad_uq UNIQUE (municipalidad_id, ficha_id, predio_id);
ALTER TABLE permiso ADD CONSTRAINT permiso_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_sujeto_ck CHECK ((((grupo_id IS NOT NULL) AND (usuario_id IS NULL)) OR ((grupo_id IS NULL) AND (usuario_id IS NOT NULL))));
ALTER TABLE predio ADD CONSTRAINT predio_codigo_uq UNIQUE (municipalidad_id, codigo_ref_catastral);
ALTER TABLE predio ADD CONSTRAINT predio_estado_check CHECK (((estado)::text = ANY ((ARRAY['ACTIVO'::character varying, 'DADO_DE_BAJA'::character varying])::text[])));
ALTER TABLE predio ADD CONSTRAINT predio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['URBANO'::character varying, 'RUSTICO'::character varying])::text[])));
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_causal_check CHECK (((causal)::text = ANY ((ARRAY['DECLARACION_PRESENTADA'::character varying, 'SIN_DECLARACION'::character varying, 'AGENTE_RETENCION'::character varying])::text[])));
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_ejercicios_ck CHECK (((ejercicio_desde)::smallint <= (ejercicio_hasta)::smallint));
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_plazo_anios_check CHECK ((plazo_anios > 0));
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_resultado_check CHECK (((resultado)::text = ANY ((ARRAY['PROCEDE'::character varying, 'PROCEDE_EN_PARTE'::character varying, 'NO_PROCEDE'::character varying])::text[])));
ALTER TABLE prescripcion_ejercicio ADD CONSTRAINT prescripcion_ejercicio_orden_ck CHECK ((inicio_vigente >= inicio_computo));
ALTER TABLE prescripcion_ejercicio ADD CONSTRAINT prescripcion_ejercicio_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE prescripcion_ejercicio ADD CONSTRAINT prescripcion_ejercicio_uq UNIQUE (municipalidad_id, prescripcion_id, ejercicio);
ALTER TABLE prescripcion_hecho ADD CONSTRAINT prescripcion_hecho_clase_check CHECK (((clase)::text = ANY ((ARRAY['INTERRUPCION'::character varying, 'SUSPENSION'::character varying])::text[])));
ALTER TABLE prescripcion_hecho ADD CONSTRAINT prescripcion_hecho_fechas_ck CHECK (((((clase)::text = 'INTERRUPCION'::text) AND (fecha_hasta IS NULL)) OR (((clase)::text = 'SUSPENSION'::text) AND (fecha_hasta IS NOT NULL) AND (fecha_hasta >= fecha_desde))));
ALTER TABLE prescripcion_hecho ADD CONSTRAINT prescripcion_hecho_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_fiscalizacion_criterio_check CHECK (((criterio)::text = ANY ((ARRAY['CONFORME'::character varying, 'OMISO'::character varying, 'SUBVALUADOR'::character varying, 'USO_DISTINTO'::character varying, 'NO_UBICADO'::character varying])::text[])));
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_fiscalizacion_estado_check CHECK (((estado)::text = ANY ((ARRAY['ABIERTO'::character varying, 'EN_PROCESO'::character varying, 'CERRADO'::character varying])::text[])));
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_fiscalizacion_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['PREDIAL'::character varying, 'VEHICULAR'::character varying])::text[])));
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_condicion_check CHECK (((condicion)::text = ANY ((ARRAY['CONFORME'::character varying, 'OMISO'::character varying, 'SUBVALUADOR'::character varying, 'USO_DISTINTO'::character varying, 'NO_UBICADO'::character varying])::text[])));
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_uq UNIQUE (municipalidad_id, programa_id, predio_id);
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
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_liquidacion_uq UNIQUE (municipalidad_id, liquidacion_id);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_unidad_ck CHECK (((predio_id IS NOT NULL) <> (vehiculo_id IS NOT NULL)));
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_version_ck CHECK ((((predio_id IS NOT NULL) = (ficha_nueva_id IS NOT NULL)) AND ((ficha_anterior_id IS NULL) = (ficha_nueva_id IS NULL))));
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_versiones_distintas_ck CHECK (((ficha_anterior_id IS NULL) OR (ficha_anterior_id <> ficha_nueva_id)));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_documento_uq UNIQUE (municipalidad_id, documento_id);
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_efecto_check CHECK (((efecto)::text = ANY ((ARRAY['SE_MANTIENE'::character varying, 'SE_DEJA_SIN_EFECTO'::character varying, 'SE_REDUCE'::character varying])::text[])));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_fallo_ck CHECK (((descargo_id IS NOT NULL) = ((sentido IS NOT NULL) AND (efecto IS NOT NULL))));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_numero_uq UNIQUE (municipalidad_id, numero);
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_plazo_ck CHECK (((ordinaria_exigible_desde IS NULL) OR (fecha >= ordinaria_exigible_desde)));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_sentido_check CHECK (((sentido)::text = ANY ((ARRAY['FUNDADO'::character varying, 'FUNDADO_EN_PARTE'::character varying, 'INFUNDADO'::character varying, 'IMPROCEDENTE'::character varying])::text[])));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_sustento_ck CHECK ((((tipo)::text = 'SANCIONADORA'::text) = ((ordinaria_notificacion_id IS NOT NULL) AND (ordinaria_exigible_desde IS NOT NULL))));
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['ORDINARIA'::character varying, 'SANCIONADORA'::character varying, 'ADMINISTRATIVA'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_pkey PRIMARY KEY (id);
ALTER TABLE respaldo ADD CONSTRAINT respaldo_resultado_check CHECK (((resultado)::text = ANY ((ARRAY['EN_CURSO'::character varying, 'EXITOSO'::character varying, 'FALLIDO'::character varying])::text[])));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_tamano_bytes_check CHECK (((tamano_bytes IS NULL) OR (tamano_bytes >= 0)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_terminado_ck CHECK ((((resultado)::text = 'EN_CURSO'::text) OR (fin IS NOT NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_completa_ck CHECK (((ultima_restauracion_verificada IS NULL) = (ultima_restauracion_verificada_por IS NULL)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_exitosa_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((resultado)::text = 'EXITOSO'::text)));
ALTER TABLE respaldo ADD CONSTRAINT respaldo_verificacion_posterior_ck CHECK (((ultima_restauracion_verificada IS NULL) OR ((fin IS NOT NULL) AND (ultima_restauracion_verificada >= fin))));
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_distinto_ck CHECK ((responsable_id <> contribuyente_id));
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_solidario_vinculo_check CHECK (((vinculo)::text = ANY ((ARRAY['CONYUGE'::character varying, 'CONDOMINO'::character varying, 'POSEEDOR'::character varying, 'REPRESENTANTE'::character varying])::text[])));
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE saldo_proyectado ADD CONSTRAINT saldo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE sector ADD CONSTRAINT sector_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE sector ADD CONSTRAINT sector_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE sesion ADD CONSTRAINT sesion_fechas_ck CHECK (((fin IS NULL) OR (fin >= inicio)));
ALTER TABLE sesion ADD CONSTRAINT sesion_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_codigo_uq UNIQUE (municipalidad_id, codigo, vigencia_desde);
ALTER TABLE tasa ADD CONSTRAINT tasa_importe_check CHECK (((importe)::numeric >= (0)::numeric));
ALTER TABLE tasa ADD CONSTRAINT tasa_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_cantidad_hectareas_check CHECK ((cantidad_hectareas > (0)::numeric));
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_cantidad_hectareas_comun_check CHECK ((cantidad_hectareas_comun >= (0)::numeric));
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_rural_riego_check CHECK (((riego)::text = ANY ((ARRAY['BAJO_RIEGO'::character varying, 'SECANO'::character varying])::text[])));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_condicion_check CHECK (((condicion)::text = ANY ((ARRAY['PROPIETARIO_UNICO'::character varying, 'COPROPIETARIO'::character varying, 'CONYUGE'::character varying, 'POSEEDOR'::character varying, 'SUCESION'::character varying, 'USUFRUCTUARIO'::character varying])::text[])));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE titularidad ADD CONSTRAINT titularidad_unico_ck CHECK ((((condicion)::text <> 'PROPIETARIO_UNICO'::text) OR ((porcentaje)::numeric = (100)::numeric)));
ALTER TABLE titularidad ADD CONSTRAINT titularidad_vigencias_no_se_pisan EXCLUDE USING gist (municipalidad_id WITH =, predio_id WITH =, contribuyente_id WITH =, daterange(vigencia_desde, COALESCE(vigencia_hasta, 'infinity'::date), '[]'::text) WITH &&) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE transferencia ADD CONSTRAINT transferencia_objeto_check CHECK (((objeto)::text = ANY ((ARRAY['PREDIO'::character varying, 'VEHICULO'::character varying])::text[])));
ALTER TABLE transferencia ADD CONSTRAINT transferencia_objeto_ck CHECK (((((objeto)::text = 'PREDIO'::text) AND (predio_id IS NOT NULL) AND (vehiculo_id IS NULL)) OR (((objeto)::text = 'VEHICULO'::text) AND (vehiculo_id IS NOT NULL) AND (predio_id IS NULL))));
ALTER TABLE transferencia ADD CONSTRAINT transferencia_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_tipo_ck CHECK (((tipo_transferencia)::text = ANY ((ARRAY['COMPRA_VENTA'::character varying, 'DONACION'::character varying, 'PERMUTA'::character varying, 'ANTICIPO_DE_LEGITIMA'::character varying, 'ADJUDICACION'::character varying, 'DACION_EN_PAGO'::character varying, 'SUCESION'::character varying, 'REMATE'::character varying, 'HERENCIA'::character varying])::text[]))) NOT VALID NOT VALID;
ALTER TABLE transferencia ADD CONSTRAINT transferencia_valor_transferencia_check CHECK (((valor_transferencia)::numeric >= (0)::numeric));
ALTER TABLE usuario ADD CONSTRAINT usuario_cuenta_uq UNIQUE (municipalidad_id, cuenta);
ALTER TABLE usuario ADD CONSTRAINT usuario_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_sujeto_uq UNIQUE (municipalidad_id, sujeto_oidc);
ALTER TABLE usuario ADD CONSTRAINT usuario_vigencia_ck CHECK (((vigencia_hasta IS NULL) OR (vigencia_desde IS NULL) OR (vigencia_hasta >= vigencia_desde)));
ALTER TABLE valor ADD CONSTRAINT valor_estado_check CHECK (((estado)::text = ANY ((ARRAY['EMITIDO'::character varying, 'NOTIFICADO'::character varying, 'COACTIVA'::character varying, 'PAGADO'::character varying, 'ANULADO'::character varying, 'PRESCRITO'::character varying])::text[])));
ALTER TABLE valor ADD CONSTRAINT valor_monto_total_check CHECK (((monto_total)::numeric >= (0)::numeric));
ALTER TABLE valor ADD CONSTRAINT valor_numero_uq UNIQUE (municipalidad_id, tipo, numero);
ALTER TABLE valor ADD CONSTRAINT valor_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE valor ADD CONSTRAINT valor_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['OP'::character varying, 'RD'::character varying, 'RM'::character varying])::text[])));
ALTER TABLE valor_correlativo ADD CONSTRAINT valor_correlativo_pk PRIMARY KEY (municipalidad_id, tipo, ejercicio);
ALTER TABLE valor_correlativo ADD CONSTRAINT valor_correlativo_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['OP'::character varying, 'RD'::character varying, 'RM'::character varying])::text[])));
ALTER TABLE valor_correlativo ADD CONSTRAINT valor_correlativo_ultimo_check CHECK ((ultimo >= 0));
ALTER TABLE valor_detalle ADD CONSTRAINT valor_detalle_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_ejercicios_ck CHECK (((ejercicio_desde)::smallint <= (ejercicio_hasta)::smallint));
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_origen_check CHECK (((origen)::text = ANY ((ARRAY['SELECCION'::character varying, 'IMPORTACION'::character varying])::text[])));
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['OP'::character varying, 'RD'::character varying, 'RM'::character varying])::text[])));
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_total_candidatos_check CHECK ((total_candidatos >= 0));
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_estado_check CHECK (((estado)::text = ANY ((ARRAY['PENDIENTE'::character varying, 'GENERADO'::character varying, 'SIN_DEUDA'::character varying])::text[])));
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_uq UNIQUE (municipalidad_id, corrida_id, contribuyente_id);
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_valor_ck CHECK (((((estado)::text = 'GENERADO'::text) AND (valor_id IS NOT NULL)) OR (((estado)::text <> 'GENERADO'::text) AND (valor_id IS NULL))));
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_exigible_ck CHECK ((fecha >= exigible_desde));
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['PCO'::character varying, 'ACO'::character varying, 'RCO'::character varying])::text[])));
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
ALTER TABLE vehiculo ADD CONSTRAINT vehiculo_estado_check CHECK (((estado)::text = ANY ((ARRAY['ACTIVO'::character varying, 'TRANSFERIDO'::character varying, 'BAJA'::character varying, 'ROBADO'::character varying])::text[])));
ALTER TABLE vehiculo ADD CONSTRAINT vehiculo_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE via ADD CONSTRAINT via_codigo_uq UNIQUE (municipalidad_id, codigo);
ALTER TABLE via ADD CONSTRAINT via_pk PRIMARY KEY (municipalidad_id, id);
ALTER TABLE acceso ADD CONSTRAINT acceso_modulo_fk FOREIGN KEY (municipalidad_id, modulo_id) REFERENCES modulo_sistema(municipalidad_id, id);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_programa_fk FOREIGN KEY (municipalidad_id, programa_id) REFERENCES programa_fiscalizacion(municipalidad_id, id);
ALTER TABLE acta_fiscalizacion ADD CONSTRAINT acta_fisc_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE actividad_economica ADD CONSTRAINT actividad_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_expediente_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE acto_coactivo ADD CONSTRAINT acto_rec1_notificacion_fk FOREIGN KEY (municipalidad_id, rec1_notificacion_id) REFERENCES notificacion(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE anuncio ADD CONSTRAINT anuncio_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE anuncio ADD CONSTRAINT anuncio_licencia_fk FOREIGN KEY (municipalidad_id, licencia_id) REFERENCES licencia_funcionamiento(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE anuncio ADD CONSTRAINT anuncio_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE anuncio ADD CONSTRAINT anuncio_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE anuncio_correlativo ADD CONSTRAINT anuncio_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_anuncio_fk FOREIGN KEY (municipalidad_id, anuncio_id) REFERENCES anuncio(municipalidad_id, id);
ALTER TABLE anuncio_movimiento ADD CONSTRAINT anuncio_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE arancel ADD CONSTRAINT arancel_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE arancel ADD CONSTRAINT arancel_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE arancel ADD CONSTRAINT arancel_via_fk FOREIGN KEY (municipalidad_id, via_id) REFERENCES via(municipalidad_id, id);
ALTER TABLE area ADD CONSTRAINT area_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE beneficio ADD CONSTRAINT beneficio_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE beneficio ADD CONSTRAINT beneficio_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE beneficio ADD CONSTRAINT beneficio_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE bien_comun ADD CONSTRAINT bien_comun_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_area_fk FOREIGN KEY (municipalidad_id, area_id) REFERENCES area(municipalidad_id, id);
ALTER TABLE caja ADD CONSTRAINT caja_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE certificado ADD CONSTRAINT certificado_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE certificado ADD CONSTRAINT certificado_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id);
ALTER TABLE certificado ADD CONSTRAINT certificado_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE certificado ADD CONSTRAINT certificado_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE certificado ADD CONSTRAINT certificado_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE certificado_correlativo ADD CONSTRAINT certificado_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
ALTER TABLE cierre_caja ADD CONSTRAINT cierre_caja_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_revierte_fk FOREIGN KEY (municipalidad_id, revierte_a_id) REFERENCES cierre_turno(municipalidad_id, id);
ALTER TABLE cierre_turno ADD CONSTRAINT cierre_turno_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id);
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_cierre_fk FOREIGN KEY (municipalidad_id, cierre_id) REFERENCES cierre_turno(municipalidad_id, id);
ALTER TABLE cierre_turno_detalle ADD CONSTRAINT cierre_turno_detalle_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE ciiu ADD CONSTRAINT ciiu_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE codigo_infraccion ADD CONSTRAINT codigo_infraccion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE colindante_rural ADD CONSTRAINT colindante_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_detalle_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE conjunto_parametro_detalle ADD CONSTRAINT conjunto_parametro_detalle_parametro_id_fkey FOREIGN KEY (parametro_id) REFERENCES parametro_tributario(id);
ALTER TABLE conjunto_parametros ADD CONSTRAINT conjunto_parametros_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_solicitante_fk FOREIGN KEY (municipalidad_id, solicitante_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE constancia_libre ADD CONSTRAINT constancia_libre_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE construccion ADD CONSTRAINT construccion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE contacto ADD CONSTRAINT contacto_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_conyuge_fk FOREIGN KEY (municipalidad_id, conyuge_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE contribuyente ADD CONSTRAINT contribuyente_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE convenio ADD CONSTRAINT convenio_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE convenio ADD CONSTRAINT convenio_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE convenio ADD CONSTRAINT convenio_origen_fk FOREIGN KEY (municipalidad_id, convenio_origen_id) REFERENCES convenio(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE convenio_correlativo ADD CONSTRAINT convenio_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_convenio_fk FOREIGN KEY (municipalidad_id, convenio_id) REFERENCES convenio(municipalidad_id, id);
ALTER TABLE convenio_cuota ADD CONSTRAINT convenio_cuota_municipalidad_fk FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id) NOT VALID NOT VALID;
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_convenio_fk FOREIGN KEY (municipalidad_id, convenio_id) REFERENCES convenio(municipalidad_id, id);
ALTER TABLE convenio_deuda ADD CONSTRAINT convenio_deuda_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_convenio_fk FOREIGN KEY (municipalidad_id, convenio_id) REFERENCES convenio(municipalidad_id, id);
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_nuevo_fk FOREIGN KEY (municipalidad_id, convenio_nuevo_id) REFERENCES convenio(municipalidad_id, id);
ALTER TABLE convenio_movimiento ADD CONSTRAINT convenio_movimiento_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE corrida_predial ADD CONSTRAINT corrida_predial_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE corrida_predial_observado ADD CONSTRAINT corrida_predial_observado_corrida_fk FOREIGN KEY (municipalidad_id, corrida_id) REFERENCES corrida_predial(municipalidad_id, id);
ALTER TABLE costa_obligacion ADD CONSTRAINT costa_obligacion_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE costa_obligacion ADD CONSTRAINT costa_obligacion_expediente_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE costa_obligacion ADD CONSTRAINT costa_obligacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE costa_procesal ADD CONSTRAINT costa_acto_fk FOREIGN KEY (municipalidad_id, acto_id) REFERENCES acto_coactivo(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE costa_procesal ADD CONSTRAINT costa_expediente_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE costa_procesal ADD CONSTRAINT costa_liquidacion_fk FOREIGN KEY (municipalidad_id, liquidacion_id) REFERENCES liquidacion_costas(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE cuenta_corriente_asiento ADD CONSTRAINT asiento_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE declaracion_jurada ADD CONSTRAINT declaracion_jurada_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_ficha_catastral_fk FOREIGN KEY (municipalidad_id, ficha_catastral_id) REFERENCES ficha_catastral(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_rectifica_fk FOREIGN KEY (municipalidad_id, dj_rectifica_id) REFERENCES declaracion_jurada(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE declaracion_jurada ADD CONSTRAINT dj_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE depreciacion ADD CONSTRAINT depreciacion_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);
ALTER TABLE descargo ADD CONSTRAINT descargo_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE descargo ADD CONSTRAINT descargo_papeleta_fk FOREIGN KEY (municipalidad_id, papeleta_id) REFERENCES papeleta(municipalidad_id, id);
ALTER TABLE determinacion ADD CONSTRAINT determinacion_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE determinacion ADD CONSTRAINT determinacion_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE determinacion ADD CONSTRAINT determinacion_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE determinacion ADD CONSTRAINT determinacion_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT det_arbitrio_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT det_arbitrio_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE determinacion_arbitrio ADD CONSTRAINT det_arbitrio_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_determinacion_fk FOREIGN KEY (municipalidad_id, ejercicio, determinacion_id) REFERENCES determinacion(municipalidad_id, ejercicio, id);
ALTER TABLE determinacion_predio_detalle ADD CONSTRAINT det_predio_detalle_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE dj_correlativo ADD CONSTRAINT dj_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE documento_emitido ADD CONSTRAINT documento_emitido_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE domicilio ADD CONSTRAINT domicilio_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE edificacion_correlativo ADD CONSTRAINT edificacion_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_estructura ADD CONSTRAINT edificacion_estructura_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id);
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_movimiento ADD CONSTRAINT edificacion_movimiento_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_profesional ADD CONSTRAINT edificacion_profesional_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_proyecto ADD CONSTRAINT edificacion_proyecto_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_requisito ADD CONSTRAINT edificacion_requisito_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_fue_fk FOREIGN KEY (municipalidad_id, fue_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_terreno ADD CONSTRAINT edificacion_terreno_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_licencia_fk FOREIGN KEY (municipalidad_id, licencia_id) REFERENCES licencia_edificacion(municipalidad_id, id);
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_movimiento_fk FOREIGN KEY (municipalidad_id, movimiento_id) REFERENCES edificacion_movimiento(municipalidad_id, id);
ALTER TABLE edificacion_vigencia ADD CONSTRAINT edificacion_vigencia_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE espectaculo ADD CONSTRAINT espectaculo_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE espectaculo ADD CONSTRAINT espectaculo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_coactivo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE expediente_coactivo ADD CONSTRAINT expediente_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE expediente_correlativo ADD CONSTRAINT expediente_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_exp_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE expediente_movimiento ADD CONSTRAINT expediente_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE expediente_valor ADD CONSTRAINT expediente_valor_exp_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE expediente_valor ADD CONSTRAINT expediente_valor_valor_fk FOREIGN KEY (municipalidad_id, valor_id) REFERENCES valor(municipalidad_id, id);
ALTER TABLE ficha_catastral ADD CONSTRAINT ficha_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE grupo ADD CONSTRAINT grupo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE inquilino ADD CONSTRAINT inquilino_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE inquilino ADD CONSTRAINT inquilino_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE internamiento ADD CONSTRAINT internamiento_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE internamiento ADD CONSTRAINT internamiento_papeleta_fk FOREIGN KEY (municipalidad_id, papeleta_id) REFERENCES papeleta(municipalidad_id, id);
ALTER TABLE internamiento ADD CONSTRAINT internamiento_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_fk FOREIGN KEY (municipalidad_id, internamiento_id) REFERENCES internamiento(municipalidad_id, id);
ALTER TABLE internamiento_movimiento ADD CONSTRAINT internamiento_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE licencia_correlativo ADD CONSTRAINT licencia_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_fk FOREIGN KEY (municipalidad_id, licencia_id) REFERENCES licencia_funcionamiento(municipalidad_id, id);
ALTER TABLE licencia_duplicado ADD CONSTRAINT licencia_duplicado_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_origen_fk FOREIGN KEY (municipalidad_id, licencia_origen_id) REFERENCES licencia_edificacion(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE licencia_edificacion ADD CONSTRAINT edificacion_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE licencia_edificacion ADD CONSTRAINT licencia_edificacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_funcionamiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE licencia_funcionamiento ADD CONSTRAINT licencia_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE licencia_giro ADD CONSTRAINT licencia_giro_ciiu_fk FOREIGN KEY (municipalidad_id, ciiu_id) REFERENCES ciiu(municipalidad_id, id);
ALTER TABLE licencia_giro ADD CONSTRAINT licencia_giro_licencia_fk FOREIGN KEY (municipalidad_id, licencia_id) REFERENCES licencia_funcionamiento(municipalidad_id, id);
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id);
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_licencia_fk FOREIGN KEY (municipalidad_id, licencia_id) REFERENCES licencia_funcionamiento(municipalidad_id, id);
ALTER TABLE licencia_movimiento ADD CONSTRAINT licencia_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE liquidacion_correlativo ADD CONSTRAINT liquidacion_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_expediente_fk FOREIGN KEY (municipalidad_id, expediente_id) REFERENCES expediente_coactivo(municipalidad_id, id);
ALTER TABLE liquidacion_costas ADD CONSTRAINT liquidacion_costas_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE liquidacion_costas_correlativo ADD CONSTRAINT liquidacion_costas_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_liq_fk FOREIGN KEY (municipalidad_id, liquidacion_id) REFERENCES liquidacion_fiscalizacion(municipalidad_id, id);
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE liquidacion_detalle ADD CONSTRAINT liquidacion_detalle_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_acta_fk FOREIGN KEY (municipalidad_id, acta_id) REFERENCES acta_fiscalizacion(municipalidad_id, id);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_anterior_fk FOREIGN KEY (municipalidad_id, liquidacion_anterior_id) REFERENCES liquidacion_fiscalizacion(municipalidad_id, id);
ALTER TABLE liquidacion_fiscalizacion ADD CONSTRAINT liquidacion_fiscalizacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE liquidacion_movimiento ADD CONSTRAINT liquidacion_movimiento_liq_fk FOREIGN KEY (municipalidad_id, liquidacion_id) REFERENCES liquidacion_fiscalizacion(municipalidad_id, id);
ALTER TABLE manzana ADD CONSTRAINT manzana_sector_fk FOREIGN KEY (municipalidad_id, sector_id) REFERENCES sector(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE miembro ADD CONSTRAINT miembro_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE modulo_sistema ADD CONSTRAINT modulo_sistema_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE notificacion ADD CONSTRAINT notificacion_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE notificacion ADD CONSTRAINT notificacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notif_adm_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notif_adm_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE notificacion_administrativa ADD CONSTRAINT notificacion_administrativa_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE otra_instalacion ADD CONSTRAINT otra_instalacion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_codigo_fk FOREIGN KEY (municipalidad_id, codigo_infraccion_id) REFERENCES codigo_infraccion(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_infractor_fk FOREIGN KEY (municipalidad_id, infractor_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_notificacion_fk FOREIGN KEY (municipalidad_id, notificacion_previa_id) REFERENCES notificacion_administrativa(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_obligado_fk FOREIGN KEY (municipalidad_id, obligado_id) REFERENCES contribuyente(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE papeleta ADD CONSTRAINT papeleta_propietario_fk FOREIGN KEY (municipalidad_id, propietario_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE papeleta ADD CONSTRAINT papeleta_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE papeleta_cambio_numero ADD CONSTRAINT papeleta_cambio_fk FOREIGN KEY (municipalidad_id, papeleta_id) REFERENCES papeleta(municipalidad_id, id);
ALTER TABLE papeleta_masivo ADD CONSTRAINT papeleta_masivo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_corrida_fk FOREIGN KEY (municipalidad_id, corrida_id) REFERENCES papeleta_masivo(municipalidad_id, id);
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_papeleta_fk FOREIGN KEY (municipalidad_id, papeleta_id) REFERENCES papeleta(municipalidad_id, id);
ALTER TABLE papeleta_masivo_item ADD CONSTRAINT papeleta_masivo_item_valor_fk FOREIGN KEY (municipalidad_id, valor_id) REFERENCES valor(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE parametro_tributario ADD CONSTRAINT parametro_tributario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE participacion_comun ADD CONSTRAINT participacion_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_acceso_fk FOREIGN KEY (municipalidad_id, acceso_id) REFERENCES acceso(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_grupo_fk FOREIGN KEY (municipalidad_id, grupo_id) REFERENCES grupo(municipalidad_id, id);
ALTER TABLE permiso ADD CONSTRAINT permiso_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_manzana_fk FOREIGN KEY (municipalidad_id, manzana_id) REFERENCES manzana(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE predio ADD CONSTRAINT predio_sector_fk FOREIGN KEY (municipalidad_id, sector_id) REFERENCES sector(municipalidad_id, id);
ALTER TABLE predio ADD CONSTRAINT predio_via_fk FOREIGN KEY (municipalidad_id, via_id) REFERENCES via(municipalidad_id, id);
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id);
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE prescripcion ADD CONSTRAINT prescripcion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE prescripcion_ejercicio ADD CONSTRAINT prescripcion_ejercicio_fk FOREIGN KEY (municipalidad_id, prescripcion_id) REFERENCES prescripcion(municipalidad_id, id);
ALTER TABLE prescripcion_hecho ADD CONSTRAINT prescripcion_hecho_fk FOREIGN KEY (municipalidad_id, prescripcion_id) REFERENCES prescripcion(municipalidad_id, id);
ALTER TABLE programa_fiscalizacion ADD CONSTRAINT programa_fiscalizacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE programa_muestra ADD CONSTRAINT programa_muestra_programa_fk FOREIGN KEY (municipalidad_id, programa_id) REFERENCES programa_fiscalizacion(municipalidad_id, id);
ALTER TABLE recibo ADD CONSTRAINT recibo_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
ALTER TABLE recibo ADD CONSTRAINT recibo_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE recibo ADD CONSTRAINT recibo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo ADD CONSTRAINT recibo_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE recibo_correlativo ADD CONSTRAINT recibo_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE recibo_detalle ADD CONSTRAINT recibo_detalle_tasa_fk FOREIGN KEY (municipalidad_id, tasa_id) REFERENCES tasa(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_caja_fk FOREIGN KEY (municipalidad_id, caja_id) REFERENCES caja(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_recibo_fk FOREIGN KEY (municipalidad_id, recibo_id) REFERENCES recibo(municipalidad_id, id);
ALTER TABLE recibo_movimiento ADD CONSTRAINT recibo_movimiento_turno_fk FOREIGN KEY (municipalidad_id, turno_id) REFERENCES cierre_caja(municipalidad_id, id);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_ficha_anterior_fk FOREIGN KEY (municipalidad_id, ficha_anterior_id) REFERENCES ficha_catastral(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_ficha_nueva_fk FOREIGN KEY (municipalidad_id, ficha_nueva_id) REFERENCES ficha_catastral(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_liquidacion_fk FOREIGN KEY (municipalidad_id, liquidacion_id) REFERENCES liquidacion_fiscalizacion(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_determinacion ADD CONSTRAINT resolucion_determinacion_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_descargo_fk FOREIGN KEY (municipalidad_id, descargo_id) REFERENCES descargo(municipalidad_id, id);
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_documento_fk FOREIGN KEY (municipalidad_id, documento_id) REFERENCES documento_emitido(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_notificacion_fk FOREIGN KEY (municipalidad_id, ordinaria_notificacion_id) REFERENCES notificacion(municipalidad_id, id) NOT VALID NOT VALID;
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_papeleta_fk FOREIGN KEY (municipalidad_id, papeleta_id) REFERENCES papeleta(municipalidad_id, id);
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE responsable_solidario ADD CONSTRAINT responsable_responsable_fk FOREIGN KEY (municipalidad_id, responsable_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE saldo_proyectado ADD CONSTRAINT saldo_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE saldo_proyectado ADD CONSTRAINT saldo_proyectado_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE sector ADD CONSTRAINT sector_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE sesion ADD CONSTRAINT sesion_usuario_fk FOREIGN KEY (municipalidad_id, usuario_id) REFERENCES usuario(municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_area_fk FOREIGN KEY (municipalidad_id, area_id) REFERENCES area(municipalidad_id, id);
ALTER TABLE tasa ADD CONSTRAINT tasa_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE tierra_rural ADD CONSTRAINT tierra_ficha_fk FOREIGN KEY (municipalidad_id, ficha_id) REFERENCES ficha_catastral(municipalidad_id, id);
ALTER TABLE titularidad ADD CONSTRAINT titularidad_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE titularidad ADD CONSTRAINT titularidad_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_adquiriente_fk FOREIGN KEY (municipalidad_id, adquiriente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_predio_fk FOREIGN KEY (municipalidad_id, predio_id) REFERENCES predio(municipalidad_id, id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_transferente_fk FOREIGN KEY (municipalidad_id, transferente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE transferencia ADD CONSTRAINT transferencia_vehiculo_fk FOREIGN KEY (municipalidad_id, vehiculo_id) REFERENCES vehiculo(municipalidad_id, id);
ALTER TABLE usuario ADD CONSTRAINT usuario_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor ADD CONSTRAINT valor_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE valor ADD CONSTRAINT valor_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_correlativo ADD CONSTRAINT valor_correlativo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_detalle ADD CONSTRAINT valor_detalle_valor_fk FOREIGN KEY (municipalidad_id, valor_id) REFERENCES valor(municipalidad_id, id);
ALTER TABLE valor_masivo ADD CONSTRAINT valor_masivo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_corrida_fk FOREIGN KEY (municipalidad_id, corrida_id) REFERENCES valor_masivo(municipalidad_id, id);
ALTER TABLE valor_masivo_item ADD CONSTRAINT valor_masivo_item_valor_fk FOREIGN KEY (municipalidad_id, valor_id) REFERENCES valor(municipalidad_id, id);
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_notificacion_fk FOREIGN KEY (municipalidad_id, notificacion_id) REFERENCES notificacion(municipalidad_id, id);
ALTER TABLE valor_movimiento ADD CONSTRAINT valor_movimiento_valor_fk FOREIGN KEY (municipalidad_id, valor_id) REFERENCES valor(municipalidad_id, id);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);
ALTER TABLE valor_referencial_vehiculo ADD CONSTRAINT valor_referencial_vehiculo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_edificacion_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE valor_unitario_edificacion ADD CONSTRAINT valor_unitario_publicacion_fk FOREIGN KEY (publicacion_id) REFERENCES parametro_tributario(id);
ALTER TABLE vehiculo ADD CONSTRAINT vehiculo_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id);
ALTER TABLE vehiculo ADD CONSTRAINT vehiculo_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);
ALTER TABLE via ADD CONSTRAINT via_municipalidad_id_fkey FOREIGN KEY (municipalidad_id) REFERENCES municipalidad(id);

-- ==========================================================================
--  5. INDICES
--  Empezando por municipalidad_id. Los de una tabla particionada se propagan
--  solos a sus particiones, asi que aqui no se repiten.
-- ==========================================================================

CREATE INDEX acceso_modulo_ix ON public.acceso USING btree (municipalidad_id, modulo_id, tipo);
CREATE INDEX acta_fisc_contribuyente_ix ON public.acta_fiscalizacion USING btree (municipalidad_id, contribuyente_id, estado);
CREATE INDEX actividad_ficha_ix ON public.actividad_economica USING btree (municipalidad_id, ficha_id);
CREATE INDEX actividad_licencia_ix ON public.actividad_economica USING btree (municipalidad_id, licencia_numero);
CREATE INDEX acto_expediente_ix ON public.acto_coactivo USING btree (municipalidad_id, expediente_id, fecha);
CREATE UNIQUE INDEX acto_rec1_uq ON public.acto_coactivo USING btree (municipalidad_id, expediente_id) WHERE ((tipo)::text = 'REC1'::text);
CREATE INDEX acto_tipo_ix ON public.acto_coactivo USING btree (municipalidad_id, expediente_id, tipo);
CREATE INDEX anuncio_contribuyente_ix ON public.anuncio USING btree (municipalidad_id, contribuyente_id, fecha_autorizacion);
CREATE INDEX anuncio_expediente_ix ON public.anuncio USING btree (municipalidad_id, expediente text_pattern_ops) WHERE (expediente IS NOT NULL);
CREATE UNIQUE INDEX anuncio_idempotencia_uq ON public.anuncio USING btree (municipalidad_id, clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);
CREATE INDEX anuncio_licencia_ix ON public.anuncio USING btree (municipalidad_id, licencia_id) WHERE (licencia_id IS NOT NULL);
CREATE INDEX anuncio_ubicacion_ix ON public.anuncio USING btree (municipalidad_id, ubicacion text_pattern_ops);
CREATE INDEX anuncio_movimiento_anuncio_ix ON public.anuncio_movimiento USING btree (municipalidad_id, anuncio_id, fecha);
CREATE UNIQUE INDEX anuncio_movimiento_autorizacion_uq ON public.anuncio_movimiento USING btree (municipalidad_id, anuncio_id) WHERE ((tipo)::text = 'AUTORIZACION'::text);
CREATE UNIQUE INDEX anuncio_movimiento_cargo_uq ON public.anuncio_movimiento USING btree (municipalidad_id, referencia_cargo) WHERE (referencia_cargo IS NOT NULL);
CREATE UNIQUE INDEX anuncio_movimiento_cese_uq ON public.anuncio_movimiento USING btree (municipalidad_id, anuncio_id) WHERE ((tipo)::text = 'CESE'::text);
CREATE UNIQUE INDEX anuncio_movimiento_retiro_uq ON public.anuncio_movimiento USING btree (municipalidad_id, anuncio_id) WHERE ((tipo)::text = 'RETIRO'::text);
CREATE UNIQUE INDEX arancel_sin_tramo_uq ON public.arancel USING btree (municipalidad_id, conjunto_id, via_id) WHERE (tramo IS NULL);
CREATE INDEX auditoria_tabla_ix ON public.auditoria USING btree (municipalidad_id, tabla, clave);
CREATE INDEX auditoria_usuario_ix ON public.auditoria USING btree (municipalidad_id, usuario_id, fecha);
CREATE INDEX beneficio_vigente_ix ON public.beneficio USING btree (municipalidad_id, contribuyente_id, tributo) WHERE (vigencia_hasta IS NULL);
CREATE INDEX bien_comun_ficha_ix ON public.bien_comun USING btree (municipalidad_id, ficha_id);
CREATE INDEX certificado_codigo_predial_ix ON public.certificado USING btree (municipalidad_id, codigo_predial text_pattern_ops);
CREATE INDEX certificado_contribuyente_ix ON public.certificado USING btree (municipalidad_id, contribuyente_id, fecha_emision);
CREATE UNIQUE INDEX certificado_idempotencia_uq ON public.certificado USING btree (municipalidad_id, clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);
CREATE INDEX certificado_predio_ix ON public.certificado USING btree (municipalidad_id, predio_id, fecha_emision);
CREATE INDEX certificado_tipo_ix ON public.certificado USING btree (municipalidad_id, tipo, fecha_emision);
CREATE UNIQUE INDEX cierre_turno_reversion_uq ON public.cierre_turno USING btree (municipalidad_id, revierte_a_id) WHERE ((tipo)::text = 'REVERSION'::text);
CREATE INDEX cierre_turno_turno_ix ON public.cierre_turno USING btree (municipalidad_id, turno_id, id DESC);
CREATE INDEX ciiu_descripcion_ix ON public.ciiu USING btree (municipalidad_id, descripcion text_pattern_ops);
CREATE UNIQUE INDEX codigo_infraccion_vigente_uq ON public.codigo_infraccion USING btree (municipalidad_id, familia, codigo) WHERE (vigencia_hasta IS NULL);
CREATE INDEX colindante_ficha_ix ON public.colindante_rural USING btree (municipalidad_id, ficha_id);
CREATE INDEX conjunto_sellado_vigente_ix ON public.conjunto_parametros USING btree (municipalidad_id, ejercicio, version DESC) WHERE ((estado)::text = 'SELLADO'::text);
CREATE INDEX constancia_libre_fecha_ix ON public.constancia_libre USING btree (municipalidad_id, fecha_emision);
CREATE INDEX constancia_libre_placa_ix ON public.constancia_libre USING btree (municipalidad_id, placa);
CREATE INDEX constancia_libre_usuario_ix ON public.constancia_libre USING btree (municipalidad_id, usuario_registro, fecha_emision);
CREATE INDEX construccion_ficha_ix ON public.construccion USING btree (municipalidad_id, ficha_id);
CREATE INDEX contribuyente_documento_ix ON public.contribuyente USING btree (municipalidad_id, numero_documento);
CREATE INDEX contribuyente_nombre_ix ON public.contribuyente USING btree (municipalidad_id, nombre_razon_social);
CREATE INDEX contribuyente_nombre_trgm_ix ON public.contribuyente USING gin (nombre_normalizado((nombre_razon_social)::text) gin_trgm_ops);
CREATE INDEX contribuyente_numero_documento_ix ON public.contribuyente USING btree (numero_documento);
CREATE INDEX convenio_contribuyente_ix ON public.convenio USING btree (municipalidad_id, contribuyente_id);
CREATE INDEX convenio_fecha_ix ON public.convenio USING btree (municipalidad_id, fecha);
CREATE UNIQUE INDEX convenio_idempotencia_uq ON public.convenio USING btree (municipalidad_id, clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);
CREATE INDEX convenio_deuda_convenio_ix ON public.convenio_deuda USING btree (municipalidad_id, convenio_id);
CREATE UNIQUE INDEX convenio_deuda_uq ON public.convenio_deuda USING btree (municipalidad_id, convenio_id, tributo, ejercicio, periodo, COALESCE(predio_id, (0)::bigint), COALESCE(vehiculo_id, (0)::bigint));
CREATE UNIQUE INDEX convenio_movimiento_cierre_uq ON public.convenio_movimiento USING btree (municipalidad_id, convenio_id) WHERE ((tipo)::text = ANY ((ARRAY['ANULACION'::character varying, 'QUIEBRE'::character varying, 'REFORMULACION'::character varying])::text[]));
CREATE INDEX convenio_movimiento_convenio_ix ON public.convenio_movimiento USING btree (municipalidad_id, convenio_id, tipo);
CREATE UNIQUE INDEX convenio_movimiento_formalizacion_uq ON public.convenio_movimiento USING btree (municipalidad_id, convenio_id) WHERE ((tipo)::text = 'FORMALIZACION'::text);
CREATE UNIQUE INDEX convenio_movimiento_idempotencia_uq ON public.convenio_movimiento USING btree (municipalidad_id, clave_idempotencia) WHERE (clave_idempotencia IS NOT NULL);
CREATE INDEX corrida_predial_ejercicio_ix ON public.corrida_predial USING btree (municipalidad_id, ejercicio, id DESC);
CREATE INDEX corrida_predial_observado_corrida_ix ON public.corrida_predial_observado USING btree (municipalidad_id, corrida_id, id);
CREATE INDEX costa_obligacion_expediente_ix ON public.costa_obligacion USING btree (municipalidad_id, expediente_id);
CREATE UNIQUE INDEX costa_acto_uq ON public.costa_procesal USING btree (municipalidad_id, acto_id);
CREATE INDEX costa_liquidacion_ix ON public.costa_procesal USING btree (municipalidad_id, liquidacion_id);
CREATE UNIQUE INDEX asiento_alta_unica_uq ON public.cuenta_corriente_asiento USING btree (municipalidad_id, ejercicio, contribuyente_id, tributo, COALESCE((periodo)::integer, 0), COALESCE(predio_id, (0)::bigint), COALESCE(vehiculo_id, (0)::bigint), documento_origen, concepto) WHERE (((acto)::text = 'ALTA_DEUDA'::text) AND (asiento_reversado_id IS NULL));
CREATE INDEX asiento_deudor_ix ON public.cuenta_corriente_asiento USING btree (municipalidad_id, contribuyente_id, tributo, ejercicio);
CREATE INDEX asiento_documento_origen_ix ON public.cuenta_corriente_asiento USING btree (municipalidad_id, documento_origen);
CREATE INDEX asiento_predio_ix ON public.cuenta_corriente_asiento USING btree (municipalidad_id, predio_id) WHERE (predio_id IS NOT NULL);
CREATE INDEX asiento_referencia_ix ON public.cuenta_corriente_asiento USING btree (municipalidad_id, referencia_externa) WHERE (referencia_externa IS NOT NULL);
CREATE INDEX asiento_reversado_ix ON public.cuenta_corriente_asiento USING btree (municipalidad_id, asiento_reversado_id) WHERE (asiento_reversado_id IS NOT NULL);
CREATE INDEX dj_contribuyente_ix ON public.declaracion_jurada USING btree (municipalidad_id, contribuyente_id, ejercicio);
CREATE INDEX dj_ejercicio_predio_ix ON public.declaracion_jurada USING btree (municipalidad_id, ejercicio, predio_id) WHERE (predio_id IS NOT NULL);
CREATE UNIQUE INDEX dj_rectifica_uq ON public.declaracion_jurada USING btree (municipalidad_id, dj_rectifica_id) WHERE (dj_rectifica_id IS NOT NULL);
CREATE INDEX descargo_papeleta_ix ON public.descargo USING btree (municipalidad_id, papeleta_id, fecha);
CREATE INDEX determinacion_contribuyente_ix ON public.determinacion USING btree (municipalidad_id, contribuyente_id, tributo);
CREATE INDEX determinacion_predio_ix ON public.determinacion USING btree (municipalidad_id, predio_id, tributo);
CREATE INDEX documento_referencia_ix ON public.documento_emitido USING btree (municipalidad_id, tipo, referencia);
CREATE UNIQUE INDEX domicilio_fiscal_vigente_uq ON public.domicilio USING btree (municipalidad_id, contribuyente_id) WHERE (((tipo)::text = 'FISCAL'::text) AND (vigencia_hasta IS NULL));
CREATE INDEX edificacion_estructura_fue_ix ON public.edificacion_estructura USING btree (municipalidad_id, fue_id, version);
CREATE UNIQUE INDEX edificacion_movimiento_anulacion_uq ON public.edificacion_movimiento USING btree (municipalidad_id, fue_id) WHERE ((tipo)::text = 'ANULACION'::text);
CREATE UNIQUE INDEX edificacion_movimiento_emision_uq ON public.edificacion_movimiento USING btree (municipalidad_id, fue_id) WHERE ((tipo)::text = 'EMISION'::text);
CREATE INDEX edificacion_movimiento_fue_ix ON public.edificacion_movimiento USING btree (municipalidad_id, fue_id, fecha);
CREATE UNIQUE INDEX edificacion_numero_licencia_uq ON public.edificacion_movimiento USING btree (municipalidad_id, numero_licencia) WHERE (numero_licencia IS NOT NULL);
CREATE INDEX edificacion_profesional_fue_ix ON public.edificacion_profesional USING btree (municipalidad_id, fue_id, version);
CREATE INDEX edificacion_requisito_fue_ix ON public.edificacion_requisito USING btree (municipalidad_id, fue_id, version);
CREATE INDEX edificacion_terreno_lote_ix ON public.edificacion_terreno USING btree (municipalidad_id, lote text_pattern_ops);
CREATE INDEX edificacion_terreno_manzana_ix ON public.edificacion_terreno USING btree (municipalidad_id, manzana text_pattern_ops);
CREATE INDEX edificacion_vigencia_licencia_ix ON public.edificacion_vigencia USING btree (municipalidad_id, licencia_id, orden);
CREATE INDEX expediente_contribuyente_ix ON public.expediente_coactivo USING btree (municipalidad_id, contribuyente_id);
CREATE INDEX expediente_ejercicio_ix ON public.expediente_coactivo USING btree (municipalidad_id, ejercicio, correlativo);
CREATE UNIQUE INDEX expediente_movimiento_apertura_uq ON public.expediente_movimiento USING btree (municipalidad_id, expediente_id) WHERE ((tipo)::text = 'APERTURA'::text);
CREATE INDEX expediente_movimiento_exp_ix ON public.expediente_movimiento USING btree (municipalidad_id, expediente_id, id);
CREATE UNIQUE INDEX expediente_valor_unico_uq ON public.expediente_valor USING btree (municipalidad_id, valor_id);
CREATE INDEX expediente_valor_valor_ix ON public.expediente_valor USING btree (municipalidad_id, valor_id);
CREATE INDEX ficha_predio_ix ON public.ficha_catastral USING btree (municipalidad_id, predio_id, tipo);
CREATE INDEX ficha_vigencia_ix ON public.ficha_catastral USING btree (municipalidad_id, vigencia_desde, tipo) WHERE (vigencia_hasta IS NULL);
CREATE UNIQUE INDEX ficha_vigente_uq ON public.ficha_catastral USING btree (municipalidad_id, predio_id, tipo) WHERE (vigencia_hasta IS NULL);
CREATE INDEX internamiento_deposito_ix ON public.internamiento USING btree (municipalidad_id, deposito, fecha_ingreso);
CREATE INDEX internamiento_placa_ix ON public.internamiento USING btree (municipalidad_id, placa);
CREATE UNIQUE INDEX internamiento_liberacion_uq ON public.internamiento_movimiento USING btree (municipalidad_id, internamiento_id) WHERE ((tipo)::text = 'LIBERACION'::text);
CREATE INDEX internamiento_movimiento_ix ON public.internamiento_movimiento USING btree (municipalidad_id, internamiento_id, fecha);
CREATE INDEX licencia_duplicado_fecha_ix ON public.licencia_duplicado USING btree (municipalidad_id, fecha);
CREATE INDEX edificacion_contribuyente_ix ON public.licencia_edificacion USING btree (municipalidad_id, contribuyente_id, fecha_declaracion);
CREATE INDEX edificacion_origen_ix ON public.licencia_edificacion USING btree (municipalidad_id, licencia_origen_id) WHERE (licencia_origen_id IS NOT NULL);
CREATE INDEX edificacion_predio_ix ON public.licencia_edificacion USING btree (municipalidad_id, predio_id) WHERE (predio_id IS NOT NULL);
CREATE INDEX licencia_contribuyente_ix ON public.licencia_funcionamiento USING btree (municipalidad_id, contribuyente_id, fecha_emision);
CREATE INDEX licencia_direccion_ix ON public.licencia_funcionamiento USING btree (municipalidad_id, direccion text_pattern_ops);
CREATE INDEX licencia_emision_ix ON public.licencia_funcionamiento USING btree (municipalidad_id, fecha_emision);
CREATE INDEX licencia_nombre_comercial_ix ON public.licencia_funcionamiento USING btree (municipalidad_id, nombre_comercial text_pattern_ops);
CREATE INDEX licencia_predio_ix ON public.licencia_funcionamiento USING btree (municipalidad_id, predio_id) WHERE (predio_id IS NOT NULL);
CREATE INDEX licencia_giro_licencia_ix ON public.licencia_giro USING btree (municipalidad_id, licencia_id, activo);
CREATE UNIQUE INDEX licencia_giro_principal_uq ON public.licencia_giro USING btree (municipalidad_id, licencia_id) WHERE principal;
CREATE UNIQUE INDEX licencia_movimiento_cancelacion_uq ON public.licencia_movimiento USING btree (municipalidad_id, licencia_id) WHERE ((tipo)::text = 'CANCELACION'::text);
CREATE UNIQUE INDEX licencia_movimiento_emision_uq ON public.licencia_movimiento USING btree (municipalidad_id, licencia_id) WHERE ((tipo)::text = 'EMISION'::text);
CREATE INDEX licencia_movimiento_licencia_ix ON public.licencia_movimiento USING btree (municipalidad_id, licencia_id, fecha);
CREATE INDEX liquidacion_costas_contribuyente_ix ON public.liquidacion_costas USING btree (municipalidad_id, contribuyente_id);
CREATE INDEX liquidacion_costas_expediente_ix ON public.liquidacion_costas USING btree (municipalidad_id, expediente_id, fecha);
CREATE INDEX liquidacion_detalle_liq_ix ON public.liquidacion_detalle USING btree (municipalidad_id, liquidacion_id, ejercicio);
CREATE INDEX liquidacion_acta_ix ON public.liquidacion_fiscalizacion USING btree (municipalidad_id, acta_id, version);
CREATE INDEX liquidacion_anterior_ix ON public.liquidacion_fiscalizacion USING btree (municipalidad_id, liquidacion_anterior_id);
CREATE UNIQUE INDEX liquidacion_movimiento_apertura_uq ON public.liquidacion_movimiento USING btree (municipalidad_id, liquidacion_id) WHERE ((tipo)::text = 'APERTURA'::text);
CREATE INDEX liquidacion_movimiento_liq_ix ON public.liquidacion_movimiento USING btree (municipalidad_id, liquidacion_id, id);
CREATE INDEX miembro_usuario_ix ON public.miembro USING btree (municipalidad_id, usuario_id);
CREATE INDEX notificacion_objeto_ix ON public.notificacion USING btree (municipalidad_id, objeto, objeto_id);
CREATE INDEX papeleta_codigo_ix ON public.papeleta USING btree (municipalidad_id, codigo_infraccion_id, fecha_infraccion);
CREATE INDEX papeleta_familia_fecha_ix ON public.papeleta USING btree (municipalidad_id, familia, fecha_infraccion);
CREATE INDEX papeleta_fecha_ix ON public.papeleta USING btree (municipalidad_id, fecha_infraccion, estado);
CREATE INDEX papeleta_infractor_ix ON public.papeleta USING btree (municipalidad_id, infractor_id) WHERE (infractor_id IS NOT NULL);
CREATE INDEX papeleta_obligado_ix ON public.papeleta USING btree (municipalidad_id, obligado_id, estado);
CREATE INDEX papeleta_placa_ix ON public.papeleta USING btree (municipalidad_id, placa) WHERE (placa IS NOT NULL);
CREATE INDEX papeleta_placa_prefijo_ix ON public.papeleta USING btree (municipalidad_id, placa text_pattern_ops);
CREATE INDEX papeleta_masivo_item_pendientes_ix ON public.papeleta_masivo_item USING btree (municipalidad_id, corrida_id, estado, id);
CREATE UNIQUE INDEX papeleta_valor_unico_uq ON public.papeleta_masivo_item USING btree (municipalidad_id, papeleta_id) WHERE ((estado)::text = 'GENERADO'::text);
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
CREATE INDEX prescripcion_contribuyente_ix ON public.prescripcion USING btree (municipalidad_id, contribuyente_id, tributo);
CREATE INDEX prescripcion_ejercicio_ix ON public.prescripcion_ejercicio USING btree (municipalidad_id, prescripcion_id);
CREATE INDEX prescripcion_hecho_ix ON public.prescripcion_hecho USING btree (municipalidad_id, prescripcion_id);
CREATE INDEX programa_muestra_predio_ix ON public.programa_muestra USING btree (municipalidad_id, predio_id);
CREATE INDEX programa_muestra_programa_ix ON public.programa_muestra USING btree (municipalidad_id, programa_id, predio_id);
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
CREATE INDEX resolucion_determinacion_contribuyente_ix ON public.resolucion_determinacion USING btree (municipalidad_id, contribuyente_id, fecha);
CREATE INDEX resolucion_determinacion_predio_ix ON public.resolucion_determinacion USING btree (municipalidad_id, predio_id);
CREATE UNIQUE INDEX resolucion_gerencia_descargo_uq ON public.resolucion_gerencia USING btree (municipalidad_id, descargo_id) WHERE (descargo_id IS NOT NULL);
CREATE UNIQUE INDEX resolucion_gerencia_ordinaria_uq ON public.resolucion_gerencia USING btree (municipalidad_id, papeleta_id) WHERE ((tipo)::text = 'ORDINARIA'::text);
CREATE INDEX resolucion_gerencia_papeleta_ix ON public.resolucion_gerencia USING btree (municipalidad_id, papeleta_id, fecha);
CREATE UNIQUE INDEX resolucion_gerencia_sancionadora_uq ON public.resolucion_gerencia USING btree (municipalidad_id, papeleta_id) WHERE ((tipo)::text = 'SANCIONADORA'::text);
CREATE INDEX respaldo_inicio_ix ON public.respaldo USING btree (inicio DESC);
CREATE INDEX responsable_por_contribuyente_ix ON public.responsable_solidario USING btree (municipalidad_id, contribuyente_id);
CREATE INDEX responsable_por_responsable_ix ON public.responsable_solidario USING btree (municipalidad_id, responsable_id);
CREATE UNIQUE INDEX responsable_vigente_uq ON public.responsable_solidario USING btree (municipalidad_id, contribuyente_id, responsable_id, vinculo) WHERE (vigencia_hasta IS NULL);
CREATE UNIQUE INDEX saldo_uq ON public.saldo_proyectado USING btree (municipalidad_id, contribuyente_id, tributo, ejercicio, periodo, COALESCE(predio_id, (0)::bigint), COALESCE(vehiculo_id, (0)::bigint));
CREATE INDEX sesion_abierta_ix ON public.sesion USING btree (municipalidad_id, usuario_id) WHERE (fin IS NULL);
CREATE INDEX tasa_vigencia_ix ON public.tasa USING btree (municipalidad_id, codigo, vigencia_desde DESC);
CREATE INDEX tierra_ficha_ix ON public.tierra_rural USING btree (municipalidad_id, ficha_id);
CREATE INDEX titularidad_contribuyente_ix ON public.titularidad USING btree (municipalidad_id, contribuyente_id) WHERE (vigencia_hasta IS NULL);
CREATE INDEX titularidad_predio_ix ON public.titularidad USING btree (municipalidad_id, predio_id, vigencia_desde);
CREATE INDEX titularidad_predio_vigente_ix ON public.titularidad USING btree (municipalidad_id, predio_id, porcentaje DESC) WHERE (vigencia_hasta IS NULL);
CREATE INDEX valor_contribuyente_ix ON public.valor USING btree (municipalidad_id, contribuyente_id, estado);
CREATE INDEX valor_detalle_valor_ix ON public.valor_detalle USING btree (municipalidad_id, valor_id);
CREATE UNIQUE INDEX valor_movimiento_pase_uq ON public.valor_movimiento USING btree (municipalidad_id, valor_id) WHERE ((tipo)::text = 'PCO'::text);
CREATE INDEX valor_movimiento_valor_ix ON public.valor_movimiento USING btree (municipalidad_id, valor_id, fecha);
CREATE INDEX valor_referencial_catalogo_ix ON public.valor_referencial_vehiculo USING btree (publicacion_id, marca, modelo);
CREATE INDEX vehiculo_contribuyente_ix ON public.vehiculo USING btree (municipalidad_id, contribuyente_id);
CREATE UNIQUE INDEX vehiculo_placa_uq ON public.vehiculo USING btree (municipalidad_id, replace((placa)::text, '-'::text, ''::text));
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
ALTER TABLE acta_fiscalizacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE acta_fiscalizacion FORCE ROW LEVEL SECURITY;
CREATE POLICY acta_fiscalizacion_tenant ON acta_fiscalizacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE actividad_economica ENABLE ROW LEVEL SECURITY;
ALTER TABLE actividad_economica FORCE ROW LEVEL SECURITY;
CREATE POLICY actividad_por_tenant ON actividad_economica FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE acto_coactivo ENABLE ROW LEVEL SECURITY;
ALTER TABLE acto_coactivo FORCE ROW LEVEL SECURITY;
CREATE POLICY acto_coactivo_tenant ON acto_coactivo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE anuncio ENABLE ROW LEVEL SECURITY;
ALTER TABLE anuncio FORCE ROW LEVEL SECURITY;
CREATE POLICY anuncio_tenant ON anuncio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE anuncio_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE anuncio_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY anuncio_correlativo_tenant ON anuncio_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE anuncio_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE anuncio_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY anuncio_movimiento_tenant ON anuncio_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE arancel ENABLE ROW LEVEL SECURITY;
ALTER TABLE arancel FORCE ROW LEVEL SECURITY;
CREATE POLICY arancel_tenant ON arancel FOR ALL TO PUBLIC
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
ALTER TABLE beneficio ENABLE ROW LEVEL SECURITY;
ALTER TABLE beneficio FORCE ROW LEVEL SECURITY;
CREATE POLICY beneficio_tenant ON beneficio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE bien_comun ENABLE ROW LEVEL SECURITY;
ALTER TABLE bien_comun FORCE ROW LEVEL SECURITY;
CREATE POLICY bien_comun_por_tenant ON bien_comun FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE caja ENABLE ROW LEVEL SECURITY;
ALTER TABLE caja FORCE ROW LEVEL SECURITY;
CREATE POLICY caja_tenant ON caja FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE certificado ENABLE ROW LEVEL SECURITY;
ALTER TABLE certificado FORCE ROW LEVEL SECURITY;
CREATE POLICY certificado_tenant ON certificado FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE certificado_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE certificado_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY certificado_correlativo_tenant ON certificado_correlativo FOR ALL TO PUBLIC
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
ALTER TABLE ciiu ENABLE ROW LEVEL SECURITY;
ALTER TABLE ciiu FORCE ROW LEVEL SECURITY;
CREATE POLICY ciiu_tenant ON ciiu FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE codigo_infraccion ENABLE ROW LEVEL SECURITY;
ALTER TABLE codigo_infraccion FORCE ROW LEVEL SECURITY;
CREATE POLICY codigo_infraccion_tenant ON codigo_infraccion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE colindante_rural ENABLE ROW LEVEL SECURITY;
ALTER TABLE colindante_rural FORCE ROW LEVEL SECURITY;
CREATE POLICY colindante_por_tenant ON colindante_rural FOR ALL TO PUBLIC
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
ALTER TABLE constancia_libre ENABLE ROW LEVEL SECURITY;
ALTER TABLE constancia_libre FORCE ROW LEVEL SECURITY;
CREATE POLICY constancia_libre_tenant ON constancia_libre FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE construccion ENABLE ROW LEVEL SECURITY;
ALTER TABLE construccion FORCE ROW LEVEL SECURITY;
CREATE POLICY construccion_tenant ON construccion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE contacto ENABLE ROW LEVEL SECURITY;
ALTER TABLE contacto FORCE ROW LEVEL SECURITY;
CREATE POLICY contacto_tenant ON contacto FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE contribuyente ENABLE ROW LEVEL SECURITY;
ALTER TABLE contribuyente FORCE ROW LEVEL SECURITY;
CREATE POLICY contribuyente_tenant ON contribuyente FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE convenio ENABLE ROW LEVEL SECURITY;
ALTER TABLE convenio FORCE ROW LEVEL SECURITY;
CREATE POLICY convenio_tenant ON convenio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE convenio_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE convenio_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY convenio_correlativo_tenant ON convenio_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE convenio_cuota ENABLE ROW LEVEL SECURITY;
ALTER TABLE convenio_cuota FORCE ROW LEVEL SECURITY;
CREATE POLICY convenio_cuota_tenant ON convenio_cuota FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE convenio_deuda ENABLE ROW LEVEL SECURITY;
ALTER TABLE convenio_deuda FORCE ROW LEVEL SECURITY;
CREATE POLICY convenio_deuda_tenant ON convenio_deuda FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE convenio_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE convenio_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY convenio_movimiento_tenant ON convenio_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE corrida_predial ENABLE ROW LEVEL SECURITY;
ALTER TABLE corrida_predial FORCE ROW LEVEL SECURITY;
CREATE POLICY corrida_predial_tenant ON corrida_predial FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE corrida_predial_observado ENABLE ROW LEVEL SECURITY;
ALTER TABLE corrida_predial_observado FORCE ROW LEVEL SECURITY;
CREATE POLICY corrida_predial_observado_tenant ON corrida_predial_observado FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE costa_obligacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE costa_obligacion FORCE ROW LEVEL SECURITY;
CREATE POLICY costa_obligacion_tenant ON costa_obligacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE costa_procesal ENABLE ROW LEVEL SECURITY;
ALTER TABLE costa_procesal FORCE ROW LEVEL SECURITY;
CREATE POLICY costa_procesal_tenant ON costa_procesal FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cuenta_corriente_asiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE cuenta_corriente_asiento FORCE ROW LEVEL SECURITY;
CREATE POLICY cuenta_corriente_asiento_tenant ON cuenta_corriente_asiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cuenta_corriente_asiento_2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE cuenta_corriente_asiento_2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY cuenta_corriente_asiento_2026_tenant ON cuenta_corriente_asiento_2026 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE cuenta_corriente_asiento_2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE cuenta_corriente_asiento_2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY cuenta_corriente_asiento_2027_tenant ON cuenta_corriente_asiento_2027 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE declaracion_jurada ENABLE ROW LEVEL SECURITY;
ALTER TABLE declaracion_jurada FORCE ROW LEVEL SECURITY;
CREATE POLICY declaracion_jurada_tenant ON declaracion_jurada FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE depreciacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE depreciacion FORCE ROW LEVEL SECURITY;
CREATE POLICY depreciacion_escritura ON depreciacion FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY depreciacion_lectura ON depreciacion FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));
ALTER TABLE descargo ENABLE ROW LEVEL SECURITY;
ALTER TABLE descargo FORCE ROW LEVEL SECURITY;
CREATE POLICY descargo_tenant ON descargo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_tenant ON determinacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_2026_tenant ON determinacion_2026 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_2027_tenant ON determinacion_2027 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_arbitrio ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_arbitrio FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_arbitrio_tenant ON determinacion_arbitrio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_arbitrio_2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_arbitrio_2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_arbitrio_2026_tenant ON determinacion_arbitrio_2026 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_arbitrio_2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_arbitrio_2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_arbitrio_2027_tenant ON determinacion_arbitrio_2027 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_predio_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_predio_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_predio_detalle_tenant ON determinacion_predio_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_predio_detalle_2026 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_predio_detalle_2026 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_predio_detalle_2026_tenant ON determinacion_predio_detalle_2026 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE determinacion_predio_detalle_2027 ENABLE ROW LEVEL SECURITY;
ALTER TABLE determinacion_predio_detalle_2027 FORCE ROW LEVEL SECURITY;
CREATE POLICY determinacion_predio_detalle_2027_tenant ON determinacion_predio_detalle_2027 FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE dj_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE dj_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY dj_correlativo_tenant ON dj_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE documento_emitido ENABLE ROW LEVEL SECURITY;
ALTER TABLE documento_emitido FORCE ROW LEVEL SECURITY;
CREATE POLICY documento_por_tenant ON documento_emitido FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE domicilio ENABLE ROW LEVEL SECURITY;
ALTER TABLE domicilio FORCE ROW LEVEL SECURITY;
CREATE POLICY domicilio_tenant ON domicilio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_correlativo_tenant ON edificacion_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_estructura ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_estructura FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_estructura_tenant ON edificacion_estructura FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_movimiento_tenant ON edificacion_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_profesional ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_profesional FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_profesional_tenant ON edificacion_profesional FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_proyecto ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_proyecto FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_proyecto_tenant ON edificacion_proyecto FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_requisito ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_requisito FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_requisito_tenant ON edificacion_requisito FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_terreno ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_terreno FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_terreno_tenant ON edificacion_terreno FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE edificacion_vigencia ENABLE ROW LEVEL SECURITY;
ALTER TABLE edificacion_vigencia FORCE ROW LEVEL SECURITY;
CREATE POLICY edificacion_vigencia_tenant ON edificacion_vigencia FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE espectaculo ENABLE ROW LEVEL SECURITY;
ALTER TABLE espectaculo FORCE ROW LEVEL SECURITY;
CREATE POLICY espectaculo_tenant ON espectaculo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE expediente_coactivo ENABLE ROW LEVEL SECURITY;
ALTER TABLE expediente_coactivo FORCE ROW LEVEL SECURITY;
CREATE POLICY expediente_coactivo_tenant ON expediente_coactivo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE expediente_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE expediente_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY expediente_correlativo_tenant ON expediente_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE expediente_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE expediente_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY expediente_movimiento_tenant ON expediente_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE expediente_valor ENABLE ROW LEVEL SECURITY;
ALTER TABLE expediente_valor FORCE ROW LEVEL SECURITY;
CREATE POLICY expediente_valor_tenant ON expediente_valor FOR ALL TO PUBLIC
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
ALTER TABLE internamiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE internamiento FORCE ROW LEVEL SECURITY;
CREATE POLICY internamiento_tenant ON internamiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE internamiento_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE internamiento_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY internamiento_movimiento_tenant ON internamiento_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_correlativo_tenant ON licencia_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_duplicado ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_duplicado FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_duplicado_tenant ON licencia_duplicado FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_edificacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_edificacion FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_edificacion_tenant ON licencia_edificacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_funcionamiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_funcionamiento FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_funcionamiento_tenant ON licencia_funcionamiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_giro ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_giro FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_giro_tenant ON licencia_giro FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE licencia_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE licencia_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY licencia_movimiento_tenant ON licencia_movimiento FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_correlativo_tenant ON liquidacion_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_costas ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_costas FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_costas_tenant ON liquidacion_costas FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_costas_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_costas_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_costas_correlativo_tenant ON liquidacion_costas_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_detalle_tenant ON liquidacion_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_fiscalizacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_fiscalizacion FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_tenant ON liquidacion_fiscalizacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE liquidacion_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE liquidacion_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY liquidacion_movimiento_tenant ON liquidacion_movimiento FOR ALL TO PUBLIC
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
ALTER TABLE notificacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE notificacion FORCE ROW LEVEL SECURITY;
CREATE POLICY notificacion_tenant ON notificacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE notificacion_administrativa ENABLE ROW LEVEL SECURITY;
ALTER TABLE notificacion_administrativa FORCE ROW LEVEL SECURITY;
CREATE POLICY notificacion_administrativa_tenant ON notificacion_administrativa FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE otra_instalacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE otra_instalacion FORCE ROW LEVEL SECURITY;
CREATE POLICY otra_instalacion_tenant ON otra_instalacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE papeleta ENABLE ROW LEVEL SECURITY;
ALTER TABLE papeleta FORCE ROW LEVEL SECURITY;
CREATE POLICY papeleta_tenant ON papeleta FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE papeleta_cambio_numero ENABLE ROW LEVEL SECURITY;
ALTER TABLE papeleta_cambio_numero FORCE ROW LEVEL SECURITY;
CREATE POLICY papeleta_cambio_numero_tenant ON papeleta_cambio_numero FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE papeleta_masivo ENABLE ROW LEVEL SECURITY;
ALTER TABLE papeleta_masivo FORCE ROW LEVEL SECURITY;
CREATE POLICY papeleta_masivo_tenant ON papeleta_masivo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE papeleta_masivo_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE papeleta_masivo_item FORCE ROW LEVEL SECURITY;
CREATE POLICY papeleta_masivo_item_tenant ON papeleta_masivo_item FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE parametro_tributario ENABLE ROW LEVEL SECURITY;
ALTER TABLE parametro_tributario FORCE ROW LEVEL SECURITY;
CREATE POLICY parametro_escritura ON parametro_tributario FOR ALL TO rol_carga_parametros
    USING (true)
    WITH CHECK (true);
CREATE POLICY parametro_lectura ON parametro_tributario FOR SELECT TO PUBLIC
    USING (((municipalidad_id IS NULL) OR (municipalidad_id = (NULLIF(current_setting('app.municipalidad_id'::text, true), ''::text))::bigint)));
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
ALTER TABLE prescripcion ENABLE ROW LEVEL SECURITY;
ALTER TABLE prescripcion FORCE ROW LEVEL SECURITY;
CREATE POLICY prescripcion_tenant ON prescripcion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE prescripcion_ejercicio ENABLE ROW LEVEL SECURITY;
ALTER TABLE prescripcion_ejercicio FORCE ROW LEVEL SECURITY;
CREATE POLICY prescripcion_ejercicio_tenant ON prescripcion_ejercicio FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE prescripcion_hecho ENABLE ROW LEVEL SECURITY;
ALTER TABLE prescripcion_hecho FORCE ROW LEVEL SECURITY;
CREATE POLICY prescripcion_hecho_tenant ON prescripcion_hecho FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE programa_fiscalizacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE programa_fiscalizacion FORCE ROW LEVEL SECURITY;
CREATE POLICY programa_fiscalizacion_tenant ON programa_fiscalizacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE programa_muestra ENABLE ROW LEVEL SECURITY;
ALTER TABLE programa_muestra FORCE ROW LEVEL SECURITY;
CREATE POLICY programa_muestra_tenant ON programa_muestra FOR ALL TO PUBLIC
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
ALTER TABLE resolucion_determinacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE resolucion_determinacion FORCE ROW LEVEL SECURITY;
CREATE POLICY resolucion_determinacion_tenant ON resolucion_determinacion FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE resolucion_gerencia ENABLE ROW LEVEL SECURITY;
ALTER TABLE resolucion_gerencia FORCE ROW LEVEL SECURITY;
CREATE POLICY resolucion_gerencia_tenant ON resolucion_gerencia FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE respaldo ENABLE ROW LEVEL SECURITY;
ALTER TABLE respaldo FORCE ROW LEVEL SECURITY;
CREATE POLICY respaldo_escritura ON respaldo FOR ALL TO sgtm_owner
    USING (true)
    WITH CHECK (true);
CREATE POLICY respaldo_lectura ON respaldo FOR SELECT TO PUBLIC
    USING (true);
ALTER TABLE responsable_solidario ENABLE ROW LEVEL SECURITY;
ALTER TABLE responsable_solidario FORCE ROW LEVEL SECURITY;
CREATE POLICY responsable_por_tenant ON responsable_solidario FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE saldo_proyectado ENABLE ROW LEVEL SECURITY;
ALTER TABLE saldo_proyectado FORCE ROW LEVEL SECURITY;
CREATE POLICY saldo_proyectado_tenant ON saldo_proyectado FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
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
ALTER TABLE tasa ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasa FORCE ROW LEVEL SECURITY;
CREATE POLICY tasa_tenant ON tasa FOR ALL TO PUBLIC
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
ALTER TABLE transferencia ENABLE ROW LEVEL SECURITY;
ALTER TABLE transferencia FORCE ROW LEVEL SECURITY;
CREATE POLICY transferencia_tenant ON transferencia FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario FORCE ROW LEVEL SECURITY;
CREATE POLICY usuario_tenant ON usuario FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_tenant ON valor FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_correlativo ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_correlativo FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_correlativo_tenant ON valor_correlativo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_detalle_tenant ON valor_detalle FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_masivo ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_masivo FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_masivo_tenant ON valor_masivo FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_masivo_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_masivo_item FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_masivo_item_tenant ON valor_masivo_item FOR ALL TO PUBLIC
    USING ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint))
    WITH CHECK ((municipalidad_id = (current_setting('app.municipalidad_id'::text))::bigint));
ALTER TABLE valor_movimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE valor_movimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY valor_movimiento_tenant ON valor_movimiento FOR ALL TO PUBLIC
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
ALTER TABLE vehiculo ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehiculo FORCE ROW LEVEL SECURITY;
CREATE POLICY vehiculo_tenant ON vehiculo FOR ALL TO PUBLIC
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
GRANT INSERT, SELECT, UPDATE ON acta_fiscalizacion TO sgtm_app;
GRANT SELECT ON acta_fiscalizacion TO sgtm_readonly;
GRANT INSERT, SELECT ON actividad_economica TO sgtm_app;
GRANT SELECT ON actividad_economica TO sgtm_readonly;
GRANT INSERT, SELECT ON acto_coactivo TO sgtm_app;
GRANT SELECT ON acto_coactivo TO sgtm_readonly;
GRANT INSERT, SELECT ON anuncio TO sgtm_app;
GRANT SELECT ON anuncio TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON anuncio_correlativo TO sgtm_app;
GRANT SELECT ON anuncio_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON anuncio_movimiento TO sgtm_app;
GRANT SELECT ON anuncio_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON arancel TO sgtm_app;
GRANT SELECT ON arancel TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON area TO sgtm_app;
GRANT SELECT ON area TO sgtm_readonly;
GRANT INSERT, SELECT ON auditoria TO sgtm_app;
GRANT SELECT ON auditoria TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON beneficio TO sgtm_app;
GRANT SELECT ON beneficio TO sgtm_readonly;
GRANT INSERT, SELECT ON bien_comun TO sgtm_app;
GRANT SELECT ON bien_comun TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON caja TO sgtm_app;
GRANT SELECT ON caja TO sgtm_readonly;
GRANT INSERT, SELECT ON certificado TO sgtm_app;
GRANT SELECT ON certificado TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON certificado_correlativo TO sgtm_app;
GRANT SELECT ON certificado_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON cierre_caja TO sgtm_app;
GRANT SELECT ON cierre_caja TO sgtm_readonly;
GRANT INSERT, SELECT ON cierre_turno TO sgtm_app;
GRANT SELECT ON cierre_turno TO sgtm_readonly;
GRANT INSERT, SELECT ON cierre_turno_detalle TO sgtm_app;
GRANT SELECT ON cierre_turno_detalle TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON ciiu TO sgtm_app;
GRANT SELECT ON ciiu TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON codigo_infraccion TO sgtm_app;
GRANT SELECT ON codigo_infraccion TO sgtm_readonly;
GRANT INSERT, SELECT ON colindante_rural TO sgtm_app;
GRANT SELECT ON colindante_rural TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON conjunto_parametro_detalle TO sgtm_app;
GRANT SELECT ON conjunto_parametro_detalle TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON conjunto_parametros TO sgtm_app;
GRANT SELECT ON conjunto_parametros TO sgtm_readonly;
GRANT INSERT, SELECT ON constancia_libre TO sgtm_app;
GRANT SELECT ON constancia_libre TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON construccion TO sgtm_app;
GRANT SELECT ON construccion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON contacto TO sgtm_app;
GRANT SELECT ON contacto TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON contribuyente TO sgtm_app;
GRANT SELECT ON contribuyente TO sgtm_readonly;
GRANT INSERT, SELECT ON convenio TO sgtm_app;
GRANT SELECT ON convenio TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON convenio_correlativo TO sgtm_app;
GRANT SELECT ON convenio_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON convenio_cuota TO sgtm_app;
GRANT SELECT ON convenio_cuota TO sgtm_readonly;
GRANT INSERT, SELECT ON convenio_deuda TO sgtm_app;
GRANT SELECT ON convenio_deuda TO sgtm_readonly;
GRANT INSERT, SELECT ON convenio_movimiento TO sgtm_app;
GRANT SELECT ON convenio_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT ON corrida_predial TO sgtm_app;
GRANT SELECT ON corrida_predial TO sgtm_readonly;
GRANT INSERT, SELECT ON corrida_predial_observado TO sgtm_app;
GRANT SELECT ON corrida_predial_observado TO sgtm_readonly;
GRANT INSERT, SELECT ON costa_obligacion TO sgtm_app;
GRANT SELECT ON costa_obligacion TO sgtm_readonly;
GRANT INSERT, SELECT ON costa_procesal TO sgtm_app;
GRANT SELECT ON costa_procesal TO sgtm_readonly;
GRANT INSERT, SELECT ON cuenta_corriente_asiento TO sgtm_app;
GRANT SELECT ON cuenta_corriente_asiento TO sgtm_readonly;
GRANT INSERT, SELECT ON declaracion_jurada TO sgtm_app;
GRANT SELECT ON declaracion_jurada TO sgtm_readonly;
GRANT UPDATE (estado) ON declaracion_jurada TO sgtm_app;
GRANT INSERT, SELECT, UPDATE ON depreciacion TO rol_carga_parametros;
GRANT SELECT ON depreciacion TO sgtm_app;
GRANT SELECT ON depreciacion TO sgtm_readonly;
GRANT INSERT, SELECT ON descargo TO sgtm_app;
GRANT SELECT ON descargo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON determinacion TO sgtm_app;
GRANT SELECT ON determinacion TO sgtm_readonly;
GRANT INSERT, SELECT ON determinacion_arbitrio TO sgtm_app;
GRANT SELECT ON determinacion_arbitrio TO sgtm_readonly;
GRANT INSERT, SELECT ON determinacion_predio_detalle TO sgtm_app;
GRANT SELECT ON determinacion_predio_detalle TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON dj_correlativo TO sgtm_app;
GRANT SELECT ON dj_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON documento_emitido TO sgtm_app;
GRANT SELECT ON documento_emitido TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON domicilio TO sgtm_app;
GRANT SELECT ON domicilio TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON edificacion_correlativo TO sgtm_app;
GRANT SELECT ON edificacion_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_estructura TO sgtm_app;
GRANT SELECT ON edificacion_estructura TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_movimiento TO sgtm_app;
GRANT SELECT ON edificacion_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_profesional TO sgtm_app;
GRANT SELECT ON edificacion_profesional TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_proyecto TO sgtm_app;
GRANT SELECT ON edificacion_proyecto TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_requisito TO sgtm_app;
GRANT SELECT ON edificacion_requisito TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_terreno TO sgtm_app;
GRANT SELECT ON edificacion_terreno TO sgtm_readonly;
GRANT INSERT, SELECT ON edificacion_vigencia TO sgtm_app;
GRANT SELECT ON edificacion_vigencia TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON espectaculo TO sgtm_app;
GRANT SELECT ON espectaculo TO sgtm_readonly;
GRANT INSERT, SELECT ON expediente_coactivo TO sgtm_app;
GRANT SELECT ON expediente_coactivo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON expediente_correlativo TO sgtm_app;
GRANT SELECT ON expediente_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON expediente_movimiento TO sgtm_app;
GRANT SELECT ON expediente_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT ON expediente_valor TO sgtm_app;
GRANT SELECT ON expediente_valor TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON ficha_catastral TO sgtm_app;
GRANT SELECT ON ficha_catastral TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON grupo TO sgtm_app;
GRANT SELECT ON grupo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON inquilino TO sgtm_app;
GRANT SELECT ON inquilino TO sgtm_readonly;
GRANT INSERT, SELECT ON internamiento TO sgtm_app;
GRANT SELECT ON internamiento TO sgtm_readonly;
GRANT INSERT, SELECT ON internamiento_movimiento TO sgtm_app;
GRANT SELECT ON internamiento_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON licencia_correlativo TO sgtm_app;
GRANT SELECT ON licencia_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON licencia_duplicado TO sgtm_app;
GRANT SELECT ON licencia_duplicado TO sgtm_readonly;
GRANT INSERT, SELECT ON licencia_edificacion TO sgtm_app;
GRANT SELECT ON licencia_edificacion TO sgtm_readonly;
GRANT INSERT, SELECT ON licencia_funcionamiento TO sgtm_app;
GRANT SELECT ON licencia_funcionamiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON licencia_giro TO sgtm_app;
GRANT SELECT ON licencia_giro TO sgtm_readonly;
GRANT INSERT, SELECT ON licencia_movimiento TO sgtm_app;
GRANT SELECT ON licencia_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON liquidacion_correlativo TO sgtm_app;
GRANT SELECT ON liquidacion_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON liquidacion_costas TO sgtm_app;
GRANT SELECT ON liquidacion_costas TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON liquidacion_costas_correlativo TO sgtm_app;
GRANT SELECT ON liquidacion_costas_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON liquidacion_detalle TO sgtm_app;
GRANT SELECT ON liquidacion_detalle TO sgtm_readonly;
GRANT INSERT, SELECT ON liquidacion_fiscalizacion TO sgtm_app;
GRANT SELECT ON liquidacion_fiscalizacion TO sgtm_readonly;
GRANT INSERT, SELECT ON liquidacion_movimiento TO sgtm_app;
GRANT SELECT ON liquidacion_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON manzana TO sgtm_app;
GRANT SELECT ON manzana TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON miembro TO sgtm_app;
GRANT SELECT ON miembro TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON modulo_sistema TO sgtm_app;
GRANT SELECT ON modulo_sistema TO sgtm_readonly;
GRANT SELECT ON municipalidad TO sgtm_app;
GRANT SELECT ON municipalidad TO sgtm_readonly;
GRANT INSERT, SELECT ON notificacion TO sgtm_app;
GRANT SELECT ON notificacion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON notificacion_administrativa TO sgtm_app;
GRANT SELECT ON notificacion_administrativa TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON otra_instalacion TO sgtm_app;
GRANT SELECT ON otra_instalacion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON papeleta TO sgtm_app;
GRANT SELECT ON papeleta TO sgtm_readonly;
GRANT INSERT, SELECT ON papeleta_cambio_numero TO sgtm_app;
GRANT SELECT ON papeleta_cambio_numero TO sgtm_readonly;
GRANT INSERT, SELECT ON papeleta_masivo TO sgtm_app;
GRANT SELECT ON papeleta_masivo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON papeleta_masivo_item TO sgtm_app;
GRANT SELECT ON papeleta_masivo_item TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON parametro_tributario TO rol_carga_parametros;
GRANT SELECT ON parametro_tributario TO sgtm_app;
GRANT SELECT ON parametro_tributario TO sgtm_readonly;
GRANT INSERT, SELECT ON participacion_comun TO sgtm_app;
GRANT SELECT ON participacion_comun TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON permiso TO sgtm_app;
GRANT SELECT ON permiso TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON predio TO sgtm_app;
GRANT SELECT ON predio TO sgtm_readonly;
GRANT INSERT, SELECT ON prescripcion TO sgtm_app;
GRANT SELECT ON prescripcion TO sgtm_readonly;
GRANT INSERT, SELECT ON prescripcion_ejercicio TO sgtm_app;
GRANT SELECT ON prescripcion_ejercicio TO sgtm_readonly;
GRANT INSERT, SELECT ON prescripcion_hecho TO sgtm_app;
GRANT SELECT ON prescripcion_hecho TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON programa_fiscalizacion TO sgtm_app;
GRANT SELECT ON programa_fiscalizacion TO sgtm_readonly;
GRANT INSERT, SELECT ON programa_muestra TO sgtm_app;
GRANT SELECT ON programa_muestra TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo TO sgtm_app;
GRANT SELECT ON recibo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON recibo_correlativo TO sgtm_app;
GRANT SELECT ON recibo_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo_detalle TO sgtm_app;
GRANT SELECT ON recibo_detalle TO sgtm_readonly;
GRANT INSERT, SELECT ON recibo_movimiento TO sgtm_app;
GRANT SELECT ON recibo_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT ON resolucion_determinacion TO sgtm_app;
GRANT SELECT ON resolucion_determinacion TO sgtm_readonly;
GRANT INSERT, SELECT ON resolucion_gerencia TO sgtm_app;
GRANT SELECT ON resolucion_gerencia TO sgtm_readonly;
GRANT SELECT ON respaldo TO sgtm_app;
GRANT SELECT ON respaldo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON responsable_solidario TO sgtm_app;
GRANT SELECT ON responsable_solidario TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON saldo_proyectado TO sgtm_app;
GRANT SELECT ON saldo_proyectado TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sector TO sgtm_app;
GRANT SELECT ON sector TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON sesion TO sgtm_app;
GRANT SELECT ON sesion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON tasa TO sgtm_app;
GRANT SELECT ON tasa TO sgtm_readonly;
GRANT INSERT, SELECT ON tierra_rural TO sgtm_app;
GRANT SELECT ON tierra_rural TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON titularidad TO sgtm_app;
GRANT SELECT ON titularidad TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON transferencia TO sgtm_app;
GRANT SELECT ON transferencia TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON usuario TO sgtm_app;
GRANT SELECT ON usuario TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor TO sgtm_app;
GRANT SELECT ON valor TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_correlativo TO sgtm_app;
GRANT SELECT ON valor_correlativo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_detalle TO sgtm_app;
GRANT SELECT ON valor_detalle TO sgtm_readonly;
GRANT INSERT, SELECT ON valor_masivo TO sgtm_app;
GRANT SELECT ON valor_masivo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_masivo_item TO sgtm_app;
GRANT SELECT ON valor_masivo_item TO sgtm_readonly;
GRANT INSERT, SELECT ON valor_movimiento TO sgtm_app;
GRANT SELECT ON valor_movimiento TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_referencial_vehiculo TO rol_carga_parametros;
GRANT SELECT ON valor_referencial_vehiculo TO sgtm_app;
GRANT SELECT ON valor_referencial_vehiculo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON valor_unitario_edificacion TO rol_carga_parametros;
GRANT SELECT ON valor_unitario_edificacion TO sgtm_app;
GRANT SELECT ON valor_unitario_edificacion TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON vehiculo TO sgtm_app;
GRANT SELECT ON vehiculo TO sgtm_readonly;
GRANT INSERT, SELECT, UPDATE ON via TO sgtm_app;
GRANT SELECT ON via TO sgtm_readonly;

-- ==========================================================================
--  8. DISPARADORES DE INMUTABILIDAD Y DE INVARIANTE
--  Con sus funciones. Un disparador sin su funcion no protege nada.
-- ==========================================================================

CREATE TRIGGER arancel_de_conjunto_sellado_inmutable BEFORE INSERT OR UPDATE ON public.arancel FOR EACH ROW EXECUTE FUNCTION valuacion_de_conjunto_sellado_es_inmutable();
CREATE TRIGGER detalle_de_conjunto_sellado_inmutable BEFORE INSERT OR UPDATE ON public.conjunto_parametro_detalle FOR EACH ROW EXECUTE FUNCTION detalle_de_conjunto_sellado_es_inmutable();
CREATE TRIGGER conjunto_sellado_inmutable BEFORE UPDATE ON public.conjunto_parametros FOR EACH ROW EXECUTE FUNCTION conjunto_sellado_es_inmutable();
CREATE TRIGGER declaracion_jurada_estado_terminal BEFORE UPDATE ON public.declaracion_jurada FOR EACH ROW EXECUTE FUNCTION declaracion_jurada_estado_es_terminal();
CREATE TRIGGER depreciacion_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.depreciacion FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();
CREATE TRIGGER documento_inmutable_trg BEFORE UPDATE ON public.documento_emitido FOR EACH ROW EXECUTE FUNCTION documento_solo_cuenta_reimpresiones();
CREATE CONSTRAINT TRIGGER participacion_no_excede_trg AFTER INSERT OR DELETE OR UPDATE ON public.participacion_comun DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verificar_participacion_no_excede();
CREATE CONSTRAINT TRIGGER titularidad_no_excede_trg AFTER INSERT OR DELETE OR UPDATE ON public.titularidad DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verificar_titularidad_no_excede();
CREATE TRIGGER valor_referencial_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.valor_referencial_vehiculo FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();
CREATE TRIGGER valor_unitario_de_publicacion_sellada_inmutable BEFORE INSERT OR UPDATE ON public.valor_unitario_edificacion FOR EACH ROW EXECUTE FUNCTION valuacion_de_publicacion_sellada_es_inmutable();

-- ==========================================================================
--  9. COMENTARIOS
--  El por que de una columna, que es lo primero que se pierde.
-- ==========================================================================

COMMENT ON COLUMN acta_fiscalizacion.uso_hallado IS 'El uso que la inspeccion observo en campo, contra el que se compara ficha_catastral.uso (V76). Mismo tipo y largo que el lado declarado y que liquidacion_detalle.uso_hallado, donde acaba copiado. Solo un acta predial lo consigna: un vehiculo no tiene uso declarado.';
COMMENT ON TABLE actividad_economica IS 'Actividad economica declarada en la unidad catastral (RF-002). Referencia la licencia por numero, no por clave ajena: catastro no depende de licencias.';
COMMENT ON TABLE acto_coactivo IS 'Los actos del procedimiento de ejecucion coactiva (#41, RF-101, RF-102): la REC-1 que lo inicia, la REC-2 que ordena la medida cautelar, y los demas. Cada uno con su fecha, su usuario, su observacion y el documento emitido que lo materializa. Solo se agrega: un acto se deja sin efecto con otro acto, nunca editandolo.';
COMMENT ON COLUMN acto_coactivo.numero IS 'El numero del documento emitido que materializa el acto (documento_emitido.numero, V15). No es un correlativo propio: dos numeraciones para el mismo papel divergen.';
COMMENT ON COLUMN acto_coactivo.documento_id IS 'La fila de documento_emitido con los datos que dibujaron el acto y el SHA-256 de lo que salio. Es lo que permite reimprimir la REC identica anos despues (RF-132).';
COMMENT ON COLUMN acto_coactivo.rec1_exigible_desde IS 'Desde cuando, vencidos los siete dias habiles del art. 14.1 de la Ley 26979 contados desde la notificacion de la REC-1, se puede dictar la medida cautelar. Se COPIA de notificacion.exigible_desde, que sale del plazo PARAMETRIZADO del conjunto sellado (regla 5): recalcularla al leer daria otra fecha el dia que el plazo cambie.';
COMMENT ON TABLE anuncio IS 'La autorizacion municipal para instalar un elemento publicitario (#51, RF-114). Solo se agrega: su estado se deriva de anuncio_movimiento y sus tramites -renovacion, cese, retiro- producen actos nuevos, nunca la edicion del formulario. Registrarla GENERA LA DEUDA por la tasa, y esa deuda se le pide a cuentacorriente por su API publica: este contexto no escribe en el libro (ARQ-01 §4 regla 2).';
COMMENT ON COLUMN anuncio.area IS 'El area declarada del elemento. Es la medida que el acto administrativo consigna; la base y la altura con que el operador la obtiene NO se guardan, para que no puedan discrepar de ella. La tasa NO se calcula aqui a partir del area: eso es una regla, y sus cifras son D-02b (#199).';
COMMENT ON COLUMN anuncio.licencia_id IS 'El establecimiento asociado, como licencia de funcionamiento de #44. Opcional: hay anuncios que no cuelgan de ningun local.';
COMMENT ON COLUMN anuncio.clase IS 'La clase del elemento publicitario. Es la que la ordenanza tarifa: de ella sale la llave TASA_ANUNCIO:<CLASE> del conjunto sellado (D-02b, #199). Sin clase no hay tasa que pedir.';
COMMENT ON COLUMN anuncio.clave_idempotencia IS 'La clave que el cliente manda en la cabecera idempotency-key. Con su indice unico parcial, reenviar el mismo registro devuelve el anuncio de la primera vez y no pide un segundo cargo.';
COMMENT ON TABLE anuncio_correlativo IS 'El ultimo correlativo de autorizacion de anuncio emitido por municipalidad y ejercicio (#51). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE.';
COMMENT ON TABLE anuncio_movimiento IS 'Lo que le pasa a un anuncio: su autorizacion, sus renovaciones, su cese y su retiro (#51, RF-114). SOLO SE AGREGA. De aqui se deriva el estado -que es lo que decide si se sigue devengando tasa- y de aqui sale la garantia de que un cargo no se pide dos veces.';
COMMENT ON COLUMN anuncio_movimiento.referencia_cargo IS 'La referencia_externa con la que el cargo entro en el libro, ANUNCIO-<numero>-<ejercicio>. Su indice unico es lo que impide que el mismo anuncio devengue dos veces la tasa del mismo ejercicio, y por eso vive aqui y no en cuenta_corriente_asiento: alli referencia_externa NO es unica por diseño (#42 asienta varias costas del mismo expediente).';
COMMENT ON TABLE bien_comun IS 'Areas comunes de una edificacion (RF-003), cuyo valor se distribuye entre las unidades.';
COMMENT ON COLUMN caja.serie IS 'La serie de sus recibos, unica en la municipalidad (#33). Es lo que impide que dos ventanillas compitan por el mismo correlativo: cada una incrementa su propia fila de recibo_correlativo.';
COMMENT ON TABLE certificado IS 'Los certificados de numeracion, zonificacion y vias, parametros urbanisticos y jurisdiccion (#54, RF-115). SOLO SE AGREGA: se entrega al administrado, que se lo lleva, y uno equivocado se sustituye emitiendo otro -con su numero y su derecho de tramite-, nunca corrigiendo la fila. sgtm_app no recibe UPDATE ni DELETE sobre esta tabla.';
COMMENT ON COLUMN certificado.numero IS 'El numero del certificado, el que se cita en la escritura publica o en el expediente. NO es el numero del documento emitido: el certificado sobrevive a sus reimpresiones.';
COMMENT ON COLUMN certificado.vigencia_hasta IS 'Hasta cuando vale el certificado que se entrego, calculado con el parametro sellado que regia el dia de la emision (VIGENCIA_CERTIFICADO:<TIPO>, D-02b). Se copia y no se recalcula: dentro de dos anios el TUPA puede decir otra cosa y este papel ya esta en manos de alguien.';
COMMENT ON COLUMN certificado.derecho IS 'Lo que el recibo cobro por el concepto del TUPA de este certificado, copiado del acto. NO se recalcula leyendo `tasa`: releer el catalogo daria la tarifa de hoy para un certificado de hace dos anios. Va siempre con `derecho_a`, que es el dia al que corresponde (regla 9).';
COMMENT ON COLUMN certificado.clave_idempotencia IS 'La clave que el cliente manda en la cabecera idempotency-key. Con su indice unico parcial, reenviar la misma emision devuelve el certificado de la primera vez y no consume otro correlativo ni entrega un segundo papel por el mismo derecho pagado.';
COMMENT ON TABLE certificado_correlativo IS 'El ultimo correlativo de certificado emitido por municipalidad, TIPO y ejercicio (#54). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. Es por tipo porque cada clase de certificado es un tramite del TUPA con su propia serie.';
COMMENT ON TABLE cierre_caja IS 'El turno de una caja: se abre por cajero y fecha (#33) y se cobra contra el. Su fila es donde se serializa la ventanilla —una cobranza la bloquea con FOR UPDATE antes de numerar y de asentar—, y por eso sgtm_app CONSERVA el UPDATE aunque el turno no se edite nunca: PostgreSQL exige ese privilegio para poder bloquear una fila. La inmutabilidad la sostiene el escaner de fuentes (#36, V32 §1.bis). El cierre, su reversion y el estado que de ellos se deriva viven en cierre_turno.';
COMMENT ON COLUMN cierre_caja.fecha_apertura IS 'Cuando se abrio el turno. Sale del reloj INYECTADO de la aplicacion, no de un DEFAULT now() de la base: la fila se audita por ejercicio y el ejercicio tiene que ser el mismo que la aplicacion cree que es.';
COMMENT ON TABLE cierre_turno IS 'El cierre de un turno de caja y su reversion (#36, RF-087). Solo se agrega: un cierre no se modifica ni se borra —se reversa con otro registro que lo deja sin efecto y reabre el turno (regla 4, RNF-051)—. El estado del turno se DERIVA de aqui: hay cierre vigente o no lo hay.';
COMMENT ON COLUMN cierre_turno.secuencia IS 'El orden del movimiento dentro del turno, unico por turno. Es lo que impide dos cierres simultaneos: los dos calculan la misma secuencia y uno recibe 23505. Un indice unico parcial «un solo CIERRE» no serviria, porque despues de una reversion tiene que caber otro.';
COMMENT ON COLUMN cierre_turno.total_anulado IS 'Lo que las anulaciones del dia sacaron del cajon, tomado de recibo_movimiento.importe —el importe congelado, no releido— para los movimientos cuyo turno_id es este. Una anulacion lleva el turno DEL RECIBO (V30 §4): el dinero sale de donde entro.';
COMMENT ON COLUMN cierre_turno.diferencia IS 'Lo declarado menos el neto del sistema. Admite negativo a proposito, y es la unica columna de importe del esquema que lo hace: un arqueo que exigiera diferencia cero haria que el cajero al que le faltan diez soles declarara lo que el sistema diga, y el descuadre desapareceria del papel en vez de quedar escrito.';
COMMENT ON TABLE cierre_turno_detalle IS 'El arqueo del cierre, una fila por medio de pago (#36, RF-087): lo cobrado, lo anulado, el neto del sistema y lo DECLARADO por el cajero. Lo declarado no esta en ningun otro sitio —es lo que se conto en el cajon—, y por eso el desglose se congela aqui en vez de recomponerse sumando recibos.';
COMMENT ON COLUMN ciiu.extendido IS 'El giro lo agrego la municipalidad, no venia en la clasificacion publicada (RF-112). Se guarda para poder distinguir el catalogo normativo de su extension local el dia que la clasificacion oficial se cargue.';
COMMENT ON COLUMN ciiu.riesgo_itse IS 'Nivel de riesgo de la inspeccion tecnica de seguridad que el giro determina. Nulo mientras la municipalidad no lo declare: un valor por omision decidiria por descuido si la ITSE es previa o posterior.';
COMMENT ON TABLE colindante_rural IS 'Predios colindantes de un predio rustico (RF-004), por orientacion.';
COMMENT ON TABLE constancia_libre IS 'La constancia con que la municipalidad acredita que un vehiculo no registra papeletas de transito pendientes (#53, RF-068). Solo se agrega: se entrega al administrado, asi que una equivocada se deja sin efecto con otra, nunca editandola (regla 4).';
COMMENT ON COLUMN constancia_libre.verificada_al IS 'El dia al que se comprobo que no habia papeleta pendiente (regla 9, RNF-075). Sin el, la constancia afirma algo que no se puede fechar.';
COMMENT ON COLUMN construccion.categoria_muros IS 'Categoria del formulario de ficha catastral del manual, no del cuadro de la norma. La ficha declara siete caracteristicas; el cuadro publica tres partidas. Que se parezcan no las hace la misma cosa (V59).';
COMMENT ON TABLE convenio IS 'Un convenio de fraccionamiento (#35, RF-084). NO SE EDITA: su estado se deriva de convenio_movimiento -sin movimientos es un preconvenio; con su FORMALIZACION es vigente; con ANULACION, QUIEBRE o REFORMULACION esta cerrado-. Las columnas de estado que V3 le habia puesto se retiraron en V31 por decir VIGENTE para siempre, igual que las de recibo decian EMITIDO (V30).';
COMMENT ON COLUMN convenio.fecha_corte IS 'A que fecha esta la deuda acogida que monto_total resume (regla 9, RNF-075). No es la fecha del convenio: entre la simulacion y la firma la deuda devenga, y el papel tiene que decir con que corte se calculo.';
COMMENT ON COLUMN convenio.conjunto_id IS 'El conjunto sellado de parametros que dio el interes y el maximo de cuotas (ARQ-09 §3). Recalcular este cronograma en 2037 recupera ESTE conjunto, no «los parametros de 2027»: si entre medias se sello otra version, resolver por ejercicio daria otro interes sin que nada falle.';
COMMENT ON COLUMN convenio.interes_mensual IS 'El interes de fraccionamiento mensual que se aplico, copiado del conjunto sellado. Su VALOR no vive en el codigo (regla 5) y lo firma D-02b (#191): aqui solo queda constancia de cual se uso.';
COMMENT ON COLUMN convenio.clave_idempotencia IS 'La clave que el cliente manda en la cabecera Idempotency-Key al registrar el preconvenio. Con su indice unico parcial, reenviar el mismo intento devuelve el convenio de la primera vez y no abre otro sobre la misma deuda (#606). Nula en el preconvenio que nace de una REFORMULACION: ese acto lo reclama el movimiento de cierre.';
COMMENT ON TABLE convenio_correlativo IS 'El ultimo correlativo de convenio emitido por municipalidad y ejercicio (#35). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE.';
COMMENT ON TABLE convenio_cuota IS 'El cronograma del convenio, congelado (#35). La cuota 0 es la inicial. No se edita: que una cuota este pagada se deriva de convenio_movimiento, no de una columna que habria que actualizar.';
COMMENT ON TABLE convenio_deuda IS 'Que deuda se acogio a un convenio, en que fase estaba y cuanto debia a la fecha de corte (#35). Es la «deuda original» de la consulta, y su fase_origen es lo que hace posible el quiebre: devolverla a ordinaria cuando venia de coactiva dejaria el expediente sin sustento.';
COMMENT ON COLUMN convenio_deuda.fase_origen IS 'La fase en que la obligacion estaba antes del acogimiento. Tesoreria la guarda y la devuelve tal cual; no la interpreta -las fases son de cuentacorriente-.';
COMMENT ON TABLE convenio_movimiento IS 'Lo que le pasa a un convenio despues de registrarse (#35, RF-085, RF-086): FORMALIZACION, ANULACION, QUIEBRE o REFORMULACION. Solo se agrega. El estado de un convenio se DERIVA de aqui, porque el convenio no se edita (V31); es a convenio lo que recibo_movimiento (V30) es a recibo y valor_movimiento (V28) a valor.';
COMMENT ON COLUMN convenio_movimiento.importe IS 'Lo que el acogimiento movio a fase CONVENIO, o lo que el cierre devolvio a su fase de origen. Congelado y no releido: dentro de dos anios el libro dira otra cosa. La aplicacion lo COMPRUEBA contra lo que cuentacorriente dijo haber asentado, en vez de suponerlo.';
COMMENT ON COLUMN convenio_movimiento.clave_idempotencia IS 'La clave que el cliente manda en la cabecera Idempotency-Key al anular, quebrar o reformular (#606). Con su indice unico parcial, reenviar el mismo intento devuelve el acta de la primera vez —201 con el convenio ya cerrado— en vez del 409 que contestaba convenio_movimiento_cierre_uq, que se lee como un fallo nuevo. La FORMALIZACION no la usa: ese acto entra por la caja y lo protege recibo_idempotencia_uq (V29).';
COMMENT ON TABLE corrida_predial IS 'Lo que hizo una corrida de emision anual del predial (#523). Un hecho, no un borrador: sin UPDATE ni DELETE, igual que valor_masivo (V27) y papeleta_masivo (V47).';
COMMENT ON COLUMN corrida_predial.conjunto IS 'El conjunto sellado con el que se emitio (ARQ-09 §3). Sin el, la corrida no se puede repetir dentro de diez anios y dar lo mismo. Vacio si no se determino ninguna.';
COMMENT ON COLUMN corrida_predial.leidos IS 'Cuantos contribuyentes miro en total. Determinados + observados, y se guarda en vez de derivarse porque los observados se pueden purgar y la etapa «padron leido» no.';
COMMENT ON COLUMN corrida_predial.fecha_calculo IS 'El dia al que corresponden sus cifras (regla 9). Sale del reloj inyectado, no de now(): la fila tiene que caer en el mismo dia con que se determino.';
COMMENT ON COLUMN corrida_predial.codigo_desde IS 'Primer codigo de contribuyente del tramo, con alcance RANGO_DE_CODIGO. Se compara como texto: el codigo del padron es una cadena y ni siquiera es siempre numerica (#577).';
COMMENT ON COLUMN corrida_predial.codigo_hasta IS 'Ultimo codigo del tramo, incluido (#577).';
COMMENT ON TABLE corrida_predial_observado IS 'Un contribuyente que quedo fuera de la emision, con su motivo (#523). Es lo unico que convierte «emitio menos de lo esperado» en una lista de cosas que arreglar, y lo unico de la corrida que no se puede recomponer leyendo el padron: un observado es, por definicion, el que NO tiene determinacion.';
COMMENT ON COLUMN corrida_predial_observado.motivo IS 'Por que quedo fuera, redactado por el caso de uso. Un observado sin motivo no se puede arreglar, que es para lo que existe esta tabla.';
COMMENT ON TABLE costa_obligacion IS 'Que expediente coactivo es dueno de la obligacion de costas de un contribuyente, tributo y ejercicio (#42). Existe porque el libro NO distingue expedientes: su clave de obligacion no incluye el numero de expediente y los abonos no copian la referencia externa. Sin esta tabla, dos expedientes del mismo obligado que liquidaran costas del mismo tributo y ejercicio compartirian obligacion, y la columna «Costas S/» diria lo mismo en las dos filas. Con ella, el segundo choca contra la clave y la aplicacion lo explica nombrando al primero.';
COMMENT ON TABLE costa_procesal IS 'Una linea de la liquidacion de costas: el acto que la devenga, el arancel que se le aplico y de que conjunto sellado salio (#42, RF-104). Solo se agrega: V35 le retira el UPDATE. Una costa mal liquidada no se corrige en el sitio -su cargo ya esta en el libro-: se reversa el asiento y se liquida de nuevo.';
COMMENT ON COLUMN costa_procesal.arancel_fuente IS 'De donde salio la cifra: la llave del parametro sellado y el documento fuente que la sustenta (ADR-0007). Nunca un numero escrito en el codigo (regla 5, D-02c, #193).';
COMMENT ON COLUMN cuenta_corriente_asiento.tributo IS 'A que tributo se imputa el asiento, de los doce que declara pe.gob.sgtm.cuentacorriente.TributoDelLibro. Es parte de la clave de una obligacion (saldo_uq), asi que dos grafias del mismo tributo son dos deudas distintas: por eso el vocabulario es cerrado desde V74 (#553). Una fila que reversa a otra queda exceptuada, porque copia el tributo del original y reversar es el unico modo de corregir un asiento (regla 4).';
COMMENT ON COLUMN cuenta_corriente_asiento.acto IS 'De que acto nace el asiento, cuando el libro lo sabe: ALTA_DEUDA o BAJA_DEUDA (RF-043, RF-044). NULL es «no nacio de un alta ni de una baja» —una emision, una cobranza, una reversion—, no «se desconoce». Es lo que permite que «lo cargado» del panel reste las bajas sin restar los cobros: el abono de una baja y el de una cobranza son el mismo asiento columna a columna, y la distincion se hace por el acto, nunca por el signo (V68, #601).';
COMMENT ON COLUMN cuenta_corriente_asiento.unidad_de_titular_anterior IS 'Quien registro el movimiento declaro que el predio o el vehiculo NO es del contribuyente al que se le carga, porque la deuda es de un ejercicio anterior a la transferencia (#635, #653). Es una DECLARACION de quien atiende, no un hecho derivado del padron: la titularidad de hoy no dice quien era titular en el ejercicio de la deuda. false es «nadie lo declaro», que es lo que ocurre en todo asiento anterior a #635 y en todo asiento que no nace de un alta ni de una baja de deuda (V71).';
COMMENT ON COLUMN cuenta_corriente_asiento.causal IS 'Por que se dio de baja la deuda: el sustento juridico del acto (RF-044, #684). Las seis del desplegable «Causal» de la pantalla, letra por letra y sin traducir. Solo una BAJA_DEUDA la lleva; el alta no la tiene. NULL es «esta fila no la declaro» —toda baja anterior a V77, cuya causal viaja dentro del texto de la observacion y no se puede recuperar (V7, regla 4)—, no «se desconoce». No sustituye al motivo, que es la observacion DEL USUARIO (regla 10): una es el sustento y la otra el relato de quien firma (V77).';
COMMENT ON COLUMN depreciacion.municipalidad_id IS 'Siempre nulo: la tabla de depreciacion es nacional (ARQ-09 §2.1, D-13).';
COMMENT ON COLUMN depreciacion.antiguedad_hasta IS 'Extremo superior del tramo de antiguedad, en anios; NULO es «mas de 50 anios», el tramo abierto con que cierra cada tabla del Anexo I. Entra en depreciacion_uq, que por eso se declara NULLS NOT DISTINCT: con la semantica por omision el tramo abierto se podria duplicar con dos porcentajes distintos.';
COMMENT ON COLUMN depreciacion.publicacion_id IS 'La edicion a la que pertenece esta fila (ver valor_unitario_edificacion.publicacion_id).';
COMMENT ON COLUMN depreciacion.uso IS 'La tabla del Anexo I del Reglamento Nacional de Tasaciones a la que pertenece la fila (01 vivienda, 02 tiendas y depositos, 03 oficinas, 04 salud/industria/educacion), con el numero que usa la propia norma. Su titulo verbatim esta en depreciacion.md §1. Que tabla le toca a un predio es criterio y no vive aqui: RT-004 todavia no esta escrita.';
COMMENT ON TABLE descargo IS 'El escrito que el administrado presenta contra una papeleta (#50, RF-064). Solo se agrega: quien lo resuelve es la gerencia, con una resolucion propia; el sentido del fallo vive en resolucion_gerencia y el estado del descargo se deriva de si existe una que lo resuelva.';
COMMENT ON COLUMN descargo.presentado_hasta IS 'El ultimo dia en que el recurso era admisible. Sale del plazo PARAMETRIZADO del conjunto sellado vigente a la fecha de la papeleta (regla 5), nunca de un 5 compilado, y se COPIA aqui porque releerlo daria otra fecha el dia que el plazo cambie (ARQ-09 §3).';
COMMENT ON TABLE determinacion_arbitrio IS 'Una cuota de arbitrio determinada para un predio (#31): limpieza publica, parques y jardines o serenazgo, mes a mes. Nunca se modifica: una redeterminacion es una fila nueva de otro conjunto sellado, o una reversa del asiento que genero (regla 4).';
COMMENT ON TABLE determinacion_predio_detalle IS 'El aporte de cada predio a la base del contribuyente (NEG-05 §1, M02 "detalle de los predios"). Sin esto, un contribuyente con tres predios no se puede explicar de donde sale su base.';
COMMENT ON COLUMN determinacion_predio_detalle.valuo_exonerado IS 'Parte del autovaluo que no esta afecta. La base ponderada del predio es (autovaluo - valuo_exonerado) x porcentaje_propiedad (#395).';
COMMENT ON TABLE dj_correlativo IS 'El ultimo correlativo de declaracion jurada emitido por municipalidad y ejercicio (#365). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. La fila NO se siembra aqui: la crea la primera peticion del ejercicio, arrancando por encima del mayor numero historico de ese año. Sembrarla en la migracion es imposible, y por un motivo que vale anotar: `declaracion_jurada` tiene RLS con FORCE, el migrador corre como sgtm_owner y NO tiene contexto de tenant, asi que un SELECT sobre ella durante la migracion falla con «unrecognized configuration parameter app.municipalidad_id» (DAT-01 §0, cuarto hallazgo).';
COMMENT ON TABLE documento_emitido IS 'Documentos emitidos con los datos que los generaron, para reimprimirlos identicos (RF-132).';
COMMENT ON TABLE edificacion_correlativo IS 'El ultimo correlativo de licencia de edificacion emitido por municipalidad y ejercicio (#48). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE.';
COMMENT ON TABLE edificacion_estructura IS 'La valorizacion del proyecto por pisos y estructuras (#48 AC 2, RF-113): que partida, en que categoria y cuantos metros. NINGUN IMPORTE: el valor por metro cuadrado de cada letra vive en valor_unitario_edificacion (#17) y se lee del conjunto sellado que rija.';
COMMENT ON COLUMN edificacion_estructura.categoria IS 'La letra de la tabla de valores unitarios, de A a I. Es el MISMO dominio que valor_unitario_edificacion.categoria: las dos son mitades de la misma matriz.';
COMMENT ON TABLE edificacion_movimiento IS 'Lo que le pasa a un FUE: su emision -con el numero de licencia y la fecha-, su revalidacion y su anulacion (#48, RF-113). SOLO SE AGREGA. De aqui se deriva el estado, que por eso no es una columna que alguien tenga que acordarse de mover.';
COMMENT ON TABLE edificacion_profesional IS 'Los proyectistas por especialidad y el responsable de obra del FUE (#48, RF-113). Un solo varchar(240) -lo que V4 tenia- no cabe: son varios, y cada uno lleva la colegiatura con que se verifica su habilitacion.';
COMMENT ON TABLE edificacion_proyecto IS 'Las caracteristicas del proyecto del FUE (#48, RF-113). NINGUNA CIFRA DE DINERO: el valor de obra se valoriza con edificacion_estructura contra el cuadro de valores unitarios del conjunto sellado (#17), y guardarlo aqui lo duplicaria (AC 2).';
COMMENT ON TABLE edificacion_requisito IS 'Los documentos adjuntos que el FUE declara presentados (#48, RF-113). QUE requisitos exige cada modalidad es TUPA -ordenanza local, D-02b- y no esta compilado: la tabla registra el nombre con que el TUPA los llama.';
COMMENT ON TABLE edificacion_terreno IS 'Los datos urbanos del FUE: ubicacion, area del terreno, zonificacion y partida registral (#48, RF-113). Se VERSIONA, no se edita: lo que el administrado declaro primero y lo que corrigio despues son los dos datos.';
COMMENT ON TABLE edificacion_vigencia IS 'Cada tramo de vigencia de una licencia de edificacion, con el acto que lo concedio (#48 AC 4). La revalidacion agrega el segundo tramo; el primero queda intacto, y por eso las dos vigencias son trazables.';
COMMENT ON TABLE expediente_coactivo IS 'La carpeta que agrupa los valores exigibles de un contribuyente y lleva su propio ciclo (#40, RF-100). Su estado NO esta aqui: se deriva de expediente_movimiento, por lo mismo que el del convenio (V31) y el del recibo (V30). Solo se agrega.';
COMMENT ON COLUMN expediente_coactivo.direccion_referencial IS 'La direccion referencial CON QUE SE ABRIO el expediente, no la vigente: distinta del domicilio fiscal, es donde el ejecutor notifica cuando aquel no sirve (RF-106). Cambiarla es un acto con motivo y observacion, y la vigente se deriva de expediente_movimiento.';
COMMENT ON COLUMN expediente_coactivo.correlativo IS 'El correlativo dentro del ejercicio, sin formato. `numero` es su forma impresa segun la plantilla que D-09 cerrara; este entero no depende de ella.';
COMMENT ON TABLE expediente_correlativo IS 'El ultimo correlativo de expediente coactivo por municipalidad y ejercicio (#40). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. D-09 decide con que formato se imprime; esta tabla solo garantiza que no se repita ni salte.';
COMMENT ON TABLE expediente_movimiento IS 'El historial del expediente coactivo (#40, RF-100, RF-106): su apertura, cada cambio de estado y cada cambio de direccion referencial, con fecha, usuario, motivo y observacion. De aqui se DERIVA el estado, y la direccion vigente. Solo se agrega: un cambio equivocado se corrige con otro movimiento, nunca editando el anterior.';
COMMENT ON COLUMN expediente_valor.fecha_importacion IS 'Cuando se importo. Sale del reloj inyectado de la aplicacion, nunca de un DEFAULT del motor: una importacion se puede registrar con la fecha en que la resolucion lo dispuso.';
COMMENT ON TABLE internamiento IS 'El ingreso de un vehiculo al deposito municipal (#50, RF-064). Su salida NO se escribe aqui: es una fila de internamiento_movimiento. El estado —INTERNADO, LIBERADO, EN_ABANDONO— se deriva de los movimientos, nunca de una columna que habria que actualizar.';
COMMENT ON COLUMN internamiento.tasa_custodia IS 'El codigo del concepto del TUPA con que se cobra la custodia diaria. La TARIFA no esta aqui: vive en `tasa` vigente a la fecha (regla 5). Copiarla seria una cifra normativa en dos sitios.';
COMMENT ON TABLE internamiento_movimiento IS 'Lo que le pasa a un vehiculo internado (#50, RF-064): su liberacion, con el recibo de la custodia y quien lo retira, o su declaracion de abandono. Solo se agrega: el estado del internamiento se deriva de aqui.';
COMMENT ON TABLE licencia_correlativo IS 'El ultimo correlativo de licencia de funcionamiento emitido por municipalidad y ejercicio (#44). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE.';
COMMENT ON TABLE licencia_duplicado IS 'Cada duplicado autorizado de una licencia, con su resolucion y su recibo (#44, RF-111). El duplicado NO es una licencia nueva: conserva el numero de la original y sale marcado como duplicado, que es lo que EmitirDocumento.reimprimir garantiza comprobando el SHA-256.';
COMMENT ON TABLE licencia_edificacion IS 'La cabecera del Formulario Unico de Edificaciones (#48, RF-113). Es el EXPEDIENTE, no la licencia: nace al presentarse, se completa por partes en las tablas edificacion_* y se convierte en licencia cuando edificacion_movimiento registra su EMISION. Solo se agrega.';
COMMENT ON COLUMN licencia_edificacion.licencia_origen_id IS 'El FUE de la licencia original cuando este tramite es una ampliacion o una revalidacion (AC 3 y AC 4 de #48). La referencia NO sustituye: el original conserva su numero, su vigencia y su papel, y esta tabla ni siquiera admite UPDATE.';
COMMENT ON TABLE licencia_funcionamiento IS 'La licencia municipal de funcionamiento (#44, RF-110). Solo se agrega: su estado se deriva de licencia_movimiento y sus tramites -renovacion, cambio de titular, cese- producen actos nuevos, nunca la edicion del formulario.';
COMMENT ON COLUMN licencia_funcionamiento.numero IS 'El numero de la licencia municipal, el del papel que cuelga en el establecimiento. NO es el numero del documento emitido: la licencia sobrevive a sus papeles, y una con tres duplicados sigue siendo la misma licencia.';
COMMENT ON COLUMN licencia_funcionamiento.recibo_id IS 'El recibo de caja de tasas con que se pago el derecho de tramite. NOT NULL desde V37: sin el pago del derecho no se emite (RF-110). Que ademas sea del titular, no este anulado y cubra el concepto del TUPA lo comprueba EmitirLicenciaDeFuncionamiento contra la API publica de tesoreria, porque exige un JOIN que un CHECK no puede hacer.';
COMMENT ON COLUMN licencia_funcionamiento.ficha_id IS 'La version de la ficha economica del predio vigente al emitir (#19). Se guarda el identificador y no los datos: licencias no conoce fichas catastrales, se la pide a catastro por su puerto publico.';
COMMENT ON TABLE licencia_movimiento IS 'Lo que le pasa a una licencia: su emision y su cancelacion, cada una con la resolucion que la sustenta (#44, RF-111). SOLO SE AGREGA. De aqui se deriva el estado de la licencia, que por eso no es una columna que alguien tenga que acordarse de mover.';
COMMENT ON TABLE liquidacion_correlativo IS 'El ultimo correlativo de liquidacion de fiscalizacion por municipalidad y ejercicio (#49). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. D-09 decide con que formato se imprime; esta tabla solo garantiza que no se repita ni salte.';
COMMENT ON TABLE liquidacion_costas IS 'La liquidacion de costas y gastos de un expediente coactivo (#42, RF-104): que actos se liquidaron, con que arancel sellado y por cuanto. NO guarda saldo: su importe se asienta como CARGO de concepto GASTO en fase COACTIVA, y cuanto queda pendiente lo dice el libro a la fecha que se pida (regla 9). Solo se agrega: no tiene columna de estado porque diria ACTIVA para siempre, igual que las que V30..V34 retiraron.';
COMMENT ON COLUMN liquidacion_costas.conjunto_id IS 'El conjunto sellado del que salieron los aranceles por acto (ARQ-09 §3). Recalcular esta liquidacion en 2037 recupera ESTE conjunto, no «los parametros de 2027»: resolver por ejercicio daria otro arancel sin que nada falle.';
COMMENT ON TABLE liquidacion_costas_correlativo IS 'El ultimo correlativo de liquidacion de costas por municipalidad y ejercicio (#42). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. Mismo patron que valor_correlativo (V26), recibo_correlativo (V29), convenio_correlativo (V31) y expediente_correlativo (V33).';
COMMENT ON TABLE liquidacion_detalle IS 'El contraste hallado/declarado de una liquidacion, una fila por unidad y ejercicio (#49, RF-053). La comparacion estructural -area y uso- se guarda; la monetaria espera a D-02a (#198) y sus columnas van con nombre y sin cifra.';
COMMENT ON COLUMN liquidacion_detalle.conjunto_id IS 'El conjunto de parametros SELLADO del ejercicio de esta linea, copiado al emitir. Todo recalculo lo lee por este identificador y nunca por ejercicio: resolver «el vigente del ejercicio» devolveria otra version el dia que se selle una nueva, y la liquidacion ya emitida cambiaria de cifra sin que nada fallara (ARQ-09 §3, AC 1 de #49).';
COMMENT ON TABLE liquidacion_fiscalizacion IS 'La liquidacion de un proceso de fiscalizacion (#49, RF-053): el consolidado de lo hallado frente a lo declarado para un acta y un periodo. Solo se agrega: una liquidacion equivocada se reliquida -otra version que referencia esta- o se anula con un movimiento, nunca editandola.';
COMMENT ON COLUMN liquidacion_fiscalizacion.liquidacion_anterior_id IS 'La liquidacion que esta reliquida. Las dos quedan y las dos se pueden leer: la anterior explica por que se notifico lo que se notifico, y la nueva por que ya no vale (AC 2).';
COMMENT ON TABLE liquidacion_movimiento IS 'El historial de una liquidacion de fiscalizacion (#49, RF-056): su apertura y cada cambio de estado, con fecha, usuario, motivo y observacion. De aqui se DERIVA el estado; la cabecera no tiene columna de estado porque, sin UPDATE, diria ABIERTA para siempre.';
COMMENT ON TABLE municipalidad IS 'Registro de tenants. No es tabla de tenant: la aplicacion la lee entera porque los procesos masivos iteran municipalidad por municipalidad. Solo sgtm_owner escribe.';
COMMENT ON COLUMN municipalidad.es_demostracion IS 'Instalacion de demostracion: todo documento emitido bajo este tenant sale marcado, en los tres formatos. Lo lee la capa de documentos, no cada emisor. Solo sgtm_owner la escribe, como el alta de la municipalidad.';
COMMENT ON COLUMN notificacion.intento IS 'Que diligencia es. Una no hallada no se corrige: se registra otra con el intento siguiente, y la anterior se queda (AC de #39).';
COMMENT ON COLUMN notificacion.exigible_desde IS 'Desde cuando la deuda del valor notificado es exigible: se deriva de la fecha de la diligencia y del plazo PARAMETRIZADO, nunca de una constante (#39, regla 5). Sin ella el expediente coactivo es nulo.';
COMMENT ON COLUMN papeleta.obligado_id IS 'El contribuyente contra el que se asento el cargo de la multa (#46, #47). Sin el no se puede encontrar la obligacion que un descargo fundado tiene que dar de baja (#50), ni imprimir el «Obligado» de la resolucion de gerencia. No se deduce de infractor/propietario/contribuyente: el manual permite cobrarle al propietario aunque condujera otro.';
COMMENT ON TABLE papeleta_masivo IS 'El criterio de una generacion masiva de valores por papeletas (#53, RF-066, RF-073), congelado al registrarlo: reanudar la generacion no vuelve a evaluar «hoy», evalua fecha_criterio. No numera nada; el correlativo sale de valor_correlativo (V26).';
COMMENT ON COLUMN papeleta_masivo.fecha_criterio IS 'La fecha a la que se mira la deuda y la exigibilidad de cada papeleta candidata (regla 9). Congelada: dos ejecuciones de la misma corrida tienen que ver lo mismo.';
COMMENT ON TABLE papeleta_masivo_item IS 'Una papeleta candidata de una corrida masiva (#53), con su estado. La generacion recorre los PENDIENTE; no hay tabla de progreso aparte. papeleta_valor_unico_uq garantiza un valor por papeleta aunque se relance la corrida o se lance otra.';
COMMENT ON TABLE participacion_comun IS 'Porcentaje de participacion de cada unidad en los bienes comunes de la edificacion.';
COMMENT ON TABLE prescripcion IS 'La declaracion de prescripcion de la accion de cobro (#39, RF-094). No borra deuda: la marca. El libro de asientos no se toca, y los valores alcanzados pasan a estado PRESCRITO (regla 4).';
COMMENT ON COLUMN prescripcion.conjunto_id IS 'De que conjunto sellado salio plazo_anios. Sin esto, revisar la resolucion dentro de dos anios resolveria "el vigente del ejercicio" y podria dar otro plazo (ARQ-09 §3).';
COMMENT ON TABLE prescripcion_hecho IS 'Que causal de interrupcion (art. 45) o de suspension (art. 46) entro en el computo, fila a fila. Es lo que NEG-02 anotaba como "lo que no cabe hoy" en docs/10-negocio/valores-normativos/prescripcion-y-plazos.md §3.';
COMMENT ON COLUMN programa_fiscalizacion.ejercicio IS 'El ejercicio que el programa examina. NO se deduce del anio de fecha_inicio: un programa abierto en enero de 2027 puede estar fiscalizando 2025, y deducirlo diria otra cosa que el filtro de la pantalla (#457).';
COMMENT ON COLUMN programa_fiscalizacion.sector_codigo IS 'El sector del padron sobre el que se sortea la muestra, o NULL para todo el distrito. Es el codigo de sector.codigo (V1), no su identificador: es lo que PadronDePredios.porSector recibe.';
COMMENT ON COLUMN programa_fiscalizacion.criterio IS 'La CondicionFiscalizada que se busca. El CHECK reproduce el enumerado entero, que es lo que la columna guarda; la pantalla ofrece SOLO las tres que un cruce de gabinete puede producir -OMISO, SUBVALUADOR, CONFORME-: USO_DISTINTO exige el uso declarado, que DeteccionDeOmisos no resuelve, y NO_UBICADO no es un criterio de seleccion por definicion (no se puede programar la visita a los predios que no se van a encontrar).';
COMMENT ON COLUMN programa_fiscalizacion.fiscalizador IS 'Quien tiene asignado el programa. Es de donde el acta toma SU fiscalizador: el catalogo lo dibuja de solo lectura, y un campo de solo lectura lo llena el sistema (RNF-080).';
COMMENT ON TABLE programa_muestra IS 'Los predios que un programa de fiscalizacion sorteo para inspeccionar (#481, RF-050). Es la LISTA DE TRABAJO de la visita, no una imputacion: estar en ella no le cobra nada a nadie, y por eso admite el predio sin titular vigente (#586). SOLO SE AGREGA: un predio sale de la muestra marcandolo -con su acta-, nunca borrandolo (RNF-051, regla 4). sgtm_app no recibe UPDATE ni DELETE sobre esta tabla.';
COMMENT ON COLUMN programa_muestra.contribuyente_id IS 'El titular principal del predio -el de mayor porcentaje- a la fecha del sorteo, o NULL si el predio no tiene ninguno vigente (#586). NULO NO ES UN DATO QUE FALTE: es el predio que nadie reclama, el candidato de primer orden de la fiscalizacion, y la visita es lo que resuelve quien lo ocupa. Quien fiscaliza nombra al contribuyente en el acta, que lo sigue exigiendo (acta_fiscalizacion.contribuyente_id, V4): sin obligado no hay a quien notificar ni a quien asentarle el cargo.';
COMMENT ON COLUMN programa_muestra.condicion IS 'La CondicionFiscalizada que DeteccionDeOmisos calculo el dia del sorteo, copiada. Es lo que la grilla dibuja en la columna «Riesgo» y lo que contesta por que este predio entro al programa.';
COMMENT ON COLUMN programa_muestra.fecha_sorteo IS 'El dia al que se resolvieron el padron, la titularidad y la ficha vigentes. La muestra es una foto: un predio que regulariza despues sigue aqui con la condicion de ese dia hasta que alguien lo visite.';
COMMENT ON COLUMN recibo.campania_beneficio IS 'Que campana de beneficio se declaro en ventanilla. Hoy es SOLO constancia: el importe cobrado es el integro. Aplicarle un descuento esta bloqueado por D-02b, que es la que firma los valores de ordenanza local con su ratificacion provincial (#33).';
COMMENT ON COLUMN recibo.actualizado_a IS 'A que fecha estaban actualizados los importes que este recibo cobro (regla 9, RNF-075). En caja tributaria es la fecha de pago con la que se releyo deudaActualizadaA; en caja de tasas, la fecha a la que la tarifa del TUPA estaba vigente. Sin ella un duplicado no puede explicar por que su interes no es el de hoy.';
COMMENT ON COLUMN recibo.clave_idempotencia IS 'La clave que el cliente manda en la cabecera idempotency-key. Con su indice unico parcial, reenviar la misma cobranza devuelve el recibo de la primera y no emite otro.';
COMMENT ON TABLE recibo_correlativo IS 'El ultimo numero emitido por municipalidad y serie de recibo (#33). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE. El UPDATE bloquea la fila, asi que dos cobranzas concurrentes de la misma caja se serializan en el motor y salen con numeros consecutivos, sin huecos ni repetidos.';
COMMENT ON TABLE recibo_movimiento IS 'Lo que le pasa a un recibo despues de emitirse (#34, RF-082, RF-083): ANULACION o DUPLICADO. Solo se agrega. El estado de un recibo se DERIVA de aqui, porque el recibo no se edita (V29); las columnas de anulacion que V3 le habia puesto se retiraron en esta misma migracion por decir EMITIDO para siempre.';
COMMENT ON COLUMN recibo_movimiento.turno_id IS 'El turno DEL RECIBO, no el de quien anula: una anulacion del mismo dia saca dinero del cajon en el que entro, y el arqueo de ese turno (#36) tiene que poder restarla.';
COMMENT ON COLUMN recibo_movimiento.importe IS 'El importe del recibo que deja de estar cobrado, copiado y no releido. Dentro de dos anios el libro dira otra cosa -habra mas asientos- y el acta de anulacion tiene que explicarse sola. Es lo que el arqueo del turno (#36) resta del cajon; en una cobranza tributaria coincide con lo que la reversion devolvio al libro, y la aplicacion lo comprueba.';
COMMENT ON COLUMN recibo_movimiento.resumen IS 'SHA-256 del recibo dibujado a partir de lo congelado. Misma garantia que documento_emitido.resumen (V15): la segunda reimpresion se compara con la primera y FALLA si no coincide, en vez de entregar un papel distinto con el mismo numero.';
COMMENT ON TABLE resolucion_determinacion IS 'La transferencia a rentas de un resultado de fiscalizacion y la resolucion de determinacion que la materializa (#52, RF-054, RF-057). Es el unico acto que convierte lo hallado en dato oficial del padron: deja la ficha anterior intacta, abre una version nueva con origen FISCALIZACION, asienta los cargos de la diferencia en el libro y emite el papel notificable. Solo se agrega: una resolucion equivocada se deja sin efecto con otro acto.';
COMMENT ON COLUMN resolucion_determinacion.ficha_nueva_id IS 'La version de ficha que este acto abrio. Con ficha_anterior_id es lo que permite responder «como estaba el padron antes de esta transferencia» sin recorrer fechas, y lo que ata la version nueva al acto que la justifica (AC 2 y AC 5 de #52).';
COMMENT ON COLUMN resolucion_determinacion.documento_sustento IS 'El papel que sustenta el acto -el acta de la inspeccion, el expediente-. Sin sustento no se transfiere (AC 3 de #52): la columna es NOT NULL y quien escribe lo exige antes de tocar el padron.';
COMMENT ON TABLE resolucion_gerencia IS 'Las resoluciones que la gerencia dicta sobre una papeleta (#50, RF-065, RF-074): la ordinaria que ordena la cobranza, la sancionadora que la sigue y deriva la sancion accesoria, y la del procedimiento administrativo sancionador. Solo se agrega: una resolucion se deja sin efecto con otra, nunca editandola.';
COMMENT ON COLUMN resolucion_gerencia.ordinaria_exigible_desde IS 'Desde cuando, vencido el plazo que la ordinaria concedio y contado desde su notificacion, se puede dictar la sancionadora. Se COPIA de notificacion.exigible_desde, que sale del plazo PARAMETRIZADO del conjunto sellado (regla 5): recalcularla al leer daria otra fecha el dia que el plazo cambie.';
COMMENT ON TABLE respaldo IS 'Estado de las copias de seguridad (RF-126). La aplicacion solo lee: quien hace la copia y escribe aqui es el proceso de despliegue, como sgtm_owner.';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada IS 'Instante en que se comprobo, restaurandola de verdad, que esta copia se puede restaurar (RNF-079). NULO significa «nunca se probo», nunca «hoy».';
COMMENT ON COLUMN respaldo.ultima_restauracion_verificada_por IS 'Que proceso lo comprobo: el simulacro de restauracion y el ambiente contra el que corrio. No es un usuario de la aplicacion: la aplicacion no restaura.';
COMMENT ON TABLE responsable_solidario IS 'Quien responde por la deuda ademas del contribuyente (RF-012), con vigencia.';
COMMENT ON COLUMN saldo_proyectado.tributo IS 'El tributo de la obligacion, derivado siempre de sus asientos. Sin CHECK a proposito: es cache reconstruible y acotarla impediria reproyectar una obligacion con grafia anterior a V74, que es justo la que hay que poder seguir leyendo y detectando (#553).';
COMMENT ON TABLE tierra_rural IS 'Grupos de tierra de un predio rustico (RF-004), en hectareas, con su clasificacion y riego.';
COMMENT ON COLUMN transferencia.tipo_transferencia IS 'Que acto fue, de los nueve que dibujan los dos desplegables «Tipo de acto» del manual (pe.gob.sgtm.rentas.dominio.TipoTransferencia). NO decide la afectacion a alcabala: eso es afecta_alcabala, y depende ademas de quien adquiere (TUO LTM art. 28) y de quien vende (art. 22), que esta columna no ve (V64, #542).';
COMMENT ON TABLE valor_correlativo IS 'El ultimo correlativo emitido por municipalidad, tipo y ejercicio de valor (#37). Se lee y se incrementa en una sola sentencia UPSERT; nunca con SELECT + UPDATE.';
COMMENT ON TABLE valor_masivo IS 'La etapa "criterio" de una generacion masiva (#38), congelada al registrarla: reanudar la generacion no vuelve a evaluar "hoy", evalua fecha_criterio.';
COMMENT ON TABLE valor_masivo_item IS 'Un contribuyente candidato de una corrida masiva (#38), con su estado. La generacion recorre los PENDIENTE; no hay una tabla de progreso aparte.';
COMMENT ON TABLE valor_movimiento IS 'El movimiento de un valor hacia coactiva (#39, RF-095): PCO pase, ACO aceptado, RCO rechazado. Es lo que `coactiva` importa (#40). Solo se agrega: un movimiento equivocado se corrige con otro movimiento, no editando el anterior.';
COMMENT ON COLUMN valor_referencial_vehiculo.municipalidad_id IS 'Siempre nulo: la tabla de valores referenciales la aprueba el MEF (ARQ-09 §2.1, D-13).';
COMMENT ON COLUMN valor_referencial_vehiculo.categoria IS 'La categoria con que el anexo del MEF publica la fila (A1..A4, BUSES Y OMNIBUSES, CAMIONES, CAMIONETAS, REMOLCADORES). Es parte de la identidad: el anexo publica «OTROS MODELOS» en cada categoria, con un valor distinto en cada una.';
COMMENT ON COLUMN valor_referencial_vehiculo.publicacion_id IS 'La edicion a la que pertenece esta fila (ver valor_unitario_edificacion.publicacion_id).';
COMMENT ON COLUMN valor_unitario_edificacion.municipalidad_id IS 'Siempre nulo: el cuadro de valores unitarios es nacional (ARQ-09 §2.1, D-13). La columna se conserva para que la politica de RLS compare algo y para que admitir una fila municipal exija quitar valor_unitario_nacional_ck y justificarlo.';
COMMENT ON COLUMN valor_unitario_edificacion.partida IS 'Las TRES partidas de apreciacion exterior del Cuadro de Valores Unitarios: MUROS (muros y columnas), TECHOS, PUERTAS (puertas y ventanas). No son las siete de construccion.categoria_*, que son el formulario del manual y otra cosa (V59).';
COMMENT ON COLUMN valor_unitario_edificacion.categoria IS 'La fila del cuadro de valores unitarios, A..J. La J existe solo en el Anexo I.4 (Selva) y solo en la partida de muros y columnas; el rango es unico porque esta columna no sabe de que region es el cuadro (V58).';
COMMENT ON COLUMN valor_unitario_edificacion.anio_construccion_desde IS 'Extremo inferior del ano de construccion al que aplica esta letra (NEG-05 RT-002, ../srtm): el cuadro de valores unitarios es una matriz categoria x ano de construccion, no solo categoria.';
COMMENT ON COLUMN valor_unitario_edificacion.anio_construccion_hasta IS 'Extremo superior del tramo; nulo cuando la tabla no le pone tope (la construccion mas reciente).';
COMMENT ON COLUMN valor_unitario_edificacion.publicacion_id IS 'La edicion a la que pertenece esta fila: un parametro_tributario que es la cabecera del cuadro. El conjunto sellado de una municipalidad la compone por conjunto_parametro_detalle.';
COMMENT ON COLUMN via.nombre_busqueda IS 'El nombre en minusculas, sin tildes y sin espacios repetidos (V11). Existe para que la busqueda por prefijo compare una columna desnuda: envuelta en la funcion, la condicion no es leakproof y no llega al indice bajo RLS (#565).';
