package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Los dos procesos batch que faltaban para completar la carga inicial del catastro (#121, #290):
 * sectores y manzanas.
 *
 * <p>Sin base de datos, con dobles en memoria. {@code ImportarSectoresTest} e {@code
 * ImportarManzanasTest} ya prueban contra PostgreSQL lo que es del importador —rechazo por fila,
 * reimportar sin duplicar—; lo que estos procesos agregan es leer el archivo de una ruta del
 * sistema de archivos, fijar y limpiar a mano los dos contextos que en una peticion salen del
 * token, y la <b>secuencia</b>: las manzanas necesitan su sector cargado antes.
 */
@DisplayName("#121 — Carga batch de sectores y manzanas")
class CargarSectoresYManzanasTest {

    @TempDir private Path directorio;

    private CatastroEnMemoria catastro;
    private ImportarSectores importarSectores;
    private ImportarManzanas importarManzanas;
    private List<RegistroDeAuditoria> asientos;

    @BeforeEach
    void preparar() {
        catastro = new CatastroEnMemoria();
        asientos = new ArrayList<>();
        Auditoria auditoria = asientos::add;
        Clock reloj = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
        importarSectores = new ImportarSectores(new RegistrarSector(catastro, auditoria, reloj));
        importarManzanas = new ImportarManzanas(new RegistrarManzana(catastro, auditoria, reloj));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("carga los sectores del archivo y deja el contexto limpio")
    void cargaLosSectoresYLimpiaElContexto() throws IOException {
        String archivo =
                escribir(
                        "sectores.csv",
                        """
                        # Sectores de ejemplo.
                        codigo,nombre,zona
                        01,Cercado de Catacaos,Urbana
                        02,Narihuala,Urbana
                        """);

        procesoDeSectores(archivo).run(null);

        assertThat(catastro.sectorPorCodigo("01")).isPresent();
        assertThat(catastro.sectorPorCodigo("02")).isPresent();
        assertThat(asientos).hasSize(2);
        assertThat(TenantContext.actualSiHay())
                .as("el proceso batch no deja el contexto fijado para lo que corra despues")
                .isEmpty();
    }

    @Test
    @DisplayName("un archivo de sectores que no existe falla y de todos modos limpia el contexto")
    void unArchivoQueNoExisteLimpiaElContexto() {
        CargarSectores proceso = procesoDeSectores(directorio.resolve("no-existe.csv").toString());

        assertThatThrownBy(() -> proceso.run(null)).isInstanceOf(IOException.class);

        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName(
            "las manzanas entran cuando su sector ya esta, y la fila mala no arrastra a la buena")
    void lasManzanasEntranConSuSectorCargado() throws IOException {
        catastro.sembrarSector("01", "Cercado de Catacaos");
        String archivo =
                escribir(
                        "manzanas.csv",
                        """
                        sectorCodigo,codigo
                        01,001
                        99,001
                        01,002
                        """);

        procesoDeManzanas(archivo).run(null);

        List<String> codigos =
                catastro.manzanasDe(catastro.sectorPorCodigo("01").orElseThrow().id()).stream()
                        .map(m -> m.codigo())
                        .toList();
        assertThat(codigos)
                .as("la fila del sector 99 se rechaza sola: las de despues entran igual")
                .containsExactly("001", "002");
    }

    @Test
    @DisplayName("sin los sectores cargados, todas las manzanas se rechazan y nada queda a medias")
    void sinSectoresTodasLasManzanasSeRechazan() throws IOException {
        // Es el sintoma de correr los guiones en el orden equivocado, y tiene que ser este:
        // un informe con todas las filas rechazadas, no una carga a medias ni una excepcion
        // que deje el proceso sin decir cuantas entraron.
        String archivo =
                escribir(
                        "manzanas-sin-sector.csv",
                        """
                        sectorCodigo,codigo
                        01,001
                        01,002
                        """);

        procesoDeManzanas(archivo).run(null);

        assertThat(asientos).as("no se escribio ninguna manzana").isEmpty();
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    // ------------------------------------------------------------------

    private CargarSectores procesoDeSectores(String archivo) {
        return new CargarSectores(
                importarSectores,
                new DatosDeCargaSectores(7, archivo, "prueba-sectores", "Carga batch de prueba"));
    }

    private CargarManzanas procesoDeManzanas(String archivo) {
        return new CargarManzanas(
                importarManzanas,
                new DatosDeCargaManzanas(7, archivo, "prueba-manzanas", "Carga batch de prueba"));
    }

    private String escribir(String nombre, String contenido) {
        try {
            Path archivo = directorio.resolve(nombre);
            Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
            return archivo.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
