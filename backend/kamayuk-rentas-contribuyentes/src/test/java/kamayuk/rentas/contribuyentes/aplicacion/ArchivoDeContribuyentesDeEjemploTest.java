package kamayuk.rentas.contribuyentes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code infra/carga-de-datos/ejemplos/contribuyentes.csv} pasa <b>por el analizador de verdad</b>,
 * fila a fila (#290).
 *
 * <p>Es la mitad de {@code ArchivosDeEjemploTest} que le toca a este contexto: el padron es suyo, y
 * catastro solo puede leer del archivo los codigos con los que cruza sus fichas. Cada uno analiza
 * lo que es de su modulo, que es lo mismo que hace la carga de verdad.
 */
@DisplayName("#290 — El archivo de contribuyentes de ejemplo es valido")
class ArchivoDeContribuyentesDeEjemploTest {

    @Test
    @DisplayName("se carga entero, sin una sola fila rechazada")
    void seCargaEntero() throws IOException {
        PadronEnMemoria padron = new PadronEnMemoria();
        Auditoria auditoria = registro -> {};
        Clock reloj = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
        ImportarContribuyentes importar =
                new ImportarContribuyentes(new RegistrarContribuyente(padron, auditoria, reloj));

        InformeDeImportacion informe =
                importar.importar(
                        abrir("contribuyentes.csv"),
                        Observacion.de("Carga del archivo de ejemplo (#290)"));

        assertThat(informe.rechazadas())
                .as("un tipo de documento mal escrito o un DNI corto saldria aqui")
                .isEmpty();
        assertThat(informe.nuevas()).isEqualTo(informe.totalFilas()).isGreaterThanOrEqualTo(8);
        assertThat(padron.cuantos()).isEqualTo(informe.nuevas());
    }

    @Test
    @DisplayName("los codigos son unicos: cargarlo dos veces no mete a nadie dos veces")
    void losCodigosSonUnicos() throws IOException {
        PadronEnMemoria padron = new PadronEnMemoria();
        Auditoria auditoria = registro -> {};
        Clock reloj = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
        ImportarContribuyentes importar =
                new ImportarContribuyentes(new RegistrarContribuyente(padron, auditoria, reloj));
        Observacion observacion = Observacion.de("Carga del archivo de ejemplo (#290)");

        int primera = importar.importar(abrir("contribuyentes.csv"), observacion).nuevas();
        InformeDeImportacion segunda = importar.importar(abrir("contribuyentes.csv"), observacion);

        assertThat(segunda.nuevas()).isZero();
        assertThat(padron.cuantos()).isEqualTo(primera);
    }

    // ------------------------------------------------------------------

    private static Reader abrir(String nombre) throws IOException {
        return Files.newBufferedReader(ejemplos().resolve(nombre), StandardCharsets.UTF_8);
    }

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
