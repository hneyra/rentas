package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Una migracion que llega tarde se aplica, en vez de parar el despliegue (#722).
 *
 * <h2>Lo que paso, dos veces en un dia</h2>
 *
 * <p>Varias ramas cogen numero de migracion <b>antes</b> de mezclarse y se mezclan en otro orden.
 * El 2026-09-02, con ocho PR mezclados en unas horas y cuatro con migracion, el migrador termino
 * con excepcion dos veces —«Detected resolved migration not applied to database: 74», y luego la
 * 72—, y la primera hubo que aplicarla a mano para desbloquear.
 *
 * <p>Ninguna revision puede verlo: cada PR es correcto por su lado, el conflicto solo existe en el
 * arbol mezclado y {@code git} no marca nada porque son ficheros distintos.
 *
 * <h2>Como se mide aqui</h2>
 *
 * <p>Sobre un <b>esquema de usar y tirar</b>, con migraciones de mentira en el disco: se aplican la
 * 1 y la 3, aparece la 2, y se pregunta que hace Flyway. El esquema propio es lo que permite
 * reproducir el fallo sin ensuciar el historial real ni meter tablas que la prueba de aislamiento
 * tendria que censar.
 *
 * <p>Y aparte se comprueba que <b>el migrador de produccion</b> lleva la opcion, sobre la
 * configuracion que el mismo construye y no sobre una copia de ella: si esta prueba armara su
 * propio Flyway «igual que el suyo», demostraria lo que la prueba hace y no lo que el despliegue
 * ejecuta.
 */
@DisplayName("#722 — El orden de las migraciones al mezclar")
class OrdenDeLasMigracionesTest {

    private static final String ESQUEMA = "prueba_722";

    private static BaseDeDatosDePrueba base;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        // El esquema lo crea el superusuario y no `sgtm_owner`, que a proposito no tiene CREATE
        // sobre la base —«permission denied for database», que es lo que dijo la primera vez—.
        // Es la misma frontera que el resto del sistema respeta: quien migra tiene DDL sobre lo
        // suyo, no sobre el motor.
        try (var admin = base.conexionAdmin();
                var sentencia = admin.createStatement()) {
            sentencia.execute("CREATE SCHEMA IF NOT EXISTS " + ESQUEMA);
            sentencia.execute(
                    "GRANT ALL ON SCHEMA " + ESQUEMA + " TO " + BaseDeDatosDePrueba.OWNER);
        }
    }

    @AfterAll
    static void liberar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("el migrador que se despliega lleva la opcion, y se lee de SU configuracion")
    void elMigradorLlevaLaOpcion() {
        assertThat(
                        Migrador.configuracion(
                                        base.url(),
                                        BaseDeDatosDePrueba.OWNER,
                                        base.clave(BaseDeDatosDePrueba.OWNER))
                                .isOutOfOrder())
                .as(
                        "sin ella, una migracion que llega tarde no es un aviso: el migrador"
                                + " termina con excepcion y el despliegue se queda como este")
                .isTrue();
    }

    @Test
    @DisplayName("sin la opcion, la que llega tarde para el migrador — que es lo que pasaba")
    void sinLaOpcionElMigradorSePara(@TempDir Path carpeta) throws IOException {
        aplicarLaUnoYLaTres(carpeta, false);
        migracion(carpeta, "V9002__la_que_llega_tarde.sql", "tarde");

        assertThatThrownBy(() -> flyway(carpeta, false).migrate())
                .as("es el mensaje literal que dejo en el log las dos veces")
                .hasMessageContaining("not applied to database");
    }

    @Test
    @DisplayName(
            "con la opcion, se aplica y el esquema queda igual que si hubiera llegado a tiempo")
    void conLaOpcionSeAplica(@TempDir Path carpeta) throws IOException, SQLException {
        aplicarLaUnoYLaTres(carpeta, true);
        migracion(carpeta, "V9002__la_que_llega_tarde.sql", "tarde");

        int aplicadas = flyway(carpeta, true).migrate().migrationsExecuted;

        assertThat(aplicadas).isEqualTo(1);
        assertThat(existe("tarde"))
                .as("y no basta con que Flyway no proteste: la tabla tiene que estar")
                .isTrue();
    }

    // ------------------------------------------------------------------

    /** Deja el esquema con la 1 y la 3 aplicadas, que es el hueco que abre el defecto. */
    private static void aplicarLaUnoYLaTres(Path carpeta, boolean fueraDeOrden) throws IOException {
        migracion(carpeta, "V9001__la_primera.sql", "primera");
        migracion(carpeta, "V9003__la_que_se_mezclo_antes.sql", "tercera");
        flyway(carpeta, fueraDeOrden).migrate();
    }

    private static void migracion(Path carpeta, String nombre, String tabla) throws IOException {
        Files.writeString(
                carpeta.resolve(nombre),
                "CREATE TABLE " + ESQUEMA + ".prueba_722_" + tabla + " (id int PRIMARY KEY);\n");
    }

    private static Flyway flyway(Path carpeta, boolean fueraDeOrden) {
        return Flyway.configure()
                .dataSource(
                        base.url(),
                        BaseDeDatosDePrueba.OWNER,
                        base.clave(BaseDeDatosDePrueba.OWNER))
                .locations("filesystem:" + carpeta)
                .schemas(ESQUEMA)
                .defaultSchema(ESQUEMA)
                .table("historial_722")
                .outOfOrder(fueraDeOrden)
                .load();
    }

    private static boolean existe(String tabla) throws SQLException {
        try (var conexion = base.conexion(BaseDeDatosDePrueba.OWNER);
                var sentencia =
                        conexion.prepareStatement(
                                "SELECT count(*) FROM pg_tables"
                                        + " WHERE schemaname = ? AND tablename = ?")) {
            sentencia.setString(1, ESQUEMA);
            sentencia.setString(2, "prueba_722_" + tabla);
            try (var fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1) == 1;
            }
        }
    }
}
