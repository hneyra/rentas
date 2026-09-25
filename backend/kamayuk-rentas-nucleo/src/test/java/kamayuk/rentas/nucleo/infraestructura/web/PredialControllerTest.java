package kamayuk.rentas.nucleo.infraestructura.web;

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
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.aplicacion.CandadoDeEmision;
import kamayuk.rentas.nucleo.aplicacion.CuadroPredialParametrizado;
import kamayuk.rentas.nucleo.aplicacion.DeterminarPredial;
import kamayuk.rentas.nucleo.aplicacion.DeterminarPredialMasivo;
import kamayuk.rentas.nucleo.aplicacion.PadronPredialDelEjercicio;
import kamayuk.rentas.nucleo.aplicacion.RegistrarCorridaDeEmision;
import kamayuk.rentas.nucleo.aplicacion.RegistrarDeterminacionPredial;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.OrigenDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
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

    /** Los beneficios registrados de cada contribuyente (#331); por omision, ninguno. */
    private final Map<Long, List<BeneficioRegistrado>> beneficios = new LinkedHashMap<>();

    /** Las fichas que leen la determinacion y el alcance de la corrida; por omision, ninguna. */
    private final FichasDePrueba fichas = new FichasDePrueba();

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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"predios\":"
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"codContribuyente\":\"C-001\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"codContribuyente\":\"C-001\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"codContribuyente\":\"C-001\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"codContribuyente\":\"NO-EXISTE\","
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
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true}"))
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,"
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
                                .content("{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true}"))
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
        // Y los dos agregados que el panel dibuja arriba (#271). Iban solo DENTRO de una fila de
        // `etapas`, y sacarlos de la tabla en el navegador es lo que `conectores.ts` prohibe.
        assertThat(cuerpo)
                .as("«cuentas emitidas» y «monto determinado» son campos, no una celda de la tabla")
                .contains("\"determinados\":")
                .contains("\"montoEmitido\":");
        // Y el sello de V23 (#312): la corrida que no determino a nadie no resolvio ningun
        // conjunto, asi que no tiene derecho que sellar — y lo dice con NULO, no con cero. Un
        // `"0.00"` aqui afirmaria que no se cobro derecho de emision.
        assertThat(cuerpo)
                .as("nulo dice «esta corrida no lo guardo»; cero diria «no se cobro»")
                .contains("\"derechoDeEmision\":null")
                .contains("\"conjuntoId\":null");
    }

    /**
     * <b>La corrida que SI determina sella lo que aplico</b> (#312, V23, D-02b).
     *
     * <h2>Por que hacen falta las dos corridas y no basta esta</h2>
     *
     * <p>{@link #laCorridaDejaRastro()} corre sobre un padron vacio: no determina a nadie, no
     * resuelve ningun conjunto, y su sello sale <b>nulo</b>. Esta corre sobre un padron con un
     * contribuyente y su sello sale <b>escrito</b>. Con una sola de las dos, una implementacion que
     * devolviera siempre cero —o siempre nulo— pasaria en verde.
     *
     * <h2>Las cifras de la muestra no coinciden entre si</h2>
     *
     * <p>El conjunto vigente del doble es el <b>77</b>, el derecho <b>4,50</b>, los determinados
     * <b>1</b> y el monto <b>274,50</b>: cuatro cifras distintas. Con dos iguales, «publica el
     * conjunto sellado» y «publica cuantos determino» pasarian la misma asercion.
     *
     * <p><b>Esto no cierra D-02b.</b> El {@code 4.50} de aqui lo pone el cuadro de la prueba; en
     * una instalacion de verdad {@code DERECHO_EMISION_PREDIAL} sigue sin publicarlo nadie, y una
     * corrida que determine a alguien todavia revienta con {@code ParametroAusente} nombrando la
     * llave. Lo que se prueba es que, cuando el valor existe, la corrida lo <b>conserva</b>.
     */
    @Test
    @DisplayName("#312 — la corrida que determina sella el derecho y el conjunto del que salio")
    void laCorridaSellaElDerechoQueAplico() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        sembrarUnPadronQueSeRecalcula();
        mvc = montar(cuadroCompleto());

        mvc.perform(recalcularElPadron()).andReturn();

        MvcResult ultima =
                mvc.perform(get("/rentas/api/v1/rentas/predial/corridas/ultima")).andReturn();
        String cuerpo = ultima.getResponse().getContentAsString();

        assertThat(ultima.getResponse().getStatus()).isEqualTo(200);
        assertThat(cuerpo)
                .as("determino a alguien, asi que hubo conjunto y hubo derecho: %s", cuerpo)
                .contains("\"determinados\":1")
                .contains("\"derechoDeEmision\":\"4.50\"")
                .contains("\"conjuntoId\":77");
        // Texto y no coma flotante (regla 1, RNF-055): `4.50` en un `double` viaja como `4.5`.
        assertThat(cuerpo).doesNotContain("\"derechoDeEmision\":4.5");
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
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"incluyeArbitrios\":true}"))
                        .andReturn();
        MvcResult conCuponera =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"generaCuponeraPdf\":true}"))
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
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"alcance\":\"SECTOR\"}"))
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"alcance\":\"TODO EL"
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"alcance\":\"RANGO_DE_CODIGO\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"ejercicio\":\"2026\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"ejercicio\":\"2026\","
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
                                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"ejercicio\":\"2026\","
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
                                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"ejercicio\":\"2026\","
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
                                .content("{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true}"))
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
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"ejercicio\":\"2026\"}"))
                        .andReturn();

        assertThat(sinRecalcular.getResponse().getContentAsString())
                .contains("\"codContribuyente\":\"C-001\"")
                .contains("EMITIDA");

        MvcResult recalculando =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"ejercicio\":\"2026\","
                                                        + "\"recalculaYaEmitidos\":true}"))
                        .andReturn();

        assertThat(recalculando.getResponse().getContentAsString())
                .contains("\"observados\":[]")
                .contains("\"conjunto\":\"2026 v1\"");
    }

    // ------------------------------------ la corrida lee el padron al 1 de enero (#328)

    /**
     * <b>El que vende durante el ejercicio sigue en la emision del ejercicio</b> (#328, TUO LTM
     * art. 10).
     *
     * <p>A —C-001— tiene P1 y P al 1 de enero y los declaro en febrero (determinacion EMITIDA con
     * los dos en el detalle); vende P a B el 15 de marzo; la corrida es de agosto con «recalcula ya
     * emitidos». Leyendo el padron del dia de la corrida, el detalle trae P y el padron no, salta
     * {@code PredioAjeno} y A queda observado —fuera de la emision 2026 entera, con P1—. Basta una
     * venta en enero, antes de la corrida de febrero.
     */
    @Test
    @DisplayName("#328 — el que vendio en marzo sale DETERMINADO por la corrida, no observado")
    void laCorridaDeterminaAlQueVendioDuranteElEjercicio() throws Exception {
        predios.con(501L, 11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.conVigencia(501L, 33L, "10033", "CALLE LA VENTA 33", "2019-06-01", "2026-03-14");
        predios.conVigencia(502L, 33L, "10033", "CALLE LA VENTA 33", "2026-03-15", null);
        determinaciones.sembrar(
                EJERCICIO,
                7L,
                501L,
                EstadoDeDeterminacion.EMITIDA,
                ModalidadDelPredial.TRIMESTRAL,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")),
                DetalleDeterminacionPredio.nuevo(
                        33L,
                        Dinero.de("200000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("200000.00")));

        MvcResult corrida = mvc.perform(asentarLaCorrida("TODOS", null)).andReturn();

        String cuerpo = corrida.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("A es el obligado de 2026: observarlo lo saca de la emision. %s", cuerpo)
                .contains("\"observados\":[]");
        assertThat(determinaciones.determinados).containsExactly(501L);
        // Base 300 000,00 —P1 y P al 100 %—: 82 500 x 0.2 % = 165.00 ; 217 500 x 0.6 % = 1 305.00
        // ; 1 470.00 de impuesto y 4.50 de derecho de emision.
        assertThat(cuerpo).contains("\"monto\":\"1474.50\"");
        assertThat(cuerpo)
                .as("la fecha de calculo de la corrida sigue siendo la del reloj (regla 9)")
                .contains("\"fechaCalculo\":\"2026-08-29\"");
    }

    @Test
    @DisplayName(
            "#331 — con un beneficio PREDIAL sin RT-012, el calculo individual es 422 y lo dice")
    void elBeneficioSinReglaEsUn422() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        beneficios.put(501L, List.of(pensionista()));

        MvcResult resultado = mvc.perform(simularIndividual()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("sin la guarda, sale un 201 con la cifra sin deducir")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("VALIDACION")
                .contains("RT-012")
                .contains("PENSIONISTA");
    }

    @Test
    @DisplayName("#331 — y la corrida lo deja observado, fuera de la emision, en vez de cobrarle")
    void laCorridaObservaAlDelBeneficioSinRegla() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        beneficios.put(501L, List.of(pensionista()));
        determinaciones.sembrar(
                EJERCICIO,
                7L,
                501L,
                EstadoDeDeterminacion.EMITIDA,
                ModalidadDelPredial.TRIMESTRAL,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));

        MvcResult corrida = mvc.perform(asentarLaCorrida("TODOS", null)).andReturn();

        String cuerpo = corrida.getResponse().getContentAsString();
        assertThat(cuerpo).as(cuerpo).contains("C-001").contains("RT-012");
        assertThat(determinaciones.determinados)
                .as("el pensionista no se determina: queda observado, a la vista")
                .isEmpty();
    }

    /**
     * <b>El minimo sobre una base afecta cero</b> (#332).
     *
     * <p>El escenario del issue: el unico predio de C-001 es un templo inafecto (art. 17 del TUO
     * LTM), autovaluo S/ 250 000 y {@code valuoExonerado} 250 000. La siembra que distingue es la
     * base <b>exactamente</b> cero: con cualquier base positiva, cobrar el minimo y negarse a
     * decidir dan la misma cifra. Se asienta, no se simula, para que «ninguna fila» signifique
     * algo.
     */
    @Test
    @DisplayName(
            "#332 — con la base afecta cero, el calculo individual es 422, nombra RT-014-c02/c03 y"
                    + " no asienta nada")
    void laBaseAfectaCeroEsUn422() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado = mvc.perform(asentarConExonerado("250000.00")).andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin la condicion sale un 201 con 33,00 —el minimo sobre una base"
                                + " inafecta—, y sin el catch del controlador, un 500")
                .isEqualTo(422);
        assertThat(cuerpo)
                .as(cuerpo)
                .contains("VALIDACION")
                .contains("RT-014-c02")
                .contains("RT-014-c03")
                .doesNotContain("parametroQueFalta");
        assertThat(determinaciones.insertadas).as("ninguna fila en determinacion").isZero();
        assertThat(auditoria.registros).isEmpty();
    }

    @Test
    @DisplayName(
            "#332 — el borde del otro lado: una base afecta de S/ 1,00 sigue determinando 33,00")
    void unaBaseDeUnSolSigueDeterminandoElMinimo() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado = mvc.perform(asentarConExonerado("249999.00")).andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as(cuerpo).isEqualTo(201);
        // Sin la comilla de cierre a proposito: el minimo sale de UIT x % sin redondear y viaja
        // con cinco decimales («33.00000»). Lo que esta prueba fija es la cifra, no su escala.
        assertThat(cuerpo)
                .contains("\"baseImponible\":\"1.00\"")
                .contains("\"minimoImponible\":\"33.00")
                .contains("\"impuestoInsoluto\":\"33.00");
        assertThat(determinaciones.insertadas).isEqualTo(1);
    }

    @Test
    @DisplayName("#332 — y la corrida deja observado al de base afecta cero, en vez de cobrarle")
    void laCorridaObservaAlDeBaseAfectaCero() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        determinaciones.sembrar(
                EJERCICIO,
                7L,
                501L,
                EstadoDeDeterminacion.EMITIDA,
                ModalidadDelPredial.TRIMESTRAL,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("250000.00"),
                        Dinero.de("250000.00"),
                        Porcentaje.total(),
                        Dinero.CERO));

        MvcResult corrida = mvc.perform(asentarLaCorrida("TODOS", null)).andReturn();

        String cuerpo = corrida.getResponse().getContentAsString();
        assertThat(corrida.getResponse().getStatus()).as(cuerpo).isEqualTo(201);
        assertThat(cuerpo).as(cuerpo).contains("C-001").contains("RT-014-c02");
        assertThat(determinaciones.determinados)
                .as("el inafecto no se determina: queda observado, a la vista")
                .isEmpty();
    }

    /** Asienta C-001 con su predio 11 de 250 000,00 y el exonerado que se le diga (#332). */
    private static org.springframework.test.web.servlet.RequestBuilder asentarConExonerado(
            String valuoExonerado) {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,"
                                + "\"codContribuyente\":\"C-001\",\"ejercicio\":\"2026\","
                                + "\"observacion\":\"Determinacion del templo inafecto\","
                                + "\"predios\":[{\"predioId\":11,\"autovaluo\":\"250000.00\","
                                + "\"valuoExonerado\":\""
                                + valuoExonerado
                                + "\"}]}");
    }

    private static BeneficioRegistrado pensionista() {
        return new BeneficioRegistrado(
                "PENSIONISTA",
                "DEDUCCION",
                "PREDIAL",
                null,
                null,
                "TUO LTM art. 19",
                LocalDate.parse("2024-03-01"),
                null);
    }

    /**
     * <b>El sector del alcance es el de la ficha al 1 de enero</b> (#328).
     *
     * <p>P1 esta en el sector 03 al 1 de enero y una resectorizacion lo pasa al 05 el 1 de junio.
     * La corrida de agosto «por sector 03» es la emision de los predios que en 2026 estaban en el
     * 03: con la ficha del dia de la corrida, A quedaba fuera de su sector y de ningun otro.
     */
    @Test
    @DisplayName("#328 — el sector del alcance es el de la ficha al 1 de enero, no el de hoy")
    void elSectorDelAlcanceEsElDelPrimeroDeEnero() throws Exception {
        predios.con(501L, 11L, "10001", "AV. GRAU 100", Porcentaje.total());
        fichas.sector(11L, "03", "2026-06-01", "05");
        sembrarUnPadronQueSeRecalcula();

        mvc.perform(asentarLaCorrida("SECTOR", "03")).andReturn();

        assertThat(determinaciones.determinados)
                .as("la emision del sector 03 de 2026 es la de los predios que en 2026 eran del 03")
                .containsExactly(501L);
    }

    private static org.springframework.test.web.servlet.RequestBuilder asentarLaCorrida(
            String alcance, @Nullable String sector) {
        return post("/rentas/api/v1/rentas/predial/calculo-masivo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"ejercicio\":\"2026\","
                                + "\"alcance\":\""
                                + alcance
                                + "\","
                                + (sector == null ? "" : "\"sector\":\"" + sector + "\",")
                                + "\"recalculaYaEmitidos\":true,"
                                + "\"observacion\":\"Emision anual del ejercicio\"}");
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

    /**
     * <b>La corrida que ASIENTA sin derecho de emision no deja a nadie determinado</b> (#359).
     *
     * <p>Dos contribuyentes con el mismo conjunto, y asentando: con uno solo, «el primero queda
     * confirmado antes del 422» y «nadie queda confirmado» se confunden si el unico es el que
     * falla. Hasta #359 el primero del padron quedaba asentado y auditado dentro de {@code
     * individual.determinar}, la corrida contestaba 422 y no escribia ninguna {@code
     * corrida_predial}: una determinacion suelta que ningun informe explica.
     *
     * <p>El cuadro es el completo menos el derecho —con las cuotas y el punto {@code CUOTA}
     * publicados—, para que el rojo no pueda salir de otra pieza que la que se mide.
     */
    @Test
    @DisplayName(
            "#359 — la corrida que asienta sin derecho de emision: 422, cero filas y sin rastro")
    void laCorridaQueAsientaSinDerechoNoDejaANadieDeterminado() throws Exception {
        sembrarDosContribuyentes();
        mvc = montar(cuadroCompletoMenosElDerecho());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,"
                                                        + "\"ejercicio\":\"2026\","
                                                        + "\"recalculaYaEmitidos\":true,"
                                                        + "\"observacion\":\"Emision anual del"
                                                        + " ejercicio\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("DERECHO_EMISION_PREDIAL");
        assertThat(determinaciones.determinados)
                .as("ni el primero del padron: la falta es del conjunto y le pasa a los dos")
                .isEmpty();
        assertThat(auditoria.registros).as("ni su ALTA").isEmpty();
        assertThat(
                        mvc.perform(get("/rentas/api/v1/rentas/predial/corridas/ultima"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("la corrida cortada no deja rastro, y ahora tampoco deja nada que explicar")
                .isEqualTo(204);
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"codContribuyente\":\"C-001\","
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
                ModalidadDelPredial.TRIMESTRAL,
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
                ModalidadDelPredial.CONTADO,
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
                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"ejercicio\":\"2026\","
                                + "\"recalculaYaEmitidos\":true}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder simularIndividual() {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":true,\"codContribuyente\":\"C-001\",\"ejercicio\":\"2026\","
                                + "\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder asentarSinObservacion() {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"codContribuyente\":\"C-001\",\"ejercicio\":\"2026\","
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
                                                "{\"modalidad\":\"TRIMESTRAL\",\"observacion\":\"Emision anual\","
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

        @Override
        public java.util.Optional<kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada> delPredio(
                kamayuk.rentas.dominio.Ejercicio ejercicio, long predioId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Map<Long, kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada>
                deLosPredios(
                        kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.List<Long> predios) {
            // Ninguna valuacion sellada: la determinacion cae a los autovaluos DECLARADOS, que es
            // lo que estas pruebas miden. Que la sellada mande cuando la hay lo mide #38 aparte.
            return java.util.Map.of();
        }
    }

    // ---------------------------------------------------------------- #207

    @Test
    @DisplayName("#207 — determinar y volver a leer da el MISMO centimo, sin volver a determinar")
    void leerLaDeterminacionGuardada() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult escritura =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .param("codContribuyente", "C-001")
                                        .param("ano", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"observacion\":\"Determinacion"
                                                        + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();
        assertThat(escritura.getResponse().getStatus()).isEqualTo(201);
        int insertadasTrasDeterminar = determinaciones.insertadas;

        MvcResult lectura = leer("C-001", "2026");

        assertThat(lectura.getResponse().getStatus()).isEqualTo(200);
        String json = lectura.getResponse().getContentAsString();
        String escrito = escritura.getResponse().getContentAsString();

        assertThat(json)
                .as("la lectura afirma lo que la fila guarda, no lo que el POST devolvio de paso")
                .contains("\"baseImponible\":\"100000.00\"")
                .contains("\"impuestoInsoluto\":\"270.00\"")
                .contains("\"reglasAplicadas\":[\"RT-011\",\"RT-013\",\"RT-014\"]");
        assertThat(json)
                .as("y lo que no esta guardado sale del conjunto SELLADO que la fila fijo")
                .contains("\"conjuntoId\":77")
                .contains("\"conjunto\":\"2026 v1\"")
                .contains("\"uit\":\"5500.00\"")
                .contains("\"alicuota\":\"0.2\"")
                .contains("\"derechoDeEmision\":\"4.50\"")
                .contains("\"totalAPagar\":\"274.50\"");
        assertThat(escrito)
                .as("el mismo centimo que la escritura: regla 6, y por eso no se guarda dos veces")
                .contains("\"totalAPagar\":\"274.50\"")
                .contains("\"impuestoInsoluto\":\"270.00\"");

        assertThat(determinaciones.insertadas)
                .as("leer no determina: el POST inserta una fila CADA vez que se le llama")
                .isEqualTo(insertadasTrasDeterminar);
    }

    @Test
    @DisplayName("#234 — la lectura publica el cronograma que la fila DICE: al contado, UNA cuota")
    void laLecturaPublicaElCronogramaAlContado() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc.perform(determinarCon("CONTADO")).andReturn();

        String json = leer("C-001", "2026").getResponse().getContentAsString();

        assertThat(json)
                .as("y hay cuerpo que mirar: con un 204 esto pasaria sobre la cadena vacia")
                .contains("\"impuestoInsoluto\"");
        // CONTADO y no TRIMESTRAL, y UNA cuota y no cuatro: es lo que distingue «publica la
        // modalidad que la fila guarda» de «publica siempre la trimestral supuesta», que era
        // exactamente el defecto de #234. Con el montaje trimestral las dos pasan en verde.
        assertThat(json).contains("\"modalidad\":\"CONTADO\"");
        assertThat(json).contains("\"vencimiento\":\"2026-02-27\"");
        assertThat(contarCuotas(json))
                .as("el articulo 15 a) es una sola cuota, no las cuatro del inciso b)")
                .isEqualTo(1);
        assertThat(json)
                .as("y no se cuela `simulacion`: una simulacion no deja fila que leer")
                .doesNotContain("\"simulacion\"");
    }

    @Test
    @DisplayName("#234 — y con la trimestral son las CUATRO fechas del conjunto sellado")
    void laLecturaPublicaElCronogramaTrimestral() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc.perform(determinarCon("TRIMESTRAL")).andReturn();

        String json = leer("C-001", "2026").getResponse().getContentAsString();

        assertThat(json).contains("\"modalidad\":\"TRIMESTRAL\"");
        assertThat(contarCuotas(json)).isEqualTo(4);
        assertThat(json)
                .as("las fechas no se calculan: salen del conjunto que ESA determinacion fijo")
                .contains("\"vencimiento\":\"2026-02-27\"")
                .contains("\"vencimiento\":\"2026-11-30\"");
    }

    @Test
    @DisplayName("#234 — una fila anterior a V21 publica el cronograma EN BLANCO, no el supuesto")
    void laLecturaDeUnaFilaAnteriorAV21NoInventaElCronograma() throws Exception {
        predios.con(501L, 11L, "10001", "AV. GRAU 100", Porcentaje.total());
        determinaciones.sembrarSinModalidad(
                EJERCICIO,
                9L,
                501L,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));

        String json = leer("C-001", "2026").getResponse().getContentAsString();

        assertThat(json)
                .as("y hay cuerpo que mirar: con un 204 esto pasaria sobre la cadena vacia")
                .contains("\"impuestoInsoluto\"");
        assertThat(json)
                .as(
                        "nulo significa «esta fila es anterior a V21», no «al contado»: suponer la"
                                + " trimestral publicaria un cronograma que puede no ser el que el"
                                + " contribuyente recibio")
                .contains("\"modalidad\":null")
                .contains("\"cuotas\":[]")
                .doesNotContain("\"vencimiento\"");
    }

    @Test
    @DisplayName("#234 — determinar sin decir la modalidad es 422, y la nombra")
    void determinarSinModalidadEs422() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .param("codContribuyente", "C-001")
                                        .param("ano", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"observacion\":\"Determinacion"
                                                        + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "antes de #234 esto determinaba TRIMESTRAL en silencio, y con V21 esa"
                                + " suposicion quedaria ESCRITA en la fila")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("modalidad")
                .contains("CONTADO, TRIMESTRAL");
        assertThat(determinaciones.insertadas)
                .as("y no se asienta nada: el rechazo es antes de escribir")
                .isZero();
    }

    @Test
    @DisplayName("#234 — una modalidad que no es del articulo 15 es 422, y dice cuales si")
    void unaModalidadInventadaEs422() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        MvcResult resultado = mvc.perform(determinarCon("MENSUAL")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "antes de #234 'MENSUAL' devolvia las cuatro fechas trimestrales con esa"
                                + " etiqueta encima, y sin error de ninguna clase")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("MENSUAL")
                .contains("CONTADO, TRIMESTRAL");
    }

    @Test
    @DisplayName("#234 — la corrida masiva tampoco supone: sin modalidad, 422")
    void laCorridaMasivaSinModalidadEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-masivo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true,\"ejercicio\":\"2026\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "una emision anual escribe la modalidad en decenas de miles de filas: suponerla"
                                + " ahi es el mismo defecto multiplicado")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("modalidad");
    }

    @Test
    @DisplayName("#207 — un contribuyente sin determinacion de ese ejercicio es 204, no 404")
    void sinDeterminacionDeEseEjercicioEs204() throws Exception {
        MvcResult resultado = leer("C-001", "2026");

        assertThat(resultado.getResponse().getStatus())
                .as("«todavia no se le ha determinado» no es «ese contribuyente no existe» (#546)")
                .isEqualTo(204);
        assertThat(resultado.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("#207 — un codigo que no esta en el padron es 404, y lo nombra")
    void unCodigoQueNoEstaEnElPadronEs404() throws Exception {
        MvcResult resultado = leer("C-999", "2026");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("C-999");
    }

    @Test
    @DisplayName("#207 — sin decir de quien, 422: no se contesta la determinacion de cualquiera")
    void sinContribuyenteEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predial/determinaciones")
                                        .param("ano", "2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("codContribuyente");
    }

    @Test
    @DisplayName("#207 — el acceso es el de su pantalla, con LECTURA y no con REGISTRO")
    void elAccesoDeLaLectura() throws Exception {
        leer("C-001", "2026");

        assertThat(comprobador.acceso).isEqualTo("predial_individual");
        assertThat(comprobador.privilegio)
                .as("leer una determinacion no es determinarla")
                .isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName(
            "#207 — y lee el conjunto que la determinacion FIJO, no el que rige hoy (ARQ-09 §3)")
    void laLecturaUsaElConjuntoSelladoDeLaDeterminacion() throws Exception {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-individual")
                                .param("codContribuyente", "C-001")
                                .param("ano", "2026")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"modalidad\":\"TRIMESTRAL\",\"simulacion\":false,\"observacion\":\"Determinacion"
                                                + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                .andReturn();

        // Se sella una SEGUNDA version del mismo ejercicio, con otra UIT y otra alicuota. La
        // determinacion de arriba no cambia: fijo su `conjunto_id` y ahi se queda.
        mvc = montarCon(conDosVersiones(cuadroCompleto(), cuadroDeLaSegundaVersion()));

        String json = leer("C-001", "2026").getResponse().getContentAsString();

        assertThat(json)
                .as(
                        "resolver con `vigenteEn` publicaria unos tramos que esta determinacion"
                                + " nunca uso, y el contribuyente ya tiene el papel")
                .contains("\"uit\":\"5500.00\"")
                .contains("\"alicuota\":\"0.2\"")
                .doesNotContain("\"uit\":\"9999.00\"");
    }

    /**
     * <b>La lectura publica los importes de cierre con dos decimales, con la forma que entrega la
     * cache</b> (#354).
     *
     * <p>Todas las demas pruebas de esta clase siembran la UIT como {@code "5500.00"}, y esa es la
     * muestra uniforme que escondia el defecto: la copia local de {@code normativa} no la entrega
     * asi. {@code valor_numerico} es {@code monto_calc}, o sea {@code numeric(18,6)}, y {@code
     * CacheDeSnapshotsJdbc} lo lee con {@code getObject().toString()}: llega {@code "5500.000000"}.
     * Sin tocarla, {@code Dinero.toString()} la publicaba tal cual; el minimo imponible salia con
     * catorce decimales —{@code Dinero.por} no redondea, por diseño— y los limites de tramo con
     * doce. La interfaz exige dos decimales como mucho ({@code formatearImporte}), y con razon: asi
     * que el 100 % de estas lecturas tumbaba la pantalla el dia que el conjunto se sellara.
     *
     * <p>La base, {@code 400000.75}, recorre los TRES tramos y deja en el ultimo una porcion con
     * centimos —{@code 70000.75}—: con una base redonda, una porcion recortada a entero pasaria. Y
     * el aporte de ese tramo, {@code 700.0075}, <b>sigue entero</b>: es un intermedio sin redondear
     * (ADR-0018, #245) y recortarlo seria redondear en el borde lo que el calculo no redondeo.
     */
    @Test
    @DisplayName("#354 — la lectura publica uit, minimo, limites y porciones con dos decimales")
    void laLecturaPublicaLosImportesDeCierreConDosDecimales() throws Exception {
        mvc = montar(cuadroConLaFormaDeLaCache());
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        mvc.perform(
                        post("/rentas/api/v1/rentas/predial/calculo-individual")
                                .param("codContribuyente", "C-001")
                                .param("ano", "2026")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"modalidad\":\"CONTADO\",\"simulacion\":false,\"observacion\":\"Determinacion"
                                                + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"400000.75\"}]}"))
                .andReturn();

        MvcResult resultado = leer("C-001", "2026");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json)
                .as(
                        "la cache entrega numeric(18,6): publicarlo con toString() hace que la"
                                + " interfaz reviente al formatear el importe")
                .contains("\"uit\":\"5500.00\"")
                .contains("\"minimoImponible\":\"33.00\"")
                .contains("\"limiteSuperior\":\"82500.00\"")
                .contains("\"limiteSuperior\":\"330000.00\"")
                .contains("\"porcionGravada\":\"82500.00\"")
                .contains("\"porcionGravada\":\"247500.00\"")
                .contains("\"porcionGravada\":\"70000.75\"")
                .contains("\"derechoDeEmision\":\"4.50\"")
                .contains("\"totalAPagar\":\"2354.51\"");
        assertThat(json)
                .as("el aporte es un intermedio sin redondear (#245): no pasa por el ayudante")
                .containsPattern("\"aporte\":\"700\\.0075\\d*\"");
    }

    /**
     * <b>Y no redondea</b>: un importe de cierre con un centimo partido hace fallar la lectura, en
     * vez de publicarse redondeado (#354, ronda 1).
     *
     * <p>La prueba de arriba siembra cifras exactas a dos decimales —{@code 5500.000000} recortado
     * a {@code 5500.00} no pierde nada—, asi que ahi {@code UNNECESSARY}, {@code HALF_UP} y {@code
     * DOWN} dan la misma cadena: medido, cambiar el modo por {@code HALF_UP} la dejaba verde, y la
     * propiedad que el javadoc de {@code importeDeCierre} destaca no la vigilaba nadie. Aqui la UIT
     * del conjunto sellado es {@code 5500.005}: con {@code HALF_UP} saldria {@code "5500.01"} y un
     * 200, una cifra que ninguna ordenanza fijo y que nadie decidio redondear (D-03, ADR-0018). Lo
     * que se exige es el error: el 500 del manejador, con la {@link ArithmeticException} de {@code
     * setScale} como causa.
     *
     * <p>La escritura sigue en 201 porque {@code DeterminacionPredialResource} no pasa por el
     * ayudante (queda fuera de #354): asi se sabe que lo que falla es la lectura, y no el calculo.
     */
    @Test
    @DisplayName("#354 — un importe de cierre con un centimo partido falla, y no se redondea")
    void unImporteDeCierreConUnCentimoPartidoFallaYNoSeRedondea() throws Exception {
        mvc = montar(cuadroConUnCentimoPartido());
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        MvcResult escrita =
                mvc.perform(
                                post("/rentas/api/v1/rentas/predial/calculo-individual")
                                        .param("codContribuyente", "C-001")
                                        .param("ano", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"modalidad\":\"CONTADO\",\"simulacion\":false,\"observacion\":\"Determinacion"
                                                        + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}"))
                        .andReturn();
        assertThat(escrita.getResponse().getStatus())
                .as("la determinacion se guarda: lo que se prueba es la lectura")
                .isEqualTo(201);

        MvcResult resultado = leer("C-001", "2026");

        assertThat(resultado.getResponse().getContentAsString())
                .as("redondear la UIT en el borde del contrato es aritmetica que nadie decidio")
                .doesNotContain("\"uit\":\"5500.01\"")
                .doesNotContain("\"uit\":\"5500.00\"");
        assertThat(resultado.getResponse().getStatus())
                .as("un centimo partido no se publica: la lectura falla")
                .isEqualTo(500);
        assertThat(resultado.getResolvedException())
                .as("y falla por la escala del contrato, no por otra cosa")
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Rounding necessary");
    }

    /**
     * Un lector con dos conjuntos: el que rige HOY y el que una determinacion vieja fijo.
     *
     * <p>Sin los dos distintos, `delConjunto` y `vigenteEn` son indistinguibles y la prueba de
     * arriba saldria verde con cualquiera de las dos — que es el defecto que existe para impedir.
     */
    private static LectorDeParametros conDosVersiones(
            ParametrosSellados elDeLaDeterminacion, ParametrosSellados elDeHoy) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return elDeHoy;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return elDeLaDeterminacion;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(78L);
            }
        };
    }

    /** La segunda version sellada del mismo ejercicio: otra UIT y otra escala. */
    private static ParametrosSellados cuadroDeLaSegundaVersion() {
        return conRedondeo(
                        ParametrosSellados.de(EJERCICIO, 2)
                                .numero("UIT", null, ValorNormativo.de("9999.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.9"))
                                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("9.90")))
                .construir();
    }

    /** Determina C-001 con la modalidad que se le diga, para poder contrastar dos cronogramas. */
    private static org.springframework.test.web.servlet.RequestBuilder determinarCon(
            String modalidad) {
        return post("/rentas/api/v1/rentas/predial/calculo-individual")
                .param("codContribuyente", "C-001")
                .param("ano", "2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"modalidad\":\""
                                + modalidad
                                + "\",\"simulacion\":false,\"observacion\":\"Determinacion"
                                + " anual\",\"predios\":[{\"predioId\":11,\"autovaluo\":\"100000.00\"}]}");
    }

    /** Cuantas cuotas trae el JSON, contadas por sus vencimientos. */
    private static int contarCuotas(String json) {
        return json.split("\"vencimiento\"", -1).length - 1;
    }

    private MvcResult leer(String codContribuyente, String ano) throws Exception {
        return mvc.perform(
                        get("/rentas/api/v1/rentas/predial/determinaciones")
                                .param("codContribuyente", codContribuyente)
                                .param("ano", ano))
                .andReturn();
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
                        fichas,
                        new DirectorioDePrueba(),
                        cuadro,
                        new kamayuk.rentas.nucleo.dobles.ValuacionesSelladasEnMemoria(),
                        (contribuyenteId, fecha) ->
                                beneficios.getOrDefault(contribuyenteId, List.of()).stream()
                                        .filter(beneficio -> beneficio.rigeEn(fecha))
                                        .toList(),
                        new RegistrarDeterminacionPredial(determinaciones, lector, auditoria),
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
                        fichas,
                        rastro,
                        new CandadoDeEmision(valuacion),
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(
                        new PredialController(
                                individual,
                                masivo,
                                rastro,
                                new kamayuk.rentas.nucleo.aplicacion
                                        .ConsultaDeLaDeterminacionPredial(
                                        new DirectorioDePrueba(), determinaciones, cuadro),
                                RELOJ))
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
                                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                                // El articulo 15 a): la clave del contado, que #234 vuelve pedible
                                // desde la
                                // fila guardada. Sin ella el montaje solo sabria dibujar el
                                // fraccionado.
                                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27"))
                .construir();
    }

    /**
     * El cuadro completo <b>con la forma que entrega la cache</b>: cada numero con los seis
     * decimales de {@code monto_calc} (#354). Ver {@link
     * #laLecturaPublicaLosImportesDeCierreConDosDecimales()}.
     */
    private static ParametrosSellados cuadroConLaFormaDeLaCache() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.000000"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.200000"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15.000000"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.600000"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60.000000"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.000000"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.600000"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.500000"))
                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "CUOTA", "HALF_UP")
                .construir();
    }

    /**
     * El cuadro con la forma de la cache, pero con una UIT de <b>centimo partido</b>: {@code
     * 5500.005000}. Ninguna UIT real es asi; es la cifra minima que distingue «no redondea» de
     * «redondea a dos». Ver {@link #unImporteDeCierreConUnCentimoPartidoFallaYNoSeRedondea()}.
     */
    private static ParametrosSellados cuadroConUnCentimoPartido() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.005000"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.200000"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15.000000"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.600000"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60.000000"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.000000"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.600000"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.500000"))
                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2.000000"))
                .texto("REDONDEO", "CUOTA", "HALF_UP")
                .construir();
    }

    /**
     * {@link #cuadroCompleto()} sin {@code DERECHO_EMISION_PREDIAL} y con todo lo demas (#359): las
     * cuotas trimestrales y el punto {@code CUOTA} siguen publicados. {@link
     * #cuadroSinDerechoDeEmision()} no sirve para eso: tampoco trae los vencimientos.
     */
    private static ParametrosSellados cuadroCompletoMenosElDerecho() {
        return conRedondeo(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27"))
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
        private final Map<Long, List<Cuota>> porContribuyente = new LinkedHashMap<>();

        /**
         * Una cuota con su vigencia (#328); {@code desde} y {@code hasta} nulos son «siempre».
         *
         * <p>Hasta #328 este doble contestaba lo mismo a cualquier fecha, y con el reloj fijo en
         * agosto «el padron de hoy» y «el padron al 1 de enero» eran la misma lista: la corrida
         * podia leer la titularidad del dia de la corrida y ninguna prueba lo distinguia.
         */
        private record Cuota(
                PredioDelContribuyente predio,
                @Nullable LocalDate desde,
                @Nullable LocalDate hasta) {

            boolean vigenteEn(LocalDate fecha) {
                return (desde == null || !fecha.isBefore(desde))
                        && (hasta == null || !fecha.isAfter(hasta));
            }
        }

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
                    .add(
                            new Cuota(
                                    new PredioDelContribuyente(
                                            predioId, codigo, "URBANO", direccion, cuota),
                                    null,
                                    null));
        }

        void conVigencia(
                long contribuyenteId,
                long predioId,
                String codigo,
                String direccion,
                String desde,
                @Nullable String hasta) {
            porContribuyente
                    .computeIfAbsent(contribuyenteId, quien -> new ArrayList<>())
                    .add(
                            new Cuota(
                                    new PredioDelContribuyente(
                                            predioId,
                                            codigo,
                                            "URBANO",
                                            direccion,
                                            Porcentaje.total()),
                                    LocalDate.parse(desde),
                                    hasta == null ? null : LocalDate.parse(hasta)));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return porContribuyente.getOrDefault(contribuyenteId, List.of()).stream()
                    .filter(cuota -> cuota.vigenteEn(fecha))
                    .map(Cuota::predio)
                    .toList();
        }
    }

    /**
     * Las fichas: por omision ninguna, y con {@link #sector} un predio que cambia de sector en una
     * fecha (#328). Contesta segun la fecha, igual que el padron.
     */
    private static final class FichasDePrueba implements LectorDeCaracteristicas {

        private long predioId;
        private @Nullable String sectorAntes;
        private @Nullable LocalDate cambiaEl;
        private @Nullable String sectorDespues;

        void sector(long predio, String antes, String cambia, String despues) {
            this.predioId = predio;
            this.sectorAntes = antes;
            this.cambiaEl = LocalDate.parse(cambia);
            this.sectorDespues = despues;
        }

        @Override
        public Optional<CaracteristicasDelPredio> de(long predio, LocalDate fecha) {
            if (cambiaEl == null || predio != predioId) {
                return Optional.empty();
            }
            String sector = fecha.isBefore(cambiaEl) ? sectorAntes : sectorDespues;
            return Optional.of(new CaracteristicasDelPredio(null, sector, null));
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
            sembrar(
                    ejercicio,
                    id,
                    501L,
                    EstadoDeDeterminacion.EMITIDA,
                    ModalidadDelPredial.TRIMESTRAL,
                    detalle);
        }

        /**
         * Una fila ANTERIOR a V21: la que no dice con que modalidad se emitio (#234).
         *
         * <p>Existe para que el montaje no sea uniforme. Con todas las filas trayendo modalidad,
         * «publica el cronograma que la fila dice» y «publica siempre un cronograma» son
         * indistinguibles.
         */
        void sembrarSinModalidad(
                Ejercicio ejercicio,
                long id,
                long contribuyenteId,
                DetalleDeterminacionPredio... detalle) {
            sembrar(ejercicio, id, contribuyenteId, EstadoDeDeterminacion.EMITIDA, null, detalle);
        }

        void sembrar(
                Ejercicio ejercicio,
                long id,
                long contribuyenteId,
                EstadoDeDeterminacion estado,
                @Nullable ModalidadDelPredial modalidad,
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
                            "siembra",
                            modalidad));
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
            // Filtra por ejercicio desde #207: sin eso, «este contribuyente no tiene determinacion
            // de ESE ejercicio» no se puede medir, y el 204 de la lectura saldria verde por
            // casualidad.
            return cabeceras.stream()
                    .filter(c -> c.contribuyenteId() == contribuyenteId)
                    .filter(c -> c.ejercicio().equals(ejercicio))
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
            Determinacion guardada =
                    new Determinacion(
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
                            "cajero.ventanilla",
                            determinacion.modalidad());
            // Lo que inserta queda guardado desde #207. Hasta entonces este doble aceptaba la
            // escritura y la olvidaba, asi que ninguna prueba podia determinar y volver a leer.
            cabeceras.add(guardada);
            detallePorId.put(java.util.Objects.requireNonNull(guardada.id()), List.copyOf(detalle));
            return guardada;
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
            implements kamayuk.rentas.nucleo.dominio.CorridaDeEmisionRepository {

        private final java.util.List<kamayuk.rentas.nucleo.dominio.CorridaDeEmision> guardadas =
                new java.util.ArrayList<>();

        @Override
        public kamayuk.rentas.nucleo.dominio.CorridaDeEmision guardar(
                kamayuk.rentas.nucleo.dominio.CorridaDeEmision corrida,
                kamayuk.rentas.dominio.Observacion observacion) {
            kamayuk.rentas.nucleo.dominio.CorridaDeEmision conId =
                    new kamayuk.rentas.nucleo.dominio.CorridaDeEmision(
                            (long) (guardadas.size() + 1),
                            corrida.ejercicio(),
                            corrida.alcance(),
                            corrida.sector(),
                            corrida.codigoDesde(),
                            corrida.codigoHasta(),
                            corrida.modalidad(),
                            corrida.simulacion(),
                            corrida.conjunto(),
                            corrida.conjuntoId(),
                            corrida.derechoDeEmision(),
                            corrida.leidos(),
                            corrida.determinados(),
                            corrida.montoEmitido(),
                            corrida.fechaCalculo(),
                            corrida.observados());
            guardadas.add(conId);
            return conId;
        }

        @Override
        public java.util.Optional<kamayuk.rentas.nucleo.dominio.CorridaDeEmision> ultimaDe(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return guardadas.reversed().stream()
                    .filter(corrida -> corrida.ejercicio().equals(ejercicio))
                    .findFirst();
        }

        @Override
        public java.util.List<kamayuk.rentas.nucleo.dominio.CorridaDeEmision> ultimas(int cuantas) {
            return guardadas.reversed().stream().limit(cuantas).toList();
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<
                        kamayuk.rentas.nucleo.dominio.CorridaDeEmision.Observado>
                observadosDe(long corridaId, kamayuk.rentas.compartido.Paginacion paginacion) {
            var filas =
                    guardadas.stream()
                            .filter(corrida -> java.util.Objects.equals(corrida.id(), corridaId))
                            .findFirst()
                            .map(kamayuk.rentas.nucleo.dominio.CorridaDeEmision::observados)
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

        @Override
        public java.util.Optional<kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada> delPredio(
                kamayuk.rentas.dominio.Ejercicio ejercicio, long predioId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Map<Long, kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada>
                deLosPredios(
                        kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.List<Long> predios) {
            // Ninguna valuacion sellada: la determinacion cae a los autovaluos DECLARADOS, que es
            // lo que estas pruebas miden. Que la sellada mande cuando la hay lo mide #38 aparte.
            return java.util.Map.of();
        }
    }
}
