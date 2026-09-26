package kamayuk.rentas.documentos;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * #427 — El correlativo de un documento lo reparte una fila contador, no un {@code count(*) + 1}.
 *
 * <p>Hasta #427, {@code DocumentoRepositoryJdbc.siguienteCorrelativo} contaba los documentos del
 * tipo y el ejercicio y sumaba uno, sin candado. Dos emisiones simultaneas leian la misma cuenta,
 * dibujaban y firmaban las dos el papel —cientos de milisegundos— y chocaban en {@code
 * documento_numero_uq}: la segunda contestaba 500 y se deshacia el acto entero que la emitia.
 *
 * <p>La siembra <b>no es uniforme</b>, y cada parte existe para que una rotura distinta se vea:
 *
 * <ul>
 *   <li>la municipalidad tiene <b>3</b> documentos de {@code TIPO} en 2026: el primero nuevo tiene
 *       que ser el {@code 000004};
 *   <li><b>5</b> del mismo tipo en <b>2025</b>: un arranque que no filtre por ejercicio saldria
 *       {@code 000006};
 *   <li><b>2</b> de <b>otro tipo</b> en 2026, numerados {@code 000009} y {@code 000010} —un
 *       historico migrado que no empieza en uno—: un arranque que no filtre por tipo saldria {@code
 *       000011};
 *   <li>y la <b>otra municipalidad</b> tiene los suyos del mismo tipo y ejercicio, hasta el {@code
 *       000007}: si leyera los de esta, o compartiera su contador, su siguiente no seria el {@code
 *       000008}.
 * </ul>
 */
