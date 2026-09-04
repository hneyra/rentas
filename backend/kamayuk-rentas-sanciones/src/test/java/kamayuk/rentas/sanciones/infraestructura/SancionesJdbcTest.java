package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeActosDeLaPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeInternamientos;
import kamayuk.rentas.sanciones.aplicacion.LiberarVehiculoInternado;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.aplicacion.RegistrarInternamiento;
import kamayuk.rentas.sanciones.aplicacion.RegistrarPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.AcuseDelActo;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.aplicacion.CobrosDeTasasTesoreria;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeReciboRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.ReciboRepositoryJdbc;
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
 * #50 — Descargos, internamiento vehicular y resoluciones de gerencia contra PostgreSQL de verdad
 * (V41), conectado como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>AC 1 — un descargo procedente no borra la papeleta.</b> La fila sigue ahí y lo que
 *       cambia es el <b>libro</b>: la baja se asienta con su motivo, y {@code deudaActualizadaA}
 *       vuelve a dar cero sin que nadie haya escrito esa cifra en ningún sitio. Contra un doble
 *       esto solo probaría que el doble recuerda lo que se le dijo.
 *   <li><b>AC 2 — no hay sancionadora sin ordinaria notificada y sin plazo vencido.</b> Las tres
 *       condiciones por separado, y la última <b>además por SQL directo</b>: {@code
 *       resolucion_gerencia_plazo_ck} es lo que queda cuando alguien se salta el caso de uso.
 *   <li><b>AC 3 — la liberación exige el pago de la custodia, verificado contra {@code
 *       tesoreria}.</b> Con un recibo que no existe, con uno anulado, con uno que cobró otro
 *       concepto, y con el bueno. La casilla del prototipo la marca quien entrega el vehículo; el
 *       recibo lo dice la caja.
 *   <li><b>AC 4 — todos los documentos emitidos por una papeleta, con su fecha y su acuse.</b>
 *       Resolución, acta de ingreso y acta de liberación en una sola secuencia, y los dos intentos
 *       de notificación, no solo el que encontró a alguien.
 *   <li><b>AC 5 — cada acto deja auditoría.</b> Se cuentan las filas de {@code auditoria}.
 *   <li><b>Que dos peticiones simultáneas no dicten dos ordinarias.</b> Un doble que consulta antes
 *       de insertar pasa la prueba y falla en producción: diez peticiones a la vez pasan las diez
 *       por el {@code if}. Aquí se lanzan diez hilos.
 *   <li><b>Que {@code sgtm_app} no pueda editar una resolución ni un internamiento.</b> Es el
 *       {@code REVOKE} de V41, y se comprueba intentándolo.
 *   <li><b>Que RLS aísle</b>: desde otra municipalidad, la resolución no existe.
 * </ul>
 */
@DisplayName("#50 — Descargos, internamiento y resoluciones de gerencia contra PostgreSQL")
class SancionesJdbcTest {

