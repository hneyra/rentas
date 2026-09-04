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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.PermisoRepositoryJdbc;
import kamayuk.rentas.web.ProblemaDeNegocio;
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
 * RF-121: otorgar y retirar los siete privilegios, y las dos reglas que no son obvias —la
 * precedencia entre grupo y usuario, y el ultimo administrador—.
 *
 * <p>Cada asercion mira el <b>resultado del guardia</b>, no la fila de la tabla. Comprobar que la
 * columna quedo en {@code true} verificaria que el {@code UPDATE} funciona; lo que hay que
 * verificar es que el cambio se traduce en que esa persona puede o no puede hacer aquello, que es
 * lo unico que le importa a quien administra.
 */
@DisplayName("RF-121 — Permisos y niveles de accesibilidad")
class AdministrarPermisosTest {

    private static final LocalDate HOY = LocalDate.of(2026, 8, 18);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    /**
     * Las pruebas del ultimo administrador viven en su propia municipalidad.
     *
     * <p>No es capricho: comprueban que <b>no queda nadie</b>, y cualquier administrador que otra
     * prueba haya creado antes las haria pasar en verde sin haber verificado nada. JUnit no
     * garantiza el orden entre clases anidadas, asi que aislarlas es la unica forma de que la
     * asercion signifique siempre lo mismo.
     */
    private static long municipalidadSolitaria;

    private static TransactionTemplate transaccion;
    private static AdministrarPermisos permisos;
    private static AdministrarSeguridad administrar;
    private static ComprobadorDeAcceso comprobador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad de permisos");
        municipalidadSolitaria = crearMunicipalidad("260102", "Municipalidad solitaria");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        comprobador = new ComprobadorDeAccesoJdbc(jdbc);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AdministracionRepositoryJdbc administracion = new AdministracionRepositoryJdbc(jdbc);
        permisos =
                envolver(
                        new AdministrarPermisos(
                                new PermisoRepositoryJdbc(jdbc), administracion, auditoria, RELOJ),
                        gestor);
        administrar = envolver(new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);

        SembradorDeAccesos sembrador =
                envolver(new SembradorDeAccesos(jdbc, auditoria, RELOJ), gestor);
        for (long cual : new long[] {municipalidad, municipalidadSolitaria}) {
            TenantContext.fijar(new MunicipalidadId(cual));
            OrigenContext.fijar(Origen.deProceso("despliegue"));
            sembrador.sembrar(Observacion.de("Siembra inicial de accesos de la municipalidad"));
            TenantContext.limpiar();
            OrigenContext.limpiar();
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

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("admin.seguridad", "PC-TI-01", "10.9.9.9"));
        asegurarQueHayAdministrador();
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Otorgar y retirar cambia lo que el guardia responde")
    class OtorgarYRetirar {

        @Test
        @DisplayName("los siete privilegios, uno a uno: se otorgan y se retiran")
        void losSietePrivilegiosUnoAUno() {
            Grupo grupo = grupo("Siete privilegios");
            Usuario usuario = usuario("prueba.siete");
            administrar.afiliar(grupo.id(), usuario.id(), observacion("Entra al grupo de prueba"));

            for (Privilegio privilegio : Privilegio.values()) {
                permisos.fijarParaGrupo(
                        grupo.id(),
                        "calles",
                        EnumSet.of(privilegio),
                        observacion("Se otorga " + privilegio + " sobre el catalogo vial"));

                assertThat(autoriza("prueba.siete", "calles", privilegio))
                        .as("otorgado %s", privilegio)
                        .isTrue();
                for (Privilegio otro : Privilegio.values()) {
                    if (otro != privilegio) {
                        assertThat(autoriza("prueba.siete", "calles", otro))
                                .as("y solo %s: %s sigue sin otorgarse", privilegio, otro)
                                .isFalse();
                    }
                }
            }

            permisos.fijarParaGrupo(
                    grupo.id(),
                    "calles",
                    Set.of(),
                    observacion("Se retiran todos los privilegios del grupo"));

            assertThat(autoriza("prueba.siete", "calles", Privilegio.LECTURA))
                    .as("guardar sin ninguna casilla marcada retira todo, no deja lo anterior")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("La precedencia entre el grupo y la excepcion del usuario")
    class Precedencia {

        @Test
        @DisplayName("el usuario amplia: puede lo que su grupo no le da")
        void elUsuarioAmplia() {
            Grupo grupo = grupo("Solo lectura");
            Usuario usuario = usuario("amplia");
            administrar.afiliar(grupo.id(), usuario.id(), observacion("Entra al grupo"));
            permisos.fijarParaGrupo(
                    grupo.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA),
                    observacion("El grupo solo consulta el catalogo vial"));

            assertThat(autoriza("amplia", "calles", Privilegio.IMPRESION)).isFalse();

            permisos.fijarParaUsuario(
                    usuario.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA, Privilegio.IMPRESION),
                    observacion("Se le habilita imprimir por encargo de la gerencia"));

            assertThat(autoriza("amplia", "calles", Privilegio.IMPRESION)).isTrue();
            assertThat(autoriza("amplia", "calles", Privilegio.LECTURA))
                    .as(
                            "la excepcion sustituye al grupo, asi que tiene que repetir lo que conserva")
                    .isTrue();
        }

