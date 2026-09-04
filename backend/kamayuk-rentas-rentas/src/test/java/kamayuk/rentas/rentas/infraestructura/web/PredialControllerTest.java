package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.aplicacion.CandadoDeEmision;
import kamayuk.rentas.rentas.aplicacion.CuadroPredialParametrizado;
import kamayuk.rentas.rentas.aplicacion.DeterminarPredial;
import kamayuk.rentas.rentas.aplicacion.DeterminarPredialMasivo;
import kamayuk.rentas.rentas.aplicacion.PadronPredialDelEjercicio;
import kamayuk.rentas.rentas.aplicacion.RegistrarCorridaDeEmision;
import kamayuk.rentas.rentas.aplicacion.RegistrarDeterminacionPredial;
import kamayuk.rentas.rentas.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.rentas.dominio.OrigenDeDeterminacion;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.rentas.dominio.predial.ValuacionRecibida;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El transporte de la determinacion predial, por HTTP de verdad y sin base de datos (#395).
 *
 * <p>Lo que se verifica aqui es lo que la base no puede decir: <b>quien puede pedirlo, que cruza la
 * frontera y que se rechaza</b>.
 *
 * <ul>
 *   <li>Simular y determinar son la <b>misma</b> operacion del contrato y se distinguen por el
 *       cuerpo. Sin decirlo, se rechaza: no hay valor por omision que no sea peligroso en una de
 *       las dos direcciones.
 *   <li>La observacion del usuario es obligatoria para asentar y no para simular, porque simular no
 *       modifica ningun dato (regla 10 gobierna las modificaciones).
 *   <li>Una cifra del cuadro que el conjunto sellado no trae responde <b>422 nombrando la
 *       llave</b>, no 500 y no un valor por omision.
 * </ul>
 *
 * <p>El guardia de verdad esta puesto como interceptor: el 403 lo produce {@link GuardiaDeAcceso}
 * leyendo la anotacion, no la prueba.
 */
@DisplayName("Capa web — POST /api/v1/rentas/predial/*")
class PredialControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();
    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final DeterminacionesEnMemoria determinaciones = new DeterminacionesEnMemoria();
    private final PrediosDePrueba predios = new PrediosDePrueba();

    /**
     * En que estado esta la valuacion del ejercicio cuando se monta el controlador (P5C).
     *
     * <p>Por omision, cerrada y completa: esta clase mide el TRANSPORTE de la corrida y no el
     * candado. Lo que si mide aqui es que el candado <b>este puesto</b>, poniendolo a negarse.
     *
     * <p>Se declara ANTES de {@code mvc} y no despues: Java inicializa los campos en el orden en
     * que estan escritos, y {@code montar} lo lee. Con el orden al reves el candado se construia
     * con {@code null} y cinco pruebas ajenas contestaban 204.
     */
    private ValuacionRecibida valuacion = new ValuacionCerradaYCompleta();

    private MockMvc mvc = montar(cuadroCompleto());

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("simular devuelve las cinco piezas y no asienta nada")
    void simularDevuelveLaMemoriaYNoAsienta() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .param("codContribuyente", "C-001")
                                        .param("ano", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"predios\":"
                                                        + "[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String json = resultado.getResponse().getContentAsString();
        // 1. los predios que integran la base
        assertThat(json)
                .contains("\"codigoPredial\":\"10001\"")
                .contains("\"ubicacion\":\"AV. GRAU 100\"");
        // 2. la base del conjunto, ponderada, con el valuo total, el exonerado y el afecto
        assertThat(json)
                .contains("\"baseImponible\":\"100000.00\"")
                .contains("\"valuoAfecto\":\"100000.00\"");
        // 3. los tramos, con el conjunto sellado que los produjo
        assertThat(json).contains("\"conjunto\":\"2026 v1\"").contains("\"conjuntoId\":77");
        assertThat(json).contains("\"alicuota\":\"0.2\"");
        // 4. las cuotas con sus vencimientos, y el derecho de emision
        assertThat(json)
                .contains("\"vencimiento\":\"2026-02-27\"")
                .contains("\"derechoDeEmision\":\"4.50\"");
        // 5. la fecha a la que todo eso esta calculado
        assertThat(json).contains("\"fechaCalculo\":\"2026-08-29\"");
        // y no se asento nada
        assertThat(json).contains("\"simulacion\":true").contains("\"id\":0");
        assertThat(determinaciones.insertadas).isZero();
        assertThat(auditoria.registros).isEmpty();
    }

    @Test
    @DisplayName("sin decir si simula o determina se rechaza: no hay omision segura")
    void sinLaMarcaSeRechaza() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .param("codContribuyente", "C-001")
                                        .param("ano", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"predios\":[{\"predioId\":11,"
                                                        + "\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("simula o determina");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("asentar sin la observacion del usuario se rechaza (regla 10)")
    void asentarSinObservacionSeRechaza() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado = mvc.perform(asentarSinObservacion()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("observacion");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("asentar con observacion inserta la determinacion y la audita")
    void asentarConObservacion() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"codContribuyente\":\"C-001\","
                                                        + "\"ejercicio\":\"2026\","
                                                        + "\"observacion\":\"Emision ordinaria del ejercicio\","
                                                        + "\"predios\":[{\"predioId\":11,"
                                                        + "\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"simulacion\":false")
                .contains("\"id\":901");
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
        assertThat(auditoria.registros.get(0).observacion().texto())
                .isEqualTo("Emision ordinaria del ejercicio");
    }

    @Test
    @DisplayName("una cifra del cuadro que el conjunto no trae es 422 y dice cual falta")
    void laCifraQueFaltaSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinDerechoDeEmision());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"codContribuyente\":\"C-001\","
                                                        + "\"ejercicio\":\"2026\",\"predios\":"
                                                        + "[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("la peticion esta bien y el sistema no esta roto: falta la ordenanza")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("DERECHO_EMISION_PREDIAL");
    }

    @Test
    @DisplayName("un predio sin autovaluo declarado es 422 y nombra el predio, no un cero")
    void elPredioSinAutovaluoSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(22L, "10002", "JR. LIMA 250", Porcentaje.total());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"codContribuyente\":\"C-001\","
                                                        + "\"ejercicio\":\"2026\",\"predios\":"
                                                        + "[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("10002");
    }

    @Test
    @DisplayName("un contribuyente que no esta en el padron es 404, no 422")
    void contribuyenteInexistenteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"codContribuyente\":\"NO-EXISTE\","
                                                        + "\"ejercicio\":\"2026\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("sin el permiso de la opcion es 403, y no llega a calcular nada")
    void sinPermisoEs403() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        comprobador.autoriza = false;

        MvcResult resultado = mvc.perform(asentarSinObservacion()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(comprobador.acceso).isEqualTo("predial_individual");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.REGISTRO);
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("la corrida masiva simula sin decir el ejercicio, y asienta solo si lo dice")
    void laCorridaMasiva() throws Exception {
        MvcResult simulada =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(simulada.getResponse().getStatus()).isEqualTo(201);
        assertThat(simulada.getResponse().getContentAsString())
                .contains("\"ejercicio\":\"2026\"")
                .contains("\"alcance\":\"TODOS\"")
                .contains("Padrón leído");

        MvcResult asentadaSinEjercicio =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,"
                                                        + "\"observacion\":\"Emision anual del ejercicio\"}"))
                        .andReturn();

        assertThat(asentadaSinEjercicio.getResponse().getStatus())
                .as("elegir por el operador que padron se emite es lo que nadie revisa")
                .isEqualTo(422);
        assertThat(asentadaSinEjercicio.getResponse().getContentAsString()).contains("ejercicio");
    }

    /**
     * <b>La corrida deja rastro, y el rastro se puede volver a leer</b> (#523).
     *
     * <p>Antes de esto la corrida viajaba solo en la respuesta del {@code POST}: cerrar la pestana
     * perdia el resultado de un proceso que toca decenas de miles de cuentas, y volver a verlo
     * exigia volver a correrlo. Lo que no se podia recomponer eran los observados —un observado es,
     * por definicion, el que NO tiene determinacion—.
     */
    @Test
    @DisplayName("la corrida deja rastro, y GET /corridas/ultima lo devuelve")
    void laCorridaDejaRastro() throws Exception {
        MvcResult sinCorridas =
                mvc.perform(get("/rentas/api/v1/rentas/predial/corridas/ultima")).andReturn();
        assertThat(sinCorridas.getResponse().getStatus())
                .as("«todavia no se ha corrido» y «se corrio y no emitio nada» son dos cosas")
                .isEqualTo(204);

        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"simulacion\":true}"))
                .andReturn();

        MvcResult ultima =
                mvc.perform(get("/rentas/api/v1/rentas/predial/corridas/ultima")).andReturn();

        assertThat(ultima.getResponse().getStatus())
                .as("sin el rastro escrito, la corrida murio con su respuesta")
                .isEqualTo(200);
        String cuerpo = ultima.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"simulacion\":true").contains("Padrón leído");
        assertThat(cuerpo)
                .as("lleva el id: es con lo que la pantalla pide despues sus observados")
                .contains("\"id\":");
    }

    @Test
    @DisplayName("el acceso de la lectura de la corrida es el de su pantalla, con LECTURA")
    void elAccesoDeLaLecturaDeLaCorrida() throws Exception {
        mvc.perform(get("/rentas/api/v1/rentas/predial/corridas/ultima")).andReturn();

        assertThat(comprobador.acceso).isEqualTo("predial_masivo");
        assertThat(comprobador.privilegio)
                .as("leer lo que hizo una corrida no es correrla: LECTURA, no EJECUCION")
                .isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("la corrida rechaza los dos interruptores que no hace, en vez de ignorarlos")
    void laCorridaRechazaLoQueNoHace() throws Exception {
        MvcResult conArbitrios =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true,\"incluyeArbitrios\":true}"))
                        .andReturn();
        MvcResult conCuponera =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"generaCuponeraPdf\":true}"))
                        .andReturn();

        assertThat(conArbitrios.getResponse().getStatus()).isEqualTo(422);
        assertThat(conArbitrios.getResponse().getContentAsString()).contains("arbitrios");
        assertThat(conCuponera.getResponse().getStatus()).isEqualTo(422);
        assertThat(conCuponera.getResponse().getContentAsString()).contains("cuponera");
    }

    @Test
    @DisplayName("el alcance por sector sin sector se rechaza: seria la corrida entera")
    void elAlcanceSinSectorSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true,\"alcance\":\"SECTOR\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("sector");
    }

    // ------------------------------------------- los cuatro alcances (#577)

    @Test
    @DisplayName("un alcance que no esta en la lista se rechaza, y la lista sale entera")
    void unAlcanceDesconocidoSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"alcance\":\"TODO EL"
                                                        + " PADRON\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "«TODO EL PADRON» es el rotulo del desplegable y NO es la palabra que este"
                                + " servicio admite: parecerse no es serlo (#427)")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("TODOS")
                .contains("SECTOR")
                .contains("RANGO_DE_CODIGO")
                .contains("OBSERVADOS");
    }

    @Test
    @DisplayName("el alcance por rango sin sus dos extremos se rechaza: seria la corrida entera")
    void elRangoSinSusExtremosSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"alcance\":\"RANGO_DE_CODIGO\","
                                                        + "\"codigoDesde\":\"C-001\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("codigoHasta");
    }

    @Test
    @DisplayName("el rango de codigo recorre el tramo y a nadie mas")
    void elRangoDeCodigoAcotaElConjunto() throws Exception {
        sembrarDosContribuyentes();

        MvcResult diag =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"ejercicio\":\"2026\","
                                                        + "\"alcance\":\"RANGO_DE_CODIGO\","
                                                        + "\"codigoDesde\":\"C-002\",\"codigoHasta\":\"C-999\","
                                                        + "\"recalculaYaEmitidos\":true,"
                                                        + "\"observacion\":\"Emision del tramo alto\"}"))
                        .andReturn();

        assertThat(determinaciones.determinados)
                .as(
                        "el CONJUNTO recorrido, no el recuento: sin el filtro saldrian los dos y el"
                                + " numero por si solo no lo distingue del tramo bien acotado")
                .containsExactly(502L);
    }

    @Test
    @DisplayName(
            "«solo observados» recorre a los que la corrida anterior dejo fuera, y a nadie mas")
    void soloObservadosRecorreALosDeLaCorridaAnterior() throws Exception {
        sembrarDosContribuyentes();

        // Primera corrida: C-001 esta EMITIDA y sin «recalcula ya emitidos» queda
        // observado; C-002 esta en BORRADOR y se determina.
        MvcResult primera =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"ejercicio\":\"2026\","
                                                        + "\"observacion\":\"Emision anual\"}"))
                        .andReturn();
        assertThat(primera.getResponse().getContentAsString())
                .contains("\"codContribuyente\":\"C-001\"");
        assertThat(determinaciones.determinados).containsExactly(502L);
        determinaciones.determinados.clear();

        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"simulacion\":false,\"ejercicio\":\"2026\","
                                                + "\"alcance\":\"OBSERVADOS\","
                                                + "\"recalculaYaEmitidos\":true,"
                                                + "\"observacion\":\"Segunda pasada de la campana\"}"))
                .andReturn();

        assertThat(determinaciones.determinados)
                .as(
                        "es el alcance que mas se usa en una campana —volver a correr sobre los que"
                                + " quedaron fuera— y el que no habia manera de pedir. Con TODOS"
                                + " saldrian los dos")
                .containsExactly(501L);
    }

    @Test
    @DisplayName("«solo observados» sin corrida previa no recorre a nadie, y eso no es «ninguno»")
    void soloObservadosSinCorridaPreviaNoRecorreANadie() throws Exception {
        sembrarDosContribuyentes();

        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"simulacion\":false,\"ejercicio\":\"2026\","
                                                + "\"alcance\":\"OBSERVADOS\","
                                                + "\"recalculaYaEmitidos\":true,"
                                                + "\"observacion\":\"Segunda pasada\"}"))
                .andReturn();

        assertThat(determinaciones.determinados)
                .as(
                        "«ninguno quedo observado» y «todavia no se ha corrido» son dos cosas"
                                + " distintas, y la unica que puede emitir es la primera")
                .isEmpty();
    }

    @Test
    @DisplayName("la corrida masiva pide su propio permiso, no el del calculo individual")
    void laCorridaTieneSuPropioPermiso() throws Exception {
        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"simulacion\":true}"))
                .andReturn();

        assertThat(comprobador.acceso).isEqualTo("predial_masivo");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.EJECUCION);
    }

    @Test
    @DisplayName("un contribuyente ya emitido queda observado con su motivo, salvo que se pida")
    void elYaEmitidoQuedaObservado() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        determinaciones.sembrarEmitida(
                EJERCICIO,
                7L,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));

        MvcResult sinRecalcular =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true,\"ejercicio\":\"2026\"}"))
                        .andReturn();

        assertThat(sinRecalcular.getResponse().getContentAsString())
                .contains("\"codContribuyente\":\"C-001\"")
                .contains("EMITIDA");

        MvcResult recalculando =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"ejercicio\":\"2026\","
                                                        + "\"recalculaYaEmitidos\":true}"))
                        .andReturn();

        assertThat(recalculando.getResponse().getContentAsString())
                .contains("\"observados\":[]")
                .contains("\"conjunto\":\"2026 v1\"");
    }

    // ------------------------------------------------- falta publicar, y se dice (#540)

    @Test
    @DisplayName("un ejercicio sin conjunto sellado es 422 y nombra el ejercicio, no 500")
    void elEjercicioSinSellarSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montarCon(lectorSinSellar());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la peticion esta bien y el servidor no esta roto: lo que falta es sellar el"
                                + " conjunto del ejercicio")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("la corrida masiva contesta lo mismo: 422 nombrando el ejercicio")
    void laCorridaMasivaTambienLoDice() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        sembrarUnPadronQueSeRecalcula();
        mvc = montarCon(lectorSinSellar());

        MvcResult resultado = mvc.perform(recalcularElPadron()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la corrida corta y lo dice: la falta es del conjunto, no de un"
                                + " contribuyente, asi que no se observa treinta mil veces")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("2026")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("un conjunto sellado sin ningun punto de redondeo observado es 422, y dice cual")
    void elConjuntoSinPuntosDeRedondeoSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinRedondeo());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("D-03c abierta no es un fallo del servidor: es una campana de observacion")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("REDONDEO")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("media politica de redondeo —escala sin modo— tambien es 422, y nombra el punto")
    void laMediaPoliticaSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroConMediaPolitica());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("CUOTA")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("#633 — un conjunto que observa puntos y no el de la cuota es 422, no 500")
    void elPuntoDeLaCuotaSinObservarSeNombra() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinElPuntoDeLaCuota());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "hasta #633 ningun catch del backend nombraba `PuntoSinPolitica`: la"
                                + " determinacion entera contestaba 500 con su incidencia")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("«falta publicar» solo sirve si dice QUE punto falta")
                .contains("VALIDACION")
                .contains("CUOTA");
        assertThat(cuerpo)
                .as("y los que si estan: quien va a observar el que falta necesita saber que hay")
                .contains("IMPUESTO_POR_TRAMO");
        assertThat(cuerpo)
                .as(
                        "no es «el conjunto no observa ningun punto»: ese lo traducia #540 desde"
                                + " el lector, y sembrarlo dejaria esta prueba en verde con el"
                                + " defecto dentro")
                .doesNotContain("no tiene ninguna fila REDONDEO");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("#633 — y la corrida masiva contesta lo mismo, en vez de observar a 30 000")
    void laCorridaMasivaTambienNombraElPuntoDeLaCuota() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        sembrarUnPadronQueSeRecalcula();
        mvc = montar(cuadroSinElPuntoDeLaCuota());

        MvcResult resultado = mvc.perform(recalcularElPadron()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la falta es del conjunto y no de un contribuyente: el bucle no la observa"
                                + " una vez por cada uno, corta y lo dice")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("CUOTA")
                .doesNotContain("incidencia");
    }

    // ------------------------------------------- y el 422 dice CUAL de las dos cosas (#691)

    @Test
    @DisplayName("#691 — sin conjunto sellado, el 422 trae el ejercicio y ninguna llave")
    void sinConjuntoSelladoTraeElMiembro() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montarCon(lectorSinSellar());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que falta es el conjunto entero: no hay ninguna fila que nombrar")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
    }

    @Test
    @DisplayName("#691 — y la corrida masiva contesta el mismo miembro, no solo el mismo texto")
    void laCorridaMasivaTambienTraeElMiembro() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        sembrarUnPadronQueSeRecalcula();
        mvc = montarCon(lectorSinSellar());

        MvcResult resultado = mvc.perform(recalcularElPadron()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
    }

    @Test
    @DisplayName("#691 — la cifra del cuadro que falta viaja como llave, no solo dentro del texto")
    void laCifraDelCuadroTraeSuLlave() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinDerechoDeEmision());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("la interfaz reacciona al miembro, nunca al texto: el texto se reescribe")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"DERECHO_EMISION_PREDIAL\"}");
    }

    @Test
    @DisplayName("#691 — sin ningun punto observado, la llave es el TIPO solo")
    void sinPuntosObservadosLaLlaveEsElTipo() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinRedondeo());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "falta el bloque entero y nadie sabe cual de los trece puntos queria el que"
                                + " llamo: nombrar uno seria verosimil y equivocado")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"REDONDEO\"}");
    }

    @Test
    @DisplayName("#691 — con media politica, la llave es la fila del punto")
    void laMediaPoliticaTraeLaFila() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroConMediaPolitica());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("#691 — y la del dominio puro tambien, con el ejercicio puesto desde fuera")
    void elPuntoSinPoliticaTraeSuLlave() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc = montar(cuadroSinElPuntoDeLaCuota());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "`PuntoSinPolitica` es dominio puro y no sabe de que ejercicio son sus"
                                + " politicas (regla 7): el ejercicio lo pone quien lo pidio, y la"
                                + " llave se compone con el punto que la excepcion si nombra")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("#691 — CONTRASTE: el predio sin autovaluo NO lo lleva, y es el mismo 422")
    void elPredioSinAutovaluoNoLlevaElMiembro() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(22L, "10002", "JR. LIMA 250", Porcentaje.total());

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("tambien es 422 VALIDACION: eso es justo lo que hacia falta discriminar")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "esto lo arregla quien atiende, declarando el autovaluo del otro predio."
                                + " Ponerlo en todos seria tan inutil como no ponerlo en ninguno")
                .doesNotContain("parametroQueFalta");
    }

    @Test
    @DisplayName("y ninguna de las seis escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        sembrarUnPadronQueSeRecalcula();
        anotados.start();
        registro.addAppender(anotados);
        try {
            mvc = montarCon(lectorSinSellar());
            mvc.perform(simularIndividual());
            mvc.perform(recalcularElPadron());
            mvc = montar(cuadroSinRedondeo());
            mvc.perform(simularIndividual());
            mvc = montar(cuadroConMediaPolitica());
            mvc.perform(simularIndividual());
            mvc = montar(cuadroSinElPuntoDeLaCuota());
            mvc.perform(simularIndividual());
            mvc.perform(recalcularElPadron());
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(
                        anotados.list.stream()
                                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as(
                        "hoy NINGUN ejercicio esta sellado (D-02a): cada intento dejaba una"
                                + " incidencia ERROR con su UUID, y con eso el registro deja de"
                                + " servir para encontrar defectos de verdad")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        determinaciones.revienta = true;

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"codContribuyente\":\"C-001\","
                                                        + "\"ejercicio\":\"2026\",\"observacion\":"
                                                        + "\"Emision ordinaria del ejercicio\","
                                                        + "\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir las dos excepciones no puede convertir TODO en 422: un defecto del"
                                + " servidor tiene que seguir diciendo que lo es, y dejar su rastro")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ---------------------------------------------------------------- utilidades

    /**
     * Dos contribuyentes: C-001 con su determinacion EMITIDA y C-002 en BORRADOR.
     *
     * <p>Con uno solo no se puede medir ningun alcance: acotar y no acotar dan el mismo resultado y
     * la prueba no distingue las dos cosas (#577).
     */
    private void sembrarDosContribuyentes() {
        predios.con(501L, 11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(502L, 12L, "10002", "CALLE LIMA 200", Porcentaje.total());
        determinaciones.sembrar(
                EJERCICIO,
                7L,
                501L,
                EstadoDeDeterminacion.EMITIDA,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));
        determinaciones.sembrar(
                EJERCICIO,
                8L,
                502L,
                EstadoDeDeterminacion.BORRADOR,
                DetalleDeterminacionPredio.nuevo(
                        12L,
                        Dinero.de("120000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("120000.00")));
    }

    /** Un padron con un contribuyente al que la corrida SI llega a determinar. */
    private void sembrarUnPadronQueSeRecalcula() {
        determinaciones.sembrarEmitida(
                EJERCICIO,
                7L,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));
    }

    private static org.springframework.test.web.servlet.RequestBuilder recalcularElPadron() {
        return post("/rentas/api/v1/rentas/predial/calculo-masivo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"simulacion\":true,\"ejercicio\":\"2026\","
                                + "\"recalculaYaEmitidos\":true}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder simularIndividual() {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"simulacion\":true,\"codContribuyente\":\"C-001\",\"ejercicio\":\"2026\","
                                + "\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder asentarSinObservacion() {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"simulacion\":false,\"codContribuyente\":\"C-001\",\"ejercicio\":\"2026\","
                                + "\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}");
    }

    @Test
    @DisplayName("P5C / AC 3 — con la valuacion sin cerrar, la corrida masiva no arranca")
    void laCorridaMasivaNoArrancaSinValuacion() throws Exception {
        // Que el candado exista no basta: hay que comprobar que ESTA PUESTO en el camino de la
        // corrida. Se midio antes de escribir esta prueba y quitar la llamada de
        // `DeterminarPredialMasivo` dejaba las 3 674 pruebas del backend en VERDE.
        valuacion = new SinValuacionDelEjercicio();
        mvc = montar(cuadroCompleto());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Emision anual\","
                                                        + "\"ejercicio\":\"2026\","
                                                        + "\"alcance\":\"TODOS\","
                                                        + "\"simulacion\":true}"))
                        .andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus())
                .as("409 y no 422: no hay nada en la peticion que corregir. %s", cuerpo)
                .isEqualTo(409);
        assertThat(cuerpo)
                .as("y dice cual de las tres situaciones es, que es lo que se arregla distinto")
                .contains("no ha cerrado su corrida de valuacion");
    }

    /** Ninguna corrida cerrada: el estado de hoy en todas las municipalidades. */
    private static final class SinValuacionDelEjercicio implements ValuacionRecibida {

        @Override
        public java.util.Optional<CierreDeCorrida> cierreDe(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return java.util.Optional.empty();
        }

        @Override
        public long valuacionesRecibidasDe(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return 0;
        }

        @Override
        public String huellaDeLoRecibido(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return "";
        }
    }

    private MockMvc montar(ParametrosSellados sellados) {
        return montarCon(lector(sellados));
    }

    private MockMvc montarCon(LectorDeParametros lector) {
        CuadroPredialParametrizado cuadro = new CuadroPredialParametrizado(lector);
        PadronPredialDelEjercicio padron = new PadronPredialDelEjercicio(determinaciones);
        DeterminarPredial individual =
                new DeterminarPredial(
                        padron,
                        predios,
                        new SinCaracteristicas(),
                        new DirectorioDePrueba(),
                        cuadro,
                        new RegistrarDeterminacionPredial(
                                determinaciones, lector, auditoria, RELOJ),
                        RELOJ);
        /* El rastro de la corrida (#523) va contra un repositorio en memoria: lo que
        esta prueba mira es el transporte, y que la corrida se escriba de verdad lo
        mide `CorridaDeEmisionJdbcTest` contra PostgreSQL. */
        RegistrarCorridaDeEmision rastro = new RegistrarCorridaDeEmision(new CorridasEnMemoria());
        DeterminarPredialMasivo masivo =
                new DeterminarPredialMasivo(
                        padron,
                        individual,
                        new DirectorioDePrueba(),
                        new SinCaracteristicas(),
                        rastro,
                        new CandadoDeEmision(valuacion),
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(
                        new PredialController(individual, masivo, rastro, RELOJ))
                .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    private static ParametrosSellados.Constructor conRedondeo(ParametrosSellados.Constructor base) {
        return base.numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .texto("REDONDEO", "CUOTA", "HALF_UP");
    }

    private static ParametrosSellados cuadroCompleto() {
        return conRedondeo(
                        ParametrosSellados.de(EJERCICIO, 1)
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
                                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30"))
                .construir();
    }

    private static ParametrosSellados cuadroSinDerechoDeEmision() {
        return conRedondeo(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.0"))
                                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6")))
                .construir();
    }

    /** El cuadro completo, pero sin una sola fila {@code REDONDEO:‹punto›} (D-03c, #540). */
    private static ParametrosSellados cuadroSinRedondeo() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .construir();
    }

    /**
     * El cuadro completo, con <b>tres</b> de los cuatro puntos de redondeo y sin el de la cuota.
     *
     * <p>Es el tercer estado (#633), y no es {@link #cuadroSinRedondeo()} con otro nombre: alli no
     * hay ninguna fila {@code REDONDEO} y quien falla es el lector, con {@code
     * SinPuntosObservados}, que #540 ya traducia —sembrarlo dejaria la prueba en verde con el
     * defecto dentro—. Aqui las politicas se leen enteras, la determinacion recorre {@code
     * BASE_IMPONIBLE_DEL_PREDIO}, {@code BASE_DEL_CONTRIBUYENTE} e {@code IMPUESTO_POR_TRAMO} sin
     * tropezar, y revienta al repartir el cronograma: {@code politicas.en(CUOTA)}.
     */
    private static ParametrosSellados cuadroSinElPuntoDeLaCuota() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .construir();
    }

    /** Un punto con la escala y sin el modo: media politica no es una politica (#203, #540). */
    private static ParametrosSellados cuadroConMediaPolitica() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .construir();
    }

    /** Lo que ocurre HOY en todas las municipalidades: ningun conjunto sellado (D-02a). */
    private static LectorDeParametros lectorSinSellar() {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                throw new LectorDeParametros.ConjuntoNoSellado(identificador);
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }
        };
    }

    private static LectorDeParametros lector(ParametrosSellados sellados) {
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
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }

    // ---------------------------------------------------------------- dobles

    private static final class PrediosDePrueba implements PrediosDelContribuyente {

        /**
         * Los predios <b>de cada</b> contribuyente.
         *
         * <p>Devolverlos todos a todos parecia inofensivo con un solo contribuyente y no lo es en
         * cuanto hay dos: la determinacion exige que cada predio de la base traiga su autovaluo
         * declarado, asi que el segundo contribuyente heredaba el predio del primero y salia
         * observado por no declararlo (#577).
         */
        private final Map<Long, List<PredioDelContribuyente>> porContribuyente =
                new LinkedHashMap<>();

        void con(long predioId, String codigo, String direccion, Porcentaje cuota) {
            con(501L, predioId, codigo, direccion, cuota);
        }

        void con(
                long contribuyenteId,
                long predioId,
                String codigo,
                String direccion,
                Porcentaje cuota) {
            porContribuyente
                    .computeIfAbsent(contribuyenteId, quien -> new ArrayList<>())
                    .add(new PredioDelContribuyente(predioId, codigo, "URBANO", direccion, cuota));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.copyOf(porContribuyente.getOrDefault(contribuyenteId, List.of()));
        }
    }

    private static final class SinCaracteristicas implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class DirectorioDePrueba implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente UNO =
                new ResumenDeContribuyente(501L, "C-001", "SUC. RUFINA MEDINA MEDINA", "03593174");

        /** El segundo hace falta para medir un ALCANCE: con uno solo, acotar no se nota (#577). */
        private static final ResumenDeContribuyente DOS =
                new ResumenDeContribuyente(502L, "C-002", "SULLON VILCHEZ, JOSE RAUL", "29614026");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if ("C-001".equals(codigo)) {
                return Optional.of(UNO);
            }
            return "C-002".equals(codigo) ? Optional.of(DOS) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            if (ids.contains(UNO.id())) {
                encontrados.put(UNO.id(), UNO);
            }
            if (ids.contains(DOS.id())) {
                encontrados.put(DOS.id(), DOS);
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class DeterminacionesEnMemoria implements DeterminacionRepository {

        private int insertadas;

        /** Un defecto de verdad del servidor, para el contraste de #540. */
        private boolean revienta;

        private final Map<Long, List<DetalleDeterminacionPredio>> detallePorId =
                new LinkedHashMap<>();
        private final List<Determinacion> cabeceras = new ArrayList<>();

        /** Quien recibio determinacion nueva, en orden. Es el CONJUNTO que un alcance acota. */
        private final List<Long> determinados = new ArrayList<>();

        void sembrarEmitida(Ejercicio ejercicio, long id, DetalleDeterminacionPredio... detalle) {
            sembrar(ejercicio, id, 501L, EstadoDeDeterminacion.EMITIDA, detalle);
        }

        void sembrar(
                Ejercicio ejercicio,
                long id,
                long contribuyenteId,
                EstadoDeDeterminacion estado,
                DetalleDeterminacionPredio... detalle) {
            cabeceras.add(
                    new Determinacion(
                            id,
                            ejercicio,
                            "PREDIAL",
                            null,
                            contribuyenteId,
                            null,
                            null,
                            77L,
                            Dinero.de("100000.00"),
                            Dinero.de("165.00"),
                            List.of("RT-011"),
                            OrigenDeDeterminacion.ORDINARIA,
                            estado,
                            "siembra"));
            detallePorId.put(id, List.of(detalle));
        }

        @Override
        public Optional<Determinacion> findById(long id) {
            return cabeceras.stream().filter(c -> Long.valueOf(id).equals(c.id())).findFirst();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.copyOf(cabeceras);
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return cabeceras.stream()
                    .filter(c -> c.contribuyenteId() == contribuyenteId)
                    .reduce((primera, segunda) -> segunda);
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return detallePorId.getOrDefault(determinacionId, List.of());
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            if (revienta) {
                throw new IllegalStateException("un defecto de verdad, con su rastro");
            }
            insertadas++;
            determinados.add(determinacion.contribuyenteId());
            return new Determinacion(
                    900L + insertadas,
                    determinacion.ejercicio(),
                    determinacion.tributo(),
                    determinacion.periodo(),
                    determinacion.contribuyenteId(),
                    determinacion.predioId(),
                    determinacion.vehiculoId(),
                    determinacion.conjuntoId(),
                    determinacion.baseImponible(),
                    determinacion.montoDeterminado(),
                    determinacion.reglasAplicadas(),
                    determinacion.origen(),
                    determinacion.estado(),
                    "cajero.ventanilla");
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            throw new UnsupportedOperationException("El predial siempre lleva detalle por predio");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String acceso = "";
        private Privilegio privilegio = Privilegio.LECTURA;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }

    /** Las corridas en memoria: esta prueba mira el transporte, no la persistencia (#523). */
    private static final class CorridasEnMemoria
            implements kamayuk.rentas.rentas.dominio.CorridaDeEmisionRepository {

        private final java.util.List<kamayuk.rentas.rentas.dominio.CorridaDeEmision> guardadas =
                new java.util.ArrayList<>();

        @Override
        public kamayuk.rentas.rentas.dominio.CorridaDeEmision guardar(
                kamayuk.rentas.rentas.dominio.CorridaDeEmision corrida,
                kamayuk.rentas.dominio.Observacion observacion) {
            kamayuk.rentas.rentas.dominio.CorridaDeEmision conId =
                    new kamayuk.rentas.rentas.dominio.CorridaDeEmision(
                            (long) (guardadas.size() + 1),
                            corrida.ejercicio(),
                            corrida.alcance(),
                            corrida.sector(),
                            corrida.codigoDesde(),
                            corrida.codigoHasta(),
                            corrida.modalidad(),
                            corrida.simulacion(),
                            corrida.conjunto(),
                            corrida.leidos(),
                            corrida.determinados(),
                            corrida.montoEmitido(),
                            corrida.fechaCalculo(),
                            corrida.observados());
            guardadas.add(conId);
            return conId;
        }

        @Override
        public java.util.Optional<kamayuk.rentas.rentas.dominio.CorridaDeEmision> ultimaDe(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return guardadas.reversed().stream()
                    .filter(corrida -> corrida.ejercicio().equals(ejercicio))
                    .findFirst();
        }

        @Override
        public java.util.List<kamayuk.rentas.rentas.dominio.CorridaDeEmision> ultimas(int cuantas) {
            return guardadas.reversed().stream().limit(cuantas).toList();
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<
                        kamayuk.rentas.rentas.dominio.CorridaDeEmision.Observado>
                observadosDe(long corridaId, kamayuk.rentas.compartido.Paginacion paginacion) {
            var filas =
                    guardadas.stream()
                            .filter(corrida -> java.util.Objects.equals(corrida.id(), corridaId))
                            .findFirst()
                            .map(kamayuk.rentas.rentas.dominio.CorridaDeEmision::observados)
                            .orElse(java.util.List.of());
            return new kamayuk.rentas.compartido.Pagina<>(filas, 0, 20, filas.size());
        }
    }

    /**
     * La valuacion del ejercicio, cerrada y completa (P5C).
     *
     * <p>Esta prueba mide el TRANSPORTE de la corrida masiva, no el candado. Que el candado se
     * niegue cuando falta algo lo mide `CandadoDeEmisionTest` contra PostgreSQL, con las tres
     * negativas por separado; aqui lo que hace falta es que deje pasar, y decirlo explicito es
     * mejor que un doble que devuelva lo que sea.
     */
    private static final class ValuacionCerradaYCompleta implements ValuacionRecibida {

        private static final String HUELLA = "0".repeat(64);

        @Override
        public java.util.Optional<CierreDeCorrida> cierreDe(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return java.util.Optional.of(
                    new CierreDeCorrida(
                            1L, 1L, java.time.LocalDate.of(2025, 12, 31), "v1", 0, HUELLA));
        }

        @Override
        public long valuacionesRecibidasDe(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return 0;
        }

        @Override
        public String huellaDeLoRecibido(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return HUELLA;
        }
    }
}
