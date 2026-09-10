package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.LecturaDeLaCopiaLocalJdbc;
import kamayuk.rentas.seguridad.infraestructura.RegistroDeMunicipalidadesJdbc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La implantacion contra PostgreSQL de verdad, en la forma que tiene desde la etapa 4 de ADR-0039:
 * la de catastro, normativa y caja —el registro, la siembra de la copia local y nada mas—. Lo que
 * se mide es lo que el guardia de acceso contesta despues, que es lo unico que decide si la
 * municipalidad se puede usar.
 *
 * <p>El paso final de la implantacion —cederle el paso al consumidor del buzon— no se mide aqui: el
 * orden entre los dos runners lo mide {@code CorrerElConsumidorDeIdentidadTest}, y que el
 * consumidor sin {@code KAMAYUK_IDENTIDAD_URL} no exista lo decide Spring con
 * {@code @ConditionalOnProperty}, que una prueba unitaria no ejerce.
 */
@DisplayName("Implantacion de una municipalidad")
class ImplantarMunicipalidadTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDate HOY = LocalDate.of(2026, 8, 20);

    private static BaseDeDatosDePrueba base;
    private static JdbcClient jdbc;
    private static ComprobadorDeAcceso comprobador;
    private static TransactionTemplate transaccion;
    private static RegistroDeMunicipalidades registro;
    private static SembradorDeLaCopiaLocal sembrador;

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
                        new SembradorDeLaCopiaLocal(jdbc, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
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

    private static ImplantarMunicipalidad implantacion(String ubigeo, String administradorCuenta) {
        return implantacion(ubigeo, administradorCuenta, false);
    }

    private static ImplantarMunicipalidad implantacion(
            String ubigeo, String administradorCuenta, boolean esDemostracion) {
        return new ImplantarMunicipalidad(
                registro,
                sembrador,
                new DatosDeImplantacion(
                        ubigeo,
                        "Municipalidad de prueba " + ubigeo,
                        "DISTRITAL",
                        administradorCuenta,
                        "Administrador de la implantacion",
                        esDemostracion,
                        "implantacion"),
                "");
    }

    private static long implantar(String ubigeo, String administradorCuenta) {
        implantacion(ubigeo, administradorCuenta).run(null);
        return idDe(ubigeo);
    }

    private static long contar(String sql) {
        Long cuenta = transaccion.execute(estado -> jdbc.sql(sql).query(Long.class).single());
        if (cuenta == null) {
            throw new IllegalStateException("sin cuenta para: " + sql);
        }
        return cuenta;
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

    @Nested
    @DisplayName("Deja el sistema usable")
    class DejaElSistemaUsable {

        @Test
        @DisplayName("quedan sembrados todos los accesos del catalogo")
        void quedanSembradosTodosLosAccesos() {
            long municipalidad = implantar("250202", "admin.250202");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(contar("SELECT count(*) FROM acceso"))
                    .as(
                            "una opcion sin acceso sembrado es una opcion a la que nadie puede dar"
                                    + " permiso, y no se nota hasta que alguien la busca")
                    .isEqualTo(CatalogoDeOpciones.leer().size());
        }

        @Test
        @DisplayName("el administrador recibe el catalogo entero con los siete privilegios")
        void elAdministradorRecibeTodoElCatalogo() {
            long municipalidad = implantar("250203", "admin.250203");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(
                            comprobador.autoriza(
                                    "admin.250203", "contribuyentes", Privilegio.REGISTRO, HOY))
                    .as("el administrador inicial administra toda la municipalidad")
                    .isTrue();
            for (CatalogoDeOpciones.Opcion opcion : CatalogoDeOpciones.leer()) {
                for (Privilegio privilegio : EnumSet.allOf(Privilegio.class)) {
                    assertThat(
                                    comprobador.autoriza(
                                            "admin.250203", opcion.codigo(), privilegio, HOY))
                            .as("%s / %s", opcion.codigo(), privilegio)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("y NO recibe las cuatro opciones que se fueron a identidad: aqui no existen")
        void lasCuatroDeAdministracionNoExisten() {
            long municipalidad = implantar("250205", "admin.250205");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            for (String retirada : new String[] {"usuarios", "grupos", "miembros", "permisos"}) {
                assertThat(contar("SELECT count(*) FROM acceso WHERE codigo = '" + retirada + "'"))
                        .as(
                                "«%s» se administra en `identidad` (ADR-0039, etapa 4): sembrarla"
                                        + " aqui seria una fila de `acceso` sobre la que se pueden"
                                        + " otorgar permisos que no habilitan nada",
                                retirada)
                        .isZero();
                assertThat(comprobador.autoriza("admin.250205", retirada, Privilegio.LECTURA, HOY))
                        .isFalse();
            }
        }

        @Test
        @DisplayName("deja UN grupo, y el grupo «Seguridad» de antes de la etapa 4 ya no")
        void dejaUnSoloGrupo() {
            long municipalidad = implantar("250206", "admin.250206");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(contar("SELECT count(*) FROM grupo")).isEqualTo(1);
            assertThat(
                            contar(
                                    "SELECT count(*) FROM grupo WHERE nombre = '"
                                            + SembradorDeLaCopiaLocal.GRUPO_DE_ADMINISTRACION
                                            + "'"))
                    .isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM grupo WHERE nombre = 'Seguridad'"))
                    .as(
                            "un grupo plantilla sobre cuatro opciones que este sistema ya no sirve"
                                    + " seria una promesa vacia")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades implantadas")
    class Aislamiento {

        @Test
        @DisplayName("desde B, el administrador de A no existe")
        void desdeBElAdministradorDeANoExiste() {
            long a = implantar("250211", "admin.de.a");
            long b = implantar("250212", "admin.de.b");
            TenantContext.fijar(new MunicipalidadId(b));

            LecturaDeLaCopiaLocalJdbc copia = new LecturaDeLaCopiaLocalJdbc(jdbc);
            Optional<Usuario> deA =
                    transaccion.execute(estado -> copia.usuarioPorCuenta("admin.de.a"));
            assertThat(deA)
                    .as("dos municipalidades implantadas en la misma base no se ven entre si")
                    .isEmpty();
            assertThat(
                            comprobador.autoriza(
                                    "admin.de.a", "contribuyentes", Privilegio.MODIFICACION, HOY))
                    .as("y el administrador de una no autoriza en la otra")
                    .isFalse();
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("#122 — El regimen con que se implanta queda en la fila")
    class ElRegimen {

        @Test
        @DisplayName("implantada como demostracion, la fila lo dice")
        void implantadaComoDemostracionLaFilaLoDice() {
            implantacion("200501", "admin.demostracion", true).run(null);

            assertThat(esDemostracion("200501"))
                    .as("de ahi lo lee la capa de documentos para marcar todo lo que emita")
                    .isTrue();
        }

        @Test
        @DisplayName("por omision NO es de demostracion")
        void porOmisionNoEsDeDemostracion() {
            implantacion("200502", "admin.real").run(null);

            assertThat(esDemostracion("200502")).isFalse();
        }

        @Test
        @DisplayName("relanzar el despliegue no le quita la marca a una instalacion")
        void relanzarNoLeQuitaLaMarca() {
            implantacion("200503", "admin.marchablanca", true).run(null);
            implantacion("200503", "admin.marchablanca", false).run(null);

            assertThat(esDemostracion("200503"))
                    .as("la segunda implantacion pidio false, y la fila sigue marcada")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Idempotente: corre en cada despliegue")
    class Idempotente {

        @Test
        @DisplayName("la segunda ejecucion no duplica nada ni falla")
        void laSegundaEjecucionNoDuplicaNada() {
            long primera = implantar("250204", "admin.250204");
            long segunda = implantar("250204", "admin.250204");

            assertThat(segunda).as("la municipalidad es la misma fila").isEqualTo(primera);
            TenantContext.fijar(new MunicipalidadId(primera));

            assertThat(contar("SELECT count(*) FROM grupo"))
                    .as(
                            "un grupo de administracion duplicado deja permisos repartidos en dos sitios")
                    .isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM usuario WHERE cuenta = 'admin.250204'"))
                    .isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM miembro")).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM permiso"))
                    .as("un permiso por opcion del catalogo, y ni uno mas")
                    .isEqualTo(CatalogoDeOpciones.leer().size());
        }

        @Test
        @DisplayName("y no le quita al administrador lo que alguien le haya recortado despues")
        void noReponeLoRecortado() {
            long municipalidad = implantar("250207", "admin.250207");
            TenantContext.fijar(new MunicipalidadId(municipalidad));
            transaccion.executeWithoutResult(
                    estado ->
                            jdbc.sql(
                                            "UPDATE permiso SET registro = false WHERE acceso_id ="
                                                    + " (SELECT id FROM acceso WHERE codigo ="
                                                    + " 'contribuyentes')")
                                    .update());
            TenantContext.limpiar();

            implantar("250207", "admin.250207");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(
                            comprobador.autoriza(
                                    "admin.250207", "contribuyentes", Privilegio.REGISTRO, HOY))
                    .as(
                            "lo que ya existe se queda como esta: relanzar el despliegue no vuelve"
                                    + " a abrir lo que alguien cerro")
                    .isFalse();
        }
    }
}