        @Test
        @DisplayName("el usuario restringe: no puede lo que su grupo si le da")
        void elUsuarioRestringe() {
            // Este es el caso que obliga a que la excepcion sustituya y no se sume.
            // Con una union pura no se puede expresar, y la unica salida seria sacar a
            // la persona del grupo y repetirle veinte permisos a mano.
            Grupo grupo = grupo("Mesa de Partes plena");
            Usuario usuario = usuario("restringe");
            administrar.afiliar(grupo.id(), usuario.id(), observacion("Entra al grupo"));
            permisos.fijarParaGrupo(
                    grupo.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA, Privilegio.REGISTRO, Privilegio.ELIMINACION),
                    observacion("El grupo administra el catalogo vial"));

            assertThat(autoriza("restringe", "calles", Privilegio.ELIMINACION)).isTrue();

            permisos.fijarParaUsuario(
                    usuario.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA, Privilegio.REGISTRO),
                    observacion("Se le retira dar de baja vias, por acuerdo de gerencia"));

            assertThat(autoriza("restringe", "calles", Privilegio.ELIMINACION))
                    .as("la excepcion del usuario manda sobre lo que el grupo le daba")
                    .isFalse();
            assertThat(autoriza("restringe", "calles", Privilegio.REGISTRO)).isTrue();
        }

        @Test
        @DisplayName("sin excepcion, mandan los grupos, y se suman entre si")
        void sinExcepcionMandanLosGrupos() {
            Grupo consulta = grupo("Consulta vial");
            Grupo impresion = grupo("Impresion vial");
            Usuario usuario = usuario("dos.grupos");
            administrar.afiliar(consulta.id(), usuario.id(), observacion("Entra al primer grupo"));
            administrar.afiliar(
                    impresion.id(), usuario.id(), observacion("Entra al segundo grupo"));

            permisos.fijarParaGrupo(
                    consulta.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA),
                    observacion("El primer grupo consulta"));
            permisos.fijarParaGrupo(
                    impresion.id(),
                    "calles",
                    EnumSet.of(Privilegio.IMPRESION),
                    observacion("El segundo grupo imprime"));

            assertThat(autoriza("dos.grupos", "calles", Privilegio.LECTURA)).isTrue();
            assertThat(autoriza("dos.grupos", "calles", Privilegio.IMPRESION))
                    .as("pertenecer a dos grupos da lo de los dos")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Nadie puede dejar el sistema sin administrador")
    class UltimoAdministrador {

        private long grupo;

        @BeforeEach
        void enSuPropiaMunicipalidad() throws SQLException {
            TenantContext.fijar(new MunicipalidadId(municipalidadSolitaria));
            // Fixture, no caso de uso: se retiran las excepciones de usuario que otra
            // prueba de esta clase haya podido dejar, para que «el ultimo» sea de
            // verdad el ultimo. Va por la conexion de superusuario a proposito, para
            // que quede claro que es preparacion y no algo que el sistema permita.
            ejecutarComoAdmin(
                    "UPDATE permiso SET registro = false, lectura = false"
                            + " WHERE municipalidad_id = "
                            + municipalidadSolitaria
                            + " AND usuario_id IS NOT NULL");
            if (grupoSolitario == 0) {
                Grupo administradores = grupo("Unicos administradores");
                Usuario admin = usuario("admin.solitario");
                administrar.afiliar(
                        administradores.id(),
                        admin.id(),
                        observacion("Alta del unico administrador"));
                grupoSolitario = administradores.id();
            }
            grupo = grupoSolitario;
            permisos.fijarParaGrupo(
                    grupo,
                    "permisos",
                    EnumSet.of(Privilegio.REGISTRO, Privilegio.LECTURA),
                    observacion("Se restituye el unico administrador antes de cada prueba"));
        }

        @Test
        @DisplayName("retirar el ultimo permiso de administracion se rechaza, y no deja rastro")
        void retirarElUltimoSeRechaza() throws SQLException {
            long permisosAntes =
                    contar(
                            "SELECT count(*) FROM permiso WHERE municipalidad_id = "
                                    + municipalidadSolitaria);
            long auditoriaAntes =
                    contar(
                            "SELECT count(*) FROM auditoria WHERE tabla = 'permiso'"
                                    + " AND municipalidad_id = "
                                    + municipalidadSolitaria);

            assertThatThrownBy(
                            () ->
                                    permisos.fijarParaGrupo(
                                            grupo,
                                            "permisos",
                                            Set.of(),
                                            observacion("Se retira el permiso de administracion")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("sin ningun usuario capaz de administrar permisos");

            // La comprobacion ocurre despues de escribir, dentro de la misma
            // transaccion: si no se deshiciera, el sistema quedaria sin administrador
            // y ademas con una fila de auditoria diciendo que fue a proposito.
            assertThat(
                            contar(
                                    "SELECT count(*) FROM auditoria WHERE tabla = 'permiso'"
                                            + " AND municipalidad_id = "
                                            + municipalidadSolitaria))
                    .as("la auditoria del intento se deshace con el intento")
                    .isEqualTo(auditoriaAntes);
            assertThat(
                            contar(
                                    "SELECT count(*) FROM permiso WHERE municipalidad_id = "
                                            + municipalidadSolitaria))
                    .isEqualTo(permisosAntes);
            assertThat(autoriza("admin.solitario", "permisos", Privilegio.REGISTRO))
                    .as("y quien administraba sigue pudiendo hacerlo")
                    .isTrue();
        }

        @Test
        @DisplayName("con otro administrador de por medio, retirarlo si se admite")
        void conOtroAdministradorSeAdmite() {
            Usuario relevo = usuario("relevo");
            permisos.fijarParaUsuario(
                    relevo.id(),
                    "permisos",
                    EnumSet.of(Privilegio.REGISTRO),
                    observacion("Se nombra un segundo administrador antes del relevo"));

            permisos.fijarParaGrupo(
                    grupo,
                    "permisos",
                    Set.of(),
                    observacion("Se retira el permiso del grupo, ya hay relevo"));

            assertThat(autoriza("relevo", "permisos", Privilegio.REGISTRO)).isTrue();
            assertThat(autoriza("admin.solitario", "permisos", Privilegio.REGISTRO)).isFalse();
        }
    }

    @Nested
    @DisplayName("Leer los permisos de un grupo, para cargar la matriz antes de guardarla")
    class LecturaDeLaMatriz {

        @Test
        @DisplayName("trae solo lo configurado, con el codigo del acceso ya resuelto")
        void traeSoloLoConfigurado() {
            Grupo grupo = grupo("Lectura de matriz");
            permisos.fijarParaGrupo(
                    grupo.id(),
                    "calles",
                    EnumSet.of(Privilegio.LECTURA, Privilegio.IMPRESION),
                    observacion("Se otorga el catalogo vial para la prueba de lectura"));
            permisos.fijarParaGrupo(
                    grupo.id(),
                    "sectores",
                    EnumSet.of(Privilegio.REGISTRO),
                    observacion("Se otorga sectores para la prueba de lectura"));

            List<AdministrarPermisos.PermisoDeAcceso> leidos = permisos.deGrupo(grupo.id());

            assertThat(leidos)
                    .as("no trae las 134 opciones del catalogo, solo las dos configuradas")
                    .hasSize(2);
            assertThat(leidos)
                    .filteredOn(p -> p.codigoDeAcceso().equals("calles"))
                    .singleElement()
                    .satisfies(
                            p -> {
                                assertThat(p.privilegios())
                                        .containsExactlyInAnyOrder(
                                                Privilegio.LECTURA, Privilegio.IMPRESION);
                                assertThat(p.grupoId()).isEqualTo(grupo.id());
                                assertThat(p.usuarioId()).isNull();
                            });
            assertThat(leidos)
                    .filteredOn(p -> p.codigoDeAcceso().equals("sectores"))
                    .singleElement()
                    .satisfies(
                            p -> assertThat(p.privilegios()).containsExactly(Privilegio.REGISTRO));
        }

        @Test
        @DisplayName("un grupo sin ningun permiso configurado devuelve la lista vacia")
        void unGrupoSinPermisosDevuelveVacio() {
            Grupo grupo = grupo("Sin permisos todavia");

            assertThat(permisos.deGrupo(grupo.id())).isEmpty();
        }

        @Test
        @DisplayName("un grupo que no existe se rechaza, igual que al guardar")
        void unGrupoInexistenteSeRechaza() {
            assertThatThrownBy(() -> permisos.deGrupo(999_999L))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("999999");
        }
    }

    @Nested
    @DisplayName("Auditoria de la configuracion (ADR-0008 §5)")
    class AuditoriaDeLaConfiguracion {

        @Test
        @DisplayName("todo cambio de permisos deja su fila, con el acceso y los privilegios")
        void todoCambioDejaSuFila() throws SQLException {
            Grupo grupo = grupo("Auditado");
            permisos.fijarParaGrupo(
                    grupo.id(),
                    "sectores",
                    EnumSet.of(Privilegio.LECTURA, Privilegio.IMPRESION),
                    observacion("Alta de permisos sobre sectores, memorando 2026-91"));

            List<String> datos =
                    filas(
                            "SELECT datos_nuevos::text FROM auditoria"
                                    + " WHERE tabla = 'permiso' ORDER BY id DESC LIMIT 1");

            assertThat(datos).hasSize(1);
            assertThat(datos.get(0))
                    .contains("sectores")
                    .contains("LECTURA")
                    .contains("IMPRESION")
                    .doesNotContain("ESPECIAL");
        }
    }

    @Nested
    @DisplayName("RF-122 — Una opcion nueva del catalogo aparece sin desplegar codigo")
    class OpcionNueva {

        @Test
        @DisplayName("se puede configurar cualquiera de las 134 opciones, sin tocar nada")
        void sePuedeConfigurarCualquiera() {
            // El acceso no se declara en ningun sitio del codigo: viene del catalogo.
            // Que este se pueda otorgar es la prueba de que agregar una opcion al
            // catalogo la hace configurable sin desplegar nada.
            Grupo grupo = grupo("Configurable");
            Usuario usuario = usuario("configura");
            administrar.afiliar(grupo.id(), usuario.id(), observacion("Entra al grupo"));

            permisos.fijarParaGrupo(
                    grupo.id(),
                    "ficha_urbana",
                    EnumSet.of(Privilegio.REGISTRO),
                    observacion("Se habilita registrar la ficha catastral urbana"));

            assertThat(autoriza("configura", "ficha_urbana", Privilegio.REGISTRO)).isTrue();
        }

        @Test
        @DisplayName("un acceso que no esta en el catalogo se rechaza, y lo dice")
        void unAccesoInexistenteSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    permisos.fijarParaGrupo(
                                            grupoAdministradores,
                                            "pantalla_que_no_existe",
                                            EnumSet.of(Privilegio.LECTURA),
                                            observacion("No deberia encontrar el acceso")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("pantalla_que_no_existe");
        }
    }

    // ------------------------------------------------------------------

    private static long grupoAdministradores = 0;
    private static long grupoSolitario = 0;

    /**
     * Deja siempre un administrador en pie antes de cada prueba.
     *
     * <p>Sin esto, la primera prueba que retire un permiso dejaria a las siguientes sin poder
     * escribir ninguno, y el fallo aparecerria en la prueba equivocada.
     */
    private void asegurarQueHayAdministrador() {
        if (grupoAdministradores == 0) {
            Grupo administradores = grupo("Administradores");
            Usuario admin = usuario("admin.seguridad");
            administrar.afiliar(
                    administradores.id(),
                    admin.id(),
                    observacion("Alta del administrador inicial"));
            grupoAdministradores = administradores.id();
        }
        permisos.fijarParaGrupo(
                grupoAdministradores,
                "permisos",
                EnumSet.of(Privilegio.REGISTRO, Privilegio.LECTURA),
                observacion("Se asegura que la municipalidad conserva un administrador"));
    }

    private static Grupo grupo(String nombre) {
        return administrar.registrarGrupo(
                Grupo.nuevo(nombre, null), observacion("Alta del grupo " + nombre));
    }

    private static Usuario usuario(String cuenta) {
        return administrar.registrarUsuario(
                Usuario.nuevo(cuenta, "Usuario " + cuenta, null),
                observacion("Alta del usuario " + cuenta));
    }

    private static Observacion observacion(String texto) {
        return Observacion.de(texto);
    }

    private static boolean autoriza(String usuario, String acceso, Privilegio privilegio) {
        return Boolean.TRUE.equals(
                transaccion.execute(
                        estado -> comprobador.autoriza(usuario, acceso, privilegio, HOY)));
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

    private static void ejecutarComoAdmin(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql)) {
            sentencia.executeUpdate();
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
