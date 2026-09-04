package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * RF-120 y RF-123 contra PostgreSQL real: modulos, accesos, grupos, usuarios y miembros.
 *
 * <p>Se prueba con dos municipalidades sembradas porque el criterio que mas importa aqui no es
 * funcional sino de aislamiento: {@code seguridad} es el modulo del que dependen los demas, y una
 * fuga aqui es una fuga en todo el sistema.
 */
@DisplayName("RF-120 — Administracion de la seguridad")
class AdministrarSeguridadTest {

    private static final LocalDate HOY = LocalDate.of(2026, 8, 18);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final Paginacion TODO = Paginacion.de(0, 500, "id");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    private static TransactionTemplate transaccion;
    private static AdministrarSeguridad administrar;
    private static SembradorDeAccesos sembrador;
    private static ComprobadorDeAcceso comprobador;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250101", "Seguridad A");
        municipalidadB = crearMunicipalidad("250102", "Seguridad B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        comprobador = new ComprobadorDeAccesoJdbc(jdbc);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        administrar =
                envolver(
                        new AdministrarSeguridad(
                                new AdministracionRepositoryJdbc(jdbc), auditoria, RELOJ),
                        gestor);
        sembrador = envolver(new SembradorDeAccesos(jdbc, auditoria, RELOJ), gestor);

        // Cada municipalidad recibe su propia siembra: los accesos son datos de
        // tenant, no un catalogo global.
        sembrarEn(municipalidadA);
        sembrarEn(municipalidadB);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static void sembrarEn(long municipalidad) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(Origen.deProceso("despliegue"));
        sembrador.sembrar(Observacion.de("Siembra inicial de accesos de la municipalidad"));
        TenantContext.limpiar();
        OrigenContext.limpiar();
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
        OrigenContext.fijar(new Origen("admin.a", "PC-TI-01", "10.1.1.1"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Aislamiento: seguridad es el modulo del que dependen los demas")
    class Aislamiento {

        @Test
        @DisplayName("un usuario de A no aparece en ninguna consulta con contexto de B")
        void unUsuarioDeANoApareceEnB() {
            administrar.registrarUsuario(
                    Usuario.nuevo("solo.de.a", "Empleado de A", null),
                    Observacion.de("Alta de empleado de la municipalidad A"));

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            Pagina<Usuario> desdeB = administrar.usuarios(TODO);

            assertThat(desdeB.contenido())
                    .as("no es que se filtre: desde B esa fila no existe")
                    .noneSatisfy(u -> assertThat(u.cuenta()).isEqualTo("solo.de.a"));
        }

        @Test
        @DisplayName("cada municipalidad tiene sus propios accesos, con los mismos codigos")
        void cadaMunicipalidadTieneSusPropiosAccesos() {
            assertThat(administrar.accesos(TODO).totalElementos()).isEqualTo(134);

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            assertThat(administrar.accesos(TODO).totalElementos())
                    .as("los accesos son datos de tenant: cada una configura los suyos")
                    .isEqualTo(134);
        }
    }

    @Nested
    @DisplayName("La siembra coincide exactamente con el catalogo")
    class Siembra {

        @Test
        @DisplayName("los 134 codigos sembrados son los 134 del catalogo, ni uno mas ni uno menos")
        void losCodigosCoincidenConElCatalogo() {
            List<String> sembrados =
                    administrar.accesos(TODO).contenido().stream()
                            .map(a -> a.codigo())
                            .sorted()
                            .toList();
            List<String> delCatalogo =
                    kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones.leer().stream()
                            .map(o -> o.codigo())
                            .sorted()
                            .toList();

            // Si divergieran, habria opciones sin acceso configurable —una pantalla a
            // la que nadie puede dar permiso— o accesos huerfanos, que se configuran y
            // no gobiernan nada.
            assertThat(sembrados).isEqualTo(delCatalogo);
        }

        @Test
        @DisplayName("los doce modulos del manual, con su nombre")
        void losDoceModulos() {
            assertThat(administrar.modulos(TODO).totalElementos()).isEqualTo(12);
            assertThat(administrar.modulos(TODO).contenido())
                    .anySatisfy(
                            m -> {
                                assertThat(m.codigo()).isEqualTo("CATASTRO");
                                assertThat(m.nombre()).isEqualTo("Catastro");
                            });
        }
    }

    @Nested
    @DisplayName("Alta, baja y vigencia, con auditoria")
    class AltaBajaYVigencia {

        @Test
        @DisplayName("toda alta, baja y modificacion deja auditoria con su observacion")
        void todaEscrituraDejaAuditoria() throws SQLException {
            Grupo grupo =
                    administrar.registrarGrupo(
                            Grupo.nuevo("Auditables", "Grupo para verificar la pista"),
                            Observacion.de("Alta del grupo segun memorando 2026-77"));

            administrar.inhabilitarGrupo(
                    grupo.id(), Observacion.de("Suspension temporal por reorganizacion"));
            administrar.habilitarGrupo(
                    grupo.id(), Observacion.de("Se reactiva terminada la reorganizacion"));

            assertThat(
                            filas(
                                    "SELECT operacion FROM auditoria WHERE tabla = 'grupo'"
                                            + " AND clave = '"
                                            + grupo.id()
                                            + "' ORDER BY id"))
                    .containsExactly("ALTA", "BAJA", "MODIFICACION");
            assertThat(
                            filas(
                                    "SELECT observacion FROM auditoria WHERE tabla = 'grupo'"
                                            + " AND clave = '"
                                            + grupo.id()
                                            + "' ORDER BY id"))
                    .allSatisfy(o -> assertThat(o).isNotBlank());
        }

        @Test
        @DisplayName("inhabilitar un grupo retira el acceso de sus miembros sin borrar la relacion")
        void inhabilitarUnGrupoRetiraElAccesoSinBorrar() throws SQLException {
            Grupo grupo =
                    administrar.registrarGrupo(
                            Grupo.nuevo("Con miembros", null),
                            Observacion.de("Alta de grupo con miembros para la prueba"));
            Usuario usuario =
                    administrar.registrarUsuario(
                            Usuario.nuevo("miembro.uno", "Miembro Uno", null),
                            Observacion.de("Alta de usuario para la prueba de miembros"));
            administrar.afiliar(
                    grupo.id(), usuario.id(), Observacion.de("Se incorpora a Mesa de Partes"));
            otorgarAGrupo(grupo.id(), "calles", Privilegio.LECTURA);

            assertThat(autoriza("miembro.uno", "calles", Privilegio.LECTURA)).isTrue();

            administrar.inhabilitarGrupo(
                    grupo.id(),
                    Observacion.de("Se suspende el grupo entero por auditoria interna"));

            assertThat(autoriza("miembro.uno", "calles", Privilegio.LECTURA))
                    .as("un grupo inhabilitado retira el acceso de todos sus miembros")
                    .isFalse();
            assertThat(
                            contar(
                                    "SELECT count(*) FROM miembro WHERE grupo_id = "
                                            + grupo.id()
                                            + " AND usuario_id = "
                                            + usuario.id()
                                            + " AND activo"))
                    .as("y la relacion sigue ahi, activa: no se borro nada (regla 4)")
                    .isEqualTo(1);

            administrar.habilitarGrupo(
                    grupo.id(), Observacion.de("Terminada la auditoria interna se reactiva"));
            assertThat(autoriza("miembro.uno", "calles", Privilegio.LECTURA))
                    .as("volver a habilitarlo devuelve el acceso a los mismos")
                    .isTrue();
        }

        @Test
        @DisplayName("desafiliar da de baja la fila, no la borra")
        void desafiliarDaDeBajaNoBorra() throws SQLException {
            Grupo grupo =
                    administrar.registrarGrupo(
                            Grupo.nuevo("Del que se sale", null),
                            Observacion.de("Alta de grupo para la prueba de baja de miembro"));
            Usuario usuario =
                    administrar.registrarUsuario(
                            Usuario.nuevo("se.va", "Quien se va", null),
                            Observacion.de("Alta de usuario que despues sale del grupo"));

            administrar.afiliar(grupo.id(), usuario.id(), Observacion.de("Entra al grupo"));
            administrar.desafiliar(
                    grupo.id(), usuario.id(), Observacion.de("Sale del grupo por cambio de area"));

            assertThat(
                            contar(
                                    "SELECT count(*) FROM miembro WHERE grupo_id = "
                                            + grupo.id()
                                            + " AND usuario_id = "
                                            + usuario.id()))
                    .as("la fila dice que entre tal dia y tal otro esa persona pudo hacer aquello")
                    .isEqualTo(1);
            assertThat(
                            contar(
                                    "SELECT count(*) FROM miembro WHERE grupo_id = "
                                            + grupo.id()
                                            + " AND usuario_id = "
                                            + usuario.id()
                                            + " AND NOT activo AND fecha_baja IS NOT NULL"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("RF-123: la vigencia limita la autorizacion sin tocar el permiso")
        void laVigenciaLimitaLaAutorizacion() {
            Usuario usuario =
                    administrar.registrarUsuario(
                            Usuario.nuevo("por.contrato", "Personal por contrato", null),
                            Observacion.de("Alta de personal por contrato hasta fin de anio"));
            otorgarAUsuario(usuario.id(), "calles", Privilegio.LECTURA);

            assertThat(autoriza("por.contrato", "calles", Privilegio.LECTURA)).isTrue();

            administrar.fijarVigenciaDeUsuario(
                    usuario.id(),
                    new Vigencia(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
                    Observacion.de("Se registra el fin del contrato segun resolucion 2026-90"));

            assertThat(autoriza("por.contrato", "calles", Privilegio.LECTURA))
                    .as("caduca sola el dia que termina el contrato, sin retirar el permiso")
                    .isFalse();
        }

        @Test
        @DisplayName("un grupo o usuario inexistente da 404, no un error del motor")
        void unGrupoInexistenteDa404() {
            assertThatThrownBy(
                            () ->
                                    administrar.inhabilitarGrupo(
                                            999_999L, Observacion.de("No deberia encontrarlo")))
                    .isInstanceOf(kamayuk.rentas.web.ProblemaDeNegocio.class)
                    .hasMessageContaining("999999");
        }
    }

    // ------------------------------------------------------------------

    private static boolean autoriza(String usuario, String acceso, Privilegio privilegio) {
        return Boolean.TRUE.equals(
                transaccion.execute(
                        estado -> comprobador.autoriza(usuario, acceso, privilegio, HOY)));
    }

    private static void otorgarAGrupo(Long grupo, String acceso, Privilegio privilegio) {
        otorgar("grupo_id", grupo, acceso, privilegio);
    }

    private static void otorgarAUsuario(Long usuario, String acceso, Privilegio privilegio) {
        otorgar("usuario_id", usuario, acceso, privilegio);
    }

    private static void otorgar(String columna, Long sujeto, String acceso, Privilegio privilegio) {
        transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "INSERT INTO permiso (municipalidad_id, acceso_id, "
                                                + columna
                                                + ", "
                                                + privilegio.columna()
                                                + ", usuario_registro) SELECT"
                                                + " current_setting('app.municipalidad_id')::bigint,"
                                                + " a.id, :sujeto, true, 'prueba' FROM acceso a"
                                                + " WHERE a.codigo = :acceso")
                                .param("sujeto", sujeto)
                                .param("acceso", acceso)
                                .update());
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
        return Long.parseLong(filas(sql).get(0));
    }

    private static List<String> filas(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            List<String> valores = new java.util.ArrayList<>();
            while (resultado.next()) {
                valores.add(resultado.getString(1));
            }
            return valores;
        }
    }
}
