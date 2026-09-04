package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * Las <b>dos</b> barreras del vocabulario de tributos del libro, medidas por separado (#553).
 *
 * <p>Se conecta como {@code sgtm_app}, nunca como {@code sgtm_owner}: con {@code FORCE ROW LEVEL
 * SECURITY} el dueno de la tabla tambien queda sujeto a la politica (#537, #545, #601).
 *
 * <h2>Por que dos barreras y por que medirlas aparte</h2>
 *
 * <p>Es la leccion de #188 y #435: quitar la guarda de Java deja las pruebas de sintoma en verde
 * —la para el {@code CHECK}— y quitar el {@code CHECK} tambien —la para Java—. Cada una tiene aqui
 * su prueba:
 *
 * <ul>
 *   <li>La de la base escribe por <b>SQL directo</b> y espera {@code 23514}.
 *   <li>La de Java pasa por el caso de uso y espera {@code TributoDesconocido}, con el valor
 *       recibido y los admitidos en el mensaje.
 * </ul>
 *
 * <h2>Y lo que el vocabulario deliberadamente NO cierra</h2>
 *
 * <p>Las filas escritas antes de {@code V74} no se pueden corregir —el libro no admite {@code
 * UPDATE} ni {@code DELETE} (V7, regla 4), y el migrador no puede reescribirlas porque corre sin
 * contexto de tenant (DAT-01 §0, medido igual en V64)—. Asi que:
 *
 * <ul>
 *   <li>se siguen <b>leyendo</b>, porque validar al leer dejaria a esa instalacion sin estado de
 *       cuenta;
 *   <li>se pueden <b>reversar</b>, que es el unico modo de correccion que la regla 4 deja abierto,
 *       y por eso el {@code CHECK} exceptua a la fila que reversa otra;
 *   <li>su cache de saldo se puede <b>reconstruir</b>, y por eso {@code saldo_proyectado} no lleva
 *       {@code CHECK}: es una copia derivada del libro, y acotarla convertiria un defecto
 *       detectable en un estado de cuenta que revienta;
 *   <li>y se pueden <b>detectar</b>, que es lo tercero que el issue pide.
 * </ul>
 */
@DisplayName("#553 — El vocabulario de tributos del libro, contra PostgreSQL")
class VocabularioDeTributosJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA = LocalDate.of(2026, 4, 10);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T14:00:00Z"), ZoneId.of("America/Lima"));

    /** La grafia que {@code ejemplos/deuda.csv} sembraba y que el sistema nunca escribio. */
    private static final String GRAFIA_VIEJA = "ARBITRIOS";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static SaldoRepositoryJdbc saldos;
    private static RegistrarAsiento registrar;

    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("280101", "Municipalidad del vocabulario");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        saldos = new SaldoRepositoryJdbc(jdbc);
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  AC 1 — un solo origen: el enumerado y el CHECK dicen lo mismo
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — el CHECK de la base declara exactamente el enumerado")
    class ElOrigenEsUnoSolo {

        @Test
        @DisplayName("los doce textos del enumerado son los doce del CHECK, letra por letra")
        void elCheckDeclaraLoMismoQueElEnumerado() throws SQLException {
            List<String> enLaBase = literalesDelCheck();

            assertThat(enLaBase)
                    .as(
                            "dos sitios que declaran el vocabulario no son dos redes: son dos"
                                    + " sitios donde se puede corregir uno solo (#188, #435)")
                    .containsExactlyInAnyOrderElementsOf(TributoDelLibro.admitidos());
        }

        @Test
        @DisplayName("y el de las costas lleva el espacio tambien en la base")
        void elDeLasCostasLlevaElEspacioEnLaBase() throws SQLException {
            // Si el enumerado dejara que `name()` decidiera el texto, aqui saldria
            // COSTAS_PROCESALES y las costas liquidadas desde #42 quedarian huerfanas.
            assertThat(literalesDelCheck())
                    .contains("COSTAS PROCESALES")
                    .doesNotContain("COSTAS_PROCESALES");
        }
    }

    // ------------------------------------------------------------------
    //  AC 2 y AC 3 — las dos barreras, cada una con su prueba
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 3 — asentar ARBITRIOS se rechaza, por los dos lados")
    class LasDosBarreras {

        @Test
        @DisplayName("la base: un INSERT directo con la grafia vieja da 23514")
        void laBaseRechazaElInsertDirecto() throws SQLException {
            long titular = nuevoTitular();

            assertThatThrownBy(() -> insertarDirecto(titular, GRAFIA_VIEJA, null))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            error ->
                                    assertThat(((SQLException) error).getSQLState())
                                            .as("asiento_tributo_ck, la barrera de V74")
                                            .isEqualTo("23514"));
        }

        @Test
        @DisplayName("y el mismo INSERT con la grafia del vocabulario entra")
        void laBaseAdmiteLaGrafiaDelVocabulario() throws SQLException {
            long titular = nuevoTitular();

            assertThatCode(() -> insertarDirecto(titular, TributoDelLibro.ARBITRIO.texto(), null))
                    .as(
                            "sin este contraste, la prueba de arriba podria estar diciendo que no a"
                                    + " todo")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Java: el caso de uso lo rechaza nombrando la grafia y los admitidos")
        void javaLoRechazaNombrandoLaGrafia() throws SQLException {
            long titular = nuevoTitular();

            assertThatThrownBy(
                            () ->
                                    registrar.asentar(
                                            Asiento.nuevo(
                                                    EJERCICIO,
                                                    titular,
                                                    GRAFIA_VIEJA,
                                                    Concepto.INSOLUTO,
                                                    TipoAsiento.CARGO,
                                                    Fase.ORDINARIA,
                                                    1,
                                                    null,
                                                    null,
                                                    null,
                                                    Dinero.de("36.50"),
                                                    FECHA,
                                                    "SALDO-INICIAL-DEMO"),
                                            Observacion.de("Siembra con la grafia vieja")))
                    .isInstanceOf(TributoDelLibro.TributoDesconocido.class)
                    .hasMessageContaining("'ARBITRIOS'")
                    .hasMessageContaining("ARBITRIO");

            assertThat(cuantosAsientos(titular)).as("y no llega a escribir nada").isZero();
        }

        @Test
        @DisplayName("con la grafia del vocabulario el mismo cargo entra y se proyecta")
        void conLaGrafiaBuenaEntra() throws SQLException {
            long titular = nuevoTitular();

            asentarCargo(titular, TributoDelLibro.ARBITRIO.texto(), "36.50");

            assertThat(cuantosAsientos(titular)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    //  Lo que el vocabulario NO cierra sobre las filas ya escritas
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Las filas anteriores a V74: se leen, se reversan y se detectan")
    class LasFilasQueYaEstaban {

        @Test
        @DisplayName("una fila con la grafia vieja se sigue leyendo por el camino de siempre")
        void seSigueLeyendo() throws SQLException {
            long titular = nuevoTitular();
            insertarSaltandoElCheck(titular, GRAFIA_VIEJA);

            List<Asiento> deLaObligacion =
                    transaccion.execute(
                            estado ->
                                    asientos.deLaObligacion(
                                            new ClaveDeSaldo(
                                                    titular,
                                                    GRAFIA_VIEJA,
                                                    EJERCICIO,
                                                    1,
                                                    null,
                                                    null)));

            assertThat(deLaObligacion)
                    .as(
                            "validar al leer dejaria sin estado de cuenta a la instalacion que"
                                    + " tenga una, que es justo la que hay que poder mirar")
                    .singleElement()
                    .satisfies(asiento -> assertThat(asiento.tributo()).isEqualTo(GRAFIA_VIEJA));
        }

        @Test
        @DisplayName("y se puede REVERSAR: el CHECK exceptua a la fila que reversa otra")
        void seDejaReversar() throws SQLException {
            long titular = nuevoTitular();
            insertarSaltandoElCheck(titular, GRAFIA_VIEJA);
            Asiento original =
                    java.util.Objects.requireNonNull(
                                    transaccion.execute(
                                            estado ->
                                                    asientos.deLaObligacion(
                                                            new ClaveDeSaldo(
                                                                    titular,
                                                                    GRAFIA_VIEJA,
                                                                    EJERCICIO,
                                                                    1,
                                                                    null,
                                                                    null))))
                            .getFirst();

            Asiento reversion =
                    transaccion.execute(
                            estado ->
                                    registrar.reversar(
                                            java.util.Objects.requireNonNull(original.id()),
                                            FECHA,
                                            "RES-REVERSION",
                                            Observacion.de(
                                                    "Se asento con una grafia que no es del"
                                                            + " vocabulario")));

            assertThat(java.util.Objects.requireNonNull(reversion).tributo())
                    .as(
                            "reversar es el unico modo de corregir un asiento (regla 4): cerrarlo"
                                    + " aqui seria cerrarlo justo donde hace falta")
                    .isEqualTo(GRAFIA_VIEJA);
            assertThat(cuantosAsientos(titular)).isEqualTo(2);
        }

        @Test
        @DisplayName("saldo_proyectado no lleva CHECK: su cache se puede reconstruir")
        void laCacheDelSaldoSeReconstruye() throws SQLException {
            long titular = nuevoTitular();
            insertarSaltandoElCheck(titular, GRAFIA_VIEJA);
            ClaveDeSaldo clave = new ClaveDeSaldo(titular, GRAFIA_VIEJA, EJERCICIO, 1, null, null);

            // Es lo que hace `RegistrarAsiento.reproyectar` en CADA escritura. Con un CHECK en
            // la cache, este UPSERT fallaria y la obligacion con grafia vieja se quedaria sin
            // saldo: un defecto detectable convertido en un estado de cuenta que revienta.
            assertThatCode(
                            () ->
                                    transaccion.execute(
                                            estado -> {
                                                saldos.proyectar(
                                                        new SaldoProyectado(
                                                                clave,
                                                                Dinero.de("55.00"),
                                                                Fase.ORDINARIA,
                                                                null,
                                                                RELOJ.instant()));
                                                return null;
                                            }))
                    .doesNotThrowAnyException();

            java.util.Optional<SaldoProyectado> reconstruido =
                    java.util.Objects.requireNonNull(
                            transaccion.execute(estado -> saldos.buscar(clave)));
            assertThat(reconstruido).as("y queda leible, con su grafia tal cual").isPresent();
        }

        @Test
        @DisplayName("y se DETECTAN: el libro dice que tributos suyos no son del vocabulario")
        void seDetectan() throws SQLException {
            long conGrafiaVieja = nuevoTitular();
            long conGrafiaBuena = nuevoTitular();
            insertarSaltandoElCheck(conGrafiaVieja, GRAFIA_VIEJA);
            asentarCargo(conGrafiaBuena, TributoDelLibro.PREDIAL.texto(), "120.00");

            List<String> fuera =
                    java.util.Objects.requireNonNull(
                            transaccion.execute(estado -> asientos.tributosFueraDelVocabulario()));

            assertThat(fuera)
                    .as(
                            "no se pueden corregir (regla 4), asi que lo unico que se puede hacer"
                                    + " con ellas es decir cuales son")
                    .contains(GRAFIA_VIEJA)
                    .doesNotContain(TributoDelLibro.PREDIAL.texto());
        }
    }

    // ------------------------------------------------------------------

    /**
     * Los literales del {@code CHECK} tal como PostgreSQL lo guarda, leidos de {@code
     * pg_constraint}. Se lee de la base y no del archivo {@code .sql} a proposito: lo que acota los
     * {@code INSERT} es la restriccion aplicada, no el texto de la migracion.
     */
    private static List<String> literalesDelCheck() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                        + " WHERE conname = 'asiento_tributo_ck'"
                                        + "   AND conrelid = 'cuenta_corriente_asiento'::regclass");
                ResultSet fila = sentencia.executeQuery()) {
            assertThat(fila.next()).as("V74 declara asiento_tributo_ck").isTrue();
            String definicion = fila.getString(1);
            Matcher literales = Pattern.compile("'([^']*)'").matcher(definicion);
            List<String> encontrados = new ArrayList<>();
            while (literales.find()) {
                encontrados.add(literales.group(1));
            }
            return encontrados;
        }
    }

    private static void asentarCargo(long titular, String tributo, String importe) {
        transaccion.execute(
                estado -> {
                    registrar.asentar(
                            Asiento.nuevo(
                                    EJERCICIO,
                                    titular,
                                    tributo,
                                    Concepto.INSOLUTO,
                                    TipoAsiento.CARGO,
                                    Fase.ORDINARIA,
                                    1,
                                    null,
                                    null,
                                    null,
                                    Dinero.de(importe),
                                    FECHA,
                                    "SALDO-INICIAL-DEMO"),
                            Observacion.de("Siembra de la prueba de #553"));
                    return null;
                });
    }

    /** Un {@code INSERT} por SQL directo, para medir la barrera de la base sola (#188, #435). */
    private static void insertarDirecto(
            long titular, String tributo, @org.jspecify.annotations.Nullable Long reversado)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                                    + " contribuyente_id, tributo, concepto, tipo, fase, periodo,"
                                    + " monto, fecha_valor, documento_origen, usuario_id,"
                                    + " asiento_reversado_id)"
                                    + " VALUES (?, 2026, ?, ?, 'INSOLUTO', 'CARGO', 'ORDINARIA',"
                                    + " 1, 36.50, DATE '2026-04-10', 'SALDO-INICIAL-DEMO',"
                                    + " 'prueba', ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, titular);
                sentencia.setString(3, tributo);
                if (reversado == null) {
                    sentencia.setNull(4, java.sql.Types.BIGINT);
                } else {
                    sentencia.setLong(4, reversado);
                }
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /**
     * Una fila anterior a {@code V74}: la escribe el <b>dueno</b> con la restriccion desactivada un
     * instante, porque desde {@code sgtm_app} ya no se puede producir —que es exactamente lo que
     * este issue cierra— y hace falta poder sembrar el estado que las instalaciones ya tienen.
     */
    private static void insertarSaltandoElCheck(long titular, String tributo) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement apagar =
                        owner.prepareStatement(
                                "ALTER TABLE cuenta_corriente_asiento"
                                        + " DROP CONSTRAINT asiento_tributo_ck")) {
            apagar.executeUpdate();
            owner.commit();
        }
        try {
            insertarDirecto(titular, tributo, null);
        } finally {
            try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                    PreparedStatement encender =
                            owner.prepareStatement(
                                    "ALTER TABLE cuenta_corriente_asiento"
                                            + " ADD CONSTRAINT asiento_tributo_ck"
                                            + " CHECK (asiento_reversado_id IS NOT NULL"
                                            + "        OR tributo IN ('PREDIAL','ARBITRIO',"
                                            + "        'VEHICULAR','ALCABALA','ESPECTACULOS',"
                                            + "        'ANUNCIOS','JUEGOS','MULTA_TRIBUTARIA',"
                                            + "        'MULTA_TRANSITO','MULTA_ADMINISTRATIVA',"
                                            + "        'CONVENIO','COSTAS PROCESALES'))"
                                            + " NOT VALID")) {
                encender.executeUpdate();
                owner.commit();
            }
        }
    }

    private static long cuantosAsientos(long titular) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT count(*) FROM cuenta_corriente_asiento"
                                    + " WHERE contribuyente_id = ?")) {
                sentencia.setLong(1, titular);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        }
    }

    private static long nuevoTitular() throws SQLException {
        siguienteCodigo++;
        String codigo = String.format("VOC-%04d", siguienteCodigo);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR DEL"
                                    + " VOCABULARIO', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("9090%04d", siguienteCodigo));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
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
