package kamayuk.rentas.esquema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * <b>FIXTURE DE PRUEBA</b>: hace correr al ingestor de la proyeccion de {@code catastro} (P5C).
 *
 * <h2>Para que existe</h2>
 *
 * <p>Desde P5C, las consultas de {@code rentas} que necesitaban cruzar a catastro leen la
 * proyeccion local (`V4`) y no las tablas del vecino. Las pruebas siguen sembrando el escenario
 * como siempre —predios y fichas—, y lo que falta entre una cosa y otra es <b>el paso del
 * ingestor</b>: en produccion lo dispara un evento; aqui lo dispara esta clase.
 *
 * <h2>Por que escribe con OTRO rol</h2>
 *
 * <p>Porque `V4` no le da a `sgtm_app` mas que `SELECT` sobre las tres tablas, y eso no es una
 * precaucion: es lo que hace que ADR-0027 §3 sea cierto en vez de una promesa. Una prueba que
 * pudiera escribir la proyeccion con la conexion de la aplicacion estaria midiendo un sistema que
 * no es el que se despliega.
 *
 * <h2>Lo que esta clase NO es</h2>
 *
 * <p>No es el ingestor de produccion. Copia de las tablas de catastro que todavia viven en esta
 * base; el de produccion recibe los datos dentro del evento y no tiene de donde copiarlos. El dia
 * que esas tablas se retiren de aqui, lo que cambia es de donde salen las filas —del evento— y no
 * quien las escribe ni con que privilegio.
 */
public final class ProyeccionDeCatastro {

    private ProyeccionDeCatastro() {}

