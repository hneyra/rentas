package kamayuk.rentas.contribuyentes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
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
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.SoloEnDemostracion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

/**
 * La siembra de contribuyentes ficticios (#290), <b>sin base de datos</b>: dobles en memoria.
 *
 * <p>Que la unicidad la exija PostgreSQL ya lo prueba {@code ContribuyenteRepositoryJdbcTest}
 * contra el motor de verdad. Lo que hace falta verificar aqui es lo que este proceso agrega: la
 * guarda que lo detiene fuera de una instalacion de demostracion, que una fila mala no arrastra a
 * la siguiente, que reimportar no duplica y que el contexto de tenant no queda fijado para lo que
 * corra despues.
 */
@DisplayName("#290 — Siembra de contribuyentes de demostracion")
class CargarContribuyentesDeDemostracionTest {

    private static final String ARCHIVO =
            """
            # Personas inventadas.
            codigo,tipoDocumento,numeroDocumento,tipoPersona,nombreRazonSocial,condicionEspecial,fechaNacimiento,estadoCivil
            C-000001,DNI,00000001,NATURAL,DEMO Ramirez Chulle Marina,,1975-03-14,CASADA
            C-000002,DNI,00000002,NATURAL,DEMO Yovera Sandoval Teodoro,PENSIONISTA,1952-11-02,CASADO
            C-000006,RUC,20000000001,JURIDICA,DEMO Ceramica Narihuala S.A.C.,,,
            """;

    @TempDir private Path directorio;

    private PadronEnMemoria padron;
    private ImportarContribuyentes importar;
    private List<RegistroDeAuditoria> asientos;

    @BeforeEach
    void preparar() {
        padron = new PadronEnMemoria();
        asientos = new ArrayList<>();
        Auditoria auditoria = asientos::add;
        Clock reloj = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
        importar = new ImportarContribuyentes(new RegistrarContribuyente(padron, auditoria, reloj));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    // La guarda
    // ------------------------------------------------------------------

    @Test
    @DisplayName("contra una municipalidad que NO es de demostracion se niega y no escribe nada")
    void contraUnaMunicipalidadRealSeNiega() {
        CargarContribuyentesDeDemostracion proceso = proceso(RegimenDeLaInstalacion.REAL);

        assertThatThrownBy(() -> proceso.run(null))
                .isInstanceOf(SoloEnDemostracion.NoEsInstalacionDeDemostracion.class);

        assertThat(padron.cuantos())
                .as(
                        "un --municipalidad-id equivocado en un digito no mete a nadie en un padron real")
                .isZero();
        assertThat(asientos).as("ni deja rastro de auditoria de algo que no paso").isEmpty();
    }

    @Test
    @DisplayName("el contexto queda limpio aunque la guarda lo detenga")
    void elContextoQuedaLimpioAunqueSeDetenga() {
        CargarContribuyentesDeDemostracion proceso = proceso(RegimenDeLaInstalacion.REAL);

        assertThatThrownBy(() -> proceso.run(null)).isInstanceOf(RuntimeException.class);

        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("contra una municipalidad de demostracion siembra, y deja el contexto limpio")
    void contraUnaMunicipalidadDeDemostracionSiembra() throws IOException {
        proceso(RegimenDeLaInstalacion.DEMOSTRACION).run(null);

        assertThat(padron.codigos()).containsExactly("C-000001", "C-000002", "C-000006");
        assertThat(asientos).as("una fila de auditoria por alta").hasSize(3);
        assertThat(TenantContext.actualSiHay())
                .as("el proceso batch no deja el contexto fijado para lo que corra despues")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // El importador
    // ------------------------------------------------------------------

    @Test
    @DisplayName("una fila que repite el codigo no arrastra a la que la sigue")
    void unaFilaMalaNoArrastraALaBuena() {
        String archivo =
                """
                codigo,tipoDocumento,numeroDocumento,tipoPersona,nombreRazonSocial
                C-000001,DNI,00000001,NATURAL,DEMO Primera
                C-000001,DNI,00000002,NATURAL,DEMO Repetida
                C-000003,DNI,00000003,NATURAL,DEMO Tercera
                """;

        InformeDeImportacion informe =
                importar.importar(new StringReader(archivo), Observacion.de("Siembra de prueba"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(2);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).fila()).isEqualTo(3);
        assertThat(padron.codigos()).containsExactly("C-000001", "C-000003");
    }

    @Test
    @DisplayName("una fila mal formada se rechaza con su motivo y las demas entran")
    void unaFilaMalFormadaSeRechaza() {
        String archivo =
                """
                codigo,tipoDocumento,numeroDocumento,tipoPersona,nombreRazonSocial
                C-000001,DNI,123,NATURAL,DEMO Con DNI corto
                C-000002,MARCIANO,00000002,NATURAL,DEMO Con documento inventado
                C-000003,DNI,00000003,NATURAL,DEMO Correcta
                """;

        InformeDeImportacion informe =
                importar.importar(new StringReader(archivo), Observacion.de("Siembra de prueba"));

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(2);
        assertThat(informe.rechazadas().get(0).motivo()).contains("DNI");
        assertThat(informe.rechazadas().get(1).motivo()).contains("MARCIANO");
        assertThat(padron.codigos()).containsExactly("C-000003");
    }

    @Test
    @DisplayName("reimportar el mismo archivo no duplica")
    void reimportarNoDuplica() {
        importar.importar(new StringReader(ARCHIVO), Observacion.de("Primera siembra"));

        InformeDeImportacion segunda =
                importar.importar(
                        new StringReader(ARCHIVO), Observacion.de("Segunda, mismo archivo"));

        assertThat(segunda.nuevas()).isZero();
        assertThat(segunda.rechazadas()).hasSize(3);
        assertThat(padron.cuantos()).isEqualTo(3);
    }

    @Test
    @DisplayName("el metodo que recorre el archivo no lleva @Transactional: cada fila abre la suya")
    void elRecorridoDelArchivoNoEsUnaSolaTransaccion() throws NoSuchMethodException {
        // Es la propiedad que sostiene «la fila mala no arrastra a la buena», y se rompe
        // con una anotacion de una linea: con @Transactional aqui, las tres filas caerian
        // en la misma transaccion y la que viola la unicidad se llevaria a las validas.
        // La prueba de arriba no lo detectaria —los dobles en memoria no se deshacen—,
        // asi que la propiedad se comprueba donde si se ve: en la firma.
        Method importar =
                ImportarContribuyentes.class.getMethod(
                        "importar", java.io.Reader.class, Observacion.class);

        assertThat(importar.getAnnotation(Transactional.class)).isNull();
        assertThat(ImportarContribuyentes.class.getAnnotation(Transactional.class))
                .as("ni en la clase, que anotaria todos sus metodos")
                .isNull();
        assertThat(
                        RegistrarContribuyente.class
                                .getMethod(
                                        "registrar",
                                        kamayuk.rentas.contribuyentes.dominio.Contribuyente.class,
                                        Observacion.class)
                                .getAnnotation(Transactional.class))
                .as("la transaccion vive en el caso de uso al que cada fila llama")
                .isNotNull();
    }

    // ------------------------------------------------------------------

    private CargarContribuyentesDeDemostracion proceso(RegimenDeLaInstalacion regimen) {
        return new CargarContribuyentesDeDemostracion(
                importar,
                new SoloEnDemostracion(regimen),
                new DatosDeCargaContribuyentesDemo(
                        7,
                        escribir("contribuyentes.csv", ARCHIVO),
                        "prueba-demo",
                        "Siembra de prueba"));
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
