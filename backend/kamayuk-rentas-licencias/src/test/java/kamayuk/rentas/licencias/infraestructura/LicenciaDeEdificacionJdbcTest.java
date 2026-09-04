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
import java.util.Locale;
import java.util.Optional;
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
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.aplicacion.TablasDeValuacion;
import kamayuk.rentas.catastro.aplicacion.ValoresUnitariosPublicados;
import kamayuk.rentas.catastro.infraestructura.ValuacionRepositoryJdbc;
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
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.licencias.aplicacion.CompletarSeccionDelFue;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.LecturaDelFue;
import kamayuk.rentas.licencias.aplicacion.PresentarFue;
import kamayuk.rentas.licencias.aplicacion.RevalidarLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.ValorizacionDelFue;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstadoDelFue;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.ModalidadDeAprobacion;
import kamayuk.rentas.licencias.dominio.PartidaDeEdificacion;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeEdificacion;
import kamayuk.rentas.licencias.dominio.RepresentanteLegal;
import kamayuk.rentas.licencias.dominio.RevisionDelProyecto;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeObra;
import kamayuk.rentas.licencias.dominio.TipoDeProfesional;
import kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.aplicacion.RecibosDeTramiteTesoreria;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.infraestructura.CajaRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeReciboRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.ReciboRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.TurnoDeCajaRepositoryJdbc;
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
 * #48 — El Formulario Unico de Edificaciones contra PostgreSQL de verdad (V43), conectado como
 * {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El ciclo entero.</b> Expediente presentado, secciones completadas <b>por partes</b>,
 *       derecho cobrado en la caja de tasas de verdad, licencia emitida con su papel, ampliada y
 *       revalidada.
 *   <li><b>Que la valorizacion salga del cuadro de #17 y de ningun otro sitio</b> (AC 2). Las
 *       celdas se siembran en {@code valor_unitario_edificacion}, dentro de un conjunto que se
 *       sella; la valorizacion las lee por el puerto publico de {@code catastro}. Y con un
 *       ejercicio <b>sin</b> cuadro sellado, el papel imprime «—» y la ficha dice que falta: eso es
 *       lo que separa #48 de #197.
 *   <li><b>Que {@code sgtm_app} no pueda editar ni borrar un expediente ni sus secciones.</b> Es el
 *       {@code REVOKE} de V43, comprobado por SQL directo, que es como se salta cualquier
 *       comprobacion escrita en Java.
 *   <li><b>Que la base impida una segunda emision bajo concurrencia real.</b> Un doble que consulta
 *       antes de insertar pasa la prueba y falla en produccion: diez peticiones simultaneas pasan
 *       las diez por el {@code if}.
 *   <li><b>Que las secciones se versionen</b>: completar dos veces deja las dos, y la vigente es la
 *       ultima.
 *   <li><b>Que RLS aisle el expediente</b>: desde otra municipalidad no existe.
 * </ul>
 *
 * <p><b>Ninguna cifra normativa de esta prueba es real.</b> Los valores por metro cuadrado que se
 * siembran son inventados y estan a la vista; las celdas del cuadro las espera #197 (D-02a).
 */
@DisplayName("#48 — La licencia de edificacion contra PostgreSQL")
class LicenciaDeEdificacionJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final LocalDate SIN_CUADRO = LocalDate.of(2027, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.obras";
    private static final String CAJERO = "cajero.tasas";
    private static final String CAJA = "C-48";

    /** El concepto del TUPA que el conjunto sellado nombra como derecho de la licencia. */
    private static final String DERECHO_EDIFICACION = "LE-001";

    /** Y el de la revalidacion. */
    private static final String DERECHO_REVALIDACION = "LE-009";

    /** Un concepto del TUPA que existe pero no es el derecho de la licencia. */
    private static final String OTRO_CONCEPTO = "COPIAS-48";

    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    /** WinAnsiEncoding: la codificacion con que el renderizador de PDF escribe su texto. */
    private static final java.nio.charset.Charset WIN_ANSI =
            java.nio.charset.Charset.forName("windows-1252");

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;

    /**
     * Una municipalidad <b>recien implantada</b>: con padron y expedientes, y sin un solo conjunto
     * de parametros sellado. Es el estado de hoy de todas ellas con D-02a abierta, y lo que #569
     * necesita para que el lector de parametros <b>lance</b> de verdad.
     */
    private static long municipalidadRecienImplantada;

    private static long contribuyenteRecienImplantada;
    private static long areaId;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static FueRepositoryJdbc expedientes;
    private static MovimientoDeEdificacionRepositoryJdbc movimientos;

    private static CobrarTasa cobrarTasa;
    private static EmitirDocumento documentos;

    private static PresentarFue presentar;
    private static CompletarSeccionDelFue completar;
    private static EmitirLicenciaDeEdificacion emitir;
    private static EmitirLicenciaDeEdificacion emitirSinParametro;
    private static RevalidarLicenciaDeEdificacion revalidar;
    private static ConsultaDeFue consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240460", "Municipalidad de las obras");
        otraMunicipalidad = crearMunicipalidad("240461", "Municipalidad vecina de #48");
        municipalidadRecienImplantada =
                crearMunicipalidad("240462", "Municipalidad recien implantada de #569");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        expedientes = new FueRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeEdificacionRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        ReciboRepositoryJdbc repositorioDeRecibos = new ReciboRepositoryJdbc(jdbc);
        MovimientoDeReciboRepositoryJdbc movimientosDeRecibo =
                new MovimientoDeReciboRepositoryJdbc(jdbc);
        cobrarTasa =
                envolver(
                        new CobrarTasa(
                                envolver(
                                        new AbrirCaja(
                                                new CajaRepositoryJdbc(jdbc),
                                                new TurnoDeCajaRepositoryJdbc(jdbc),
                                                auditoria,
                                                RELOJ)),
                                new kamayuk.rentas.tesoreria.infraestructura.TasaRepositoryJdbc(
                                        jdbc),
                                repositorioDeRecibos,
                                auditoria,
                                RELOJ));
        RecibosDeTramite recibos =
                envolver(new RecibosDeTramiteTesoreria(repositorioDeRecibos, movimientosDeRecibo));

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

        kamayuk.rentas.parametros.LectorDeParametros parametros =
                envolver(
                        new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                new ParametrosRepositoryJdbc(jdbc)));
        DerechosDeTramiteParametrizados derechos = new DerechosDeTramiteParametrizados(parametros);

        // La valorizacion lee el cuadro por el puerto PUBLICO de catastro, no por su tabla: es lo
        // que el AC 2 pide y lo que Spring Modulith verifica.
        TablasDeValuacion tablas =
                envolver(
                        new TablasDeValuacion(
                                new ValuacionRepositoryJdbc(jdbc), parametros, auditoria, RELOJ));
        LectorDeValoresUnitarios cuadro = new ValoresUnitariosPublicados(tablas);
        ValorizacionDelFue valorizaciones = new ValorizacionDelFue(cuadro);

        presentar = envolver(new PresentarFue(expedientes, padron, auditoria, RELOJ));
        completar =
                envolver(new CompletarSeccionDelFue(expedientes, movimientos, auditoria, RELOJ));
        emitir =
                envolver(
                        new EmitirLicenciaDeEdificacion(
                                expedientes,
                                movimientos,
                                recibos,
                                padron,
                                derechos,
                                valorizaciones,
                                documentos,
                                PlantillaDeNumeroDeEdificacion.POR_OMISION,
                                auditoria,
                                RELOJ));
        // El mismo caso de uso, con un conjunto sellado que NO tiene el concepto del TUPA. Es la
        // demostracion de la regla 5: sin el dato, la operacion falla nombrando la llave, en vez
        // de admitir cualquier recibo.
        emitirSinParametro =
                envolver(
                        new EmitirLicenciaDeEdificacion(
                                expedientes,
                                movimientos,
                                recibos,
                                padron,
                                new DerechosDeTramiteParametrizados(new SinDerechosSellados()),
                                valorizaciones,
                                documentos,
                                PlantillaDeNumeroDeEdificacion.POR_OMISION,
                                auditoria,
                                RELOJ));
        revalidar =
                envolver(
                        new RevalidarLicenciaDeEdificacion(
                                expedientes,
                                movimientos,
                                recibos,
                                padron,
                                derechos,
                                documentos,
                                auditoria,
                                RELOJ));
        // Las DOS van envueltas, como en el contenedor: `envolver` usa
        // `AnnotationTransactionAttributeSource`, asi que OBEDECE a la anotacion y sobre
        // `ConsultaDeFue` —que no declara ninguna— no abre nada. Envolver solo `LecturaDelFue`
        // haria que la mutacion de #569 —devolverle el `@Transactional` al metodo que orquesta—
        // pasara en VERDE sin que nadie se enterara: la anotacion no se aplica a un objeto que
        // nadie proxia (la leccion de #430 con `ImportarCajas`).
        consulta =
                envolver(
                        new ConsultaDeFue(
                                envolver(new LecturaDelFue(expedientes, movimientos, padron)),
                                valorizaciones));

        // 2026 con cuadro de valores unitarios; 2027 con los conceptos del TUPA pero SIN cuadro.
        // El segundo es el que demuestra que sin cifra el sistema dice cual falta en vez de
        // inventar una (AC 2, D-02a).
        crearConjunto(municipalidad, 2026, true);
        crearConjunto(municipalidad, 2027, false);
        crearConjunto(otraMunicipalidad, 2026, false);
        // Y la tercera se queda SIN NINGUN conjunto: es lo unico que hace que
        // `LectorDeParametrosSellados` lance de verdad, dentro de su propia transaccion (#569).
        contribuyenteRecienImplantada =
                insertarComoAppEn(
                        municipalidadRecienImplantada,
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro) VALUES (?, 'C-569',"
                                + " 'DNI', '20569001', 'NATURAL', 'VILELA SOSA, ROSA',"
                                + " 'prueba') RETURNING id",
                        municipalidadRecienImplantada);

        areaId = crearArea(municipalidad, "A-48");
        crearCaja(municipalidad, CAJA, "R48", areaId);
        crearTasa(DERECHO_EDIFICACION, Dinero.de("350.00"));
        crearTasa(DERECHO_REVALIDACION, Dinero.de("120.00"));
        crearTasa(OTRO_CONCEPTO, Dinero.de("2.00"));
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
        OrigenContext.fijar(new Origen(USUARIO, "PC-OBRAS-01", "10.1.1.30"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — Las secciones se completan por partes, y sin ellas no se emite")
    class PorPartes {

        @Test
        @DisplayName("un expediente recien presentado esta EN_TRAMITE y le faltan las cinco")
        void reciennPresentado() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);

            ConsultaDeFue.FichaDelFue ficha =
                    enContexto(() -> consulta.porExpediente(expediente, HOY).orElseThrow());

            assertThat(ficha.fila().estado())
                    .as("V4 lo habria dicho VIGENTE desde el INSERT")
                    .isEqualTo(EstadoDelFue.EN_TRAMITE);
            assertThat(ficha.fila().numeroDeLicencia())
                    .as("presentar no numera ninguna licencia")
                    .isNull();
            assertThat(ficha.seccionesFaltantes())
                    .containsExactlyInAnyOrder(SeccionDelFue.values());
            assertThat(ficha.estaCompleto()).isFalse();
        }

        @Test
        @DisplayName("emitir con secciones a medias falla nombrando TODAS las que faltan")
        void faltanSecciones() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);
            String recibo = cobrar(DERECHO_EDIFICACION);

            enContexto(() -> completar.completarTerreno(expediente, terreno("A", "3"), PORQUE));

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirLicenciaDeEdificacion.SeccionesIncompletas.class)
                    .satisfies(
                            fallo ->
                                    assertThat(
                                                    ((EmitirLicenciaDeEdificacion
                                                                            .SeccionesIncompletas)
                                                                    fallo)
                                                            .faltantes())
                                            .as("las cuatro que faltan, no solo la primera")
                                            .containsExactlyInAnyOrder(
                                                    SeccionDelFue.PROYECTO,
                                                    SeccionDelFue.VALORIZACION,
                                                    SeccionDelFue.PROFESIONALES,
                                                    SeccionDelFue.DOCUMENTOS));

            assertThat(
                            filas(
                                    "SELECT count(*) FROM edificacion_movimiento WHERE fue_id ="
                                            + " (SELECT id FROM licencia_edificacion WHERE"
                                            + " expediente = ?)",
                                    expediente))
                    .as("una emision que falla no deja movimiento")
                    .isZero();
        }

        @Test
        @DisplayName("completar una seccion dos veces la VERSIONA: la anterior queda entera")
        void seVersiona() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);

            enContexto(() -> completar.completarTerreno(expediente, terreno("A", "3"), PORQUE));
            enContexto(() -> completar.completarTerreno(expediente, terreno("B", "12"), PORQUE));

            long id = identificadorDe(expediente);
            assertThat(filas("SELECT count(*) FROM edificacion_terreno WHERE fue_id = ?", id))
                    .as("las dos versiones siguen ahi: la primera explica la observacion")
                    .isEqualTo(2);

            ConsultaDeFue.FichaDelFue ficha =
                    enContexto(() -> consulta.porExpediente(expediente, HOY).orElseThrow());
            assertThat(ficha.terreno()).isNotNull();
            assertThat(ficha.terreno().version()).isEqualTo(2);
            assertThat(ficha.terreno().manzana()).isEqualTo("B");
        }

        @Test
        @DisplayName("una vez emitida, la seccion ya no se completa")
        void yaEmitidaNoSeCompleta() {
            String expediente = expedienteCompleto(HOY);
            emitirLicencia(expediente, HOY);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    completar.completarProyecto(
                                                            expediente, proyecto(), PORQUE)))
                    .isInstanceOf(CompletarSeccionDelFue.ExpedienteYaEmitido.class);
        }

        @Test
        @DisplayName("un anteproyecto en consulta no llega a licencia")
        void anteproyectoNoSeEmite() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.ANTEPROYECTO_EN_CONSULTA, null, HOY);
            completarTodo(municipalidad, expediente);
            String recibo = cobrar(DERECHO_EDIFICACION);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirLicenciaDeEdificacion.TramiteQueNoOtorgaLicencia.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 2 — La valorizacion sale del cuadro de #17, o no sale")
    class Valorizacion {

        @Test
        @DisplayName("con el cuadro sellado, la licencia lleva su valor de obra calculado")
        void conCuadro() {
            String expediente = expedienteCompleto(HOY);
            EmitirLicenciaDeEdificacion.LicenciaEmitida emitida = emitirLicencia(expediente, HOY);

            assertThat(emitida.valorizacion().estaDisponible()).isTrue();
            // 40 m2 de MUROS categoria A a 120,00 + 40 m2 de TECHOS categoria B a 80,00.
            // Las dos cifras las siembra esta prueba y estan a la vista; las reales, #197.
            assertThat(emitida.valorizacion().obra().orElseThrow().total().valor())
                    .isEqualByComparingTo(new BigDecimal("8000.00"));
            assertThat(new String(emitida.documento().contenido(), StandardCharsets.ISO_8859_1))
                    .as("y el papel la imprime")
                    .contains("8000.00");
        }

        @Test
        @DisplayName("sin cuadro sellado, la licencia se emite igual y el papel imprime «—»")
        void sinCuadro() {
            // Ejercicio 2027: su conjunto tiene los conceptos del TUPA pero NINGUNA celda del
            // cuadro de valores unitarios, que es la situacion real mientras D-02a siga abierta.
            String expediente = expedienteCompleto(SIN_CUADRO);
            EmitirLicenciaDeEdificacion.LicenciaEmitida emitida =
                    emitirLicencia(expediente, SIN_CUADRO);

            assertThat(emitida.valorizacion().estaDisponible())
                    .as("no hay cifra, y no se inventa ninguna")
                    .isFalse();
            assertThat(emitida.valorizacion().motivo()).contains("#197");

            // El PDF se codifica en WinAnsiEncoding (CP-1252), no en ISO-8859-1: la raya larga
            // es el byte 0x97 y leerla como latin-1 la convierte en un carater de control
            // invisible. Leerla mal hacia parecer que el papel salia SIN la raya, que es justo el
            // defecto que esta prueba existe para impedir.
            String papel = new String(emitida.documento().contenido(), WIN_ANSI);
            assertThat(papel)
                    .as("donde iria la cifra va una raya, no un cero")
                    .contains(ValorizacionDelFue.Resultado.SIN_CIFRA);
            assertThat(papel)
                    .as("y el motivo, para que nadie lo lea como «la obra no vale nada»")
                    .contains("Valor de obra no valorizado");
            assertThat(emitida.numeroDeLicencia())
                    .as("la estructura del FUE no espera a ninguna cifra: la licencia sale")
                    .startsWith("LE-2027-");
        }

        @Test
        @DisplayName("la ficha dice que llave falta cuando el cuadro esta incompleto")
        void llaveQueFalta() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);
            enContexto(() -> completar.completarTerreno(expediente, terreno("C", "1"), PORQUE));
            enContexto(() -> completar.completarProyecto(expediente, proyecto(), PORQUE));
            // PUERTAS categoria I no esta sembrada en el cuadro de 2026.
            enContexto(
                    () ->
                            completar.completarValorizacion(
                                    expediente,
                                    List.of(
                                            new CompletarSeccionDelFue.Estructura(
                                                    1,
                                                    PartidaDeEdificacion.PUERTAS,
                                                    'I',
                                                    new AreaM2(new BigDecimal("10.00")))),
                                    PORQUE));

            ConsultaDeFue.FichaDelFue ficha =
                    enContexto(() -> consulta.porExpediente(expediente, HOY).orElseThrow());

            assertThat(ficha.valorizacion().estaDisponible()).isFalse();
            assertThat(ficha.valorizacion().llaveQueFalta()).isEqualTo("PUERTAS:I");
            assertThat(ficha.valorizacion().motivo()).contains("PUERTAS:I");
        }

        @Test
        @DisplayName("la cifra no esta en ninguna columna: la tabla no tiene donde guardarla")
        void ningunaColumnaDeValorDeObra() {
            // V4 tenia `valor_obra dinero NOT NULL`. V43 la retira, y esta prueba es lo que
            // impide que alguien la devuelva: con la columna de vuelta, la misma cifra viviria en
            // el cuadro de #17 y en la fila, y el dia que difieran nadie sabria cual mando.
            assertThat(
                            filas(
                                    "SELECT count(*) FROM information_schema.columns"
                                            + " WHERE table_name = 'licencia_edificacion'"
                                            + "   AND column_name IN"
                                            + "       ('valor_obra','estado','numero',"
                                            + "        'vigencia_hasta','revalidacion_hasta')"))
                    .as("las cinco columnas que mentirian estan retiradas (V43)")
                    .isZero();
            assertThat(
                            filas(
                                    "SELECT count(*) FROM information_schema.columns"
                                            + " WHERE table_name IN ('edificacion_proyecto',"
                                            + "        'edificacion_estructura')"
                                            + "   AND data_type = 'numeric'"
                                            + "   AND domain_name = 'dinero'"))
                    .as("ninguna tabla de la valorizacion tiene una columna de dinero")
                    .isZero();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 3 y AC 4 — La ampliacion referencia y la revalidacion no sustituye")
    class AmpliacionYRevalidacion {

        @Test
        @DisplayName("AC 3: la ampliacion tiene su numero y su vigencia; la original no se toca")
        void laAmpliacionNoSustituye() {
            String original = expedienteCompleto(HOY);
            EmitirLicenciaDeEdificacion.LicenciaEmitida primera = emitirLicencia(original, HOY);
            long originalId = identificadorDe(original);

            String ampliacion =
                    presentarFue(
                            TipoDeTramiteDeEdificacion.AMPLIACION_DE_LICENCIA,
                            primera.numeroDeLicencia(),
                            HOY);
            completarTodo(municipalidad, ampliacion);
            EmitirLicenciaDeEdificacion.LicenciaEmitida segunda = emitirLicencia(ampliacion, HOY);

            assertThat(segunda.numeroDeLicencia())
                    .as("la ampliacion es una licencia nueva, con su propio numero")
                    .isNotEqualTo(primera.numeroDeLicencia());
            assertThat(segunda.fue().licenciaOrigenId())
                    .as("y referencia la original")
                    .isEqualTo(originalId);

            ConsultaDeFue.FichaDelFue laOriginal =
                    enContexto(() -> consulta.porExpediente(original, HOY).orElseThrow());
            assertThat(laOriginal.fila().numeroDeLicencia())
                    .as("la original conserva su numero")
                    .isEqualTo(primera.numeroDeLicencia());
            assertThat(laOriginal.vigencias())
                    .as("y su unica vigencia: la ampliacion no le agrega ni le quita nada")
                    .hasSize(1);
            assertThat(laOriginal.fila().estado()).isEqualTo(EstadoDelFue.VIGENTE);
        }

        @Test
        @DisplayName("AC 4: la revalidacion deja las DOS vigencias, cada una con su acto")
        void lasDosVigenciasTrazables() {
            String original = expedienteCompleto(HOY);
            EmitirLicenciaDeEdificacion.LicenciaEmitida primera = emitirLicencia(original, HOY);
            long originalId = identificadorDe(original);
            LocalDate finDelPrimerTramo = primera.vigencia().hasta();

            String tramite =
                    presentarFue(
                            TipoDeTramiteDeEdificacion.REVALIDACION_DE_LICENCIA,
                            primera.numeroDeLicencia(),
                            HOY);
            String recibo = cobrar(DERECHO_REVALIDACION);
            RevalidarLicenciaDeEdificacion.Revalidacion revalidacion =
                    enContexto(
                            () ->
                                    revalidar.revalidar(
                                            tramite,
                                            HOY,
                                            finDelPrimerTramo.plusMonths(12),
                                            recibo,
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(revalidacion.numeroDeLicencia())
                    .as("la revalidacion NO numera otra licencia: es la misma")
                    .isEqualTo(primera.numeroDeLicencia());

            List<VigenciaDeLaLicencia> vigencias =
                    enContexto(() -> transaccion.execute(e -> movimientos.vigenciasDe(originalId)));

            assertThat(vigencias).hasSize(2);
            assertThat(vigencias.get(0).orden()).isEqualTo(1);
            assertThat(vigencias.get(0).hasta())
                    .as("el primer tramo queda intacto: no se piso")
                    .isEqualTo(finDelPrimerTramo);
            assertThat(vigencias.get(1).orden()).isEqualTo(2);
            assertThat(vigencias.get(1).desde())
                    .as("el segundo empieza al dia siguiente: dos tramos no se solapan")
                    .isEqualTo(finDelPrimerTramo.plusDays(1));
            assertThat(vigencias.get(0).movimientoId())
                    .as("y cada tramo nombra el acto que lo concedio: son actos distintos")
                    .isNotEqualTo(vigencias.get(1).movimientoId());

            // La trazabilidad se lee tambien en la resolucion: la tabla de vigencias del papel.
            assertThat(
                            new String(
                                    revalidacion.resolucion().contenido(),
                                    StandardCharsets.ISO_8859_1))
                    .contains(finDelPrimerTramo.toString())
                    .contains(finDelPrimerTramo.plusMonths(12).toString());
        }

        @Test
        @DisplayName("una prorroga que no pasa del tramo anterior no prorroga nada")
        void prorrogaQueNoProrroga() {
            String original = expedienteCompleto(HOY);
            EmitirLicenciaDeEdificacion.LicenciaEmitida primera = emitirLicencia(original, HOY);
            String tramite =
                    presentarFue(
                            TipoDeTramiteDeEdificacion.REVALIDACION_DE_LICENCIA,
                            primera.numeroDeLicencia(),
                            HOY);
            String recibo = cobrar(DERECHO_REVALIDACION);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    revalidar.revalidar(
                                                            tramite,
                                                            HOY,
                                                            primera.vigencia().hasta().minusDays(1),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(RevalidarLicenciaDeEdificacion.ProrrogaQueNoProrroga.class);
        }

        @Test
        @DisplayName("una ampliacion que nombra una licencia inexistente no se presenta")
        void ampliacionSinOriginal() {
            assertThatThrownBy(
                            () ->
                                    presentarFue(
                                            TipoDeTramiteDeEdificacion.AMPLIACION_DE_LICENCIA,
                                            "LE-2026-999999",
                                            HOY))
                    .isInstanceOf(PresentarFue.LicenciaOriginalInexistente.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 5 — Sin el derecho pagado en caja no se emite")
    class Derecho {

        @Test
        @DisplayName("un recibo de otro concepto del TUPA no respalda el derecho")
        void otroConcepto() {
            String expediente = expedienteCompleto(HOY);
            String recibo = cobrar(OTRO_CONCEPTO);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining(DERECHO_EDIFICACION);
        }

        @Test
        @DisplayName("un recibo anulado devolvio la plata y no paga nada")
        void reciboAnulado() {
            String expediente = expedienteCompleto(HOY);
            String recibo = cobrar(DERECHO_EDIFICACION);
            anular(recibo);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitir.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("anulado");
        }

        @Test
        @DisplayName("sin el concepto en el conjunto sellado, la emision falla nombrando la llave")
        void sinParametro() {
            String expediente = expedienteCompleto(HOY);
            String recibo = cobrar(DERECHO_EDIFICACION);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitirSinParametro.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            recibo,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .as("sin el dato, la operacion falla: no se admite «cualquier concepto»")
                    .isInstanceOf(DerechosDeTramiteParametrizados.DerechoSinParametrizar.class)
                    .satisfies(
                            fallo ->
                                    assertThat(
                                                    ((DerechosDeTramiteParametrizados
                                                                            .DerechoSinParametrizar)
                                                                    fallo)
                                                            .llave())
                                            .contains("TUPA:DERECHO_LICENCIA_EDIFICACION"));
        }

        @Test
        @DisplayName("la emision deja su fila de auditoria con la observacion de quien la hizo")
        void auditoria() {
            String expediente = expedienteCompleto(HOY);
            Observacion propia = Observacion.de("Se otorga por expediente de la prueba");
            String recibo = cobrar(DERECHO_EDIFICACION);
            EmitirLicenciaDeEdificacion.LicenciaEmitida emitida =
                    enContexto(
                            () ->
                                    emitir.emitir(
                                            expediente,
                                            HOY,
                                            HOY.plusMonths(36),
                                            recibo,
                                            FormatoDeDocumento.PDF,
                                            propia));

            assertThat(
                            unicoTexto(
                                    "SELECT observacion FROM auditoria WHERE tabla ="
                                            + " 'edificacion_movimiento' AND clave = :clave",
                                    String.valueOf(emitida.emision().identificador())))
                    .isEqualTo(propia.texto());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Lo que decide la base, y no un if")
    class LaBase {

        @Test
        @DisplayName("sgtm_app no puede editar ni borrar un expediente ni sus secciones")
        void sinUpdateNiDelete() {
            String expediente = expedienteCompleto(HOY);
            long id = identificadorDe(expediente);

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE licencia_edificacion SET expediente = 'X'"
                                                    + " WHERE id = "
                                                    + id))
                    .as("V43 §11: REVOKE UPDATE")
                    .hasStackTraceContaining("permission denied");

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE edificacion_terreno SET direccion = 'X'"
                                                    + " WHERE fue_id = "
                                                    + id))
                    .as("las secciones se versionan; nunca se editan")
                    .hasStackTraceContaining("permission denied");

            assertThatThrownBy(() -> ejecutar("DELETE FROM licencia_edificacion WHERE id = " + id))
                    .hasStackTraceContaining("permission denied");

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE edificacion_vigencia SET hasta = '2099-12-31'"
                                                    + " WHERE licencia_id = "
                                                    + id))
                    .as("pisar la vigencia original es exactamente lo que el AC 4 prohibe")
                    .hasStackTraceContaining("permission denied");
        }

        @Test
        @DisplayName("diez emisiones simultaneas del mismo expediente dejan UNA licencia")
        @SuppressWarnings("checkstyle:IllegalCatch")
        void unaSolaEmision() throws Exception {
            String expediente = expedienteCompleto(HOY);
            List<String> recibos = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                recibos.add(cobrar(DERECHO_EDIFICACION));
            }
            long id = identificadorDe(expediente);

            AtomicInteger siguiente = new AtomicInteger();
            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                OrigenContext.fijar(
                                        new Origen(USUARIO, "PC-OBRAS-01", "10.1.1.30"));
                                try {
                                    emitir.emitir(
                                            expediente,
                                            HOY,
                                            HOY.plusMonths(36),
                                            recibos.get(siguiente.getAndIncrement()),
                                            FormatoDeDocumento.PDF,
                                            PORQUE);
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            assertThat(exitos).as("alguna tiene que entrar").isPositive();
            assertThat(
                            filas(
                                    "SELECT count(*) FROM edificacion_movimiento"
                                            + " WHERE fue_id = ? AND tipo = 'EMISION'",
                                    id))
                    .as("dos licencias para la misma obra no se pueden distinguir en el cartel")
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM edificacion_vigencia WHERE licencia_id"
                                            + " = ?",
                                    id))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("dos expedientes con el mismo numero los rechaza el indice")
        void expedienteRepetido() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);
            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    presentar.presentar(
                                                            solicitud(
                                                                    expediente,
                                                                    TipoDeTramiteDeEdificacion
                                                                            .LICENCIA_DE_OBRA,
                                                                    null,
                                                                    HOY,
                                                                    "C-" + contribuyente()),
                                                            PORQUE)))
                    .isInstanceOf(
                            kamayuk.rentas.licencias.dominio.FueRepository.ExpedienteDuplicado
                                    .class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Consulta y aislamiento")
    class ConsultaYAislamiento {

        @Test
        @DisplayName("la grilla filtra por manzana del terreno vigente, por rango y no por LIKE")
        void filtraPorManzana() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);
            enContexto(() -> completar.completarTerreno(expediente, terreno("ZZ9", "44"), PORQUE));

            Pagina<ConsultaDeFue.FueEnConsulta> encontrados =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            new CriterioDeFue(
                                                    null, null, "ZZ9", null, null, null, null, null,
                                                    null),
                                            null,
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "expediente")));

            assertThat(encontrados.contenido())
                    .extracting(fila -> fila.fue().expediente())
                    .contains(expediente);
        }

        @Test
        @DisplayName("filtrar por un solicitante sin expedientes no devuelve los de otros")
        void solicitanteSinExpedientes() {
            presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);

            Pagina<ConsultaDeFue.FueEnConsulta> suyos =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            CriterioDeFue.ninguno()
                                                    .conTitulares(java.util.Set.of(999_999L)),
                                            null,
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "expediente")));

            assertThat(suyos.totalElementos()).isZero();
        }

        @Test
        @DisplayName("desde otra municipalidad, el expediente no existe")
        void rlsAisla() {
            String expediente = expedienteCompleto(HOY);
            emitirLicencia(expediente, HOY);

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                Optional<ConsultaDeFue.FichaDelFue> desdeB =
                        transaccion.execute(estado -> consulta.porExpediente(expediente, HOY));
                assertThat(desdeB).as("RLS: el expediente de A no existe desde B").isEmpty();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
        }

        @Test
        @DisplayName("el reporte general trae el area a construir y el valor de obra con su fecha")
        void reporte() {
            String expediente = expedienteCompleto(HOY);
            emitirLicencia(expediente, HOY);

            Pagina<ConsultaDeFue.FilaDelReporte> hoja =
                    enContexto(
                            () ->
                                    consulta.reporte(
                                            new CriterioDeFue(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    HOY.minusDays(1),
                                                    HOY,
                                                    null),
                                            null,
                                            null,
                                            HOY,
                                            Paginacion.de(0, 50, "expediente")));

            assertThat(hoja.contenido())
                    .filteredOn(fila -> fila.fila().fue().expediente().equals(expediente))
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.proyecto()).isNotNull();
                                assertThat(fila.valorizacion()).isNotNull();
                                assertThat(fila.valorizacion().estaDisponible()).isTrue();
                                assertThat(fila.fila().aLaFecha())
                                        .as("toda cifra dice de que dia es (regla 9)")
                                        .isEqualTo(HOY);
                            });
        }
    }

    @Nested
    @DisplayName("#569 — El reporte general en una municipalidad sin conjunto sellado")
    class ReporteSinConjuntoSellado {

        @Test
        @DisplayName("AC 1 y AC 5: con dos expedientes y sin conjunto sellado, el reporte responde")
        void dosFilasSinConjuntoSellado() {
            String uno = expedienteRecienImplantado();
            String otro = expedienteRecienImplantado();

            Pagina<ConsultaDeFue.FilaDelReporte> hoja = reporteRecienImplantado();

            assertThat(hoja.contenido())
                    .extracting(fila -> fila.fila().fue().expediente())
                    .as("el reporte devuelve las dos filas en vez de reventar al confirmar")
                    .contains(uno, otro);
        }

        @Test
        @DisplayName("AC 2 y AC 3: la fila sale sin cifra, y el motivo nombra el ejercicio")
        void sinCifraYConMotivo() {
            String expediente = expedienteRecienImplantado();

            Pagina<ConsultaDeFue.FilaDelReporte> hoja = reporteRecienImplantado();

            assertThat(hoja.contenido())
                    .filteredOn(fila -> fila.fila().fue().expediente().equals(expediente))
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.valorizacion()).isNotNull();
                                assertThat(fila.valorizacion().estaDisponible())
                                        .as("ni cero ni inventada: la cifra no esta (AC 2)")
                                        .isFalse();
                                assertThat(fila.valorizacion().valorizacion()).isNull();
                                assertThat(fila.valorizacion().motivo())
                                        .as("y dice por que, nombrando el ejercicio (AC 3)")
                                        .contains("2026");
                            });
        }

        @Test
        @DisplayName("AC 3: con el conjunto sellado incompleto, la fila nombra la llave que falta")
        void nombraLaLlaveQueFalta() {
            String expediente =
                    presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, HOY);
            enContexto(() -> completar.completarTerreno(expediente, terreno("D", "7"), PORQUE));
            enContexto(() -> completar.completarProyecto(expediente, proyecto(), PORQUE));
            // PUERTAS categoria I no esta sembrada en el cuadro de 2026.
            enContexto(
                    () ->
                            completar.completarValorizacion(
                                    expediente,
                                    List.of(
                                            new CompletarSeccionDelFue.Estructura(
                                                    1,
                                                    PartidaDeEdificacion.PUERTAS,
                                                    'I',
                                                    new AreaM2(new BigDecimal("10.00")))),
                                    PORQUE));

            Pagina<ConsultaDeFue.FilaDelReporte> hoja =
                    enContexto(() -> reporteDe(municipalidad, HOY));

            assertThat(hoja.contenido())
                    .filteredOn(fila -> fila.fila().fue().expediente().equals(expediente))
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.valorizacion()).isNotNull();
                                assertThat(fila.valorizacion().estaDisponible()).isFalse();
                                assertThat(fila.valorizacion().llaveQueFalta())
                                        .isEqualTo("PUERTAS:I");
                                assertThat(fila.valorizacion().motivo()).contains("PUERTAS:I");
                            });
        }

        @Test
        @DisplayName("AC 4: la fila que SI se puede valorizar trae su cifra con su fecha de corte")
        void laQueSiSeValorizaTraeSuCifra() {
            String expediente = expedienteCompleto(HOY);

            Pagina<ConsultaDeFue.FilaDelReporte> hoja =
                    enContexto(() -> reporteDe(municipalidad, HOY));

            assertThat(hoja.contenido())
                    .filteredOn(fila -> fila.fila().fue().expediente().equals(expediente))
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.valorizacion()).isNotNull();
                                assertThat(fila.valorizacion().estaDisponible()).isTrue();
                                assertThat(fila.valorizacion().motivo()).isNull();
                                assertThat(fila.fila().aLaFecha())
                                        .as("toda cifra dice de que dia es (regla 9, RNF-075)")
                                        .isEqualTo(HOY);
                            });
        }

        @Test
        @DisplayName("AC 6: un rango sin ninguna fila sigue devolviendo la pagina vacia")
        void rangoSinFilas() {
            expedienteRecienImplantado();

            Pagina<ConsultaDeFue.FilaDelReporte> hoja =
                    enContextoDe(
                            municipalidadRecienImplantada,
                            () ->
                                    consulta.reporte(
                                            new CriterioDeFue(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    LocalDate.of(2019, 1, 1),
                                                    LocalDate.of(2020, 1, 1),
                                                    null),
                                            null,
                                            null,
                                            LocalDate.of(2020, 1, 1),
                                            Paginacion.de(0, 50, "expediente")));

            assertThat(hoja.contenido()).isEmpty();
            assertThat(hoja.totalElementos()).isZero();
        }

        @Test
        @DisplayName("AC 7: la ficha del mismo expediente tampoco revienta sin conjunto sellado")
        void laFichaTampocoRevienta() {
            String expediente = expedienteRecienImplantado();

            ConsultaDeFue.FichaDelFue ficha =
                    enContextoDe(
                            municipalidadRecienImplantada,
                            () -> consulta.porExpediente(expediente, HOY).orElseThrow());

            assertThat(ficha.valorizacion().estaDisponible()).isFalse();
            assertThat(ficha.valorizacion().motivo()).contains("2026");
        }

        @Test
        @DisplayName("AC 7: la emision sin conjunto sellado FALLA, y falla nombrando el ejercicio")
        void laEmisionFallaEnVozAlta() {
            // El otro anfitrion del modulo que llama a la valorizacion es `EmitirLicenciaDeEdifi-
            // cacion`, y SI abre transaccion —tiene que abrirla: escribe—. No tiene el defecto de
            // #569 porque nadie le captura nada: pide el concepto del TUPA ANTES de valorizar, y
            // sin conjunto sellado esa lectura LANZA y la excepcion sale entera. La transaccion se
            // deshace, que es lo correcto, y quien pregunta recibe el motivo en vez de un
            // «rollback-only» que no dice nada.
            String expediente = expedienteRecienImplantado();

            assertThatThrownBy(
                            () ->
                                    enContextoDe(
                                            municipalidadRecienImplantada,
                                            () ->
                                                    emitir.emitir(
                                                            expediente,
                                                            HOY,
                                                            HOY.plusMonths(36),
                                                            "R48-0000001",
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(
                            kamayuk.rentas.parametros.LectorDeParametros.EjercicioSinSellar.class)
                    .hasMessageContaining("2026");
        }

        private static String expedienteRecienImplantado() {
            return expedienteCompletoEn(
                    municipalidadRecienImplantada, contribuyenteRecienImplantada, HOY);
        }

        private static Pagina<ConsultaDeFue.FilaDelReporte> reporteRecienImplantado() {
            return enContextoDe(
                    municipalidadRecienImplantada,
                    () -> reporteDe(municipalidadRecienImplantada, HOY));
        }

        private static Pagina<ConsultaDeFue.FilaDelReporte> reporteDe(long muni, LocalDate corte) {
            return consulta.reporte(
                    new CriterioDeFue(
                            null, null, null, null, null, null, corte.minusDays(1), corte, null),
                    null,
                    null,
                    corte,
                    Paginacion.de(0, 50, "expediente"));
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
        return enContextoDe(municipalidad, accion);
    }

    /** Lo mismo, en la municipalidad que se le diga: lo pide #569, que siembra en una tercera. */
    private static <T> T enContextoDe(long muni, Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(muni));
        OrigenContext.fijar(new Origen(USUARIO, "PC-OBRAS-01", "10.1.1.30"));
        return accion.get();
    }

    private static String presentarFue(
            TipoDeTramiteDeEdificacion tramite, String licenciaAnterior, LocalDate fecha) {
        String expediente = "EXP-48-" + CONTADOR.incrementAndGet();
        enContexto(
                () ->
                        presentar.presentar(
                                solicitud(
                                        expediente,
                                        tramite,
                                        licenciaAnterior,
                                        fecha,
                                        "C-" + contribuyente()),
                                PORQUE));
        return expediente;
    }

    /** Presenta un FUE en la municipalidad y a nombre del contribuyente que se le digan (#569). */
    private static String presentarFueEn(long muni, long contribuyenteId, LocalDate fecha) {
        String expediente = "EXP-569-" + CONTADOR.incrementAndGet();
        enContextoDe(
                muni,
                () ->
                        presentar.presentar(
                                solicitud(
                                        expediente,
                                        TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA,
                                        null,
                                        fecha,
                                        "C-" + contribuyenteId),
                                PORQUE));
        return expediente;
    }

    private static PresentarFue.Solicitud solicitud(
            String expediente,
            TipoDeTramiteDeEdificacion tramite,
            String licenciaAnterior,
            LocalDate fecha,
            String codigoContribuyente) {
        return new PresentarFue.Solicitud(
                expediente,
                fecha,
                codigoContribuyente,
                null,
                tramite,
                TipoDeObra.EDIFICACION_NUEVA,
                ModalidadDeAprobacion.B,
                RevisionDelProyecto.REVISORES_URBANOS,
                null,
                licenciaAnterior,
                true,
                new RepresentanteLegal("40404040", "TORRES, ANA", "P-11223", null));
    }

    /** El expediente con las cinco secciones completadas y listo para emitir. */
    private static String expedienteCompleto(LocalDate fecha) {
        String expediente = presentarFue(TipoDeTramiteDeEdificacion.LICENCIA_DE_OBRA, null, fecha);
        completarTodo(municipalidad, expediente);
        return expediente;
    }

    /** Lo mismo, en la municipalidad que se le diga y a nombre de su contribuyente (#569). */
    private static String expedienteCompletoEn(long muni, long contribuyenteId, LocalDate fecha) {
        String expediente = presentarFueEn(muni, contribuyenteId, fecha);
        completarTodo(muni, expediente);
        return expediente;
    }

    private static void completarTodo(long muni, String expediente) {
        enContextoDe(muni, () -> completar.completarTerreno(expediente, terreno("A", "3"), PORQUE));
        enContextoDe(muni, () -> completar.completarProyecto(expediente, proyecto(), PORQUE));
        enContextoDe(
                muni,
                () ->
                        completar.completarValorizacion(
                                expediente,
                                List.of(
                                        new CompletarSeccionDelFue.Estructura(
                                                1,
                                                PartidaDeEdificacion.MUROS,
                                                'A',
                                                new AreaM2(new BigDecimal("40.00"))),
                                        new CompletarSeccionDelFue.Estructura(
                                                1,
                                                PartidaDeEdificacion.TECHOS,
                                                'B',
                                                new AreaM2(new BigDecimal("40.00")))),
                                PORQUE));
        enContextoDe(
                muni,
                () ->
                        completar.completarProfesionales(
                                expediente,
                                List.of(
                                        new CompletarSeccionDelFue.Profesional(
                                                TipoDeProfesional.PROYECTISTA_ARQUITECTURA,
                                                "QUISPE, MARIA",
                                                "CAP",
                                                "12345"),
                                        new CompletarSeccionDelFue.Profesional(
                                                TipoDeProfesional.RESPONSABLE_OBRA,
                                                "ROJAS, JULIO",
                                                "CIP",
                                                "67890")),
                                PORQUE));
        enContextoDe(
                muni,
                () ->
                        completar.completarDocumentos(
                                expediente,
                                List.of(
                                        new CompletarSeccionDelFue.Requisito(
                                                "FUE FIRMADO POR EL SOLICITANTE", true, 2),
                                        new CompletarSeccionDelFue.Requisito(
                                                "PLANOS DE ARQUITECTURA", true, 5)),
                                PORQUE));
    }

    private static EmitirLicenciaDeEdificacion.LicenciaEmitida emitirLicencia(
            String expediente, LocalDate fecha) {
        String recibo = cobrar(DERECHO_EDIFICACION);
        return enContexto(
                () ->
                        emitir.emitir(
                                expediente,
                                fecha,
                                fecha.plusMonths(36),
                                recibo,
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static CompletarSeccionDelFue.Terreno terreno(String manzana, String lote) {
        return new CompletarSeccionDelFue.Terreno(
                null,
                "AV. LOS ALGARROBOS 450",
                manzana,
                lote,
                new AreaM2(new BigDecimal("200.00")),
                "RDM",
                "P-99887",
                Medida.enMetrosLineales("10.00"),
                Medida.enMetrosLineales("20.00"));
    }

    private static CompletarSeccionDelFue.Proyecto proyecto() {
        return new CompletarSeccionDelFue.Proyecto(
                "VIVIENDA UNIFAMILIAR",
                2,
                new AreaM2(new BigDecimal("160.00")),
                new AreaM2(new BigDecimal("40.00")),
                1,
                12);
    }

    /** Cobra el concepto en la caja de tasas <b>de verdad</b> y devuelve el numero del recibo. */
    private static String cobrar(String concepto) {
        Recibo recibo =
                enContexto(
                        () ->
                                cobrarTasa.cobrar(
                                        new CobrarTasa.CobroDeTasas(
                                                CAJA,
                                                CAJERO,
                                                contribuyente(),
                                                List.of(new LineaDeTasaPedida(concepto, 1)),
                                                FormaDePago.EFECTIVO,
                                                HOY,
                                                null),
                                        Observacion.de("Cobro del derecho de tramite")));
        return recibo.numero().impreso();
    }

    /**
     * Anula el recibo escribiendo su movimiento como lo hace {@code AnularRecibo}.
     *
     * <p>Va por SQL directo a proposito: lo que esta prueba necesita es el <b>estado</b> —un recibo
     * anulado— y no el caso de uso de la anulacion, que ya tiene el suyo en #34.
     */
    private static void anular(String numeroImpreso) {
        int guion = numeroImpreso.lastIndexOf('-');
        String serie = numeroImpreso.substring(0, guion);
        long correlativo = Long.parseLong(numeroImpreso.substring(guion + 1));
        transaccion.executeWithoutResult(
                estado ->
                        jdbc.sql(
                                        "INSERT INTO recibo_movimiento (municipalidad_id, recibo_id,"
                                                + " tipo, fecha, caja_id, turno_id, motivo, importe,"
                                                + " usuario_registro, observacion)"
                                                + " SELECT r.municipalidad_id, r.id, 'ANULACION',"
                                                + " :fecha, r.caja_id, r.turno_id, 'Anulada en la"
                                                + " prueba', r.total, 'prueba', 'Se anula para la"
                                                + " prueba' FROM recibo r WHERE r.serie = :serie AND"
                                                + " r.numero = :numero")
                                .param("fecha", HOY)
                                .param("serie", serie)
                                .param("numero", correlativo)
                                .update());
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
                if (Boolean.TRUE.equals(resultado.get(60, TimeUnit.SECONDS))) {
                    exitos++;
                }
            }
            return exitos;
        }
    }

    private static long identificadorDe(String expediente) {
        return enContexto(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                expedientes
                                                        .porExpediente(expediente)
                                                        .map(FueDeEdificacion::identificador)
                                                        .orElseThrow()))
                .longValue();
    }

    private static long filas(String sql, Object... parametros) {
        return transaccion.execute(
                estado -> {
                    var peticion = jdbc.sql(sql);
                    for (Object parametro : parametros) {
                        peticion = peticion.param(parametro);
                    }
                    Long total = peticion.query(Long.class).single();
                    return total == null ? 0L : total;
                });
    }

    private static String unicoTexto(String sql, Object parametro) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(sql)
                                .param("clave", parametro)
                                .query(String.class)
                                .optional()
                                .orElse(""));
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
     * Un conjunto sellado del ejercicio, con los conceptos del TUPA y —opcionalmente— el cuadro de
     * valores unitarios.
     *
     * <p>Que la prueba tenga que sembrarlos es la demostracion de la regla 5: sin ellos, ni se
     * emite ni se valoriza. Y que el de 2027 vaya <b>sin</b> cuadro es lo que permite comprobar el
     * caso real de hoy: la estructura del FUE funciona y la cifra dice que falta (#197, D-02a).
     */
    private static void crearConjunto(long municipalidadId, int ejercicio, boolean conCuadro)
            throws SQLException {
        long deLaLicencia =
                parametroDelTupa(
                        "DERECHO_LICENCIA_EDIFICACION_" + municipalidadId + "_" + ejercicio,
                        "DERECHO_LICENCIA_EDIFICACION",
                        DERECHO_EDIFICACION);
        long deLaRevalidacion =
                parametroDelTupa(
                        "DERECHO_REVALIDACION_EDIFICACION_" + municipalidadId + "_" + ejercicio,
                        "DERECHO_REVALIDACION_EDIFICACION",
                        DERECHO_REVALIDACION);

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, ejercicio);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : new long[] {deLaLicencia, deLaRevalidacion}) {
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
            if (conCuadro) {
                // El cuadro de valores unitarios es NACIONAL desde V55 (D-13, ADR-0017): no
                // pertenece a este conjunto, el conjunto lo COMPONE. Se publica como edicion con
                // rol_carga_parametros y se nombra aqui, igual que los dos conceptos del TUPA.
                // Cifras INVENTADAS para la prueba, y a la vista. Las reales las espera #197.
                long edicion = publicarCuadroDeValoresUnitarios(municipalidadId + "_" + ejercicio);
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, edicion);
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

    /**
     * Publica una edicion del cuadro de valores unitarios y devuelve su identificador.
     *
     * <p>Va por {@code rol_carga_parametros} porque desde V55 es la unica credencial que puede
     * escribir esa tabla: es un catalogo nacional, no una tabla de esta municipalidad (D-13,
     * ADR-0017). La edicion se deja <b>abierta</b>: cerrarla no hace falta aqui, y el disparador de
     * V55 rechazaria las celdas si se cerrara antes.
     */
    private static long publicarCuadroDeValoresUnitarios(String sufijo) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES (NULL, 'CUADRO_VALORES_UNITARIOS', ?,"
                                    + " 'Cuadro inventado para la prueba de #48', DATE '2026-01-01',"
                                    + " 'Cuadro inventado para la prueba de #48', 'siembra',"
                                    + " 'otra persona') RETURNING id")) {
                sentencia.setString(1, sufijo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    edicion = resultado.getLong(1);
                }
            }
            sembrarCelda(carga, edicion, "MUROS", "A", "120.000000");
            sembrarCelda(carga, edicion, "TECHOS", "B", "80.000000");
            sembrarCelda(carga, edicion, "PUERTAS", "C", "40.000000");
            carga.commit();
            return edicion;
        }
    }

    private static void sembrarCelda(
            Connection carga, long edicion, String partida, String categoria, String valorM2)
            throws SQLException {
        try (PreparedStatement sentencia =
                carga.prepareStatement(
                        "INSERT INTO valor_unitario_de_prueba (publicacion_id, partida,"
                                + " categoria, anio_construccion_desde, anio_construccion_hasta,"
                                + " valor_m2, documento_fuente)"
                                + " VALUES (?, ?, ?, 1990, NULL, ?, 'Cuadro inventado para la"
                                + " prueba de #48')")) {
            sentencia.setLong(1, edicion);
            sentencia.setString(2, partida);
            sentencia.setString(3, categoria);
            sentencia.setBigDecimal(4, new BigDecimal(valorM2));
            sentencia.executeUpdate();
        }
    }

    private static long parametroDelTupa(String sufijo, String clave, String codigo)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'TUPA', ?, ?,"
                                        + " DATE '2026-01-01', ?, true, 'siembra') RETURNING id")) {
            sentencia.setString(1, clave);
            sentencia.setString(2, codigo);
            sentencia.setString(3, "TUPA de la prueba " + sufijo);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    /** El unico contribuyente de la prueba, creado una vez. */
    private static long contribuyente() {
        if (contribuyenteId == 0) {
            contribuyenteId =
                    insertarComoApp(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro) VALUES (?, 'C-48',"
                                    + " 'DNI', '20480001', 'NATURAL', 'TORRES DIAZ, MARIO',"
                                    + " 'prueba') RETURNING id",
                            municipalidad);
        }
        return contribuyenteId;
    }

    private static long contribuyenteId;

    private static long crearArea(long muni, String codigo) {
        return insertarComoOwner(
                muni,
                "INSERT INTO area (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Gerencia de Desarrollo Urbano') RETURNING id",
                muni,
                codigo);
    }

    private static void crearCaja(long muni, String codigo, String serie, long area) {
        insertarComoOwner(
                muni,
                "INSERT INTO caja (municipalidad_id, codigo, nombre, area_id, serie)"
                        + " VALUES (?, ?, 'Caja de tasas de la prueba', ?, ?) RETURNING id",
                muni,
                codigo,
                area,
                serie);
    }

    private static void crearTasa(String codigo, Dinero importe) {
        insertarComoOwner(
                municipalidad,
                "INSERT INTO tasa (municipalidad_id, codigo, descripcion, area_id,"
                        + " partida_presupuestal, importe, vigencia_desde, documento_fuente)"
                        + " VALUES (?, ?, 'Concepto del TUPA', ?, '1.3.1.1.1.1', ?,"
                        + " DATE '2026-01-01', 'TUPA de la prueba') RETURNING id",
                municipalidad,
                codigo,
                areaId,
                importe.valor());
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
        return insertarComoAppEn(municipalidad, sql, parametros);
    }

    private static long insertarComoAppEn(long muni, String sql, Object... parametros) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
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
     * Un lector de parametros cuyo conjunto sellado <b>no</b> tiene el concepto de la edificacion.
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

    /** El padron de la prueba: resuelve el codigo {@code C-<id>} al contribuyente sembrado. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (!codigo.toUpperCase(Locale.ROOT).startsWith("C-")) {
                return Optional.empty();
            }
            long id = Long.parseLong(codigo.substring(2));
            return Optional.of(
                    new ResumenDeContribuyente(id, codigo, "TORRES DIAZ, MARIO", "DNI 20480001"));
        }

        @Override
        public java.util.Map<Long, ResumenDeContribuyente> porIds(java.util.Set<Long> ids) {
            java.util.Map<Long, ResumenDeContribuyente> encontrados =
                    new java.util.LinkedHashMap<>();
            for (Long id : ids) {
                encontrados.put(
                        id,
                        new ResumenDeContribuyente(
                                id, "C-" + id, "TORRES DIAZ, MARIO", "DNI 20480001"));
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }
}
