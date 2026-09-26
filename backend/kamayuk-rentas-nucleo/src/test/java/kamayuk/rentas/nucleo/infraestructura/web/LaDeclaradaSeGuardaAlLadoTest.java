package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.nucleo.aplicacion.CandadoDeEmision;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeLaDeterminacionPredial;
import kamayuk.rentas.nucleo.aplicacion.CuadroPredialParametrizado;
import kamayuk.rentas.nucleo.aplicacion.DeterminarPredial;
import kamayuk.rentas.nucleo.aplicacion.DeterminarPredialMasivo;
import kamayuk.rentas.nucleo.aplicacion.PadronPredialDelEjercicio;
import kamayuk.rentas.nucleo.aplicacion.RegistrarCorridaDeEmision;
import kamayuk.rentas.nucleo.aplicacion.RegistrarDeterminacionPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.OrigenDelAutovaluo;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada;
import kamayuk.rentas.nucleo.infraestructura.CorridaDeEmisionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.ValuacionRecibidaJdbc;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #362 — cuando manda la valuacion sellada, <b>la declarada se guarda al lado</b>, se publica, y el
 * siguiente recalculo no la confunde con la sellada.
 *
 * <h2>Lo que media la prueba que habia, y por que no bastaba</h2>
 *
 * <p>{@code DeterminarPredialTest} («la declarada se guarda al lado») comprobaba {@code
 * autovaluoDeclarado()} sobre el {@code PredioEnLaBase} <b>en memoria</b>. Esa cifra no llegaba a
 * ninguna tabla ni a ninguna respuesta, y el recalculo sin predios en el cuerpo tomaba como
 * «declarado» el autovaluo guardado —que era el sellado—, de modo que la discrepancia se borraba
 * hasta del objeto que la prometia.
 *
 * <h2>Lo que distingue esta siembra</h2>
 *
 * <p>Declarado <b>100 000,00</b> y sellado <b>180 000,00</b>: distintos. Con las dos cifras
 * iguales, ninguna de las tres pruebas distingue la declarada de la sellada. Todo va contra
 * PostgreSQL con {@link DeterminacionRepositoryJdbc} real, {@code simulacion = false}, y por HTTP
 * con {@link PredialController}: lo que se mira es <b>lo guardado</b> y <b>lo publicado</b>, no el
 * objeto.
 *
 * <p>Se monta como {@code DeterminarPredialContraLaValuacionDeLaBaseTest} (#358): cada componente
 * que lee o escribe la base pasa por un proxy con {@link TransactionInterceptor} sobre {@link
 * TenantTransactionManager}, como lo monta Spring. Dobles solo para lo que no es de esta base: el
 * padron de {@code catastro}, el directorio, los beneficios y el conjunto sellado.
 */
@DisplayName("#362 — la declarada se guarda al lado de la sellada, se publica y no se confunde")
class LaDeclaradaSeGuardaAlLadoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final long PREDIO = 11L;
    private static final long CONJUNTO_DE_LA_CORRIDA = 77L;
    private static final String DECLARADO = "100000.00";
    private static final String SELLADO = "180000.00";
    private static final AtomicInteger UBIGEOS = new AtomicInteger(362_000);
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    private static BaseDeDatosDePrueba base;
    private static DriverManagerDataSource pool;
    private static TenantTransactionManager gestor;
    private static DeterminacionRepositoryJdbc repositorio;
    private static TransactionTemplate transaccion;

    /** Una municipalidad por prueba: la masiva recorre el ejercicio entero de la suya. */
    private long municipalidad;

    private ResumenDeContribuyente contribuyente;
    private MockMvc mvc;
    private DeterminarPredial individual;
    private DeterminarPredialMasivo masivo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        // `kamayuk_app`, nunca el superusuario: el superusuario omite RLS (DAT-01 §0).
        pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        gestor = new TenantTransactionManager(pool);
        repositorio = new DeterminacionRepositoryJdbc(JdbcClient.create(pool));
        transaccion = new TransactionTemplate(gestor);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void sembrar() throws SQLException {
        String ubigeo = String.valueOf(UBIGEOS.incrementAndGet());
        municipalidad =
                DatosDePrueba.crearMunicipalidad(base, ubigeo, "Municipalidad de #362 " + ubigeo);
        contribuyente =
                new ResumenDeContribuyente(
                        crearContribuyente("C-362"), "C-362", "PRUEBA, DECLARANTE", "03620362");
        sellarLaValuacion(PREDIO, SELLADO);
        montar();
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "determinar con declarada 100 000 y sellada 180 000: la declarada queda escrita y"
                    + " publicada")
    void laDeclaradaQuedaEscritaYPublicada() throws Exception {
        JsonNode respuesta =
                determinarPorHttp(List.of(Map.of("predioId", PREDIO, "autovaluo", DECLARADO)));

        JsonNode predio = respuesta.at("/predios/0");
        assertThat(predio.at("/autovaluo").asString())
                .as("manda la sellada (#38, AC-2): eso no lo cambia #362")
                .isEqualTo(SELLADO);
        assertThat(predio.at("/autovaluoDeclarado").asString())
                .as("la respuesta del POST publica la declarada al lado de la sellada")
                .isEqualTo(DECLARADO);

        long id = respuesta.at("/id").asLong();
        assertThat(guardado(id).autovaluoDeclarado())
                .as("la declarada esta EN LA BASE, no solo en memoria")
                .isEqualTo(Dinero.de(DECLARADO));

        JsonNode guardada = leerPorHttp();
        assertThat(guardada.at("/id").asLong()).isEqualTo(id);
        assertThat(guardada.at("/predios/0/origenDelAutovaluo").asString()).isEqualTo("SELLADO");
        assertThat(guardada.at("/predios/0/autovaluoDeclarado").asString())
                .as("GET /determinaciones la lee de la fila y la publica")
                .isEqualTo(DECLARADO);
    }

    @Test
    @DisplayName(
            "recalcular sin predios en el cuerpo: la declarada sigue siendo 100 000, no 180 000")
    void elRecalculoNoTomaLaSelladaPorDeclarada() throws Exception {
        long primera =
                determinarPorHttp(List.of(Map.of("predioId", PREDIO, "autovaluo", DECLARADO)))
                        .at("/id")
                        .asLong();

        // El paso 3 del escenario del issue, sobre el objeto que lo prometia: hasta #362 el
        // «declarado» que el recalculo construia era 180 000.
        assertThat(
                        individual
                                .determinar(
                                        new DeterminarPredial.Peticion(
                                                EJERCICIO,
                                                contribuyente.codigo(),
                                                List.of(),
                                                ModalidadDelPredial.TRIMESTRAL,
                                                true),
                                        Observacion.de("Simulacion del recalculo (#362)"))
                                .predios()
                                .get(0)
                                .autovaluoDeclarado())
                .as("ni siquiera en memoria la sellada pasa por declarada")
                .isEqualTo(Dinero.de(DECLARADO));

        // El recalculo que el issue describe: nadie vuelve a teclear el padron.
        JsonNode respuesta = determinarPorHttp(List.of());

        long segunda = respuesta.at("/id").asLong();
        assertThat(segunda).as("es otra determinacion, no la primera").isNotEqualTo(primera);
        assertThat(respuesta.at("/predios/0/autovaluo").asString()).isEqualTo(SELLADO);
        assertThat(respuesta.at("/predios/0/autovaluoDeclarado").asString())
                .as("el «declarado» del recalculo sale de la declarada guardada, no del autovaluo")
                .isEqualTo(DECLARADO);
        assertThat(guardado(segunda).autovaluoDeclarado()).isEqualTo(Dinero.de(DECLARADO));
        assertThat(leerPorHttp().at("/predios/0/autovaluoDeclarado").asString())
                .isEqualTo(DECLARADO);
    }

    @Test
    @DisplayName("la corrida masiva tampoco toma la sellada por declarada")
    void laMasivaNoTomaLaSelladaPorDeclarada() throws Exception {
        long primera =
                determinarPorHttp(List.of(Map.of("predioId", PREDIO, "autovaluo", DECLARADO)))
                        .at("/id")
                        .asLong();

        DeterminarPredialMasivo.Corrida corrida =
                masivo.ejecutar(
                        new DeterminarPredialMasivo.Peticion(
                                EJERCICIO,
                                null,
                                null,
                                null,
                                null,
                                ModalidadDelPredial.TRIMESTRAL,
                                true,
                                false),
                        Observacion.de("Emision del predial 2026 (#362)"));

        assertThat(corrida.observados()).isEmpty();
        assertThat(corrida.determinados()).isEqualTo(1);
        Determinacion ultima =
                transaccion
                        .execute(
                                estado ->
                                        repositorio.ultimaPredialDe(EJERCICIO, contribuyente.id()))
                        .orElseThrow();
        assertThat(ultima.id()).as("la corrida escribio otra").isNotEqualTo(primera);
        assertThat(guardado(ultima.id()).autovaluoDeclarado())
                .as("la corrida lee lo declarado por su origen, no el autovaluo guardado")
                .isEqualTo(Dinero.de(DECLARADO));
    }

    // ------------------------------------------------------------------

    private JsonNode determinarPorHttp(List<Map<String, Object>> predios) throws Exception {
        String cuerpo =
                JSON.writeValueAsString(
                        Map.of(
                                "codContribuyente",
                                contribuyente.codigo(),
                                "ejercicio",
                                "2026",
                                "modalidad",
                                "TRIMESTRAL",
                                "simulacion",
                                false,
                                "observacion",
                                "Determinacion del predial 2026 (#362)",
                                "predios",
                                predios));
        String salida =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        JsonNode leida = JSON.readTree(salida);
        assertThat(leida.at("/id").asLong()).as("se asento: " + salida).isPositive();
        return leida;
    }

    private JsonNode leerPorHttp() throws Exception {
        String salida =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predial/determinaciones")
                                        .param("codContribuyente", contribuyente.codigo())
                                        .param("ejercicio", "2026"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(salida);
    }

    /**
     * Lo guardado, leido por el repositorio de verdad ({@code detalleDe}) y no por el objeto que
     * devolvio la determinacion: es la lectura que hacen el recalculo, la corrida y la consulta.
     */
    private DetalleDeterminacionPredio guardado(long determinacionId) {
        List<DetalleDeterminacionPredio> detalle =
                transaccion.execute(estado -> repositorio.detalleDe(determinacionId));
        assertThat(detalle).hasSize(1);
        DetalleDeterminacionPredio predio = detalle.get(0);
        assertThat(predio.origen()).isEqualTo(OrigenDelAutovaluo.SELLADO);
        assertThat(predio.autovaluo()).isEqualTo(Dinero.de(SELLADO));
        return predio;
    }

    private void montar() {
        LectorDeParametros lector = lectorDe(conjuntoConRedondeo());
        CuadroPredialParametrizado cuadro = new CuadroPredialParametrizado(lector);
        ValuacionRecibida valuaciones =
                comoLoMontaSpring(new ValuacionRecibidaJdbc(JdbcClient.create(pool)));
        PadronPredialDelEjercicio padron =
                comoLoMontaSpring(new PadronPredialDelEjercicio(repositorio));
        DirectorioDeContribuyentes directorio = new UnSoloContribuyente();
        individual =
                new DeterminarPredial(
                        padron,
                        (contribuyenteId, fecha) ->
                                contribuyenteId == contribuyente.id()
                                        ? List.of(
                                                new PredioDelContribuyente(
                                                        PREDIO,
                                                        "10001",
                                                        "URBANO",
                                                        "AV. GRAU 100",
                                                        Porcentaje.total()))
                                        : List.of(),
                        (predioId, fecha) -> Optional.empty(),
                        directorio,
                        cuadro,
                        valuaciones,
                        (contribuyenteId, fecha) -> List.of(),
                        comoLoMontaSpring(
                                new RegistrarDeterminacionPredial(
                                        repositorio, lector, registro -> {})),
                        RELOJ);
        RegistrarCorridaDeEmision rastro =
                comoLoMontaSpring(
                        new RegistrarCorridaDeEmision(
                                new CorridaDeEmisionRepositoryJdbc(
                                        JdbcClient.create(pool), RELOJ)));
        masivo =
                new DeterminarPredialMasivo(
                        padron,
                        individual,
                        directorio,
                        (predioId, fecha) -> Optional.empty(),
                        rastro,
                        comoLoMontaSpring(
                                new CandadoDeEmision(new CerradaConLoRecibido(valuaciones))),
                        RELOJ);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PredialController(
                                        individual,
                                        masivo,
                                        rastro,
                                        comoLoMontaSpring(
                                                new ConsultaDeLaDeterminacionPredial(
                                                        directorio, repositorio, cuadro)),
                                        RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                        .build();
    }

    /** Lo que Spring hace con un bean anotado: abre la transaccion SOLO donde hay anotacion. */
    @SuppressWarnings("unchecked")
    private static <T> T comoLoMontaSpring(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private long crearContribuyente(String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', '03620362', 'NATURAL',"
                                    + " 'PRUEBA, DECLARANTE', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /** La valuacion sellada, escrita como la escribe el ingestor (la de #358). */
    private void sellarLaValuacion(long predioId, String valorDelPredio) throws SQLException {
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);
            String evento = UUID.randomUUID().toString();
            try (PreparedStatement sentencia =
                    ingestor.prepareStatement(
                            "INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id,"
                                    + " secuencia, tipo, predio_id, aplicado_en, huella)"
                                    + " VALUES (?, CAST(? AS uuid), 1, 'VALUACION_SELLADA', ?,"
                                    + " now(), ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, evento);
                sentencia.setLong(3, predioId);
                sentencia.setString(4, sha256(evento));
                sentencia.executeUpdate();
            }
            try (PreparedStatement sentencia =
                    ingestor.prepareStatement(
                            "INSERT INTO valuacion_predio (municipalidad_id, ejercicio,"
                                    + " predio_id, fecha_de_corte, valor_terreno,"
                                    + " valor_construccion, valor_obras, valor_del_predio,"
                                    + " conjunto_id, reglas_version, reglas_aplicadas, huella,"
                                    + " evento_id, secuencia, recibida_en)"
                                    + " VALUES (?, 2026, ?, DATE '2025-12-31', CAST(? AS numeric),"
                                    + " 0, 0, CAST(? AS numeric), ?, 'v1', 'RT-001', ?,"
                                    + " CAST(? AS uuid), 1, now())")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setString(3, valorDelPredio);
                sentencia.setString(4, valorDelPredio);
                sentencia.setLong(5, CONJUNTO_DE_LA_CORRIDA);
                sentencia.setString(6, sha256("valuacion-" + predioId));
                sentencia.setString(7, evento);
                sentencia.executeUpdate();
            }
            ingestor.commit();
        }
    }

    private static ParametrosSellados conjuntoConRedondeo() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .texto("REDONDEO", "CUOTA", "HALF_UP")
                .construir();
    }

    private static LectorDeParametros lectorDe(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(CONJUNTO_DE_LA_CORRIDA);
            }
        };
    }

    /**
     * La valuacion de la base, con su corrida dada por cerrada sobre lo que de verdad llego.
     *
     * <p>El candado de emision (ADR-0027 §2) no es lo que se mide aqui: el cierre dice haber
     * emitido exactamente lo recibido, y las cifras de cada predio siguen saliendo de la base.
     */
    private record CerradaConLoRecibido(ValuacionRecibida base) implements ValuacionRecibida {

        @Override
        public Optional<CierreDeCorrida> cierreDe(Ejercicio ejercicio) {
            return Optional.of(
                    new CierreDeCorrida(
                            1L,
                            CONJUNTO_DE_LA_CORRIDA,
                            LocalDate.of(2025, 12, 31),
                            "v1",
                            (int) base.valuacionesRecibidasDe(ejercicio),
                            base.huellaDeLoRecibido(ejercicio)));
        }

        @Override
        public Optional<ValuacionSellada> delPredio(Ejercicio ejercicio, long predioId) {
            return base.delPredio(ejercicio, predioId);
        }

        @Override
        public Map<Long, ValuacionSellada> deLosPredios(Ejercicio ejercicio, List<Long> predios) {
            return base.deLosPredios(ejercicio, predios);
        }

        @Override
        public long valuacionesRecibidasDe(Ejercicio ejercicio) {
            return base.valuacionesRecibidasDe(ejercicio);
        }

        @Override
        public String huellaDeLoRecibido(Ejercicio ejercicio) {
            return base.huellaDeLoRecibido(ejercicio);
        }
    }

    /** El directorio es de `contribuyentes`: aqui solo hace falta que conteste por el codigo. */
    private final class UnSoloContribuyente implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return contribuyente.codigo().equals(codigo)
                    ? Optional.of(contribuyente)
                    : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return ids.contains(contribuyente.id())
                    ? Map.of(contribuyente.id(), contribuyente)
                    : Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException(imposible);
        }
    }
}
