package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
import kamayuk.rentas.fiscalizacion.infraestructura.DeteccionRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Por qué se puede ordenar «Omisos y subvaluadores», de HTTP a PostgreSQL (#608).
 *
 * <h2>El defecto que esto cierra</h2>
 *
 * <p>La pantalla dibuja siete columnas y desde #546 se podía ordenar por <b>una</b>: las otras seis
 * contestaban {@code 422 ORDEN_NO_ADMITIDO}. Y no es una lista cualquiera —es de la que salen los
 * programas de fiscalización, y en Catacaos tiene 14 422 filas en 722 páginas—, así que «los
 * predios con más diferencia de área», que es el criterio con el que se elige a quién visitar,
 * exigía recorrer las 722 páginas a mano.
 *
 * <p>Entran los dos que <b>no piden ningún dato nuevo</b>: {@code sector}, que la fila ya publica y
 * la consulta ya filtraba, y {@code diferenciaDeArea}, que se deriva de dos columnas que la
 * consulta ya trae. {@code impuestoOmitidoS} <b>no</b>: es {@code null} en todas las filas mientras
 * D-02a siga abierta (#198), así que ordenar por él no ordena nada.
 *
 * <h2>Por qué hasta la base, y por HTTP</h2>
 *
 * <p>Porque el orden lo produce el motor —{@code ORDER BY} no puede llamar a un método de Java— y
 * con un doble lo escribiría la propia prueba. El {@code 422} y su {@code detalles}, en cambio,
 * sólo existen al otro lado de {@link ManejadorDeErrores}: es la misma frontera que el issue midió
 * con {@code curl} contra el backend local.
 *
 * <p>La conexión es la de {@code sgtm_app}: un superusuario omite RLS <b>incluso con {@code FORCE
 * ROW LEVEL SECURITY}</b> (DAT-01 §0, primer hallazgo), y con {@code sgtm_owner} no basta —FORCE lo
 * sujeta a la política igual (#537, #545)—. Por eso el padrón de la vecina lleva a propósito el
 * sector mayor y la diferencia mayor de las dos municipalidades: si la conexión omitiera RLS,
 * <b>encabezaría los dos órdenes descendentes</b> y la fuga saldría en la aserción del AC 1 y en la
 * del AC 2, no sólo en una prueba aparte.
 *
 * <p>El caso de uso se envuelve con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotación</b> como el contenedor: un {@code TransactionTemplate}
 * incondicional dejaría pasar la mutación de quitarle el {@code @Transactional}, que es el modo de
 * fallo que #486 existe para impedir.
 *
 * <h2>Y el padrón no puede estar vacío</h2>
 *
 * <p>{@code RepositorioJdbc.paginar} cuenta primero y devuelve la página vacía <b>sin llegar a
 * armar el {@code ORDER BY}</b>, así que sobre un padrón sin filas un {@code ordenarPor} inventado
 * contesta 200. Las seis filas sembradas no son decoración: sin ellas, el AC 3 y el AC 4 pasarían
 * sin que ninguna lista blanca existiera.
 */
@DisplayName("#608 — Por que se ordena la deteccion de omisos, de HTTP a PostgreSQL")
class OrdenDeLaDeteccionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

    private static final String EJERCICIO = "2026";

    /** De dónde arranca el código de referencia catastral de cada fila sembrada. */
    private static final int PRIMER_CODIGO = 800;

    /**
     * El padrón sembrado, y de aquí sale también lo que la función pura tiene que contestar.
     *
     * <p><b>Los dos lados entran de la SIEMBRA, no de la fila devuelta</b> (el patrón de #545):
     * derivar el área declarada de lo que la consulta contestó sería circular —la consulta se
     * estaría dando la razón a sí misma—.
     *
     * <p>Está montado para que los dos órdenes que este issue añade <b>no coincidan</b>: el sector
     * mayor lo tiene el primero y la diferencia mayor la tiene el cuarto, que además no tiene
     * sector. Un orden que se quedara en el de siempre —el código— no podría pasar ninguna de las
     * dos.
     */
    private static final List<Sembrado> PADRON =
            List.of(
                    // Declaro exactamente la ficha vigente: diferencia 0,00.
                    new Sembrado("S-03", "300.00", "300.00"),
                    // Declaro una ficha anterior mas pequena: diferencia 120,00.
                    new Sembrado("S-01", "500.00", "380.00"),
                    // OMISO: no declaro, asi que no hay diferencia que calcular.
                    new Sembrado("S-02", "300.00", null),
                    // La mayor diferencia del padron, y sin sector.
                    new Sembrado(null, "450.00", "200.00"),
                    // Otro cero, para que el desempate tenga algo que romper.
                    new Sembrado("S-01", "200.00", "200.00"),
                    // Declaro de MAS de lo que el catastro tiene hoy: la diferencia es cero, no
                    // negativa. Es lo que mide la rama «<=» de la transcripcion, y sin ese predio
                    // la rama no se podria distinguir de restar y ya esta.
                    new Sembrado("S-02", "200.00", "500.00"));

    private static final String P1 = codigoDe(0);
    private static final String P2 = codigoDe(1);
    private static final String P3 = codigoDe(2);
    private static final String P4 = codigoDe(3);
    private static final String P5 = codigoDe(4);
    private static final String P6 = codigoDe(5);

    /** El código del predio de la municipalidad vecina. Ninguna respuesta puede llevarlo. */
    private static final String DE_LA_VECINA = codigoDe(90);

    private static final Pattern CODIGO_EN_LA_RESPUESTA =
            Pattern.compile("\"codRefCatastral\":\"(\\d+)\"");

    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);
    private static final AtomicInteger SIGUIENTE_DJ = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260801", "Municipalidad del orden");
        municipalidadB = crearMunicipalidad("260802", "Municipalidad vecina");

        long titularA = crearContribuyente(municipalidadA, "O-000001", "70800001");
        for (String sector : sectoresDe(PADRON)) {
            crearSector(municipalidadA, sector);
        }
        for (int i = 0; i < PADRON.size(); i++) {
            sembrar(municipalidadA, titularA, codigoDe(i), PADRON.get(i));
        }

        // La vecina, con el sector mayor y la diferencia mayor de las dos municipalidades:
        // sin RLS encabezaria los dos ordenes descendentes.
        long titularB = crearContribuyente(municipalidadB, "O-B00001", "70800002");
        crearSector(municipalidadB, "Z-99");
        sembrar(municipalidadB, titularB, DE_LA_VECINA, new Sembrado("Z-99", "9000.00", "10.00"));

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OmisosController(
                                        envolver(
                                                new DeteccionDeOmisos(
                                                        new DeteccionRepositoryJdbc(jdbc),
                                                        new TitularesDeMentira()),
                                                gestor),
                                        envolver(
                                                new EstadoDeCuentaDeFiscalizacion(
                                                        new LiquidacionesEnMemoria(),
                                                        (contribuyenteId, fecha) -> List.of()),
                                                gestor),
                                        new PadronVacio(),
                                        RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-09", "10.0.0.9"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("AC 1 — se puede ordenar por sector")
    class ElSectorOrdena {

        @Test
        @DisplayName("descendente abre por el sector MAYOR del padron")
        void descendenteAbrePorElSectorMayor() throws Exception {
            MvcResult resultado = omisos("sector", "DESCENDENTE");

            assertThat(resultado.getResponse().getStatus())
                    .as("hasta #608 esto era 422 ORDEN_NO_ADMITIDO")
                    .isEqualTo(200);
            assertThat(codigosDe(resultado))
                    .as("S-03, S-02, S-02, S-01, S-01 y el que no tiene sector al final")
                    .containsExactly(P1, P3, P6, P2, P5, P4);
        }

        @Test
        @DisplayName("ascendente abre por el sector MENOR, y el desempate es total")
        void ascendenteAbrePorElSectorMenor() throws Exception {
            MvcResult resultado = omisos("sector", "ASCENDENTE");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(codigosDe(resultado))
                    .as(
                            "los dos S-01 empatan y los rompe el desempate por predio_id, que es el"
                                    + " orden de siembra")
                    .containsExactly(P2, P5, P3, P6, P1, P4);
        }

        @Test
        @DisplayName("el predio SIN sector no encabeza el descendente")
        void elPredioSinSectorNoEncabeza() throws Exception {
            List<String> codigos = codigosDe(omisos("sector", "DESCENDENTE"));

            assertThat(codigos.get(0))
                    .as(
                            "en PostgreSQL DESC implica NULLS FIRST: sin el NULLS LAST, «de mayor a"
                                    + " menor» abre por el predio que no tiene sector")
                    .isNotEqualTo(P4);
            assertThat(codigos.get(codigos.size() - 1)).isEqualTo(P4);
        }
    }

    @Nested
    @DisplayName("AC 2 — se puede ordenar por la diferencia de area, y el nulo no se cuela delante")
    class LaDiferenciaOrdena {

        @Test
        @DisplayName("descendente abre por el predio con MAS metros sin declarar")
        void descendenteAbrePorElQueMasDebe() throws Exception {
            MvcResult resultado = omisos("diferenciaDeArea", "DESCENDENTE");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(codigosDe(resultado))
                    .as("250,00 · 120,00 · 0,00 · 0,00 · 0,00 · el que no se puede calcular")
                    .containsExactly(P4, P2, P1, P5, P6, P3);
        }

        @Test
        @DisplayName("el predio sin area declarada no encabeza el descendente")
        void elQueNoSePuedeCalcularNoEncabeza() throws Exception {
            List<String> codigos = codigosDe(omisos("diferenciaDeArea", "DESCENDENTE"));

            assertThat(codigos.get(0))
                    .as(
                            "sin NULLS LAST encabeza el predio cuya diferencia no se puede"
                                    + " calcular, que es el que menos dice para decidir a quien"
                                    + " visitar")
                    .isNotEqualTo(P3);
            assertThat(codigos.get(codigos.size() - 1)).isEqualTo(P3);
        }

        /**
         * El ascendente entero, y <b>no</b> por el nulo.
         *
         * <p>Conviene decir lo que esta prueba NO guarda, porque su sitio invita a lo contrario: en
         * PostgreSQL {@code ASC} ya implica {@code NULLS LAST}, asi que quitar el {@code
         * conNulosAlFinal("diferencia_de_area")} la deja en <b>verde</b> — la que caza esa rotura
         * es la del descendente, que es ademas el sentido que la pantalla usa.
         *
         * <p>Lo que si guarda es la rama {@code <=} de la transcripcion: es una de las dos unicas
         * que la cazan, porque un negativo cae detras de los ceros en el descendente y alli no se
         * distingue de un cero.
         */
        @Test
        @DisplayName("el ascendente completo: los seis en orden, con el nulo detras")
        void ascendenteDejaElNuloAlFinal() throws Exception {
            assertThat(codigosDe(omisos("diferenciaDeArea", "ASCENDENTE")))
                    .containsExactly(P1, P5, P6, P2, P4, P3);
        }

        /**
         * La transcripción SQL de {@link ComparacionHalladoDeclarado#diferenciaDeArea} y la función
         * pura tienen que dar lo mismo, y aquí lo que se compara es el <b>orden</b>: es lo único
         * que la columna decide, porque la cifra que la fila enseña la sigue derivando la función
         * pura en {@code FilaDeOmisos}.
         *
         * <p>Es la prueba gemela de {@code LaCondicionCoincideConLaFuncionPura}: sin ella, esta
         * segunda transcripción al motor podría separarse de su original sin que nada lo dijera,
         * que es el defecto que #397 midió con el «Estado» de la infracción administrativa.
         */
        @Test
        @DisplayName("el orden que produce el motor es el que produce la funcion pura")
        void elOrdenDelMotorEsElDeLaFuncionPura() throws Exception {
            assertThat(codigosDe(omisos("diferenciaDeArea", "DESCENDENTE")))
                    .as("descendente, con los nulos al final y el desempate por orden de siembra")
                    .containsExactlyElementsOf(ordenDeLaFuncionPura(true));
            assertThat(codigosDe(omisos("diferenciaDeArea", "ASCENDENTE")))
                    .containsExactlyElementsOf(ordenDeLaFuncionPura(false));
        }
    }

    @Nested
    @DisplayName("AC 3 y AC 4 — lo que sigue sin admitirse, y lo dice")
    class LoQueSigueSin {

        @Test
        @DisplayName("impuestoOmitidoS sigue siendo 422 y el cuerpo nombra el campo pedido")
        void elImpuestoOmitidoSigueSiendo422() throws Exception {
            MvcResult resultado = omisos("impuestoOmitidoS", "DESCENDENTE");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"ORDEN_NO_ADMITIDO\"")
                    .contains("Campo pedido: impuestoOmitidoS");
        }

        @Test
        @DisplayName("y no se admite porque la columna es null en TODAS las filas hasta D-02a")
        void laColumnaDelImpuestoSigueVacia() throws Exception {
            String cuerpo =
                    omisos("codRefCatastral", "ASCENDENTE").getResponse().getContentAsString();

            assertThat(cuerpo)
                    .as(
                            "ordenar por una columna que es null en todas las filas no ordena nada:"
                                    + " las dos direcciones devolverian la misma primera fila")
                    .contains("\"impuestoOmitidoS\":null")
                    .doesNotContain("\"impuestoOmitidoS\":\"");
        }

        @Test
        @DisplayName("el nombre interno sigue siendo 422 y el publicado sigue siendo 200 (#546)")
        void elNombreInternoNoSeConfundeConElPublicado() throws Exception {
            assertThat(omisos("codigoRefCatastral", "ASCENDENTE").getResponse().getStatus())
                    .as(
                            "aceptar los dos deja a un cliente funcionando por accidente hasta el"
                                    + " dia que el repositorio renombre su columna")
                    .isEqualTo(422);
            assertThat(omisos("codRefCatastral", "ASCENDENTE").getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("y los dos nombres nuevos tampoco traen su nombre interno de regalo")
        void losDosNombresNuevosNoTraenSuInterno() throws Exception {
            assertThat(omisos("sectorCodigo", "ASCENDENTE").getResponse().getStatus())
                    .as(
                            "«sectorCodigo» es el camelCase automatico que publicandoComo retira, y"
                                    + " es el nombre que ninguna fila lleva. La columna cruda"
                                    + " —«sector_codigo»— SI sigue admitida, como en sobre(...) y como"
                                    + " su javadoc dice: lo que se retira es el camelCase, no la"
                                    + " columna")
                    .isEqualTo(422);
        }
    }

    @Nested
    @DisplayName("Con quien habla el pool, y que padron ordena")
    class ElAislamiento {

        @Test
        @DisplayName("la prueba se conecta como sgtm_app, no como superusuario ni como el dueno")
        void seConectaComoSgtmApp() {
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .as(
                            "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                    + " todo lo de este archivo pasaria sin verificar nada. Con"
                                    + " sgtm_owner NO basta: FORCE lo sujeta a la politica igual,"
                                    + " asi que la rotura clasica escrita con el dueño sale VERDE"
                                    + " (#537, #545)")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }

        @Test
        @DisplayName("el predio de la vecina no entra en ninguno de los dos ordenes nuevos")
        void elPredioDeLaVecinaNoEntra() throws Exception {
            assertThat(codigosDe(omisos("sector", "DESCENDENTE")))
                    .as("tiene el sector mayor de las dos municipalidades: sin RLS encabezaria")
                    .doesNotContain(DE_LA_VECINA);
            assertThat(codigosDe(omisos("diferenciaDeArea", "DESCENDENTE")))
                    .as("y la diferencia mayor: 8 990,00 m2 sin declarar")
                    .doesNotContain(DE_LA_VECINA);
        }
    }

    // ------------------------------------------------------------------

    private static MvcResult omisos(String ordenarPor, String direccion) throws Exception {
        ingestar(municipalidadA);
        ingestar(municipalidadB);
        return mvc.perform(
                        get("/rentas/api/v1/fiscalizacion/omisos")
                                .param("ejercicio", EJERCICIO)
                                .param("ordenarPor", ordenarPor)
                                .param("direccion", direccion))
                .andReturn();
    }

    /** Los códigos de referencia catastral de la respuesta, en el orden en que salieron. */
    private static List<String> codigosDe(MvcResult resultado) throws Exception {
        Matcher encontrados =
                CODIGO_EN_LA_RESPUESTA.matcher(resultado.getResponse().getContentAsString());
        List<String> codigos = new ArrayList<>();
        while (encontrados.find()) {
            codigos.add(encontrados.group(1));
        }
        return codigos;
    }

    /**
     * El orden que la función pura produce sobre el padrón sembrado: por la diferencia, con los
     * nulos al final, y desempatando por el orden de siembra.
     *
     * <p>El desempate del repositorio es {@code predio_id ASC} y ese identificador no lo publica
     * ninguna fila (#546); el orden de siembra es su equivalente aquí, porque los seis predios se
     * insertan en este mismo orden y {@code predio.id} es una secuencia.
     */
    private static List<String> ordenDeLaFuncionPura(boolean descendente) {
        Comparator<AreaM2> porValor =
                descendente ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<Integer> porDiferencia =
                Comparator.comparing(
                        (Integer indice) -> PADRON.get(indice).diferencia(),
                        Comparator.nullsLast(porValor));

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < PADRON.size(); i++) {
            indices.add(i);
        }
        indices.sort(porDiferencia.thenComparing(Comparator.naturalOrder()));

        List<String> codigos = new ArrayList<>();
        for (Integer indice : indices) {
            codigos.add(codigoDe(indice));
        }
        return codigos;
    }

    /** Envuelve el objetivo en un proxy transaccional que OBEDECE a la anotacion. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Siembra ----------

    /**
     * Un predio del padrón sembrado.
     *
     * @param sector el código de su sector, o {@code null} si no tiene ninguno
     * @param areaVigente el área de la ficha que rige hoy
     * @param areaDeclarada el área de la ficha que su declaración referencia; {@code null} si no
     *     declaró, que es el caso mayoritario
     */
    private record Sembrado(
            @Nullable String sector, String areaVigente, @Nullable String areaDeclarada) {

        @Nullable AreaM2 diferencia() {
            return ComparacionHalladoDeclarado.diferenciaDeArea(
                    areaDeclarada == null ? null : AreaM2.de(areaDeclarada),
                    AreaM2.de(areaVigente));
        }
    }

    private static String codigoDe(int indice) {
        return String.format("%018d", PRIMER_CODIGO + indice);
    }

    /** Los sectores distintos del padron: {@code sector.codigo} es unico por municipalidad. */
    private static Set<String> sectoresDe(List<Sembrado> padron) {
        Set<String> codigos = new TreeSet<>();
        for (Sembrado fila : padron) {
            if (fila.sector() != null) {
                codigos.add(fila.sector());
            }
        }
        return codigos;
    }

    private static void sembrar(long municipalidadId, long titular, String codigo, Sembrado fila) {
        long predioId = crearPredio(municipalidadId, codigo, fila.sector());

        if (fila.areaDeclarada() == null) {
            crearFicha(municipalidadId, predioId, fila.areaVigente(), null);
            return;
        }
        if (fila.areaDeclarada().equals(fila.areaVigente())) {
            long vigente = crearFicha(municipalidadId, predioId, fila.areaVigente(), null);
            crearDeclaracion(municipalidadId, predioId, titular, vigente);
            return;
        }
        // Declaro una version anterior, mas pequena: es la ficha que la DJ referencia.
        long declarada =
                crearFicha(
                        municipalidadId,
                        predioId,
                        fila.areaDeclarada(),
                        LocalDate.of(2020, 12, 31));
        // Desde 2021: la version vigente empieza cuando la declarada deja de regir. Abrirla en
        // 2020 como la cerrada dejaba las dos cubriendo todo ese ano —el padron imposible que
        // `ficha_vigencias_no_se_pisan` (V72, #669) rechaza— y la deteccion veria el predio dos
        // veces.
        crearFichaDesde(
                municipalidadId, predioId, fila.areaVigente(), LocalDate.of(2021, 1, 1), null);
        crearDeclaracion(municipalidadId, predioId, titular, declarada);
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return comoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static void crearSector(long municipalidadId, String codigo) {
        comoApp(
                municipalidadId,
                "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidadId,
                codigo);
    }

    private static long crearPredio(
            long municipalidadId, String codigo, @Nullable String sectorCodigo) {
        if (sectorCodigo == null) {
            return comoApp(
                    municipalidadId,
                    "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                            + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                    municipalidadId,
                    codigo);
        }
        return comoApp(
                municipalidadId,
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector_de_prueba WHERE municipalidad_id = ? AND codigo = ?))"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                municipalidadId,
                sectorCodigo);
    }

    private static long crearFicha(
            long municipalidadId, long predioId, String area, @Nullable LocalDate hasta) {
        return crearFichaDesde(municipalidadId, predioId, area, LocalDate.of(2020, 1, 1), hasta);
    }

    private static long crearFichaDesde(
            long municipalidadId,
            long predioId,
            String area,
            LocalDate desde,
            @Nullable LocalDate hasta) {
        return comoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, vigencia_hasta, origen,"
                        + " documento_origen, observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, ?, 'CASA_HABITACION', ?, ?,"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement(),
                new BigDecimal(area),
                desde,
                hasta);
    }

    private static void crearDeclaracion(
            long municipalidadId, long predioId, long contribuyenteId, long fichaId) {
        comoApp(
                municipalidadId,
                "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                        + " contribuyente_id, tipo, predio_id, ficha_catastral_id,"
                        + " fecha_presentacion, fecha_limite, fuera_de_plazo, estado,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, ?, "
                        + EJERCICIO
                        + ", ?, 'PU', ?, ?, DATE '2026-02-20', DATE '2026-02-28', false,"
                        + " 'PRESENTADA', 'siembra', 'Siembra de la prueba') RETURNING id",
                municipalidadId,
                "DJ-608-" + SIGUIENTE_DJ.getAndIncrement(),
                contribuyenteId,
                predioId,
                fichaId);
    }

    private static long comoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /**
     * El padrón de contribuyentes no interviene: los titulares los resuelve {@link
     * TitularesDeMentira}, que no devuelve ninguno. Lo que este archivo mide es el orden de las
     * filas, y el titular no ordena nada.
     */
    private static final class PadronVacio implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /**
     * Hace correr al ingestor de la proyeccion de catastro antes de preguntar (P5C).
     *
     * <p>Desde la separacion, la deteccion de omisos lee {@code predio_ref} y {@code ficha_ref} y
     * no las tablas del vecino: la consulta que paginaba y contaba cruzando cuatro tablas ajenas ya
     * no puede existir con dos bases (ADR-0029, y #631 midio que componerla en memoria contesta
     * «722 paginas, 14 422 elementos» y cero filas en todas). Esta llamada es el paso que en
     * produccion dispara un evento.
     */
    private static void ingestar(long municipalidadId) {
        try {
            kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, municipalidadId);
        } catch (java.sql.SQLException noSePudo) {
            throw new IllegalStateException("No se pudo proyectar el catastro", noSePudo);
        }
    }
}
