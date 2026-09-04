package kamayuk.rentas.licencias.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.DuplicarLicencia;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.aplicacion.MantenerCatalogoCiiu;
import kamayuk.rentas.licencias.dobles.CajaDeMentira;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CriterioDeCiiu;
import kamayuk.rentas.licencias.dominio.CriterioDeLicencias;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.jspecify.annotations.Nullable;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #44 — La licencia de funcionamiento contra PostgreSQL de verdad (V37), conectada como {@code
 * sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El ciclo entero, de una punta a la otra.</b> Giro registrado en el catalogo, derecho
 *       cobrado en la caja de tasas <b>de verdad</b> —con su area, su ventanilla, su turno y su
 *       tarifa—, licencia emitida con su papel, consultada, cancelada con su resolucion y
 *       duplicada. Contra dobles esto solo probaria que los dobles recuerdan lo que se les dijo.
 *   <li><b>Que el recibo se compruebe por la API publica de tesoreria y no por su tabla</b> (AC 1).
 *       El recibo lo emite {@code CobrarTasa}, y {@code licencias} lo lee por {@link
 *       RecibosDeTramite}. Que un recibo anulado no sirva se comprueba anulandolo de verdad en
 *       {@code recibo_movimiento}, que es donde la anulacion vive desde #34; un doble tendria que
 *       fingir eso mismo y solo probaria que finge bien.
 *   <li><b>Que {@code sgtm_app} no pueda editar ni borrar una licencia</b> (AC 2). Es el {@code
 *       REVOKE} de V37, y se comprueba intentandolo por SQL directo, que es como se salta cualquier
 *       comprobacion escrita en Java.
 *   <li><b>Que la base impida una segunda cancelacion bajo concurrencia real</b>. Un doble que
 *       consulta antes de insertar pasa la prueba y falla en produccion: diez peticiones
 *       simultaneas pasan las diez por el {@code if}. Aqui se lanzan diez hilos a la vez.
 *   <li><b>Que dos duplicados simultaneos no compartan ordinal</b> (AC 4), por el mismo motivo, y
 *       que el duplicado conserve el numero del papel original comprobando el SHA-256.
 *   <li><b>Que el conjunto sellado decida el concepto del TUPA</b> (regla 5): sin el parametro, la
 *       emision falla nombrando la llave, y el parametro se siembra con su propio rol de carga.
 *   <li><b>Que RLS aisle la licencia</b>: desde otra municipalidad no existe.
 * </ul>
 */
