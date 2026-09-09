package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una vuelta entera, con el aplicador de verdad contra PostgreSQL y un buzon de mentira que mira la
 * copia local <b>en el instante del acuse</b>: es lo unico que puede medir que el acuse va DESPUES
 * del commit (AC-7 #1).
 */
@DisplayName("Etapa 4 — una vuelta del consumidor del buzon de identidad")
class ConsumirEventosDeIdentidadJdbcTest {

    private static final Instant AHORA = Instant.parse("2026-09-09T15:00:00Z");
    private static final String HUELLA = "e".repeat(64);

    private static BaseDeDatosDePrueba base;
    private static TenantTransactionManager gestor;
    private static JdbcClient jdbc;
    private static long municipalidad;

    private BuzonDeMentira buzon;
    private AlertaQueAnota alerta;
    private ConsumirEventosDeIdentidad consumidor;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("299903", "Municipalidad C");
        try (Connection admin = base.conexionAdmin();
                Statement s = admin.createStatement()) {
            s.execute(
                    "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre) VALUES ("
                            + municipalidad
                            + ", 'SEGURIDAD', 'Seguridad')");
            s.execute(
                    "INSERT INTO acceso (municipalidad_id, modulo_id, tipo, codigo, nombre)"
                            + " SELECT "
                            + municipalidad
                            + ", id, 'OPCION_MENU', 'permisos', 'Permisos' FROM modulo_sistema"
                            + " WHERE municipalidad_id = "
                            + municipalidad);
        }
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        gestor = new TenantTransactionManager(pool);
        jdbc = JdbcClient.create(pool);
    }

    @AfterAll
    static void liberar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void armar() {
        buzon = new BuzonDeMentira();
        alerta = new AlertaQueAnota();
        consumidor = consumidorCon(aplicadorDeVerdad());
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName(
            "una pagina con de todo: se aplica lo aplicable, se aparta lo imposible, se espera lo"
                    + " pendiente, y se acusa DESPUES del commit")
    void unaPaginaConDeTodo() throws SQLException {
        EventoDeIdentidadRecibido grupo =
                evento(
                        1,
                        "GRUPO_DADO_DE_ALTA",
                        "{\"grupoId\":1,\"nombre\":\"Mesa de Partes\",\"descripcion\":null,"
                                + "\"habilitado\":true,\"vigenciaDesde\":null,"
                                + "\"vigenciaHasta\":null}");
        EventoDeIdentidadRecibido usuario =
                evento(
                        2,
                        "USUARIO_DADO_DE_ALTA",
                        "{\"usuarioId\":1,\"cuenta\":\"jperez\",\"nombre\":\"Juan Perez\","
                                + "\"correo\":null,\"habilitado\":true,\"vigenciaDesde\":null,"
                                + "\"vigenciaHasta\":null}");
        EventoDeIdentidadRecibido afiliacion =
                evento(
                        3,
                        "MIEMBRO_AFILIADO",
                        "{\"grupoId\":1,\"grupoNombre\":\"Mesa de Partes\",\"usuarioId\":1,"
                                + "\"usuarioCuenta\":\"jperez\",\"activo\":true,"
                                + "\"usuarioAlta\":\"admin\",\"usuarioBaja\":null}");
        EventoDeIdentidadRecibido permisoDeRentas = evento(4, "PERMISO_FIJADO", permiso("rentas"));
        EventoDeIdentidadRecibido permisoDeIdentidad =
                evento(5, "PERMISO_FIJADO", permiso("identidad"));
        EventoDeIdentidadRecibido octavoTipo = evento(6, "SISTEMA_DADO_DE_ALTA", "{}");
        EventoDeIdentidadRecibido huerfana =
                evento(
                        7,
                        "MIEMBRO_AFILIADO",
                        "{\"grupoId\":9,\"grupoNombre\":\"Grupo que no llego\",\"usuarioId\":1,"
                                + "\"usuarioCuenta\":\"jperez\",\"activo\":true,"
                                + "\"usuarioAlta\":\"admin\",\"usuarioBaja\":null}");
        buzon.sirve(
                grupo,
                usuario,
                afiliacion,
                permisoDeRentas,
                permisoDeIdentidad,
                octavoTipo,
                huerfana);
        // Las pruebas de esta clase comparten la municipalidad y no se limpian entre si, asi que
        // lo que se afirma es lo que ESTA vuelta anade, y no un total.
        long aplicadosAntes = contar("identidad_evento_aplicado");
        long muertosAntes = contar("identidad_evento_muerto");

        ConsumirEventosDeIdentidad.Vuelta vuelta = consumidor.consumir();

        assertThat(vuelta.leidos()).isEqualTo(7);
        assertThat(vuelta.aplicados()).isEqualTo(4);
        assertThat(vuelta.ajenos()).isEqualTo(1);
        assertThat(vuelta.apartados()).isEqualTo(1);
        assertThat(vuelta.pendientes()).isEqualTo(1);
        assertThat(vuelta.sinProgreso()).isFalse();

        assertThat(buzon.acusados)
                .as("se acusan los seis resueltos y NO la huerfana, que se queda en el buzon")
                .containsExactly(
                        grupo.eventoId(),
                        usuario.eventoId(),
                        afiliacion.eventoId(),
                        permisoDeRentas.eventoId(),
                        permisoDeIdentidad.eventoId(),
                        octavoTipo.eventoId());
        assertThat(buzon.aplicadosVisiblesAlAcusar - aplicadosAntes)
                .as(
                        "[el acuse va DESPUES del commit (AC-7 #1): en el instante del acuse, otra"
                                + " conexion ya ve en la copia los cinco resueltos —los cuatro"
                                + " aplicados y el ajeno, que se registra para no volver a leerlo—;"
                                + " si el acuse llegara antes, `identidad` dejaria de servir"
                                + " eventos que esta copia todavia no habia confirmado, y un fallo"
                                + " en ese hueco los perderia para siempre]")
                .isEqualTo(5L);
        assertThat(buzon.muertosVisiblesAlAcusar - muertosAntes)
                .as("y el apartado ya esta en la cola de muertos cuando se acusa")
                .isEqualTo(1L);

        assertThat(alerta.avisos).hasSize(1);
        assertThat(alerta.avisos.getFirst())
                .contains("SISTEMA_DADO_DE_ALTA")
                .contains("apartados=1");
        assertThat(contar("identidad_evento_muerto") - muertosAntes).isEqualTo(1);
        assertThat(contar("permiso")).as("el de identidad no rige aqui").isEqualTo(1);
    }

    /**
     * AC-7 #1 con un solo sujeto y sin nada que falle: la pagina «con de todo» tambien lo mide,
     * pero alli una vuelta envuelta en UNA transaccion revienta antes por la huerfana —el {@code
     * TodaviaNo} la marca de rollback— y el rojo habla de eso y no del acuse. Aqui no hay nada que
     * deshacer, asi que lo unico que puede fallar es el ORDEN entre el commit y el acuse.
     */
    @Test
    @DisplayName("el acuse ve la copia YA confirmada: va despues del commit (AC-7 #1)")
    void elAcuseVaDespuesDelCommit() throws SQLException {
        buzon.sirve(
                evento(
                        40,
                        "USUARIO_DADO_DE_ALTA",
                        "{\"usuarioId\":4,\"cuenta\":\"acuse.tarde\",\"nombre\":\"Acuse"
                                + " Tarde\",\"correo\":null,\"habilitado\":true,"
                                + "\"vigenciaDesde\":null,\"vigenciaHasta\":null}"));
        long aplicadosAntes = contar("identidad_evento_aplicado");

        ConsumirEventosDeIdentidad.Vuelta vuelta = consumidor.consumir();

        assertThat(vuelta.aplicados()).isEqualTo(1);
        assertThat(buzon.acusados).hasSize(1);
        assertThat(buzon.aplicadosVisiblesAlAcusar - aplicadosAntes)
                .as(
                        "[en el instante del acuse, OTRA conexion ya ve el acuse local: el evento"
                                + " esta confirmado. Si el acuse viajara antes del commit, un fallo"
                                + " en ese hueco dejaria a `identidad` sin volver a servirlo y a"
                                + " esta copia sin tenerlo — perdido para siempre]")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "la MISMA pagina otra vez no escribe nada: todo esta ya aplicado, y se vuelve a acusar")
    void laMismaPaginaOtraVez() throws SQLException {
        EventoDeIdentidadRecibido usuario =
                evento(
                        10,
                        "USUARIO_DADO_DE_ALTA",
                        "{\"usuarioId\":2,\"cuenta\":\"mlopez\",\"nombre\":\"Maria Lopez\","
                                + "\"correo\":null,\"habilitado\":true,\"vigenciaDesde\":null,"
                                + "\"vigenciaHasta\":null}");
        buzon.sirve(usuario);
        consumidor.consumir();
        long usuariosAntes = contar("usuario");

        buzon.sirve(usuario);
        ConsumirEventosDeIdentidad.Vuelta segunda = consumidor.consumir();

        assertThat(segunda.yaEstaban()).isEqualTo(1);
        assertThat(segunda.aplicados()).isZero();
        assertThat(contar("usuario")).isEqualTo(usuariosAntes);
        assertThat(buzon.acusados).hasSize(2);
    }

    @Test
    @DisplayName("un fallo TRANSITORIO al aplicar no aparta nada, no acusa nada y sube (AC-7 #3)")
    void unFalloTransitorioSube() throws SQLException {
        buzon.sirve(
                evento(
                        20,
                        "USUARIO_DADO_DE_ALTA",
                        "{\"usuarioId\":3,\"cuenta\":\"rcastro\",\"nombre\":\"Rosa Castro\","
                                + "\"correo\":null,\"habilitado\":true,\"vigenciaDesde\":null,"
                                + "\"vigenciaHasta\":null}"));
        ConsumirEventosDeIdentidad conLaBaseCaida =
                consumidorCon(
                        envolver(
                                new AplicarUnEventoDeIdentidad(
                                        jdbc,
                                        JsonMapper.builder().build(),
                                        Clock.fixed(AHORA, ZoneOffset.UTC)) {
                                    @Override
                                    public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
                                        throw new QueryTimeoutException(
                                                "la base no contesto a tiempo");
                                    }
                                }));
        long muertosAntes = contar("identidad_evento_muerto");

        assertThatThrownBy(conLaBaseCaida::consumir)
                .as(
                        "un fallo de la base es transitorio: se arregla solo y la corrida tiene que"
                                + " acabar en rojo, no tragarselo")
                .isInstanceOf(DataAccessException.class);

        assertThat(contar("identidad_evento_muerto"))
                .as(
                        "[mandar a muertos un fallo transitorio es matar un permiso que alguien"
                                + " concedio por un tiempo fuera de la base: el evento tiene que"
                                + " seguir pendiente en el buzon]")
                .isEqualTo(muertosAntes);
        assertThat(buzon.acusados).as("y sin acuse: el buzon lo vuelve a servir").isEmpty();
        assertThat(alerta.avisos).isEmpty();
    }

    @Test
    @DisplayName(
            "un acuse RECHAZADO se registra, no se reintenta, y la vuelta se declara sin progreso")
    void unAcuseRechazado() throws SQLException {
        buzon.sirve(
                evento(
                        30,
                        "GRUPO_DADO_DE_ALTA",
                        "{\"grupoId\":2,\"nombre\":\"Supervisores\",\"descripcion\":null,"
                                + "\"habilitado\":true,\"vigenciaDesde\":null,"
                                + "\"vigenciaHasta\":null}"));
        buzon.rechazaElAcuse = true;

        ConsumirEventosDeIdentidad.Vuelta vuelta = consumidor.consumir();

        assertThat(vuelta.acuseRechazado()).isTrue();
        assertThat(vuelta.sinProgreso()).isTrue();
        assertThat(vuelta.aplicados()).as("lo aplicado SIGUE aplicado").isEqualTo(1);
        assertThat(contar("grupo")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("y con el buzon vacio no se acusa nada y no hay progreso")
    void conElBuzonVacio() {
        ConsumirEventosDeIdentidad.Vuelta vuelta = consumidor.consumir();

        assertThat(vuelta.leidos()).isZero();
        assertThat(vuelta.sinProgreso()).isTrue();
        assertThat(buzon.acusados).isEmpty();
    }

    // ------------------------------------------------------------------

    private static AplicarUnEventoDeIdentidad aplicadorDeVerdad() {
        return envolver(
                new AplicarUnEventoDeIdentidad(
                        jdbc, JsonMapper.builder().build(), Clock.fixed(AHORA, ZoneOffset.UTC)));
    }

    private ConsumirEventosDeIdentidad consumidorCon(AplicarUnEventoDeIdentidad aplicador) {
        return envolver(new ConsumirEventosDeIdentidad(buzon, aplicador, alerta));
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static EventoDeIdentidadRecibido evento(long secuencia, String tipo, String cuerpo) {
        return new EventoDeIdentidadRecibido(
                UUID.randomUUID(), secuencia, tipo, 1L, cuerpo, HUELLA, AHORA);
    }

    private static String permiso(String sistema) {
        return "{\"sujeto\":\"GRUPO\",\"sujetoId\":1,\"sujetoNombre\":\"Mesa de"
                + " Partes\",\"sistema\":\""
                + sistema
                + "\",\"codigo\":\"permisos\",\"privilegios\":{\"ejecucion\":false,"
                + "\"lectura\":true,\"registro\":false,\"modificacion\":false,"
                + "\"eliminacion\":false,\"impresion\":false,\"especial\":false},"
                + "\"usuarioRegistro\":\"admin\"}";
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute(
                    "INSERT INTO municipalidad (ubigeo, nombre, tipo) VALUES ('"
                            + ubigeo
                            + "', '"
                            + nombre
                            + "', 'DISTRITAL') ON CONFLICT (ubigeo) DO NOTHING");
            try (ResultSet fila =
                    sentencia.executeQuery(
                            "SELECT id FROM municipalidad WHERE ubigeo = '" + ubigeo + "'")) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static long contar(String tabla) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM " + tabla + " WHERE municipalidad_id = ?")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static final class BuzonDeMentira implements FuenteDeEventosDeIdentidad {
        private final List<EventoDeIdentidadRecibido> pendientes = new ArrayList<>();
        private final List<UUID> acusados = new ArrayList<>();
        private long aplicadosVisiblesAlAcusar = -1;
        private long muertosVisiblesAlAcusar = -1;
        private boolean rechazaElAcuse;

        void sirve(EventoDeIdentidadRecibido... eventos) {
            pendientes.addAll(List.of(eventos));
        }

        @Override
        public Lote pendientes(int limite) {
            List<EventoDeIdentidadRecibido> pagina = List.copyOf(pendientes);
            pendientes.clear();
            return new Lote(pagina, pagina.size());
        }

        @Override
        public Acuse acusar(List<UUID> eventoIds) {
            try {
                aplicadosVisiblesAlAcusar = contar("identidad_evento_aplicado");
                muertosVisiblesAlAcusar = contar("identidad_evento_muerto");
            } catch (SQLException noSePudoMirar) {
                throw new IllegalStateException(
                        "el buzon de mentira no pudo mirar la copia", noSePudoMirar);
            }
            if (rechazaElAcuse) {
                throw new AcuseRechazado(422, "{\"codigo\":\"VALIDACION\"}");
            }
            acusados.addAll(eventoIds);
            return new Acuse(eventoIds.size(), eventoIds.size(), 0);
        }
    }

    private static final class AlertaQueAnota implements AlertaDeEventosSinAplicar {
        private final List<String> avisos = new ArrayList<>();

        @Override
        public void hayUnEventoSinAplicar(
                EventoDeIdentidadRecibido evento, String motivo, long apartados) {
            avisos.add(evento.tipoPublicado() + ": " + motivo + " apartados=" + apartados);
        }
    }
}
