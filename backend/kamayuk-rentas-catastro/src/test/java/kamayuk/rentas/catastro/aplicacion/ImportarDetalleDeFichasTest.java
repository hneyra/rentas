package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El detalle de la ficha —construcciones, obras complementarias y lo propio de cada tipo— cargado
 * desde archivo, <b>sin base de datos</b>.
 *
 * <p>Lo que PostgreSQL garantiza ya tiene sus pruebas contra el motor de verdad ({@code
 * FichasDeTodoTipoTest}, {@code ActualizarFichaCatastralTest}). Lo que se verifica aqui es lo que
 * este importador agrega, y es una propiedad que ninguno de los otros tiene: <b>la unidad de carga
 * es el predio, no la fila</b>. Varias filas son una escritura, porque una version de ficha es
 * atomica; de ahi salen las cuatro cosas que se prueban: que se agrupen, que la cabecera se exija
 * coherente dentro del grupo, que una seccion que no le toca al tipo se rechace nombrandolo, y que
 * un grupo malo no arrastre al siguiente.
 */
@DisplayName("Detalle de las fichas cargado desde archivo")
class ImportarDetalleDeFichasTest {

    private static final String ENCABEZADO =
            "codigoPredial,tipoFicha,vigenciaDesde,origen,documentoOrigen,seccion,"
                    + "c1,c2,c3,c4,c5,c6,c7\n";

    private static final String URBANO = "20010401001001000000000";
    private static final String OTRO = "20010401001002000000000";

    private CatastroEnMemoria catastro;
    private ImportarDetalleDeFichas importar;

    @BeforeEach
    void preparar() {
        catastro = new CatastroEnMemoria();
        catastro.sembrarSector("01", "Cercado de Catacaos");
        catastro.sembrarManzana("01", "001");
        catastro.sembrarVia(new Via(null, "V-0003", TipoVia.CALLE, "Comercio", "200104", true));
        catastro.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

        Clock reloj = Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
        Auditoria auditoria = registro -> {};
        ActualizarFichaCatastral fichas = new ActualizarFichaCatastral(catastro, auditoria, reloj);
        importar = new ImportarDetalleDeFichas(fichas, new PrediosPorCodigo(catastro));

        inscribir(URBANO, TipoFicha.UNICA, "Calle Comercio 245", "001");
        inscribir(OTRO, TipoFicha.RURAL, "Calle Comercio 251", "002");
    }

