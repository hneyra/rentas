package kamayuk.rentas.licencias.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.CesarAnuncio;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeAnuncios;
import kamayuk.rentas.licencias.aplicacion.RegistrarAnuncio;
import kamayuk.rentas.licencias.aplicacion.RenovarAnuncio;
import kamayuk.rentas.licencias.aplicacion.TasaDeAnunciosParametrizada;
import kamayuk.rentas.licencias.dobles.AnunciosEnMemoria;
import kamayuk.rentas.licencias.dobles.LibroDeMentira;
import kamayuk.rentas.licencias.dobles.LicenciasEnMemoria;
import kamayuk.rentas.licencias.dobles.MovimientosDeAnuncioEnMemoria;
import kamayuk.rentas.licencias.dobles.PadronDeMentira;
import kamayuk.rentas.licencias.dobles.TarifasDeMentira;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeAnuncio;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #51 — Capa web: el transporte y los codigos de respuesta, no la persistencia —eso lo verifica
 * {@code AnunciosYPropagandaJdbcTest} contra PostgreSQL real—.
 *
 * <p>Lo que si se prueba aqui, y no alla:
 *
 * <ul>
 *   <li><b>201 contra 200.</b> La autorizacion nace, o la peticion era un reintento ya atendido.
 *       Son cosas distintas y quien reintenta merece saber cual le paso; y el reintento <b>no</b>
 *       vuelve a pedirle el cargo al libro, que es lo que cuenta {@link LibroDeMentira}.
 *   <li><b>422 con la llave dentro</b> cuando la ordenanza sellada no tarifa esa clase. Es un dato
 *       de configuracion que falta —D-02b, #199—, no un sistema roto: quien opera tiene que poder
 *       pedirlo en vez de recibir «error interno» y un numero de incidencia.
 *   <li><b>409 cuando la peticion esta bien y lo que no la admite es el estado</b> del anuncio:
 *       cesado, ya cesado, ya devengado.
 *   <li><b>Sin observacion no se escribe</b> (regla 10, RNF-052), en los cuatro verbos.
 *   <li>Y que <b>la tasa no viaja en el cuerpo</b>: si viajara, cualquiera autorizaria un panel por
 *       un sol cambiando un numero en la peticion.
 * </ul>
 */
