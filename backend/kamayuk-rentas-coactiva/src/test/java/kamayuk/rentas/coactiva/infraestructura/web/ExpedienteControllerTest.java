package kamayuk.rentas.coactiva.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.aplicacion.CambiarDireccionReferencial;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.coactiva.dobles.CostasEnMemoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.FasesDeMentira;
import kamayuk.rentas.coactiva.dobles.LibroDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * #40 — Capa web: se prueba el transporte y los codigos de respuesta, no la persistencia —eso lo
 * verifica {@code ExpedienteCoactivoJdbcTest} contra PostgreSQL real—.
 */
@DisplayName("Capa web — /api/v1/coactiva/expedientes")
class ExpedienteControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String EJECUTOR = "ejecutor.coactivo";

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);

    private final ValoresDeMentira valores =
            new ValoresDeMentira()
                    .con(valor(1L, "OP-2026-000001", "COACTIVA", true))
                    .con(valor(2L, "OP-2026-000002", "NOTIFICADO", false));

    private final LibroDeMentira libro =
            new LibroDeMentira()
                    .con(
                            new ObligacionPublica(
                                    "PREDIAL",
                                    EJERCICIO,
                                    null,
                                    null,
                                    HOY,
                                    Dinero.de("500.00"),
                                    Dinero.de("10.00"),
                                    Dinero.de("25.50"),
                                    Dinero.CERO,
                                    "COACTIVA"));

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"));

    private final ConsultaDeExpedientes consulta =
            new ConsultaDeExpedientes(
                    expedientes, movimientos, valores, libro, new CostasEnMemoria());

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ExpedienteController(
                                    new ImportarValoresACoactiva(
                                            expedientes,
                                            movimientos,
                                            valores,
                                            new FasesDeMentira(),
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    new CambiarEstadoDelExpediente(
                                            expedientes,
                                            movimientos,
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    new CambiarDireccionReferencial(
                                            expedientes,
                                            movimientos,
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    consulta,
                                    contribuyentes,
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

    /** El origen lo fija el borde de la aplicacion; aqui no hay borde, asi que se fija a mano. */
    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen(EJECUTOR, "PC-COACTIVA-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("importa y devuelve 201 con el expediente, su deuda y la fecha de esa deuda")
    void importaYDevuelve201() throws Exception {
        MvcResult resultado = importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"numero\":\"EXP-2026-000001\"");
        assertThat(cuerpo).contains("\"estado\":\"INICIADO\"");
        assertThat(cuerpo).contains("\"importados\":1");
        assertThat(cuerpo)
                .as("toda cifra sale con su fecha (RNF-075, regla 9)")
                .contains("\"deudaAlDia\":\"2026-06-15\"");
        assertThat(cuerpo).contains("\"totalExigible\":\"535.50\"");
        assertThat(cuerpo)
                .as("las costas viajan aunque sean cero: son #42")
                .contains("\"costas\":\"0.00\"");
        assertThat(valores.aceptados())
                .as("importar responde el ACO que #39 dejo anunciado")
                .containsExactly(1L);
    }

    @Test
    @DisplayName("el valor que no cumple sale en el informe con su motivo, no como un total")
    void elInformeDiceElMotivoPorValor() throws Exception {
        MvcResult resultado = importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"numero\":\"OP-2026-000002\"");
        assertThat(cuerpo).contains("\"motivo\":\"PLAZO_VIGENTE\"");
        assertThat(cuerpo)
                .as("el motivo se lee, no se descifra")
                .contains("El plazo todavia corre a esa fecha");
    }

    @Test
    @DisplayName("sin observacion, 422: no se importa nada (regla 10)")
    void sinObservacionRechaza() throws Exception {
        MvcResult resultado = importar(cuerpoDeImportacion(""));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(movimientos.cuantos()).isZero();
    }

    @Test
    @DisplayName("sin ejecutor, 422: un expediente sin ejecutor coactivo no se sigue")
    void sinEjecutorRechaza() throws Exception {
        MvcResult resultado =
                importar("{\"codContribuyente\":\"C-0007\",\"observacion\":\"Se importa\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(movimientos.cuantos()).isZero();
    }

    @Test
    @DisplayName("si nada entra, 200 con el informe: la peticion estaba bien formada")
    void sinNadaAdmitidoDevuelve200() throws Exception {
        MvcResult resultado =
                importar(
                        "{\"codContribuyente\":\"C-0007\",\"ejecutor\":\"R. MENDOZA CRUZ\","
                                + "\"valores\":[\"OP-2026-000002\"],"
                                + "\"observacion\":\"Se intenta importar\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"expediente\":null")
                .contains("PLAZO_VIGENTE");
        assertThat(movimientos.cuantos())
                .as("sin ningun valor admitido no se abre expediente ni se gasta correlativo")
                .isZero();
    }

    @Test
    @DisplayName("un contribuyente que no existe, 404")
    void contribuyenteInexistenteDevuelve404() throws Exception {
        MvcResult resultado =
                importar(
                        "{\"codContribuyente\":\"C-9999\",\"ejecutor\":\"R. MENDOZA CRUZ\","
                                + "\"observacion\":\"Se importa\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("PATCH del estado devuelve el expediente con su historial y su estado nuevo")
    void elPatchDeEstadoDevuelveElHistorial() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.patch(
                                                "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                                "EXP-2026-000001")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nuevoEstado\":\"011 — REC 01 EMITIDO\","
                                                        + "\"motivo\":\"se emite la REC 01\","
                                                        + "\"documentoDeRespaldoFecha\":\"2026-06-15\","
                                                        + "\"documentoDeRespaldoNumero\":\"REC-1\","
                                                        + "\"observacion\":\"Se inicia la ejecucion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"estado\":\"REC 01 EMITIDO\"");
        assertThat(cuerpo).contains("\"estadoCodigo\":\"011\"");
        assertThat(cuerpo)
                .as("«Activo» de la pantalla se deriva: es el ultimo movimiento con estado")
                .contains("\"activo\":true");
        assertThat(cuerpo).contains("\"numDoc\":\"REC-1\"");
    }

    @Test
    @DisplayName("PATCH del estado sin motivo, 422: el acto se queda sin sustento")
    void elPatchSinMotivoRechaza() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));
        int antes = movimientos.cuantos();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.patch(
                                                "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                                "EXP-2026-000001")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nuevoEstado\":\"041\","
                                                        + "\"observacion\":\"Se suspende\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(movimientos.cuantos()).isEqualTo(antes);
    }

    @Test
    @DisplayName("#423 — el numero de la ruta en minusculas es el mismo expediente: 200")
    void elNumeroEnMinusculasEsElMismoExpediente() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.patch(
                                                "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                                "exp-2026-000001")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nuevoEstado\":\"011 — REC 01 EMITIDO\","
                                                        + "\"motivo\":\"se emite la REC 01\","
                                                        + "\"observacion\":\"Se inicia la ejecucion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la grilla lo encuentra con `?nroDeExpediente=exp-2026-000001`, y el PATCH"
                                + " sobre la misma cadena contestaba 404: "
                                + resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"numero\":\"EXP-2026-000001\"")
                .contains("\"estado\":\"REC 01 EMITIDO\"");
    }

    @Test
    @DisplayName("#423 — un numero que la plantilla no compone es 422, y dice como se escribe")
    void unNumeroIlegibleEs422() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));
        int antes = movimientos.cuantos();

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.patch(
                                                "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                                "2026-000001")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nuevoEstado\":\"041\",\"motivo\":\"m\","
                                                        + "\"observacion\":\"Se suspende\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "no es «no existe»: es un numero mal escrito, y quien lo escribio lo"
                                + " corrige")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("EXP-2026-000001");
        assertThat(movimientos.cuantos()).isEqualTo(antes);
    }

    /**
     * #409 — {@code INICIADO} no se elige: es con lo que nace el expediente. Hasta #409 el {@code
     * PATCH} lo aceptaba con 200, y el expediente con su REC-1 dictada pasaba a contar como «sin
     * REC-1» en el panel de trabajo parado y en el resumen de cartera, sin que nadie pudiera
     * dictarsela ({@code acto_rec1_uq}). Se pide por el nombre y por el codigo del manual, que son
     * las dos formas en que {@code porNombre} lo reconoce.
     */
    @Test
    @DisplayName("#409 — PATCH del estado a INICIADO (o 000), 422: no se vuelve al nacimiento")
    void elPatchAIniciadoRechaza() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));
        MvcResult notificada = patchDeEstado("012 — REC 01 NOTIFICADA");
        assertThat(notificada.getResponse().getStatus()).isEqualTo(200);
        int antes = movimientos.cuantos();

        for (String iniciado : List.of("INICIADO", "000")) {
            MvcResult resultado = patchDeEstado(iniciado);

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "«"
                                    + iniciado
                                    + "» contestaba 200: "
                                    + resultado.getResponse().getContentAsString())
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("INICIADO");
        }
        assertThat(movimientos.cuantos())
                .as("el historial no gana la fila que lo devolvia a INICIADO")
                .isEqualTo(antes);
    }

    private MvcResult patchDeEstado(String nuevoEstado) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.patch(
                                        "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                        "EXP-2026-000001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"nuevoEstado\":\""
                                                + nuevoEstado
                                                + "\",\"motivo\":\"x\","
                                                + "\"observacion\":\"Se corrige el estado\"}"))
                .andReturn();
    }

    /**
     * #423 — las otras dos rutas de este controlador con {@code {numero}} leen el numero con la
     * misma plantilla que el {@code PATCH} de estados. La prueba de arriba solo cubria ese; sin
     * estas, quitar {@code comoSeImprime} de la deuda o de la direccion volvia a dar 404 sin que
     * nada lo dijera. El doble compara como el adaptador —exacto, sin {@code equalsIgnoreCase}—,
     * asi que lo que normaliza es el borde y no la prueba.
     */
    @Test
    @DisplayName("#423 — la deuda del expediente con el numero en minusculas: 200")
    void laDeudaConElNumeroEnMinusculas() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                        "/rentas/api/v1/coactiva/expedientes/{numero}/deuda",
                                        "exp-2026-000001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"expediente\":\"EXP-2026-000001\"");
    }

    @Test
    @DisplayName("#423 — la direccion referencial con el numero en minusculas: 200")
    void laDireccionConElNumeroEnMinusculas() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                cambiarDireccion("exp-2026-000001", "JR. NUEVO 250", "no ubicado", "Se corrige");

        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"numero\":\"EXP-2026-000001\"")
                .contains("\"direccionReferencial\":\"JR. NUEVO 250\"");
    }

    @Test
    @DisplayName("un expediente que no existe, 404")
    void expedienteInexistenteDevuelve404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.patch(
                                                "/rentas/api/v1/coactiva/expedientes/{numero}/estados",
                                                "EXP-2026-999999")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nuevoEstado\":\"041\",\"motivo\":\"m\","
                                                        + "\"observacion\":\"Se suspende\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("PATCH de la direccion referencial devuelve la vigente, no la de apertura")
    void elPatchDeDireccionDevuelveLaVigente() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado = cambiarDireccion("JR. NUEVO 250", "no ubicado", "Se corrige");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"direccionReferencial\":\"JR. NUEVO 250\"");
    }

    @Test
    @DisplayName("la misma direccion dos veces, 409: no es un cambio")
    void laMismaDireccionDevuelve409() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));
        cambiarDireccion("JR. NUEVO 250", "no ubicado", "Se corrige");

        MvcResult resultado = cambiarDireccion("jr. nuevo 250", "otra vez", "Se corrige");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("PATCH de la direccion sin observacion, 422 (regla 10)")
    void laDireccionSinObservacionRechaza() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));
        int antes = movimientos.cuantos();

        MvcResult resultado = cambiarDireccion("JR. NUEVO 250", "no ubicado", "");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(movimientos.cuantos()).isEqualTo(antes);
    }

    @Test
    @DisplayName("la grilla pagina y trae el estado, la deuda y su fecha")
    void laGrillaPagina() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get("/rentas/api/v1/coactiva/expedientes")
                                        .param("estado", "Todos"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"numero\":\"EXP-2026-000001\"");
        assertThat(cuerpo).contains("\"codContribuyente\":\"C-0007\"");
        assertThat(cuerpo).contains("\"deudaAlDia\":\"2026-06-15\"");
    }

    /**
     * #426 — La deuda del expediente <b>obligación por obligación</b>, por HTTP.
     *
     * <p>Lo que se fija aquí son los <b>nombres de campo</b> del recurso, que es lo único que la
     * pantalla que fracciona lee: su adaptador saca de cada fila {@code tributo}, {@code
     * ejercicio}, {@code predioId} y {@code vehiculoId} —los cuatro que {@code
     * PeticionDeObligacionAcogida} pide— y de la cabecera {@code aLaFecha}, sin la cual ninguna de
     * las cifras se puede fechar (regla 9). Un renombre aquí deja la grilla vacía en silencio.
     */
    @Test
    @DisplayName("#426 — la deuda por obligacion trae los cuatro campos que el convenio pide")
    void laDeudaPorObligacionTraeLoQueElConvenioPide() throws Exception {
        importar(cuerpoDeImportacion("Se importa la cartera vencida"));

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                        "/rentas/api/v1/coactiva/expedientes/{numero}/deuda",
                                        "EXP-2026-000001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"expediente\":\"EXP-2026-000001\"");
        assertThat(cuerpo).contains("\"codContribuyente\":\"C-0007\"");
        assertThat(cuerpo).contains("\"contribuyente\":\"TITULAR, PRUEBA\"");
        assertThat(cuerpo)
                .as("toda cifra con su fecha, y una sola para todas las filas (regla 9)")
                .contains("\"aLaFecha\":\"2026-06-15\"");
        assertThat(cuerpo)
                .as("los cuatro campos con que el convenio identifica lo que se acoge")
                .contains("\"tributo\":\"PREDIAL\"")
                .contains("\"ejercicio\":2026")
                .contains("\"predioId\":null")
                .contains("\"vehiculoId\":null");
        assertThat(cuerpo)
                .as("y la costa se distingue de la deuda materia de cobranza")
                .contains("\"esCosta\":false")
                .contains("\"costasS\":\"0.00\"");
        assertThat(cuerpo)
                .as("el total lo publica el servidor: la pantalla no suma columnas (RNF-083)")
                .contains("\"totalS\":\"535.50\"");
    }

    @Test
    @DisplayName("#426 — un expediente que no existe, 404: no una deuda de cero")
    void laDeudaDeUnExpedienteQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                        "/rentas/api/v1/coactiva/expedientes/{numero}/deuda",
                                        "EXP-2026-999999"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("una deuda de cero es una respuesta; «ese expediente no esta» es otra")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("un estado que la pantalla no ofrece, 422: no se traduce a algo parecido")
    void elEstadoDesconocidoRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get("/rentas/api/v1/coactiva/expedientes")
                                        .param("estado", "ARCHIVADO"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    // ------------------------------------------------------------------

    private MvcResult importar(String cuerpo) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post(
                                        "/rentas/api/v1/coactiva/expedientes/importacion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private MvcResult cambiarDireccion(String nueva, String motivo, String observacion)
            throws Exception {
        return cambiarDireccion("EXP-2026-000001", nueva, motivo, observacion);
    }

    private MvcResult cambiarDireccion(
            String numero, String nueva, String motivo, String observacion) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.patch(
                                        "/rentas/api/v1/coactiva/expedientes/{numero}/direccion-referencial",
                                        numero)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"nuevaDireccionReferencial\":\""
                                                + nueva
                                                + "\",\"motivo\":\""
                                                + motivo
                                                + "\",\"observacion\":\""
                                                + observacion
                                                + "\"}"))
                .andReturn();
    }

    private static String cuerpoDeImportacion(String observacion) {
        return "{\"codContribuyente\":\"C-0007\",\"ejecutor\":\"R. MENDOZA CRUZ\","
                + "\"auxiliar\":\"S. PALACIOS NIMA\",\"asunto\":\"Cobranza coactiva\","
                + "\"direccionReferencialDelContribuyente\":\"AV. ORIGINAL 100\","
                + "\"observacion\":\""
                + observacion
                + "\"}";
    }

    private static ValorParaCoactiva valor(
            long id, String numero, String situacion, boolean conPase) {
        return new ValorParaCoactiva(
                id,
                "OP",
                numero,
                EJERCICIO,
                LocalDate.of(2026, 3, 2),
                7L,
                situacion,
                HOY,
                LocalDate.of(2026, 5, 5),
                conPase,
                Dinero.de("500.00"),
                LocalDate.of(2026, 3, 2),
                List.of(new ObligacionDelValor("PREDIAL", EJERCICIO, null, null)));
    }
}
