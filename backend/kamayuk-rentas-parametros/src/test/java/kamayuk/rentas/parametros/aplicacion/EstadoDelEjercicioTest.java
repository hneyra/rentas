package kamayuk.rentas.parametros.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros.EstadoDelEjercicio;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * #605 — Si un ejercicio tiene conjunto de parametros sellado, contra PostgreSQL real.
 *
 * <h2>Que se estaba midiendo mal hasta ahora</h2>
 *
 * <p>Nada lo medía: no habia forma de preguntarlo. La unica manera de saber si un ejercicio estaba
 * parametrizado era <b>mandar la peticion de calculo y recibir el 422</b>, asi que quien fracciona
 * rellenaba el formulario entero —y en el preconvenio tambien la observacion que exige la regla 10—
 * para enterarse al final. Con D-02a abierta ese 422 es el estado normal de todas las
 * municipalidades.
 *
 * <h2>Como esta montada</h2>
 *
 * <p>Contra PostgreSQL de verdad y conectada como {@code sgtm_app} —nunca como {@code sgtm_owner},
 * que con {@code FORCE ROW LEVEL SECURITY} tambien queda sujeto a la politica y dejaria pasar la
 * rotura de aislamiento (#537, #545); quien la omite es el superusuario del cluster—. El caso de
 * uso se envuelve con {@link AnnotationTransactionAttributeSource}, que <b>obedece a la
 * anotacion</b> igual que el contenedor: un {@code TransactionTemplate} incondicional dejaria pasar
 * la rotura de quitarle el {@code @Transactional}, que es el defecto de clase de #486 —sin
 * transaccion no hay {@code SET LOCAL} y la politica RLS no devuelve vacio, revienta—.
 *
 * <p>Los ejercicios se reparten para que cada caso tenga el suyo: {@code conjunto_uq} impide dos
 * versiones con el mismo numero, y sembrar el abierto y el sellado en el mismo ano dejaria de medir
 * lo que dice medir.
 */
@DisplayName("#605 — ¿Tiene el ejercicio conjunto de parametros sellado?")
class EstadoDelEjercicioTest {

    /**
     * El acto es de 2026 aunque se pregunte por 2041: la particion de {@code auditoria} es el
     * ejercicio del ACTO, y solo estan declaradas 2026 y 2027 (V5).
     */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio SIN_NINGUNA_VERSION = new Ejercicio(2040);
    private static final Ejercicio SOLO_ABIERTO = new Ejercicio(2041);
    private static final Ejercicio SELLADO = new Ejercicio(2042);
    private static final Ejercicio DOS_VECES_SELLADO = new Ejercicio(2043);
    private static final Ejercicio SELLADO_EN_LA_VECINA = new Ejercicio(2044);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static AdministrarParametros administrar;
    private static JdbcClient jdbcDeLaPrueba;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("290201", "Municipalidad del estado del ejercicio");
        municipalidadVecina = crearMunicipalidad("290202", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        // sgtm_app, y no el dueno: con FORCE ROW LEVEL SECURITY el dueno tambien queda
        // sujeto a la politica, asi que una prueba de aislamiento escrita con `OWNER`
        // pasaria en verde con la fuga dentro (#537).
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        jdbcDeLaPrueba = jdbc;
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        administrar =
                envolver(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);

        enLaMunicipalidad(
                municipalidad,
                () -> {
                    sembrarAbierto(SOLO_ABIERTO);
                    sembrarSellado(SELLADO);
                    sembrarSellado(DOS_VECES_SELLADO);
                    sembrarSellado(DOS_VECES_SELLADO);
                });
        enLaMunicipalidad(municipalidadVecina, () -> sembrarSellado(SELLADO_EN_LA_VECINA));
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

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  AC 1 — la lectura contesta si hay conjunto sellado, y cual
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un ejercicio sin ninguna version no esta sellado, y lo dice sin fallar")
    void sinNingunaVersionNoEstaSellado() {
        fijarContexto(municipalidad);

        EstadoDelEjercicio estado = administrar.estadoDelEjercicio(SIN_NINGUNA_VERSION);

        assertThat(estado.ejercicio())
                .as("la respuesta dice de que ano habla, para que el aviso lo pueda nombrar")
                .isEqualTo(SIN_NINGUNA_VERSION);
        assertThat(estado.estaSellado()).isFalse();
        assertThat(estado.sellado()).isNull();
    }

    @Test
    @DisplayName("un conjunto ABIERTO no cuenta como sellado, aunque tenga sus parametros")
    void unConjuntoAbiertoNoCuentaComoSellado() {
        fijarContexto(municipalidad);

        EstadoDelEjercicio estado = administrar.estadoDelEjercicio(SOLO_ABIERTO);

        assertThat(estado.estaSellado())
                .as(
                        "calcular con un conjunto abierto produce una cifra que manana puede ser"
                                + " otra, y el contribuyente ya tendria el recibo: por eso la"
                                + " consulta lleva su AND estado = 'SELLADO'")
                .isFalse();
        assertThat(estado.sellado()).isNull();
    }

    @Test
    @DisplayName("un ejercicio sellado sale con la identidad de su conjunto")
    void unEjercicioSelladoSaleConSuIdentidad() {
        fijarContexto(municipalidad);

        EstadoDelEjercicio estado = administrar.estadoDelEjercicio(SELLADO);

        assertThat(estado.estaSellado()).isTrue();
        ConjuntoDeParametros conjunto = estado.sellado();
        assertThat(conjunto).isNotNull();
        assertThat(conjunto.id())
                .as("«cual» es el identificador, que es lo que una determinacion guarda")
                .isNotNull();
        assertThat(conjunto.ejercicio()).isEqualTo(SELLADO);
        assertThat(conjunto.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("con dos versiones selladas sale la vigente, que es la ultima")
    void conDosVersionesSelladasSaleLaVigente() {
        fijarContexto(municipalidad);

        EstadoDelEjercicio estado = administrar.estadoDelEjercicio(DOS_VECES_SELLADO);

        ConjuntoDeParametros conjunto = estado.sellado();
        assertThat(conjunto).isNotNull();
        assertThat(conjunto.version())
                .as(
                        "ARQ-09 §3: un arancel corregido a mitad de ano crea una version nueva y la"
                                + " sella al lado de la anterior; la que rige es la ultima. Sin el"
                                + " ORDER BY version DESC la consulta devolveria una cualquiera")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    //  AC 2 — la bitacora: esta lectura NO deja fila, y por que
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "no deja fila de ACCESO: es la unica lectura fuera del catalogo, y auditar aqui es una escritura sin cota")
    void noDejaFilaDeAcceso() throws SQLException {
        fijarContexto(municipalidad);
        long antes = accesosAlEjercicio(SELLADO);

        administrar.estadoDelEjercicio(SELLADO);
        administrar.estadoDelEjercicio(SOLO_ABIERTO);
        administrar.estadoDelEjercicio(SIN_NINGUNA_VERSION);

        assertThat(accesosAlEjercicio(SELLADO))
                .as(
                        "el AC 2 pedia la fila, y medirlo cambio la respuesta: este endpoint es el"
                                + " unico del sistema FUERA del catalogo —SESION_PROPIA, para que"
                                + " un cajero pueda preguntarlo sin el permiso de parametros—, y"
                                + " los cinco escritores de ACCESO que ya existen estan todos"
                                + " detras de un acceso del catalogo o de la cadena firmada del"
                                + " ciudadano. Auditar aqui deja una escritura SIN COTA al alcance"
                                + " de cualquier token valido sobre una tabla append-only por"
                                + " diseno: sin DELETE (regla 4, RNF-051), sin poda y sin limite de"
                                + " peticiones. Recorrer 1990..2100 en bucle la haria crecer sin"
                                + " que nada lo pare")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("y la lectura es de solo lectura: readOnly, no una transaccion de escritura")
    void laTransaccionEsDeSoloLectura() throws Exception {
        org.springframework.transaction.annotation.Transactional anotacion =
                AdministrarParametros.class
                        .getMethod("estadoDelEjercicio", Ejercicio.class)
                        .getAnnotation(
                                org.springframework.transaction.annotation.Transactional.class);

        assertThat(anotacion)
                .as("sin transaccion no hay SET LOCAL y la politica RLS revienta con 500 (#486)")
                .isNotNull();
        assertThat(anotacion.readOnly())
                .as(
                        "declararla de escritura invitaria a volver a meterle una fila de bitacora,"
                                + " que es justo lo que la prueba de arriba impide")
                .isTrue();
    }

    // ------------------------------------------------------------------
    //  Aislamiento
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el conjunto sellado de la vecina no parametriza este ejercicio")
    void elConjuntoSelladoDeLaVecinaNoCuentaAqui() {
        fijarContexto(municipalidad);

        assertThat(administrar.estadoDelEjercicio(SELLADO_EN_LA_VECINA).estaSellado())
                .as(
                        "conjunto_parametros es tabla de tenant: con el pool conectado como"
                                + " superusuario del cluster —que omite RLS incluso con FORCE ROW"
                                + " LEVEL SECURITY— esta municipalidad diria que puede calcular un"
                                + " ejercicio que nadie le sello")
                .isFalse();

        fijarContexto(municipalidadVecina);
        assertThat(administrar.estadoDelEjercicio(SELLADO_EN_LA_VECINA).estaSellado())
                .as("y en la vecina, donde si se sello, sale sellado")
                .isTrue();
    }

    @Test
    @DisplayName("y el centinela: la prueba habla con la base como sgtm_app, no como su dueno")
    void seConectaComoSgtmApp() {
        // Medido, no supuesto (#537, #545): cambiar el pool a `sgtm_owner` —la rotura de
        // aislamiento que uno teclea por costumbre— deja las nueve pruebas en VERDE, porque el
        // esquema declara FORCE ROW LEVEL SECURITY y con el el dueno de la tabla tambien queda
        // sujeto a la politica. Quien la omite es el superusuario del cluster, y esa si pone en
        // rojo la de aqui arriba. Sin este centinela, un cambio de fixture devolveria la conexion
        // al dueno y la prueba de aislamiento seguiria pasando sin medir nada.
        assertThat(usuarioDeLaConexion())
                .as("una prueba de aislamiento escrita con el dueno de las tablas no mide nada")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("preguntar no deja rastro en NINGUNA de las dos municipalidades")
    void preguntarNoDejaRastroEnNinguna() throws SQLException {
        fijarContexto(municipalidad);
        long aqui = accesosDe(municipalidad, SELLADO_EN_LA_VECINA);
        long enLaVecina = accesosDe(municipalidadVecina, SELLADO_EN_LA_VECINA);

        administrar.estadoDelEjercicio(SELLADO_EN_LA_VECINA);

        assertThat(accesosDe(municipalidadVecina, SELLADO_EN_LA_VECINA))
                .as(
                        "la bitacora de cada municipio no puede convertirse en una forma de saber"
                                + " que alguien pregunto desde otro (ADR-0020 §5)")
                .isEqualTo(enLaVecina);
        assertThat(accesosDe(municipalidad, SELLADO_EN_LA_VECINA))
                .as(
                        "y tampoco en la que pregunta: esta lectura no audita, porque es la unica"
                                + " fuera del catalogo y auditarla dejaria una escritura sin cota"
                                + " sobre una tabla append-only al alcance de cualquier token")
                .isEqualTo(aqui);
    }

    // ------------------------------------------------------------------

    private static void fijarContexto(long cual) {
        fijarContexto(cual, "jefe.rentas");
    }

    private static void fijarContexto(long cual, String usuario) {
        TenantContext.fijar(new MunicipalidadId(cual));
        OrigenContext.fijar(new Origen(usuario, null, null));
    }

    private static void enLaMunicipalidad(long cual, Siembra siembra) throws SQLException {
        fijarContexto(cual);
        try {
            siembra.sembrar();
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    private interface Siembra {
        void sembrar() throws SQLException;
    }

    /** Una version abierta con un parametro dentro: tener parametros no es estar sellado. */
    private static void sembrarAbierto(Ejercicio ejercicio) throws SQLException {
        ConjuntoDeParametros abierto =
                administrar.abrirVersion(
                        ejercicio, Observacion.de("Se prepara el ejercicio " + ejercicio));
        administrar.agregarParametro(
                exigirId(abierto),
                parametroFicticio("ABIERTO_" + ejercicio),
                Observacion.de("Se incorpora un parametro mientras se prepara"));
    }

    private static void sembrarSellado(Ejercicio ejercicio) throws SQLException {
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(
                        ejercicio, Observacion.de("Se abre el ejercicio " + ejercicio));
        administrar.agregarParametro(
                exigirId(conjunto),
                parametroFicticio("SELLADO_" + ejercicio + "_v" + conjunto.version()),
                Observacion.de("Se incorpora el parametro de la ordenanza ficticia"));
        administrar.sellar(
                exigirId(conjunto), Observacion.de("Se sella " + ejercicio + " tras la revision"));
    }

    private static long exigirId(ConjuntoDeParametros conjunto) {
        Long id = conjunto.id();
        if (id == null) {
            throw new IllegalStateException("Un conjunto guardado tiene id");
        }
        return id;
    }

    /**
     * Con quien habla de verdad la base, preguntado <b>por el mismo pool</b> que usa el caso de
     * uso. No se lee del campo del {@code DataSource}: lo que importa no es lo que la prueba cree
     * haber configurado, sino como llega la conexion al motor.
     */
    private static String usuarioDeLaConexion() {
        return Objects.requireNonNull(
                jdbcDeLaPrueba.sql("SELECT current_user").query(String.class).single());
    }

    private static long accesosAlEjercicio(Ejercicio ejercicio) throws SQLException {
        return accesosDe(municipalidad, ejercicio);
    }

    private static long accesosDe(long cual, Ejercicio ejercicio) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM auditoria WHERE municipalidad_id = ?"
                                        + " AND tabla = 'conjunto_parametros' AND clave = ?"
                                        + " AND operacion = 'ACCESO'")) {
            sentencia.setLong(1, cual);
            sentencia.setString(2, "ejercicio=" + ejercicio.valor());
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }

    /**
     * Publica un parametro <b>ficticio</b> con el rol que corresponde: la aplicacion no publica.
     */
    private static long parametroFicticio(String clave) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL,"
                                        + " 'FICTICIO', ?, 1.000000, DATE '2026-01-01', 'Valor"
                                        + " ficticio de prueba; no representa ninguna norma',"
                                        + " 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, clave);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
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
}
