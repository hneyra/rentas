package kamayuk.rentas.valores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.DocumentoEmitido;
import kamayuk.rentas.documentos.DocumentoRepository;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.aplicacion.ConstruirModeloDeValor;
import kamayuk.rentas.valores.aplicacion.ConsultaDeLaCorridaMasiva;
import kamayuk.rentas.valores.aplicacion.ImprimirCorridaMasiva;
import kamayuk.rentas.valores.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.valores.dominio.EstadoDeItemMasivo;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.OrigenDeCriterio;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #631 — La corrida masiva se imprime: {@code GET /valores/masivo/{id}/impresion}.
 *
 * <p>Se prueba el transporte con {@link ImprimirCorridaMasiva} <b>de verdad</b> y los tres
 * renderizadores reales; lo que es de mentira son los repositorios, porque lo que se mide aqui es
 * que la ruta exista, que entregue un documento por valor generado y que diga por que no cuando no
 * puede. La lectura contra PostgreSQL de la corrida es de {@code GeneracionMasivaJdbcTest}.
 */
@DisplayName("#631 — GET /valores/masivo/{id}/impresion")
class ImpresionDeLaCorridaMasivaControllerTest {

    private static final String RUTA = "/rentas/api/v1/valores/masivo/{id}/impresion";

    private static final LocalDate PROYECTADO_A = LocalDate.of(2026, 3, 15);

    private static final Observacion OBSERVACION = Observacion.de("Corrida de la prueba de #631");

    /** La corrida con dos valores generados y un candidato todavia pendiente. */
    private static final long CON_DOS = 1L;

    /** La corrida que existe y todavia no genero nada. */
    private static final long SIN_GENERAR = 2L;

