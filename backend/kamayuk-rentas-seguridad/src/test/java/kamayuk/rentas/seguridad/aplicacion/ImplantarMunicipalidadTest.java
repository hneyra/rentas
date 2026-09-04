package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.PermisoRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.RegistroDeMunicipalidadesJdbc;
import org.junit.jupiter.api.AfterAll;
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
 * La implantacion, contra PostgreSQL real y de punta a punta.
 *
 * <p>Lo que se verifica no es que las filas queden escritas —eso lo verifica cada caso de uso por
 * su cuenta— sino <b>que despues de correr esto haya alguien que pueda administrar el sistema</b>.
 * Es la unica pregunta que importa el dia de la implantacion, y la unica que no se puede responder
 * mirando tablas sueltas.
 *
 * <p>Se usa el {@link RegistroDeMunicipalidadesJdbc} de verdad, con las credenciales de {@code
 * sgtm_owner} de la base de prueba: el paso que necesita ese rol es justamente el que no tiene
 * sentido simular.
 */
@DisplayName("Implantacion de una municipalidad")
class ImplantarMunicipalidadTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDate HOY = LocalDate.of(2026, 8, 20);
    private static final Paginacion TODO = Paginacion.de(0, 500, "id");

    private static BaseDeDatosDePrueba base;
    private static JdbcClient jdbc;
    private static ComprobadorDeAcceso comprobador;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;
    private static AdministrarSeguridad administrar;
    private static AdministrarPermisos permisos;
    private static AdministracionRepositoryJdbc administracion;
    private static RegistroDeMunicipalidades registro;
    private static SembradorDeAccesos sembrador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        // El comprobador es @Transactional y hay que envolverlo: consulta tablas con
        // RLS, y sus politicas leen app.municipalidad_id, que solo existe dentro de una
        // transaccion. Sin envolver, PostgreSQL responde «unrecognized configuration
        // parameter» —el aislamiento funcionando— y la prueba se cae por el motivo
        // equivocado. Es el mismo descuido que tenia el guardia de acceso en produccion.
        comprobador = envolver(new ComprobadorDeAccesoJdbc(jdbc), gestor);
        administracion = new AdministracionRepositoryJdbc(jdbc);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        administrar = envolver(new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);
        permisos =
                envolver(
                        new AdministrarPermisos(
                                new PermisoRepositoryJdbc(jdbc), administracion, auditoria, RELOJ),
                        gestor);
        sembrador = envolver(new SembradorDeAccesos(jdbc, auditoria, RELOJ), gestor);

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
                sembrador,
                administrar,
                permisos,
                administracion,
                registro,
                gestor,
                new DatosDeImplantacion(
                        ubigeo,
                        "Municipalidad de prueba " + ubigeo,
                        "DISTRITAL",
                        administradorCuenta,
                        "Administrador de la implantacion",
                        esDemostracion,
                        "implantacion"));
    }

    /** Ejecuta la implantacion y devuelve el identificador de la municipalidad. */
    private static long implantar(String ubigeo, String administradorCuenta) {
        implantacion(ubigeo, administradorCuenta).run(null);
        return idDe(ubigeo);
    }

    /**
     * Cuenta los accesos de la municipalidad del contexto, dentro de una transaccion.
     *
     * <p>`acceso` es tabla de tenant: su politica lee app.municipalidad_id, y ese parametro lo fija
     * TenantTransactionManager al abrir la transaccion. Una lectura suelta no falla en vacio, falla
     * y punto, que es el comportamiento correcto (DAT-01 §0).
     */
    private static long accesosSembrados() {
        return transaccion.execute(
                estado -> jdbc.sql("SELECT count(*) FROM acceso").query(Long.class).single());
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
    @DisplayName("Deja el sistema administrable")
    class DejaElSistemaAdministrable {

        @Test
        @DisplayName("el administrador puede administrar permisos, que es de lo que depende todo")
        void elAdministradorPuedeAdministrar() {
            long municipalidad = implantar("250201", "admin.implantacion");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(
                            comprobador.autoriza(
                                    "admin.implantacion", "permisos", Privilegio.MODIFICACION, HOY))
                    .as(
                            "sin esto, el primer administrador no puede darle permisos a nadie —ni a"
                                    + " si mismo—, y de ahi solo se sale entrando por la base de datos")
                    .isTrue();

            TenantContext.limpiar();
        }

        @Test
        @DisplayName("quedan sembrados todos los accesos del catalogo, no solo los de seguridad")
        void quedanSembradosTodosLosAccesos() {
            long municipalidad = implantar("250202", "admin.250202");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            long enLaBase = accesosSembrados();

            assertThat(enLaBase)
                    .as(
                            "una opcion sin acceso sembrado es una opcion a la que nadie puede dar"
                                    + " permiso, y no se nota hasta que alguien la busca")
                    .isEqualTo(CatalogoDeOpciones.leer().size());

            TenantContext.limpiar();
        }

        @Test
        @DisplayName("el administrador recibe el catalogo entero, no solo el modulo de seguridad")
        void elAdministradorRecibeTodoElCatalogo() {
            long municipalidad = implantar("250203", "admin.250203");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            // Administra la seguridad...
            assertThat(comprobador.autoriza("admin.250203", "usuarios", Privilegio.REGISTRO, HOY))
                    .isTrue();
            // ...y tambien el padron, la caja y todo lo demas: administra la municipalidad
            // entera, no solo su seguridad (REQ-03 §3).
            assertThat(
                            comprobador.autoriza(
                                    "admin.250203", "contribuyentes", Privilegio.REGISTRO, HOY))
                    .as("el administrador inicial administra toda la municipalidad")
                    .isTrue();

            // Cada opcion del catalogo, con cada uno de los siete privilegios.
            for (CatalogoDeOpciones.Opcion opcion : CatalogoDeOpciones.leer()) {
                for (Privilegio privilegio : EnumSet.allOf(Privilegio.class)) {
                    assertThat(
                                    comprobador.autoriza(
                                            "admin.250203", opcion.codigo(), privilegio, HOY))
                            .as("%s / %s", opcion.codigo(), privilegio)
                            .isTrue();
                }
            }

            TenantContext.limpiar();
        }
    }

    @Nested
    @DisplayName("El grupo Seguridad delegado")
    class GrupoDeSeguridad {

        @Test
        @DisplayName("un miembro administra el acceso de los usuarios, y nada mas")
        void unMiembroSoloAdministraElAccesoDeLosUsuarios() {
            long municipalidad = implantar("250209", "admin.250209");
            TenantContext.fijar(new MunicipalidadId(municipalidad));
            kamayuk.rentas.auditoria.OrigenContext.fijar(
                    kamayuk.rentas.auditoria.Origen.deProceso("admin.250209"));

            long grupoSeguridad =
                    transaccion.execute(
                            estado ->
                                    administracion
                                            .grupoPorNombre(
                                                    ImplantarMunicipalidad.GRUPO_DE_SEGURIDAD)
                                            .orElseThrow()
                                            .id());

            Usuario operador =
                    administrar.registrarUsuario(
                            Usuario.nuevo("operador.accesos", "Operadora de accesos", null),
                            Observacion.de("prueba: miembro del grupo Seguridad"));
            administrar.afiliar(
                    grupoSeguridad,
                    operador.id(),
                    Observacion.de("prueba: miembro del grupo Seguridad"));

            // Administra el acceso: grupos, usuarios, permisos, miembros.
            assertThat(
                            comprobador.autoriza(
                                    "operador.accesos", "usuarios", Privilegio.MODIFICACION, HOY))
                    .isTrue();
            assertThat(
                            comprobador.autoriza(
                                    "operador.accesos", "permisos", Privilegio.MODIFICACION, HOY))
                    .isTrue();
            // Y nada mas: es el alcance que tuvo el administrador antes de recibir el catalogo.
            assertThat(
                            comprobador.autoriza(
                                    "operador.accesos", "contribuyentes", Privilegio.LECTURA, HOY))
                    .as("el grupo Seguridad solo administra el acceso de los usuarios")
                    .isFalse();

            kamayuk.rentas.auditoria.OrigenContext.limpiar();
            TenantContext.limpiar();
        }

        @Test
        @DisplayName("se crea sin miembros: es una plantilla")
        void seCreaSinMiembros() {
            long municipalidad = implantar("250210", "admin.250210");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            long miembros =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM miembro m"
                                                            + " JOIN grupo g ON g.id = m.grupo_id"
                                                            + " WHERE g.nombre = :n")
                                            .param("n", ImplantarMunicipalidad.GRUPO_DE_SEGURIDAD)
                                            .query(Long.class)
                                            .single());

            assertThat(miembros)
                    .as("la implantacion no mete a nadie en el grupo Seguridad")
                    .isZero();

            TenantContext.limpiar();
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
            // De los dos errores posibles, el valor por omision tiene que ser el que no se
            // pueda cometer callando: una instalacion real que se declarara de demostracion
            // emite papeles marcados de mas —molesto—; una de demostracion que se olvidara
            // de declararse emite papeles sin marca, que es lo que #122 impide.
            implantacion("200502", "admin.real").run(null);

            assertThat(esDemostracion("200502")).isFalse();
        }

        @Test
        @DisplayName("relanzar el despliegue no le quita la marca a una instalacion")
        void relanzarNoLeQuitaLaMarca() {
            // Quitar la marca tiene que ser deliberado y dejar rastro: un UPDATE de
            // sgtm_owner. Si un despliegue con la variable en false la quitara, bastaria
            // un descuido en un archivo de entorno para que la marcha blanca empezara a
            // emitir papeles indistinguibles de los de verdad.
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

            Pagina<Grupo> grupos = administrar.grupos(TODO);
            assertThat(
                            grupos.contenido().stream()
                                    .filter(
                                            g ->
                                                    ImplantarMunicipalidad.GRUPO_DE_ADMINISTRACION
                                                            .equals(g.nombre()))
                                    .count())
                    .as(
                            "un grupo de administracion duplicado deja permisos repartidos en dos sitios")
                    .isEqualTo(1);
            assertThat(
                            grupos.contenido().stream()
                                    .filter(
                                            g ->
                                                    ImplantarMunicipalidad.GRUPO_DE_SEGURIDAD
                                                            .equals(g.nombre()))
                                    .count())
                    .as("y el grupo Seguridad tampoco se duplica al relanzar")
                    .isEqualTo(1);

            Pagina<Usuario> usuarios = administrar.usuarios(TODO);
            assertThat(
                            usuarios.contenido().stream()
                                    .filter(u -> "admin.250204".equals(u.cuenta()))
                                    .count())
                    .isEqualTo(1);

            assertThat(accesosSembrados()).isEqualTo(CatalogoDeOpciones.leer().size());

            TenantContext.limpiar();
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades implantadas")
    class Aislamiento {

        @Test
        @DisplayName("desde B, el administrador de A no existe")
        void desdeBElAdministradorDeANoExiste() {
            long a = implantar("250205", "admin.de.a");
            long b = implantar("250206", "admin.de.b");

            TenantContext.fijar(new MunicipalidadId(b));

            Optional<Usuario> deA =
                    transaccion.execute(estado -> administracion.usuarioPorCuenta("admin.de.a"));
            assertThat(deA)
                    .as("dos municipalidades implantadas en la misma base no se ven entre si")
                    .isEmpty();
            assertThat(comprobador.autoriza("admin.de.a", "permisos", Privilegio.MODIFICACION, HOY))
                    .as("y el administrador de una no autoriza en la otra")
                    .isFalse();

            assertThat(a).isNotEqualTo(b);
            TenantContext.limpiar();
        }
    }

    @Nested
    @DisplayName("Lo que la implantacion deja protegido")
    class LoQueDejaProtegido {

        @Test
        @DisplayName("no se le puede quitar al grupo el permiso que sostiene la administracion")
        void noSeLePuedeQuitarElPermisoQueSostieneLaAdministracion() {
            long municipalidad = implantar("250207", "admin.250207");
            TenantContext.fijar(new MunicipalidadId(municipalidad));
            kamayuk.rentas.auditoria.OrigenContext.fijar(
                    kamayuk.rentas.auditoria.Origen.deProceso("admin.250207"));

            long grupoId =
                    transaccion.execute(
                            estado ->
                                    administracion
                                            .grupoPorNombre(
                                                    ImplantarMunicipalidad.GRUPO_DE_ADMINISTRACION)
                                            .orElseThrow()
                                            .id());

            // Es la comprobacion que da valor a todo lo anterior: si el unico grupo que
            // puede administrar permisos se quedara sin ellos, la municipalidad tendria
            // que arreglarse entrando por la base de datos.
            assertThatThrownBy(
                            () ->
                                    permisos.fijarParaGrupo(
                                            grupoId,
                                            "permisos",
                                            Set.of(),
                                            Observacion.de(
                                                    "Intento de dejar la municipalidad sin quien"
                                                            + " administre")))
                    .as(
                            "la implantacion deja UN grupo administrador: quitarle esto lo deja en cero")
                    .isInstanceOf(RuntimeException.class);

            assertThat(
                            comprobador.autoriza(
                                    "admin.250207", "permisos", Privilegio.MODIFICACION, HOY))
                    .as("y despues del intento fallido sigue pudiendo administrar")
                    .isTrue();

            kamayuk.rentas.auditoria.OrigenContext.limpiar();
            TenantContext.limpiar();
        }

        @Test
        @DisplayName("los siete privilegios quedan puestos, no solo la lectura")
        void losSietePrivilegiosQuedanPuestos() {
            long municipalidad = implantar("250208", "admin.250208");
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            for (Privilegio privilegio : EnumSet.allOf(Privilegio.class)) {
                assertThat(comprobador.autoriza("admin.250208", "grupos", privilegio, HOY))
                        .as("privilegio %s sobre la pantalla de grupos", privilegio)
                        .isTrue();
            }

            TenantContext.limpiar();
        }
    }
}
