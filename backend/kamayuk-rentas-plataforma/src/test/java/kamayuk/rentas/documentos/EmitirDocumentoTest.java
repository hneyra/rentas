package kamayuk.rentas.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emitir y reimprimir, contra PostgreSQL real (RF-132).
 *
 * <p>Lo que defiende es la promesa que el manual repite en las 231 figuras: <b>reimprimir un
 * documento de hace anos devuelve exactamente el original</b>. Y que eso sea comprobable y no una
 * intencion: el resumen SHA-256 se guarda con la emision y se recalcula al reimprimir.
 */
@DisplayName("RF-132 — Emision y reimpresion identica")
class EmitirDocumentoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TIPO = "ORDEN_PAGO";

    /** El PDF se escribe en WinAnsiEncoding, que es CP-1252 (ver RenderizadorPdf). */
    private static final java.nio.charset.Charset WIN_ANSI =
            java.nio.charset.Charset.forName("windows-1252");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;

    private static EmitirDocumento emitir;
    private static GeneradorDeDocumentos generador;

    /**
     * El mismo caso de uso sobre el mismo registro, pero con un renderizador que dibuja distinto.
     *
     * <p>Es exactamente el escenario que la comprobacion del resumen existe para atrapar: alguien
     * toca una fuente o un margen dentro de dos anos, y las reimpresiones empiezan a salir
     * distintas del original con el mismo numero.
     */
    private static EmitirDocumento emitirConOtroRenderizador;

    /** El mismo caso de uso, pero con la instalacion declarada de demostracion (#122). */
    private static EmitirDocumento emitirEnDemostracion;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("300101", "Municipalidad de los documentos");
        otraMunicipalidad = crearMunicipalidad("300102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();

        generador =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorXls(),
                                new RenderizadorRtf()),
                        RegimenDeLaInstalacion.REAL);

        emitir =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(jdbc, json),
                                generador,
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);

        emitirEnDemostracion =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(jdbc, json),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.DEMOSTRACION),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);

        emitirConOtroRenderizador =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(jdbc, json),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new PdfConOtroMargen(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);
    }

    /** Un PDF con un margen distinto: mismos datos, otros bytes. */
    private static final class PdfConOtroMargen implements Renderizador {

        private final RenderizadorPdf real = new RenderizadorPdf();

        @Override
        public FormatoDeDocumento formato() {
            return FormatoDeDocumento.PDF;
        }

        @Override
        public void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException {
            real.escribir(modelo, salida);
            salida.write(' ');
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("caja.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Emitir")
    class Emitir {

        @Test
        @DisplayName("guarda los datos y el resumen, no el archivo")
        void guardaLosDatosYElResumen() {
            EmitirDocumento.Emision emision = emitirUno("C-100001");

            assertThat(emision.contenido()).isNotEmpty();
            assertThat(emision.registro().resumen())
                    .isEqualTo(GeneradorDeDocumentos.resumenDe(emision.contenido()));
            assertThat(emision.registro().datos().titulo()).isEqualTo("Orden de pago");
            assertThat(emision.registro().reimpresiones()).isZero();
        }

        @Test
        @DisplayName("el numero no se repite dentro del tipo y el ejercicio")
        void elNumeroNoSeRepite() {
            String uno = emitirUno("C-100011").registro().numero();
            String otro = emitirUno("C-100012").registro().numero();

            assertThat(uno).isNotEqualTo(otro);
        }

        @Test
        @DisplayName("el nombre de archivo lleva el numero y la extension del formato")
        void elNombreDeArchivoLlevaElNumero() {
            EmitirDocumento.Emision emision = emitirUno("C-100021");

            assertThat(emision.nombreDeArchivo())
                    .startsWith(emision.registro().numero())
                    .endsWith(".pdf");
        }
    }

    @Nested
    @DisplayName("Reimprimir")
    class Reimprimir {

        @Test
        @DisplayName("devuelve los MISMOS datos, marcados como duplicado")
        void devuelveLosMismosDatosMarcados() {
            EmitirDocumento.Emision original = emitirUno("C-100101");
            String numero = original.registro().numero();

            EmitirDocumento.Emision duplicado =
                    emitir.reimprimir(
                            TIPO,
                            EJERCICIO,
                            numero,
                            FormatoDeDocumento.PDF,
                            Observacion.de("El contribuyente extravio su ejemplar"));

            assertThat(duplicado.registro().reimpresiones()).isEqualTo(1);
            assertThat(duplicado.registro().datos())
                    .as("los datos guardados no se tocan: son la fuente de toda reimpresion")
                    .isEqualTo(original.registro().datos());
            assertThat(
                            new String(
                                    duplicado.contenido(),
                                    java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as(
                            "un duplicado sin marcar circula como si fuera el original, y en un"
                                    + " expediente coactivo eso es un documento de mas")
                    .contains("DUPLICADO N")
                    .contains("1");
        }

        @Test
        @DisplayName("el duplicado numera: el segundo dice 2, no 1 otra vez")
        void elDuplicadoNumera() {
            String numero = emitirUno("C-100111").registro().numero();

            emitir.reimprimir(
                    TIPO, EJERCICIO, numero, FormatoDeDocumento.PDF, Observacion.de("Primera"));
            EmitirDocumento.Emision segunda =
                    emitir.reimprimir(
                            TIPO,
                            EJERCICIO,
                            numero,
                            FormatoDeDocumento.PDF,
                            Observacion.de("Segunda"));

            assertThat(segunda.registro().reimpresiones()).isEqualTo(2);
        }

        @Test
        @DisplayName("se reimprime en OTRO formato: se guardaron los datos, no el archivo")
        void seReimprimeEnOtroFormato() {
            String numero = emitirUno("C-100121").registro().numero();

            EmitirDocumento.Emision hoja =
                    emitir.reimprimir(
                            TIPO,
                            EJERCICIO,
                            numero,
                            FormatoDeDocumento.XLS,
                            Observacion.de("Rentas lo pide en hoja de calculo"));

            assertThat(new String(hoja.contenido(), java.nio.charset.StandardCharsets.UTF_8))
                    .as(
                            "quien recibio un PDF tiene derecho a pedir la misma emision en hoja de"
                                    + " calculo; guardar el archivo lo habria hecho imposible")
                    .contains("<Workbook")
                    .contains("C-100121");
        }

        @Test
        @DisplayName("si el renderizador cambia y ya no se dibuja igual, la reimpresion FALLA")
        void siYaNoSeDibujaIgualFalla() {
            String numero = emitirUno("C-100131").registro().numero();

            assertThatThrownBy(
                            () ->
                                    emitirConOtroRenderizador.reimprimir(
                                            TIPO,
                                            EJERCICIO,
                                            numero,
                                            FormatoDeDocumento.PDF,
                                            Observacion.de("Reimpresion")))
                    .as(
                            "entregar esto seria dar un papel distinto al original con el mismo"
                                    + " numero, y nadie lo notaria: el registro dice que se emitio una"
                                    + " cosa y el contribuyente tiene otra en la mano")
                    .isInstanceOf(EmitirDocumento.LaReimpresionNoCoincide.class);
        }

        @Test
        @DisplayName("reimprimir algo que nunca se emitio no lo emite")
        void reimprimirAlgoInexistenteNoLoEmite() {
            assertThatThrownBy(
                            () ->
                                    emitir.reimprimir(
                                            TIPO,
                                            EJERCICIO,
                                            "NO-EXISTE-0001",
                                            FormatoDeDocumento.PDF,
                                            Observacion.de("Reimpresion")))
                    .isInstanceOf(EmitirDocumento.DocumentoNoEmitido.class);
        }
    }

    @Nested
    @DisplayName("Lo que la base no deja")
    class LoQueLaBaseNoDeja {

        @Test
        @DisplayName("editar un documento emitido lo rechaza el motor, hasta al propietario")
        void editarLosDatosLoRechazaElMotor() throws SQLException {
            String numero = emitirUno("C-100201").registro().numero();

            assertThatThrownBy(() -> reescribirLosDatos(numero))
                    .as(
                            "si los datos estaban mal se emite otro y se anula este; corregirlos en"
                                    + " el sitio dejaria un documento en circulacion que ya no coincide"
                                    + " con su registro")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no se edita");
        }

        @Test
        @DisplayName("un documento de A no existe con el contexto de B")
        void unDocumentoDeANoExisteEnB() {
            String numero = emitirUno("C-100211").registro().numero();

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            assertThat(emitir.buscar(TIPO, EJERCICIO, numero))
                    .as("la prueba corre como sgtm_app, que es a quien la politica RLS aplica")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("#122 — Bajo demostracion, el papel nace marcado y muere marcado")
    class BajoDemostracion {

        @Test
        @DisplayName("el documento emitido sale marcado")
        void elDocumentoEmitidoSaleMarcado() {
            EmitirDocumento.Emision emision = enDemostracion("C-000501");

            assertThat(new String(emision.contenido(), WIN_ANSI))
                    .as("mientras D-02a este abierta, ninguna cifra que salga de aqui esta firmada")
                    .contains(ModeloDeDocumento.MARCA_DE_DEMOSTRACION);
        }

        @Test
        @DisplayName("la marca queda GUARDADA con los datos, no solo dibujada")
        void laMarcaQuedaGuardada() {
            // Es la diferencia entre marcar al dibujar y marcar al emitir. Si solo se
            // dibujara, el modelo guardado saldria limpio y la marca del papel dependeria
            // del regimen del dia en que alguien pide el duplicado.
            EmitirDocumento.Emision emision = enDemostracion("C-000502");

            assertThat(emision.registro().datos().esDemostracion())
                    .as("lo que se guarda es el modelo ya marcado")
                    .isTrue();
        }

        @Test
        @DisplayName("la reimpresion sigue saliendo marcada, y con los mismos bytes")
        void laReimpresionSigueSaliendoMarcada() {
            // Este es el escenario que importa: la municipalidad sale de la marcha blanca,
            // el regimen de la instalacion pasa a real, y alguien pide el duplicado de un
            // papel de entonces. Tiene que salir marcado —lo era— y tiene que salir
            // identico, o la comprobacion del resumen lo rechaza con razon.
            String numero = enDemostracion("C-000503").registro().numero();

            EmitirDocumento.Emision duplicado =
                    emitir.reimprimir(
                            TIPO,
                            EJERCICIO,
                            numero,
                            FormatoDeDocumento.PDF,
                            Observacion.de("El contribuyente pidio copia del documento"));

            String papel = new String(duplicado.contenido(), WIN_ANSI);
            assertThat(papel)
                    .as("reimpreso por una instalacion que YA NO es de demostracion")
                    .contains(ModeloDeDocumento.MARCA_DE_DEMOSTRACION);
            assertThat(papel).contains("DUPLICADO");
        }

        private EmitirDocumento.Emision enDemostracion(String referencia) {
            return emitirEnDemostracion.emitir(
                    TIPO,
                    EJERCICIO,
                    referencia,
                    modelo(referencia),
                    FormatoDeDocumento.PDF,
                    Observacion.de("Emision de la orden de pago del ejercicio"));
        }
    }

    @Nested
    @DisplayName("Emision masiva")
    class Masiva {

        @Test
        @DisplayName("escribe miles de documentos sin acumularlos")
        void escribeMilesSinAcumular() {
            List<ModeloDeDocumento> modelos =
                    java.util.stream.IntStream.range(0, 2_000)
                            .mapToObj(i -> modelo("C-2" + String.format("%05d", i)))
                            .toList();
            Contador salida = new Contador();

            long escritos =
                    emitir.emitirEnLote(
                            modelos.iterator(), FormatoDeDocumento.RTF, modelo -> salida);

            assertThat(escritos).isEqualTo(2_000);
            assertThat(salida.bytes)
                    .as(
                            "cada documento se escribe y se olvida; con una List de bytes, emitir"
                                    + " el padron de una provincia significaria tenerlo entero en"
                                    + " memoria antes de escribir el primero")
                    .isPositive();
        }

        private static final class Contador extends OutputStream {
            private long bytes;

            @Override
            public void write(int unByte) {
                bytes++;
            }

            @Override
            public void write(byte[] datos, int desde, int cuantos) {
                bytes += cuantos;
            }
        }
    }

    // ------------------------------------------------------------------

    private static EmitirDocumento.Emision emitirUno(String referencia) {
        return emitir.emitir(
                TIPO,
                EJERCICIO,
                referencia,
                modelo(referencia),
                FormatoDeDocumento.PDF,
                Observacion.de("Emision de la orden de pago del ejercicio"));
    }

    /**
     * Un modelo con datos <b>ficticios</b>.
     *
     * <p>Las cifras no representan ninguna deuda: este modulo no calcula nada, y el importe entra
     * como texto ya formateado por quien sí sabe formatearlo.
     */
    private static ModeloDeDocumento modelo(String referencia) {
        return new ModeloDeDocumento(
                "Orden de pago",
                null,
                LocalDate.of(2026, 8, 19),
                List.of(Campo.de("Contribuyente", referencia)),
                List.of(
                        Tabla.de(
                                "Detalle",
                                List.of("Concepto", "Importe"),
                                List.of(List.of("Cifra ficticia de prueba", "0,00")))),
                List.of(),
                null,
                null);
    }

    private static void reescribirLosDatos(String numero) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidad);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "UPDATE documento_emitido"
                                    + " SET datos = CAST('{\"titulo\":\"Otro\"}' AS jsonb)"
                                    + " WHERE numero = ?")) {
                sentencia.setString(1, numero);
                sentencia.executeUpdate();
                owner.commit();
            }
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }
}
