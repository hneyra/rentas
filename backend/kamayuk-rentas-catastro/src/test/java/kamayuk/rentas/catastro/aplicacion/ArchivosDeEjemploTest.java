package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los archivos de {@code infra/carga-de-datos/ejemplos/} pasan <b>por el analizador de verdad</b>,
 * fila a fila (#290).
 *
 * <p>Un archivo de ejemplo versionado se copia y se carga tal cual: si tiene una columna de menos,
 * un enumerado mal escrito o una manzana cuyo sector no existe, el sintoma aparece contra un
 * ambiente real —o, peor, en una demostracion delante de alguien—. Aqui aparece en el build, que
 * cuesta segundos.
 *
 * <p>No basta con que cada archivo se analice solo: se cargan <b>en su orden</b> contra el mismo
 * catastro en memoria, asi que se comprueba tambien la coherencia entre ellos —cada manzana con su
 * sector, cada ficha con su sector, su manzana, su via y su contribuyente—.
 */
@DisplayName("#290 — Los archivos de carga de ejemplo son validos")
class ArchivosDeEjemploTest {

    /** Palabras de valor normativo: ninguna puede aparecer en una fila de datos (regla 5). */
    private static final List<String> VALORES_NORMATIVOS =
            List.of("arancel", "valor unitario", "valorm2", "depreciacion", "uit", "alicuota");

    private CatastroEnMemoria catastro;
    private Observacion observacion;

    @BeforeEach
    void preparar() {
        catastro = new CatastroEnMemoria();
        observacion = Observacion.de("Carga de los archivos de ejemplo (#290)");
    }

    @Test
    @DisplayName("los diez archivos de la siembra existen donde el README dice")
    void losArchivosExisten() {
        assertThat(ejemplos()).isDirectory();
        for (String nombre :
                List.of(
                        "vias.csv",
                        "sectores.csv",
                        "manzanas.csv",
                        // El paso 4 (#430): sin una `caja` la ventanilla no se puede abrir, y
                        // hasta ese issue nada la creaba fuera de las fixtures de prueba. Que
                        // se analice de verdad lo comprueba AltaDeCajasJdbcTest, en
                        // kamayuk-rentas-tesoreria, contra PostgreSQL.
                        "cajas.csv",
                        "contribuyentes.csv",
                        "fichas.csv",
                        "detalle-de-fichas.csv",
                        // Los tres de rentas: aqui solo se comprueba que esten, porque
                        // `sembrar-demostracion.sh` los nombra y una siembra que descubre
                        // en el paso 7 que falta el archivo del 8 queda a medias, y a
                        // medias es el estado que peor se lee. Que se analicen de verdad
                        // lo comprueba ArchivosDeEjemploDeRentasTest, en kamayuk-rentas-rentas.
                        "vehiculos.csv",
                        "transferencias.csv",
                        "deuda.csv")) {
            assertThat(ejemplos().resolve(nombre)).as(nombre).isRegularFile();
        }
        assertThat(ejemplos().getParent().resolve("README.md")).isRegularFile();
    }

    @Test
    @DisplayName("vias, sectores y manzanas se cargan enteros, sin una sola fila rechazada")
    void laEstructuraSeCargaEntera() throws IOException {
        Clock reloj = reloj();
        Auditoria auditoria = registro -> {};

        InformeDeImportacion vias =
                new ImportarVias(new RegistrarVia(catastro, auditoria, reloj))
                        .importar(abrir("vias.csv"), observacion);
        InformeDeImportacion sectores =
                new ImportarSectores(new RegistrarSector(catastro, auditoria, reloj))
                        .importar(abrir("sectores.csv"), observacion);
        InformeDeImportacion manzanas =
                new ImportarManzanas(new RegistrarManzana(catastro, auditoria, reloj))
                        .importar(abrir("manzanas.csv"), observacion);

        assertThat(vias.rechazadas()).isEmpty();
        assertThat(vias.nuevas()).isEqualTo(vias.totalFilas()).isGreaterThanOrEqualTo(15);
        assertThat(sectores.rechazadas()).isEmpty();
        assertThat(sectores.nuevas()).isEqualTo(sectores.totalFilas()).isGreaterThanOrEqualTo(4);
        assertThat(manzanas.rechazadas())
                .as("una manzana cuyo sector no este en sectores.csv se rechazaria aqui")
                .isEmpty();
        assertThat(manzanas.nuevas()).isEqualTo(manzanas.totalFilas()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("fichas.csv se carga entero sobre esa estructura, con los cuatro tipos de ficha")
    void lasFichasSeCarganSobreLaEstructura() throws IOException {
        cargarLaEstructura();

        InformeDeImportacion fichas = importarFichas().importar(abrir("fichas.csv"), observacion);

        assertThat(fichas.rechazadas())
                .as(
                        "cada fila nombra sector, manzana, via y contribuyente: si uno falla, sale aqui")
                .isEmpty();
        assertThat(fichas.nuevas()).isEqualTo(fichas.totalFilas()).isGreaterThanOrEqualTo(20);
        assertThat(catastro.fichasRegistradas().stream().map(f -> f.tipo()).distinct().toList())
                .as(
                        "los cuatro tipos de ficha, para que la demostracion muestre las cuatro pantallas")
                .containsExactlyInAnyOrder(TipoFicha.values());
        // Uno, y solo uno, sin titular. No es un descuido del archivo: en un levantamiento
        // catastral es lo normal fichar antes de identificar al propietario, InscribirFicha
        // lo admite, y es el unico modo de que la conciliacion catastro-rentas (ADR-0015) y
        // la deteccion de omisos tengan delante el caso que existen para tratar. Se cuenta
        // en vez de admitirse «alguno»: dos serian un archivo que se esta desmoronando.
        assertThat(catastro.fichasRegistradas().size() - catastro.titularidadesRegistradas().size())
                .as("exactamente un predio de ejemplo se ficha sin titular identificado")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("detalle-de-fichas.csv versiona las fichas, sin un solo predio rechazado")
    void elDetalleSeCargaSobreLasFichas() throws IOException {
        cargarLaEstructura();
        importarFichas().importar(abrir("fichas.csv"), observacion);
        int inscritas = catastro.fichasRegistradas().size();

        InformeDeImportacion detalle =
                new ImportarDetalleDeFichas(
                                new ActualizarFichaCatastral(catastro, registro -> {}, reloj()),
                                new PrediosPorCodigo(catastro))
                        .importar(abrir("detalle-de-fichas.csv"), observacion);

        assertThat(detalle.rechazadas())
                .as(
                        "cada grupo nombra su predio y su tipo de ficha: si uno falla, sale aqui"
                                + " —incluido el anio de construccion anterior a 1990, que Ejercicio"
                                + " no admite—")
                .isEmpty();
        assertThat(detalle.nuevas())
                .as("nuevas cuenta FICHAS VERSIONADAS, no filas: varias filas son una escritura")
                .isLessThan(detalle.totalFilas())
                .isGreaterThanOrEqualTo(20);

        // Cada version nueva es una ficha mas en la base: la anterior no se borra (regla 4).
        assertThat(catastro.fichasRegistradas()).hasSize(inscritas + detalle.nuevas());

        assertThat(vigentes())
                .as(
                        "las cuatro clases de detalle, para que las cuatro pantallas tengan que dibujar")
                .anySatisfy(ficha -> assertThat(ficha.construcciones()).isNotEmpty())
                .anySatisfy(ficha -> assertThat(ficha.instalaciones()).isNotEmpty())
                .anySatisfy(
                        ficha -> assertThat(ficha.detalle()).isInstanceOf(DetalleEconomico.class))
                .anySatisfy(
                        ficha ->
                                assertThat(ficha.detalle())
                                        .isInstanceOf(DetalleDeBienesComunes.class))
                .anySatisfy(ficha -> assertThat(ficha.detalle()).isInstanceOf(DetalleRural.class));

        // Y un predio que se queda sin construcciones, porque es un terreno sin construir:
        // la pantalla tiene que saber dibujar eso, y sin este caso nunca se le pediria.
        assertThat(vigentes())
                .as("el terreno sin construir conserva su ficha vacia")
                .anySatisfy(ficha -> assertThat(ficha.construcciones()).isEmpty());
    }

    @Test
    @DisplayName("los codigos catastrales quedan bien compuestos: todos de la misma longitud")
    void losCodigosQuedanBienCompuestos() throws IOException {
        cargarLaEstructura();
        importarFichas().importar(abrir("fichas.csv"), observacion);

        assertThat(catastro.prediosRegistrados())
                .allSatisfy(
                        predio ->
                                assertThat(predio.codigo().valor())
                                        .hasSize(
                                                kamayuk.rentas.dominio.ComposicionCatastral
                                                        .DEL_MANUAL
                                                        .longitud()))
                .allSatisfy(predio -> assertThat(predio.codigo().ubigeo()).isEqualTo("200104"));
    }

    @Test
    @DisplayName("ninguna fila de dato trae un valor normativo (regla 5, D-02a y D-13)")
    void ningunaFilaTraeUnValorNormativo() throws IOException {
        for (String nombre :
                List.of(
                        "vias.csv",
                        "sectores.csv",
                        "manzanas.csv",
                        // El paso 4 (#430): sin una `caja` la ventanilla no se puede abrir, y
                        // hasta ese issue nada la creaba fuera de las fixtures de prueba. Que
                        // se analice de verdad lo comprueba AltaDeCajasJdbcTest, en
                        // kamayuk-rentas-tesoreria, contra PostgreSQL.
                        "cajas.csv",
                        "contribuyentes.csv",
                        "fichas.csv",
                        "detalle-de-fichas.csv")) {
            List<String> lineas = Files.readAllLines(ejemplos().resolve(nombre));
            for (String linea : lineas) {
                if (linea.stripLeading().startsWith("#")) {
                    continue; // los comentarios explican justamente que no se siembran
                }
                String minuscula = linea.toLowerCase(Locale.ROOT);
                for (String prohibida : VALORES_NORMATIVOS) {
                    assertThat(minuscula)
                            .as(
                                    "%s: un arancel o un valor unitario inventado no se distingue de"
                                            + " uno real, y sus pantallas tienen que seguir diciendo «sin"
                                            + " conjunto sellado»",
                                    nombre)
                            .doesNotContain(prohibida);
                }
            }
        }
    }

    @Test
    @DisplayName("los documentos de contribuyentes.csv son de mentira, y se nota")
    void losDocumentosSonDeMentira() throws IOException {
        List<FilaCsv> filas = LectorDeFilasCsv.leer(abrir("contribuyentes.csv"));

        assertThat(filas).hasSizeGreaterThanOrEqualTo(16);
        assertThat(filas)
                .allSatisfy(
                        fila -> {
                            String numero = fila.campos().get(2);
                            assertThat(numero)
                                    .as("un DNI 00000001 o un RUC 20000000001 no es de nadie")
                                    .matches("0{5}\\d{1,3}|20000000\\d{3}");
                            assertThat(fila.campos().get(4))
                                    .as("el nombre se reconoce como inventado en cualquier reporte")
                                    .startsWith("DEMO ");
                        });
    }

    // ------------------------------------------------------------------

    private void cargarLaEstructura() throws IOException {
        Clock reloj = reloj();
        Auditoria auditoria = registro -> {};
        new ImportarVias(new RegistrarVia(catastro, auditoria, reloj))
                .importar(abrir("vias.csv"), observacion);
        new ImportarSectores(new RegistrarSector(catastro, auditoria, reloj))
                .importar(abrir("sectores.csv"), observacion);
        new ImportarManzanas(new RegistrarManzana(catastro, auditoria, reloj))
                .importar(abrir("manzanas.csv"), observacion);

        // El padron es de otro contexto acotado: aqui solo se leen los codigos del archivo
        // de contribuyentes, para que el cruce de fichas.csv contra ellos sea real. Que ese
        // archivo se analice de verdad lo comprueba su propia prueba, en
        // kamayuk-rentas-contribuyentes.
        for (FilaCsv fila : LectorDeFilasCsv.leer(abrir("contribuyentes.csv"))) {
            catastro.sembrarContribuyente(fila.campos().get(0), fila.campos().get(4));
        }
    }

    /** Las fichas todavia abiertas: una por predio, la ultima version de cada uno. */
    private List<FichaCatastral> vigentes() {
        return catastro.fichasRegistradas().stream()
                .filter(ficha -> ficha.vigenciaHasta() == null)
                .toList();
    }

    private ImportarFichas importarFichas() {
        Clock reloj = reloj();
        Auditoria auditoria = registro -> {};
        return new ImportarFichas(
                new InscribirFicha(
                        catastro,
                        catastro,
                        catastro,
                        new RegistrarPredio(catastro, auditoria, reloj),
                        new ActualizarFichaCatastral(catastro, auditoria, reloj)));
    }

    private static Clock reloj() {
        return Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
    }

    private static Reader abrir(String nombre) throws IOException {
        return Files.newBufferedReader(ejemplos().resolve(nombre), StandardCharsets.UTF_8);
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
