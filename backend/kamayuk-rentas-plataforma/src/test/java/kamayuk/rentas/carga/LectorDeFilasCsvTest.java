package kamayuk.rentas.carga;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El lector de archivos de los tres importadores de #121, sin base de datos: es mecanica de texto,
 * no una regla de negocio.
 */
@DisplayName("El lector de filas separadas por comas")
class LectorDeFilasCsvTest {

    @Test
    @DisplayName("descarta el encabezado y numera las filas por su linea real del archivo")
    void descartaElEncabezadoYNumeraPorLineaReal() throws IOException {
        String archivo =
                """
                codigo,tipo,nombre
                V-1,AVENIDA,Avenida Uno
                V-2,CALLE,Calle Dos
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).numeroDeLinea()).as("primera fila de datos: linea 2").isEqualTo(2);
        assertThat(filas.get(0).campos()).containsExactly("V-1", "AVENIDA", "Avenida Uno");
        assertThat(filas.get(1).numeroDeLinea()).isEqualTo(3);
    }

    @Test
    @DisplayName("las lineas en blanco se saltan, pero cuentan para la numeracion")
    void lasLineasEnBlancoNoRompenLaNumeracion() throws IOException {
        String archivo =
                """
                codigo,nombre
                S-1,Sector Uno

                S-2,Sector Dos
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).numeroDeLinea()).isEqualTo(2);
        assertThat(filas.get(1))
                .as("la linea en blanco (3) no genera fila, y la siguiente sigue siendo la 4")
                .satisfies(
                        fila -> {
                            assertThat(fila.numeroDeLinea()).isEqualTo(4);
                            assertThat(fila.campos()).containsExactly("S-2", "Sector Dos");
                        });
    }

    @Test
    @DisplayName("un campo entre comillas puede traer una coma")
    void unCampoEntreComillasPuedeTraerUnaComa() throws IOException {
        String archivo =
                """
                codigo,tipo,nombre
                V-3,AVENIDA,"Avenida Grau, tramo norte"
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).campos())
                .containsExactly("V-3", "AVENIDA", "Avenida Grau, tramo norte");
    }

    @Test
    @DisplayName("una comilla doble dentro de un campo entre comillas se escribe dos veces")
    void unaComillaEscapadaSeEscribeDosVeces() throws IOException {
        // Fuera de un bloque de texto: la fila termina en tres comillas seguidas
        // («escapada» + cierre), y eso choca con el delimitador de cierre del bloque.
        String archivo = "codigo,nombre\n" + "S-9,\"Sector \"\"El Progreso\"\"\"\n";

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas.get(0).campos()).containsExactly("S-9", "Sector \"El Progreso\"");
    }

    @Test
    @DisplayName("los espacios alrededor de cada campo se recortan")
    void losEspaciosSeRecortan() throws IOException {
        String archivo =
                """
                sectorCodigo,codigo
                 S-1 , 001
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas.get(0).campos()).containsExactly("S-1", "001");
    }

    @Test
    @DisplayName("un archivo con solo el encabezado no trae ninguna fila")
    void unArchivoConSoloElEncabezadoNoTraeFilas() throws IOException {
        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader("codigo,nombre\n"));

        assertThat(filas).isEmpty();
    }

    @Test
    @DisplayName("un archivo vacio no falla y no trae filas")
    void unArchivoVacioNoFalla() throws IOException {
        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(""));

        assertThat(filas).isEmpty();
    }

    @Test
    @DisplayName("los comentarios de cabecera no son el encabezado ni una fila")
    void losComentariosDeCabeceraNoSonElEncabezado() throws IOException {
        // Sin esto, el archivo de ejemplo versionado no puede decir de donde salio: la
        // primera linea del archivo seria el aviso, y el encabezado real entraria como
        // fila de datos.
        String archivo =
                """
                # Catalogo vial de ejemplo — Catacaos.
                # ESTRUCTURA, no valores.
                codigo,tipo,nombre
                V-1,AVENIDA,Avenida Uno
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).campos()).containsExactly("V-1", "AVENIDA", "Avenida Uno");
        assertThat(filas.get(0).numeroDeLinea())
                .as("la numeracion sigue siendo la linea real del archivo, comentarios incluidos")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("un comentario entre filas se salta sin descolocar la numeracion")
    void unComentarioEntreFilasSeSalta() throws IOException {
        String archivo =
                """
                codigo,nombre
                S-1,Sector Uno
                # los dos siguientes son del anexo
                S-2,Sector Dos
                """;

        List<FilaCsv> filas = LectorDeFilasCsv.leer(new StringReader(archivo));

        assertThat(filas).hasSize(2);
        assertThat(filas.get(1).numeroDeLinea()).isEqualTo(4);
        assertThat(filas.get(1).campos()).containsExactly("S-2", "Sector Dos");
    }
}
