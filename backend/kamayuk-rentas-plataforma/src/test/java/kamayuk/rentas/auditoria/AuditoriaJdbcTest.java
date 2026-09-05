package kamayuk.rentas.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0008, contra PostgreSQL real: la auditoria se escribe, no se puede editar y —lo que da
 * sentido a todo lo demas— <b>sin observacion la operacion completa se deshace</b>.
 *
 * <p>Como todas las pruebas de persistencia, se conecta como {@code kamayuk_app}: es la unica forma
 * de que los privilegios y la RLS signifiquen algo. Con la conexion de superusuario de
 * Testcontainers, los tres casos de privilegios de aqui pasarian en verde sin verificar nada.
 */
@DisplayName("ADR-0008 — Auditoria obligatoria")
class AuditoriaJdbcTest {

    /**
     * Fijo a proposito: la fecha de la fila de auditoria sale de este reloj, no de {@code now()} de
     * la base. Si saliera de la base, la fila podria caer en un dia distinto del ejercicio con que
     * se particiono.
     */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static AuditoriaJdbc auditoria;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("210101", "Auditoria A");
        municipalidadB = crearMunicipalidad("210102", "Auditoria B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        auditoria = new AuditoriaJdbc(jdbc, RELOJ);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("jperez", "PC-RENTAS-04", "10.20.30.40"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("una escritura con observacion deja exactamente una fila, con equipo e IP")
    void dejaUnaFilaConEquipoEIp() throws SQLException {
        transaccion.execute(
                estado -> {
                    auditoria.registrar(
                            new RegistroDeAuditoria(
                                    EJERCICIO,
                                    "via",
                                    "4321",
                                    Operacion.ALTA,
                                    Observacion.de("Alta de via por convenio con catastro 2026"),
                                    null,
                                    "{\"codigo\":\"V-1\"}"));
                    return null;
                });

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT usuario_id, origen_equipo, host(origen_ip), observacion,"
                                        + " operacion, datos_nuevos, municipalidad_id"
                                        + " FROM auditoria WHERE tabla = 'via' AND clave = '4321'");
                ResultSet fila = sentencia.executeQuery()) {

            assertThat(fila.next()).as("exactamente una fila").isTrue();
            assertThat(fila.getString(1)).isEqualTo("jperez");
            assertThat(fila.getString(2)).isEqualTo("PC-RENTAS-04");
            assertThat(fila.getString(3))
                    .as("el manual pide la IP de la maquina desde la que ocurre el cambio")
                    .isEqualTo("10.20.30.40");
            assertThat(fila.getString(4)).contains("convenio con catastro");
            assertThat(fila.getString(5)).isEqualTo("ALTA");
            assertThat(fila.getString(6)).contains("V-1");
            assertThat(fila.getLong(7))
                    .as("la auditoria es dato de tenant: el motor le puso la municipalidad")
                    .isEqualTo(municipalidadA);
            assertThat(fila.next()).as("y solo una").isFalse();
        }
    }

    /**
     * La barrera final, la que no se puede rodear.
     *
     * <p>El tipo {@code Observacion} impide construir una vacia, asi que para llegar hasta aqui hay
     * que saltarselo a proposito, con SQL directo. Es exactamente lo que haria alguien con prisa
     * escribiendo un {@code INSERT} a mano: la prueba demuestra que ni asi se puede.
     */
    @Test
    @DisplayName(
            "sin observacion, la operacion completa se deshace: no queda ni la fila de negocio")
    void sinObservacionSeDeshaceLaOperacionCompleta() throws SQLException {
        long viasAntes =
                contar(
                        "SELECT count(*) FROM via_de_prueba WHERE municipalidad_id = "
                                + municipalidadA);

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado -> {
                                            jdbc.sql(
                                                            "INSERT INTO via_de_prueba (municipalidad_id,"
                                                                    + " codigo, tipo_via, nombre)"
                                                                    + " VALUES"
                                                                    + " (current_setting('app.municipalidad_id')::bigint,"
                                                                    + " 'SIN-OBS', 'CALLE', 'Calle sin"
                                                                    + " observacion')")
                                                    .update();
                                            return jdbc.sql(
                                                            "INSERT INTO auditoria"
                                                                    + " (municipalidad_id, ejercicio,"
                                                                    + "  tabla, clave, operacion,"
                                                                    + "  usuario_id, observacion)"
                                                                    + " VALUES"
                                                                    + " (current_setting('app.municipalidad_id')::bigint,"
                                                                    + " 2026, 'via', 'x', 'ALTA',"
                                                                    + " 'jperez', '   ')")
                                                    .update();
                                        }))
                .as("el CHECK de al menos cinco caracteres no vacios muerde")
                .hasMessageContaining("auditoria_observacion_ck");

        assertThat(
                        contar(
                                "SELECT count(*) FROM via_de_prueba WHERE municipalidad_id = "
                                        + municipalidadA))
                .as("y arrastra la operacion completa: la via tampoco queda")
                .isEqualTo(viasAntes);
        assertThat(contar("SELECT count(*) FROM via_de_prueba WHERE codigo = 'SIN-OBS'")).isZero();
    }

    @Test
    @DisplayName("una observacion de espacios tampoco explica nada, y tampoco entra")
    void unaObservacionDeEspaciosNoEntra() {
        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                jdbc.sql(
                                                                "INSERT INTO auditoria"
                                                                        + " (municipalidad_id,"
                                                                        + " ejercicio, tabla, clave,"
                                                                        + " operacion, usuario_id,"
                                                                        + " observacion) VALUES"
                                                                        + " (current_setting('app.municipalidad_id')::bigint,"
                                                                        + " 2026, 'via', 'y', 'ALTA',"
                                                                        + " 'jperez', '      ')")
                                                        .update()))
                .as("NOT NULL no basta: una cadena de espacios cumple NOT NULL y no dice nada")
                .hasMessageContaining("auditoria_observacion_ck");
    }

    @Test
    @DisplayName(
            "la aplicacion no puede editar la auditoria: quien la edita borra su propio rastro")
    void noSePuedeEditarLaAuditoria() {
        registrarUnaFilaCualquiera("edicion");

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                jdbc.sql(
                                                                "UPDATE auditoria SET observacion ="
                                                                        + " 'otra cosa' WHERE clave ="
                                                                        + " 'edicion'")
                                                        .update()))
                .hasMessageContaining("auditoria");
    }

    @Test
    @DisplayName("la aplicacion no puede borrar la auditoria")
    void noSePuedeBorrarLaAuditoria() {
        registrarUnaFilaCualquiera("borrado");

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                jdbc.sql(
                                                                "DELETE FROM auditoria WHERE clave"
                                                                        + " = 'borrado'")
                                                        .update()))
                .hasMessageContaining("auditoria");
    }

    @Test
    @DisplayName("la auditoria es dato de tenant: desde B no se ve la pista de A")
    void laAuditoriaEsDatoDeTenant() {
        registrarUnaFilaCualquiera("solo-de-A");

        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        Long desdeB =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT count(*) FROM auditoria WHERE clave ="
                                                        + " 'solo-de-A'")
                                        .query(Long.class)
                                        .single());

        assertThat(desdeB)
                .as("la pista de auditoria de una municipalidad no es visible desde otra")
                .isZero();
    }

    @Test
    @DisplayName("sin origen fijado no se escribe auditoria: una pista anonima no sirve de pista")
    void sinOrigenNoSeEscribe() {
        OrigenContext.limpiar();

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado -> {
                                            auditoria.registrar(
                                                    new RegistroDeAuditoria(
                                                            EJERCICIO,
                                                            "via",
                                                            "9",
                                                            Operacion.ALTA,
                                                            Observacion.de(
                                                                    "Observacion suficientemente"
                                                                            + " larga"),
                                                            null,
                                                            null));
                                            return null;
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("origen");
    }

    @Test
    @DisplayName("un ejercicio sin particion falla en vez de perderse")
    void unEjercicioSinParticionFalla() {
        // Las particiones declaradas son 2026 y 2027. Que falle es lo correcto: la
        // alternativa —una particion por omision— guardaria la fila donde nadie la
        // busca, y una auditoria que no se encuentra es una auditoria que no existe.
        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado -> {
                                            auditoria.registrar(
                                                    new RegistroDeAuditoria(
                                                            new Ejercicio(2035),
                                                            "via",
                                                            "10",
                                                            Operacion.ALTA,
                                                            Observacion.de(
                                                                    "Ejercicio sin particion"
                                                                            + " declarada"),
                                                            null,
                                                            null));
                                            return null;
                                        }))
                .hasMessageContaining("partition");
    }

    private void registrarUnaFilaCualquiera(String clave) {
        transaccion.execute(
                estado -> {
                    auditoria.registrar(
                            new RegistroDeAuditoria(
                                    EJERCICIO,
                                    "via",
                                    clave,
                                    Operacion.MODIFICACION,
                                    Observacion.de("Fila de apoyo de la prueba " + clave),
                                    null,
                                    null));
                    return null;
                });
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long contar(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return resultado.getLong(1);
        }
    }
}
