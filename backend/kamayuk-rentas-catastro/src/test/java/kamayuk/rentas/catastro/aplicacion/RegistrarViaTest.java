package kamayuk.rentas.catastro.aplicacion;

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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * El primer caso de uso de escritura, de extremo a extremo y contra PostgreSQL real.
 *
 * <p><b>Se envuelve en un proxy transaccional de verdad</b> en lugar de llamar al objeto desnudo
 * dentro de un {@code TransactionTemplate}. La diferencia importa: asi lo que se prueba es que la
 * anotacion {@code @Transactional} del caso de uso abre la transaccion —y por tanto emite el {@code
 * SET LOCAL}— y que la auditoria cae dentro de ella. Con el objeto desnudo, la transaccion la
 * abriria la prueba y la anotacion podria estar puesta o no sin que nada cambiara.
 *
 * <p>Cubre las <b>tres</b> operaciones de auditoria que el caso de uso puede asentar —{@code ALTA},
 * {@code MODIFICACION} con los datos anteriores y {@code BAJA}— porque la que se elige es la unica
 * decision del caso de uso, y una que se equivoque no rompe nada: deja la pista mintiendo.
 */
@DisplayName("Caso de uso: registrar, editar y dar de baja una via, con su auditoria")
class RegistrarViaTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static RegistrarVia registrarVia;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("220101", "Municipalidad del caso de uso");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        // Reloj fijo: la fecha decide en que particion de auditoria cae la fila, y
        // una prueba que dependa del dia en que se ejecuta se rompe sola en 2028.
        Clock reloj = Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

        RegistrarVia objetivo =
                new RegistrarVia(
                        new ViaRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, reloj), reloj);

        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(
                        new TenantTransactionManager(pool),
                        new AnnotationTransactionAttributeSource()));
        registrarVia = (RegistrarVia) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("mtorres", "PC-CATASTRO-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("el alta deja la via y su auditoria, sin transaccion externa que la envuelva")
    void elAltaDejaLaViaYSuAuditoria() throws SQLException {
        Via guardada =
                registrarVia.registrar(
                        Via.nueva("V-100", TipoVia.AVENIDA, "Avenida San Martin", "220101"),
                        Observacion.de("Alta por acuerdo de concejo 2026-014"));

        assertThat(guardada.id())
                .as("la transaccion la abrio la anotacion del caso de uso, no la prueba")
                .isNotNull();

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, observacion, usuario_id, origen_equipo,"
                                        + " datos_nuevos, ejercicio"
                                        + " FROM auditoria WHERE tabla = 'via' AND clave = ?"); ) {
            sentencia.setString(1, String.valueOf(guardada.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).as("el alta dejo su fila de auditoria").isTrue();
                assertThat(fila.getString(1)).isEqualTo("ALTA");
                assertThat(fila.getString(2)).contains("acuerdo de concejo");
                assertThat(fila.getString(3)).isEqualTo("mtorres");
                assertThat(fila.getString(4)).isEqualTo("PC-CATASTRO-01");
                assertThat(fila.getString(5)).contains("Avenida San Martin");
                assertThat(fila.getInt(6))
                        .as("el ejercicio sale del reloj inyectado, no del dia de hoy")
                        .isEqualTo(2026);
                assertThat(fila.next()).as("y solo una").isFalse();
            }
        }
    }

    @Test
    @DisplayName("la edicion se audita como MODIFICACION y deja los datos anteriores")
    void laEdicionSeAuditaComoModificacion() throws SQLException {
        Via original =
                registrarVia.registrar(
                        Via.nueva("V-101", TipoVia.CALLE, "Calle Piura", "220101"),
                        Observacion.de("Alta previa a la correccion"));

        Via cambiada =
                new Via(
                        original.id(),
                        original.codigo(),
                        original.tipo(),
                        "Calle Piura Norte",
                        original.ubigeo(),
                        original.activa());
        registrarVia.editar(
                original, cambiada, Observacion.de("Correccion de nomenclatura, oficio 2026-31"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, datos_anteriores->>'nombre',"
                                        + " datos_nuevos->>'nombre'"
                                        + " FROM auditoria WHERE tabla = 'via' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1)).isEqualTo("MODIFICACION");
                assertThat(fila.getString(2))
                        .as("el contrato de MODIFICACION es que el estado previo quede aqui")
                        .isEqualTo("Calle Piura");
                assertThat(fila.getString(3)).isEqualTo("Calle Piura Norte");
            }
        }
    }

    @Test
    @DisplayName("retirar del catalogo se audita como BAJA, no como MODIFICACION")
    void retirarDelCatalogoSeAuditaComoBaja() throws SQLException {
        Via original =
                registrarVia.registrar(
                        Via.nueva("V-102", TipoVia.PASAJE, "Pasaje Los Olivos", "220101"),
                        Observacion.de("Alta previa a la baja"));

        registrarVia.editar(
                original,
                original.dadaDeBaja(),
                Observacion.de("Via absorbida por la Av. San Martin"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, datos_anteriores->>'activa',"
                                        + " datos_nuevos->>'activa'"
                                        + " FROM auditoria WHERE tabla = 'via' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1))
                        .as("Operacion.BAJA es, literalmente, «una via retirada del catalogo»")
                        .isEqualTo("BAJA");
                assertThat(fila.getString(2)).isEqualTo("true");
                assertThat(fila.getString(3)).isEqualTo("false");
            }
        }

        assertThat(contar("SELECT count(*) FROM via WHERE codigo = 'V-102'"))
                .as("dar de baja no es borrar (RNF-051): la fila sigue ahi")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("registrar no acepta una via que ya tiene identificador: para eso esta editar")
    void registrarNoAceptaUnaViaYaGuardada() {
        Via original =
                registrarVia.registrar(
                        Via.nueva("V-103", TipoVia.JIRON, "Jiron Union", null),
                        Observacion.de("Alta para la comprobacion"));

        assertThatThrownBy(
                        () ->
                                registrarVia.registrar(
                                        original, Observacion.de("Alta encubierta de una edicion")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("si el alta falla, no queda auditoria de algo que no paso")
    void siElAltaFallaNoQuedaAuditoria() throws SQLException {
        registrarVia.registrar(
                Via.nueva("V-REPETIDA", TipoVia.CALLE, "Calle Repetida", null),
                Observacion.de("Primera alta, esta si debe quedar"));

        long auditoriasAntes = contar("SELECT count(*) FROM auditoria WHERE tabla = 'via'");

        assertThatThrownBy(
                        () ->
                                registrarVia.registrar(
                                        Via.nueva(
                                                "V-REPETIDA",
                                                TipoVia.CALLE,
                                                "Calle repetida a proposito",
                                                null),
                                        Observacion.de("Segunda alta con codigo ya usado")))
                .isNotNull();

        assertThat(contar("SELECT count(*) FROM auditoria WHERE tabla = 'via'"))
                .as("una auditoria de una operacion deshecha seria una constancia falsa")
                .isEqualTo(auditoriasAntes);
    }

    @Test
    @DisplayName("sin contexto de municipalidad, ni via ni auditoria")
    void sinContextoNiViaNiAuditoria() throws SQLException {
        TenantContext.limpiar();
        long viasAntes = contar("SELECT count(*) FROM via");
        long auditoriasAntes = contar("SELECT count(*) FROM auditoria");

        assertThatThrownBy(
                        () ->
                                registrarVia.registrar(
                                        Via.nueva(
                                                "V-SIN-CTX",
                                                TipoVia.JIRON,
                                                "Jiron sin contexto",
                                                null),
                                        Observacion.de("No deberia llegar a la base")))
                .isNotNull();

        assertThat(contar("SELECT count(*) FROM via")).isEqualTo(viasAntes);
        assertThat(contar("SELECT count(*) FROM auditoria")).isEqualTo(auditoriasAntes);
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
