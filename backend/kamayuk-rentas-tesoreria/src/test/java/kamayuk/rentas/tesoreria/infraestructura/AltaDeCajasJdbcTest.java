package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.ImportarCajas;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarCaja;
import kamayuk.rentas.tesoreria.dominio.Area;
import kamayuk.rentas.tesoreria.dominio.Caja;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #430 — El alta de las ventanillas, contra PostgreSQL de verdad y como {@code sgtm_app}.
 *
 * <p><b>Lo que este archivo existe para medir</b> no es que una fila entre: es que <b>antes de la
 * carga la ventanilla no se puede abrir</b>. Hasta #430 nada creaba una {@code caja} ni un {@code
 * area} fuera de las fixtures de prueba, así que en una instalación recién implantada la primera
 * cobranza del día fallaba con {@code CajaInexistente} y no había forma de arreglarlo desde dentro
 * del sistema. La prueba {@link #laVentanillaNoSePuedeAbrirHastaQueLaCajaExiste} es esa afirmación,
 * ejecutada.
 *
 * <p>Se conecta como {@code sgtm_app} —nunca como el superusuario que entrega Testcontainers, que
 * omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}—, y el caso de uso va envuelto en un proxy
 * transaccional de verdad: lo que se verifica es la anotación del código de producción.
 */
