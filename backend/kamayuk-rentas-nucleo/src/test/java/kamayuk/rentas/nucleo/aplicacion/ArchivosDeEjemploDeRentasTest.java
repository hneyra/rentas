package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.ComposicionCatastral;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los tres archivos de {@code infra/carga-de-datos/ejemplos/} que siembra {@code rentas} —padron
 * vehicular, transferencias y saldo inicial del libro— pasan <b>por el analizador de verdad</b>,
 * fila a fila, y <b>en su orden</b>.
 *
 * <p>Es la contraparte de {@code ArchivosDeEjemploTest} (catastro, #290), y por el mismo motivo: un
 * archivo de ejemplo versionado se copia y se carga tal cual. Si tiene una columna de menos, una
 * placa mal escrita o nombra un predio que a esa fecha era de otro, el sintoma aparece contra un
 * ambiente real —o, peor, en una demostracion delante de alguien—. Aqui aparece en el build.
 *
 * <p>No basta con que cada archivo se analice solo: los tres se cargan contra el mismo padron, y
 * ese padron se siembra <b>leyendo</b> {@code contribuyentes.csv} y {@code fichas.csv}, componiendo
 * el codigo de referencia catastral igual que lo compone {@code ImportarFichas}. Asi se comprueba
 * tambien la coherencia entre los cinco: que cada vehiculo nombre a un contribuyente que existe,
 * que cada transferencia nombre un predio del que su transferente era titular <b>a esa fecha</b>, y
 * que cada cargo nombre una unidad que a su fecha valor era de quien lo debe.
 */
@DisplayName("Los archivos de ejemplo de rentas son validos")
class ArchivosDeEjemploDeRentasTest {

    /** Palabras de valor normativo: ninguna puede aparecer en una fila de datos (regla 5). */
    private static final List<String> VALORES_NORMATIVOS =
            List.of(
                    "arancel",
                    "valor unitario",
                    "valorm2",
                    "depreciacion",
                    "uit",
                    "alicuota",
                    "tramo");

    /** Las fichas se inscriben con esta vigencia en el archivo; el padron parte de ahi. */
    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);

    private PadronDeLaSiembraEnMemoria padron;
    private ReferenciasDeLaSiembra referencias;
    private Observacion observacion;

    @BeforeEach
    void preparar() throws IOException {
        padron = new PadronDeLaSiembraEnMemoria();
        referencias = new ReferenciasDeLaSiembra(padron, padron, padron);
        observacion = Observacion.de("Carga de los archivos de ejemplo");
        sembrarElPadronDesdeLosArchivos();
    }

    @Test
    @DisplayName("vehiculos.csv se carga entero, sin una sola fila rechazada")
    void elPadronVehicularSeCargaEntero() throws IOException {
        InformeDeImportacion informe =
                importarVehiculos().importar(abrir("vehiculos.csv"), observacion);

        assertThat(informe.rechazadas())
                .as("cada fila nombra a su propietario por codigo: si uno falla, sale aqui")
                .isEmpty();
        assertThat(informe.nuevas()).isEqualTo(informe.totalFilas()).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("transferencias.csv se carga entero sobre el padron ya sembrado")
    void lasTransferenciasSeCarganSobreElPadron() throws IOException {
        importarVehiculos().importar(abrir("vehiculos.csv"), observacion);

        InformeDeImportacion informe =
                importarTransferencias().importar(abrir("transferencias.csv"), observacion);

        assertThat(informe.rechazadas())
                .as(
                        "cada fila nombra un predio del que su transferente era titular A ESA FECHA,"
                                + " o una placa del padron: si una falla, sale aqui")
                .isEmpty();
        assertThat(informe.nuevas()).isEqualTo(informe.totalFilas()).isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("las transferencias dejan al menos una copropiedad viva, que es para lo que estan")
    void lasTransferenciasDejanUnaCopropiedad() throws IOException {
        importarVehiculos().importar(abrir("vehiculos.csv"), observacion);
        importarTransferencias().importar(abrir("transferencias.csv"), observacion);

        // Una cuota viva que no sea del 100 % solo puede venir de una transferencia parcial:
        // fichas.csv inscribe cada predio con UN titular por el total.
        assertThat(padron.cuotasVivas())
                .as("la copropiedad no se declara en fichas.csv, se produce aqui")
                .anySatisfy(cuota -> assertThat(cuota.porcentaje().esTotal()).isFalse());
    }

    @Test
    @DisplayName("deuda.csv se carga entero, con su unidad resuelta a la fecha valor de cada fila")
    void laDeudaSeCargaEntera() throws IOException {
        importarVehiculos().importar(abrir("vehiculos.csv"), observacion);
        importarTransferencias().importar(abrir("transferencias.csv"), observacion);

        InformeDeImportacion informe =
                new ImportarDeudaDeDemostracion(padron, referencias)
                        .importar(abrir("deuda.csv"), observacion);

        assertThat(informe.rechazadas())
                .as(
                        "cada fila nombra una unidad que a SU FECHA VALOR era de quien la debe: si"
                                + " una falla, sale aqui")
                .isEmpty();
        assertThat(informe.nuevas()).isEqualTo(informe.totalFilas()).isGreaterThanOrEqualTo(50);
        assertThat(padron.cargosAsentados())
                .as("las tres clases de obligacion, para que la demostracion las muestre")
                .extracting(PadronDeLaSiembraEnMemoria.Cargo::tributo)
                .contains("PREDIAL", "ARBITRIO", "VEHICULAR");
    }

    @Test
    @DisplayName("#553 — cada fila de deuda.csv usa una grafia del vocabulario del libro")
    void cadaFilaUsaLaGrafiaDelVocabulario() throws IOException {
        importarVehiculos().importar(abrir("vehiculos.csv"), observacion);
        importarTransferencias().importar(abrir("transferencias.csv"), observacion);

        InformeDeImportacion informe =
                new ImportarDeudaDeDemostracion(padron, referencias)
                        .importar(abrir("deuda.csv"), observacion);

        // El archivo sembraba ARBITRIOS (plural) y el sistema escribe ARBITRIO: la deuda
        // entraba, se cobraba y caia AL LADO de la que deberia ser la misma obligacion, sin
        // que nada lo dijera. Ahora la fila con una grafia de fuera se rechaza con su numero
        // de linea, en vez de sembrar una obligacion paralela.
        assertThat(informe.rechazadas())
                .as("una grafia de fuera se rechaza nombrando la fila, no se siembra")
                .isEmpty();
        assertThat(padron.cargosAsentados())
                .extracting(PadronDeLaSiembraEnMemoria.Cargo::tributo)
                .allSatisfy(
                        tributo ->
                                assertThat(TributoDelLibro.esDelVocabulario(tributo))
                                        .as("«%s» no es un tributo del libro", tributo)
                                        .isTrue());
    }

    @Test
    @DisplayName("#553 — el filtro «Arbitrios» de la consulta unificada encuentra esas filas")
    void elFiltroDeArbitriosEncuentraLasFilasDelArchivo() throws IOException {
        importarVehiculos().importar(abrir("vehiculos.csv"), observacion);
        importarTransferencias().importar(abrir("transferencias.csv"), observacion);
        new ImportarDeudaDeDemostracion(padron, referencias)
                .importar(abrir("deuda.csv"), observacion);

        List<String> arbitriosSembrados =
                padron.cargosAsentados().stream()
                        .map(PadronDeLaSiembraEnMemoria.Cargo::tributo)
                        .filter(ConsultaUnificada.Alcance.ARBITRIOS::incluye)
                        .toList();

        // Es el defecto del issue medido donde nacia: el desplegable dice «ARBITRIOS», el
        // filtro compara contra ARBITRIO —como se asienta— y el archivo sembraba el plural,
        // asi que el filtro no encontraba ni una de las 18 filas que la siembra habia escrito.
        assertThat(arbitriosSembrados)
                .as(
                        "el archivo y el filtro no pueden volver a divergir: si el archivo cambia"
                                + " de grafia, aqui sale vacio")
                .isNotEmpty()
                .allSatisfy(
                        tributo -> assertThat(tributo).isEqualTo(TributoDelLibro.ARBITRIO.texto()));
    }

    @Test
    @DisplayName("ninguna fila de dato trae un valor normativo (regla 5, D-02a, D-02b y D-13)")
    void ningunaFilaTraeUnValorNormativo() throws IOException {
        for (String nombre : List.of("vehiculos.csv", "transferencias.csv", "deuda.csv")) {
            for (String linea : Files.readAllLines(ejemplos().resolve(nombre))) {
                if (linea.stripLeading().startsWith("#")) {
                    continue; // los comentarios explican justamente que no se siembran
                }
                String minuscula = linea.toLowerCase(Locale.ROOT);
                for (String prohibida : VALORES_NORMATIVOS) {
                    assertThat(minuscula)
                            .as(
                                    "%s: una cifra normativa inventada no se distingue de una real, y"
                                            + " sus pantallas tienen que seguir diciendo «sin conjunto"
                                            + " sellado»",
                                    nombre)
                            .doesNotContain(prohibida);
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Siembra el padron leyendo los dos archivos de los que dependen los tres de rentas, y
     * componiendo el codigo catastral <b>igual que {@code ImportarFichas}</b>: si el archivo cambia
     * de tramos, este cruce cambia con el.
     */
    private void sembrarElPadronDesdeLosArchivos() throws IOException {
        Map<String, Long> porCodigo = new LinkedHashMap<>();
        for (FilaCsv fila : LectorDeFilasCsv.leer(abrir("contribuyentes.csv"))) {
            porCodigo.put(
                    fila.campos().get(0),
                    padron.sembrarContribuyente(fila.campos().get(0), fila.campos().get(4)));
        }

        ComposicionCatastral composicion = ComposicionCatastral.DEL_MANUAL;
        int tramos = composicion.tramos().size();
        for (FilaCsv fila : LectorDeFilasCsv.leer(abrirLasFichasDeCatastro())) {
            Map<String, String> porTramo = new LinkedHashMap<>();
            for (int i = 0; i < tramos; i++) {
                String valor = fila.campos().get(i).strip();
                if (!valor.isEmpty()) {
                    porTramo.put(composicion.tramos().get(i).nombre(), valor);
                }
            }
            CodigoReferenciaCatastral codigo =
                    CodigoReferenciaCatastral.componer(porTramo, composicion);
            String codigoContribuyente = fila.campos().get(tramos + 11).strip();
            if (codigoContribuyente.isEmpty()) {
                continue; // el predio fichado sin titular identificado: no cuelga de nadie
            }
            Long titular = porCodigo.get(codigoContribuyente);
            assertThat(titular)
                    .as(
                            "fichas.csv nombra al contribuyente %s, que contribuyentes.csv no trae",
                            codigoContribuyente)
                    .isNotNull();
            padron.sembrarPredio(codigo.valor(), titular, INICIO);
        }
    }

    private ImportarVehiculos importarVehiculos() {
        return new ImportarVehiculos(
                new RegistrarVehiculo(padron, auditoria(), reloj()), referencias);
    }

    private ImportarTransferencias importarTransferencias() {
        return new ImportarTransferencias(
                new RegistrarTransferencia(
                        padron.registroDeTransferencias(), padron, padron, auditoria()),
                referencias);
    }

    private static Auditoria auditoria() {
        return registro -> {};
    }

    private static Clock reloj() {
        return Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
    }

    private static Reader abrir(String nombre) throws IOException {
        return Files.newBufferedReader(ejemplos().resolve(nombre), StandardCharsets.UTF_8);
    }

    /**
     * {@code fichas.csv}, que es de {@code catastro} y se lee del repositorio hermano (C-6).
     *
     * <p><b>No se copia aqui a proposito.</b> Hasta C-6 habia una copia byte a byte en este
     * repositorio, y nada impedia que divergiera de la que carga el paso 6: la que se leyera
     * decidiria contra que predios se cruzan las transferencias y la deuda. Es exactamente el
     * mecanismo inverso del que usa {@code ArchivosDeEjemploTest} de {@code catastro} para leer
     * {@code contribuyentes.csv} de aqui (hueco 5 de P5C), y tiene su mismo costo: <b>este modulo
     * no compila sus pruebas sin `catastro` clonado al lado</b>, y si no esta, la prueba falla
     * nombrando el {@code git clone}; no se salta.
     */
    private static Reader abrirLasFichasDeCatastro() throws IOException {
        Path csv =
                ejemplos()
                        .getParent()
                        .getParent()
                        .getParent()
                        .getParent()
                        .resolve("catastro/infra/carga-de-datos/ejemplos/fichas.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IllegalStateException(
                    "No esta "
                            + csv
                            + ". Las transferencias y la deuda de demostracion cuelgan de los"
                            + " predios de `fichas.csv`, que desde P5C vive en `catastro`: git"
                            + " clone https://github.com/hneyra/catastro ../../catastro");
        }
        return Files.newBufferedReader(csv, StandardCharsets.UTF_8);
    }

    /** {@code infra/carga-de-datos/ejemplos}, buscando la raiz del repositorio hacia arriba. */
    private static Path ejemplos() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            Path candidato = actual.resolve("infra/carga-de-datos/ejemplos");
            if (Files.isDirectory(candidato)) {
                return candidato;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException(
                "No se encontro infra/carga-de-datos/ejemplos desde "
                        + Path.of("").toAbsolutePath());
    }
}