@DisplayName("Capa web — anuncios y propaganda")
class AnuncioControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.anuncios";
    private static final String NUMERO_DE_LICENCIA = "LF-2026-000001";

    private final AnunciosEnMemoria anuncios = new AnunciosEnMemoria();
    private final MovimientosDeAnuncioEnMemoria movimientos = new MovimientosDeAnuncioEnMemoria();
    private final LicenciasEnMemoria licencias = new LicenciasEnMemoria();
    private final LibroDeMentira libro = new LibroDeMentira();

    private final PadronDeMentira padron =
            new PadronDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "PENA GARCIA, LUIS", "DNI 1234"));

    /** La ordenanza de la prueba: tarifa el panel y el letrero, y NO tarifa el toldo. */
    private final MockMvc mvc =
            montar(
                    new TarifasDeMentira()
                            .con(ClaseDeAnuncio.PANEL, "90.00")
                            .con(ClaseDeAnuncio.LETRERO, "45.00"));

    /** El mismo controlador con un conjunto sellado que no tarifa NADA. */
    private final MockMvc mvcSinTarifas = montar(new TarifasDeMentira());

    /**
     * El mismo controlador sin <b>ningun</b> conjunto sellado: lo que ocurre hoy en todas las
     * municipalidades con D-02a abierta (#562). No es lo mismo que el anterior —ahi hay conjunto y
     * no tarifa la clase— y hasta este issue salia como 500 con identificador de incidencia.
     */
    private final MockMvc mvcSinSellar =
            montar(
                    new TarifasDeMentira()
                            .con(ClaseDeAnuncio.PANEL, "90.00")
                            .con(ClaseDeAnuncio.LETRERO, "45.00")
                            .sinSellar());

    private MockMvc montar(TarifasDeMentira tarifas) {
        TasaDeAnunciosParametrizada tasas = new TasaDeAnunciosParametrizada(tarifas);
        return MockMvcBuilders.standaloneSetup(
                        new AnuncioController(
                                new ConsultaDeAnuncios(anuncios, movimientos, padron),
                                new RegistrarAnuncio(
                                        anuncios,
                                        movimientos,
                                        licencias,
                                        padron,
                                        tasas,
                                        libro,
                                        PlantillaDeNumeroDeAnuncio.POR_OMISION,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new RenovarAnuncio(
                                        anuncios,
                                        movimientos,
                                        tasas,
                                        libro,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new CesarAnuncio(
                                        anuncios,
                                        movimientos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /** El origen lo fija el borde de la aplicacion; aqui no hay borde, asi que se fija a mano. */
    @BeforeEach
    void sembrar() {
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
        licencias.emitir(
                new LicenciaDeFuncionamiento(
                        null,
                        NUMERO_DE_LICENCIA,
                        7L,
                        4200L,
                        null,
                        "BODEGA SAN MARTIN",
                        "AV. GRAU 100",
                        new AreaM2(new BigDecimal("45.50")),
                        TipoDeLicencia.DEFINITIVA,
                        "CV",
                        20,
                        HOY,
                        null,
                        11L,
                        12L,
                        "EXP-2026-0001",
                        HOY,
                        Instant.parse("2026-03-16T10:00:00Z"),
                        "prueba",
                        Observacion.de("Siembra de la prueba"),
                        List.of(new GiroDeLaLicencia(1L, "47111", "COMERCIO", true, true))));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("Registrar")
    class Registrar {

        @Test
        @DisplayName("numera desde el correlativo, responde 201 y pide UN cargo")
        void registraYGeneraLaDeuda() throws Exception {
            String cuerpo = registrar(mvc, null, 201);

            assertThat(cuerpo).contains("\"nroAutorizacion\":\"AN-2026-000001\"");
            assertThat(cuerpo).contains("\"acto\":\"AUTORIZACION\"");
            assertThat(cuerpo).contains("\"yaExistia\":false");
            assertThat(cuerpo)
                    .as(
                            "la referencia del cargo sale en la respuesta: es como se comprueba desde"
                                    + " fuera que un reintento no genero un segundo")
                    .contains("\"referenciaDelCargo\":\"ANUNCIO-AN-2026-000001-2026\"");
            assertThat(cuerpo)
                    .as("y la tasa lleva su fecha pegada (regla 9, RNF-075)")
                    .contains("\"tasa\":{\"importe\":\"90.00\",\"actualizadoA\":\"2026-03-16\"}");

            assertThat(libro.cuantos()).isEqualTo(1);
            LibroDeMentira.CargoPedido cargo = libro.pedidos().get(0);
            assertThat(cargo.tributo()).isEqualTo(RegistrarAnuncio.TRIBUTO);
            assertThat(cargo.referenciaExterna()).isEqualTo("ANUNCIO-AN-2026-000001-2026");
            assertThat(cargo.monto().valor()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(cargo.fechaValor()).isEqualTo(HOY);
        }

        @Test
        @DisplayName("el reintento con la misma Idempotency-Key responde 200 y no pide otro cargo")
        void elReintento() throws Exception {
            registrar(mvc, "IDEM-51", 201);
            String segunda = registrar(mvc, "IDEM-51", 200);

            assertThat(segunda).contains("\"yaExistia\":true");
            assertThat(segunda).contains("\"nroAutorizacion\":\"AN-2026-000001\"");
            assertThat(libro.cuantos())
                    .as("un doble clic no puede costarle al administrado dos tasas")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("dos registros distintos toman correlativos distintos")
        void dosRegistros() throws Exception {
            registrar(mvc, null, 201);
            assertThat(registrar(mvc, null, 201))
                    .contains("\"nroAutorizacion\":\"AN-2026-000002\"");
            assertThat(libro.cuantos()).isEqualTo(2);
        }

        @Test
        @DisplayName("sin observacion no se registra: 422 (regla 10)")
        void sinObservacion() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/autorizaciones/anuncios",
                            cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, null),
                            null,
                            422);

            assertThat(cuerpo).contains("regla 10");
            assertThat(libro.cuantos()).as("y no se pide ningun cargo").isZero();
        }

        @Test
        @DisplayName("una clase que la ordenanza no tarifa: 422 CON la llave que falta")
        void sinTarifa() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/autorizaciones/anuncios",
                            cuerpoDeRegistro(ClaseDeAnuncio.TOLDO, null, "Se autoriza el toldo"),
                            null,
                            422);

            assertThat(cuerpo)
                    .as(
                            "quien opera tiene que poder pedir el parametro, no un identificador de"
                                    + " incidencia")
                    .contains("TASA_ANUNCIO:TOLDO")
                    .contains("#199");
            assertThat(cuerpo)
                    .as("#691 — y la llave, legible por programa")
                    .contains(
                            "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                    + "\"llave\":\"TASA_ANUNCIO:TOLDO\"}");
            assertThat(libro.cuantos()).isZero();
        }

        @Test
        @DisplayName("y con un conjunto que no tarifa nada, tampoco el panel")
        void conjuntoSinTarifas() throws Exception {
            String cuerpo =
                    envio(
                            mvcSinTarifas,
                            "/rentas/api/v1/autorizaciones/anuncios",
                            cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza el panel"),
                            null,
                            422);

            assertThat(cuerpo).contains("TASA_ANUNCIO:PANEL");
        }

        @Test
        @DisplayName("un titular que el padron no tiene: 404")
        void titularDesconocido() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/autorizaciones/anuncios",
                            cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza")
                                    .replace("C-0007", "C-9999"),
                            null,
                            404);

            assertThat(cuerpo).contains("C-9999");
        }

        @Test
        @DisplayName("un establecimiento que no existe: 404")
        void establecimientoDesconocido() throws Exception {
            envio(
                    mvc,
                    "/rentas/api/v1/autorizaciones/anuncios",
                    cuerpoDeRegistro(ClaseDeAnuncio.PANEL, "LF-2026-999999", "Se autoriza"),
                    null,
                    404);
        }

        @Test
        @DisplayName("con establecimiento, el anuncio hereda su predio")
        void conEstablecimiento() throws Exception {
            registrar(
                    mvc,
                    null,
                    201,
                    cuerpoDeRegistro(ClaseDeAnuncio.PANEL, NUMERO_DE_LICENCIA, "Se autoriza"));

            assertThat(libro.pedidos().get(0).predioId())
                    .as("un anuncio colgado de un local esta donde el local")
                    .isEqualTo(4200L);
        }

        @Test
        @DisplayName("la clase es obligatoria y su vocabulario cerrado: 422")
        void claseInvalida() throws Exception {
            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios",
                                    cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza")
                                            .replace("\"PANEL\"", "\"CARTEL_LUMINOSO\""),
                                    null,
                                    422))
                    .contains("PANTALLA_DIGITAL");
            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios",
                                    cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza")
                                            .replace(
                                                    "\"claseAnuncio\":\"PANEL\"",
                                                    "\"claseAnuncio\":\"\""),
                                    null,
                                    422))
                    .contains("de que clase es el anuncio");
        }

        @Test
        @DisplayName("la tasa NO viaja en el cuerpo: mandarla no cambia lo que se cobra")
        void laTasaNoViaja() throws Exception {
            String conTasaInventada =
                    cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza")
                            .replaceFirst("\\{", "{\"tasa\":\"1.00\",\"importe\":\"1.00\",");

            envio(mvc, "/rentas/api/v1/autorizaciones/anuncios", conTasaInventada, null, 201);

            assertThat(libro.pedidos().get(0).monto().valor())
                    .as("si viajara, cualquiera autorizaria un panel por un sol")
                    .isEqualByComparingTo(new BigDecimal("90.00"));
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Renovar, cesar y retirar")
    class LosTramites {

        @Test
        @DisplayName("la renovacion devenga otra vez y responde 201")
        void renovacion() throws Exception {
            registrar(mvc, null, 201);

            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/renovacion",
                            """
                            {"fecha":"2027-01-15","fecVenc":"2027-12-31",
                             "observacion":"Se renueva por el ejercicio 2027"}
                            """,
                            null,
                            201);

            assertThat(cuerpo).contains("\"acto\":\"RENOVACION\"");
            assertThat(cuerpo).contains("\"referenciaDelCargo\":\"ANUNCIO-AN-2026-000001-2027\"");
            assertThat(libro.cuantos()).isEqualTo(2);
        }

        @Test
        @DisplayName("#562: registrar sin ningun conjunto sellado es 422, no 500 con incidencia")
        void registrarSinConjuntoSellado() throws Exception {
            String cuerpo =
                    envio(
                            mvcSinSellar,
                            "/rentas/api/v1/autorizaciones/anuncios",
                            cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza el panel"),
                            null,
                            422);

            assertThat(cuerpo)
                    .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                    .contains("VALIDACION")
                    .contains("2026")
                    .doesNotContain("incidencia");
            assertThat(cuerpo)
                    .as("#691 — sin conjunto sellado no hay llave: viaja el ejercicio solo")
                    .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
            assertThat(libro.cuantos()).as("y no se asienta ningun cargo").isZero();
        }

        @Test
        @DisplayName("#562: y la renovacion tambien, que es la otra que devenga")
        void renovarSinConjuntoSellado() throws Exception {
            registrar(mvc, null, 201);

            String cuerpo =
                    envio(
                            mvcSinSellar,
                            "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/renovacion",
                            """
                            {"fecha":"2027-01-15","fecVenc":"2027-12-31",
                             "observacion":"Se renueva por el ejercicio 2027"}
                            """,
                            null,
                            422);

            assertThat(cuerpo).contains("2027").doesNotContain("incidencia");
            assertThat(libro.cuantos()).as("el cargo de la renovacion no se asienta").isEqualTo(1);
        }

        @Test
        @DisplayName("la segunda renovacion del mismo ejercicio: 409 y ningun cargo mas")
        void renovacionRepetida() throws Exception {
            registrar(mvc, null, 201);
            renovar(201, "2027-01-15");

            assertThat(renovar(409, "2027-03-20")).contains("ANUNCIO-AN-2026-000001-2027");
            assertThat(libro.cuantos()).isEqualTo(2);
        }

        @Test
        @DisplayName("renovar lo cesado: 409, y sin pedir ningun cargo")
        void renovarLoCesado() throws Exception {
            registrar(mvc, null, 201);
            cesar(201);

            assertThat(renovar(409, "2027-01-15")).contains("CESADO");
            assertThat(libro.cuantos()).as("AC 3: el cese detiene la deuda futura").isEqualTo(1);
        }

        @Test
        @DisplayName("el cese no pide ningun cargo y no reversa el ya pedido")
        void elCeseNoTocaElLibro() throws Exception {
            registrar(mvc, null, 201);
            String cuerpo = cesar(201);

            assertThat(cuerpo).contains("\"acto\":\"CESE\"");
            assertThat(cuerpo)
                    .as("un acto que no devenga no lleva ni referencia ni importe")
                    .contains("\"referenciaDelCargo\":null")
                    .contains("\"tasa\":null");
            assertThat(libro.cuantos())
                    .as("AC 3: y no borra la pasada. El cargo de 2026 sigue pedido")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un segundo cese: 409")
        void ceseRepetido() throws Exception {
            registrar(mvc, null, 201);
            cesar(201);
            assertThat(cesar(409)).contains("CESADO");
        }

        @Test
        @DisplayName("el retiro sin cese previo: 422")
        void retiroSinCese() throws Exception {
            registrar(mvc, null, 201);

            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/retiro",
                                    """
                                    {"fecha":"2026-06-30","motivo":"Ya no esta",
                                     "observacion":"Se constata el retiro"}
                                    """,
                                    null,
                                    422))
                    .contains("primero se cesa");
        }

        @Test
        @DisplayName("cesar sin motivo: 422; y sin observacion, tambien")
        void ceseSinMotivoNiObservacion() throws Exception {
            registrar(mvc, null, 201);

            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/cese",
                                    "{\"fecha\":\"2026-06-30\",\"observacion\":\"Se cesa\"}",
                                    null,
                                    422))
                    .contains("lleva el motivo");
            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/cese",
                                    "{\"fecha\":\"2026-06-30\",\"motivo\":\"Cese de giro\"}",
                                    null,
                                    422))
                    .contains("regla 10");
        }

        @Test
        @DisplayName("un anuncio que no existe: 404 en los tres tramites")
        void anuncioInexistente() throws Exception {
            for (String tramite : List.of("renovacion", "cese", "retiro")) {
                envio(
                        mvc,
                        "/rentas/api/v1/autorizaciones/anuncios/AN-2026-999999/" + tramite,
                        """
                        {"fecha":"2026-06-30","motivo":"Da igual",
                         "observacion":"Se intenta el tramite"}
                        """,
                        null,
                        404);
            }
        }

        @Test
        @DisplayName("una fecha mal escrita dice cual campo es: 422")
        void fechaMalEscrita() throws Exception {
            registrar(mvc, null, 201);

            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/renovacion",
                                    """
                                    {"fecha":"15/01/2027","observacion":"Se renueva"}
                                    """,
                                    null,
                                    422))
                    .contains("'fecha'");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Consultar y el padron")
    class LaConsulta {

        @Test
        @DisplayName("la grilla pinta el estado derivado a hoy, con su fecha")
        void laGrilla() throws Exception {
            registrar(mvc, null, 201);

            String cuerpo = obtener("/rentas/api/v1/autorizaciones/anuncios");
            assertThat(cuerpo).contains("\"estado\":\"VIGENTE\"");
            assertThat(cuerpo)
                    .as("un estado sin su fecha es un estado que manana significa otra cosa")
                    .contains("\"estadoALaFecha\":\"2026-03-16\"");
            assertThat(cuerpo).contains("\"est\":\"V\"");
        }

        @Test
        @DisplayName("con nroAutorizacion trae la ficha, y el estado es el de HOY")
        void laFicha() throws Exception {
            registrar(mvc, null, 201);
            // El cese se registra con fecha de junio; la ficha se pide con el reloj congelado en
            // marzo. Un acto posterior a la fecha preguntada NO cuenta (regla 9, RNF-075): el
            // padron de marzo no puede traer el estado de julio.
            cesar(201);

            String cuerpo =
                    obtener(
                            "/rentas/api/v1/autorizaciones/anuncios?nroAutorizacion=AN-2026-000001");
            assertThat(cuerpo)
                    .as("en marzo la autorizacion todavia rige, aunque el cese de junio ya conste")
                    .contains("\"estado\":\"VIGENTE\"")
                    .contains("\"estadoALaFecha\":\"2026-03-16\"");
            assertThat(cuerpo)
                    .as("y el historial trae los dos actos, que es lo que la ficha dibuja")
                    .contains("\"tipo\":\"AUTORIZACION\"", "\"tipo\":\"CESE\"");
        }

        @Test
        @DisplayName("un numero que no existe devuelve la pagina vacia, no un error")
        void fichaInexistente() throws Exception {
            assertThat(
                            obtener(
                                    "/rentas/api/v1/autorizaciones/anuncios?nroAutorizacion=AN-2026-999999"))
                    .contains("\"totalElementos\":0");
        }

        @Test
        @DisplayName("el padron responde con su fecha de corte y su resumen")
        void elPadron() throws Exception {
            registrar(mvc, null, 201);
            registrar(mvc, null, 201);

            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/autorizaciones/anuncios/reportes",
                            "{\"aLaFecha\":\"2026-03-16\"}",
                            null,
                            201);

            assertThat(cuerpo).contains("\"aLaFecha\":\"2026-03-16\"");
            assertThat(cuerpo).contains("\"autorizaciones\":2");
        }

        @Test
        @DisplayName("un intervalo que termina antes de empezar: 422")
        void intervaloHaciaAtras() throws Exception {
            assertThat(
                            envio(
                                    mvc,
                                    "/rentas/api/v1/autorizaciones/anuncios/reportes",
                                    "{\"desde\":\"2026-12-31\",\"hasta\":\"2026-01-01\"}",
                                    null,
                                    422))
                    .contains("termina antes de empezar");
        }
    }

    // ==================================================================
    // Ayudas
    // ==================================================================

    private String registrar(MockMvc cual, String clave, int esperado) throws Exception {
        return registrar(
                cual, clave, esperado, cuerpoDeRegistro(ClaseDeAnuncio.PANEL, null, "Se autoriza"));
    }

    private String registrar(MockMvc cual, String clave, int esperado, String cuerpo)
            throws Exception {
        return envio(cual, "/rentas/api/v1/autorizaciones/anuncios", cuerpo, clave, esperado);
    }

    private String renovar(int esperado, String fecha) throws Exception {
        return envio(
                mvc,
                "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/renovacion",
                "{\"fecha\":\""
                        + fecha
                        + "\",\"fecVenc\":\"2027-12-31\","
                        + "\"observacion\":\"Se renueva\"}",
                null,
                esperado);
    }

    private String cesar(int esperado) throws Exception {
        return envio(
                mvc,
                "/rentas/api/v1/autorizaciones/anuncios/AN-2026-000001/cese",
                """
                {"fecha":"2026-06-30","motivo":"Cese de giro",
                 "observacion":"Se cesa por solicitud del titular"}
                """,
                null,
                esperado);
    }

    private static String cuerpoDeRegistro(
            ClaseDeAnuncio clase, String licencia, String observacion) {
        String conLicencia = licencia == null ? "" : ",\"nroLicencia\":\"" + licencia + "\"";
        String conObservacion =
                observacion == null ? "" : ",\"observacion\":\"" + observacion + "\"";
        return """
               {"codContribuyente":"C-0007",
                "claseAnuncio":"%s",
                "tipoAnuncio":"AVISO_LUMINOSO",
                "ubicacion":"FACHADA",
                "forma":"ADOSADO",
                "denominacion":"BODEGA SAN MARTIN",
                "direccion":"AV. GRAU 100",
                "area":"6.00",
                "nroLados":2,
                "cantidad":1,
                "fecInicio":"2026-03-16",
                "fecVenc":"2026-12-31",
                "nroDeExpediente":"EXP-2026-0051",
                "fechaExp":"2026-03-10"%s%s}
               """
                .formatted(clase.name(), conLicencia, conObservacion);
    }

    private String envio(MockMvc cual, String ruta, String cuerpo, String clave, int esperado)
            throws Exception {
        var peticion =
                MockMvcRequestBuilders.post(ruta)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo);
        if (clave != null) {
            peticion = peticion.header("Idempotency-Key", clave);
        }
        MvcResult resultado = cual.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("%s -> %s", ruta, resultado.getResponse().getContentAsString())
                .isEqualTo(esperado);
        return resultado.getResponse().getContentAsString();
    }

    private String obtener(String ruta) throws Exception {
        MvcResult resultado = mvc.perform(MockMvcRequestBuilders.get(ruta)).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("%s -> %s", ruta, resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }
}
