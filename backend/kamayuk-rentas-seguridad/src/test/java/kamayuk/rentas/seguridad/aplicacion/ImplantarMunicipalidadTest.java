package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import kamayuk.rentas.seguridad.infraestructura.ClienteHttpDelBuzonDeIdentidad;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.LecturaDeLaCopiaLocalJdbc;
import kamayuk.rentas.seguridad.infraestructura.RegistroDeMunicipalidadesJdbc;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * La implantacion contra PostgreSQL de verdad, en la forma que tiene desde la etapa 5 de ADR-0039:
 * <b>da de alta la municipalidad, siembra su CATALOGO y trae la autorizacion del buzon de {@code
 * identidad}</b>. Lo que se mide es lo que hay en la copia local y lo que el guardia de acceso
 * contesta despues, que es lo unico que decide si la municipalidad se puede usar.
 *
 * <h2>AC-2 — la implantacion de cero, y por que el buzon es de mentira pero la corriente no</h2>
 *
 * <p>Hasta la etapa 4 el administrador lo escribia el sembrador con cuatro {@code INSERT} directos,
 * asi que esta clase podia medir «hay administrador» sin que existiera ningun buzon. Retirados esos
 * {@code INSERT}, la unica forma de que alguien pueda entrar es que el buzon lo traiga — y por eso
 * aqui hay un buzon de mentira sobre {@code ServerSocket}, con el cliente HTTP de <b>produccion</b>
 * delante ({@link ClienteHttpDelBuzonDeIdentidad}) y el aplicador de <b>produccion</b> detras.
 *
 * <p><b>Lo que sirve ese buzon es la corriente de verdad</b>, copiada campo a campo de {@code
 * identidad} y no inventada: los siete campos del evento ({@code eventoId}, {@code secuencia},
 * {@code tipo}, {@code sujetoId}, {@code cuerpo}, {@code huella}, {@code creadoEn}) tal como los
 * publica su {@code EventosController.EventoResource}; los cuerpos tal como los compone su {@code
 * HechoDeIdentidad} —el usuario con {@code usuarioId/cuenta/nombre/correo/habilitado/vigencia*}, el
 * miembro con las dos claves naturales, el permiso con {@code sujeto}, {@code sujetoNombre}, {@code
 * sistema}, {@code codigo} y sus SIETE privilegios—; y {@code quedan} <b>despues</b> del acuse, que
 * es lo que aquel controlador publica y lo que tres de los cuatro consumidores servian mal en la
 * etapa 4.
 *
 * <h2>Y el orden que emite es el de su implantacion</h2>
 *
 * <p>Grupo de administracion, administrador, su afiliacion, sus permisos sobre las opciones de
 * <b>los cinco</b> catalogos —de los que a esta copia solo le tocan los suyos—, el grupo del buzon
 * y las cuatro cuentas de servicio con sus afiliaciones. Es lo que {@code
 * ImplantacionEmiteSusEventosTest} de {@code identidad} afirma que emite.
 */