@DisplayName("#427 — El correlativo de documentos no choca bajo concurrencia")
class ElCorrelativoDelDocumentoJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final String TIPO = "ACTA_INTERNAMIENTO";
    private static final String OTRO_TIPO = "RES_REVALIDACION_EDIFICACION";
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final int HILOS = 10;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static EmitirDocumento emitir;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("427101", "Municipalidad del deposito");
        otraMunicipalidad = crearMunicipalidad("427102", "Municipalidad vecina del deposito");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();

        EmitirDocumento objetivo =
                new EmitirDocumento(
                        new DocumentoRepositoryJdbc(jdbc, json),
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        new AuditoriaJdbc(jdbc, RELOJ),
                        RELOJ);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        emitir = (EmitirDocumento) fabrica.getProxy();

        sembrar(municipalidad, TIPO, 2026, 1, 2, 3);
        sembrar(municipalidad, TIPO, 2025, 1, 2, 3, 4, 5);
        sembrar(municipalidad, OTRO_TIPO, 2026, 9, 10);
        sembrar(otraMunicipalidad, TIPO, 2026, 1, 2, 3, 4, 5, 6, 7);
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

    @Test
    @DisplayName(
            "diez emisiones simultaneas del mismo tipo y ejercicio entran las diez, del 000004 al"
                    + " 000013")
    // Se captura RuntimeException a proposito: lo que se cuenta es cuantas emisiones ENTRARON; el
    // motivo del rechazo —hasta #427, `documento_numero_uq`— lo nombra el mensaje de la asercion.
    @SuppressWarnings("checkstyle:IllegalCatch")
    void diezEmisionesSimultaneasEntranLasDiez() throws Exception {
        List<String> rechazos = java.util.Collections.synchronizedList(new ArrayList<>());
        int exitos =
                aLaVez(
                        HILOS,
                        indice -> {
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            OrigenContext.fijar(new Origen("deposito.vehicular", null, null));
                            try {
                                emitirUno(TIPO, "CARRERA-" + indice);
                                return true;
                            } catch (RuntimeException rechazada) {
                                rechazos.add(rechazada.getClass().getSimpleName());
                                return false;
                            }
                        });

        assertThat(exitos)
                .as(
                        "dos internamientos a la vez son ventanilla normal: ninguno puede salir"
                                + " con 500 porque otro dibujaba su acta al mismo tiempo (rechazos:"
                                + " %s)",
                        rechazos)
                .isEqualTo(HILOS);
        assertThat(numerosDe(municipalidad, TIPO, 2026, "CARRERA-%"))
                .as(
                        "consecutivos y por encima de los 3 que ya habia de ese tipo en 2026: ni"
                                + " los 5 de 2025 ni los de otro tipo ni los de otra"
                                + " municipalidad cuentan")
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(4, 3 + HILOS)
                                .mapToObj(n -> String.format("%s-2026-%06d", TIPO, n))
                                .toList());
    }

    @Test
    @DisplayName("la otra municipalidad sigue con su propio numero, y el otro tipo con el suyo")
    void cadaMunicipalidadYCadaTipoConSuContador() {
        TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
        OrigenContext.fijar(new Origen("deposito.vehicular", null, null));
        assertThat(emitirUno(TIPO, "VECINA-1").registro().numero())
                .as("la vecina tenia hasta el 000007; lo que emita la otra no le mueve el numero")
                .isEqualTo(TIPO + "-2026-000008");

        TenantContext.fijar(new MunicipalidadId(municipalidad));
        assertThat(emitirUno(OTRO_TIPO, "OTRO-TIPO-1").registro().numero())
                .as("el otro tipo arranca por encima de SU mayor numero, el 000010")
                .isEqualTo(OTRO_TIPO + "-2026-000011");
    }

    // ------------------------------------------------------------------

    private static EmitirDocumento.Emision emitirUno(String tipo, String referencia) {
        return emitir.emitir(
                tipo,
                EJERCICIO,
                referencia,
                new ModeloDeDocumento(
                        "Acta de internamiento",
                        null,
                        LocalDate.of(2026, 9, 23),
                        List.of(Campo.de("Referencia", referencia)),
                        List.of(),
                        List.of(),
                        null,
                        null),
                FormatoDeDocumento.PDF,
                Observacion.de("Internamiento en el deposito municipal"));
    }

    /** Documentos ya emitidos, con el numero que se le diga: el historico de la municipalidad. */
    private static void sembrar(long muni, String tipo, int ejercicio, int... numeros)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO documento_emitido (municipalidad_id, tipo, numero,"
                                    + " ejercicio, referencia, datos, formato, resumen,"
                                    + " fecha_emision, usuario_emision, observacion)"
                                    + " VALUES (?, ?, ?, ?, ?, CAST('{}' AS jsonb), 'PDF',"
                                    + " repeat('0', 64), DATE '2026-01-15', 'historico',"
                                    + " 'sembrado para la prueba')")) {
                for (int numero : numeros) {
                    sentencia.setLong(1, muni);
                    sentencia.setString(2, tipo);
                    sentencia.setString(3, String.format("%s-%d-%06d", tipo, ejercicio, numero));
                    sentencia.setInt(4, ejercicio);
                    sentencia.setString(5, "HISTORICO-" + numero);
                    sentencia.executeUpdate();
                }
            }
            app.commit();
        }
    }

    private static List<String> numerosDe(long muni, String tipo, int ejercicio, String referencia)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT numero FROM documento_emitido WHERE tipo = ?"
                                    + " AND ejercicio = ? AND referencia LIKE ? ORDER BY numero")) {
                sentencia.setString(1, tipo);
                sentencia.setInt(2, ejercicio);
                sentencia.setString(3, referencia);
                List<String> numeros = new ArrayList<>();
                try (ResultSet filas = sentencia.executeQuery()) {
                    while (filas.next()) {
                        numeros.add(filas.getString(1));
                    }
                }
                app.commit();
                return numeros;
            }
        }
    }

    /** Lo que cada hilo hace, sabiendo cual es. */
    @FunctionalInterface
    private interface Accion {
        boolean ejecutar(int indice) throws Exception;
    }

    private static int aLaVez(int cuantos, Accion accion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (ExecutorService hilos = Executors.newFixedThreadPool(cuantos)) {
            for (int i = 0; i < cuantos; i++) {
                int indice = i;
                Callable<Boolean> tarea =
                        () -> {
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                return accion.ejecutar(indice);
                            } finally {
                                TenantContext.limpiar();
                                OrigenContext.limpiar();
                            }
                        };
                resultados.add(hilos.submit(tarea));
            }
            salida.countDown();
            int exitos = 0;
            for (Future<Boolean> resultado : resultados) {
                if (Boolean.TRUE.equals(resultado.get(60, TimeUnit.SECONDS))) {
                    exitos++;
                }
            }
            return exitos;
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
