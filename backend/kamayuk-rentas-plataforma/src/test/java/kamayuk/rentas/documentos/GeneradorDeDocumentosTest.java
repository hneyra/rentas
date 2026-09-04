package kamayuk.rentas.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Los tres formatos que el manual promete en todo reporte (RF-132).
 *
 * <p>Sin base de datos y sin Spring: renderizar es una funcion pura de un modelo a unos bytes, y
 * eso es justamente lo que permite exigir que la salida sea <b>identica</b> ejecucion tras
 * ejecucion. Una prueba que necesitara el contexto no podria afirmarlo.
 */
@DisplayName("RF-132 — Generacion de documentos")
class GeneradorDeDocumentosTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 8, 19);

    private final GeneradorDeDocumentos generador =
            new GeneradorDeDocumentos(
                    List.of(new RenderizadorPdf(), new RenderizadorXls(), new RenderizadorRtf()),
                    RegimenDeLaInstalacion.REAL);

    private static ModeloDeDocumento unaFicha() {
        return new ModeloDeDocumento(
                "Ficha del contribuyente",
                null,
                FECHA,
                List.of(
                        Campo.de("Código de contribuyente", "C-000900"),
                        Campo.de("Nombre o razón social", "PEÑA GARCÍA, MARÍA DEL CARMEN")),
                List.of(
                        Tabla.de(
                                "Unidades afectas",
                                List.of("Cód. referencia catastral", "Dirección", "% propiedad"),
                                List.of(
                                        List.of("28010100100100101010001", "AV. GRAU 100", "60,00"),
                                        List.of(
                                                "28010100100100101010002",
                                                "JR. LIMA 250",
                                                "100,00")))),
                List.of("Este documento no consigna importes."),
                null,
                null);
    }

    @Nested
    @DisplayName("Los tres formatos con los mismos datos")
    class LosTresFormatos {

        @Test
        @DisplayName("los tres se generan y ninguno sale vacio")
        void losTresSeGeneran() {
            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                assertThat(generador.generar(unaFicha(), formato))
                        .as("el manual promete los tres en las 231 figuras (%s)", formato)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("los tres llevan los mismos datos, no una version recortada")
        void losTresLlevanLosMismosDatos() {
            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                String texto = texto(generador.generar(unaFicha(), formato), formato);

                assertThat(texto)
                        .as("el codigo del contribuyente falta en %s", formato)
                        .contains("C-000900");
                assertThat(texto)
                        .as("una unidad afecta falta en %s", formato)
                        .contains("28010100100100101010002");
                assertThat(texto)
                        .as(
                                "la fecha va en los tres: toda cifra impresa dice de cuando es"
                                        + " (RNF-075). Falta en %s",
                                formato)
                        .contains("2026-08-19");
            }
        }

        @Test
        @DisplayName("el castellano sobrevive a los tres, incluidas las tildes y la ñ")
        void elCastellanoSobrevive() {
            byte[] rtf = generador.generar(unaFicha(), FormatoDeDocumento.RTF);
            assertThat(new String(rtf, StandardCharsets.US_ASCII))
                    .as(
                            "RTF no es UTF-8: sin el escape \\uNNNN, «PEÑA GARCÍA» sale «PE?A"
                                    + " GARC?A» en Word, que es el apellido de alguien escrito mal en"
                                    + " un documento oficial")
                    .contains("PE\\u209?A GARC\\u205?A");

            assertThat(
                            new String(
                                    generador.generar(unaFicha(), FormatoDeDocumento.XLS),
                                    StandardCharsets.UTF_8))
                    .contains("PEÑA GARCÍA");
        }

        @Test
        @DisplayName("cada formato se anuncia con su tipo de medio y su extension")
        void cadaFormatoSeAnuncia() {
            assertThat(FormatoDeDocumento.PDF.nombreDeArchivo("ficha")).isEqualTo("ficha.pdf");
            assertThat(FormatoDeDocumento.XLS.tipoDeMedio()).isEqualTo("application/vnd.ms-excel");
            assertThat(FormatoDeDocumento.RTF.tipoDeMedio()).isEqualTo("application/rtf");
        }
    }

    @Nested
    @DisplayName("La salida es identica ejecucion tras ejecucion")
    class Determinismo {

        @Test
        @DisplayName("generar dos veces el mismo modelo da los MISMOS bytes")
        void generarDosVecesDaLosMismosBytes() {
            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                assertThat(generador.resumenDe(unaFicha(), formato))
                        .as(
                                "es lo que hace comprobable «reimprimir devuelve exactamente el"
                                        + " original». Una biblioteca de PDF escribe la fecha de"
                                        + " creacion dentro y rompe esto sin avisar (%s)",
                                formato)
                        .isEqualTo(generador.resumenDe(unaFicha(), formato));
            }
        }

        @Test
        @DisplayName("el PDF no lleva fecha de creacion ni identificador de archivo")
        void elPdfNoLlevaFechaDeCreacion() {
            String pdf =
                    new String(
                            generador.generar(unaFicha(), FormatoDeDocumento.PDF),
                            StandardCharsets.ISO_8859_1);

            assertThat(pdf)
                    .as("son justo los dos campos que harian distinta cada reimpresion")
                    .doesNotContain("/CreationDate")
                    .doesNotContain("/ID");
        }

        @Test
        @DisplayName("un cambio en los datos SI cambia el resumen")
        void unCambioEnLosDatosCambiaElResumen() {
            ModeloDeDocumento otro = unaFicha().con(List.of("Otro pie"));

            assertThat(generador.resumenDe(otro, FormatoDeDocumento.PDF))
                    .as("si no, el resumen no estaria comprobando nada")
                    .isNotEqualTo(generador.resumenDe(unaFicha(), FormatoDeDocumento.PDF));
        }
    }

    @Nested
    @DisplayName("La estructura del PDF")
    class EstructuraDelPdf {

        @Test
        @DisplayName("es un PDF valido: cabecera, tabla de referencias y fin")
        void esUnPdfValido() {
            String pdf =
                    new String(
                            generador.generar(unaFicha(), FormatoDeDocumento.PDF),
                            StandardCharsets.ISO_8859_1);

            assertThat(pdf).startsWith("%PDF-1.4");
            assertThat(pdf).endsWith("%%EOF\n");
            assertThat(pdf).contains("/Type /Catalog").contains("/Type /Pages");
        }

        @Test
        @DisplayName("los desplazamientos de la tabla xref apuntan de verdad a cada objeto")
        void losDesplazamientosApuntanACadaObjeto() {
            byte[] pdf = generador.generar(unaFicha(), FormatoDeDocumento.PDF);
            String texto = new String(pdf, StandardCharsets.ISO_8859_1);

            int inicio = texto.indexOf("xref\n");
            assertThat(inicio).as("todo PDF tiene tabla de referencias cruzadas").isPositive();

            // La primera entrada es el objeto libre; a partir de la segunda, cada
            // desplazamiento tiene que caer justo donde empieza «N 0 obj».
            String[] lineas = texto.substring(inicio).split("\n");
            for (int objeto = 1; objeto < lineas.length - 1; objeto++) {
                String linea = lineas[objeto + 1];
                if (!linea.endsWith(" n ") && !linea.endsWith(" n")) {
                    break;
                }
                int desplazamiento = Integer.parseInt(linea.substring(0, 10));
                assertThat(texto.substring(desplazamiento))
                        .as(
                                "un desplazamiento mal contado produce un archivo que unos lectores"
                                        + " reconstruyen y otros rechazan, que es la peor forma de"
                                        + " estar roto")
                        .startsWith(objeto + " 0 obj");
            }
        }

        @Test
        @DisplayName("una tabla larga se reparte en varias paginas")
        void unaTablaLargaSeRepartEnVariasPaginas() {
            List<List<String>> muchas =
                    java.util.stream.IntStream.range(0, 200)
                            .mapToObj(i -> List.of("COD-" + i, "AV. LARGA " + i, "100,00"))
                            .toList();
            ModeloDeDocumento larga =
                    new ModeloDeDocumento(
                            "Padron",
                            null,
                            FECHA,
                            List.of(),
                            List.of(
                                    Tabla.de(
                                            "Unidades",
                                            List.of("Código", "Dirección", "%"),
                                            muchas)),
                            List.of(),
                            null,
                            null);

            String pdf =
                    new String(
                            generador.generar(larga, FormatoDeDocumento.PDF),
                            StandardCharsets.ISO_8859_1);

            assertThat(pdf)
                    .as(
                            "doscientas filas no caben en A4, y amontonarlas fuera del margen las pierde")
                    .contains("/Count 4");
        }
    }

    @Nested
    @DisplayName("Lo que el modelo no deja construir")
    class LoQueNoSeConstruye {

        @Test
        @DisplayName("una fila con menos celdas que columnas se rechaza")
        void unaFilaCortaSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    Tabla.de(
                                            "Unidades",
                                            List.of("Código", "Dirección"),
                                            List.of(List.of("solo una"))))
                    .as(
                            "el mismo documento saldria descuadrado de una manera en el PDF y de"
                                    + " otra en la hoja de calculo")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un documento sin fecha no se construye")
        void unDocumentoSinFechaNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new ModeloDeDocumento(
                                            "Ficha", null, null, List.of(), List.of(), List.of(),
                                            null, null))
                    .as("toda cifra impresa dice de cuando es (RNF-075, regla 9)")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("dos renderizadores del mismo formato no arrancan")
        void dosRenderizadoresDelMismoFormatoNoArrancan() {
            assertThatThrownBy(
                            () ->
                                    new GeneradorDeDocumentos(
                                            List.of(
                                                    new RenderizadorPdf(),
                                                    new RenderizadorPdf(),
                                                    new RenderizadorXls(),
                                                    new RenderizadorRtf()),
                                            RegimenDeLaInstalacion.REAL))
                    .as("el documento saldria de uno u otro segun el orden de descubrimiento")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("si falta un formato, falla al arrancar y no al pedirlo")
        void siFaltaUnFormatoFallaAlArrancar() {
            assertThatThrownBy(
                            () ->
                                    new GeneradorDeDocumentos(
                                            List.of(new RenderizadorPdf(), new RenderizadorXls()),
                                            RegimenDeLaInstalacion.REAL))
                    .as(
                            "descubrir que falta el RTF cuando un usuario lo pide es descubrirlo"
                                    + " tarde")
                    .isInstanceOf(GeneradorDeDocumentos.FormatoSinRenderizador.class)
                    .hasMessageContaining("RTF");
        }
    }

    @Nested
    @DisplayName("Emision masiva")
    class Masiva {

        @Test
        @DisplayName("escribe sobre el flujo sin acumular los documentos")
        void escribeSobreElFlujoSinAcumular() throws IOException {
            ContadorDeBytes salida = new ContadorDeBytes();

            for (int i = 0; i < 500; i++) {
                generador.escribir(unaFicha(), FormatoDeDocumento.RTF, salida);
            }

            assertThat(salida.escritos())
                    .as(
                            "quinientos documentos por un flujo que no guarda nada. El contrato de"
                                    + " Renderizador es escribir sobre un OutputStream y no devolver un"
                                    + " arreglo: es lo que hace que emitir el padron de una provincia no"
                                    + " dependa de cuantos sean")
                    .isPositive();
        }

        /** Cuenta lo que pasa por el, sin guardarlo. Es lo que hara el flujo real. */
        private static final class ContadorDeBytes extends OutputStream {

            private long escritos;

            @Override
            public void write(int unByte) {
                escritos++;
            }

            @Override
            public void write(byte[] bytes, int desde, int cuantos) {
                escritos += cuantos;
            }

            long escritos() {
                return escritos;
            }
        }
    }

    // ------------------------------------------------------------------

    private static String texto(byte[] documento, FormatoDeDocumento formato) {
        return new String(
                documento,
                formato == FormatoDeDocumento.XLS
                        ? StandardCharsets.UTF_8
                        : StandardCharsets.ISO_8859_1);
    }
}
