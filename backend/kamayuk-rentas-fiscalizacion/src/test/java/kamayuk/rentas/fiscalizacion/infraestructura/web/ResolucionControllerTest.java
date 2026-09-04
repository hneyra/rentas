package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeResoluciones;
import kamayuk.rentas.fiscalizacion.aplicacion.TransferirARentas;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.CargosEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.DocumentosEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.PadronQueVersiona;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #52 — Capa web. Se prueba el transporte, no la persistencia: eso lo verifica {@code
 * TransferenciaJdbcTest} contra PostgreSQL real.
 */
@DisplayName("Capa web — transferencia a rentas y resolucion de determinacion")
class ResolucionControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Observacion OBSERVACION =
            Observacion.de("Se transfiere lo hallado en la inspeccion");
    private static final long PREDIO = 20L;
    private static final long CONTRIBUYENTE = 10L;

    private DocumentosEnMemoria documentos;
    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ResolucionesEnMemoria resoluciones;
    private PadronQueVersiona padron;
    private MockMvc mvc;
    private Liquidacion liquidacion;

    @BeforeEach
    void armar() {
        ActasEnMemoria actas = new ActasEnMemoria();
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        resoluciones = new ResolucionesEnMemoria();
        padron = new PadronQueVersiona().con(PREDIO, "120.00", "CASA_HABITACION");

        Clock reloj = Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        ContribuyentesDeMentira directorio =
                new ContribuyentesDeMentira()
                        .con(CONTRIBUYENTE, "C-0010", "PEREZ, JUAN", "Jr. Union 100");

        long actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                null,
                                LocalDate.of(2026, 3, 1),
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                "ampliacion",
                                OBSERVACION));
        liquidacion =
                liquidaciones.insertar(
                        Liquidacion.primera(
                                "LIQ-2026-000001",
                                new Ejercicio(2026),
                                1L,
                                actaId,
                                new Ejercicio(2024),
                                new Ejercicio(2024),
                                TipoDeFiscalizacion.CIERTA,
                                "Ampliacion detectada",
                                HOY,
                                OBSERVACION),
                        List.of(
                                new LineaDeLiquidacion(
                                        null,
                                        null,
                                        new Ejercicio(2024),
                                        41L,
                                        PREDIO,
                                        null,
                                        CondicionFiscalizada.SUBVALUADOR,
                                        AreaM2.de("120.00"),
                                        AreaM2.de("300.00"),
                                        "CASA_HABITACION",
                                        "COMERCIO",
                                        Dinero.de("30000.00"),
                                        Dinero.de("75000.00"),
                                        Dinero.de("450.00"),
                                        null)));
        movimientos.insertar(
                MovimientoDeLiquidacion.apertura(
                        liquidacion.identificador(), HOY, "emitida", OBSERVACION));
        movimientos.insertar(
                MovimientoDeLiquidacion.cambioDeEstado(
                        liquidacion.identificador(),
                        EstadoDeLiquidacion.LIQUIDADA,
                        HOY,
                        "cerrada",
                        OBSERVACION));

        documentos = new DocumentosEnMemoria();
        // El MISMO emisor para la transferencia y para la consulta: el papel que se descarga
        // tiene que ser el que la transferencia emitio, no otro dibujado aparte (#593).
        EmitirDocumento emisor =
                new EmitirDocumento(
                        documentos,
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        registro -> {},
                        reloj);

        TransferirARentas transferir =
                new TransferirARentas(
                        liquidaciones,
                        movimientos,
                        actas,
                        resoluciones,
                        padron,
                        new CargosEnMemoria(),
                        directorio,
                        emisor,
                        registro -> {},
                        reloj);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ResolucionController(
                                        transferir,
                                        new ConsultaDeResoluciones(
                                                resoluciones, liquidaciones, directorio, emisor),
                                        reloj))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                // El de bytes ademas del de JSON: la resolucion sale tambien como
                                // documento, y el montaje autonomo reemplaza la lista entera.
                                new ByteArrayHttpMessageConverter(),
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @Test
    @DisplayName("transfiere y devuelve 201 con la resolucion, sus versiones y sus cargos")
    void transfiereYDevuelve201() throws Exception {
        MvcResult resultado = transferir(cuerpoCompleto());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"numero\":\"RDF-2026-000001\"")
                .contains("\"nLiquidacion\":\"LIQ-2026-000001\"")
                .contains("\"documentoSustento\":\"ACTA-2026-000001\"")
                .contains("\"cargosAsentados\":1")
                .contains("\"diferencia\":\"450.00\"");
        assertThat(cuerpo)
                .as("la multa sigue sin cifra: es D-02c (#198), y un cero se leeria como cero")
                .contains("\"multa\":null")
                .contains("\"total\":null");
        assertThat(padron.escrituras()).isEqualTo(1);
    }

    @Test
    @DisplayName("la respuesta dice a que fecha estan sus cifras (regla 9)")
    void laRespuestaLlevaSuFecha() throws Exception {
        String cuerpo = transferir(cuerpoCompleto()).getResponse().getContentAsString();

        assertThat(cuerpo).contains("\"aLaFecha\":\"2026-06-15\"");
    }

    @Test
    @DisplayName("sin observacion no se transfiere: 422 y el padron intacto")
    void sinObservacion422() throws Exception {
        MvcResult resultado =
                transferir(
                        """
                        {"nLiquidacion":"LIQ-2026-000001","documentoSustento":"ACTA-1",
                         "sustento":"s","baseLegal":"b"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("observacion");
        assertThat(padron.escrituras()).isZero();
    }

    @Test
    @DisplayName("sin sustento documental, 422")
    void sinSustento422() throws Exception {
        MvcResult resultado =
                transferir(
                        """
                        {"observacion":"Se transfiere lo hallado","nLiquidacion":"LIQ-2026-000001",
                         "sustento":"s","baseLegal":"b"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("documentoSustento");
    }

    @Test
    @DisplayName("una liquidacion que no existe da 404, no 500")
    void liquidacionInexistente404() throws Exception {
        MvcResult resultado =
                transferir(
                        """
                        {"observacion":"Se transfiere lo hallado","nLiquidacion":"LIQ-2026-999999",
                         "documentoSustento":"ACTA-1","sustento":"s","baseLegal":"b"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("transferir dos veces da 409, no 500 ni una segunda version")
    void dosVeces409() throws Exception {
        transferir(cuerpoCompleto());
        MvcResult segunda = transferir(cuerpoCompleto());

        assertThat(segunda.getResponse().getStatus()).isEqualTo(409);
        assertThat(padron.escrituras()).isEqualTo(1);
        assertThat(resoluciones.cuantas()).isEqualTo(1);
    }

    @Test
    @DisplayName("la resolucion se lee por su numero, con su cuadro de determinacion")
    void laResolucionSeLeePorSuNumero() throws Exception {
        transferir(cuerpoCompleto());

        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/resoluciones/RDF-2026-000001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"numero\":\"RDF-2026-000001\"")
                .contains("\"condicion\":\"SUBVALUADOR\"")
                .contains("\"areaDeclarada\":\"120.00\"")
                .contains("\"areaHallada\":\"300.00\"")
                .contains("\"contribuyente\":\"PEREZ, JUAN\"");
        assertThat(cuerpo)
                .as("la consulta no inventa un recuento de cargos: eso es del acto, no de leerlo")
                .contains("\"cargosAsentados\":null");
    }

    @Test
    @DisplayName("una resolucion que no existe da 404")
    void resolucionInexistente404() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/resoluciones/RDF-2026-999999"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("el cuerpo es lista blanca: un area colada no cambia lo que se inscribe")
    void elCuerpoEsListaBlanca() throws Exception {
        // Si la peticion pudiera traer el area, la transferencia inscribiria en el padron lo que
        // alguien teclea en la pantalla y no lo que se hallo en campo, que es exactamente lo que
        // esta frontera existe para impedir.
        transferir(
                """
                {"observacion":"Se transfiere lo hallado","nLiquidacion":"LIQ-2026-000001",
                 "documentoSustento":"ACTA-2026-000001","sustento":"s","baseLegal":"b",
                 "areaHallada":"9999.00","predioId":"777","usoHallado":"INDUSTRIAL"}
                """);

        assertThat(padron.vigenteDe(PREDIO).area())
                .as("el area inscrita es la del acta, no la del cuerpo")
                .isEqualTo(AreaM2.de("300.00"));
        assertThat(padron.vigenteDe(PREDIO).uso()).isEqualTo("COMERCIO");
    }

    // ══════════════════════════════════════════════════════════════════
    // #593 — La resolucion como documento descargable
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("#593 — con formato=PDF devuelve el documento, con su tipo y su nombre")
    void conFormatoPdfDevuelveElDocumento() throws Exception {
        transferir(cuerpoCompleto());

        MvcResult resultado = descargar("RDF-2026-000001", "PDF");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(resultado.getResponse().getHeader("Content-Disposition"))
                .contains("RDF-2026-000001.pdf");
        // Mirar solo el 200 no distingue nada: el handler de JSON tambien lo da. Lo que
        // dice que esto es un PDF son sus primeros bytes.
        assertThat(new String(resultado.getResponse().getContentAsByteArray(), 0, 5, ISO_8859_1))
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("#593 — y en hoja de calculo y en texto enriquecido, los tres de RF-132")
    void losTresFormatos() throws Exception {
        transferir(cuerpoCompleto());

        MvcResult hoja = descargar("RDF-2026-000001", "XLS");
        MvcResult texto = descargar("RDF-2026-000001", "RTF");

        assertThat(hoja.getResponse().getContentType()).isEqualTo("application/vnd.ms-excel");
        assertThat(hoja.getResponse().getHeader("Content-Disposition"))
                .as("el nombre lleva la extension del formato PEDIDO, no la de la emision")
                .contains("RDF-2026-000001.xls");
        assertThat(hoja.getResponse().getContentAsString()).startsWith("<?xml");

        assertThat(texto.getResponse().getContentType()).isEqualTo("application/rtf");
        assertThat(texto.getResponse().getHeader("Content-Disposition"))
                .contains("RDF-2026-000001.rtf");
        assertThat(texto.getResponse().getContentAsString()).startsWith("{\\rtf1");
    }

    @Test
    @DisplayName("#593 — sin formato sigue devolviendo el JSON de siempre")
    void sinFormatoSigueElJson() throws Exception {
        transferir(cuerpoCompleto());

        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/resoluciones/RDF-2026-000001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).startsWith("application/json");
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"numero\":\"RDF-2026-000001\"");
    }

    @Test
    @DisplayName("#593 — un formato que no existe da 422 nombrando los tres, no un PDF")
    void elFormatoDesconocidoDa422() throws Exception {
        transferir(cuerpoCompleto());

        MvcResult resultado = descargar("RDF-2026-000001", "DOCX");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PDF")
                .contains("XLS")
                .contains("RTF")
                .contains("DOCX");
    }

    @Test
    @DisplayName("#593 — y «formato=» vacio tampoco cae en PDF por omision")
    void elFormatoVacioDa422() throws Exception {
        transferir(cuerpoCompleto());

        // `params = "formato"` elige este handler en cuanto el parametro esta, aunque
        // venga vacio: devolver PDF ahi seria contestar con un formato que nadie pidio.
        MvcResult resultado = descargar("RDF-2026-000001", "");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentType()).startsWith("application/problem+json");
    }

    @Test
    @DisplayName("#593, AC 2 — descargar no emite nada: mismos bytes, sin duplicado y sin numerar")
    void descargarNoEmiteNada() throws Exception {
        transferir(cuerpoCompleto());
        int documentosTrasLaTransferencia = documentos.cuantos();

        byte[] primera = descargar("RDF-2026-000001", "PDF").getResponse().getContentAsByteArray();
        byte[] segunda = descargar("RDF-2026-000001", "PDF").getResponse().getContentAsByteArray();

        // El orden importa: la reimpresion y la cuenta fallan diciendo un numero; la
        // comparacion byte a byte falla volcando dos PDF enteros, y no se lee.
        assertThat(documentos.reimpresionesDe("RDF-2026-000001"))
                .as("no se registra una reimpresion por abrir la pantalla de consulta")
                .isZero();
        assertThat(documentos.cuantos())
                .as("ni se gasta un segundo correlativo para el mismo acto")
                .isEqualTo(documentosTrasLaTransferencia);
        assertThat(new String(primera, ISO_8859_1))
                .as("y el papel no sale marcado: es la primera vez que estos bytes salen")
                .doesNotContain("DUPLICADO");
        assertThat(segunda)
                .as("dos descargas del mismo papel son el mismo papel, byte a byte")
                .isEqualTo(primera);
    }

    @Test
    @DisplayName("#593 — el papel es el que emitio la transferencia, con sus datos guardados")
    void elPapelEsElQueSeEmitio() throws Exception {
        transferir(cuerpoCompleto());

        String papel = descargar("RDF-2026-000001", "RTF").getResponse().getContentAsString();

        assertThat(papel)
                .contains("Resolucion de determinacion")
                .contains("LIQ-2026-000001")
                .contains("PEREZ, JUAN")
                .as("con su cuadro fechado, que es la regla 9 dentro del papel")
                .contains("Determinacion al 2026-06-15")
                .as("y lo que la transferencia dejo inscrito en el padron")
                .contains("Inscripcion en el padron catastral")
                .as("la multa sigue sin cifra (D-02c): el papel imprime una raya, nunca un cero")
                .contains("\\u8212?");
    }

    @Test
    @DisplayName("#593 — una resolucion de otro ejercicio se descarga igual, no con el reloj")
    void laResolucionDeOtroEjercicioSeDescarga() throws Exception {
        // El ejercicio con que se numero el documento sale de la FECHA DE LA RESOLUCION.
        // Resolverlo con el reloj —2026— dejaria sin papel a toda resolucion de otro ano.
        transferir(
                """
                {"observacion":"Se transfiere lo hallado en la inspeccion",
                 "nLiquidacion":"LIQ-2026-000001",
                 "documentoSustento":"ACTA-2026-000001",
                 "sustento":"Ampliacion no declarada",
                 "baseLegal":"TUO del Codigo Tributario, arts. 76 y 77",
                 "fecha":"2025-12-31"}
                """);

        MvcResult resultado = descargar("RDF-2025-000001", "PDF");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("#593 — si el papel ya no se dibuja igual que cuando se emitio, 409 y no un papel")
    void siElPapelYaNoSaleIgual409() throws Exception {
        transferir(cuerpoCompleto());
        documentos.corromperElResumenDe("RDF-2026-000001");

        MvcResult resultado = descargar("RDF-2026-000001", "PDF");

        // 409 y no 500: la peticion esta bien y el sistema no esta roto. Lo que pasa es
        // que entregar esto seria dar un papel distinto al que se emitio con ese numero.
        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat(resultado.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(resultado.getResponse().getContentAsString()).contains("RDF-2026-000001");
    }

    @Test
    @DisplayName("#593 — una resolucion que no existe da 404 tambien pidiendo el documento")
    void elDocumentoDeUnaResolucionInexistente404() throws Exception {
        MvcResult resultado = descargar("RDF-2026-999999", "PDF");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------

    private MvcResult descargar(String numero, String formato) throws Exception {
        return mvc.perform(
                        get("/rentas/api/v1/fiscalizacion/resoluciones/" + numero)
                                .param("formato", formato))
                .andReturn();
    }

    // ------------------------------------------------------------------

    private MvcResult transferir(String cuerpo) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/fiscalizacion/transferencias")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static String cuerpoCompleto() {
        return """
                {"observacion":"Se transfiere lo hallado en la inspeccion",
                 "nLiquidacion":"LIQ-2026-000001",
                 "documentoSustento":"ACTA-2026-000001",
                 "sustento":"Ampliacion no declarada, verificada en inspeccion",
                 "baseLegal":"TUO del Codigo Tributario, arts. 76 y 77",
                 "fecha":"2026-06-15"}
                """;
    }
}