    /** El día de la infracción: miércoles 4 de marzo de 2026. */
    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);

    /**
     * Hasta cuándo se admite el descargo, con el plazo <b>parametrizado</b> de 5 días hábiles.
     *
     * <p>La cuenta, día a día: la papeleta es del miércoles 4; el cómputo empieza el jueves 5 (día
     * hábil siguiente); cinco días hábiles desde ahí son 6, 9, 10, 11 y 12. Está escrito aquí
     * porque una prueba que recalculara la fecha con el mismo código que verifica no verificaría
     * nada.
     */
    private static final LocalDate DESCARGO_HASTA = LocalDate.of(2026, 3, 12);

    /** El día en que se dicta la resolución ordinaria: miércoles 1 de abril. */
    private static final LocalDate ORDINARIA = LocalDate.of(2026, 4, 1);

    /** El día en que se diligencia la ordinaria: jueves 2 de abril. */
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 4, 2);

    /**
     * Desde cuándo cabe la sancionadora, con el plazo <b>parametrizado</b> de 7 días hábiles.
     *
     * <p>La diligencia es el jueves 2; surte efecto el viernes 3; siete días hábiles desde ahí son
     * 6, 7, 8, 9, 10, 13 y 14; el plazo vence el martes 14 y se puede sancionar desde el
     * <b>miércoles 15</b>.
     */
    private static final LocalDate SANCIONADORA_DESDE = LocalDate.of(2026, 4, 15);

    private static final Dinero MULTA = Dinero.de("428.00");
    private static final Dinero CUSTODIA = Dinero.de("198.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long conjuntoId;
    private static long areaId;
    private static long cajaId;
    private static long turnoId;
    private static long tasaDeCustodiaId;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static PapeletaRepositoryJdbc papeletas;
    private static CodigoInfraccionRepositoryJdbc codigos;
    private static DescargoRepositoryJdbc descargos;
    private static ResolucionDeGerenciaRepositoryJdbc resoluciones;
    private static NotificacionDeResolucionRepositoryJdbc diligencias;
    private static InternamientoRepositoryJdbc internamientos;
    private static ReciboRepositoryJdbc recibos;
    private static MovimientoDeReciboRepositoryJdbc movimientosDeRecibo;

    private static RegistrarPapeleta registrarPapeleta;
    private static RegistrarDescargo registrarDescargo;
    private static ResolverConResolucionDeGerencia resolver;
    private static NotificarResolucionDeGerencia notificar;
    private static RegistrarInternamiento internar;
    private static LiberarVehiculoInternado liberar;
    private static ConsultaDeInternamientos consultaDeDeposito;
    private static ConsultaDeActosDeLaPapeleta consultaDeActos;
    private static ConsultaDeDeudaPublica deudas;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250701", "Municipalidad de sanciones");
        otraMunicipalidad = crearMunicipalidad("250702", "Municipalidad vecina de #50");
        conjuntoId = crearConjuntoConLosPlazos(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        papeletas = new PapeletaRepositoryJdbc(jdbc);
        codigos = new CodigoInfraccionRepositoryJdbc(jdbc);
        descargos = new DescargoRepositoryJdbc(jdbc);
        resoluciones = new ResolucionDeGerenciaRepositoryJdbc(jdbc);
        diligencias = new NotificacionDeResolucionRepositoryJdbc(jdbc);
        internamientos = new InternamientoRepositoryJdbc(jdbc);
        recibos = new ReciboRepositoryJdbc(jdbc);
        movimientosDeRecibo = new MovimientoDeReciboRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        RegistrarAsiento registrarAsiento =
                new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        GeneradorDeCargos cargos = envolver(new GeneradorDeCargosCuentaCorriente(registrarAsiento));
        deudas =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));
        ExtincionDeDeuda extincion =
                envolver(
                        new ExtincionDeDeudaCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));

        // El tercer colaborador entra con #54: el agregado de recaudacion por concepto, que esta
        // prueba no ejercita —lo hace el resumen anual de licencias— pero que el constructor pide.
        CobrosDeTasas cobros =
                envolver(
                        new CobrosDeTasasTesoreria(
                                recibos,
                                movimientosDeRecibo,
                                new kamayuk.rentas.tesoreria.infraestructura
                                        .RecaudacionRepositoryJdbc(jdbc)));

        EmitirDocumento documentos =
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

        PlazosDeSancionesParametrizados plazos =
                new PlazosDeSancionesParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new kamayuk.rentas.parametros.infraestructura
                                                .ParametrosRepositoryJdbc(jdbc))));

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();

        registrarPapeleta = envolver(new RegistrarPapeleta(papeletas, codigos, cargos, auditoria));
        registrarDescargo =
                envolver(new RegistrarDescargo(papeletas, descargos, plazos, auditoria, RELOJ));
        resolver =
                envolver(
                        new ResolverConResolucionDeGerencia(
                                papeletas,
                                descargos,
                                resoluciones,
                                diligencias,
                                padron,
                                deudas,
                                extincion,
                                plazos,
                                documentos,
                                auditoria,
                                RELOJ));
        notificar =
                envolver(
                        new NotificarResolucionDeGerencia(
                                resoluciones, diligencias, papeletas, padron, plazos, auditoria));
        internar =
                envolver(
                        new RegistrarInternamiento(
                                internamientos, papeletas, documentos, auditoria, RELOJ));
        liberar =
                envolver(
                        new LiberarVehiculoInternado(
                                internamientos, papeletas, cobros, documentos, auditoria, RELOJ));
        consultaDeDeposito = envolver(new ConsultaDeInternamientos(internamientos));
        consultaDeActos =
                envolver(
                        new ConsultaDeActosDeLaPapeleta(
                                papeletas, resoluciones, diligencias, internamientos, descargos));

        areaId = crearArea();
        cajaId = crearCaja();
        turnoId = crearTurno();
        tasaDeCustodiaId = crearTasa("CUSTODIA", CUSTODIA);
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
        OrigenContext.fijar(new Origen("inspector.transito", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — el descargo fundado no borra la papeleta: asienta la baja")
    class ElDescargoFundado {

        @Test
        @DisplayName(
                "presentado en plazo, declarado fundado, la papeleta sigue y la deuda queda en"
                        + " cero")
        void elDescargoFundadoDejaLaMultaSinEfecto() {
            Papeleta papeleta = papeletaDeTransito("A01");
            assertThat(deudaDe(papeleta, ORDINARIA)).isEqualTo(MULTA);

            RegistrarDescargo.Registrado registrado =
                    enTransaccion(
                            () ->
                                    registrarDescargo.registrar(
                                            Familia.TRANSITO,
                                            papeleta.numero(),
                                            new RegistrarDescargo.Peticion(
                                                    "EXP-A01",
                                                    INFRACCION.plusDays(2),
                                                    TipoDeRecurso.DESCARGO,
                                                    "El vehiculo estaba en el taller"),
                                            PORQUE),
                            "mesa.partes");

            assertThat(registrado.descargo().presentadoHasta())
                    .as(
                            "los cinco dias habiles salen del conjunto sellado, no de un 5"
                                    + " compilado (regla 5)")
                    .isEqualTo(DESCARGO_HASTA);
            assertThat(registrado.descargo().conjuntoId())
                    .as("y queda dicho de que conjunto salieron (ARQ-09 §3)")
                    .isEqualTo(conjuntoId);
            assertThat(registrado.descargo().enPlazo()).isTrue();
            assertThat(registrado.plazo().toString()).isEqualTo("5 DIAS_HABILES");

            ResolverConResolucionDeGerencia.ResolucionDictada dictada =
                    dictar(
                            papeleta,
                            TipoDeResolucionDeGerencia.ORDINARIA,
                            ORDINARIA,
                            "EXP-A01",
                            SentidoDelFallo.FUNDADO,
                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);

            assertThat(dictada.baja())
                    .as("la baja se asienta, no se edita la papeleta")
                    .isNotNull();
            assertThat(dictada.baja().importe()).isEqualTo(MULTA);
            assertThat(dictada.baja().asientos())
                    .as("un abono por cada parte del desglose con importe")
                    .isEqualTo(1);

            assertThat(
                            enTransaccion(
                                    () -> papeletas.porNumero(Familia.TRANSITO, papeleta.numero())))
                    .as("la papeleta NO se borra (regla 4, RNF-051): sigue ahi con su desglose")
                    .get()
                    .extracting(Papeleta::importeAPagar)
                    .isEqualTo(MULTA);

            assertThat(deudaDe(papeleta, ORDINARIA))
                    .as("y lo que cambia es el libro: a la fecha de la resolucion ya no debe nada")
                    .isEqualTo(Dinero.CERO);

            assertThat(motivoDelUltimoAbono(papeleta))
                    .as("el motivo del asiento es la observacion de quien resolvio (regla 10)")
                    .isEqualTo(PORQUE.texto());

            // Y la otra mitad de la misma fila (#684): el motivo es el RELATO de quien firma
            // y la causal es el SUSTENTO del acto, que aqui lo decide este caso de uso y no
            // quien atiende. Sin esta asercion nada sujeta cual de las seis se declara: una
            // resolucion que deja la multa sin efecto se asentaria como PRESCRIPCION_DECLARADA
            // —o como ERROR_MATERIAL— y la relacion de RF-045 la contaria bajo otra causal,
            // con el importe y el papel correctos.
            assertThat(causalDelUltimoAbono(papeleta))
                    .as("la causal de la baja la declara el acto que la produce, no el operador")
                    .isEqualTo(CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO.name());
        }

        @Test
        @DisplayName("un descargo tardio se registra igual, diciendo que llego fuera de plazo")
        void elDescargoTardioSeRegistraFueraDePlazo() {
            Papeleta papeleta = papeletaDeTransito("A02");

            RegistrarDescargo.Registrado registrado =
                    enTransaccion(
                            () ->
                                    registrarDescargo.registrar(
                                            Familia.TRANSITO,
                                            papeleta.numero(),
                                            new RegistrarDescargo.Peticion(
                                                    "EXP-A02",
                                                    DESCARGO_HASTA.plusDays(1),
                                                    TipoDeRecurso.RECONSIDERACION,
                                                    "Presentado tarde a proposito"),
                                            PORQUE),
                            "mesa.partes");

            assertThat(registrado.descargo().enPlazo())
                    .as(
                            "lo que corresponde es declararlo improcedente, y para eso hay que"
                                    + " poder registrarlo")
                    .isFalse();
        }

        @Test
        @DisplayName("la base impide que la fila mienta sobre si llego en plazo")
        void laBaseImpideQueLaFilaMientaSobreElPlazo() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("A03");
            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO descargo (municipalidad_id, papeleta_id,"
                                                    + " numero_expediente, fecha, tipo_recurso,"
                                                    + " sustento, presentado_hasta, conjunto_id,"
                                                    + " en_plazo, fecha_registro, usuario_registro,"
                                                    + " observacion) VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + papeleta.identificador()
                                                    + ", 'EXP-A03-SQL', DATE '2026-03-20',"
                                                    + " 'DESCARGO', 'por sql directo', DATE"
                                                    + " '2026-03-12', "
                                                    + conjuntoId
                                                    + ", true, now(), 'sql', 'por sql')"));
            assertThat(estado)
                    .as(
                            "descargo_plazo_ck: un recurso tardio admitido como si hubiera llegado a"
                                    + " tiempo es lo que esta restriccion existe para impedir")
                    .isEqualTo("23514");
        }
    }

    @Nested
    @DisplayName("AC 2 — no hay sancionadora sin ordinaria notificada y sin plazo vencido")
    class LaSancionadora {

        @Test
        @DisplayName("sin ordinaria dictada, no procede")
        void sinOrdinariaNoProcede() {
            Papeleta papeleta = papeletaDeTransito("B01");

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinDictar.class)
                    .hasMessageContaining("no tiene resolucion de gerencia ordinaria");
        }

        @Test
        @DisplayName("con la ordinaria dictada pero sin notificar, tampoco")
        void sinNotificarTampoco() {
            Papeleta papeleta = papeletaDeTransito("B02");
            dictar(papeleta, TipoDeResolucionDeGerencia.ORDINARIA, ORDINARIA, null, null, null);

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinNotificar.class)
                    .hasMessageContaining("no esta notificada");
        }

        @Test
        @DisplayName("una diligencia no hallada no abre el plazo: se reintenta con otra fila")
        void unaDiligenciaNoHalladaNoAbreElPlazo() {
            Papeleta papeleta = papeletaDeTransito("B03");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            NotificarResolucionDeGerencia.Diligencia fallida =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);
            assertThat(fallida.notificacion().intento()).isEqualTo(1);
            assertThat(fallida.notificacion().exigibleDesde()).isNull();
            assertThat(fallida.abreElPlazoDeLaSancionadora()).isFalse();

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinNotificar.class);

            NotificarResolucionDeGerencia.Diligencia buena =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(buena.notificacion().intento())
                    .as("la anterior se queda donde estaba (notificacion_intento_uq, V28)")
                    .isEqualTo(2);
            assertThat(buena.notificacion().exigibleDesde())
                    .as("los siete dias habiles salen del conjunto sellado (regla 5)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(buena.notificacion().conjuntoId()).isEqualTo(conjuntoId);
            assertThat(buena.abreElPlazoDeLaSancionadora()).isTrue();

            assertThat(enTransaccion(() -> diligencias.deResolucion(ordinaria.identificador())))
                    .as("las dos diligencias quedan: la que no encontro a nadie tambien")
                    .hasSize(2);
        }

        @Test
        @DisplayName("notificada pero con el plazo corriendo, todavia no")
        void conElPlazoCorriendoTodaviaNo() {
            Papeleta papeleta = papeletaDeTransito("B04");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE.minusDays(1),
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.PlazoDeLaOrdinariaEnCurso.class)
                    .hasMessageContaining(SANCIONADORA_DESDE.toString());
        }

        @Test
        @DisplayName("vencido el plazo, la sancionadora copia su sustento y sale")
        void vencidoElPlazoLaSancionadoraSale() {
            Papeleta papeleta = papeletaDeTransito("B05");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            NotificarResolucionDeGerencia.Diligencia acuse =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            ResolucionDeGerencia sancionadora =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.SANCIONADORA,
                                    SANCIONADORA_DESDE,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            assertThat(sancionadora.ordinariaExigibleDesde())
                    .as("copia su sustento, no lo vuelve a resolver (patron de V28 y V34)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(sancionadora.ordinariaNotificacionId())
                    .isEqualTo(acuse.notificacion().identificador());
            assertThat(sancionadora.numero()).startsWith("RGS-2026-");
        }

        @Test
        @DisplayName("y la base lo impide aunque alguien se salte el caso de uso")
        void laBaseImpideLaSancionadoraPrematura() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("B06");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            NotificarResolucionDeGerencia.Diligencia acuse =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            long documento = documentoSuelto("RGS", "SQL-B06");
            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO resolucion_gerencia (municipalidad_id,"
                                                    + " papeleta_id, tipo, numero, documento_id, fecha,"
                                                    + " ordinaria_notificacion_id,"
                                                    + " ordinaria_exigible_desde, sustento,"
                                                    + " fecha_registro, usuario_registro, observacion)"
                                                    + " VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + papeleta.identificador()
                                                    + ", 'SANCIONADORA', 'SQL-B06', "
                                                    + documento
                                                    + ", DATE '"
                                                    + SANCIONADORA_DESDE.minusDays(1)
                                                    + "', "
                                                    + acuse.notificacion().identificador()
                                                    + ", DATE '"
                                                    + SANCIONADORA_DESDE
                                                    + "', 'por sql', now(), 'sql', 'por sql')"));

            assertThat(estado)
                    .as(
                            "resolucion_gerencia_plazo_ck: misma forma y mismo motivo que"
                                    + " acto_rec2_plazo_ck (V34)")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("y no hay dos ordinarias de la misma papeleta, ni con diez hilos a la vez")
        void noHayDosOrdinariasNiConDiezHilos() throws Exception {
            Papeleta papeleta = papeletaDeTransito("B07");
            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> intentos = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                intentos.add(
                        () -> {
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                dictar(
                                        papeleta,
                                        TipoDeResolucionDeGerencia.ORDINARIA,
                                        ORDINARIA,
                                        null,
                                        null,
                                        null);
                                return true;
                            } catch (ResolucionDeGerenciaRepository.ResolucionDuplicada
                                    | org.springframework.dao.DataAccessException rechazado) {
                                // Las dos formas en que pierde el que llega segundo: la traducida
                                // -resolucion_gerencia_ordinaria_uq- y la que pueda venir del
                                // motor si dos transacciones se pisan. Cualquier otra sube y rompe
                                // la prueba.
                                return false;
                            } finally {
                                TenantContext.limpiar();
                                OrigenContext.limpiar();
                            }
                        });
            }

            ExecutorService piscina = Executors.newFixedThreadPool(hilos);
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> intento : intentos) {
                    futuros.add(piscina.submit(intento));
                }
                salida.countDown();
                long dictadas = 0;
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(30, TimeUnit.SECONDS))) {
                        dictadas++;
                    }
                }
                assertThat(dictadas)
                        .as(
                                "resolucion_gerencia_ordinaria_uq: dos resoluciones del mismo tipo"
                                        + " sobre la misma multa se contradicen en el expediente")
                        .isEqualTo(1);
            } finally {
                piscina.shutdownNow();
            }

            assertThat(cuantasResoluciones(papeleta, "ORDINARIA")).isEqualTo(1);
        }

        @Test
        @DisplayName("un descargo se resuelve una vez, y la que sobra la rechaza el indice")
        void unDescargoSeResuelveUnaVez() {
            Papeleta papeleta = papeletaDeTransito("B08");
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-B08",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");

            dictar(
                    papeleta,
                    TipoDeResolucionDeGerencia.ORDINARIA,
                    ORDINARIA,
                    "EXP-B08",
                    SentidoDelFallo.INFUNDADO,
                    EfectoSobreLaMulta.SE_MANTIENE);

            Papeleta otra = papeletaDeTransito("B09");
            assertThatThrownBy(
                            () ->
                                    dictar(
                                            otra,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            ORDINARIA,
                                            "EXP-B08",
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO))
                    .as("y ademas ese recurso impugna otra papeleta")
                    .isInstanceOf(ResolverConResolucionDeGerencia.DescargoDeOtraPapeleta.class);
        }
    }

    @Nested
    @DisplayName("AC 3 — la liberacion exige la custodia pagada, verificada contra tesoreria")
    class ElDeposito {

        @Test
        @DisplayName("sin recibo que la caja acredite, el vehiculo no sale")
        void sinReciboElVehiculoNoSale() {
            Papeleta papeleta = papeletaDeTransito("C01");
            internarVehiculo(papeleta, "T2G-401");

            assertThatThrownBy(() -> liberarVehiculo("T2G-401", "001-9999999"))
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class)
                    .hasMessageContaining("no acredita el pago del concepto CUSTODIA");
        }

        @Test
        @DisplayName("con un recibo anulado tampoco: un recibo anulado ya no acredita nada")
        void conUnReciboAnuladoTampoco() {
            Papeleta papeleta = papeletaDeTransito("C02");
            internarVehiculo(papeleta, "T2G-402");
            String recibo = cobrarCustodia(papeleta.obligadoId());
            anular(recibo);

            assertThatThrownBy(() -> liberarVehiculo("T2G-402", recibo))
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class);
        }

        @Test
        @DisplayName("con un recibo que cobro otro concepto, tampoco")
        void conUnReciboDeOtroConceptoTampoco() {
            Papeleta papeleta = papeletaDeTransito("C03");
            internarVehiculo(papeleta, "T2G-403");
            long otra = crearTasa("DUPLICADO", Dinero.de("12.00"));
            String recibo =
                    cobrarTasa(papeleta.obligadoId(), "DUPLICADO", otra, Dinero.de("12.00"));

            assertThatThrownBy(() -> liberarVehiculo("T2G-403", recibo))
                    .as(
                            "acreditar cualquier recibo dejaria salir un vehiculo con el recibo del"
                                    + " derecho de tramite de otra cosa")
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class);
        }

        @Test
        @DisplayName("con la custodia cancelada sale, con su acta, y el estado se deriva")
        void conLaCustodiaCanceladaSale() {
            Papeleta papeleta = papeletaDeTransito("C04");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-404");
            assertThat(internado.internamiento().acta())
                    .as("el numero del acta ES el del documento emitido: no hay dos numeraciones")
                    .isEqualTo(internado.acta().registro().numero())
                    .startsWith("ACTA_INTERNAMIENTO-2026-");

            String recibo = cobrarCustodia(papeleta.obligadoId());
            LiberarVehiculoInternado.Liberado liberado = liberarVehiculo("T2G-404", recibo);

            assertThat(liberado.estado())
                    .as("el estado se DERIVA de los movimientos, no de una columna (V41 §5)")
                    .isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(liberado.custodia().importe()).isEqualTo(CUSTODIA);
            assertThat(liberado.movimiento().reciboCustodia()).isEqualTo(recibo);
            assertThat(new String(liberado.acta().contenido(), StandardCharsets.ISO_8859_1))
                    .as("el acta imprime el recibo con el que la caja acredito la custodia")
                    .contains(recibo);

            Pagina<InternamientoEnConsulta> grilla =
                    enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento("T2G-404", null, null),
                                            SANCIONADORA_DESDE,
                                            Paginacion.de(0, 20, "fechaIngreso")));
            assertThat(grilla.contenido()).hasSize(1);
            InternamientoEnConsulta fila = grilla.contenido().get(0);
            assertThat(fila.estado()).isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(fila.fechaSalida()).isEqualTo(ORDINARIA);
            assertThat(fila.calculadoA())
                    .as("los dias se cuentan a una fecha, y la fila la dice (regla 9, RNF-075)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(fila.dias())
                    .as("del 4 de marzo al 1 de abril: la salida corta la cuenta, no la consulta")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("y la base impide una liberacion sin recibo, sin quien retira ni sus dias")
        void laBaseImpideUnaLiberacionSinCustodia() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("C05");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-405");
            long documento = documentoSuelto("ACTA_LIBERACION", "SQL-C05");

            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO internamiento_movimiento"
                                                    + " (municipalidad_id, internamiento_id, tipo,"
                                                    + " fecha, acta, documento_id, fecha_registro,"
                                                    + " usuario_registro, observacion) VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + internado.internamiento().identificador()
                                                    + ", 'LIBERACION', DATE '2026-04-01',"
                                                    + " 'SQL-C05', "
                                                    + documento
                                                    + ", now(), 'sql', 'por sql')"));

            assertThat(estado)
                    .as(
                            "internamiento_liberacion_ck: la mitad de la guarda que un CHECK puede"
                                    + " expresar")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("un vehiculo no entra dos veces sin haber salido")
        void unVehiculoNoEntraDosVecesSinHaberSalido() {
            Papeleta papeleta = papeletaDeTransito("C06");
            internarVehiculo(papeleta, "T2G-406");

            assertThatThrownBy(() -> internarVehiculo(papeleta, "T2G-406"))
                    .isInstanceOf(RegistrarInternamiento.VehiculoYaInternado.class);
        }
    }

    @Nested
    @DisplayName("AC 4 y 5 — todos los documentos con su fecha y su acuse, y su auditoria")
    class ElExpedienteDeLaPapeleta {

        @Test
        @DisplayName("resolucion, acta de ingreso y acta de salida en una sola secuencia")
        void todosLosDocumentosEnUnaSolaSecuencia() {
            Papeleta papeleta = papeletaDeTransito("D01");
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-D01",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");
            internarVehiculo(papeleta, "T2G-501");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    "EXP-D01",
                                    SentidoDelFallo.INFUNDADO,
                                    EfectoSobreLaMulta.SE_MANTIENE)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);
            String recibo = cobrarCustodia(papeleta.obligadoId());
            liberarVehiculo("T2G-501", recibo);

            ConsultaDeActosDeLaPapeleta.Expediente expediente =
                    enTransaccion(() -> consultaDeActos.de(Familia.TRANSITO, papeleta.numero()));

            assertThat(expediente.descargos())
                    .extracting(Descargo::numeroExpediente)
                    .containsExactly("EXP-D01");
            assertThat(expediente.actos())
                    .as("la resolucion, el acta de ingreso y la de salida: los tres papeles")
                    .hasSize(3)
                    .extracting(ActoDeLaPapeleta::tipo)
                    .containsExactlyInAnyOrder("ORDINARIA", "INGRESO", "LIBERACION");
            assertThat(expediente.actos())
                    .allSatisfy(acto -> assertThat(acto.fecha()).isNotNull())
                    .allSatisfy(acto -> assertThat(acto.documentoId()).isPositive());

            ActoDeLaPapeleta resolucion =
                    expediente.actos().stream()
                            .filter(acto -> "ORDINARIA".equals(acto.tipo()))
                            .findFirst()
                            .orElseThrow();
            assertThat(resolucion.acuses())
                    .as("los DOS intentos, no solo el que encontro a alguien")
                    .hasSize(2)
                    .extracting(AcuseDelActo::resultado)
                    .containsExactly(
                            ResultadoDeNotificacion.NO_UBICADO, ResultadoDeNotificacion.NOTIFICADO);
            assertThat(resolucion.acuses().get(1).exigibleDesde()).isEqualTo(SANCIONADORA_DESDE);
        }

        @Test
        @DisplayName("cada acto deja su fila de auditoria, con la observacion de quien lo hizo")
        void cadaActoDejaAuditoria() {
            Papeleta papeleta = papeletaDeTransito("D02");
            long antes = cuantasFilasDeAuditoria();

            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-D02",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    "EXP-D02",
                                    SentidoDelFallo.INFUNDADO,
                                    EfectoSobreLaMulta.SE_MANTIENE)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(cuantasFilasDeAuditoria() - antes)
                    .as("el descargo, el documento emitido, la resolucion y la diligencia")
                    .isGreaterThanOrEqualTo(4);
            assertThat(observacionesDeAuditoria())
                    .as("y todas con la observacion de quien lo hizo (regla 10, RNF-052)")
                    .contains(PORQUE.texto());
        }
    }

    @Nested
    @DisplayName("Privilegios y aislamiento")
    class PrivilegiosYAislamiento {

        @Test
        @DisplayName("sgtm_app no puede editar ni borrar una resolucion de gerencia")
        void laResolucionNoSeEdita() {
            Papeleta papeleta = papeletaDeTransito("E01");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE resolucion_gerencia SET sustento ="
                                                            + " 'corregido' WHERE id = "
                                                            + ordinaria.identificador())))
                    .as(
                            "V41 no le concede UPDATE: la resolucion se notifica y el administrado se"
                                    + " lleva el papel")
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM resolucion_gerencia WHERE id = "
                                                            + ordinaria.identificador())))
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("sgtm_app no puede rellenar la salida encima del ingreso")
        void elInternamientoNoSeEdita() {
            Papeleta papeleta = papeletaDeTransito("E02");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-601");

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE internamiento SET deposito = 'otro'"
                                                            + " WHERE id = "
                                                            + internado
                                                                    .internamiento()
                                                                    .identificador())))
                    .as("V41 le retira el UPDATE: la salida es un acto con su acta")
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("desde otra municipalidad, la resolucion no existe")
        void desdeOtraMunicipalidadNoExiste() {
            Papeleta papeleta = papeletaDeTransito("E03");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            Optional<ResolucionDeGerencia> desdeB =
                    enTransaccionDe(
                            otraMunicipalidad,
                            () -> resoluciones.porNumero(ordinaria.numero()),
                            "gerente");

            assertThat(desdeB).as("RLS: no es que este vacia, es que no existe").isEmpty();
        }

        @Test
        @DisplayName("y una resolucion sobre una papeleta anulada no se dicta")
        void noSeResuelveSobreUnaPapeletaAnulada() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("E04");
            ejecutarComoApp(
                    "UPDATE papeleta SET estado = 'ANULADA' WHERE id = "
                            + papeleta.identificador());

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            ORDINARIA,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(RegistrarDescargo.PapeletaSinNadaQueImpugnar.class);
        }
    }

    // ==================================================================
    //  Utilidades
    // ==================================================================

    /**
     * El contexto se fija <b>antes</b> de abrir la transacción, no dentro: {@code
     * TenantTransactionManager} lo lee al comenzarla para emitir el {@code SET LOCAL}.
     */
    private static <T> T enTransaccion(Supplier<T> accion) {
        return enTransaccionDe(municipalidad, accion, "inspector.transito");
    }

    private static <T> T enTransaccion(Supplier<T> accion, String usuario) {
        return enTransaccionDe(municipalidad, accion, usuario);
    }

    private static <T> T enTransaccionDe(long tenant, Supplier<T> accion, String usuario) {
        TenantContext.fijar(new MunicipalidadId(tenant));
        OrigenContext.fijar(new Origen(usuario, null, null));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(tenant));
                    OrigenContext.fijar(new Origen(usuario, null, null));
                    return accion.get();
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /** Una papeleta de tránsito con su cargo ya asentado en el libro. */
    private static Papeleta papeletaDeTransito(String sufijo) {
        long obligado = crearContribuyente(sufijo);
        crearCodigo("G-" + sufijo);
        return enTransaccion(
                () ->
                        registrarPapeleta.registrarTransito(
                                "PT-" + sufijo,
                                "G-" + sufijo,
                                INFRACCION,
                                null,
                                "Av. Grau",
                                "T2G-" + Math.abs(sufijo.hashCode() % 900 + 100),
                                null,
                                null,
                                null,
                                obligado,
                                obligado,
                                Dinero.de("5350.00"),
                                Alicuota.de("8"),
                                MULTA,
                                Alicuota.de("100"),
                                MULTA,
                                null,
                                PORQUE));
    }

    private static ResolverConResolucionDeGerencia.ResolucionDictada dictar(
            Papeleta papeleta,
            TipoDeResolucionDeGerencia tipo,
            LocalDate fecha,
            String expedienteDelDescargo,
            SentidoDelFallo sentido,
            EfectoSobreLaMulta efecto) {
        return enTransaccion(
                () ->
                        resolver.dictar(
                                new ResolverConResolucionDeGerencia.Peticion(
                                        papeleta.familia(),
                                        papeleta.numero(),
                                        tipo,
                                        fecha,
                                        expedienteDelDescargo,
                                        sentido,
                                        efecto,
                                        null,
                                        "Sustento de la prueba",
                                        null),
                                FormatoDeDocumento.PDF,
                                PORQUE),
                "gerente");
    }

    private static NotificarResolucionDeGerencia.Diligencia notificarResolucion(
            String numero, LocalDate fecha, ResultadoDeNotificacion resultado) {
        boolean recibio = resultado == ResultadoDeNotificacion.NOTIFICADO;
        return enTransaccion(
                () ->
                        notificar.registrar(
                                numero,
                                new NotificarResolucionDeGerencia.Peticion(
                                        fecha,
                                        ModalidadDeNotificacion.PERSONAL,
                                        resultado,
                                        "V. RETO SANTOS",
                                        "AV. JOSE DE LAMA 1180 - SULLANA",
                                        recibio ? "RUIZ INGA, FERNANDO" : null,
                                        recibio ? "DNI 10027723" : null,
                                        recibio ? "REPRESENTANTE" : null,
                                        recibio ? "CARGO-RG" : null),
                                PORQUE),
                "notificador");
    }

    private static RegistrarInternamiento.Internado internarVehiculo(
            Papeleta papeleta, String placa) {
        return enTransaccion(
                () ->
                        internar.internar(
                                new RegistrarInternamiento.Peticion(
                                        placa,
                                        null,
                                        papeleta.numero(),
                                        "DEPOSITO SULLANA NORTE",
                                        INFRACCION.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                        "CUSTODIA",
                                        "Conducir sin licencia vigente"),
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static LiberarVehiculoInternado.Liberado liberarVehiculo(String placa, String recibo) {
        return enTransaccion(
                () ->
                        liberar.liberar(
                                new LiberarVehiculoInternado.Peticion(
                                        placa,
                                        ORDINARIA,
                                        recibo,
                                        "SERNAQUE VILLEGAS, DORIS",
                                        "DNI 44218937",
                                        true),
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static Dinero deudaDe(Papeleta papeleta, LocalDate fecha) {
        List<ObligacionPublica> obligaciones =
                enTransaccion(() -> deudas.deTodoElContribuyente(papeleta.obligadoId(), fecha));
        Dinero total = Dinero.CERO;
        for (ObligacionPublica obligacion : obligaciones) {
            if ("MULTA_TRANSITO".equals(obligacion.tributo())) {
                total = total.mas(obligacion.total());
            }
        }
        return total;
    }

    private static String motivoDelUltimoAbono(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT motivo FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :contribuyente"
                                                + "   AND tipo = 'ABONO' ORDER BY id DESC LIMIT 1")
                                .param("contribuyente", papeleta.obligadoId())
                                .query(String.class)
                                .single());
    }

    /** La causal de la ultima baja asentada contra el obligado de la papeleta (#684). */
    private static String causalDelUltimoAbono(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT causal FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :contribuyente"
                                                + "   AND tipo = 'ABONO' ORDER BY id DESC LIMIT 1")
                                .param("contribuyente", papeleta.obligadoId())
                                .query(String.class)
                                .single());
    }

    private static long cuantasFilasDeAuditoria() {
        Long cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql("SELECT count(*) FROM auditoria")
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
    }

    private static List<String> observacionesDeAuditoria() {
        return enTransaccion(
                () -> jdbc.sql("SELECT observacion FROM auditoria").query(String.class).list());
    }

    private static long cuantasResoluciones(Papeleta papeleta, String tipo) {
        Long cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM resolucion_gerencia"
                                                        + " WHERE papeleta_id = :papeleta AND tipo ="
                                                        + " :tipo")
                                        .param("papeleta", papeleta.identificador())
                                        .param("tipo", tipo)
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
    }

    /** Cobra la custodia con la caja de verdad: recibo y detalle en sus tablas. */
    private static String cobrarCustodia(long contribuyenteId) {
        return cobrarTasa(contribuyenteId, "CUSTODIA", tasaDeCustodiaId, CUSTODIA);
    }

    private static String cobrarTasa(
            long contribuyenteId, String codigo, long tasaId, Dinero importe) {
        kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo numero =
                enTransaccion(
                        () ->
                                recibos.siguienteNumero(
                                        new kamayuk.rentas.tesoreria.dominio.Caja(
                                                cajaId,
                                                "C-01",
                                                "Caja de la prueba",
                                                "001",
                                                areaId,
                                                true)),
                        "cajero");
        enTransaccion(
                () ->
                        recibos.emitir(
                                new kamayuk.rentas.tesoreria.dominio.Recibo(
                                        null,
                                        numero,
                                        cajaId,
                                        turnoId,
                                        "cajero",
                                        contribuyenteId,
                                        ORDINARIA.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                        kamayuk.rentas.tesoreria.dominio.FormaDePago.EFECTIVO,
                                        kamayuk.rentas.tesoreria.dominio.TipoDePago.TASA,
                                        null,
                                        ORDINARIA,
                                        PORQUE,
                                        List.of(
                                                new kamayuk.rentas.tesoreria.dominio.LineaDeRecibo(
                                                        codigo,
                                                        "TASA",
                                                        null,
                                                        null,
                                                        tasaId,
                                                        null,
                                                        null,
                                                        null,
                                                        1,
                                                        importe,
                                                        importe,
                                                        Dinero.CERO,
                                                        Dinero.CERO,
                                                        Dinero.CERO))),
                                null),
                "cajero");
        return numero.impreso();
    }

    private static void anular(String numeroImpreso) {
        int guion = numeroImpreso.lastIndexOf('-');
        kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo numero =
                new kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo(
                        numeroImpreso.substring(0, guion),
                        Long.parseLong(numeroImpreso.substring(guion + 1)));
        kamayuk.rentas.tesoreria.dominio.Recibo recibo =
                enTransaccion(() -> recibos.porNumero(numero), "cajero").orElseThrow();
        enTransaccion(
                () ->
                        movimientosDeRecibo.registrar(
                                kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo.anulacion(
                                        recibo,
                                        ORDINARIA,
                                        "cobro indebido",
                                        "supervisor",
                                        "MEMO-01",
                                        CUSTODIA,
                                        PORQUE)),
                "cajero");
    }

    // ------------------------------------------------------------------
    //  Siembra
    // ------------------------------------------------------------------

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** El SQLSTATE del fallo, que es lo que distingue «no tiene privilegio» de «no cumple». */
    private static String estadoSqlDelFallo(SentenciaQueFalla sentencia) {
        try {
            sentencia.ejecutar();
        } catch (SQLException fallo) {
            return fallo.getSQLState();
        }
        return "no fallo";
    }

    @FunctionalInterface
    private interface SentenciaQueFalla {
        void ejecutar() throws SQLException;
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
     * El conjunto sellado de 2026 <b>con los dos plazos dentro</b>.
     *
     * <p>Los cinco días del descargo y los siete de la resolución ordinaria entran como
     * <b>dato</b>, no como constantes del programa (regla 5). Que esta prueba tenga que sembrarlos
     * es la demostración: sin ellos, registrar un descargo o notificar una resolución falla.
     */
    private static long crearConjuntoConLosPlazos(long municipalidadId) throws SQLException {
        long descargo =
                cargarParametro(
                        "DESCARGO_PAPELETA",
                        "5 DIAS_HABILES",
                        "Reglamento Nacional de Transito, D.S. 016-2009-MTC");
        long ordinaria =
                cargarParametro(
                        "RG_ORDINARIA_CUMPLIMIENTO",
                        "7 DIAS_HABILES",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF");

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio,"
                                    + " version) VALUES (?, 2026, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : new long[] {descargo, ordinaria}) {
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
                                    + " WHERE id = ?")) {
                sentencia.setLong(1, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
            return conjunto;
        }
    }

    /**
     * El catálogo normativo lo carga <b>su propio rol</b>, no la aplicación ni el dueño del esquema
     * (SoD-1 de REQ-03, política {@code parametro_escritura} de V6). Y va con {@code
     * municipalidad_id NULL}: los dos plazos son norma nacional, no una ordenanza.
     */
    private static long cargarParametro(String clave, String valor, String fuente)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'PLAZO', ?, ?,"
                                        + " DATE '2026-01-01', ?, true, 'siembra') RETURNING id")) {
            sentencia.setString(1, clave);
            sentencia.setString(2, valor);
            sentencia.setString(3, fuente);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(String sufijo) {
        return insertar(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES ("
                        + municipalidad
                        + ", 'C-"
                        + sufijo
                        + "', 'DNI', '"
                        + dniDe(sufijo)
                        + "', 'NATURAL', 'SERNAQUE VILLEGAS, DORIS', 'siembra') RETURNING id");
    }

    private static String dniDe(String codigo) {
        return "44" + Math.abs(codigo.hashCode() % 1000000 + 1000000);
    }

    private static void crearCodigo(String codigo) {
        insertar(
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                        + municipalidad
                        + ", 'TRANSITO', '"
                        + codigo
                        + "', 'Infraccion de la prueba', 8.0000, 'D.S. 016-2009-MTC',"
                        + " DATE '2026-01-01') RETURNING id");
    }

    private static long crearArea() {
        return insertar(
                "INSERT INTO area (municipalidad_id, codigo, nombre) VALUES ("
                        + municipalidad
                        + ", 'TRA', 'Transito') RETURNING id");
    }

    private static long crearCaja() {
        return insertar(
                "INSERT INTO caja (municipalidad_id, codigo, nombre, area_id, serie) VALUES ("
                        + municipalidad
                        + ", 'C-01', 'Caja de la prueba', "
                        + areaId
                        + ", '001') RETURNING id");
    }

    private static long crearTurno() {
        return insertar(
                "INSERT INTO cierre_caja (municipalidad_id, caja_id, cajero, fecha,"
                        + " fecha_apertura, usuario_apertura, observacion) VALUES ("
                        + municipalidad
                        + ", "
                        + cajaId
                        + ", 'cajero', DATE '2026-04-01', now(), 'cajero',"
                        + " 'turno de la prueba') RETURNING id");
    }

    private static long crearTasa(String codigo, Dinero importe) {
        return insertar(
                "INSERT INTO tasa (municipalidad_id, codigo, descripcion, area_id,"
                        + " partida_presupuestal, importe, vigencia_desde, documento_fuente)"
                        + " VALUES ("
                        + municipalidad
                        + ", '"
                        + codigo
                        + "', 'Concepto de la prueba', "
                        + areaId
                        + ", '1.3.1', "
                        + importe.valor().toPlainString()
                        + ", DATE '2026-01-01', 'TUPA de la prueba') RETURNING id");
    }

    /** Un documento emitido suelto, para las pruebas que insertan por SQL directo. */
    private static long documentoSuelto(String tipo, String numero) {
        return insertar(
                "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                        + " referencia, datos, formato, resumen, fecha_emision, usuario_emision,"
                        + " observacion) VALUES ("
                        + municipalidad
                        + ", '"
                        + tipo
                        + "', '"
                        + numero
                        + "', 2026, 'prueba', CAST('{\"titulo\":\"x\",\"subtitulo\":null,"
                        + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],\"pie\":[],"
                        + "\"duplicado\":null}' AS jsonb), 'PDF', repeat('f', 64),"
                        + " DATE '2026-01-01', 'siembra', 'documento de prueba') RETURNING id");
    }

    private static long insertar(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                app.commit();
                return id;
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, excepcion);
        }
    }

    /**
     * El padrón, leído de la base.
     *
     * <p>No es un doble de conveniencia: el nombre y el domicilio del obligado salen impresos en la
     * resolución, y este contexto los pide por la API pública de {@code contribuyentes} (ARQ-01
     * §4). Lo único que esta clase evita es arrastrar el módulo entero a la prueba.
     */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return jdbc.sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social, numero_documento"
                                    + " FROM contribuyente WHERE codigo_contribuyente = :codigo")
                    .param("codigo", codigo)
                    .query(PadronDeLaPrueba::mapear)
                    .optional();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new java.util.HashMap<>();
            for (Long id : ids) {
                jdbc.sql(
                                "SELECT id, codigo_contribuyente, nombre_razon_social,"
                                        + " numero_documento FROM contribuyente WHERE id = :id")
                        .param("id", id)
                        .query(PadronDeLaPrueba::mapear)
                        .optional()
                        .ifPresent(resumen -> encontrados.put(resumen.id(), resumen));
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.of("AV. JOSE DE LAMA 1180 - SULLANA");
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            // La resolucion no busca por nombre: el obligado sale de la papeleta.
            return List.of();
        }

        private static ResumenDeContribuyente mapear(ResultSet fila, int numero)
                throws SQLException {
            return new ResumenDeContribuyente(
                    fila.getLong("id"),
                    fila.getString("codigo_contribuyente"),
                    fila.getString("nombre_razon_social"),
                    "DNI " + fila.getString("numero_documento"));
        }
    }
}
