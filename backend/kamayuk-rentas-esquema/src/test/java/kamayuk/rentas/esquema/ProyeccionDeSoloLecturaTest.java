package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P5C — La proyeccion de {@code catastro} es de solo lectura, y lo sostiene el MOTOR.
 *
 * <h2>Que se mide, y por que no basta con que el repositorio no escriba</h2>
 *
 * <p>ADR-0027 §3 dice que {@code valuacion_predio} y sus hermanas «no las escribe nadie salvo el
 * ingestor de eventos, y eso <b>lo sostiene el motor</b>». Una promesa asi no la puede cumplir la
 * disciplina de un repositorio: basta que alguien «arregle» una fila a mano el dia que la
 * proyeccion se desincronice, y entonces deja de ser una proyeccion y pasa a ser un dato de nadie
 * que ya no se puede reconstruir reprocesando la cola.
 *
 * <p>Es la misma mecanica con que `V54` protege el estado de la declaracion jurada y `V3` la copia
 * de normativa: un privilegio, no una convencion.
 *
 * <h2>Y por que se mide con `has_table_privilege` ADEMAS del intento</h2>
 *
 * <p>Por lo que #435 midio y conviene no volver a descubrir: RLS y `GRANT` son dos guardas
 * independientes y las dos dan el mismo `42501`, asi que el sintoma no distingue cual actuo.
 * Devolverle el `GRANT` a `kamayuk_app` puede dejar el intento fallando igual —lo pararia la
 * politica— y la unica que ve la diferencia es la consulta al catalogo.
 */
@DisplayName("P5C — La proyeccion de catastro es de solo lectura para la aplicacion")
class ProyeccionDeSoloLecturaTest {

    private static final List<String> PROYECCION =
            List.of("predio_ref", "ficha_ref", "catastro_evento_aplicado");

    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long predioProyectado;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "200801", "Municipalidad A");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidad, parametroId, "PR");

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP);
                PreparedStatement consulta =
                        app.prepareStatement(
                                "SELECT predio_id FROM predio_ref WHERE municipalidad_id = ?"
                                        + " ORDER BY predio_id LIMIT 1")) {
            ContextoDeTenant.fijar(app, municipalidad);
            consulta.setLong(1, municipalidad);
            try (ResultSet fila = consulta.executeQuery()) {
                fila.next();
                predioProyectado = fila.getLong(1);
            }
            app.rollback();
        }
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("kamayuk_app no tiene INSERT, UPDATE ni DELETE sobre ninguna de las tres")
    void laAplicacionSoloLee() throws SQLException {
        SoftAssertions verificaciones = new SoftAssertions();
        for (String tabla : PROYECCION) {
            verificaciones
                    .assertThat(privilegio(BaseDeDatosDePrueba.APP, tabla, "SELECT"))
                    .as("%s: la aplicacion la LEE, que es para lo que existe", tabla)
                    .isTrue();
            for (String escritura : List.of("INSERT", "UPDATE", "DELETE")) {
                verificaciones
                        .assertThat(privilegio(BaseDeDatosDePrueba.APP, tabla, escritura))
                        .as(
                                "%s: %s desde la aplicacion convierte la proyeccion en un dato de"
                                        + " nadie que ya no se puede reconstruir (ADR-0027 §3)",
                                tabla, escritura)
                        .isFalse();
            }
        }
        verificaciones.assertAll();
    }

    @Test
    @DisplayName("y el intento tambien falla, no solo el catalogo")
    void elIntentoFalla() {
        // La otra mitad. El catalogo dice lo que el privilegio es; esto dice lo que pasa.
        assertThatThrownBy(
                        () -> {
                            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP);
                                    PreparedStatement sentencia =
                                            app.prepareStatement(
                                                    "UPDATE predio_ref SET direccion = 'alterada'"
                                                            + " WHERE municipalidad_id = ?")) {
                                ContextoDeTenant.fijar(app, municipalidad);
                                sentencia.setLong(1, municipalidad);
                                sentencia.executeUpdate();
                            }
                        })
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(PRIVILEGIO_INSUFICIENTE);
    }

    @Test
    @DisplayName("el ingestor si puede escribirla: es el contraste")
    void elIngestorSiEscribe() {
        // Sin esto, una guarda que negara la escritura a TODOS pasaria las dos pruebas de arriba
        // y dejaria la proyeccion sin quien la alimente.
        assertThatCode(
                        () -> {
                            try (Connection ingestor =
                                            base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO);
                                    PreparedStatement sentencia =
                                            ingestor.prepareStatement(
                                                    "UPDATE predio_ref SET secuencia = secuencia"
                                                            + " WHERE municipalidad_id = ?"
                                                            + " AND predio_id = ?")) {
                                ContextoDeTenant.fijar(ingestor, municipalidad);
                                sentencia.setLong(1, municipalidad);
                                sentencia.setLong(2, predioProyectado);
                                sentencia.executeUpdate();
                                ingestor.rollback();
                            }
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el ingestor tampoco puede borrar: la proyeccion se reescribe, no se vacia")
    void elIngestorTampocoBorra() throws SQLException {
        SoftAssertions verificaciones = new SoftAssertions();
        for (String tabla : PROYECCION) {
            verificaciones
                    .assertThat(privilegio(BaseDeDatosDePrueba.INGESTOR_CATASTRO, tabla, "DELETE"))
                    .as(
                            "%s: regla 4. Un evento que retira un predio lo marca dado de baja; "
                                    + "borrar la fila deja la deuda apuntando a nada",
                            tabla)
                    .isFalse();
        }
        verificaciones.assertAll();
    }

    @Test
    @DisplayName("el ingestor no llega al padron: no lee `predio` ni escribe deuda")
    void elIngestorNoLlegaAlPadron() throws SQLException {
        // Lo que hace del ingestor un rol y no un `kamayuk_app` con otro nombre. Recibe los datos
        // DENTRO del evento; si pudiera leer las tablas de negocio, la tentacion seria componer
        // la proyeccion con un JOIN y volveriamos al cruce que P5C existe para deshacer.
        SoftAssertions verificaciones = new SoftAssertions();
        for (String tabla :
                List.of("contribuyente", "declaracion_jurada", "cuenta_corriente_asiento")) {
            for (String privilegio : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                verificaciones
                        .assertThat(
                                privilegio(
                                        BaseDeDatosDePrueba.INGESTOR_CATASTRO, tabla, privilegio))
                        .as("%s sobre %s", privilegio, tabla)
                        .isFalse();
            }
        }
        verificaciones.assertAll();
    }

    private static boolean privilegio(String rol, String tabla, String privilegio)
            throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement consulta =
                        admin.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            consulta.setString(1, rol);
            consulta.setString(2, tabla);
            consulta.setString(3, privilegio);
            try (ResultSet fila = consulta.executeQuery()) {
                fila.next();
                return fila.getBoolean(1);
            }
        }
    }
}
