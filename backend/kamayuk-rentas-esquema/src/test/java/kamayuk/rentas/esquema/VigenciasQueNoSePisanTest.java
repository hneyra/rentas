package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dos versiones de la misma cosa no pueden cubrir la misma fecha (#669, V72).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>{@code ficha_vigente_uq} es un indice unico <b>parcial</b> —{@code WHERE vigencia_hasta IS
 * NULL}—, o sea impide dos versiones <b>abiertas</b> del mismo predio y nada mas. Una abierta y una
 * cerrada podian pisarse, y entonces toda lectura que resuelva «la ficha vigente a la fecha»
 * devuelve dos filas del mismo predio: la grilla lo enseña dos veces y el conteo lo cuenta dos
 * veces (#561). Lo mismo en {@code titularidad}, donde {@code titularidad_no_excede_trg} solo suma
 * las cuotas <b>abiertas</b> y el porcentaje <b>pondera la base imponible</b> del predial (NEG-05
 * §1, #395).
 *
 * <h2>La mitad que importa son los contrastes</h2>
 *
 * <p>Una restriccion que rechaza el solape es facil; la que ademas deja pasar lo legitimo es la que
 * hay que probar. Aqui son tres: el <b>versionado correcto</b> —cerrar el dia antes de abrir—, la
 * <b>copropiedad</b> —dos personas a la vez sobre el mismo predio, que es el caso corriente— y la
 * <b>transferencia dentro de una transaccion</b>, que atraviesa un estado intermedio solapado a
 * proposito. Sin los tres, la forma comoda de «arreglar» esto seria una restriccion que hace
 * imposible el padron entero.
 */
@DisplayName("#669 — Dos vigencias de la misma cosa no se pisan (V72)")
class VigenciasQueNoSePisanTest {

    private static final Date ENERO = Date.valueOf("2026-01-01");
    private static final Date FEBRERO = Date.valueOf("2026-02-01");
    private static final Date EL_DIA_ANTES = Date.valueOf("2026-01-31");

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
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "200602", "Municipalidad de #669");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidad, parametroId, "V");

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

    // ---------------------------------------------------------------- ficha

    @Test
    @DisplayName("dos versiones de la ficha que cubren la misma fecha se rechazan")
    void dosVersionesDeLaFichaQueSePisanSeRechazan() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            // La primera se cierra el 1 de febrero y la segunda empieza ESE MISMO DIA: las dos
            // cubren el 1 de febrero. Es la forma exacta que tenia la siembra de las pruebas de
            // aislamiento, y no lo veia nadie.
            insertarFicha(app, predio, 1, ENERO, FEBRERO);
            insertarFicha(app, predio, 2, FEBRERO, null);

            assertThatThrownBy(app::commit)
                    .as(
                            "con las dos vivas ese dia, «la ficha vigente al 1 de febrero» devuelve"
                                    + " dos filas del mismo predio y la grilla lo enseña dos veces")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ficha_vigencias_no_se_pisan");
        }
    }

    @Test
    @DisplayName("el versionado correcto —cerrar el dia antes— sigue pasando")
    void elVersionadoCorrectoPasa() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarFicha(app, predio, 1, ENERO, EL_DIA_ANTES);
            insertarFicha(app, predio, 2, FEBRERO, null);

            assertThatCode(app::commit)
                    .as(
                            "es lo que ActualizarFichaCatastral hace en produccion: cierra la"
                                    + " anterior el dia antes de abrir la siguiente")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("las cuatro fichas de tipos distintos del mismo predio conviven, como siempre")
    void losCuatroTiposConviven() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarFichaDeTipo(app, predio, "UNICA", ENERO);
            insertarFichaDeTipo(app, predio, "ECONOMICA", ENERO);
            insertarFichaDeTipo(app, predio, "BIENES_COMUNES", ENERO);
            insertarFichaDeTipo(app, predio, "RURAL", ENERO);

            assertThatCode(app::commit)
                    .as(
                            "el tipo entra en la llave: un predio tiene a la vez su ficha unica, su"
                                    + " economica, la de bienes comunes y la rural (#19)")
                    .doesNotThrowAnyException();
        }
    }

    // ----------------------------------------------------------- titularidad

    @Test
    @DisplayName("la misma persona con dos cuotas del mismo predio a la vez se rechaza")
    void laMismaPersonaDosVecesSeRechaza() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, titular, "30", ENERO, null);
            insertarTitularidad(app, predio, titular, "20", FEBRERO, null);

            assertThatThrownBy(app::commit)
                    .as(
                            "el porcentaje pondera la base imponible del predial: dos cuotas vivas"
                                    + " de la misma persona no dan un error, dan otra base")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("titularidad_vigencias_no_se_pisan");
        }
    }

    @Test
    @DisplayName("la copropiedad no se toca: dos personas a la vez sobre el mismo predio")
    void laCopropiedadNoSeToca() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, titular, "50", ENERO, null);
            insertarTitularidad(app, predio, copropietario, "50", ENERO, null);

            assertThatCode(app::commit)
                    .as(
                            "son contribuyentes distintos y el contribuyente entra en la llave; es"
                                    + " el caso corriente, no el borde")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("la transferencia atraviesa un estado solapado y aun asi pasa (DEFERRABLE)")
    void laTransferenciaAtraviesaUnEstadoSolapado() throws SQLException {
        try (Connection app = conexionConContexto()) {
            long predio = crearPredio(app);
            insertarTitularidad(app, predio, titular, "100", ENERO, null);
            app.commit();

            // El contexto se fija con SET LOCAL y muere con la transaccion (regla 3).
            ContextoDeTenant.fijar(app, municipalidad);
            // Se abre la cuota nueva ANTES de cerrar la anterior: entre las dos sentencias las
            // dos cubren febrero. Sin el diferimiento, la primera de las dos ya habria fallado y
            // una transferencia legitima seria imposible — la leccion de #16 con el disparador
            // de «no exceder 100».
            insertarTitularidad(app, predio, titular, "40", FEBRERO, null);
            ejecutar(
                    app,
                    "UPDATE titularidad SET vigencia_hasta = ?"
                            + " WHERE municipalidad_id = ? AND predio_id = ?"
                            + "   AND vigencia_desde = ? AND vigencia_hasta IS NULL",
                    EL_DIA_ANTES,
                    municipalidad,
                    predio,
                    ENERO);

            assertThatCode(app::commit).doesNotThrowAnyException();
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

    private static long crearPredio(Connection app) throws SQLException {
        int correlativo = CORRELATIVO.incrementAndGet();
        return insertar(
                app,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, via_id,"
                        + " direccion, sector_id, manzana_id, lote)"
                        + " VALUES (?, ?, 'URBANO', ?, ?, ?, ?, '01') RETURNING id",
                municipalidad,
                String.format("2006020101001001%06d", correlativo),
                viaId,
                "Jr. Vigencias " + correlativo,
                sectorId,
                manzanaId);
    }

    private static void insertarFicha(
            Connection app, long predio, int version, Date desde, Date hasta) throws SQLException {
        ejecutar(
                app,
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, vigencia_hasta, origen,"
                        + " documento_origen, observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, 120.00, 'CASA_HABITACION', ?, ?,"
                        + "         'DECLARACION_JURADA', 'DJ-669', 'ficha de la prueba de #669',"
                        + "         'prueba')",
                municipalidad,
                predio,
                version,
                desde,
                hasta);
    }

    private static void insertarFichaDeTipo(Connection app, long predio, String tipo, Date desde)
            throws SQLException {
        ejecutar(
                app,
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, ?, 1, 120.00, 'CASA_HABITACION', ?,"
                        + "         'DECLARACION_JURADA', 'DJ-669', 'ficha de la prueba de #669',"
                        + "         'prueba')",
                municipalidad,
                predio,
                tipo,
                desde);
    }

    private static void insertarTitularidad(
            Connection app,
            long predio,
            long contribuyente,
            String porcentaje,
            Date desde,
            Date hasta)
            throws SQLException {
        ejecutar(
                app,
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, vigencia_hasta, documento_origen)"
                        + " VALUES (?, ?, ?, 'COPROPIETARIO', CAST(? AS numeric), ?, ?,"
                        + "         'MINUTA-669')",
                municipalidad,
                predio,
                contribuyente,
                porcentaje,
                desde,
                hasta);
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
