package kamayuk.rentas.valores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores;
import kamayuk.rentas.valores.aplicacion.IniciarCorridaMasiva;
import kamayuk.rentas.valores.aplicacion.PasarACoactiva;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.aplicacion.RegistrarNotificacion;
import kamayuk.rentas.valores.aplicacion.RegistrarValor;
import kamayuk.rentas.valores.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.valores.dobles.MovimientosEnMemoria;
import kamayuk.rentas.valores.dobles.NotificacionesEnMemoria;
import kamayuk.rentas.valores.dobles.ParametrosDeMentira;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #37 — Capa web: se prueba el transporte, no la persistencia —eso lo verifica {@code
 * ValorRepositoryJdbcTest} contra PostgreSQL real—.
 */
@DisplayName("Capa web — /api/v1/valores")
class ValoresControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final Ejercicio EJERCICIO_DEUDA = new Ejercicio(2025);

    private final ValoresEnMemoria repositorio = new ValoresEnMemoria();
    private final DeudaDeMentira deuda = new DeudaDeMentira();
    private final ContribuyentesDeMentira contribuyentes = new ContribuyentesDeMentira();
    private final RepositorioMasivoDeMentira repositorioMasivo = new RepositorioMasivoDeMentira();
    private final IniciarCorridaMasiva iniciarMasivo =
            new IniciarCorridaMasiva(
                    repositorioMasivo,
                    contribuyentes,
                    Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    private final RegistrarValor registrar =
            new RegistrarValor(
                    repositorio,
                    deuda,
                    new MovimientoDeFase() {
                        @Override
                        public void moverAValor(
                                Ejercicio ejercicio,
                                long contribuyenteId,
                                String tributo,
                                @Nullable Integer periodo,
                                @Nullable Long predioId,
                                @Nullable Long vehiculoId,
                                String referenciaExterna,
                                Dinero monto,
                                LocalDate fechaValor,
                                String documentoOrigen,
                                Observacion observacion) {}
                    },
                    (RegistroDeAuditoria registro) -> {},
                    Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

    private final NotificacionesEnMemoria notificaciones = new NotificacionesEnMemoria();
    private final MovimientosEnMemoria movimientos = new MovimientosEnMemoria();
    private final ParametrosDeMentira parametros =
            new ParametrosDeMentira().con("PLAZO", "NOTIFICACION_VALOR-OP", "20 DIAS_HABILES");
    private final RegistrarNotificacion notificar =
            new RegistrarNotificacion(
                    repositorio,
                    notificaciones,
                    contribuyentes,
                    new PlazosParametrizados(parametros),
                    (RegistroDeAuditoria registro) -> {});
    private final PasarACoactiva pasarACoactiva =
            new PasarACoactiva(
                    repositorio,
                    notificaciones,
                    movimientos,
                    (RegistroDeAuditoria registro) -> {},
                    Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ValoresController(
                                    registrar,
                                    new ConsultaDeValores(repositorio, contribuyentes),
                                    contribuyentes,
                                    iniciarMasivo,
                                    notificar,
                                    pasarACoactiva))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("emite un valor y devuelve 201 con su numero")
    void emiteUnValorYDevuelve201() throws Exception {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"));
        deuda.con(
                new ObligacionPublica(
                        "PREDIAL",
                        EJERCICIO_DEUDA,
                        55L,
                        null,
                        HOY,
                        Dinero.de(100),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO));

        String cuerpo =
                """
                {"tipo":"OP","codContribuyente":"C-0007",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55}],
                 "observacion":"Se emite para la prueba"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"numero\":\"OP-2026-");
    }

    @Test
    @DisplayName("sin observacion, 422: no se guarda")
    void sinObservacionRechaza() throws Exception {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"));
        deuda.con(
                new ObligacionPublica(
                        "PREDIAL",
                        EJERCICIO_DEUDA,
                        55L,
                        null,
                        HOY,
                        Dinero.de(100),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO));

        String cuerpo =
                """
                {"tipo":"OP","codContribuyente":"C-0007",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55}]}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(
                        repositorio
                                .buscar(
                                        new CriterioDeValor(null, null, null, null),
                                        Paginacion.de(1, 20, "numero"))
                                .contenido())
                .isEmpty();
    }

    @Test
    @DisplayName("un codigo de contribuyente que no existe, 404")
    void contribuyenteInexistente404() throws Exception {
        String cuerpo =
                """
                {"tipo":"OP","codContribuyente":"NO-EXISTE",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55}],
                 "observacion":"Se emite para la prueba"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("busqueda por un codigo que no existe devuelve pagina vacia, no error")
    void busquedaPorCodigoInexistenteDaPaginaVacia() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get("/rentas/api/v1/valores")
                                        .param("codContribuyente", "NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("masivo por seleccion: registra la corrida en CRITERIO, sin generar nada todavia")
    void masivoPorSeleccionRegistraLaCorrida() throws Exception {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"));

        String cuerpo =
                """
                {"tipo":"OP","ejercicioDesde":2024,"ejercicioHasta":2026,
                 "contribuyentes":["C-0007"],"observacion":"Corrida de prueba"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores/masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalCandidatos\":1")
                .contains("\"origen\":\"SELECCION\"");
    }

    @Test
    @DisplayName("masivo con un codigo que no existe, rechaza la corrida entera (RF-133)")
    void masivoConCodigoInexistenteRechazaTodo() throws Exception {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"));

        String cuerpo =
                """
                {"tipo":"OP","ejercicioDesde":2024,"ejercicioHasta":2026,
                 "contribuyentes":["C-0007","NO-EXISTE"],"observacion":"Corrida de prueba"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores/masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(repositorioMasivo.corridas).isEmpty();
    }

    @Test
    @DisplayName("masivo sin 'contribuyentes' ni 'archivoCsv', 422")
    void masivoSinCriterioRechaza() throws Exception {
        String cuerpo =
                """
                {"tipo":"OP","ejercicioDesde":2024,"ejercicioHasta":2026,
                 "observacion":"Corrida de prueba"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores/masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("masivo por importacion: lee el CSV en base64 y registra la corrida")
    void masivoPorImportacionRegistraLaCorrida() throws Exception {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"));
        String csv = "codContribuyente\nC-0007\n";
        String base64 =
                java.util.Base64.getEncoder()
                        .encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String cuerpo =
                """
                {"tipo":"RD","ejercicioDesde":2024,"ejercicioHasta":2026,
                 "archivoCsv":"%s","observacion":"Corrida importada"}
                """
                        .formatted(base64);

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/valores/masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"origen\":\"IMPORTACION\"");
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  #39 — notificacion y pase a coactiva
    // ------------------------------------------------------------------

    @Test
    @DisplayName("notifica un valor y devuelve 201 con la fecha desde la que es exigible")
    void notificaUnValorYDevuelve201() throws Exception {
        emitirUnValor();

        MvcResult resultado = notificar("NOTIFICADO", "2026-04-03");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"surtioEfecto\":true");
        assertThat(cuerpo).contains("\"exigibleDesde\":\"2026-05-");
        assertThat(cuerpo).contains("\"intento\":1");
    }

    @Test
    @DisplayName("una diligencia no hallada sale con exigibleDesde nulo, no con una fecha")
    void laDiligenciaNoHalladaSaleSinExigibilidad() throws Exception {
        emitirUnValor();

        MvcResult resultado = notificar("NO_UBICADO", "2026-04-03");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"surtioEfecto\":false")
                .contains("\"exigibleDesde\":null");
    }

    @Test
    @DisplayName("«notificador» y «resultado» viajan por la consulta y deciden la diligencia")
    void losDosFiltrosViajanPorLaConsulta() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                        .param("resultado", "NO_UBICADO")
                                        .param("notificador", "V. RETO SANDOVAL")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"fechaDeNotificacion":"2026-04-03",
                                                 "tipoDeNotificacion":"PERSONAL",
                                                 "observacion":"Se diligencia para la prueba"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("no basta con que se acepte: el resultado que se pidio es el que se guardo")
                .contains("\"resultado\":\"NO_UBICADO\"")
                .contains("\"notificador\":\"V. RETO SANDOVAL\"");
        assertThat(cuerpo)
                .as(
                        "y con el se decide lo que mas importa: una diligencia no hallada no deja"
                                + " la deuda exigible (#39)")
                .contains("\"surtioEfecto\":false")
                .contains("\"exigibleDesde\":null");
    }

    @Test
    @DisplayName("y si vienen en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsultaAlNotificar() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                        .param("resultado", "NO_UBICADO")
                                        .param("notificador", "V. RETO SANDOVAL")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"fechaDeNotificacion":"2026-04-03",
                                                 "tipoDeNotificacion":"PERSONAL",
                                                 "resultado":"NOTIFICADO",
                                                 "notificador":"J. RUIZ PALACIOS",
                                                 "personaQueRecibe":"TITULAR",
                                                 "observacion":"Se diligencia para la prueba"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"resultado\":\"NOTIFICADO\"")
                .contains("\"notificador\":\"J. RUIZ PALACIOS\"")
                .contains("\"surtioEfecto\":true");
    }

    @Test
    @DisplayName("sin notificador en ninguno de los dos sitios, 422")
    void sinNotificadorEnNingunSitio422() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                        .param("resultado", "NOTIFICADO")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"fechaDeNotificacion":"2026-04-03",
                                                 "tipoDeNotificacion":"PERSONAL",
                                                 "observacion":"Se diligencia para la prueba"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("notificador");
    }

    @Test
    @DisplayName("notificar sin observacion, 422")
    void notificarSinObservacionRechaza() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"fechaDeNotificacion":"2026-04-03",
                                                 "tipoDeNotificacion":"PERSONAL",
                                                 "resultado":"NOTIFICADO",
                                                 "notificador":"J. RUIZ PALACIOS",
                                                 "direccion":"CALLE 1"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("notificar un valor que no existe, 404")
    void notificarUnValorInexistente404() throws Exception {
        MvcResult resultado = notificar("NOTIFICADO", "2026-04-03");
        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("un valor no notificado no pasa a coactiva: 422")
    void unValorNoNotificadoNoPasa() throws Exception {
        emitirUnValor();

        MvcResult resultado = pasarACoactiva("2026-06-01");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("no esta notificado");
    }

    @Test
    @DisplayName("pasar dos veces devuelve el mismo movimiento, no dos")
    void pasarDosVecesDevuelveElMismo() throws Exception {
        emitirUnValor();
        notificar("NOTIFICADO", "2026-04-03");

        String primero = pasarACoactiva("2026-06-01").getResponse().getContentAsString();
        String segundo = pasarACoactiva("2026-06-10").getResponse().getContentAsString();

        assertThat(primero).contains("\"tipoDeMovimiento\":\"PCO\"");
        assertThat(segundo).isEqualTo(primero);
        assertThat(movimientos.cuantos()).isEqualTo(1);
    }

    @Test
    @DisplayName("ACO y RCO se rechazan con un mensaje que dice de quien son")
    void aceptadoYRechazadoSeRechazan() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/valores/OP-2026-000001/movimientos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"tipoDeMovimiento":"ACO",
                                                 "observacion":"Aceptado en coactivas"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("modulo coactiva");
    }

    // ------------------------------------------------------- lo que falta publicar (#562)

    @Test
    @DisplayName("sin ningun conjunto sellado, notificar es 422 y nombra el ejercicio, no 500")
    void notificarSinConjuntoSellado() throws Exception {
        emitirUnValor();

        MvcResult resultado = notificarCon(new ParametrosDeMentira().sinSellar());

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("#691 — sin conjunto sellado no hay llave: viaja el ejercicio solo")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        assertThat(notificaciones.todas()).isEmpty();
    }

    @Test
    @DisplayName("con el conjunto sellado y sin la llave sigue nombrando la llave (#192)")
    void notificarSinLaLlaveNombraLaLlave() throws Exception {
        emitirUnValor();

        MvcResult resultado = notificarCon(new ParametrosDeMentira());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("hay conjunto y le falta una cifra: lo que se nombra es la llave")
                .contains("PLAZO:NOTIFICACION_VALOR-OP")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"PLAZO:NOTIFICACION_VALOR-OP\"}");
    }

    @Test
    @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        emitirUnValor();

        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            notificarCon(new ParametrosDeMentira().sinSellar());
            notificarCon(new ParametrosDeMentira());
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as(
                        "es la mitad del defecto que la respuesta no ensena: con D-02a abierta esto"
                                + " pasa en TODAS las municipalidades, y el registro de incidencias"
                                + " es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        emitirUnValor();

        MvcResult resultado =
                notificarCon(
                        new ParametrosDeMentira()
                                .con("PLAZO", "NOTIFICACION_VALOR-OP", "no es un plazo"));

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir lo que falta publicar no puede convertir TODO en 422: un plazo"
                                + " sellado que no se puede leer es un dato que hay que investigar")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ------------------------------------------------------------------

    /**
     * El mismo controlador con otro lector de parametros detras de la notificacion.
     *
     * <p>El conjunto sellado se resuelve al construir {@code PlazosParametrizados}, asi que la
     * unica forma de probar las tres situaciones —sin conjunto, con conjunto y sin la llave, y con
     * una llave ilegible— es montar el borde otra vez.
     */
    private MvcResult notificarCon(LectorDeParametros lector) throws Exception {
        MockMvc borde =
                MockMvcBuilders.standaloneSetup(
                                new ValoresController(
                                        registrar,
                                        new ConsultaDeValores(repositorio, contribuyentes),
                                        contribuyentes,
                                        iniciarMasivo,
                                        new RegistrarNotificacion(
                                                repositorio,
                                                notificaciones,
                                                contribuyentes,
                                                new PlazosParametrizados(lector),
                                                (RegistroDeAuditoria registro) -> {}),
                                        pasarACoactiva))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
        return borde.perform(
                        MockMvcRequestBuilders.post(
                                        "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"fechaDeNotificacion":"2026-04-03",
                                         "tipoDeNotificacion":"PERSONAL",
                                         "resultado":"NOTIFICADO",
                                         "notificador":"J. RUIZ PALACIOS",
                                         "personaQueRecibe":"TITULAR",
                                         "observacion":"Se diligencia para la prueba"}
                                        """))
                .andReturn();
    }

    private void emitirUnValor() throws Exception {
        contribuyentes
                .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"))
                .conDomicilio(7L, LocalDate.of(2020, 1, 1), "CALLE VIEJA 100");
        deuda.con(
                new ObligacionPublica(
                        "PREDIAL",
                        EJERCICIO_DEUDA,
                        55L,
                        null,
                        HOY,
                        Dinero.de(100),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO));
        mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/valores")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"tipo":"OP","codContribuyente":"C-0007",
                                         "obligaciones":[
                                            {"tributo":"PREDIAL","ejercicio":2025,"predioId":55}],
                                         "observacion":"Se emite para la prueba"}
                                        """))
                .andReturn();
    }

    private MvcResult notificar(String resultado, String fecha) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post(
                                        "/rentas/api/v1/valores/OP-2026-000001/notificacion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"fechaDeNotificacion":"%s",
                                         "tipoDeNotificacion":"PERSONAL",
                                         "resultado":"%s",
                                         "notificador":"J. RUIZ PALACIOS",
                                         "personaQueRecibe":"TITULAR",
                                         "observacion":"Se diligencia para la prueba"}
                                        """
                                                .formatted(fecha, resultado)))
                .andReturn();
    }

    private MvcResult pasarACoactiva(String fecha) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post(
                                        "/rentas/api/v1/valores/OP-2026-000001/movimientos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"tipoDeMovimiento":"PCO",
                                         "fechaDelMovimiento":"%s",
                                         "observacion":"Se pasa para la prueba"}
                                        """
                                                .formatted(fecha)))
                .andReturn();
    }

    private static final class DeudaDeMentira implements ConsultaDeDeudaPublica {

        private final List<ObligacionPublica> obligaciones = new ArrayList<>();

        void con(ObligacionPublica obligacion) {
            obligaciones.add(obligacion);
        }

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.copyOf(obligaciones);
        }
    }

    private static final class RepositorioMasivoDeMentira implements ValorMasivoRepository {

        private long siguienteId = 1;
        private final List<ValorMasivo> corridas = new ArrayList<>();

        @Override
        public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
            ValorMasivo conId =
                    new ValorMasivo(
                            siguienteId++,
                            corrida.tipo(),
                            corrida.tributo(),
                            corrida.ejercicioDesde(),
                            corrida.ejercicioHasta(),
                            corrida.fechaCriterio(),
                            corrida.origen(),
                            contribuyenteIds.size(),
                            "prueba",
                            null,
                            corrida.observacion());
            corridas.add(conId);
            return conId;
        }

        @Override
        public Optional<ValorMasivo> porId(long id) {
            return corridas.stream().filter(c -> c.id() != null && c.id() == id).findFirst();
        }

        @Override
        public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
            return List.of();
        }

        @Override
        public List<ValorMasivoItem> itemsGenerados(long corridaId) {
            return List.of();
        }

        @Override
        public long contarPendientes(long corridaId) {
            return 0;
        }

        @Override
        public void marcarGenerado(long itemId, long valorId) {}

        @Override
        public void marcarSinDeuda(long itemId) {}
    }
}
