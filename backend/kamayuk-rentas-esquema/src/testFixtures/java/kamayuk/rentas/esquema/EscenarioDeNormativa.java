package kamayuk.rentas.esquema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <b>FIXTURE DE PRUEBA</b>: las tablas de {@code normativa}, recreadas aqui con nombre propio para
 * poder sembrar el escenario de una prueba.
 *
 * <h2>Por que existen y por que se llaman `_de_prueba`</h2>
 *
 * <p>Las seis tablas de valores normativos se fueron en `V2`. Lo que veinte clases de prueba de
 * este backend necesitan no era esa tabla sino <b>la premisa</b>: «esta municipalidad tiene un
 * conjunto sellado con estos valores». Estas tablas son donde se escribe esa premisa, y desde ellas
 * {@link kamayuk.rentas.parametros.aplicacion.AdministrarParametros#sellar} la copia a la cache
 * local —{@code normativa_*}, `V3`—, que es exactamente donde en produccion la deja una descarga
 * verificada.
 *
 * <p><b>Llevan el sufijo `_de_prueba` a proposito.</b> Conservarles el nombre original habria
 * ahorrado un {@code sed} y dejado en el arbol veinte clases con SQL contra {@code
 * parametro_tributario}: quien lo leyera concluiria que la tabla sigue estando aqui, y el escaner
 * de frontera de sistema —que sabe que esa tabla es de {@code normativa}— no podria distinguir una
 * siembra de prueba de un cruce de verdad. Una instruccion falsa cuesta mas que una que falta.
 *
 * <p>Y no llevan RLS ni privilegios acotados: no son datos de nadie, son el guion de una prueba. Lo
 * que si tiene RLS es la cache a la que se copian, que es lo que el codigo de produccion lee.
 */
public final class EscenarioDeNormativa {

    private EscenarioDeNormativa() {}

    /**
     * Crea las tablas si no estan. Idempotente: cada clase de prueba provisiona su propia base, y
     * dentro de una hay varias que llaman aqui.
     */
    public static void crear(Connection conexion) throws SQLException {
        try (Statement sentencia = conexion.createStatement()) {
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS parametro_tributario_de_prueba (
                        id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        municipalidad_id bigint,
                        tipo             varchar(40)  NOT NULL,
                        clave            varchar(120),
                        valor_numerico   numeric(18,6),
                        valor_texto      text,
                        vigencia_desde   date,
                        vigencia_hasta   date,
                        documento_fuente varchar(200) NOT NULL,
                        sellado          boolean NOT NULL DEFAULT false,
                        usuario_carga    varchar(60),
                        usuario_aprueba  varchar(60)
                    )
                    """);
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS conjunto_parametros_de_prueba (
                        id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        municipalidad_id bigint,
                        ejercicio        smallint  NOT NULL,
                        version          integer   NOT NULL DEFAULT 1,
                        estado           varchar(10) NOT NULL DEFAULT 'ABIERTO',
                        fecha_sellado    timestamptz,
                        usuario_sellado  varchar(60)
                    )
                    """);
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS conjunto_parametro_detalle_de_prueba (
                        municipalidad_id bigint,
                        conjunto_id      bigint NOT NULL,
                        parametro_id     bigint NOT NULL
                    )
                    """);
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS valor_unitario_de_prueba (
                        id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        municipalidad_id        bigint,
                        publicacion_id          bigint NOT NULL,
                        partida                 varchar(20) NOT NULL,
                        categoria               text NOT NULL,
                        anio_construccion_desde smallint NOT NULL,
                        anio_construccion_hasta smallint,
                        valor_m2                numeric(18,6) NOT NULL,
                        documento_fuente        varchar(200) NOT NULL
                    )
                    """);
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS depreciacion_de_prueba (
                        id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        municipalidad_id    bigint,
                        publicacion_id      bigint NOT NULL,
                        uso                 char(2) NOT NULL,
                        material            varchar(30) NOT NULL,
                        estado_conservacion varchar(20) NOT NULL,
                        antiguedad_hasta    smallint,
                        porcentaje          numeric(7,4) NOT NULL,
                        documento_fuente    varchar(200) NOT NULL
                    )
                    """);
            sentencia.execute(
                    """
                    CREATE TABLE IF NOT EXISTS valor_referencial_de_prueba (
                        id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        municipalidad_id bigint,
                        publicacion_id   bigint NOT NULL,
                        ejercicio        smallint NOT NULL,
                        categoria        varchar(20) NOT NULL,
                        marca            varchar(60) NOT NULL,
                        modelo           varchar(80) NOT NULL,
                        anio_fabricacion smallint NOT NULL,
                        valor            numeric(15,2) NOT NULL,
                        documento_fuente varchar(200) NOT NULL
                    )
                    """);
            for (String tabla :
                    new String[] {
                        "parametro_tributario_de_prueba",
                        "conjunto_parametros_de_prueba",
                        "conjunto_parametro_detalle_de_prueba",
                        "valor_unitario_de_prueba",
                        "depreciacion_de_prueba",
                        "valor_referencial_de_prueba"
                    }) {
                // Sin DELETE, y no por prudencia: `AislamientoMultiTenantTest` exige que
                // `kamayuk_app` no lo tenga en NINGUNA tabla (RNF-051), y una tabla de escenario
                // que
                // se lo concediera pondria esa prueba en rojo por un motivo que no es el suyo.
                sentencia.execute("GRANT SELECT, INSERT, UPDATE ON " + tabla + " TO PUBLIC");
            }

            // ----------------------------------------------------------------
            // El disparador que hace de DESCARGA
            //
            // En produccion, entre el escenario y el calculo hay una descarga verificada que deja
            // el conjunto en la cache local (`normativa_*`, `V3`). En una prueba no hay red, y
            // veinte clases sellan su conjunto con SQL directo —`UPDATE ... SET estado='SELLADO'`—
            // sin pasar por ningun objeto Java al que se le pueda pedir que descargue.
            //
            // Este disparador es ese paso: al sellar, copia. Con eso, lo que las pruebas ejercitan
            // despues es el codigo de produccion leyendo de las tablas de produccion —
            // `ValuacionRepositoryJdbc` y `ValorReferencialRepositoryJdbc` leen `normativa_*` y no
            // saben que existe ningun escenario—.
            //
            // Va `SECURITY DEFINER` y lo posee el superusuario que provisiona: las tablas de la
            // cache llevan RLS con FORCE, y el sellado ocurre en conexiones que no siempre tienen
            // fijado el contexto de tenant. La municipalidad no se inventa: sale de la fila del
            // conjunto, que es quien la declara.
            // ----------------------------------------------------------------
            sentencia.execute(
                    """
                    CREATE OR REPLACE FUNCTION escenario_de_normativa_al_sellar()
                    RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER AS $fn$
                    DECLARE
                        muni bigint := NEW.municipalidad_id;
                    BEGIN
                        IF NEW.estado <> 'SELLADO' THEN
                            RETURN NEW;
                        END IF;
                        IF muni IS NULL THEN
                            muni := NULLIF(current_setting('app.municipalidad_id', true), '')::bigint;
                        END IF;
                        IF muni IS NULL THEN
                            RETURN NEW;
                        END IF;

                        INSERT INTO normativa_conjunto
                            (municipalidad_id, conjunto_id, ejercicio, version, ambito,
                             sha256, filas, origen, descargado_en)
                        SELECT muni, NEW.id, NEW.ejercicio, NEW.version, 'OBLIGACION',
                               repeat('0', 64), 0, 'escenario de prueba', now()
                        ON CONFLICT DO NOTHING;

                        INSERT INTO normativa_parametro
                            (municipalidad_id, conjunto_id, tipo, clave, valor_numerico,
                             valor_texto, vigencia_desde, vigencia_hasta, documento_fuente)
                        SELECT muni, NEW.id, p.tipo, p.clave, p.valor_numerico, p.valor_texto,
                               p.vigencia_desde, p.vigencia_hasta, p.documento_fuente
                          FROM parametro_tributario_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d ON d.parametro_id = p.id
                         WHERE d.conjunto_id = NEW.id;

                        INSERT INTO normativa_valor_unitario
                            (municipalidad_id, conjunto_id, partida, categoria,
                             anio_construccion_desde, anio_construccion_hasta, valor_m2,
                             documento_fuente)
                        SELECT muni, NEW.id, v.partida, v.categoria, v.anio_construccion_desde,
                               v.anio_construccion_hasta, v.valor_m2, v.documento_fuente
                          FROM valor_unitario_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = NEW.id;

                        INSERT INTO normativa_depreciacion
                            (municipalidad_id, conjunto_id, uso, material, estado_conservacion,
                             antiguedad_hasta, porcentaje, documento_fuente)
                        SELECT muni, NEW.id, p.uso, p.material, p.estado_conservacion,
                               p.antiguedad_hasta, p.porcentaje, p.documento_fuente
                          FROM depreciacion_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = p.publicacion_id
                         WHERE d.conjunto_id = NEW.id;

                        INSERT INTO normativa_valor_referencial
                            (municipalidad_id, conjunto_id, ejercicio, categoria, marca, modelo,
                             anio_fabricacion, valor, documento_fuente)
                        SELECT muni, NEW.id, v.ejercicio, v.categoria, v.marca, v.modelo,
                               v.anio_fabricacion, v.valor, v.documento_fuente
                          FROM valor_referencial_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = NEW.id;

                        RETURN NEW;
                    END;
                    $fn$
                    """);
            sentencia.execute(
                    "DROP TRIGGER IF EXISTS escenario_al_sellar ON conjunto_parametros_de_prueba");
            sentencia.execute(
                    "CREATE TRIGGER escenario_al_sellar AFTER INSERT OR UPDATE ON"
                            + " conjunto_parametros_de_prueba FOR EACH ROW EXECUTE FUNCTION"
                            + " escenario_de_normativa_al_sellar()");
        }
        conexion.commit();
    }
}
