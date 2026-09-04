package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariante de titularidad — heredado verificado de {@code ../srtm} (DAT-02 §4.2, alli D-36).
 *
 * <p><b>La regla es «no exceder 100», no «sumar exactamente 100».</b> Es lo que valida el SRTM del
 * MEF, y no es un matiz: un padron real tiene predios con titularidad parcialmente identificada.
 * Exigir la suma exacta obligaria al operador a <b>inventar un titular para cuadrar</b>, que es
 * peor dato que registrar el 60 % que efectivamente se conoce.
 *
 * <p>La otra mitad de la prueba es el <b>diferimiento</b>: una transferencia cierra una titularidad
 * y abre otra en la misma transaccion, y en el intermedio la suma no cuadra. Un trigger por fila
 * rechazaria la transferencia legitima; por eso es {@code DEFERRABLE INITIALLY DEFERRED}, y por eso
 * la prueba verifica en que momento muerde y no solo que muerda.
 */
@DisplayName("Titularidad (DAT-02 §4.2 de ../srtm): los porcentajes vigentes no exceden 100")
class TitularidadNoExcede100Test {

    private static final java.sql.Date VIGENCIA = java.sql.Date.valueOf("2026-01-01");

    /**
     * Sufijo de los predios que crea cada prueba: el codigo catastral es unico por municipalidad.
     */
    private static final AtomicInteger CORRELATIVO = new AtomicInteger();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long titular;
    private static long copropietario;
    private static long viaId;
    private static long sectorId;
    private static long manzanaId;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "200601", "Municipalidad A");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidad, parametroId, "A");

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long[] contribuyentes = dosContribuyentes(app);
            titular = contribuyentes[0];
            copropietario = contribuyentes[1];
            viaId = primerId(app, "via");
            sectorId = primerId(app, "sector");
            manzanaId = primerId(app, "manzana");
            app.commit();
        }
    }

    @AfterAll
    static void liberar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("una titularidad parcial —60 %, el resto sin identificar— se acepta")
    void laTitularidadParcialSeAcepta() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, copropietario, "COPROPIETARIO", "60");

            assertThatCode(app::commit)
                    .as("un predio con el 60 % identificado es un dato legitimo")
                    .doesNotThrowAnyException();
            assertThat(porcentajeVigente(predio)).isEqualByComparingTo("60.0000");
        }
    }

    @Test
    @DisplayName("la suma que excede 100 se rechaza al confirmar")
    void laSumaQueExcedeSeRechaza() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, titular, "COPROPIETARIO", "60");
            insertarTitularidad(app, predio, copropietario, "COPROPIETARIO", "50");

            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no pueden exceder 100");
        }
    }

    /**
     * Esta es la prueba que justifica el diferimiento. Las dos titularidades del 60 % coexisten
     * dentro de la transaccion —el estado intermedio suma 120— y aun asi la transferencia pasa,
     * porque el trigger se evalua al confirmar. Con un trigger por fila, cerrar y abrir en la misma
     * transaccion seria imposible.
     */
    @Test
    @DisplayName("una transferencia en la misma transaccion pasa por un estado intermedio invalido")
    void laTransferenciaAtraviesaUnEstadoIntermedioInvalido() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, titular, "COPROPIETARIO", "60");
            app.commit();

            // El contexto se fija con SET LOCAL y muere con la transaccion (regla 3): la
            // transferencia empieza en una transaccion nueva, que necesita fijarlo otra vez.
            ContextoDeTenant.fijar(app, municipalidad);
            insertarTitularidad(app, predio, copropietario, "COPROPIETARIO", "60");
            // Aqui vigen 120 %. Sin diferimiento, la sentencia anterior ya habria fallado.
            ejecutar(
                    app,
                    "UPDATE titularidad SET vigencia_hasta = ?"
                            + " WHERE municipalidad_id = ? AND predio_id = ? AND contribuyente_id = ?"
                            + "   AND vigencia_hasta IS NULL",
                    VIGENCIA,
                    municipalidad,
                    predio,
                    titular);

            assertThatCode(app::commit).doesNotThrowAnyException();
            assertThat(porcentajeVigente(predio)).isEqualByComparingTo("60.0000");
        }
    }

    @Test
    @DisplayName("el propietario unico lo es por el total: con 40 % no entra")
    void elPropietarioUnicoLoEsPorElTotal() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);

            assertThatThrownBy(
                            () ->
                                    insertarTitularidad(
                                            app, predio, titular, "PROPIETARIO_UNICO", "40"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("titularidad_unico_ck");
            app.rollback();
        }
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    private static Connection conexionConContexto() throws SQLException {
        Connection app = base.conexion(BaseDeDatosDePrueba.APP);
        ContextoDeTenant.fijar(app, municipalidad);
        return app;
    }

    /** Predio propio de cada prueba: asi ninguna depende del estado que dejo la anterior. */
    private static long crearPredio(Connection app) throws SQLException {
        int correlativo = CORRELATIVO.incrementAndGet();
        return insertar(
                app,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, via_id,"
                        + " direccion, sector_id, manzana_id, lote)"
                        + " VALUES (?, ?, 'URBANO', ?, ?, ?, ?, '01') RETURNING id",
                municipalidad,
                String.format("2006010101001001%06d", correlativo),
                viaId,
                "Jr. Titularidad " + correlativo,
                sectorId,
                manzanaId);
    }

    private static void insertarTitularidad(
            Connection app, long predio, long contribuyente, String condicion, String porcentaje)
            throws SQLException {
        ejecutar(
                app,
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, 'MINUTA-PRUEBA')",
                municipalidad,
                predio,
                contribuyente,
                condicion,
                porcentaje,
                VIGENCIA);
    }

    /** Se consulta en una conexion propia: la de la prueba puede estar por confirmar o abortada. */
    private static java.math.BigDecimal porcentajeVigente(long predio) throws SQLException {
        try (Connection app = conexionConContexto();
                PreparedStatement consulta =
                        app.prepareStatement(
                                "SELECT COALESCE(sum(porcentaje), 0) FROM titularidad"
                                        + " WHERE municipalidad_id = ? AND predio_id = ?"
                                        + "   AND vigencia_hasta IS NULL")) {
            consulta.setLong(1, municipalidad);
            consulta.setLong(2, predio);
            try (ResultSet fila = consulta.executeQuery()) {
                fila.next();
                return fila.getBigDecimal(1);
            }
        }
    }

    private static long[] dosContribuyentes(Connection app) throws SQLException {
        try (PreparedStatement consulta =
                app.prepareStatement(
                        "SELECT id FROM contribuyente WHERE municipalidad_id = ?"
                                + " ORDER BY id LIMIT 2")) {
            consulta.setLong(1, municipalidad);
            try (ResultSet filas = consulta.executeQuery()) {
                filas.next();
                long primero = filas.getLong(1);
                filas.next();
                return new long[] {primero, filas.getLong(1)};
            }
        }
    }

    private static long primerId(Connection app, String tabla) throws SQLException {
        try (PreparedStatement consulta =
                app.prepareStatement(
                        "SELECT id FROM "
                                + tabla
                                + " WHERE municipalidad_id = ? ORDER BY id LIMIT 1")) {
            consulta.setLong(1, municipalidad);
            try (ResultSet fila = consulta.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static long insertar(Connection app, String sql, Object... argumentos)
            throws SQLException {
        try (PreparedStatement sentencia = app.prepareStatement(sql)) {
            fijar(sentencia, argumentos);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static void ejecutar(Connection app, String sql, Object... argumentos)
            throws SQLException {
        try (PreparedStatement sentencia = app.prepareStatement(sql)) {
            fijar(sentencia, argumentos);
            sentencia.executeUpdate();
        }
    }

    private static void fijar(PreparedStatement sentencia, Object... argumentos)
            throws SQLException {
        for (int i = 0; i < argumentos.length; i++) {
            sentencia.setObject(i + 1, argumentos[i]);
        }
    }
}