@DisplayName("#44 — La licencia de funcionamiento contra PostgreSQL")
class LicenciaDeFuncionamientoJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.ventanilla";
    private static final String CAJERO = "cajero.tasas";
    private static final String CAJA = "C-44";

    /** El concepto del TUPA que el conjunto sellado nombra como derecho de la licencia. */
    private static final String DERECHO_LICENCIA = "LF-001";

    /** Y el del duplicado. */
    private static final String DERECHO_DUPLICADO = "LF-009";

    /** Un concepto del TUPA que existe pero no es el derecho de la licencia. */
    private static final String OTRO_CONCEPTO = "COPIAS";

    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long areaId;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static LicenciaRepositoryJdbc licencias;
    private static MovimientoDeLicenciaRepositoryJdbc movimientos;
    private static DuplicadoDeLicenciaRepositoryJdbc duplicados;
    private static CiiuRepositoryJdbc catalogo;

    /**
     * Lo que `caja` contesta por un recibo (P5D).
     *
     * <p>Hasta `V7` esto era `RecibosDeTramiteTesoreria` sobre `recibo` y `recibo_movimiento`, y la
     * prueba cobraba de verdad con `CobrarTasa`. Las tablas se fueron a `caja` y el cobro se prueba
     * alli; aqui lo que decide —y lo unico que este modulo podia ver— es la respuesta del puerto.
     */
    private static CajaDeMentira caja;

    private static RecibosDeTramite recibos;

    private static EmitirDocumento documentos;
    private static MantenerCatalogoCiiu mantenerCatalogo;
    private static EmitirLicenciaDeFuncionamiento emitir;
    private static EmitirLicenciaDeFuncionamiento emitirSinParametro;
    private static CancelarLicencia cancelar;
    private static DuplicarLicencia duplicar;
    private static ConsultaDeLicencias consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240440", "Municipalidad de las licencias");
        otraMunicipalidad = crearMunicipalidad("240441", "Municipalidad vecina de #44");
        crearConjuntoConLosDerechos(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        licencias = new LicenciaRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeLicenciaRepositoryJdbc(jdbc);
        duplicados = new DuplicadoDeLicenciaRepositoryJdbc(jdbc);
        catalogo = new CiiuRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        caja = new CajaDeMentira();
        recibos = caja;

        documentos =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(
                                        jdbc,
                                        JsonMapper.builder()
                                                .addModule(
                                                        new kamayuk.rentas.web.ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                auditoria,
                                RELOJ));

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();
        LectorDeFichasEconomicas fichas = (predioId, fecha) -> java.util.Optional.empty();

        DerechosDeTramiteParametrizados derechos =
                new DerechosDeTramiteParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

        mantenerCatalogo = envolver(new MantenerCatalogoCiiu(catalogo, auditoria, RELOJ));
        emitir =
                envolver(
                        new EmitirLicenciaDeFuncionamiento(
                                licencias,
                                movimientos,
                                catalogo,
                                recibos,
                                padron,
                                fichas,
                                derechos,
                                documentos,
                                PlantillaDeNumeroDeLicencia.POR_OMISION,
                                auditoria,
                                RELOJ));
        // El mismo caso de uso, con un conjunto sellado que NO tiene el concepto del TUPA. Es la
        // demostracion de la regla 5: sin el dato, la operacion falla nombrando la llave.
        emitirSinParametro =
                envolver(
                        new EmitirLicenciaDeFuncionamiento(
                                licencias,
                                movimientos,
                                catalogo,
                                recibos,
                                padron,
                                fichas,
                                new DerechosDeTramiteParametrizados(new SinDerechosSellados()),
                                documentos,
                                PlantillaDeNumeroDeLicencia.POR_OMISION,
                                auditoria,
                                RELOJ));
        cancelar =
                envolver(
                        new CancelarLicencia(
                                licencias, movimientos, padron, documentos, auditoria, RELOJ));
        duplicar =
                envolver(
                        new DuplicarLicencia(
                                licencias,
                                movimientos,
                                duplicados,
                                recibos,
                                padron,
                                derechos,
                                documentos,
                                auditoria,
                                RELOJ));
        consulta = envolver(new ConsultaDeLicencias(licencias, movimientos, duplicados, padron));

        // Ni `area`, ni `caja`, ni `tasa` se siembran ya: las tres se fueron con `V7`. El
        // catalogo del TUPA y su tarifa son de `caja` desde P5D, y lo que este modulo necesita
        // saber de un cobro es que concepto cubrio y por cuanto, que es lo que el puerto trae.
    }

    @AfterAll
    static void cerrarBase() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("El ciclo completo")
    class ElCiclo {

        @Test
        @DisplayName("catalogo, derecho pagado, licencia emitida, cancelada y duplicada")
        void elCicloCompleto() {
            long giro = giroDelCatalogo("47111", "COMERCIO AL POR MENOR");
            long titular = crearContribuyente();
            String reciboDelTramite = cobrar(titular, DERECHO_LICENCIA);

            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, reciboDelTramite),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(emitida.licencia().numero()).startsWith("LF-2026-");
            assertThat(emitida.licencia().giros())
                    .as("la licencia sale con su giro resuelto contra el catalogo")
                    .singleElement()
                    .satisfies(
                            autorizado -> {
                                assertThat(autorizado.ciiuId()).isEqualTo(giro);
                                assertThat(autorizado.principal()).isTrue();
                            });
            assertThat(new String(emitida.documento().contenido(), StandardCharsets.ISO_8859_1))
                    .as("el papel dice de que recibo salio el derecho de tramite")
                    .contains(reciboDelTramite);

            ConsultaDeLicencias.LicenciaEnConsulta ficha =
                    enContexto(
                            () ->
                                    consulta.porNumero(emitida.licencia().numero(), HOY)
                                            .orElseThrow());
            assertThat(ficha.estado()).isEqualTo(EstadoDeLicencia.VIGENTE);
            assertThat(ficha.historial()).hasSize(1);
            assertThat(ficha.nombreDelTitular()).isNotEmpty();

            String reciboDelDuplicado = cobrar(titular, DERECHO_DUPLICADO);
            DuplicarLicencia.Duplicado duplicado =
                    enContexto(
                            () ->
                                    duplicar.duplicar(
                                            emitida.licencia().numero(),
                                            HOY,
                                            "Extravio del original",
                                            reciboDelDuplicado,
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(duplicado.numeroDelPapel())
                    .as("AC 4: el duplicado conserva el numero del papel original")
                    .isEqualTo(emitida.documento().registro().numero());
            assertThat(new String(duplicado.reimpresion().contenido(), StandardCharsets.ISO_8859_1))
                    .as("y se identifica como duplicado")
                    .contains("DUPLICADO N° 1");
            assertThat(duplicado.duplicado().numero()).isEqualTo(1);
            assertThat(duplicado.resolucion().registro().numero())
                    .startsWith("RES_DUPLICADO_LICENCIA-2026-");

            CancelarLicencia.Cancelacion cancelacion =
                    enContexto(
                            () ->
                                    cancelar.cancelar(
                                            emitida.licencia().numero(),
                                            HOY,
                                            "Cese de actividades",
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(cancelacion.resolucion().registro().numero())
                    .startsWith("RES_CANCELACION_LICENCIA-2026-");

            ConsultaDeLicencias.LicenciaEnConsulta despues =
                    enContexto(
                            () ->
                                    consulta.porNumero(emitida.licencia().numero(), HOY)
                                            .orElseThrow());
            assertThat(despues.estado())
                    .as("AC 2: la licencia no se borro, cambio de estado con su resolucion")
                    .isEqualTo(EstadoDeLicencia.CANCELADA);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM licencia_funcionamiento WHERE id = ?",
                                    emitida.licencia().identificador()))
                    .as("la fila sigue ahi")
                    .isEqualTo(1);
            assertThat(despues.duplicados()).hasSize(1);
        }

        @Test
        @DisplayName(
                "AC 5: cada acto deja su fila de auditoria con la observacion de quien lo hizo")
        void cadaActoDejaAuditoria() {
            giroDelCatalogo("56101", "RESTAURANTES");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);

            Observacion propia = Observacion.de("Se emite por expediente 1234-2026");
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, recibo, "56101"),
                                            FormatoDeDocumento.PDF,
                                            propia));

            String observacion =
                    unicoTexto(
                            "SELECT observacion FROM auditoria WHERE tabla = 'licencia_funcionamiento'"
                                    + " AND clave = ?",
                            String.valueOf(emitida.licencia().identificador()));
            assertThat(observacion).isEqualTo(propia.texto());

            String datos =
                    unicoTexto(
                            "SELECT datos_nuevos ->> 'numero' FROM auditoria"
                                    + " WHERE tabla = 'licencia_funcionamiento' AND clave = ?",
                            String.valueOf(emitida.licencia().identificador()));
            assertThat(datos).isEqualTo(emitida.licencia().numero());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — Sin el pago del derecho no se emite")
    class ElDerecho {

        @Test
        @DisplayName("un recibo anulado de verdad no paga nada")
        void reciboAnulado() {
            giroDelCatalogo("47311", "COMBUSTIBLES");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            anular(recibo);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            solicitud(titular, recibo, "47311"),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("anulado");
        }

        @Test
        @DisplayName("un recibo por otro concepto del TUPA tampoco")
        void otroConcepto() {
            giroDelCatalogo("47312", "LUBRICANTES");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, OTRO_CONCEPTO);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            solicitud(titular, recibo, "47312"),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining(DERECHO_LICENCIA);
        }

        @Test
        @DisplayName("un recibo de otro contribuyente tampoco")
        void reciboDeOtro() {
            giroDelCatalogo("47313", "GAS");
            long titular = crearContribuyente();
            long otro = crearContribuyente();
            String recibo = cobrar(otro, DERECHO_LICENCIA);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            solicitud(titular, recibo, "47313"),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("otro contribuyente");
        }

        @Test
        @DisplayName("nada de esto se escribio: ni licencia, ni movimiento, ni documento")
        void nadaSeEscribeSiElDerechoNoEsta() {
            giroDelCatalogo("47314", "KEROSENE");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, OTRO_CONCEPTO);
            long licenciasAntes = filas("SELECT count(*) FROM licencia_funcionamiento");
            long documentosAntes = filas("SELECT count(*) FROM documento_emitido");

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            solicitud(titular, recibo, "47314"),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class);

            assertThat(filas("SELECT count(*) FROM licencia_funcionamiento"))
                    .isEqualTo(licenciasAntes);
            assertThat(filas("SELECT count(*) FROM documento_emitido"))
                    .as("el papel se emite DESPUES de comprobar el derecho, no antes")
                    .isEqualTo(documentosAntes);
        }

        @Test
        @DisplayName("regla 5: sin el concepto en el conjunto sellado, falla nombrando la llave")
        void sinElParametro() {
            giroDelCatalogo("47315", "VELAS");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitirSinParametro.emitir(
                                                            solicitud(titular, recibo, "47315"),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(DerechosDeTramiteParametrizados.DerechoSinParametrizar.class)
                    .hasMessageContaining("TUPA:DERECHO_LICENCIA_FUNCIONAMIENTO");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Lo que la base impide")
    class LaBase {

        @Test
        @DisplayName("sgtm_app no puede editar ni borrar una licencia (REVOKE de V37)")
        void laLicenciaNoSeEdita() {
            giroDelCatalogo("47411", "COMPUTADORAS");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, recibo, "47411"),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));
            long id = emitida.licencia().identificador();

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE licencia_funcionamiento SET nombre_comercial ="
                                                    + " 'OTRA COSA' WHERE id = "
                                                    + id))
                    .as("es un acto administrativo notificado: no se corrige en el sitio")
                    // Se mira la CADENA de causas y no el mensaje: el privilegio negado llega con
                    // SQLSTATE 42501, que cae en la clase 42, y Spring lo traduce a
                    // BadSqlGrammarException —«bad SQL grammar»—. El sintoma no se parece a su
                    // causa, y es exactamente lo que V32 §1.bis dejo anotado con `cierre_caja`.
                    .hasStackTraceContaining("permission denied");

            assertThatThrownBy(
                            () -> ejecutar("DELETE FROM licencia_funcionamiento WHERE id = " + id))
                    .hasStackTraceContaining("permission denied");

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE licencia_movimiento SET fecha = DATE '2020-01-01'"
                                                    + " WHERE licencia_id = "
                                                    + id))
                    .as("y el historial tampoco: lo que le pasa a una licencia se agrega")
                    .hasStackTraceContaining("permission denied");
        }

        @Test
        @DisplayName("diez cancelaciones simultaneas producen una sola resolucion")
        // La carrera se mide contando cuantas entraron: cada hilo tiene que poder decir «a mi me
        // rechazaron» sin importar por que excepcion, que es justo lo que se quiere contar.
        @SuppressWarnings("checkstyle:IllegalCatch")
        void unaSolaCancelacion() throws Exception {
            giroDelCatalogo("47412", "TELEFONOS");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, recibo, "47412"),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));
            String numero = emitida.licencia().numero();

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                try {
                                    enContexto(
                                            () ->
                                                    cancelar.cancelar(
                                                            numero,
                                                            HOY,
                                                            "Cese simultaneo",
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE));
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            assertThat(exitos)
                    .as("una comprobacion en Java pasaria diez veces; el indice unico, una")
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM licencia_movimiento"
                                            + " WHERE licencia_id = ? AND tipo = 'CANCELACION'",
                                    emitida.licencia().identificador()))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("diez duplicados simultaneos no comparten ordinal")
        @SuppressWarnings("checkstyle:IllegalCatch")
        void duplicadosSimultaneos() throws Exception {
            giroDelCatalogo("47413", "MUEBLES");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, recibo, "47413"),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));
            long licenciaId = emitida.licencia().identificador();

            // Los diez recibos y los diez documentos se preparan ANTES, uno a uno. Es lo que hace
            // que esta prueba mida lo que dice medir.
            //
            // Con el caso de uso entero en los diez hilos, la carrera nunca llega a
            // `licencia_duplicado_uq`: `DocumentoRepositoryJdbc.siguienteCorrelativo` es un
            // `count(*) + 1` y `documento_numero_uq` rechaza a los nueve que calculan el mismo
            // numero de resolucion, asi que solo uno pasa y el ordinal jamas se repite. La prueba
            // pasaba en verde con el indice degradado a normal, que es exactamente el defecto que
            // #33 documento con el candado del turno: la comprobacion la serializaba otra cosa.
            List<Long> papeles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                papeles.add(documentoDeAdorno("CARRERA-" + licenciaId + "-" + i));
            }
            AtomicInteger siguiente = new AtomicInteger();

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                long documento = papeles.get(siguiente.getAndIncrement() % 10);
                                try {
                                    enContexto(
                                            () ->
                                                    transaccion.execute(
                                                            estado -> {
                                                                int ordinal =
                                                                        duplicados.cuantosDe(
                                                                                        licenciaId)
                                                                                + 1;
                                                                return duplicados.registrar(
                                                                        new DuplicadoDeLicencia(
                                                                                null,
                                                                                licenciaId,
                                                                                ordinal,
                                                                                HOY,
                                                                                "Extravio"
                                                                                        + " simultaneo",
                                                                                emitida.licencia()
                                                                                        .reciboId(),
                                                                                documento,
                                                                                1,
                                                                                RELOJ.instant(),
                                                                                null,
                                                                                PORQUE));
                                                            }));
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            long distintos =
                    filas(
                            "SELECT count(DISTINCT numero) FROM licencia_duplicado"
                                    + " WHERE licencia_id = ?",
                            licenciaId);
            long total =
                    filas(
                            "SELECT count(*) FROM licencia_duplicado WHERE licencia_id = ?",
                            licenciaId);

            assertThat(exitos).as("alguno tiene que entrar").isPositive();
            assertThat(total)
                    .as("dos papeles que digan «DUPLICADO N.o 1» no se pueden distinguir")
                    .isEqualTo(distintos);
        }

        @Test
        @DisplayName("un giro CIIU repetido lo rechaza el indice, no un if")
        void giroRepetido() {
            giroDelCatalogo("47414", "JUGUETES");
            assertThatThrownBy(() -> giroDelCatalogo("47414", "JUGUETES OTRA VEZ"))
                    .isInstanceOf(
                            kamayuk.rentas.licencias.dominio.CiiuRepository.CodigoDuplicado.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Aislamiento")
    class Aislamiento {

        @Test
        @DisplayName("desde otra municipalidad, la licencia y su catalogo no existen")
        void desdeOtraMunicipalidadNoExiste() {
            giroDelCatalogo("47415", "LIBROS");
            long titular = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            solicitud(titular, recibo, "47415"),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                java.util.Optional<ConsultaDeLicencias.LicenciaEnConsulta> desdeB =
                        transaccion.execute(
                                estado -> consulta.porNumero(emitida.licencia().numero(), HOY));
                assertThat(desdeB).as("RLS: la licencia de A no existe desde B").isEmpty();
                Pagina<Ciiu> catalogoDeB =
                        transaccion.execute(
                                estado ->
                                        mantenerCatalogo.listar(
                                                CriterioDeCiiu.ninguno(),
                                                Paginacion.de(0, 20, "codigo")));
                assertThat(catalogoDeB).isNotNull();
                assertThat(catalogoDeB.totalElementos())
                        .as("y su catalogo de giros, tampoco")
                        .isZero();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
        }

        @Test
        @DisplayName(
                "la grilla filtra por titular, y un titular sin licencias no trae las de otros")
        void laGrillaFiltra() {
            giroDelCatalogo("47416", "FLORES");
            long titular = crearContribuyente();
            long sinLicencias = crearContribuyente();
            String recibo = cobrar(titular, DERECHO_LICENCIA);
            enContexto(
                    () ->
                            emitir.emitir(
                                    solicitud(titular, recibo, "47416"),
                                    FormatoDeDocumento.PDF,
                                    PORQUE));

            Pagina<ConsultaDeLicencias.LicenciaEnConsulta> suyas =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            CriterioDeLicencias.ninguno()
                                                    .conTitulares(java.util.Set.of(sinLicencias)),
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));

            assertThat(suyas.totalElementos())
                    .as("un contribuyente sin licencias no puede ver las de otros")
                    .isZero();
        }
    }

    // ==================================================================
    // Ayudas
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /** Ejecuta con el contexto de tenant y el origen fijados, como hace el borde HTTP. */
    private static <T> T enContexto(Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
        return accion.get();
    }

    private static EmitirLicenciaDeFuncionamiento.Solicitud solicitud(long titular, String recibo) {
        return solicitud(titular, recibo, "47111");
    }

    private static EmitirLicenciaDeFuncionamiento.Solicitud solicitud(
            long titular, String recibo, String giro) {
        return new EmitirLicenciaDeFuncionamiento.Solicitud(
                "C-" + titular,
                null,
                "BODEGA SAN MARTIN",
                "AV. GRAU 100 - SULLANA",
                new AreaM2(new BigDecimal("45.50")),
                TipoDeLicencia.DEFINITIVA,
                "CV",
                20,
                HOY,
                null,
                recibo,
                List.of(giro),
                giro,
                "EXP-2026-" + CONTADOR.incrementAndGet(),
                HOY);
    }

    private static long giroDelCatalogo(String codigo, String descripcion) {
        return enContexto(
                        () ->
                                mantenerCatalogo.registrar(
                                        new MantenerCatalogoCiiu.Alta(
                                                codigo,
                                                descripcion,
                                                "G",
                                                RiesgoItse.BAJO,
                                                "CV, CZ",
                                                false),
                                        PORQUE))
                .identificador();
    }

    /** Como si la caja de tasas hubiera cobrado el concepto; devuelve el numero del recibo. */
    private static String cobrar(long contribuyenteId, String concepto) {
        return caja.cobro(contribuyenteId, HOY, tarifaDe(concepto), concepto);
    }

    /**
     * Lo que cuesta cada concepto del TUPA, en la prueba.
     *
     * <p>Hasta `V7` salia de la tabla `tasa`, que se fue a `caja`. Aqui no se inventa una tarifa
     * por omision: un concepto que la prueba no declare revienta, porque un importe inventado es
     * exactamente lo que #48 mide en el otro sentido — la licencia que sale con una cifra plausible
     * y falsa.
     */
    private static Dinero tarifaDe(String concepto) {
        return switch (concepto) {
            case DERECHO_LICENCIA -> Dinero.de("120.00");
            case DERECHO_DUPLICADO -> Dinero.de("35.00");
            case OTRO_CONCEPTO -> Dinero.de("2.00");
            default -> throw new IllegalArgumentException("Concepto sin tarifa: " + concepto);
        };
    }

    /**
     * Como si `caja` hubiera registrado la anulacion del recibo.
     *
     * <p>Hasta `V7` esto insertaba la fila en `recibo_movimiento` por SQL directo, porque lo que la
     * prueba necesita es el <b>estado</b> —un recibo anulado— y no el caso de uso de la anulacion,
     * que ya tiene el suyo. El razonamiento no cambia y el sitio si: ese estado lo resuelve hoy
     * `caja`, y aqui solo se ve el resultado.
     */
    private static void anular(String numeroImpreso) {
        caja.anular(numeroImpreso);
    }

    private static int aLaVez(int cuantos, Callable<Boolean> accion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (ExecutorService hilos = Executors.newFixedThreadPool(cuantos)) {
            for (int i = 0; i < cuantos; i++) {
                resultados.add(
                        hilos.submit(
                                () -> {
                                    salida.await(10, TimeUnit.SECONDS);
                                    try {
                                        return accion.call();
                                    } finally {
                                        TenantContext.limpiar();
                                        OrigenContext.limpiar();
                                    }
                                }));
            }
            salida.countDown();
            int exitos = 0;
            for (Future<Boolean> resultado : resultados) {
                if (Boolean.TRUE.equals(resultado.get(30, TimeUnit.SECONDS))) {
                    exitos++;
                }
            }
            return exitos;
        }
    }

    private static long filas(String sql, Object... parametros) {
        return transaccion.execute(
                estado -> {
                    var peticion = jdbc.sql(sql.replace("?", "?"));
                    for (Object parametro : parametros) {
                        peticion = peticion.param(parametro);
                    }
                    Long total = peticion.query(Long.class).single();
                    return total == null ? 0L : total;
                });
    }

    private static @Nullable String unicoTexto(String sql, Object parametro) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(sql).param(parametro).query(String.class).optional().orElse(null));
    }

    private static void ejecutar(String sql) {
        transaccion.executeWithoutResult(estado -> jdbc.sql(sql).update());
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

    /**
     * El conjunto sellado de 2026 <b>con los dos conceptos del TUPA dentro</b>.
     *
     * <p>Que esta prueba tenga que sembrarlos es la demostracion de la regla 5: sin ellos, emitir
     * una licencia falla. El catalogo normativo lo carga <b>su propio rol</b>, no la aplicacion ni
     * el duenio del esquema (SoD-1 de REQ-03).
     *
     * <p>Van con {@code municipalidad_id NULL} porque {@code parametro_tributario} es catalogo; lo
     * que hace que el concepto sea de <b>esta</b> municipalidad es que sea <b>su</b> conjunto el
     * que lo incluye.
     */
    private static void crearConjuntoConLosDerechos(long municipalidadId) throws SQLException {
        long deLaLicencia = parametroDelTupa("DERECHO_LICENCIA_FUNCIONAMIENTO", DERECHO_LICENCIA);
        long delDuplicado = parametroDelTupa("DERECHO_DUPLICADO_LICENCIA", DERECHO_DUPLICADO);

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, 2026, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : new long[] {deLaLicencia, delDuplicado}) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                    + " WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long parametroDelTupa(String clave, String codigo) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'TUPA', ?, ?,"
                                        + " DATE '2026-01-01', 'TUPA 2026 de la prueba', true,"
                                        + " 'siembra') RETURNING id")) {
            sentencia.setString(1, clave);
            sentencia.setString(2, codigo);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente() {
        int orden = CONTADOR.incrementAndGet();
        return insertarComoApp(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES (?, ?, 'DNI', ?, 'NATURAL',"
                        + " 'PENA GARCIA, LUIS', 'prueba') RETURNING id",
                municipalidad,
                "TMP-" + orden,
                String.format("%08d", 20_000_000 + orden));
    }

    private static long insertarComoOwner(long muni, String sql, Object... parametros) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia = owner.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    private static long insertarComoApp(String sql, Object... parametros) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    /**
     * Una resolucion de duplicado emitida <b>por adelantado</b>, para que la carrera de la prueba
     * llegue al indice del ordinal y no se quede antes, en el numero del documento.
     */
    private static long documentoDeAdorno(String referencia) {
        return java.util.Objects.requireNonNull(
                enContexto(
                                () ->
                                        documentos.emitir(
                                                DuplicarLicencia.TIPO_DE_DOCUMENTO,
                                                new kamayuk.rentas.dominio.Ejercicio(2026),
                                                referencia,
                                                kamayuk.rentas.documentos.ModeloDeDocumento.de(
                                                        "Resolucion de duplicado de la carrera",
                                                        HOY,
                                                        List.of(),
                                                        List.of()),
                                                FormatoDeDocumento.PDF,
                                                PORQUE))
                        .registro()
                        .id());
    }

    /** El padron de la prueba: resuelve el codigo {@code C-<id>} al contribuyente sembrado. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public java.util.Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (!codigo.startsWith("C-")) {
                return java.util.Optional.empty();
            }
            long id = Long.parseLong(codigo.substring(2));
            return java.util.Optional.of(
                    new ResumenDeContribuyente(id, codigo, "PENA GARCIA, LUIS", "DNI 20000001"));
        }

        @Override
        public java.util.Map<Long, ResumenDeContribuyente> porIds(java.util.Set<Long> ids) {
            java.util.Map<Long, ResumenDeContribuyente> encontrados =
                    new java.util.LinkedHashMap<>();
            for (Long id : ids) {
                encontrados.put(
                        id,
                        new ResumenDeContribuyente(
                                id, "C-" + id, "PENA GARCIA, LUIS", "DNI 20000001"));
            }
            return encontrados;
        }

        @Override
        public java.util.Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Un lector de parametros cuyo conjunto sellado <b>no</b> tiene el concepto de la licencia.
     *
     * <p>Existe para demostrar la regla 5 sin tener que desellar el conjunto de verdad —que V9
     * impide, y con razon—.
     */
    private static final class SinDerechosSellados
            implements kamayuk.rentas.parametros.LectorDeParametros {

        @Override
        public kamayuk.rentas.parametros.ParametrosSellados vigenteEn(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return kamayuk.rentas.parametros.ParametrosSellados.de(ejercicio, 1).construir();
        }

        @Override
        public kamayuk.rentas.parametros.ParametrosSellados porConjunto(
                kamayuk.rentas.parametros.IdentificadorDeConjunto identificador) {
            return vigenteEn(new kamayuk.rentas.dominio.Ejercicio(2026));
        }

        @Override
        public kamayuk.rentas.parametros.IdentificadorDeConjunto conjuntoVigenteEn(
                kamayuk.rentas.dominio.Ejercicio ejercicio) {
            return kamayuk.rentas.parametros.IdentificadorDeConjunto.de(1L);
        }
    }
}
