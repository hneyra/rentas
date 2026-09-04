package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
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
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.PermisoRepositoryJdbc;
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
 * RF-121 a RF-123, contra PostgreSQL real: la siembra de accesos y la comprobacion de privilegios.
 *
 * <p>Se prueban juntos porque van juntos: sin siembra no hay acceso sobre el que otorgar un
 * permiso, y sin permiso la siembra no sirve de nada. Lo que se verifica es la cadena completa
 * —catalogo → acceso → permiso → grupo → miembro → usuario— con la vigencia en cada eslabon.
 */
@DisplayName("RF-121/123 — Autorizacion en el servidor")
class AutorizacionTest {

    private static final LocalDate HOY = LocalDate.of(2026, 8, 18);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static SembradorDeAccesos sembrador;
    private static ComprobadorDeAcceso comprobador;
    private static PermisoRepositoryJdbc permisos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240101", "Municipalidad de autorizacion");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        comprobador = new ComprobadorDeAccesoJdbc(jdbc);
        permisos = new PermisoRepositoryJdbc(jdbc);

        SembradorDeAccesos objetivo =
                new SembradorDeAccesos(jdbc, new AuditoriaJdbc(jdbc, RELOJ), RELOJ);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        sembrador = (SembradorDeAccesos) fabrica.getProxy();
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
        OrigenContext.fijar(Origen.deProceso("despliegue"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("RF-122 — La siembra reconoce el catalogo sin intervencion manual")
    class Siembra {

        @Test
        @DisplayName("siembra las 134 opciones, es idempotente y deja una sola fila de auditoria")
        void siembraEsIdempotenteYSeAudita() throws SQLException {
            // Las aserciones no dependen de que esta prueba corra la primera: JUnit no
            // garantiza el orden entre clases anidadas, y una prueba que solo pasa si
            // va primero es una prueba que se rompera al agregar otra.
            sembrador.sembrar(Observacion.de("Siembra inicial de accesos, RF-122"));

            assertThat(contar("SELECT count(*) FROM acceso")).isEqualTo(134);
            assertThat(contar("SELECT count(*) FROM modulo_sistema")).isEqualTo(12);

            int repetida = sembrador.sembrar(Observacion.de("Segundo despliegue, sin cambios"));
            assertThat(repetida)
                    .as("se ejecuta en cada despliegue: lo que ya existe se queda como esta")
                    .isZero();
            assertThat(contar("SELECT count(*) FROM acceso")).isEqualTo(134);

            assertThat(contar("SELECT count(*) FROM auditoria WHERE tabla = 'acceso'"))
                    .as("una fila por corrida que crea algo, no 134 filas identicas")
                    .isEqualTo(1);
            assertThat(
                            textoUnico(
                                    "SELECT datos_nuevos::text FROM auditoria WHERE tabla = 'acceso'"))
                    .as("y esa fila dice cuantos accesos creo la corrida que si creo algo")
                    .contains("\"accesosCreados\": 134");
        }
    }

    @Nested
    @DisplayName("RF-121/123 — Los siete privilegios, con su vigencia")
    class Comprobacion {

        @Test
        @DisplayName("un usuario sin el privilegio no autoriza, aunque la opcion exista")
        void sinPrivilegioNoAutoriza() throws SQLException {
            sembrar();
            long usuario = crearUsuario("sin.permisos", null, null);

            assertThat(autoriza("sin.permisos", "calles", Privilegio.LECTURA))
                    .as("la opcion existe y el usuario tambien; lo que falta es el permiso")
                    .isFalse();
            assertThat(usuario).isPositive();
        }

        @Test
        @DisplayName("el privilegio otorgado autoriza, y solo ese")
        void elPrivilegioOtorgadoAutorizaYSoloEse() throws SQLException {
            sembrar();
            long usuario = crearUsuario("solo.lectura", null, null);
            otorgarAUsuario(usuario, "calles", Privilegio.LECTURA);

            assertThat(autoriza("solo.lectura", "calles", Privilegio.LECTURA)).isTrue();
            assertThat(autoriza("solo.lectura", "calles", Privilegio.REGISTRO))
                    .as("los siete son independientes: leer no es registrar")
                    .isFalse();
            assertThat(autoriza("solo.lectura", "sectores", Privilegio.LECTURA))
                    .as("y el permiso es sobre una opcion, no sobre el modulo entero")
                    .isFalse();
        }

        @Test
        @DisplayName("el permiso del grupo llega al miembro")
        void elPermisoDelGrupoLlegaAlMiembro() throws SQLException {
            sembrar();
            long usuario = crearUsuario("del.grupo", null, null);
            long grupo = crearGrupo("Mesa de Partes", null, null);
            afiliar(grupo, usuario, true);
            otorgarAGrupo(grupo, "calles", Privilegio.IMPRESION);

            assertThat(autoriza("del.grupo", "calles", Privilegio.IMPRESION)).isTrue();
        }

        @Test
        @DisplayName("RF-123: un permiso vencido no autoriza, ni por el usuario ni por el grupo")
        void unPermisoVencidoNoAutoriza() throws SQLException {
            sembrar();

            long vencido =
                    crearUsuario("vencido", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
            otorgarAUsuario(vencido, "calles", Privilegio.LECTURA);
            assertThat(autoriza("vencido", "calles", Privilegio.LECTURA))
                    .as("la vigencia del usuario acabo antes de hoy")
                    .isFalse();

            long futuro = crearUsuario("futuro", LocalDate.of(2027, 1, 1), null);
            otorgarAUsuario(futuro, "calles", Privilegio.LECTURA);
            assertThat(autoriza("futuro", "calles", Privilegio.LECTURA))
                    .as("y la de este todavia no empieza")
                    .isFalse();

            long usuario = crearUsuario("grupo.vencido", null, null);
            long grupo =
                    crearGrupo("Grupo vencido", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
            afiliar(grupo, usuario, true);
            otorgarAGrupo(grupo, "calles", Privilegio.LECTURA);
            assertThat(autoriza("grupo.vencido", "calles", Privilegio.LECTURA))
                    .as("comprobar solo la vigencia del usuario dejaria esta puerta abierta")
                    .isFalse();
        }

        @Test
        @DisplayName("sacar a alguien de un grupo le quita lo que el grupo le daba")
        void sacarDelGrupoQuitaElPermiso() throws SQLException {
            sembrar();
            long usuario = crearUsuario("dado.de.baja", null, null);
            long grupo = crearGrupo("Grupo del que sale", null, null);
            afiliar(grupo, usuario, false);
            otorgarAGrupo(grupo, "calles", Privilegio.LECTURA);

            assertThat(autoriza("dado.de.baja", "calles", Privilegio.LECTURA))
                    .as("la fila sigue ahi porque no se borra (RNF-051); hay que mirar activo")
                    .isFalse();
        }

        @Test
        @DisplayName("un usuario deshabilitado no entra aunque conserve sus permisos")
        void unUsuarioDeshabilitadoNoEntra() throws SQLException {
            sembrar();
            long usuario = crearUsuario("deshabilitado", null, null);
            otorgarAUsuario(usuario, "calles", Privilegio.LECTURA);
            ejecutar("UPDATE usuario SET habilitado = false WHERE id = " + usuario);

            assertThat(autoriza("deshabilitado", "calles", Privilegio.LECTURA)).isFalse();
        }
    }

    @Nested
    @DisplayName("#548 — El perfil de cajero puro, contra la matriz real")
    class ElCajeroPuro {

        @Test
        @DisplayName("un grupo de caja NO da consulta_deuda: la premisa de #548 es real")
        void elGrupoDeCajaNoDaLaConsultaDeDeuda() throws SQLException {
            // La premisa del issue, medida en vez de supuesta: se monta el grupo que una
            // municipalidad crearia para su ventanilla —la opcion de la caja, con lo que
            // hace falta para abrirla y para cobrar— y se pregunta por la opcion de OTRO
            // modulo, que es la que sirve la grilla de deuda.
            sembrar();
            long cajera = crearUsuario("cajera.pura", null, null);
            long ventanilla = crearGrupo("Caja", null, null);
            afiliar(ventanilla, cajera, true);
            // Los dos privilegios en la MISMA fila: `permiso` es unico por (grupo, acceso).
            ejecutar(
                    "INSERT INTO permiso (municipalidad_id, acceso_id, grupo_id, lectura,"
                            + " registro, usuario_registro) SELECT"
                            + " current_setting('app.municipalidad_id')::bigint, a.id, "
                            + ventanilla
                            + ", true, true, 'prueba' FROM acceso a"
                            + " WHERE a.codigo = 'caja_tributaria'");

            assertThat(autoriza("cajera.pura", "caja_tributaria", Privilegio.REGISTRO))
                    .as("puede cobrar")
                    .isTrue();
            assertThat(autoriza("cajera.pura", "caja_tributaria", Privilegio.LECTURA))
                    .as("y puede abrir su pantalla")
                    .isTrue();
            assertThat(autoriza("cajera.pura", "consulta_deuda", Privilegio.LECTURA))
                    .as(
                            "pero NO tiene la opcion que sirve la grilla de deuda: por eso"
                                    + " ConsultaDeudaController declara `oTambien = caja_tributaria`"
                                    + " en vez de dejarlo a que cada implantacion se acuerde")
                    .isFalse();
        }

        @Test
        @DisplayName("y el permiso sigue siendo por opcion: la caja no arrastra el modulo entero")
        void laCajaNoArrastraElModuloEntero() throws SQLException {
            sembrar();
            long cajera = crearUsuario("cajera.acotada", null, null);
            otorgarAUsuario(cajera, "caja_tributaria", Privilegio.LECTURA);

            assertThat(autoriza("cajera.acotada", "caja_tributaria", Privilegio.LECTURA)).isTrue();
            assertThat(autoriza("cajera.acotada", "caja_tasas", Privilegio.LECTURA))
                    .as("la ventanilla de tasas es otra opcion, y se otorga aparte")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("ADR-0013 — La matriz de permisos efectivos de la sesion")
    class Matriz {

        @Test
        @DisplayName("solo trae las opciones con algun privilegio, y con los privilegios que hay")
        void soloLasOpcionesConAlgunPrivilegio() throws SQLException {
            sembrar();
            long usuario = crearUsuario("con.calles", null, null);
            // Dos privilegios en la MISMA fila: `permiso` es unico por (usuario, acceso).
            ejecutar(
                    "INSERT INTO permiso (municipalidad_id, acceso_id, usuario_id, lectura,"
                            + " impresion, usuario_registro) SELECT"
                            + " current_setting('app.municipalidad_id')::bigint, a.id, "
                            + usuario
                            + ", true, true, 'prueba' FROM acceso a WHERE a.codigo = 'calles'");

            Map<String, Set<Privilegio>> matriz = efectivosDe("con.calles");

            assertThat(matriz)
                    .as("de las 134 opciones sembradas, solo aparece sobre la que tiene permiso")
                    .containsOnlyKeys("calles");
            assertThat(matriz.get("calles"))
                    .containsExactlyInAnyOrder(Privilegio.LECTURA, Privilegio.IMPRESION);
        }

        @Test
        @DisplayName("la excepcion del usuario manda: amplia sobre lo del grupo")
        void laExcepcionDelUsuarioAmplia() throws SQLException {
            sembrar();
            long usuario = crearUsuario("amplia", null, null);
            long grupo = crearGrupo("Grupo base", null, null);
            afiliar(grupo, usuario, true);
            otorgarAGrupo(grupo, "calles", Privilegio.LECTURA);
            otorgarAUsuario(usuario, "calles", Privilegio.MODIFICACION);

            assertThat(efectivosDe("amplia").get("calles"))
                    .as("la fila de excepcion sustituye al grupo entero para ese acceso")
                    .containsExactly(Privilegio.MODIFICACION);
        }

        @Test
        @DisplayName("la excepcion del usuario manda: tambien cuando restringe")
        void laExcepcionDelUsuarioRestringe() throws SQLException {
            sembrar();
            long usuario = crearUsuario("restringe", null, null);
            long grupo = crearGrupo("Grupo generoso", null, null);
            afiliar(grupo, usuario, true);
            otorgarAGrupo(grupo, "sectores", Privilegio.LECTURA);
            // Excepcion de usuario sobre `sectores` sin ningun privilegio en true: niega.
            ejecutar(
                    "INSERT INTO permiso (municipalidad_id, acceso_id, usuario_id, usuario_registro)"
                            + " SELECT current_setting('app.municipalidad_id')::bigint, a.id, "
                            + usuario
                            + ", 'prueba' FROM acceso a WHERE a.codigo = 'sectores'");

            assertThat(efectivosDe("restringe"))
                    .as("una excepcion que no otorga nada quita lo que el grupo daba")
                    .doesNotContainKey("sectores");
        }

        @Test
        @DisplayName("un usuario deshabilitado recibe la matriz vacia")
        void unUsuarioDeshabilitadoRecibeLaMatrizVacia() throws SQLException {
            sembrar();
            long usuario = crearUsuario("inhabil", null, null);
            otorgarAUsuario(usuario, "calles", Privilegio.LECTURA);
            ejecutar("UPDATE usuario SET habilitado = false WHERE id = " + usuario);

            assertThat(efectivosDe("inhabil"))
                    .as("igual que el guardia: deshabilitado no entra, conserve o no permisos")
                    .isEmpty();
        }

        @Test
        @DisplayName("un grupo fuera de vigencia no aporta a la matriz")
        void unGrupoFueraDeVigenciaNoAporta() throws SQLException {
            sembrar();
            long usuario = crearUsuario("por.grupo.vencido", null, null);
            long grupo = crearGrupo("Vencido", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
            afiliar(grupo, usuario, true);
            otorgarAGrupo(grupo, "calles", Privilegio.LECTURA);

            assertThat(efectivosDe("por.grupo.vencido")).doesNotContainKey("calles");
        }
    }

    // ------------------------------------------------------------------

    private static Map<String, Set<Privilegio>> efectivosDe(String cuenta) {
        Map<String, Set<Privilegio>> resultado =
                transaccion.execute(estado -> permisos.efectivosDe(cuenta, HOY));
        if (resultado == null) {
            throw new IllegalStateException("efectivosDe no devolvio matriz");
        }
        return resultado;
    }

    private static boolean autoriza(String usuario, String acceso, Privilegio privilegio) {
        return Boolean.TRUE.equals(
                transaccion.execute(
                        estado -> comprobador.autoriza(usuario, acceso, privilegio, HOY)));
    }

    private static void sembrar() {
        sembrador.sembrar(Observacion.de("Siembra de accesos para la prueba de autorizacion"));
    }

    private static long crearUsuario(String cuenta, LocalDate desde, LocalDate hasta) {
        return unicoLong(
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "INSERT INTO usuario (municipalidad_id, cuenta,"
                                                        + " nombre, vigencia_desde, vigencia_hasta)"
                                                        + " VALUES"
                                                        + " (current_setting('app.municipalidad_id')::bigint,"
                                                        + " :cuenta, :cuenta, :desde, :hasta)"
                                                        + " RETURNING id")
                                        .param("cuenta", cuenta)
                                        .param("desde", desde)
                                        .param("hasta", hasta)
                                        .query(Long.class)
                                        .single()));
    }

    private static long crearGrupo(String nombre, LocalDate desde, LocalDate hasta) {
        return unicoLong(
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "INSERT INTO grupo (municipalidad_id, nombre,"
                                                        + " vigencia_desde, vigencia_hasta) VALUES"
                                                        + " (current_setting('app.municipalidad_id')::bigint,"
                                                        + " :nombre, :desde, :hasta) RETURNING id")
                                        .param("nombre", nombre)
                                        .param("desde", desde)
                                        .param("hasta", hasta)
                                        .query(Long.class)
                                        .single()));
    }

    private static void afiliar(long grupo, long usuario, boolean activo) {
        transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "INSERT INTO miembro (municipalidad_id, grupo_id,"
                                                + " usuario_id, usuario_alta, activo) VALUES"
                                                + " (current_setting('app.municipalidad_id')::bigint,"
                                                + " :grupo, :usuario, 'prueba', :activo)")
                                .param("grupo", grupo)
                                .param("usuario", usuario)
                                .param("activo", activo)
                                .update());
    }

    private static void otorgarAUsuario(long usuario, String acceso, Privilegio privilegio) {
        otorgar("usuario_id", usuario, acceso, privilegio);
    }

    private static void otorgarAGrupo(long grupo, String acceso, Privilegio privilegio) {
        otorgar("grupo_id", grupo, acceso, privilegio);
    }

    private static void otorgar(
            String columnaDelSujeto, long sujeto, String acceso, Privilegio privilegio) {
        transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "INSERT INTO permiso (municipalidad_id, acceso_id, "
                                                + columnaDelSujeto
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

    private static void ejecutar(String sql) {
        transaccion.execute(estado -> jdbc.sql(sql).update());
    }

    private static long unicoLong(Long valor) {
        if (valor == null) {
            throw new IllegalStateException("La insercion no devolvio identificador");
        }
        return valor;
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

    private static String textoUnico(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return String.valueOf(resultado.getString(1));
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

    /** Sin uso directo; el fixture queda referenciado para que no se pierda de vista. */
    @SuppressWarnings("unused")
    private static void fijarEn(Connection conexion) throws SQLException {
        ContextoDeTenant.fijar(conexion, municipalidad);
    }
}