@DisplayName("Etapa 5 — implantar una municipalidad, con la autorizacion llegando por el buzon")
class ImplantarMunicipalidadTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDate HOY = LocalDate.of(2026, 8, 20);

    private static final String ADMINISTRADOR = "administrador";
    private static final String GRUPO_DE_ADMINISTRACION = "Administracion del sistema";
    private static final String GRUPO_DE_CONSUMIDORES = "Consumidores del buzon";

    /** Las cuatro cuentas de servicio que la implantacion de `identidad` da de alta y afilia. */
    private static final List<String> CONSUMIDORES =
            List.of("caja", "catastro", "normativa", "rentas");

    private static BaseDeDatosDePrueba base;
    private static JdbcClient jdbc;
    private static ComprobadorDeAcceso comprobador;
    private static TransactionTemplate transaccion;
    private static RegistroDeMunicipalidades registro;
    private static SembradorDelCatalogo sembrador;
    private static LecturaDeLaCopiaLocal copiaLocal;
    private static AplicarUnEventoDeIdentidad aplicador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        comprobador = envolver(new ComprobadorDeAccesoJdbc(jdbc), gestor);
        sembrador =
                envolver(
                        new SembradorDelCatalogo(jdbc, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        copiaLocal = envolver(new LecturaDeLaCopiaLocalJdbc(jdbc), gestor);
        aplicador =
                envolver(
                        new AplicarUnEventoDeIdentidad(jdbc, JsonMapper.builder().build(), RELOJ),
                        gestor);

        registro =
                new RegistroDeMunicipalidadesJdbc(
                        base.url(),
                        BaseDeDatosDePrueba.OWNER,
                        base.clave(BaseDeDatosDePrueba.OWNER));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ------------------------------------------------------------------
    // El armado de la implantacion
    // ------------------------------------------------------------------

    private static ImplantarMunicipalidad implantacion(
            String ubigeo,
            boolean esDemostracion,
            ObjectProvider<PasadaDelConsumidorDeIdentidad> pasada) {
        return new ImplantarMunicipalidad(
                registro,
                sembrador,
                copiaLocal,
                new DatosDeImplantacion(
                        ubigeo,
                        "Municipalidad de prueba " + ubigeo,
                        "DISTRITAL",
                        ADMINISTRADOR,
                        "Administrador de la implantacion",
                        esDemostracion,
                        "implantacion"),
                pasada);
    }

    /** El consumidor de produccion entero, del cliente HTTP al aplicador, contra ese buzon. */
    private static PasadaDelConsumidorDeIdentidad pasadaContra(
            BuzonDeMentira buzon, AlertaQueAnota alerta) {
        ClienteHttpDelBuzonDeIdentidad cliente =
                new ClienteHttpDelBuzonDeIdentidad(
                        JsonMapper.builder().build(),
                        buzon.raiz(),
                        CredencialDeServicio.fija("Bearer el-token-de-servicio"));
        return new PasadaDelConsumidorDeIdentidad(
                new ConsumirEventosDeIdentidad(cliente, aplicador, alerta), alerta, RELOJ);
    }

    private static ObjectProvider<PasadaDelConsumidorDeIdentidad> hay(
            PasadaDelConsumidorDeIdentidad pasada) {
        return new ProveedorDePrueba(pasada);
    }

    private static ObjectProvider<PasadaDelConsumidorDeIdentidad> noHay() {
        return new ProveedorDePrueba(null);
    }

    private static long contar(String sql) {
        Long cuenta = transaccion.execute(estado -> jdbc.sql(sql).query(Long.class).single());
        if (cuenta == null) {
            throw new IllegalStateException("sin cuenta para: " + sql);
        }
        return cuenta;
    }

    private static List<String> filas(String sql) {
        List<String> resultado =
                transaccion.execute(estado -> jdbc.sql(sql).query(String.class).list());
        return resultado == null ? List.of() : resultado;
    }

    private static boolean esDemostracion(String ubigeo) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT es_demostracion FROM municipalidad WHERE ubigeo = :u")
                        .param("u", ubigeo)
                        .query(Boolean.class)
                        .single());
    }

    private static long idDe(String ubigeo) {
        return jdbc.sql("SELECT id FROM municipalidad WHERE ubigeo = :u")
                .param("u", ubigeo)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-2 — de cero, con `identidad` implantado antes")
    class DeCero {

        @Test
        @DisplayName(
                "el administrador llega POR EL BUZON, con sus siete privilegios sobre las opciones"
                        + " de este sistema")
        void deCeroPorElBuzon() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira buzon =
                    BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion())) {
                implantacion("270101", false, hay(pasadaContra(buzon, alerta))).run(null);
                TenantContext.fijar(new MunicipalidadId(idDe("270101")));

                // Lo que la implantacion SIEMBRA: el catalogo de este sistema, y nada mas.
                assertThat(contar("SELECT count(*) FROM acceso"))
                        .as(
                                "el catalogo lo sigue sembrando este sistema: es lo que hay, no a quien"
                                        + " se le concede")
                        .isEqualTo(CatalogoDeOpciones.leer().size());

                // Lo que la implantacion NO siembra y aun asi esta: llego por el buzon.
                assertThat(filas("SELECT cuenta FROM usuario ORDER BY cuenta"))
                        .as(
                                "[AC-2: antes de la primera peticion. Ninguna de estas cinco filas la"
                                        + " escribe `rentas`: las cinco vienen del buzon de `identidad`]")
                        .containsExactly(
                                ADMINISTRADOR,
                                "service-account-kamayuk-caja-servicio-270101",
                                "service-account-kamayuk-catastro-servicio-270101",
                                "service-account-kamayuk-normativa-servicio-270101",
                                "service-account-kamayuk-rentas-servicio-270101");
                assertThat(filas("SELECT nombre FROM grupo ORDER BY nombre"))
                        .containsExactly(GRUPO_DE_ADMINISTRACION, GRUPO_DE_CONSUMIDORES);
                assertThat(contar("SELECT count(*) FROM miembro")).isEqualTo(5);
                assertThat(contar("SELECT count(*) FROM permiso"))
                        .as(
                                "uno por opcion de ESTE sistema: los de los otros cuatro catalogos se"
                                        + " acusan y se ignoran, y el del grupo del buzon es de `identidad`")
                        .isEqualTo(CatalogoDeOpciones.leer().size());

                // Y lo unico que decide si se puede usar: lo que el guardia contesta.
                for (CatalogoDeOpciones.Opcion opcion : CatalogoDeOpciones.leer()) {
                    for (Privilegio privilegio : EnumSet.allOf(Privilegio.class)) {
                        assertThat(
                                        comprobador.autoriza(
                                                ADMINISTRADOR, opcion.codigo(), privilegio, HOY))
                                .as("%s / %s", opcion.codigo(), privilegio)
                                .isTrue();
                    }
                }
                assertThat(alerta.pospuestos).as("nada quedo esperando").isEmpty();
                assertThat(alerta.apartados).as("y nada se aparto").isEmpty();
            }
        }

        @Test
        @DisplayName(
                "un evento pospuesto NO tumba la implantacion: lo que se comprueba es el resultado")
        void unPospuestoNoTumbaLaImplantacion() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            List<EventoServido> corriente =
                    new ArrayList<>(CorrienteDeIdentidad.deUnaImplantacion());
            // Una afiliacion a un grupo que este lote NO trae: `identidad` lo emitio y su alta va
            // en otro lote. Es el pospuesto legitimo, y el CronJob avisa de el sin fallar.
            corriente.add(
                    CorrienteDeIdentidad.miembro(
                            9000, "Grupo que llega despues", 9001, ADMINISTRADOR));
            try (BuzonDeMentira buzon = BuzonDeMentira.arranca(corriente)) {
                implantacion("270102", false, hay(pasadaContra(buzon, alerta))).run(null);
                TenantContext.fijar(new MunicipalidadId(idDe("270102")));

                assertThat(contar("SELECT count(*) FROM usuario"))
                        .as(
                                "[la implantacion comprueba el RESULTADO —que alguien pueda entrar— y"
                                        + " no que la cola quede vacia: un pospuesto se reintenta solo, y"
                                        + " hacer fallar la implantacion por el convertiria el CronJob de"
                                        + " cada cinco minutos en un Job que falla siempre]")
                        .isEqualTo(5);
                assertThat(buzon.acusados())
                        .as("y el pospuesto NO se acusa: el buzon lo vuelve a servir")
                        .doesNotContain(corriente.get(corriente.size() - 1).eventoId());
            }
        }

        @Test
        @DisplayName(
                "y si `identidad` da de alta a OTRO administrador, la implantacion avisa y NO"
                        + " falla")
        void otroAdministradorNoTumbaLaImplantacion() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            List<EventoServido> corriente =
                    CorrienteDeIdentidad.deUnaImplantacion("otra.cuenta.de.administrador");
            try (BuzonDeMentira buzon = BuzonDeMentira.arranca(corriente)) {
                implantacion("270104", false, hay(pasadaContra(buzon, alerta))).run(null);
                TenantContext.fijar(new MunicipalidadId(idDe("270104")));

                assertThat(
                                contar(
                                        "SELECT count(*) FROM usuario WHERE cuenta = '"
                                                + ADMINISTRADOR
                                                + "'"))
                        .as("la cuenta que este despliegue declara no llego")
                        .isZero();
                assertThat(contar("SELECT count(*) FROM usuario"))
                        .as(
                                "[y AUN ASI la implantacion termina bien, que es la decision:"
                                        + " renombrar o dar de baja al administrador en `identidad` es un"
                                        + " acto legitimo de quien administra, y hacerlo fallar dejaria"
                                        + " todo despliegue posterior de `rentas` en rojo por una decision"
                                        + " tomada en otro sistema y que no impide que nadie entre]")
                        .isEqualTo(5);
            }
        }

        @Test
        @DisplayName("relanzar el despliegue no duplica nada: el buzon ya no tiene que servir")
        void relanzarNoDuplica() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira buzon =
                    BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion())) {
                implantacion("270103", false, hay(pasadaContra(buzon, alerta))).run(null);
                implantacion("270103", false, hay(pasadaContra(buzon, alerta))).run(null);
                TenantContext.fijar(new MunicipalidadId(idDe("270103")));

                assertThat(contar("SELECT count(*) FROM usuario")).isEqualTo(5);
                assertThat(contar("SELECT count(*) FROM grupo")).isEqualTo(2);
                assertThat(contar("SELECT count(*) FROM permiso"))
                        .isEqualTo(CatalogoDeOpciones.leer().size());
                assertThat(contar("SELECT count(*) FROM acceso"))
                        .isEqualTo(CatalogoDeOpciones.leer().size());
            }
        }
    }

    @Nested
    @DisplayName("AC-3 — el orden equivocado falla diciendolo")
    class ElOrdenEquivocado {

        @Test
        @DisplayName("sin buzon configurado NO termina en verde: falla nombrando lo que falta")
        void sinBuzonConfigurado() {
            Throwable rojo = catchThrowable(() -> implantacion("270201", false, noHay()).run(null));

            assertThat(rojo)
                    .as(
                            "[un Job que sale con codigo 0 se lee en Kubernetes como `Complete`, y eso"
                                    + " es lo que C-18 encontro haciendo el Job de `rentas` durante meses]")
                    .isInstanceOf(ImplantarMunicipalidad.SinLaAutorizacionDeIdentidad.class)
                    .hasMessageContaining("KAMAYUK_IDENTIDAD_URL")
                    .hasMessageContaining("implantar `identidad` PRIMERO");
            TenantContext.fijar(new MunicipalidadId(idDe("270201")));
            assertThat(contar("SELECT count(*) FROM usuario"))
                    .as(
                            "y la copia se queda con CERO cuentas, que es justo lo que no puede pasar"
                                    + " en silencio")
                    .isZero();
        }

        @Test
        @DisplayName("un buzon que contesta 403 falla con el 403 dentro y con el remedio")
        void unBuzonQueNiega() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira buzon = BuzonDeMentira.queNiega(403)) {
                Throwable rojo =
                        catchThrowable(
                                () ->
                                        implantacion(
                                                        "270202",
                                                        false,
                                                        hay(pasadaContra(buzon, alerta)))
                                                .run(null));

                assertThat(rojo)
                        .isInstanceOf(ImplantarMunicipalidad.SinLaAutorizacionDeIdentidad.class)
                        .hasMessageContaining("403")
                        .hasMessageContaining("implantar `identidad` PRIMERO");
                assertThat(rojo.getCause())
                        .as(
                                "con la transitoria dentro: quien atiende tiene que poder ver que fue"
                                        + " el transporte")
                        .isInstanceOf(
                                kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad
                                        .IdentidadNoContesta.class);
            }
            TenantContext.fijar(new MunicipalidadId(idDe("270202")));
            assertThat(contar("SELECT count(*) FROM usuario")).isZero();
        }

        @Test
        @DisplayName(
                "y el caso mudo: `identidad` contesta 200 con la cola VACIA porque no se implanto")
        void elBuzonVacio() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira buzon = BuzonDeMentira.arranca(List.of())) {
                Throwable rojo =
                        catchThrowable(
                                () ->
                                        implantacion(
                                                        "270203",
                                                        false,
                                                        hay(pasadaContra(buzon, alerta)))
                                                .run(null));

                assertThat(rojo)
                        .as(
                                "[este es el que no tiene sintoma: el buzon esta en pie, contesta 200 y"
                                        + " no trae nada, porque `identidad` no ha implantado esta"
                                        + " municipalidad. Sin esta comprobacion el Job sale `Complete` con"
                                        + " el catalogo sembrado y sin una sola cuenta]")
                        .isInstanceOf(ImplantarMunicipalidad.SinLaAutorizacionDeIdentidad.class)
                        .hasMessageContaining("no trajo ni una cuenta")
                        .hasMessageContaining(ADMINISTRADOR)
                        .hasMessageContaining("implantar `identidad` PRIMERO");
            }
            TenantContext.fijar(new MunicipalidadId(idDe("270203")));
            assertThat(contar("SELECT count(*) FROM acceso"))
                    .as("el catalogo SI quedo sembrado: lo que falta es a quien concedersela")
                    .isEqualTo(CatalogoDeOpciones.leer().size());
            assertThat(contar("SELECT count(*) FROM usuario")).isZero();
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades implantadas")
    class Aislamiento {

        @Test
        @DisplayName("desde B, el administrador de A no existe")
        void desdeBElAdministradorDeANoExiste() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira deA =
                            BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion());
                    BuzonDeMentira deB =
                            BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion())) {
                implantacion("270301", false, hay(pasadaContra(deA, alerta))).run(null);
                implantacion("270302", false, hay(pasadaContra(deB, alerta))).run(null);
            }
            TenantContext.fijar(new MunicipalidadId(idDe("270302")));

            assertThat(contar("SELECT count(*) FROM usuario"))
                    .as("dos municipalidades implantadas en la misma base no se ven entre si")
                    .isEqualTo(5);
            assertThat(contar("SELECT count(*) FROM permiso"))
                    .isEqualTo(CatalogoDeOpciones.leer().size());
        }
    }

    @Nested
    @DisplayName("#122 — El regimen con que se implanta queda en la fila")
    class ElRegimen {

        @Test
        @DisplayName(
                "implantada como demostracion, la fila lo dice, y relanzar no le quita la marca")
        void laFilaLoDice() throws IOException {
            AlertaQueAnota alerta = new AlertaQueAnota();
            try (BuzonDeMentira buzon =
                    BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion())) {
                implantacion("270401", true, hay(pasadaContra(buzon, alerta))).run(null);
                assertThat(esDemostracion("270401"))
                        .as("de ahi lo lee la capa de documentos para marcar todo lo que emita")
                        .isTrue();

                implantacion("270401", false, hay(pasadaContra(buzon, alerta))).run(null);
                assertThat(esDemostracion("270401"))
                        .as("la segunda implantacion pidio false, y la fila sigue marcada")
                        .isTrue();
            }
            try (BuzonDeMentira buzon =
                    BuzonDeMentira.arranca(CorrienteDeIdentidad.deUnaImplantacion())) {
                implantacion("270402", false, hay(pasadaContra(buzon, alerta))).run(null);
                assertThat(esDemostracion("270402")).isFalse();
            }
        }
    }

    // ==================================================================
    // La corriente de eventos, copiada de `identidad`
    // ==================================================================

    /** Un evento tal como lo sirve {@code EventosController} de `identidad`: sus SIETE campos. */
    private record EventoServido(
            String eventoId,
            long secuencia,
            String tipo,
            long sujetoId,
            String cuerpo,
            String huella,
            String creadoEn) {}

    /**
     * Lo que la implantacion de `identidad` emite para una municipalidad, en su orden.
     *
     * <p>Los cuerpos son los de su {@code HechoDeIdentidad} campo a campo. Los permisos NO se
     * escriben a mano: salen del catalogo de este sistema, para que el dia que `rentas` estrene una
     * pantalla esta prueba siga midiendo lo mismo sin tocarla.
     */
    private static final class CorrienteDeIdentidad {

        private static final String UBIGEO_DE_LA_CUENTA = "270101";
        private static final long GRUPO_ADMIN = 1;
        private static final long GRUPO_BUZON = 2;
        private static final long USUARIO_ADMIN = 1;

        private CorrienteDeIdentidad() {}

        static List<EventoServido> deUnaImplantacion() {
            return deUnaImplantacion(ADMINISTRADOR);
        }

        static List<EventoServido> deUnaImplantacion(String cuentaDelAdministrador) {
            List<EventoServido> eventos = new ArrayList<>();
            eventos.add(grupo(GRUPO_ADMIN, GRUPO_DE_ADMINISTRACION, "Creado por la implantacion"));
            eventos.add(
                    usuario(USUARIO_ADMIN, cuentaDelAdministrador, "Administrador del Sistema"));
            eventos.add(
                    miembro(
                            GRUPO_ADMIN,
                            GRUPO_DE_ADMINISTRACION,
                            USUARIO_ADMIN,
                            cuentaDelAdministrador));
            for (CatalogoDeOpciones.Opcion opcion : CatalogoDeOpciones.leer()) {
                eventos.add(
                        permisoDeGrupo(
                                GRUPO_ADMIN, GRUPO_DE_ADMINISTRACION, "rentas", opcion.codigo()));
            }
            // De los otros catalogos: `identidad` publica los CINCO por un solo buzon, y a esta
            // copia solo le tocan los suyos. Se acusan y se ignoran (AC-7 #4 de la etapa 4).
            eventos.add(
                    permisoDeGrupo(GRUPO_ADMIN, GRUPO_DE_ADMINISTRACION, "identidad", "permisos"));
            eventos.add(
                    permisoDeGrupo(
                            GRUPO_ADMIN, GRUPO_DE_ADMINISTRACION, "catastro", "consulta_fichas"));
            eventos.add(
                    permisoDeGrupo(GRUPO_ADMIN, GRUPO_DE_ADMINISTRACION, "caja", "caja_apertura"));
            eventos.add(grupo(GRUPO_BUZON, GRUPO_DE_CONSUMIDORES, "Lee y acusa el buzon"));
            eventos.add(permisoDeGrupo(GRUPO_BUZON, GRUPO_DE_CONSUMIDORES, "identidad", "eventos"));
            long id = 10;
            for (String sistema : CONSUMIDORES) {
                eventos.add(
                        usuario(
                                ++id,
                                cuentaDeServicio(sistema),
                                "Cuenta de servicio de " + sistema));
                eventos.add(
                        miembro(GRUPO_BUZON, GRUPO_DE_CONSUMIDORES, id, cuentaDeServicio(sistema)));
            }
            List<EventoServido> conSecuencia = new ArrayList<>();
            long secuencia = 0;
            for (EventoServido evento : eventos) {
                conSecuencia.add(
                        new EventoServido(
                                evento.eventoId(),
                                ++secuencia,
                                evento.tipo(),
                                evento.sujetoId(),
                                evento.cuerpo(),
                                evento.huella(),
                                evento.creadoEn()));
            }
            return List.copyOf(conSecuencia);
        }

        private static String cuentaDeServicio(String sistema) {
            return "service-account-kamayuk-" + sistema + "-servicio-" + UBIGEO_DE_LA_CUENTA;
        }

        static EventoServido grupo(long grupoId, String nombre, String descripcion) {
            return evento(
                    "GRUPO_DADO_DE_ALTA",
                    grupoId,
                    "{\"grupoId\":"
                            + grupoId
                            + ",\"nombre\":\""
                            + nombre
                            + "\",\"descripcion\":\""
                            + descripcion
                            + "\",\"habilitado\":true,\"vigenciaDesde\":null,"
                            + "\"vigenciaHasta\":null}");
        }

        static EventoServido usuario(long usuarioId, String cuenta, String nombre) {
            return evento(
                    "USUARIO_DADO_DE_ALTA",
                    usuarioId,
                    "{\"usuarioId\":"
                            + usuarioId
                            + ",\"cuenta\":\""
                            + cuenta
                            + "\",\"nombre\":\""
                            + nombre
                            + "\",\"correo\":null,\"habilitado\":true,\"vigenciaDesde\":null,"
                            + "\"vigenciaHasta\":null}");
        }

        static EventoServido miembro(
                long grupoId, String grupoNombre, long usuarioId, String cuenta) {
            return evento(
                    "MIEMBRO_AFILIADO",
                    grupoId,
                    "{\"grupoId\":"
                            + grupoId
                            + ",\"grupoNombre\":\""
                            + grupoNombre
                            + "\",\"usuarioId\":"
                            + usuarioId
                            + ",\"usuarioCuenta\":\""
                            + cuenta
                            + "\",\"activo\":true,\"usuarioAlta\":\"implantacion\","
                            + "\"usuarioBaja\":null}");
        }

        static EventoServido permisoDeGrupo(
                long grupoId, String grupoNombre, String sistema, String codigo) {
            StringBuilder privilegios = new StringBuilder("{");
            boolean primero = true;
            for (Privilegio privilegio : Privilegio.values()) {
                if (!primero) {
                    privilegios.append(',');
                }
                primero = false;
                privilegios.append('"').append(privilegio.columna()).append("\":true");
            }
            privilegios.append('}');
            return evento(
                    "PERMISO_FIJADO",
                    grupoId,
                    "{\"sujeto\":\"GRUPO\",\"sujetoId\":"
                            + grupoId
                            + ",\"sujetoNombre\":\""
                            + grupoNombre
                            + "\",\"sistema\":\""
                            + sistema
                            + "\",\"codigo\":\""
                            + codigo
                            + "\",\"privilegios\":"
                            + privilegios
                            + ",\"usuarioRegistro\":\"implantacion\"}");
        }

        private static EventoServido evento(String tipo, long sujetoId, String cuerpo) {
            return new EventoServido(
                    UUID.randomUUID().toString(),
                    0,
                    tipo,
                    sujetoId,
                    cuerpo,
                    "a".repeat(64),
                    "2026-08-20T09:59:00Z");
        }
    }

    // ==================================================================
    // El buzon de mentira: con estado, y con `quedan` DESPUES del acuse
    // ==================================================================

    /**
     * Un buzon con cola: sirve paginas de {@code limite} y retira lo acusado.
     *
     * <p><b>{@code quedan} se calcula DESPUES de escribir el acuse</b>, que es lo que {@code
     * EventosController} de `identidad` publica —{@code entrega.pendientesPara(consumidor, 1)
     * .quedan()} despues de {@code acusar}— y lo que tres de los cuatro consumidores servian mal en
     * sus dobles durante la etapa 4: restaban la pagina, o sea mentian justo sobre el campo que
     * mide el retraso.
     */
    private static final class BuzonDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private final Map<String, EventoServido> cola =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<String> acusados = Collections.synchronizedList(new ArrayList<>());
        private final int estadoDeNegativa;

        private BuzonDeMentira(
                ServerSocket socket, List<EventoServido> eventos, int estadoDeNegativa) {
            this.socket = socket;
            this.estadoDeNegativa = estadoDeNegativa;
            for (EventoServido evento : eventos) {
                cola.put(evento.eventoId(), evento);
            }
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        atender(cliente);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "buzon-de-identidad-de-la-implantacion");
            this.hilo.setDaemon(true);
        }

        static BuzonDeMentira arranca(List<EventoServido> eventos) throws IOException {
            return levantar(eventos, 0);
        }

        static BuzonDeMentira queNiega(int estado) throws IOException {
            return levantar(List.of(), estado);
        }

        private static BuzonDeMentira levantar(List<EventoServido> eventos, int estado)
                throws IOException {
            ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            BuzonDeMentira buzon = new BuzonDeMentira(socket, eventos, estado);
            buzon.hilo.start();
            return buzon;
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/identidad/api/v1";
        }

        List<String> acusados() {
            return List.copyOf(acusados);
        }

        private void atender(Socket cliente) throws IOException {
            BufferedReader lector =
                    new BufferedReader(
                            new InputStreamReader(
                                    cliente.getInputStream(), StandardCharsets.UTF_8));
            String primera = lector.readLine();
            String linea = primera == null ? "" : primera;
            int longitud = 0;
            String cabecera = lector.readLine();
            while (cabecera != null && !cabecera.isEmpty()) {
                if (cabecera.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    longitud =
                            Integer.parseInt(cabecera.substring(cabecera.indexOf(':') + 1).trim());
                }
                cabecera = lector.readLine();
            }
            char[] datos = new char[longitud];
            int leidos = 0;
            while (leidos < longitud) {
                int n = lector.read(datos, leidos, longitud - leidos);
                if (n < 0) {
                    break;
                }
                leidos += n;
            }
            String cuerpoDeLaPeticion = new String(datos, 0, Math.max(leidos, 0));

            if (estadoDeNegativa != 0) {
                responder(cliente, estadoDeNegativa, "{\"detail\":\"la cuenta no esta afiliada\"}");
                return;
            }
            if (linea.startsWith("GET")) {
                responder(cliente, 200, pagina(limiteDe(linea)));
            } else {
                responder(cliente, 200, acusar(cuerpoDeLaPeticion));
            }
        }

        private static int limiteDe(String linea) {
            int marca = linea.indexOf("limite=");
            if (marca < 0) {
                return 200;
            }
            String resto = linea.substring(marca + "limite=".length());
            int fin = 0;
            while (fin < resto.length() && Character.isDigit(resto.charAt(fin))) {
                fin++;
            }
            return fin == 0 ? 200 : Integer.parseInt(resto.substring(0, fin));
        }

        private String pagina(int limite) {
            StringBuilder salida = new StringBuilder("{\"eventos\":[");
            int puestos = 0;
            synchronized (cola) {
                for (EventoServido evento : cola.values()) {
                    if (puestos == limite) {
                        break;
                    }
                    if (puestos > 0) {
                        salida.append(',');
                    }
                    salida.append(comoJson(evento));
                    puestos++;
                }
                // `quedan` es la cola ENTERA, contando los de esta pagina: es «cuantos le faltan
                // en total», que es lo que EventosController publica.
                salida.append("],\"quedan\":").append(cola.size()).append('}');
            }
            return salida.toString();
        }

        private String acusar(String cuerpo) {
            List<String> ids = new ArrayList<>();
            int desde = cuerpo.indexOf('[');
            for (String trozo : cuerpo.substring(desde + 1, cuerpo.lastIndexOf(']')).split(",")) {
                String id = trozo.replace("\"", "").strip();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            int escritos = 0;
            synchronized (cola) {
                for (String id : ids) {
                    if (cola.remove(id) != null) {
                        escritos++;
                    }
                    acusados.add(id);
                }
                return "{\"recibidos\":"
                        + ids.size()
                        + ",\"escritos\":"
                        + escritos
                        + ",\"quedan\":"
                        + cola.size()
                        + "}";
            }
        }

        private static String comoJson(EventoServido evento) {
            return "{\"eventoId\":\""
                    + evento.eventoId()
                    + "\",\"secuencia\":"
                    + evento.secuencia()
                    + ",\"tipo\":\""
                    + evento.tipo()
                    + "\",\"sujetoId\":"
                    + evento.sujetoId()
                    + ",\"cuerpo\":\""
                    + evento.cuerpo().replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"huella\":\""
                    + evento.huella()
                    + "\",\"creadoEn\":\""
                    + evento.creadoEn()
                    + "\"}";
        }

        private static void responder(Socket cliente, int estado, String cuerpo)
                throws IOException {
            byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
            OutputStream salida = cliente.getOutputStream();
            salida.write(
                    ("HTTP/1.1 "
                                    + estado
                                    + " \r\nContent-Type: application/json\r\nContent-Length: "
                                    + datos.length
                                    + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            salida.write(datos);
            salida.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Un {@code ObjectProvider} con —o sin— la pasada.
     *
     * <p>Sin ella es lo que Spring le entrega a la implantacion cuando el despliegue no puso {@code
     * KAMAYUK_IDENTIDAD_URL}: {@code ConfiguracionDelConsumidorDeIdentidad} esta condicionada a esa
     * propiedad, asi que el bean no existe.
     */
    private record ProveedorDePrueba(@Nullable PasadaDelConsumidorDeIdentidad pasada)
            implements ObjectProvider<PasadaDelConsumidorDeIdentidad> {

        @Override
        public PasadaDelConsumidorDeIdentidad getObject() throws BeansException {
            if (pasada == null) {
                throw new NoSuchBeanDefinitionException(PasadaDelConsumidorDeIdentidad.class);
            }
            return pasada;
        }

        @Override
        public PasadaDelConsumidorDeIdentidad getObject(Object... args) throws BeansException {
            return getObject();
        }

        @Override
        public @Nullable PasadaDelConsumidorDeIdentidad getIfAvailable() throws BeansException {
            return pasada;
        }

        @Override
        public @Nullable PasadaDelConsumidorDeIdentidad getIfUnique() throws BeansException {
            return pasada;
        }
    }

    /** Anota los dos avisos por separado: son dos hechos distintos. */
    private static final class AlertaQueAnota implements AlertaDeEventosSinAplicar {
        private final List<String> apartados = new ArrayList<>();
        private final List<String> pospuestos = new ArrayList<>();

        @Override
        public void hayUnEventoSinAplicar(
                EventoDeIdentidadRecibido evento, String motivo, long cuantos) {
            apartados.add(evento.tipoPublicado() + ": " + motivo);
        }

        @Override
        public void hayPospuestosQueNoAvanzan(
                List<EventoPospuesto> lista, Instant ahora, java.time.Duration umbral) {
            pospuestos.add(lista.size() + " pospuestos");
        }
    }
}