    @Test
    @DisplayName("varias filas del mismo predio son UNA sola version de ficha")
    void variasFilasDelMismoPredioSonUnaSolaVersion() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                ENCABEZADO
                                        + construccion(URBANO, "1", "120.40")
                                        + construccion(URBANO, "2", "86.00")),
                        porque());

        assertThat(informe.totalFilas()).as("filas leidas").isEqualTo(2);
        assertThat(informe.nuevas()).as("fichas versionadas, no filas").isEqualTo(1);
        assertThat(informe.rechazadas()).isEmpty();
        assertThat(vigenteDe(URBANO).construcciones())
                .as("las dos filas entraron en la misma version")
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "la version nueva convive con la anterior: la ficha se versiona, no se sobrescribe")
    void laVersionNuevaConviveConLaAnterior() {
        importar.importar(
                new StringReader(ENCABEZADO + construccion(URBANO, "1", "120.40")), porque());

        List<FichaCatastral> delPredio =
                catastro.fichasRegistradas().stream()
                        .filter(ficha -> ficha.tipo() == TipoFicha.UNICA)
                        .toList();

        assertThat(delPredio).as("la inscrita y la versionada").hasSize(2);
        assertThat(delPredio)
                .filteredOn(ficha -> ficha.vigenciaHasta() == null)
                .singleElement()
                .satisfies(ficha -> assertThat(ficha.construcciones()).hasSize(1));
    }

    @Test
    @DisplayName("dos filas del mismo predio con distinta vigencia rechazan el grupo entero")
    void dosFilasConDistintaVigenciaRechazanElGrupo() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                ENCABEZADO
                                        + construccion(URBANO, "1", "120.40")
                                        + fila(
                                                URBANO,
                                                "UNICA",
                                                "2026-03-01",
                                                "CONSTRUCCION,2,86.00,2010,LADRILLO,BUENO,"
                                                        + "CCDCCDC,100.00")),
                        porque());

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .satisfies(
                        rechazada -> {
                            assertThat(rechazada.motivo()).contains("la misma vigencia");
                            assertThat(rechazada.fila())
                                    .as("se senala la PRIMERA fila del grupo")
                                    .isEqualTo(2);
                        });
    }

    @Test
    @DisplayName("una seccion que no le toca al tipo de ficha se rechaza nombrando el tipo")
    void unaSeccionQueNoLeTocaAlTipoSeRechaza() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                ENCABEZADO
                                        + fila(
                                                URBANO,
                                                "UNICA",
                                                "2026-02-01",
                                                "TIERRA,CULTIVO_EN_LIMPIO,A1,BAJO_RIEGO,1.05,,,")),
                        porque());

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .satisfies(rechazada -> assertThat(rechazada.motivo()).contains("UNICA"));
    }

    @Test
    @DisplayName("un predio que no existe se rechaza sin arrastrar al siguiente")
    void unPredioQueNoExisteNoArrastraAlSiguiente() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                ENCABEZADO
                                        + construccion("20010499999999999999999", "1", "50.00")
                                        + construccion(URBANO, "1", "120.40")),
                        porque());

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas())
                .singleElement()
                .satisfies(
                        rechazada ->
                                assertThat(rechazada.motivo()).contains("20010499999999999999999"));
        assertThat(vigenteDe(URBANO).construcciones()).hasSize(1);
    }

    @Test
    @DisplayName("el detalle rural entra con sus grupos de tierra y sus colindantes")
    void elDetalleRuralEntraCompleto() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(
                                ENCABEZADO
                                        + fila(
                                                OTRO,
                                                "RURAL",
                                                "2026-02-01",
                                                "TIERRA,CULTIVO_EN_LIMPIO,A1,BAJO_RIEGO,1.0500,,,")
                                        + fila(
                                                OTRO,
                                                "RURAL",
                                                "2026-02-01",
                                                "COLINDANTE,NORTE,Canal de regadio,,,,,")),
                        porque());

        assertThat(informe.rechazadas()).isEmpty();
        assertThat(vigenteDe(OTRO).detalle())
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.type(DetalleRural.class))
                .satisfies(
                        rural -> {
                            assertThat(rural.tierras()).hasSize(1);
                            assertThat(rural.colindantes()).hasSize(1);
                        });
    }

    @Test
    @DisplayName("las categorias constructivas entran por posicion, y el guion es «no se declara»")
    void lasCategoriasEntranPorPosicion() {
        importar.importar(
                new StringReader(
                        ENCABEZADO
                                + fila(
                                        URBANO,
                                        "UNICA",
                                        "2026-02-01",
                                        "CONSTRUCCION,1,120.40,1998,LADRILLO,BUENO,"
                                                + "AB-DEFG,100.00")),
                porque());

        assertThat(vigenteDe(URBANO).construcciones())
                .singleElement()
                .satisfies(
                        construccion -> {
                            assertThat(construccion.categorias().muros()).isEqualTo('A');
                            assertThat(construccion.categorias().techos()).isEqualTo('B');
                            assertThat(construccion.categorias().pisos())
                                    .as("el guion no declara categoria")
                                    .isNull();
                            assertThat(construccion.categorias().instalaciones()).isEqualTo('G');
                        });
    }

    // ------------------------------------------------------------------

    private static Observacion porque() {
        return Observacion.de("Siembra del detalle de las fichas");
    }

    private static String construccion(String codigo, String piso, String area) {
        return fila(
                codigo,
                "UNICA",
                "2026-02-01",
                "CONSTRUCCION," + piso + "," + area + ",2010,LADRILLO,BUENO,CCDCCDC,100.00");
    }

    private static String fila(String codigo, String tipo, String desde, String resto) {
        return codigo
                + ","
                + tipo
                + ","
                + desde
                + ",DECLARACION_JURADA,DJ-DEMO-0101,"
                + resto
                + "\n";
    }

    private FichaCatastral vigenteDe(String codigo) {
        long predioId =
                new PrediosPorCodigo(catastro)
                        .identificadorDe(
                                kamayuk.rentas.dominio.CodigoReferenciaCatastral.de(codigo))
                        .orElseThrow();
        return catastro.fichasRegistradas().stream()
                .filter(ficha -> ficha.predioId() == predioId && ficha.vigenciaHasta() == null)
                .findFirst()
                .orElseThrow();
    }

    private void inscribir(String codigo, TipoFicha tipo, String direccion, String lote) {
        InscribirFicha inscribir =
                new InscribirFicha(
                        catastro,
                        catastro,
                        catastro,
                        new RegistrarPredio(catastro, registro -> {}, reloj()),
                        new ActualizarFichaCatastral(catastro, registro -> {}, reloj()));
        inscribir.inscribir(
                new InscribirFicha.DatosDelPredio(
                        kamayuk.rentas.dominio.CodigoReferenciaCatastral.de(codigo),
                        kamayuk.rentas.catastro.dominio.TipoPredio.URBANO,
                        direccion,
                        "V-0003",
                        "245",
                        "01",
                        "001",
                        lote,
                        "200104"),
                new InscribirFicha.DatosDeLaFicha(
                        tipo,
                        kamayuk.rentas.dominio.AreaM2.de("180.50"),
                        "Casa habitacion",
                        null,
                        LocalDate.of(2026, 1, 1),
                        OrigenDeLaFicha.DECLARACION_JURADA,
                        "DJ-DEMO-0001",
                        List.of(),
                        List.of(),
                        null),
                new InscribirFicha.DatosDelTitular(
                        "C-000001",
                        kamayuk.rentas.catastro.dominio.CondicionDeTitularidad.PROPIETARIO_UNICO,
                        null,
                        "DJ-DEMO-0001"),
                porque());
    }

    private static Clock reloj() {
        return Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
    }
}