    private final ValoresEnMemoria valores = new ValoresEnMemoria();
    private final ContribuyentesDeMentira contribuyentes = new ContribuyentesDeMentira();
    private final CorridasDeMentira corridas = new CorridasDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ImpresionDeLaCorridaMasivaController(
                                    new ImprimirCorridaMasiva(
                                            new ConsultaDeLaCorridaMasiva(corridas),
                                            new ConstruirModeloDeValor(valores, contribuyentes),
                                            emitirDocumentoDeVerdad())))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new ByteArrayHttpMessageConverter(),
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    ImpresionDeLaCorridaMasivaControllerTest() {
        contribuyentes.con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR UNO", ""));
        contribuyentes.con(new ResumenDeContribuyente(8L, "C-0008", "TITULAR DOS", ""));
        valores.con(valorDe(1L, "OP-2026-000001", 7L));
        valores.con(valorDe(2L, "OP-2026-000002", 8L));

        corridas.con(CON_DOS);
        corridas.item(new ValorMasivoItem(10L, CON_DOS, 7L, EstadoDeItemMasivo.GENERADO, 1L, null));
        corridas.item(new ValorMasivoItem(11L, CON_DOS, 8L, EstadoDeItemMasivo.GENERADO, 2L, null));
        corridas.item(
                new ValorMasivoItem(12L, CON_DOS, 9L, EstadoDeItemMasivo.PENDIENTE, null, null));

        corridas.con(SIN_GENERAR);
        corridas.item(
                new ValorMasivoItem(
                        20L, SIN_GENERAR, 7L, EstadoDeItemMasivo.PENDIENTE, null, null));
    }

    @Test
    @DisplayName(
            "una corrida de dos valores generados sale en UN archivo con los dos documentos, y"
                    + " el pendiente no")
    void unaCorridaDeDosValoresSaleEnUnArchivoConLosDos() throws Exception {
        MvcResult resultado =
                mvc.perform(MockMvcRequestBuilders.get(RUTA, CON_DOS).param("formato", "PDF"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la ruta existe: hasta #631 la corrida no se podia imprimir desde la aplicacion")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(resultado.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("corrida-masiva-1.zip");

        Map<String, byte[]> documentos =
                entradasDe(resultado.getResponse().getContentAsByteArray());
        assertThat(documentos.keySet())
                .as("un documento por valor GENERADO, nombrado con su numero; el PENDIENTE no sale")
                .containsExactly(
                        "ORDEN-DE-PAGO-N-OP-2026-000001.pdf", "ORDEN-DE-PAGO-N-OP-2026-000002.pdf");
        assertThat(documentos.values())
                .as("cada entrada es un PDF entero, no un trozo del anterior")
                .allSatisfy(
                        pdf ->
                                assertThat(new String(pdf, StandardCharsets.ISO_8859_1))
                                        .startsWith("%PDF-")
                                        .endsWith("%%EOF\n"));
        assertThat(
                        new String(
                                documentos.get("ORDEN-DE-PAGO-N-OP-2026-000002.pdf"),
                                StandardCharsets.ISO_8859_1))
                .as("y cada uno es el de SU valor")
                .contains("OP-2026-000002")
                .doesNotContain("OP-2026-000001");
    }

    @Test
    @DisplayName("el formato vale para todo el archivo: con XLS, las dos entradas son hojas")
    void elFormatoValeParaTodoElArchivo() throws Exception {
        MvcResult resultado =
                mvc.perform(MockMvcRequestBuilders.get(RUTA, CON_DOS).param("formato", "xls"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(entradasDe(resultado.getResponse().getContentAsByteArray()).keySet())
                .containsExactly(
                        "ORDEN-DE-PAGO-N-OP-2026-000001.xls", "ORDEN-DE-PAGO-N-OP-2026-000002.xls");
    }

    @Test
    @DisplayName("una corrida que no existe en esta municipalidad es 404, no un archivo vacio")
    void unaCorridaQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(MockMvcRequestBuilders.get(RUTA, 99L).param("formato", "PDF"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("NO_ENCONTRADO")
                .contains("99");
    }

    @Test
    @DisplayName(
            "una corrida sin ningun valor generado es 409 y dice por que: no hay papel que sacar")
    void unaCorridaSinGenerarEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(MockMvcRequestBuilders.get(RUTA, SIN_GENERAR).param("formato", "PDF"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("CONFLICTO")
                .contains("ventana de lote");
    }

    @Test
    @DisplayName("un formato que no es PDF, XLS ni RTF es 422")
    void unFormatoDesconocidoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(MockMvcRequestBuilders.get(RUTA, CON_DOS).param("formato", "DOCX"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("DOCX");
    }

    /**
     * El acceso que ArchUnit no ve: su regla exige que haya {@code RequiereAcceso}, no cual. Dar la
     * impresion masiva con {@code LECTURA} dejaria sacar del sistema miles de documentos a quien
     * solo puede mirar la pantalla.
     */
    @Test
    @DisplayName("exige IMPRESION sobre «valores_masivo», la opcion de las tres etapas de RF-091")
    void exigeImpresionSobreValoresMasivo() throws Exception {
        RequiereAcceso acceso =
                ImpresionDeLaCorridaMasivaController.class
                        .getMethod("imprimir", long.class, String.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(acceso).isNotNull();
        assertThat(acceso.acceso()).isEqualTo("valores_masivo");
        assertThat(acceso.privilegio()).isEqualTo(Privilegio.IMPRESION);
    }

    // ------------------------------------------------------------------

    /** Las entradas del zip, en su orden, con sus bytes. */
    private static Map<String, byte[]> entradasDe(byte[] zip) throws IOException {
        Map<String, byte[]> entradas = new LinkedHashMap<>();
        try (ZipInputStream lector = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entrada;
            while ((entrada = lector.getNextEntry()) != null) {
                entradas.put(entrada.getName(), lector.readAllBytes());
            }
        }
        return entradas;
    }

    private static Valor valorDe(long id, String numero, long contribuyenteId) {
        return new Valor(
                id,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                new Ejercicio(2026),
                contribuyenteId,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                Dinero.de("1000.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                PROYECTADO_A,
                EstadoDeValor.EMITIDO,
                PROYECTADO_A,
                "prueba",
                OBSERVACION);
    }

    private static EmitirDocumento emitirDocumentoDeVerdad() {
        GeneradorDeDocumentos generador =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorRtf(),
                                new RenderizadorXls()),
                        PuntoDeFirma.SIN_FIRMA,
                        RegimenDeLaInstalacion.REAL);
        return new EmitirDocumento(
                new DocumentosQueNoSeTocan(), generador, registro -> {}, Clock.systemUTC());
    }

    /** Las corridas y sus candidatos, sin base: solo lo que la impresion lee. */
    private static final class CorridasDeMentira implements ValorMasivoRepository {

        private final Map<Long, ValorMasivo> porId = new LinkedHashMap<>();
        private final List<ValorMasivoItem> items = new ArrayList<>();

        void con(long id) {
            porId.put(
                    id,
                    new ValorMasivo(
                            id,
                            TipoValor.ORDEN_DE_PAGO,
                            null,
                            new Ejercicio(2025),
                            new Ejercicio(2026),
                            PROYECTADO_A,
                            OrigenDeCriterio.SELECCION,
                            0,
                            "prueba",
                            null,
                            OBSERVACION));
        }

        void item(ValorMasivoItem item) {
            items.add(item);
        }

        @Override
        public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
            throw new UnsupportedOperationException("imprimir no inicia corridas");
        }

        @Override
        public Optional<ValorMasivo> porId(long id) {
            return Optional.ofNullable(porId.get(id));
        }

        @Override
        public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
            throw new UnsupportedOperationException("imprimir no procesa candidatos");
        }

        @Override
        public List<ValorMasivoItem> itemsGenerados(long corridaId) {
            return items.stream()
                    .filter(item -> item.corridaId() == corridaId)
                    .filter(item -> item.estado() == EstadoDeItemMasivo.GENERADO)
                    .toList();
        }

        @Override
        public List<Long> corridasConPendientes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long contarPendientes(long corridaId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void marcarGenerado(long itemId, long valorId) {
            throw new UnsupportedOperationException("imprimir no mueve ningun candidato");
        }

        @Override
        public void marcarSinDeuda(long itemId) {
            throw new UnsupportedOperationException("imprimir no mueve ningun candidato");
        }
    }

    /** {@link EmitirDocumento#emitirEnLote} no registra nada: imprimir no consume correlativo. */
    private static final class DocumentosQueNoSeTocan implements DocumentoRepository {

        @Override
        public Optional<DocumentoEmitido> porNumero(
                String tipo, Ejercicio ejercicio, String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentoEmitido> de(String tipo, String referencia) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentoEmitido insertar(DocumentoEmitido documento) {
            throw new UnsupportedOperationException("imprimir la corrida no emite documentos");
        }

        @Override
        public DocumentoEmitido registrarReimpresion(DocumentoEmitido documento) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long siguienteCorrelativo(String tipo, Ejercicio ejercicio) {
            throw new UnsupportedOperationException("imprimir la corrida no consume correlativo");
        }
    }
}
