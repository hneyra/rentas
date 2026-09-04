package kamayuk.rentas.catastro.aplicacion;

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
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
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
 * La siembra de predios y fichas ficticios (#290), <b>sin base de datos</b>: dobles en memoria.
 *
 * <p>Lo que persiste PostgreSQL —el indice unico parcial de la ficha vigente, el disparador
 * diferido de la titularidad— ya tiene sus pruebas contra el motor de verdad. Lo que se verifica
 * aqui es lo que este proceso agrega: la guarda que lo detiene fuera de una instalacion de
 * demostracion, que el codigo de referencia catastral se <b>compone</b> a partir de los tramos del
 * archivo, y que una fila mala no arrastra a la que la sigue.
 */
@DisplayName("#290 — Siembra de predios y fichas de demostracion")
class CargarFichasDeDemostracionTest {

    private static final String ENCABEZADO =
            "departamento,provincia,distrito,sector,manzana,lote,edificacion,entrada,piso,unidad,"
                    + "tipoPredio,direccion,codigoVia,numeroMunicipal,tipoFicha,areaTerreno,uso,"
                    + "denominacion,vigenciaDesde,origen,documentoOrigen,codigoContribuyente,"
                    + "condicionTitular,porcentaje,documentoTitular\n";

    private static final String UNA_FILA =
            "20,01,04,01,001,001,00,00,00,000,URBANO,Calle Comercio 245,V-0003,245,UNICA,180.50,"
                    + "Casa habitacion,,2026-01-01,DECLARACION_JURADA,DJ-DEMO-0001,C-000001,"
                    + "PROPIETARIO_UNICO,,DJ-DEMO-0001\n";

    @TempDir private Path directorio;

    private CatastroEnMemoria catastro;
    private ImportarFichas importar;
    private List<RegistroDeAuditoria> asientos;

    @BeforeEach
    void preparar() {
        catastro = new CatastroEnMemoria();
        catastro.sembrarSector("01", "Cercado de Catacaos");
        catastro.sembrarManzana("01", "001");
        catastro.sembrarVia(new Via(null, "V-0003", TipoVia.CALLE, "Comercio", "200104", true));
        catastro.sembrarContribuyente("C-000001", "DEMO Ramirez Chulle Marina");

        asientos = new ArrayList<>();
        Auditoria auditoria = asientos::add;
        Clock reloj = Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));
        importar =
                new ImportarFichas(
                        new InscribirFicha(
                                catastro,
                                catastro,
                                catastro,
                                new RegistrarPredio(catastro, auditoria, reloj),
                                new ActualizarFichaCatastral(catastro, auditoria, reloj)));
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
        CargarFichasDeDemostracion proceso = proceso(RegimenDeLaInstalacion.REAL);

        assertThatThrownBy(() -> proceso.run(null))
                .isInstanceOf(SoloEnDemostracion.NoEsInstalacionDeDemostracion.class)
                .hasMessageContaining("es_demostracion");

        assertThat(catastro.prediosRegistrados())
                .as("ni un predio inventado en el padron de una municipalidad que ya opera")
                .isEmpty();
        assertThat(catastro.fichasRegistradas()).isEmpty();
        assertThat(asientos).isEmpty();
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("contra una de demostracion inscribe predio, ficha y titular en el mismo acto")
    void contraUnaDeDemostracionSiembra() throws IOException {
        proceso(RegimenDeLaInstalacion.DEMOSTRACION).run(null);

        assertThat(catastro.prediosRegistrados()).hasSize(1);
        assertThat(catastro.fichasRegistradas()).hasSize(1);
        assertThat(catastro.titularidadesRegistradas()).hasSize(1);
        assertThat(catastro.titularidadesRegistradas().get(0).condicion())
                .isEqualTo(CondicionDeTitularidad.PROPIETARIO_UNICO);
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    // ------------------------------------------------------------------
    // La composicion del codigo
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "el codigo de referencia catastral se compone de los tramos, con ceros a la izquierda")
    void elCodigoSeComponeDeLosTramos() {
        importar.importar(new StringReader(ENCABEZADO + UNA_FILA), Observacion.de("Siembra"));

        assertThat(catastro.prediosRegistrados()).hasSize(1);
        assertThat(catastro.prediosRegistrados().get(0).codigo().valor())
                .as("ninguna columna del archivo trae el codigo entero: lo arma el dominio")
                .isEqualTo("20010401001001000000000");
        assertThat(catastro.prediosRegistrados().get(0).ubigeo())
                .as("el ubigeo son los tres primeros tramos del propio codigo")
                .isEqualTo("200104");
    }

    @Test
    @DisplayName("un tramo que no cabe en sus digitos se rechaza, no se recorta")
    void unTramoQueNoCabeSeRechaza() {
        String fila = UNA_FILA.replaceFirst("^20,01,04,01,001,", "20,01,04,01,0001,");

        InformeDeImportacion informe =
                importar.importar(new StringReader(ENCABEZADO + fila), Observacion.de("Siembra"));

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("manzana");
        assertThat(catastro.prediosRegistrados()).isEmpty();
    }

    // ------------------------------------------------------------------
    // El importador
    // ------------------------------------------------------------------

    @Test
    @DisplayName("una fila que nombra un sector inexistente no arrastra a la que la sigue")
    void unaFilaMalaNoArrastraALaBuena() {
        catastro.sembrarManzana("01", "002");
        String mala = UNA_FILA.replaceFirst("^20,01,04,01,", "20,01,04,99,");
        String buena = UNA_FILA.replaceFirst("^20,01,04,01,001,001,", "20,01,04,01,002,001,");

        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(ENCABEZADO + mala + buena), Observacion.de("Siembra"));

        assertThat(informe.totalFilas()).isEqualTo(2);
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("sector");
        assertThat(catastro.prediosRegistrados()).hasSize(1);
    }

    @Test
    @DisplayName("repetir la misma fila no crea una segunda ficha del mismo tipo")
    void repetirLaMismaFilaNoDuplica() {
        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(ENCABEZADO + UNA_FILA + UNA_FILA),
                        Observacion.de("Siembra"));

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(catastro.fichasRegistradas()).hasSize(1);
        assertThat(catastro.prediosRegistrados())
                .as("el predio existente se reutiliza; no nace uno segundo con el mismo codigo")
                .hasSize(1);
    }

    @Test
    @DisplayName("una fila sin titular inscribe el predio y su ficha, sin titularidad")
    void unaFilaSinTitularInscribeIgual() {
        String sinTitular = UNA_FILA.replace(",C-000001,PROPIETARIO_UNICO,,DJ-DEMO-0001", ",,,,");

        InformeDeImportacion informe =
                importar.importar(
                        new StringReader(ENCABEZADO + sinTitular), Observacion.de("Siembra"));

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(catastro.fichasRegistradas()).hasSize(1);
        assertThat(catastro.titularidadesRegistradas())
                .as("fichar antes de identificar al propietario es lo normal en un levantamiento")
                .isEmpty();
    }

    @Test
    @DisplayName("un tipo de ficha desconocido se rechaza nombrando los validos")
    void unTipoDeFichaDesconocidoSeRechaza() {
        String fila = UNA_FILA.replace(",UNICA,", ",MIXTA,");

        InformeDeImportacion informe =
                importar.importar(new StringReader(ENCABEZADO + fila), Observacion.de("Siembra"));

        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo())
                .contains("MIXTA")
                .contains(TipoFicha.BIENES_COMUNES.name());
    }

    @Test
    @DisplayName("el metodo que recorre el archivo no lleva @Transactional: cada fila abre la suya")
    void elRecorridoDelArchivoNoEsUnaSolaTransaccion() throws NoSuchMethodException {
        // Con @Transactional aqui, las filas caerian todas en la misma transaccion y la
        // que revienta se llevaria a las validas que la seguian —el defecto que la prueba
        // de arriba no puede ver con dobles en memoria, porque nada se deshace—.
        Method importar =
                ImportarFichas.class.getMethod("importar", java.io.Reader.class, Observacion.class);

        assertThat(importar.getAnnotation(Transactional.class)).isNull();
        assertThat(ImportarFichas.class.getAnnotation(Transactional.class)).isNull();
        assertThat(
                        InscribirFicha.class
                                .getMethod(
                                        "inscribir",
                                        InscribirFicha.DatosDelPredio.class,
                                        InscribirFicha.DatosDeLaFicha.class,
                                        InscribirFicha.DatosDelTitular.class,
                                        Observacion.class)
                                .getAnnotation(Transactional.class))
                .as("la transaccion vive en el acto de inscribir, que es atomico de los tres")
                .isNotNull();
    }

    // ------------------------------------------------------------------

    private CargarFichasDeDemostracion proceso(RegimenDeLaInstalacion regimen) {
        return new CargarFichasDeDemostracion(
                importar,
                new SoloEnDemostracion(regimen),
                new DatosDeCargaFichasDemo(
                        7,
                        escribir("fichas.csv", ENCABEZADO + UNA_FILA),
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