    /**
     * Proyecta el catastro de una municipalidad entera: sus predios y las versiones de sus fichas.
     *
     * <p>Idempotente: reproyectar no duplica nada, porque las dos tablas tienen clave primaria y se
     * escribe con {@code ON CONFLICT DO UPDATE}. Es la misma propiedad que el ingestor de verdad
     * necesita para que reprocesar la cola sea seguro.
     */
    public static void proyectar(BaseDeDatosDePrueba base, long municipalidadId)
            throws SQLException {
        // Se LEE con `sgtm_app` y se ESCRIBE con el ingestor, en dos conexiones. El rodeo no es
        // un capricho: el ingestor NO tiene privilegio sobre las tablas de escenario, y el de
        // produccion recibe los datos DENTRO del evento; un `INSERT ... SELECT` en una sola
        // conexion no puede existir.
        //
        // Y desde P5C lee `predio_de_prueba` y `ficha_catastral_de_prueba`, porque `V6` retiro las
        // de verdad: el catastro vive en otra base. Es el unico punto en que este fixture se
        // aparta del ingestor real.
        java.util.List<Object[]> predios = new java.util.ArrayList<>();
        java.util.List<Object[]> fichas = new java.util.ArrayList<>();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            leer(
                    app,
                    """
                    SELECT p.id, p.codigo_ref_catastral, p.direccion, s.codigo, p.estado
                      FROM predio_de_prueba p
                      LEFT JOIN sector_de_prueba s
                        ON s.municipalidad_id = p.municipalidad_id AND s.id = p.sector_id
                     WHERE p.municipalidad_id = ?
                    """,
                    municipalidadId,
                    5,
                    predios);
            leer(
                    app,
                    """
                    SELECT f.id, f.predio_id, f.tipo, f.version, f.vigencia_desde,
                           f.vigencia_hasta, f.area_terreno, f.uso
                      FROM ficha_catastral_de_prueba f
                     WHERE f.municipalidad_id = ?
                    """,
                    municipalidadId,
                    8,
                    fichas);
            app.rollback();
        }

        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidadId);
            // Un evento del que salen las dos tablas, y su procedencia copiada en cada fila
            // (`V9`). Es UNO porque esta corrida proyecta el catastro entero de la municipalidad
            // de una vez: el ingestor de verdad recibira uno por hecho, y entonces esta linea es
            // la que cambia. Lo que no cambia es que ninguna fila se escriba sin nombrarlo.
            String evento = UUID.randomUUID().toString();
            String huella = huellaDe(evento);
            escribir(
                    ingestor,
                    """
                    INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id, secuencia,
                                                          tipo, aplicado_en, huella)
                    VALUES (?, CAST(? AS uuid), 1, 'CATASTRO_PROYECTADO', now(), ?)
                    ON CONFLICT (municipalidad_id, evento_id) DO NOTHING
                    """,
                    municipalidadId,
                    new Object[] {evento, huella});
            for (Object[] fila : predios) {
                escribir(
                        ingestor,
                        """
                        INSERT INTO predio_ref (municipalidad_id, predio_id, codigo_ref_catastral,
                                                direccion, sector_codigo, estado, secuencia,
                                                proyectado_en, evento_id, huella)
                        VALUES (?, ?, ?, ?, ?, ?, 1, now(), CAST(? AS uuid), ?)
                        ON CONFLICT (municipalidad_id, predio_id) DO UPDATE
                           SET codigo_ref_catastral = EXCLUDED.codigo_ref_catastral,
                               direccion = EXCLUDED.direccion,
                               sector_codigo = EXCLUDED.sector_codigo,
                               estado = EXCLUDED.estado,
                               proyectado_en = EXCLUDED.proyectado_en,
                               evento_id = EXCLUDED.evento_id,
                               huella = EXCLUDED.huella
                        """,
                        municipalidadId,
                        conProcedencia(fila, evento, huella));
            }
            for (Object[] fila : fichas) {
                escribir(
                        ingestor,
                        """
                        INSERT INTO ficha_ref (municipalidad_id, ficha_id, predio_id, tipo,
                                               version, vigencia_desde, vigencia_hasta,
                                               area_terreno, uso, secuencia, proyectado_en,
                                               evento_id, huella)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now(), CAST(? AS uuid), ?)
                        ON CONFLICT (municipalidad_id, ficha_id) DO UPDATE
                           SET vigencia_desde = EXCLUDED.vigencia_desde,
                               vigencia_hasta = EXCLUDED.vigencia_hasta,
                               area_terreno = EXCLUDED.area_terreno,
                               uso = EXCLUDED.uso,
                               proyectado_en = EXCLUDED.proyectado_en,
                               evento_id = EXCLUDED.evento_id,
                               huella = EXCLUDED.huella
                        """,
                        municipalidadId,
                        conProcedencia(fila, evento, huella));
            }
            ingestor.commit();
        }
    }

    /** La fila leida, mas las dos columnas de procedencia que `V9` exige. */
    private static Object[] conProcedencia(Object[] fila, String evento, String huella) {
        Object[] con = java.util.Arrays.copyOf(fila, fila.length + 2);
        con[fila.length] = evento;
        con[fila.length + 1] = huella;
        return con;
    }

    /**
     * La huella del cuerpo del evento.
     *
     * <p><b>Este fixture no la recibe: la fabrica.</b> En produccion la emite {@code catastro}
     * sobre el cuerpo que mando, y `V9` dice que aqui no se recalcula. Lo que esta siembra puede
     * sostener es la FORMA, no que el valor signifique nada.
     */
    private static String huellaDe(String semilla) {
        try {
            byte[] resumen =
                    MessageDigest.getInstance("SHA-256")
                            .digest(semilla.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : resumen) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("SHA-256 es obligatorio en toda JVM", imposible);
        }
    }

    private static void leer(
            Connection conexion,
            String sql,
            long municipalidadId,
            int columnas,
            java.util.List<Object[]> destino)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            sentencia.setLong(1, municipalidadId);
            try (java.sql.ResultSet filas = sentencia.executeQuery()) {
                while (filas.next()) {
                    Object[] fila = new Object[columnas];
                    for (int i = 0; i < columnas; i++) {
                        fila[i] = filas.getObject(i + 1);
                    }
                    destino.add(fila);
                }
            }
        }
    }

    private static void escribir(
            Connection conexion, String sql, long municipalidadId, Object[] fila)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            sentencia.setLong(1, municipalidadId);
            for (int i = 0; i < fila.length; i++) {
                sentencia.setObject(i + 2, fila[i]);
            }
            sentencia.executeUpdate();
        }
    }
}
