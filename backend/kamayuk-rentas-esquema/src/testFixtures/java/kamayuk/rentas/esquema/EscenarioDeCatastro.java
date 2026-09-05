package kamayuk.rentas.esquema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <b>FIXTURE DE PRUEBA</b>: las quince tablas de {@code catastro}, recreadas aqui con nombre propio
 * para poder sembrar el escenario de una prueba (P5C).
 *
 * <h2>Por que existen y por que se llaman `_de_prueba`</h2>
 *
 * <p>`V6` retiro las quince: el sistema del predio vive en el repositorio {@code catastro}. Lo que
 * treinta y cuatro clases de prueba de este backend necesitaban de ellas no era la tabla sino <b>la
 * premisa</b>: «este predio existe y tiene esta ficha». Estas tablas son donde se escribe.
 *
 * <p><b>Llevan el sufijo `_de_prueba` a proposito</b>, y es la misma decision que P5B tomo con
 * {@link EscenarioDeNormativa}: conservarles el nombre original habria ahorrado un {@code sed} y
 * dejado en el arbol treinta y cuatro clases con SQL contra {@code predio}, de modo que quien lo
 * leyera concluiria que la tabla sigue estando aqui — y el escaner de frontera de sistema, que sabe
 * que esa tabla es de {@code catastro}, no podria distinguir una siembra de prueba de un cruce de
 * verdad. Una instruccion falsa cuesta mas que una que falta.
 *
 * <h2>Que NO llevan, y por que</h2>
 *
 * <p>Ni RLS, ni claves foraneas, ni disparadores, ni las columnas generadas de la geometria. Su
 * {@code municipalidad_id} es <b>anulable</b>: si fuera {@code NOT NULL}, {@code
 * AislamientoMultiTenantTest} les exigiria RLS sola —esa es exactamente su regla— y estariamos
 * manteniendo la politica de unas tablas que no son de nadie.
 *
 * <p>Y no llevan {@code DELETE}: la prueba de aislamiento exige que {@code kamayuk_app} no lo tenga
 * en ninguna tabla de esta base (RNF-051, regla 4), y una tabla de escenario que se lo concediera
 * la pondria en rojo por un motivo que no es el suyo.
 *
 * <h2>Lo que estas tablas NO prueban</h2>
 *
 * <p>Nada de {@code catastro}. No versionan fichas, no comprueban que los porcentajes de
 * titularidad no excedan 100 y no resuelven la version vigente a una fecha: todo eso son reglas
 * suyas, con sus 425 pruebas contra PostgreSQL en su repositorio. Aqui solo se siembra el escenario
 * del que {@code rentas} cuelga sus actos, y quien lee esas filas es {@link ProyeccionDeCatastro},
 * que hace de ingestor.
 */
public final class EscenarioDeCatastro {

    private EscenarioDeCatastro() {}

    /** Crea las tablas si no estan. Idempotente. */
    public static void crear(Connection conexion) throws SQLException {
        try (Statement sentencia = conexion.createStatement()) {
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS via_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        codigo character varying(20) NOT NULL,
                        tipo_via character varying(20) NOT NULL,
                        nombre character varying(160) NOT NULL,
                        ubigeo character(6),
                        activa boolean DEFAULT true NOT NULL,
                        nombre_busqueda text
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON via_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sector_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        codigo character varying(10) NOT NULL,
                        nombre character varying(160) NOT NULL,
                        zona character varying(80),
                        activo boolean DEFAULT true NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON sector_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS manzana_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        sector_id bigint NOT NULL,
                        codigo character varying(10) NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON manzana_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS predio_de_prueba (
                        municipalidad_id bigint,
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
                        fecha_registro timestamp with time zone DEFAULT now() NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON predio_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ficha_catastral_de_prueba (
                        municipalidad_id bigint,
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
                    )
                    """);
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON ficha_catastral_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS construccion_de_prueba (
                        municipalidad_id bigint,
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
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON construccion_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS otra_instalacion_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        ficha_id bigint NOT NULL,
                        descripcion character varying(160) NOT NULL,
                        unidad_medida character varying(20) NOT NULL,
                        cantidad numeric(12,2) NOT NULL,
                        anio_construccion ejercicio,
                        estado_conservacion character varying(20)
                    )
                    """);
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON otra_instalacion_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS titularidad_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        predio_id bigint NOT NULL,
                        contribuyente_id bigint NOT NULL,
                        condicion character varying(30) NOT NULL,
                        porcentaje porcentaje NOT NULL,
                        vigencia_desde date NOT NULL,
                        vigencia_hasta date,
                        documento_origen character varying(80) NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON titularidad_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS inquilino_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        predio_id bigint NOT NULL,
                        contribuyente_id bigint NOT NULL,
                        uso character varying(60),
                        vigencia_desde date NOT NULL,
                        vigencia_hasta date,
                        documento_origen character varying(80) NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON inquilino_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS arancel_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        via_id bigint NOT NULL,
                        tramo character varying(80),
                        valor_m2 monto_calc NOT NULL,
                        documento_fuente character varying(200) NOT NULL,
                        conjunto_id bigint NOT NULL
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON arancel_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS actividad_economica_de_prueba (
                        municipalidad_id bigint,
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
                    )
                    """);
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON actividad_economica_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS bien_comun_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        ficha_id bigint NOT NULL,
                        descripcion character varying(160) NOT NULL,
                        area area_m2 NOT NULL,
                        material_estructural character varying(20),
                        estado_conservacion character varying(20),
                        anio_construccion ejercicio
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON bien_comun_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS colindante_rural_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        ficha_id bigint NOT NULL,
                        orientacion character varying(10) NOT NULL,
                        descripcion character varying(200) NOT NULL
                    )
                    """);
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON colindante_rural_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS participacion_comun_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        ficha_id bigint NOT NULL,
                        predio_id bigint NOT NULL,
                        porcentaje porcentaje NOT NULL
                    )
                    """);
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON participacion_comun_de_prueba TO PUBLIC");
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tierra_rural_de_prueba (
                        municipalidad_id bigint,
                        id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
                        ficha_id bigint NOT NULL,
                        clasificacion character varying(60) NOT NULL,
                        calidad_agrologica character varying(40),
                        riego character varying(20) DEFAULT 'SECANO'::character varying NOT NULL,
                        cantidad_hectareas numeric(12,4) NOT NULL,
                        cantidad_hectareas_comun numeric(12,4)
                    )
                    """);
            sentencia.execute("GRANT SELECT, INSERT, UPDATE ON tierra_rural_de_prueba TO PUBLIC");
        }
        // La conexion llega con autoCommit en false y se cierra al salir del try de quien llama:
        // sin este commit las tablas se crean y se pierden, y el sintoma es exactamente el mismo
        // que si no se hubieran creado.
        conexion.commit();
    }
}
