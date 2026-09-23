package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dos bajas a la vez no extinguen dos veces la misma deuda, de HTTP a PostgreSQL (#445).
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>{@code RegistrarMovimientoDeDeuda} leia el libro <b>sin candado</b>. Dos {@code POST
 * /rentas/deuda/bajas} por los 100,00 de la misma cuota —un reintento tras un tiempo de espera
 * agotado, que es realista porque el PDF se genera dentro de la transaccion— leian las dos 100,00,
 * pasaban las dos la guarda de {@code BajaMayorQueLaDeuda} y asentaban las dos. La segunda se
 * quedaba esperando en el {@code UPSERT} de {@code saldo_proyectado} hasta que la primera
 * confirmaba, y seguia: el correlativo de su nota de cargo ya veia el de la primera, asi que no
 * chocaba nada. Resultado: <b>el libro en -100,00</b> por cada baja de mas, y la proyeccion escrita
 * con un libro que la transaccion leyo antes de esperar.
 *
 * <p>Es la carrera que {@code SaldoRepository#bloquear} existe para impedir (#33) y que la
 * cobranza, el convenio y la extincion ya impedian: la baja de ventanilla era la unica escritura de
 * deuda que no pedia el candado.
 *
 * <h2>Como se mide</h2>
 *
 * <p>Calcado de {@code AltaDeDeudaRepetidaFronteraTest#diezHilosPorElCasoDeUsoEnteroDejanUnAlta}:
 * diez hilos que salen a la vez por el circuito entero —controlador, proxy transaccional, {@code
 * kamayuk_app} con su {@code SET LOCAL}—. Aqui <b>no</b> hay indice que medir aparte: la garantia
 * es el {@code SELECT ... FOR UPDATE}, y solo se ve con el caso de uso entero, que es donde se
 * pide. Y lo que se mira al final es el libro, por SQL, y no las respuestas: el dia del defecto las
 * diez contestaban {@code 201}.
 */
@DisplayName("RF-044 — Diez bajas simultaneas de la misma cuota extinguen UNA vez (#445)")
class BajasSimultaneasFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String OBSERVACION = "Deuda dada de alta por error material";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("270445", "Municipalidad de las bajas simultaneas");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        RegistrarAsiento registrarAsiento =
                new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();
        EmitirDocumento documentos =
                new EmitirDocumento(
                        new DocumentoRepositoryJdbc(jdbc, json),
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        auditoria,
                        RELOJ);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MovimientosDeDeudaController(
                                        envolver(
                                                new RegistrarMovimientoDeDeuda(
                                                        asientos,
                                                        saldos,
                                                        registrarAsiento,
                                                        new CalculoDeDeuda(new SinAcumulacion()),
                                                        new PoliticaDeRedondeo(
                                                                2, RoundingMode.HALF_UP),
                                                        documentos,
                                                        TITULARES_DE_LA_UNIDAD),
                                                gestor),
                                        envolver(new ConsultasDelLibro(asientos), gestor),
                                        RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                        .build();
    }

    /**
     * El proxy obedece a la anotacion, como el contenedor: el candado solo vale dentro de la
     * transaccion del acto, y un {@link TransactionTemplate} incondicional lo daria por bueno
     * aunque alguien quitara el {@code @Transactional} (#486).
     */
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
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "diez hilos que dan de baja 100,00 de la misma cuota dejan UNA baja y nueve 422, con el"
                    + " libro y la proyeccion en 0,00")
    void diezBajasSimultaneasDejanUna() throws Exception {
        diezBajasALaVez("BJS-0001", "70445001", "RES-2026-4451", false);
    }

    /**
     * La misma carrera por {@code registrarRepartido} (#598), que es el camino que la pantalla usa
     * siempre ({@code repartir: true}, sin cuota: la fila entera). Lee el libro por su cuenta y
     * pide su propio candado, asi que necesita su propia prueba: con la de arriba sola, quitar ese
     * candado no pondria nada en rojo.
     */
    @Test
    @DisplayName("y lo mismo repartiendo la baja de la fila entera, que es lo que hace la pantalla")
    void diezBajasRepartidasSimultaneasDejanUna() throws Exception {
        diezBajasALaVez("BJS-0002", "70445002", "RES-2026-4452", true);
    }

    private static void diezBajasALaVez(
            String codigoNuevo, String dni, String documento, boolean repartida) throws Exception {
        String codigo = crearContribuyente(codigoNuevo, dni);
        long contribuyente = idDelContribuyente(codigo);
        assertThat(
                        movimiento("altas", codigo, "ALTA-" + documento, false)
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);

        // El mismo papel en los diez: es un reintento, y una baja no tiene indice unico
        // —el de V75 es solo del alta— que lo pare.
        int hilos = 10;

        CountDownLatch salida = new CountDownLatch(1);
        List<Callable<MvcResult>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(
                    () -> {
                        // TenantContext y OrigenContext son ThreadLocal: cada hilo del pool
                        // empieza sin ellos, igual que empezaria una peticion.
                        TenantContext.fijar(new MunicipalidadId(municipalidad));
                        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
                        salida.await(10, TimeUnit.SECONDS);
                        try {
                            return movimiento("bajas", codigo, documento, repartida);
                        } finally {
                            TenantContext.limpiar();
                            OrigenContext.limpiar();
                        }
                    });
        }

        List<MvcResult> respuestas = todas(tareas, salida, hilos);
        List<Integer> estados = new ArrayList<>();
        for (MvcResult respuesta : respuestas) {
            estados.add(respuesta.getResponse().getStatus());
        }

        assertThat(netoDeLaCuota(contribuyente))
                .as(
                        "el libro: 100,00 de alta menos UNA baja de 100,00. Sin el candado las"
                                + " diez leen 100,00, las diez pasan la guarda y el libro queda en"
                                + " negativo, que es el estado que BajaMayorQueLaDeuda declara"
                                + " imposible. Respuestas: %s",
                        estados)
                .isEqualByComparingTo("0.00");
        assertThat(saldoProyectado(contribuyente))
                .as(
                        "y la proyeccion dice lo mismo que el libro: si cada baja la reescribe con"
                                + " un libro leido antes de esperar, deja de ser su proyeccion")
                .isEqualByComparingTo("0.00");
        assertThat(cuantasBajas(documento)).as("una baja en el libro, no diez").isEqualTo(1);
        assertThat(Collections.frequency(estados, 201))
                .as("y una sola respuesta puede decir que la hizo: %s", estados)
                .isEqualTo(1);
        assertThat(Collections.frequency(estados, 422))
                .as(
                        "las otras nueve releen el libro despues del candado, ven la primera"
                                + " baja y se rechazan por su motivo —ni 500 ni ningun otro—: %s",
                        estados)
                .isEqualTo(hilos - 1);
        for (MvcResult respuesta : respuestas) {
            if (respuesta.getResponse().getStatus() == 422) {
                assertThat(respuesta.getResponse().getContentAsString())
                        .contains("a esa fecha solo se deben 0.00");
            }
        }
    }

    /**
     * El centinela de #537 y #545: si alguien cambia la conexion de la prueba al dueno o al
     * superusuario, esta clase deja de medir el camino de produccion y no habria nada que lo
     * dijera.
     */
    @Test
    @DisplayName("la prueba se conecta como kamayuk_app, no como el dueno ni como el superusuario")
    void seConectaComoKamayukApp() {
        String usuario =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(usuario).isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------

    private static List<MvcResult> todas(
            List<Callable<MvcResult>> tareas, CountDownLatch salida, int hilos) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        List<MvcResult> resultados = new ArrayList<>();
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (Callable<MvcResult> tarea : tareas) {
                futuros.add(ejecutor.submit(tarea));
            }
            salida.countDown();
            for (Future<MvcResult> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS));
            }
        } finally {
            ejecutor.shutdownNow();
        }
        return resultados;
    }

    /**
     * Cuota 5 del PREDIAL 2026, 100,00 de insoluto, con fecha valor 2026-05-10. Repartida, sin
     * cuota: la baja de la fila entera, que es lo que manda la pantalla (#598).
     */
    private static MvcResult movimiento(
            String ruta, String codigo, String documento, boolean repartida) throws Exception {
        String cuerpo =
                "{\"codContribuyente\":\""
                        + codigo
                        + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                        + (repartida ? "\"repartir\":true," : "\"cuota\":5,")
                        + "\"insoluto\":\"100.00\","
                        + "\"fechaValor\":\"2026-05-10\","
                        + "\"documentoOrigen\":\""
                        + documento
                        + "\","
                        // La causal la declara toda baja desde #684; el alta no la lleva.
                        + ("bajas".equals(ruta) ? "\"causal\":\"ERROR_MATERIAL\"," : "")
                        + "\"observacion\":\""
                        + OBSERVACION
                        + "\"}";
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/" + ruta)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    // ------------------------------------------------------------------

    /**
     * Lo que quedo en el libro, leido por SQL directo y no por la respuesta del acto: la respuesta
     * ya salia bien el dia del defecto.
     */
    private static BigDecimal netoDeLaCuota(long contribuyente) {
        return unImporte(
                "SELECT COALESCE(SUM(CASE WHEN tipo = 'CARGO' THEN monto ELSE -monto END), 0)"
                        + " FROM cuenta_corriente_asiento"
                        + " WHERE contribuyente_id = ? AND concepto = 'INSOLUTO' AND periodo = 5",
                contribuyente);
    }

    private static BigDecimal saldoProyectado(long contribuyente) {
        return unImporte(
                "SELECT insoluto_saldo FROM saldo_proyectado"
                        + " WHERE contribuyente_id = ? AND periodo = 5",
                contribuyente);
    }

    private static long cuantasBajas(String documentoOrigen) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT count(*) FROM cuenta_corriente_asiento"
                                    + " WHERE documento_origen = ? AND acto = 'BAJA_DEUDA'")) {
                sentencia.setString(1, documentoOrigen);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static BigDecimal unImporte(String sql, long contribuyente) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setLong(1, contribuyente);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getBigDecimal(1);
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static long idDelContribuyente(String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT id FROM contribuyente WHERE codigo_contribuyente = ?")) {
                sentencia.setString(1, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
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

    private static String crearContribuyente(String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.executeUpdate();
                app.commit();
                return codigo;
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /** No acumula nada: aqui se mide el candado, no la mora (D-02). */
    private static final class SinAcumulacion implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /**
     * El puerto de #635: sin predio ni vehiculo en la clave no se consulta, pero el constructor lo
     * exige. Si se consultara, «sin titular» es lo que deja pasar el acto sin declarar nada (#680).
     */
    private static final TitularesDeLaUnidad TITULARES_DE_LA_UNIDAD =
            new TitularesDeLaUnidad() {

                @Override
                public TitularidadDeLaUnidad delPredio(long predioId, LocalDate fecha) {
                    return TitularidadDeLaUnidad.sinTitular();
                }

                @Override
                public TitularidadDeLaUnidad delVehiculo(long vehiculoId, LocalDate fecha) {
                    return TitularidadDeLaUnidad.sinTitular();
                }
            };
}
