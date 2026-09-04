package kamayuk.rentas.documentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Que un documento de la marcha blanca no se pueda confundir con uno real (#122).
 *
 * <p>El motivo es concreto y tiene fecha de caducidad: mientras D-02a siga abierta, cualquier
 * importe que el sistema muestre esta calculado con parametros que nadie ha firmado. Una hoja
 * impresa con una cifra plausible y sin marca es un papel que alguien puede intentar cobrar.
 *
 * <p>Se verifican dos cosas, y la segunda importa tanto como la primera: que bajo demostracion la
 * marca salga en los <b>tres</b> formatos, y que sin demostracion el documento salga
 * <b>identico</b> al de antes de este cambio. Una marca que alterase el documento de una
 * instalacion real habria roto la reimpresion identica que verifica RF-132.
 */
@DisplayName("#122 — La instalacion de demostracion se ve en el papel")
class MarcaDeDemostracionTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 8, 19);

    private static final GeneradorDeDocumentos REAL = generador(RegimenDeLaInstalacion.REAL);
    private static final GeneradorDeDocumentos DEMOSTRACION =
            generador(RegimenDeLaInstalacion.DEMOSTRACION);

    private static GeneradorDeDocumentos generador(RegimenDeLaInstalacion regimen) {
        return new GeneradorDeDocumentos(
                List.of(new RenderizadorPdf(), new RenderizadorXls(), new RenderizadorRtf()),
                regimen);
    }

    @Nested
    @DisplayName("Bajo demostracion, la marca sale en los tres formatos")
    class BajoDemostracion {

        // Una prueba por formato, y no un bucle, a proposito: con el bucle, romper el
        // renderizador del RTF y romper los tres da exactamente el mismo rojo —la primera
        // asercion corta el bucle—. Con tres pruebas, el informe dice cual de los tres
        // dejo de marcar, que es la unica pregunta que se hace quien lo lee.
        @Test
        @DisplayName("el PDF lleva la marca")
        void elPdfLlevaLaMarca() {
            exigirLaMarca(FormatoDeDocumento.PDF);
        }

        @Test
        @DisplayName("la hoja de calculo lleva la marca")
        void laHojaDeCalculoLlevaLaMarca() {
            exigirLaMarca(FormatoDeDocumento.XLS);
        }

        @Test
        @DisplayName("el texto enriquecido lleva la marca")
        void elTextoEnriquecidoLlevaLaMarca() {
            exigirLaMarca(FormatoDeDocumento.RTF);
        }

        private void exigirLaMarca(FormatoDeDocumento formato) {
            assertThat(texto(DEMOSTRACION.generar(unModelo(), formato), formato))
                    .as("un %s sin la marca es un papel que alguien puede intentar cobrar", formato)
                    .contains(esperado(formato));
        }

        @Test
        @DisplayName("el llamador no la pone, y no puede olvidarla")
        void elLlamadorNoLaPone() {
            ModeloDeDocumento sinMarcar = unModelo();

            assertThat(sinMarcar.esDemostracion())
                    .as("quien construye el modelo no sabe nada del regimen de la instalacion")
                    .isFalse();
            assertThat(DEMOSTRACION.marcar(sinMarcar).esDemostracion())
                    .as("la pone la capa de documentos, en un solo sitio")
                    .isTrue();
        }

        @Test
        @DisplayName("marcar dos veces no pone dos marcas")
        void marcarDosVecesNoPoneDosMarcas() {
            // EmitirDocumento guarda el modelo ya marcado y el generador vuelve a marcarlo
            // al dibujar. Si la segunda anadiera otra marca, la reimpresion no daria los
            // mismos bytes que la emision y saltaria en el primer duplicado.
            ModeloDeDocumento unaVez = DEMOSTRACION.marcar(unModelo());
            ModeloDeDocumento dosVeces = DEMOSTRACION.marcar(unaVez);

            assertThat(dosVeces).isEqualTo(unaVez);
            assertThat(DEMOSTRACION.generar(dosVeces, FormatoDeDocumento.PDF))
                    .isEqualTo(DEMOSTRACION.generar(unaVez, FormatoDeDocumento.PDF));
        }

        @Test
        @DisplayName("un duplicado de demostracion lleva las dos marcas, no una")
        void unDuplicadoDeDemostracionLlevaLasDos() {
            ModeloDeDocumento duplicado = DEMOSTRACION.marcar(unModelo()).comoDuplicado(1);
            String rtf = texto(DEMOSTRACION.generar(duplicado, FormatoDeDocumento.RTF), null);

            assertThat(rtf).contains("DUPLICADO");
            assertThat(rtf)
                    .as("un duplicado de un papel de demostracion sigue siendo de demostracion")
                    .contains(esperado(FormatoDeDocumento.RTF));
        }
    }

    @Nested
    @DisplayName("Sin demostracion, el documento no cambia ni un byte")
    class SinDemostracion {

        @Test
        @DisplayName("ningun formato menciona la demostracion")
        void ningunFormatoLaMenciona() {
            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                assertThat(texto(REAL.generar(unModelo(), formato), formato))
                        .as("%s de una instalacion real", formato)
                        .doesNotContain("DEMOSTRACION")
                        .doesNotContain("DEMOSTRACI");
            }
        }

        @Test
        @DisplayName("el modelo sale intacto: marcar no lo toca")
        void elModeloSaleIntacto() {
            ModeloDeDocumento modelo = unModelo();

            assertThat(REAL.marcar(modelo))
                    .as("sin demostracion no hay nada que anadir, ni siquiera una copia")
                    .isSameAs(modelo);
        }

        @Test
        @DisplayName("los bytes son los mismos que sin la columna: es la garantia de RF-132")
        void losBytesSonLosMismos() {
            // El resumen que EmitirDocumento guarda al emitir se compara con el de la
            // reimpresion anos despues. Si anadir la marca hubiera cambiado la salida de
            // una instalacion real —aunque fuera un byte—, todos los documentos ya
            // emitidos se habrian vuelto irreimprimibles de golpe.
            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                assertThat(REAL.resumenDe(unModelo(), formato))
                        .as("%s: dos ejecuciones, el mismo resumen", formato)
                        .isEqualTo(REAL.resumenDe(unModelo(), formato));
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Lo que hay que buscar en cada formato.
     *
     * <p>No es la misma cadena en los tres: el RTF escapa todo lo que no sea ASCII, asi que la raya
     * del texto de la marca sale como {@code \\u8212?} y buscar la cadena entera fallaria por un
     * motivo que no tiene nada que ver con la marca.
     */
    private static String esperado(FormatoDeDocumento formato) {
        return formato == FormatoDeDocumento.RTF
                ? "INSTALACION DE DEMOSTRACION"
                : ModeloDeDocumento.MARCA_DE_DEMOSTRACION;
    }

    /** El PDF es WinAnsi; los otros dos, UTF-8. */
    private static String texto(byte[] documento, FormatoDeDocumento formato) {
        return new String(
                documento,
                formato == FormatoDeDocumento.PDF
                        ? java.nio.charset.Charset.forName("windows-1252")
                        : StandardCharsets.UTF_8);
    }

    private static ModeloDeDocumento unModelo() {
        return ModeloDeDocumento.de(
                "Hoja de resumen",
                FECHA,
                List.of(Campo.de("Contribuyente", "PEÑA GARCÍA, MARÍA DEL CARMEN")),
                List.of(
                        Tabla.de(
                                "Detalle",
                                List.of("Concepto", "Importe"),
                                List.of(List.of("Cifra ficticia de prueba", "0,00")))));
    }
}
