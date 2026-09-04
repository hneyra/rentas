package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La constancia de no adeudo como documento en los tres formatos (RF-132, RNF-081, RNF-084, #72).
 *
 * <h2>Sin base de datos, y no es un atajo</h2>
 *
 * <p>Del codigo del contribuyente al archivo no hay ni una consulta: {@link ConstanciaDeNoAdeudo}
 * es un registro del dominio y el generador escribe los tres formatos sin salir de memoria. Lo que
 * si necesita PostgreSQL —que las cifras del papel sean las mismas filas que el JSON— se verifica
 * en {@code ConstanciaDeNoAdeudoTest}, que ya tiene el padron sembrado.
 */
@DisplayName("#72 — La constancia se exporta a PDF, XLS y RTF (RNF-081)")
class ModeloDeLaConstanciaDeNoAdeudoTest {

    private static final LocalDate CORTE = LocalDate.of(2026, 6, 1);

    private final GeneradorDeDocumentos documentos =
            new GeneradorDeDocumentos(
                    List.of(new RenderizadorPdf(), new RenderizadorXls(), new RenderizadorRtf()),
                    RegimenDeLaInstalacion.REAL);

    @Test
    @DisplayName("dice lo mismo que la vista previa: titulo, subtitulo del TUPA y las 4 columnas")
    void diceLoMismoQueLaPantalla() {
        ModeloDeDocumento modelo = ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda());

        assertThat(modelo.titulo()).isEqualTo("Constancia de no adeudo");
        assertThat(modelo.subtitulo())
                .as("el subtitulo del catalogo, letra por letra")
                .isEqualTo(
                        "Emitida conforme al Texto Único de Procedimientos Administrativos"
                                + " vigente");
        assertThat(modelo.tablas())
                .singleElement()
                .satisfies(
                        tabla ->
                                assertThat(tabla.columnas())
                                        .as("las cuatro columnas que dibuja la hoja de la pantalla")
                                        .containsExactly(
                                                "Tributo", "Ejercicios", "Situación", "Saldo S/"));
    }

    @Test
    @DisplayName("la decision se lee en el papel, y el saldo de cada fila es el que trae la deuda")
    void laDecisionYLasCifras() {
        ModeloDeDocumento negada = ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda());

        assertThat(negada.cabecera())
                .anySatisfy(
                        campo -> {
                            assertThat(campo.etiqueta()).isEqualTo("Resultado");
                            assertThat(campo.valor()).isEqualTo("SE NIEGA — hay deuda pendiente");
                        });
        assertThat(negada.tablas().getFirst().filas())
                .containsExactly(
                        List.of("PREDIAL", "2026", "Pendiente", "500.00"),
                        List.of("ARBITRIO", "2026", "Cancelado", "0.00"));

        ModeloDeDocumento emitida = ModeloDeLaConstanciaDeNoAdeudo.de(sinDeuda());
        assertThat(emitida.cabecera())
                .anySatisfy(
                        campo -> {
                            assertThat(campo.etiqueta()).isEqualTo("Resultado");
                            assertThat(campo.valor())
                                    .isEqualTo("SE EMITE — no se registra deuda pendiente");
                        });
    }

    @Test
    @DisplayName("toda cifra del papel dice de cuando es, y la fecha de corte va en la cabecera")
    void ningunaCifraSinSuFecha() {
        ModeloDeDocumento modelo = ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda());

        assertThat(modelo.aLaFecha()).isEqualTo(CORTE);
        assertThat(modelo.cabecera())
                .anySatisfy(
                        campo -> {
                            assertThat(campo.etiqueta()).isEqualTo("Fecha de corte");
                            assertThat(campo.valor()).isEqualTo(CORTE.toString());
                        });
        assertThat(modelo.pie())
                .anySatisfy(linea -> assertThat(linea).contains("Cifras al " + CORTE));
    }

    @Test
    @DisplayName("dos lineas de firma en el pie, y ninguna digital mientras D-05 siga abierta")
    void dosLineasDeFirma() {
        List<String> pie = ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda()).pie();

        assertThat(pie.stream().filter(linea -> linea.startsWith("______")).count())
                .as("RNF-084: A4 vertical, una hoja, dos lineas de firma")
                .isEqualTo(2);
        assertThat(pie).anySatisfy(linea -> assertThat(linea).contains("Cajero / Responsable"));
        assertThat(pie).anySatisfy(linea -> assertThat(linea).contains("Contribuyente"));
        assertThat(pie).anySatisfy(linea -> assertThat(linea).contains("D-05"));
    }

    @Test
    @DisplayName("los tres formatos salen, y ninguno sale vacio")
    void losTresFormatos() {
        ModeloDeDocumento modelo = ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda());

        for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
            assertThat(documentos.generar(modelo, formato))
                    .as("la constancia en %s", formato)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("volver a dibujar los mismos datos da los mismos bytes, en los tres formatos")
    void laReimpresionEsIdentica() {
        for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
            byte[] primera =
                    documentos.generar(ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda()), formato);
            byte[] segunda =
                    documentos.generar(ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda()), formato);

            assertThat(segunda)
                    .as(
                            "el papel que se lleva el contribuyente no puede cambiar entre dos"
                                    + " descargas de la misma consulta (%s)",
                            formato)
                    .isEqualTo(primera);
        }
    }

    @Test
    @DisplayName("el RTF escapa lo no-ASCII: «Único» y «Situación», no «?nico» ni «Situaci?n»")
    void elRtfEscapaLoNoAscii() {
        byte[] rtf =
                documentos.generar(
                        ModeloDeLaConstanciaDeNoAdeudo.de(conDeuda()), FormatoDeDocumento.RTF);
        String texto = new String(rtf, StandardCharsets.US_ASCII);

        assertThat(texto)
                .as("Ú es U+00DA = 218; el «?» detras es el caracter de reserva de RTF")
                .contains("Texto \\u218?nico");
        assertThat(texto)
                .as("ó es U+00F3 = 243, en la cabecera de la columna «Situación»")
                .contains("Situaci\\u243?n");
        assertThat(texto)
                .as("ni un solo interrogante suelto donde deberia haber una vocal acentuada")
                .doesNotContain("Texto ?nico")
                .doesNotContain("Situaci?n");
    }

    @Test
    @DisplayName("el nombre del archivo lleva el codigo del contribuyente, no un nombre generico")
    void elNombreDelArchivo() {
        assertThat(
                        FormatoDeDocumento.XLS.nombreDeArchivo(
                                ModeloDeLaConstanciaDeNoAdeudo.nombreDeArchivo(conDeuda())))
                .isEqualTo("constancia-K-0002.xls");
    }

    @Test
    @DisplayName("una constancia sin obligaciones se dibuja igual: la tabla sale vacia, no falta")
    void sinObligacionesSeDibujaIgual() {
        ModeloDeDocumento modelo = ModeloDeLaConstanciaDeNoAdeudo.de(sinDeuda());

        assertThat(modelo.tablas())
                .singleElement()
                .satisfies(
                        tabla -> {
                            assertThat(tabla.estaVacia()).isTrue();
                            assertThat(tabla.columnas()).hasSize(4);
                        });
        assertThat(documentos.generar(modelo, FormatoDeDocumento.PDF)).isNotEmpty();
    }

    // ------------------------------------------------------------------

    private static ConstanciaDeNoAdeudo conDeuda() {
        return ConstanciaDeNoAdeudo.de(
                "K-0002",
                CORTE,
                List.of(
                        // Con dos decimales porque asi llegan del libro: `dinero` es
                        // numeric(15,2), y el papel no reformatea lo que le dan (RNF-083).
                        obligacion("PREDIAL", Dinero.de("500.00")),
                        obligacion("ARBITRIO", Dinero.de("0.00"))));
    }

    private static ConstanciaDeNoAdeudo sinDeuda() {
        return ConstanciaDeNoAdeudo.de("K-0001", CORTE, List.of());
    }

    private static ObligacionConDeuda obligacion(String tributo, Dinero insoluto) {
        return new ObligacionConDeuda(
                tributo,
                new Ejercicio(2026),
                null,
                null,
                0,
                0,
                Fase.ORDINARIA,
                new DeudaActualizada(
                        CORTE, insoluto, Dinero.de("0.00"), Dinero.de("0.00"), Dinero.de("0.00")));
    }
}