@DisplayName("#430 — El alta de las ventanillas de una instalacion nueva")
class AltaDeCajasJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Observacion PORQUE =
            Observacion.de("Alta de las ventanillas de la municipalidad (#430)");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static long municipalidadSinCaja;
    private static long municipalidadDelEjemplo;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static CajaRepositoryJdbc cajas;
    private static AreaRepositoryJdbc areas;
    private static ImportarCajas importar;
    private static AbrirCaja abrirCaja;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("241101", "Municipalidad que abre su caja");
        municipalidadVecina = crearMunicipalidad("241102", "Municipalidad vecina");
        municipalidadSinCaja = crearMunicipalidad("241103", "Municipalidad recien implantada");
        municipalidadDelEjemplo = crearMunicipalidad("241104", "Municipalidad del ejemplo");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        cajas = new CajaRepositoryJdbc(jdbc);
        areas = new AreaRepositoryJdbc(jdbc);
        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        RegistrarCaja registrar = envolver(new RegistrarCaja(areas, cajas, auditoria, RELOJ));
        // `ImportarCajas` va TAMBIEN envuelto, y hoy el proxy no hace nada: su metodo no
        // lleva `@Transactional` a proposito. Envolverlo es lo que permite que ponerselo
        // ponga la prueba en rojo -sin el proxy, la anotacion no se aplicaria y la rotura
        // pasaria en verde sin haber cambiado nada, que es como una prueba deja de medir
        // lo que dice medir (mismo criterio que `TransferenciaJdbcTest`).
        importar = envolver(new ImportarCajas(registrar));
        abrirCaja =
                envolver(
                        new AbrirCaja(
                                cajas, new TurnoDeCajaRepositoryJdbc(jdbc), auditoria, RELOJ));
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
        OrigenContext.fijar(new Origen("carga-cajas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    /**
     * El hallazgo de la siembra de demostración, ejecutado.
     *
     * <p>La municipalidad está implantada —tiene fila, accesos, grupos y administrador— y no tiene
     * ninguna ventanilla: abrir el turno falla. Después de cargar el archivo, abre.
     */
    @Test
    @DisplayName("la ventanilla no se puede abrir hasta que la caja existe")
    void laVentanillaNoSePuedeAbrirHastaQueLaCajaExiste() {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadSinCaja));

        assertThatThrownBy(
                        () ->
                                abrirCaja.enLaCaja(
                                        "C-01", "cajero.uno", LocalDate.of(2026, 3, 16), PORQUE))
                .as("recien implantada, la municipalidad no tiene ninguna ventanilla")
                .isInstanceOf(AbrirCaja.CajaInexistente.class);

        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-01,Caja tributaria 1,001,A-01,Rentas
                                """),
                        PORQUE);
        assertThat(informe.nuevas()).isEqualTo(1);

        AbrirCaja.Abierta abierta =
                abrirCaja.enLaCaja("C-01", "cajero.uno", LocalDate.of(2026, 3, 16), PORQUE);
        assertThat(abierta).isNotNull();
    }

    @Test
    @DisplayName("una fila da de alta la caja y su area, con su fila de auditoria cada una")
    void unaFilaDaDeAltaLaCajaYSuArea() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-10,Caja de tasas,010,A-10,Tramite documentario
                                """),
                        PORQUE);

        assertThat(informe.totalFilas()).isEqualTo(1);
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).isEmpty();

        Caja guardada = transaccion.execute(estado -> cajas.porCodigo("C-10").orElseThrow());
        assertThat(guardada.serie()).isEqualTo("010");
        assertThat(guardada.activa()).isTrue();
        assertThat(guardada.areaId()).isNotNull();

        assertThat(transaccion.<Optional<Area>>execute(estado -> areas.porCodigo("A-10")))
                .as("el area de la fila queda registrada, no solo referenciada")
                .isPresent();
        assertThat(filasDeAuditoria("caja")).isPositive();
        assertThat(filasDeAuditoria("area")).isPositive();
    }

    /**
     * Dos ventanillas del mismo archivo que imputan al mismo área: la segunda la reutiliza.
     *
     * <p>Es la excepción deliberada a «reimportar no duplica»: si el área se rechazara por existir,
     * la segunda caja del archivo se caería con ella.
     */
    @Test
    @DisplayName("dos cajas del mismo archivo comparten area: la segunda no la vuelve a crear")
    void dosCajasCompartenArea() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-20,Ventanilla 1,020,A-20,Rentas
                                C-21,Ventanilla 2,021,A-20,Rentas
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isEqualTo(2);
        assertThat(informe.rechazadas()).isEmpty();

        Long primera =
                transaccion.execute(estado -> cajas.porCodigo("C-20").orElseThrow().areaId());
        Long segunda =
                transaccion.execute(estado -> cajas.porCodigo("C-21").orElseThrow().areaId());
        assertThat(primera).isEqualTo(segunda);
        assertThat(cuantasAreasConCodigo("A-20")).isEqualTo(1);
    }

    @Test
    @DisplayName("una caja sin area entra igual: la caja tributaria general no imputa a ninguna")
    void unaCajaSinAreaEntraIgual() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-30,Caja tributaria general,030,,
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(
                        transaccion
                                .<Caja>execute(estado -> cajas.porCodigo("C-30").orElseThrow())
                                .areaId())
                .isNull();
    }

    /**
     * La fila mala se rechaza sola, y la que la sigue entra.
     *
     * <p>Es la propiedad que {@code ImportarVias} documenta y que #328 volvió a medir: cada fila
     * abre su propia transacción porque {@link ImportarCajas#importar} <b>no</b> lleva
     * {@code @Transactional}. Con el bucle envuelto, la fila rechazada se lleva a la siguiente por
     * delante —y la corrida entera revienta con {@code UnexpectedRollbackException}—.
     */
    @Test
    @DisplayName("una fila que viola la unicidad se rechaza, y la siguiente entra")
    void unaFilaQueVIolaLaUnicidadNoSeLLevaALaSiguiente() {
        importar.importar(
                new StringReader(
                        """
                        codigo,nombre,serie,codigoArea,nombreArea
                        C-40,Ventanilla ya cargada,040,,
                        """),
                PORQUE);

        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-40,Repetida,041,,
                                C-41,La que sigue,042,,
                                """),
                        PORQUE);

        assertThat(informe.totalFilas()).isEqualTo(2);
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("C-40");
        assertThat(informe.rechazadas().get(0).motivo())
                .as("el motivo no nombra la tabla ni la restriccion de PostgreSQL (ARQ-04 §5)")
                .doesNotContain("caja_codigo_uq");
        assertThat(transaccion.<Optional<Caja>>execute(estado -> cajas.porCodigo("C-41")))
                .isPresent();
    }

    @Test
    @DisplayName("dos cajas no comparten serie: la segunda se rechaza con su motivo")
    void dosCajasNoComparteSerie() {
        importar.importar(
                new StringReader(
                        """
                        codigo,nombre,serie,codigoArea,nombreArea
                        C-50,Ventanilla 50,050,,
                        """),
                PORQUE);

        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-51,Otra ventanilla,050,,
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas()).hasSize(1);
    }

    @Test
    @DisplayName("un area que no existe y la fila no nombra se rechaza, sin inventarle nombre")
    void unAreaSinNombreSeRechaza() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-60,Ventanilla 60,060,A-60,
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("A-60");
    }

    @Test
    @DisplayName("la fila incompleta se rechaza y ninguna caja queda a medias")
    void laFilaIncompletaSeRechaza() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-70,Sin serie
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(transaccion.<Optional<Caja>>execute(estado -> cajas.porCodigo("C-70")))
                .isEmpty();
    }

    @Test
    @DisplayName("la caja de una municipalidad no se ve desde otra")
    void laCajaDeUnaMunicipalidadNoSeVeDesdeOtra() {
        importar.importar(
                new StringReader(
                        """
                        codigo,nombre,serie,codigoArea,nombreArea
                        C-80,Solo de esta,080,,
                        """),
                PORQUE);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadVecina));
        assertThat(transaccion.<Optional<Caja>>execute(estado -> cajas.porCodigo("C-80")))
                .isEmpty();
        assertThat(transaccion.<Optional<Area>>execute(estado -> areas.porCodigo("A-10")))
                .isEmpty();
    }

    /**
     * La misma serie en dos municipalidades <b>sí</b> puede repetirse.
     *
     * <p>{@code caja_serie_uq} es por municipalidad, como todo lo demás: dos municipios que numeran
     * sus recibos «001» no se estorban, y exigir lo contrario sería una fuga del modelo
     * multi-tenant disfrazada de restricción de negocio.
     */
    @Test
    @DisplayName("dos municipalidades pueden numerar con la misma serie")
    void dosMunicipalidadesPuedenNumerarIgual() {
        importar.importar(
                new StringReader(
                        """
                        codigo,nombre,serie,codigoArea,nombreArea
                        C-90,Ventanilla 90,090,,
                        """),
                PORQUE);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadVecina));
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                """
                                codigo,nombre,serie,codigoArea,nombreArea
                                C-90,Ventanilla 90 de la vecina,090,,
                                """),
                        PORQUE);

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).isEmpty();
    }

    /**
     * El archivo de ejemplo versionado se carga <b>tal cual</b>, contra PostgreSQL.
     *
     * <p>Mismo criterio que {@code ArchivosDeEjemploTest} en catastro (#290): un CSV que se copia y
     * se corre contra un ambiente real tiene que fallar en el build y no delante de alguien. Aquí,
     * además, contra la base: una serie repetida o un área sin nombre no las caza un analizador,
     * las caza el índice.
     */
    @Test
    @DisplayName("ejemplos/cajas.csv se carga entero, sin una sola fila rechazada")
    void elArchivoDeEjemploSeCargaEntero() throws IOException {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadDelEjemplo));

        InformeDeImportacion informe;
        try (Reader archivo =
                Files.newBufferedReader(ejemplos().resolve("cajas.csv"), StandardCharsets.UTF_8)) {
            informe = importar.importar(archivo, PORQUE);
        }

        assertThat(informe.rechazadas()).isEmpty();
        assertThat(informe.nuevas()).isEqualTo(informe.totalFilas()).isGreaterThanOrEqualTo(2);
        assertThat(transaccion.<Optional<Caja>>execute(estado -> cajas.porCodigo("C-01")))
                .as("la primera ventanilla del ejemplo es la que abre la caja tributaria")
                .isPresent();
    }

    // ------------------------------------------------------------------

    /** {@code infra/carga-de-datos/ejemplos}, buscando la raiz del repositorio hacia arriba. */
    private static Path ejemplos() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            Path candidato = actual.resolve("infra/carga-de-datos/ejemplos");
            if (Files.isDirectory(candidato)) {
                return candidato;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException(
                "No se encontro infra/carga-de-datos/ejemplos desde "
                        + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static long filasDeAuditoria(String tabla) {
        Long cuantas =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT count(*) FROM auditoria"
                                                        + " WHERE tabla = :tabla AND operacion = 'ALTA'")
                                        .param("tabla", tabla)
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0L : cuantas;
    }

    private static long cuantasAreasConCodigo(String codigo) {
        Long cuantas =
                transaccion.execute(
                        estado ->
                                jdbc.sql("SELECT count(*) FROM area WHERE codigo = :codigo")
                                        .param("codigo", codigo)
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0L : cuantas;
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
