package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.BigDecimal;
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
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.OrigenDeLaObligacionCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RecaudacionDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.ActoFueraDeOrden;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.PapeletasSinNotificar;
import kamayuk.rentas.sanciones.aplicacion.AnularPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeLaCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeLaHojaDePapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDePadronesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeResumenesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.EmitirConstanciaLibre;
import kamayuk.rentas.sanciones.aplicacion.GenerarCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.IniciarCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.PapeletasSinNotificarSanciones;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.ProcesarPapeletaDeLaCorrida;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.aplicacion.RegistrarPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CriterioDeConstancias;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.EstadoDeItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.RecuentoDelPadron;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.sanciones.infraestructura.web.AnulacionDePapeletaController;
import kamayuk.rentas.sanciones.infraestructura.web.HojaDePapeletaController;
import kamayuk.rentas.sanciones.infraestructura.web.HojaInformativaResource;
import kamayuk.rentas.sanciones.infraestructura.web.PapeletaDelPadronResource;
import kamayuk.rentas.sanciones.infraestructura.web.PeticionDeReporteDeTransito;
import kamayuk.rentas.sanciones.infraestructura.web.RecaudacionDeMultasResource;
import kamayuk.rentas.sanciones.infraestructura.web.ReporteDeTransitoResource;
import kamayuk.rentas.sanciones.infraestructura.web.ReportesDeTransitoController;
import kamayuk.rentas.sanciones.infraestructura.web.ResolucionesDeGerenciaController;
import kamayuk.rentas.sanciones.infraestructura.web.ResumenDePapeletasResource;
import kamayuk.rentas.sanciones.infraestructura.web.ResumenesDeTransitoController;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import kamayuk.rentas.valores.aplicacion.EmisionDeValoresDeMultasValores;
import kamayuk.rentas.valores.aplicacion.RegistrarValor;
import kamayuk.rentas.valores.aplicacion.ValoresSobreUnaObligacionValores;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #53 — Valores masivos de papeletas, constancias, padrones y resúmenes contra PostgreSQL de verdad
 * (V47), conectado como {@code kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>AC 1 — la generación masiva reutiliza la numeración de #37.</b> No se afirma leyendo el
 *       código: se comprueba que {@code valor_correlativo} <b>avanzó</b> exactamente tantas veces
 *       como valores salieron, que los números son consecutivos, y que una emisión individual
 *       posterior sigue la misma serie. Un correlativo propio dejaría {@code valor_correlativo}
 *       quieto y el choque aparecería en la primera emisión manual, meses después.
 *   <li><b>AC 2 — la constancia se niega con papeleta pendiente a la fecha del parámetro.</b> Con
 *       la misma placa y dos fechas distintas: antes de la infracción se emite, después se niega.
 *       Resolver esa fecha con el reloj —en vez de recibirla— haría que las dos respuestas fueran
 *       la misma.
 *   <li><b>AC 3 — el resumen de recaudación cuadra con el libro.</b> Se cobra una papeleta de
 *       verdad y se comprueba que lo recaudado es <b>exactamente</b> la suma de los abonos; y que
 *       el resumen de papeletas, que cuenta actas, dice otra cosa —0 pagadas— porque nadie escribe
 *       {@code papeleta.estado} al cobrar. Recomponer la recaudación de ahí daría 0,00 donde se
 *       cobraron 428,00.
 *   <li><b>AC 6 — un valor por papeleta, con diez hilos de verdad.</b> Diez corridas simultáneas
 *       sobre la misma papeleta: una emite y nueve se deshacen enteras. La garantía es {@code
 *       papeleta_valor_unico_uq}, no un {@code if}.
 *   <li><b>AC 7 — RLS.</b> Desde la municipalidad vecina la corrida y la constancia no existen; y
 *       {@code kamayuk_app} no puede editar ninguna de las dos.
 * </ul>
 */
@DisplayName("#53 — Valores masivos, constancias, padrones y resumenes contra PostgreSQL")
class ValoresMasivosYReportesJdbcTest {

    /** El día de la infracción de las papeletas de la siembra: miércoles 4 de marzo de 2026. */
    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);

    /** El día en que se dicta la resolución ordinaria. */
    private static final LocalDate ORDINARIA = LocalDate.of(2026, 4, 1);

    /** El día en que se diligencia. */
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 4, 2);

    /**
     * Desde cuándo se puede cobrar, con el plazo <b>parametrizado</b> de 7 días hábiles.
     *
     * <p>La cuenta, día a día: la diligencia es el jueves 2; surte efecto el viernes 3; siete días
     * hábiles desde ahí son 6, 7, 8, 9, 10, 13 y 14; el plazo vence el martes 14 y se puede exigir
     * desde el <b>miércoles 15</b>. Está escrito aquí porque una prueba que recalculara la fecha
     * con el mismo código que verifica no verificaría nada.
     */
    private static final LocalDate EXIGIBLE_DESDE = LocalDate.of(2026, 4, 15);

    private static final Dinero MULTA = Dinero.de("428.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    /**
     * El criterio del frente de #549: las de transito vivas y sin valor emitido.
     *
     * <p>No es {@code estado = IMPUESTA}. Ningun codigo de produccion escribe {@code NOTIFICADA}
     * —lo dice el censo de los usos del enumerado en {@code src/main}—, asi que contar por ese
     * estado publicaria «sin notificar» sobre una poblacion que no lo distingue.
     */
    private static final CriterioDePadron SIN_VALOR_EMITIDO =
            new CriterioDePadron(
                    Familia.TRANSITO,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Boolean.FALSE,
                    true);

    private static TransactionTemplate transaccion;

    private static PapeletaRepositoryJdbc papeletas;
    private static RegistrarDescargo registrarDescargo;
    private static PadronDePapeletasRepositoryJdbc padron;
    private static CorridaDeValoresRepositoryJdbc corridas;
    private static ConstanciaLibreRepositoryJdbc constancias;

    private static RegistrarPapeleta registrarPapeleta;
    private static ResolverConResolucionDeGerencia resolver;
    private static NotificarResolucionDeGerencia notificar;
    private static IniciarCorridaDeValores iniciar;
    private static ProcesarPapeletaDeLaCorrida procesar;
    private static GenerarCorridaDeValores generar;
    private static AnularPapeleta anularPapeleta;
    private static AnulacionDePapeletaController anulacion;
    private static ResolucionesDeGerenciaController resolucionesPorLaApi;
    private static EmitirConstanciaLibre emitirConstancia;
    private static ConsultaDePadronesDeSanciones consultaDePadrones;
    private static ConsultaDeResumenesDeSanciones consultaDeResumenes;
    private static ConsultaDeLaHojaDePapeleta consultaDeLaHoja;
    private static ReportesDeTransitoController emisorDeReportes;
    private static HojaDePapeletaController hojaDePapeleta;
    private static ResumenesDeTransitoController resumenesDeTransito;
    private static RegistroDeAbonos abonos;

    /**
     * Desde #39 hace falta tambien aqui: {@code abonarPagoIntegro} recibe lo que la caja cobro y lo
     * compara con lo que el libro dice. Esta prueba cobra integro, asi que lee la cifra del libro
     * —igual que {@code EmitirOrdenDeCobro} al valorar la orden— en vez de escribirla a mano.
     */
    private static ConsultaDeDeudaPublica deudas;

    private static RegistrarValor registrarValor;
    private static ValorRepositoryJdbc repositorioDeValores;
    private static GeneradorDeDocumentos generadorDeDocumentos;

    /**
     * Lo que {@link ProcesarQueRevienta} necesita para ser un {@link ProcesarPapeletaDeLaCorrida}
     * de verdad salvo en el candidato que se le diga (#384).
     */
    private static ResolucionDeGerenciaRepositoryJdbc repositorioDeResoluciones;

    private static NotificacionDeResolucionRepositoryJdbc repositorioDeDiligencias;
    private static EmisionDeValoresDeMultas emisionDeMultas;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250801", "Municipalidad de reportes");
        otraMunicipalidad = crearMunicipalidad("250802", "Municipalidad vecina de #53");
        crearConjuntoConElPlazo(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        papeletas = new PapeletaRepositoryJdbc(jdbc);
        padron = new PadronDePapeletasRepositoryJdbc(jdbc);
        corridas = new CorridaDeValoresRepositoryJdbc(jdbc);
        constancias = new ConstanciaLibreRepositoryJdbc(jdbc);
        CodigoInfraccionRepositoryJdbc codigos = new CodigoInfraccionRepositoryJdbc(jdbc);
        ResolucionDeGerenciaRepositoryJdbc resoluciones =
                new ResolucionDeGerenciaRepositoryJdbc(jdbc);
        NotificacionDeResolucionRepositoryJdbc diligencias =
                new NotificacionDeResolucionRepositoryJdbc(jdbc);

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
        MovimientoDeFase fases =
                envolver(
                        new MovimientoDeFaseCuentaCorriente(
                                registrarAsiento, asientos, saldos, calculo, redondeo));
        ExtincionDeDeuda extincion =
                envolver(
                        new ExtincionDeDeudaCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));
        RecaudacionDelLibro libro = envolver(new RecaudacionDelLibroCuentaCorriente(asientos));

        generadorDeDocumentos =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorXls(),
                                new RenderizadorRtf()),
                        RegimenDeLaInstalacion.REAL);
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
                                generadorDeDocumentos,
                                auditoria,
                                RELOJ));

        PlazosDeSancionesParametrizados plazos =
                new PlazosDeSancionesParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new kamayuk.rentas.parametros.infraestructura
                                                .ParametrosRepositoryJdbc(jdbc))));

        DirectorioDeContribuyentes directorio = new PadronDeLaPrueba();
        DescargoRepositoryJdbc repositorioDeDescargos = new DescargoRepositoryJdbc(jdbc);
        registrarDescargo =
                envolver(
                        new RegistrarDescargo(
                                papeletas, repositorioDeDescargos, plazos, auditoria, RELOJ));

        repositorioDeValores = new ValorRepositoryJdbc(jdbc);
        registrarValor =
                envolver(new RegistrarValor(repositorioDeValores, deudas, fases, auditoria, RELOJ));
        EmisionDeValoresDeMultas emision =
                envolver(
                        new EmisionDeValoresDeMultasValores(
                                registrarValor,
                                deudas,
                                envolver(
                                        new OrigenDeLaObligacionCuentaCorriente(
                                                asientos, saldos))));
        repositorioDeResoluciones = resoluciones;
        repositorioDeDiligencias = diligencias;
        emisionDeMultas = emision;

        registrarPapeleta = envolver(new RegistrarPapeleta(papeletas, codigos, cargos, auditoria));
        ValoresSobreUnaObligacion valoresVivos =
                envolver(new ValoresSobreUnaObligacionValores(repositorioDeValores));
        resolver =
                envolver(
                        new ResolverConResolucionDeGerencia(
                                papeletas,
                                repositorioDeDescargos,
                                resoluciones,
                                diligencias,
                                directorio,
                                valoresVivos,
                                deudas,
                                extincion,
                                plazos,
                                documentos,
                                auditoria,
                                RELOJ));
        notificar =
                envolver(
                        new NotificarResolucionDeGerencia(
                                resoluciones,
                                diligencias,
                                papeletas,
                                directorio,
                                plazos,
                                auditoria,
                                RELOJ));
        resolucionesPorLaApi = new ResolucionesDeGerenciaController(resolver, notificar);
        iniciar =
                envolver(
                        new IniciarCorridaDeValores(papeletas, padron, corridas, auditoria, RELOJ));
        procesar =
                envolver(
                        new ProcesarPapeletaDeLaCorrida(
                                papeletas, resoluciones, diligencias, emision, corridas));
        anularPapeleta =
                envolver(
                        new AnularPapeleta(
                                papeletas, valoresVivos, extincion, deudas, auditoria, RELOJ));
        anulacion = new AnulacionDePapeletaController(anularPapeleta);
        generar =
                new GenerarCorridaDeValores(
                        envolver(new ConsultaDeLaCorridaDeValores(corridas)), procesar);
        emitirConstancia =
                envolver(
                        new EmitirConstanciaLibre(
                                padron,
                                constancias,
                                documentos,
                                envolver(
                                        new kamayuk.rentas.nucleo.aplicacion.PadronVehicularRentas(
                                                new kamayuk.rentas.nucleo.infraestructura
                                                        .VehiculoRepositoryJdbc(jdbc))),
                                directorio,
                                auditoria,
                                RELOJ));
        consultaDePadrones =
                envolver(
                        new ConsultaDePadronesDeSanciones(
                                padron,
                                constancias,
                                new NotificacionAdministrativaRepositoryJdbc(jdbc)));
        consultaDeResumenes = envolver(new ConsultaDeResumenesDeSanciones(padron, libro));
        consultaDeLaHoja = envolver(new ConsultaDeLaHojaDePapeleta(papeletas, codigos, directorio));
        emisorDeReportes =
                new ReportesDeTransitoController(
                        consultaDePadrones, consultaDeResumenes, generadorDeDocumentos, RELOJ);
        hojaDePapeleta =
                new HojaDePapeletaController(consultaDeLaHoja, generadorDeDocumentos, RELOJ);
        resumenesDeTransito =
                new ResumenesDeTransitoController(
                        consultaDeResumenes, generadorDeDocumentos, RELOJ);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    // ==================================================================
    //  AC 1 — la numeracion es la de #37
    // ==================================================================

    @Nested
    @DisplayName("AC 1 — la generacion masiva reutiliza la numeracion de #37")
    class LaNumeracion {

        @Test
        @DisplayName("el numero sale de valor_correlativo, y el contador avanza con cada valor")
        void elNumeroSaleDeValorCorrelativo() {
            Papeleta una = papeletaExigible("num1");
            Papeleta otra = papeletaExigible("num2");

            long antes = correlativoDe("RM", 2026);
            CorridaDeValores corrida =
                    enTransaccion(
                            () ->
                                    iniciar.porSeleccion(
                                            Familia.TRANSITO,
                                            List.of(una.numero(), otra.numero()),
                                            EXIGIBLE_DESDE,
                                            PORQUE));
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.generados()).as("las dos papeletas se formalizan").isEqualTo(2);

            List<String> numeros = numerosEmitidosDe(corrida);
            assertThat(numeros).hasSize(2);
            assertThat(correlativoDe("RM", 2026))
                    .as("el contador de #37 avanzo exactamente dos veces")
                    .isEqualTo(antes + 2);
            assertThat(numeros.get(0)).matches("RM-2026-\\d{6}");
            assertThat(ordinalDe(numeros.get(1)))
                    .as("los dos numeros son consecutivos de la misma serie")
                    .isEqualTo(ordinalDe(numeros.get(0)) + 1);
        }

        @Test
        @DisplayName("una emision individual posterior continua la misma serie, sin chocar")
        void laEmisionIndividualContinuaLaMismaSerie() {
            Papeleta papeleta = papeletaExigible("num3");
            CorridaDeValores corrida = corridaDe(papeleta);
            generar.generar(corrida.identificador());
            String delMasivo = numerosEmitidosDe(corrida).get(0);

            long contribuyente = crearContribuyente("num4");
            cargoSuelto(contribuyente, "ARBITRIO", Dinero.de("100.00"));
            String individual =
                    enTransaccion(
                                    () ->
                                            registrarValor.emitir(
                                                    kamayuk.rentas.valores.dominio.TipoValor
                                                            .RESOLUCION_DE_MULTA,
                                                    contribuyente,
                                                    List.of(
                                                            new kamayuk.rentas.valores.dominio
                                                                    .SelectorDeObligacion(
                                                                    "ARBITRIO",
                                                                    new Ejercicio(2026),
                                                                    null,
                                                                    null)),
                                                    PORQUE,
                                                    EXIGIBLE_DESDE))
                            .numero();

            assertThat(ordinalDe(individual))
                    .as(
                            "si la corrida hubiera inventado su serie, valor_correlativo se habria"
                                    + " quedado quieto y este numero chocaria con el del masivo")
                    .isGreaterThan(ordinalDe(delMasivo));
        }

        @Test
        @DisplayName("el item guarda el numero impreso junto al identificador del valor")
        void elItemGuardaElNumeroImpreso() {
            Papeleta papeleta = papeletaExigible("num5");
            CorridaDeValores corrida = corridaDe(papeleta);
            generar.generar(corrida.identificador());

            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado()).isEqualTo(EstadoDeItemDeCorrida.GENERADO);
            assertThat(item.valorNumero())
                    .as("es lo que el padron imprime y lo que el operador teclea")
                    .isEqualTo(numeroDelValor(item.valorId()));
        }
    }

    // ==================================================================
    //  Las tres razones por las que una papeleta no procede
    // ==================================================================

    @Nested
    @DisplayName("Una papeleta sin su resolucion firme no se formaliza, y se dice por que")
    class LoQueNoProcede {

        @Test
        @DisplayName("sin resolucion que ordene la cobranza: NO_PROCEDE, diciendolo")
        void sinResolucion() {
            Papeleta papeleta = papeletaDeTransito("np1");
            CorridaDeValores corrida = corridaDe(papeleta);

            generar.generar(corrida.identificador());

            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado()).isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo()).contains("ordene la cobranza");
        }

        @Test
        @DisplayName("dictada pero sin notificar: NO_PROCEDE, nombrando la resolucion")
        void sinNotificar() {
            Papeleta papeleta = papeletaDeTransito("np2");
            dictarOrdinaria(papeleta);
            CorridaDeValores corrida = corridaDe(papeleta);

            generar.generar(corrida.identificador());

            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado()).isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo()).contains("no consta notificada");
        }

        @Test
        @DisplayName("con el plazo todavia corriendo: NO_PROCEDE, con la fecha en que vence")
        void conElPlazoCorriendo() {
            Papeleta papeleta = papeletaExigible("np3");

            // La fecha de criterio es la vispera del dia en que la deuda es exigible.
            CorridaDeValores corrida =
                    enTransaccion(
                            () ->
                                    iniciar.porSeleccion(
                                            Familia.TRANSITO,
                                            List.of(papeleta.numero()),
                                            EXIGIBLE_DESDE.minusDays(1),
                                            PORQUE));
            generar.generar(corrida.identificador());

            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado()).isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo())
                    .as("dice cuando vence, que es lo unico que quien opera puede hacer: esperar")
                    .contains(EXIGIBLE_DESDE.toString());
        }

        /**
         * #402 — La fecha de criterio no se fecha despues de hoy. Es la fecha de emision y de corte
         * de la deuda de cada RM de la corrida, y {@code ProcesarPapeletaDeLaCorrida} compara la
         * exigibilidad con ella y no con hoy: con una fecha futura, una papeleta cuyo plazo todavia
         * corre saldria formalizada. Cuanto puede ir hacia atras es otro issue.
         */
        @Test
        @DisplayName("#402 — con la fecha de criterio despues de hoy la corrida no se inicia")
        void conLaFechaDeCriterioDespuesDeHoy() {
            Papeleta papeleta = papeletaExigible("np5");
            long antes = cuantasCorridas();

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    iniciar.porSeleccion(
                                                            Familia.TRANSITO,
                                                            List.of(papeleta.numero()),
                                                            LocalDate.of(2026, 4, 21),
                                                            PORQUE)))
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(cuantasCorridas()).isEqualTo(antes);
        }

        @Test
        @DisplayName("sin deuda que formalizar: SIN_DEUDA, y no NO_PROCEDE")
        void sinDeuda() {
            Papeleta papeleta = papeletaExigible("np4");
            cobrarIntegro(papeleta, "RECIBO 001-9000001");

            CorridaDeValores corrida = corridaDe(papeleta);
            generar.generar(corrida.identificador());

            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado())
                    .as("ya pago: no hay nada que formalizar, y no es que falte un acto")
                    .isEqualTo(EstadoDeItemDeCorrida.SIN_DEUDA);
            assertThat(item.motivo()).isNull();
        }
    }

    // ==================================================================
    //  AC 6 — idempotencia
    // ==================================================================

    @Nested
    @DisplayName("AC 6 — un valor por papeleta, se relance lo que se relance")
    class LaIdempotencia {

        @Test
        @DisplayName("relanzar la generacion de la misma corrida no emite un segundo valor")
        void relanzarNoDuplica() {
            Papeleta papeleta = papeletaExigible("idem1");
            CorridaDeValores corrida = corridaDe(papeleta);

            GenerarCorridaDeValores.Informe primera = generar.generar(corrida.identificador());
            GenerarCorridaDeValores.Informe segunda = generar.generar(corrida.identificador());

            assertThat(primera.generados()).isEqualTo(1);
            assertThat(segunda.generados())
                    .as("la segunda pasada no encuentra nada PENDIENTE")
                    .isZero();
            assertThat(cuantosValoresGeneradosDe(papeleta)).isEqualTo(1);
        }

        @Test
        @DisplayName("una corrida nueva sobre la misma papeleta ya no la propone")
        void laSegundaCorridaNoLaPropone() {
            Papeleta papeleta = papeletaExigible("idem2");
            generar.generar(corridaDe(papeleta).identificador());

            CorridaDeValores porRango =
                    enTransaccion(
                            () ->
                                    iniciar.porRango(
                                            Familia.TRANSITO,
                                            INFRACCION,
                                            INFRACCION,
                                            EXIGIBLE_DESDE,
                                            PORQUE));

            assertThat(itemsDe(porRango).stream().map(ItemDeCorrida::papeletaId).toList())
                    .as(
                            "el criterio pide las que NO tienen valor, y esa ya lo tiene; las"
                                    + " demas papeletas de la siembra si pueden entrar")
                    .doesNotContain(papeleta.identificador());
        }

        /**
         * Se captura {@code RuntimeException} a propósito: lo que se mide es <b>cuántos</b> hilos
         * consiguieron emitir, y los nueve que no lo consiguen fallan de maneras distintas —el
         * choque contra el índice, o el {@code rollback-only} que ese choque deja—. Distinguirlas
         * aquí sería medir el mecanismo del fallo en vez de su recuento, y es el recuento lo que
         * dice si el índice está haciendo su trabajo.
         */
        @Test
        @SuppressWarnings("checkstyle:IllegalCatch")
        @DisplayName("diez corridas simultaneas sobre la misma papeleta emiten UN valor")
        void diezHilosEmitenUnValor() throws Exception {
            Papeleta papeleta = papeletaExigible("idem3");

            // Diez corridas distintas, cada una con la MISMA papeleta como unico
            // candidato: es la unica forma de que diez hilos lleguen a la vez al mismo
            // punto. Dentro de una sola corrida el item ya no estaria PENDIENTE, y lo
            // que serializaria seria el UPDATE del propio item, no el indice que se mide.
            List<CorridaDeValores> diez = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                diez.add(corridaDe(papeleta));
            }

            CountDownLatch salida = new CountDownLatch(1);
            ExecutorService hilos = Executors.newFixedThreadPool(10);
            try {
                List<Callable<String>> tareas = new ArrayList<>();
                for (CorridaDeValores corrida : diez) {
                    tareas.add(
                            () -> {
                                salida.await(10, TimeUnit.SECONDS);
                                try {
                                    ItemDeCorrida item = itemsDe(corrida).get(0);
                                    return enTransaccion(
                                                    () -> procesar.procesar(corrida, item, PORQUE))
                                            .name();
                                } catch (RuntimeException fallo) {
                                    return "FALLO";
                                }
                            });
                }
                List<Future<String>> futuros = new ArrayList<>();
                for (Callable<String> tarea : tareas) {
                    futuros.add(hilos.submit(tarea));
                }
                salida.countDown();

                List<String> resultados = new ArrayList<>();
                for (Future<String> futuro : futuros) {
                    resultados.add(futuro.get(60, TimeUnit.SECONDS));
                }

                assertThat(resultados.stream().filter("GENERADO"::equals).count())
                        .as(
                                "papeleta_valor_unico_uq: sin el indice saldrian dos resoluciones"
                                        + " de multa cobrando la misma papeleta")
                        .isEqualTo(1);
            } finally {
                hilos.shutdownNow();
            }

            assertThat(cuantosValoresGeneradosDe(papeleta)).isEqualTo(1);
            assertThat(cuantosValoresDeLaMulta(papeleta))
                    .as("y el valor emitido por los nueve que se deshicieron tampoco quedo")
                    .isEqualTo(1);
        }
    }

    // ==================================================================
    //  AC 2 — la constancia libre
    // ==================================================================

    @Nested
    @DisplayName("AC 2 — la constancia libre se niega con papeleta pendiente A LA FECHA")
    class LaConstanciaLibre {

        @Test
        @DisplayName("con una papeleta pendiente a esa fecha, se niega y dice cual")
        void seNiegaYDiceCual() {
            Papeleta papeleta = papeletaDeTransito("cli1");

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    emitirConstancia.emitir(
                                                            peticionDe(
                                                                    papeleta.placa(),
                                                                    INFRACCION.plusDays(30)),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirConstanciaLibre.HayPapeletasPendientes.class)
                    .hasMessageContaining(papeleta.numero());
        }

        @Test
        @DisplayName("la MISMA placa, a una fecha anterior a la infraccion, si obtiene constancia")
        void aUnaFechaAnteriorSeEmite() {
            Papeleta papeleta = papeletaDeTransito("cli2");

            EmitirConstanciaLibre.Emitida emitida =
                    enTransaccion(
                            () ->
                                    emitirConstancia.emitir(
                                            peticionDe(papeleta.placa(), INFRACCION.minusDays(1)),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(emitida.constancia().verificadaAl())
                    .as(
                            "la fecha entra como argumento: con el reloj, esta constancia y la"
                                    + " anterior serian la misma consulta y las dos se negarian")
                    .isEqualTo(INFRACCION.minusDays(1));
            assertThat(emitida.constancia().numero()).startsWith("CLI-2026-");
            assertThat(new String(emitida.emision().contenido(), StandardCharsets.ISO_8859_1))
                    .as("el papel dice a que dia acredita")
                    .contains(INFRACCION.minusDays(1).toString());
        }

        @Test
        @DisplayName("pagada la papeleta, la constancia sigue negandose: pendiente es de estado")
        void pagadaSigueContando() {
            Papeleta papeleta = papeletaExigible("cli3");
            cobrarIntegro(papeleta, "RECIBO 001-9000003");

            // La cobranza asienta el abono en el LIBRO; nadie escribe papeleta.estado.
            // La constancia mira el estado del acta, que sigue diciendo IMPUESTA, y por
            // eso se niega. Es la misma frontera que el AC 3 mide del otro lado: el
            // libro y el acta contestan preguntas distintas.
            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    emitirConstancia.emitir(
                                                            peticionDe(
                                                                    papeleta.placa(),
                                                                    LocalDate.of(2026, 12, 31)),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirConstanciaLibre.HayPapeletasPendientes.class);
        }

        @Test
        @DisplayName("la constancia queda en el padron, con su numero y su fecha de verificacion")
        void quedaEnElPadron() {
            EmitirConstanciaLibre.Emitida emitida =
                    enTransaccion(
                            () ->
                                    emitirConstancia.emitir(
                                            peticionDe("XYZ-777", LocalDate.of(2026, 4, 20)),
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            Pagina<ConstanciaLibre> pagina =
                    enTransaccion(
                            () ->
                                    consultaDePadrones.constancias(
                                            new CriterioDeConstancias(
                                                    null, null, null, null, "XYZ-777"),
                                            Paginacion.de(0, 20, "fechaEmision")));

            assertThat(pagina.contenido()).hasSize(1);
            assertThat(pagina.contenido().get(0).numero()).isEqualTo(emitida.constancia().numero());
            assertThat(pagina.contenido().get(0).verificadaAl())
                    .isEqualTo(LocalDate.of(2026, 4, 20));
        }
    }

    // ==================================================================
    //  AC 3 — los resumenes cuadran con el libro
    // ==================================================================

    @Nested
    @DisplayName("AC 3 — lo recaudado es exactamente la suma de los abonos")
    class ElResumenCuadraConElLibro {

        @Test
        @DisplayName("cobrada una papeleta, la recaudacion es su importe, al centimo")
        void laRecaudacionEsLaSumaDeLosAbonos() {
            Papeleta papeleta = papeletaExigible("rec1");
            RecaudadoEnElLibro antes = recaudacionDe2026();
            cobrarIntegro(papeleta, "RECIBO 001-9100001");
            RecaudadoEnElLibro despues = recaudacionDe2026();

            assertThat(despues.total().menos(antes.total()))
                    .as("ni un centimo mas ni uno menos que lo abonado")
                    .isEqualTo(MULTA);
            assertThat(despues.abonos())
                    .as("y se sabe de cuantos abonos sale: sin esto, «428,00» no dice si es uno")
                    .isGreaterThan(antes.abonos());
            assertThat(despues.aLaFecha())
                    .as("toda cifra indica su fecha (RNF-075, regla 9)")
                    .isEqualTo(LocalDate.of(2026, 4, 20));
        }

        @Test
        @DisplayName("el resumen de papeletas NO sabe lo recaudado, y por eso no lo dice")
        void elResumenDePapeletasNoEsLaRecaudacion() {
            Papeleta papeleta = papeletaExigible("rec2");
            cobrarIntegro(papeleta, "RECIBO 001-9100002");

            ResumenDePapeletas resumen = resumenPorEstado();
            LineaDelResumen linea = lineaDe(resumen, "IMPUESTA");

            assertThat(linea.pagadas())
                    .as(
                            "el acta sigue diciendo IMPUESTA: nadie escribe papeleta.estado al"
                                    + " cobrar, y recomponer la recaudacion de aqui daria 0,00"
                                    + " donde se cobraron 428,00")
                    .isZero();
            assertThat(recaudacionDe2026().total().esPositivo())
                    .as("mientras el libro si lo sabe")
                    .isTrue();
            assertThat(papeleta.numero()).isNotBlank();
        }

        /**
         * #243 — «En coactiva» tampoco consta, y lo que si consta se cuenta con su nombre.
         *
         * <p>El montaje siembra DOS papeletas y solo una pasa por la corrida, porque con las dos
         * emitidas {@code conResolucionDeMulta} valdria {@code cantidad} y leer {@code cantidad}
         * pasaria esta prueba en verde con el {@code FILTER} quitado. Se agrupa por CODIGO y no por
         * ESTADO justamente para poder mirarlas por separado: cada papeleta de este archivo trae su
         * propio codigo de infraccion, asi que cada una es una linea de una fila.
         */
        @Test
        @DisplayName("el estado COACTIVA no lo escribe nadie, y la multa EMITIDA si consta (#243)")
        void enCoactivaEsCeroYLaResolucionDeMultaSeCuenta() {
            Papeleta conMulta = papeletaExigible("rm1");
            Papeleta sinMulta = papeletaDeTransito("rm2");
            generar.generar(corridaDe(conMulta).identificador());

            ResumenDePapeletas resumen = resumenAgrupadoPor(AgrupacionDelResumen.CODIGO);
            LineaDelResumen laDeLaMulta = lineaDe(resumen, codigoDe("rm1"));
            LineaDelResumen laOtra = lineaDe(resumen, codigoDe("rm2"));

            assertThat(laDeLaMulta.conResolucionDeMulta())
                    .as("su resolucion de multa esta emitida: es la fila GENERADO de la corrida")
                    .isOne();
            assertThat(laOtra.conResolucionDeMulta())
                    .as(
                            "y la que no paso por la corrida no la tiene — sin esta, contar"
                                    + " `cantidad` pasaria igual")
                    .isZero();
            assertThat(laDeLaMulta.enCoactiva())
                    .as(
                            "mientras el estado sigue diciendo IMPUESTA: nadie escribe"
                                    + " papeleta.estado al pasar la multa a cobranza, y el rotulo"
                                    + " «En coactiva» dibujaba esto")
                    .isZero();
            assertThat(sinMulta.numero()).isNotBlank();
        }

        @Test
        @DisplayName("anulado el recibo, la recaudacion vuelve a lo que era")
        void elReciboAnuladoDejaDeContar() {
            Papeleta papeleta = papeletaExigible("rec3");
            RecaudadoEnElLibro antes = recaudacionDe2026();
            cobrarIntegro(papeleta, "RECIBO 001-9100003");
            enTransaccion(
                    () ->
                            abonos.reversarAbonos(
                                    "RECIBO 001-9100003",
                                    "ANULACION 001-9100003",
                                    EXIGIBLE_DESDE,
                                    PORQUE));

            assertThat(recaudacionDe2026().total())
                    .as(
                            "un recibo anulado conserva sus asientos (V2); sumarlos daria por"
                                    + " recaudado lo que ya no vale")
                    .isEqualTo(antes.total());
        }

        @Test
        @DisplayName("dejar una multa sin efecto no sube la recaudacion ni un centimo (#662)")
        void dejarSinEfectoNoEsRecaudar() {
            Papeleta papeleta = papeletaDeTransito("rec4");
            RecaudadoEnElLibro antes = recaudacionDe2026();

            ResolverConResolucionDeGerencia.ResolucionDictada dictada =
                    dejarSinEfecto(papeleta, "EXP-REC4");

            assertThat(dictada.baja())
                    .as("la baja se asienta, no se edita la papeleta")
                    .isNotNull();
            assertThat(dictada.baja().importe())
                    .as("y da de baja lo que se debia a la fecha de la resolucion")
                    .isEqualTo(MULTA);

            RecaudadoEnElLibro despues = recaudacionDe2026();

            // Este es el panel de sanciones —el resumen de recaudacion de multas de #53,
            // RF-074— y esta es la cifra que se movia. El abono de una extincion es un
            // ABONO de concepto INSOLUTO, columna a columna el mismo que el de una
            // cobranza, asi que antes de #662 dejar una multa sin efecto publicaba sus
            // 428,00 como dinero que entro por ventanilla: hacia arriba y sin que nadie lo
            // note, que es la peor manera de equivocarse en esta cifra.
            assertThat(despues.total())
                    .as("una resolucion que deja la multa sin efecto no ingresa dinero")
                    .isEqualTo(antes.total());
            assertThat(despues.abonos())
                    .as("ni el recuento: no hubo un abono mas de cobranza, hubo una baja")
                    .isEqualTo(antes.abonos());
        }

        @Test
        @DisplayName("el resumen agrupa por estado, por codigo y por iniciales de placa")
        void losTresAgrupadores() {
            papeletaDeTransito("agr1");

            for (AgrupacionDelResumen agrupacion : AgrupacionDelResumen.values()) {
                ResumenDePapeletas resumen =
                        enTransaccion(
                                () ->
                                        consultaDeResumenes.resumir(
                                                CriterioDePadron.de(
                                                        Familia.TRANSITO,
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 12, 31)),
                                                agrupacion,
                                                LocalDate.of(2026, 4, 20)));

                assertThat(resumen.lineas()).as("agrupado por " + agrupacion).isNotEmpty();
                assertThat(resumen.total())
                        .as("las lineas suman el total, sea cual sea el agrupador")
                        .isEqualTo(
                                resumen.lineas().stream()
                                        .mapToLong(LineaDelResumen::cantidad)
                                        .sum());
            }
        }
    }

    // ==================================================================
    //  #549 — El frente de Transito del trabajo parado
    // ==================================================================

    @Nested
    @DisplayName("#549 — Papeletas impuestas y sin notificar: el frente de Transito")
    class ElFrenteDeTransito {

        @Test
        @DisplayName("AC 2.5 — sembrar dos papeletas sube el recuento en dos, y la suma con ellas")
        void sembrarDosSubeElRecuentoEnDos() {
            // Se mide el DELTA y no el total, a proposito: esta clase siembra papeletas en
            // muchas pruebas y un total absoluto dependeria del orden de ejecucion, que es
            // el defecto que #397 midio. El delta dice lo mismo y no se puede pisar.
            RecuentoDelPadron antes = contarElFrente();

            Papeleta una = papeletaDeTransito("frente1");
            Papeleta otra = papeletaDeTransito("frente2");

            RecuentoDelPadron despues = contarElFrente();

            assertThat(despues.cuantas()).isEqualTo(antes.cuantas() + 2);
            assertThat(despues.importe())
                    .as("y la suma sube con ellas: es la misma consulta la que cuenta y suma")
                    .isEqualTo(antes.importe().mas(una.importeAPagar()).mas(otra.importeAPagar()));
        }

        @Test
        @DisplayName("AC 2.4 — el recuento es el mismo total que la grilla del padron anuncia")
        void elRecuentoEsElMismoTotalQueLaGrilla() {
            papeletaDeTransito("frente3");

            long delFrente = contarElFrente().cuantas();
            long deLaGrilla =
                    enTransaccion(
                                    () ->
                                            consultaDePadrones.papeletas(
                                                    SIN_VALOR_EMITIDO,
                                                    Paginacion.de(0, 1, "fechaInfraccion")))
                            .totalElementos();

            assertThat(delFrente)
                    .as(
                            "si «lo que falta emitir» significara aqui una cosa y en el padron"
                                    + " otra, las dos cifras se contradirian y la del panel se lee"
                                    + " primero")
                    .isEqualTo(deLaGrilla);
        }

        @Test
        @DisplayName("una papeleta con su resolucion ya emitida deja de estar en el frente")
        void unaPapeletaConSuValorDejaDeEstar() {
            Papeleta conValor = papeletaExigible("frente4");
            RecuentoDelPadron antes = contarElFrente();

            generar.generar(corridaDe(conValor).identificador());

            assertThat(numerosDelPadron(SIN_VALOR_EMITIDO))
                    .as("el frente son las que ESPERAN el acto, no las que ya lo tienen")
                    .doesNotContain(conValor.numero());
            assertThat(contarElFrente().cuantas())
                    .as("y el recuento baja con ella")
                    .isEqualTo(antes.cuantas() - 1);
        }

        private RecuentoDelPadron contarElFrente() {
            // Por el PUERTO publico y no por el repositorio: lo que hay que sujetar no es
            // solo el SQL, es el criterio con que este modulo contesta al panel —familia
            // TRANSITO e IMPUESTA—, que es donde vive la decision.
            PapeletasSinNotificar puerto = new PapeletasSinNotificarSanciones(padron);
            PapeletasSinNotificar.PapeletasImpuestas impuestas =
                    enTransaccion(puerto::sinNotificar);
            return new RecuentoDelPadron(impuestas.cuantas(), impuestas.importe());
        }
    }

    // ==================================================================
    //  Los padrones, el prefijo por rango y la exportacion
    // ==================================================================

    @Nested
    @DisplayName("Los padrones, el prefijo por rango y los tres formatos de RF-132")
    class LosPadronesYSuExportacion {

        @Test
        @DisplayName("el padron de coactiva solo lista las que ya tienen su resolucion de multa")
        void elPadronDeCoactiva() {
            Papeleta conValor = papeletaExigible("pad1");
            Papeleta sinValor = papeletaDeTransito("pad2");
            generar.generar(corridaDe(conValor).identificador());

            List<String> numeros =
                    numerosDelPadron(
                            new CriterioDePadron(
                                    Familia.TRANSITO,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    Boolean.TRUE,
                                    false));

            assertThat(numeros).contains(conValor.numero()).doesNotContain(sinValor.numero());
        }

        @Test
        @DisplayName("el prefijo de placa se busca por rango, y el plan usa el indice")
        void elPrefijoVaPorRango() {
            Papeleta papeleta = papeletaDeTransito("pre1");
            String prefijo = papeleta.placa().substring(0, 2);

            List<String> numeros =
                    numerosDelPadron(
                            new CriterioDePadron(
                                    Familia.TRANSITO,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    prefijo,
                                    null,
                                    null,
                                    null,
                                    false));
            assertThat(numeros).contains(papeleta.numero());

            String plan = planDelPrefijo(prefijo);
            assertThat(plan)
                    .as("bajo RLS un LIKE no llega nunca al indice (DAT-01 §0, tercer hallazgo)")
                    .doesNotContain("~~");
            assertThat(plan).contains("~>=~");
        }

        @Test
        @DisplayName("el record vehicular trae solo las de esa placa")
        void elRecordVehicular() {
            Papeleta unVehiculo = papeletaDeTransito("rv1");
            Papeleta otroVehiculo = papeletaDeTransito("rv2");

            List<String> numeros =
                    numerosDelPadron(
                            new CriterioDePadron(
                                    Familia.TRANSITO,
                                    null,
                                    null,
                                    null,
                                    null,
                                    unVehiculo.placa(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    false));

            assertThat(numeros).contains(unVehiculo.numero()).doesNotContain(otroVehiculo.numero());
        }

        @Test
        @DisplayName("el padron sale en los tres formatos, y el RTF escapa lo no ASCII")
        void losTresFormatos() {
            Papeleta papeleta = papeletaDeTransito("rf132");
            Pagina<PapeletaDelPadron> pagina =
                    enTransaccion(
                            () ->
                                    consultaDePadrones.papeletas(
                                            CriterioDePadron.de(Familia.TRANSITO, null, null),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            ModeloDeDocumento modelo =
                    ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                            "Padron de papeletas de transito",
                            List.of(Campo.de("Titular", "PEÑA GARCÍA, JOSÉ")),
                            pagina,
                            LocalDate.of(2026, 4, 20));

            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                assertThat(generadorDeDocumentos.generar(modelo, formato))
                        .as("RF-132 promete los tres en todo reporte: " + formato)
                        .isNotEmpty();
            }

            String rtf =
                    new String(
                            generadorDeDocumentos.generar(modelo, FormatoDeDocumento.RTF),
                            StandardCharsets.ISO_8859_1);
            assertThat(rtf)
                    .as("«PEÑA GARCÍA» y no «PE?A GARC?A» en un documento oficial")
                    .contains("PE\\u209?A GARC\\u205?A");
            assertThat(papeleta.numero()).isNotBlank();
        }
    }

    // ==================================================================
    //  AC 7 — RLS y privilegios
    // ==================================================================

    @Nested
    @DisplayName("AC 7 — RLS y los privilegios de V47")
    class ElAislamiento {

        @Test
        @DisplayName("desde la municipalidad vecina, la corrida y la constancia no existen")
        void desdeLaVecinaNoExisten() {
            Papeleta papeleta = papeletaExigible("rls1");
            CorridaDeValores corrida = corridaDe(papeleta);
            enTransaccion(
                    () ->
                            emitirConstancia.emitir(
                                    peticionDe("RLS-001", LocalDate.of(2026, 4, 20)),
                                    FormatoDeDocumento.PDF,
                                    PORQUE));

            Optional<CorridaDeValores> desdeLaVecina =
                    enTransaccionDe(
                            otraMunicipalidad,
                            () -> corridas.porId(corrida.identificador()),
                            "vecino");
            Pagina<ConstanciaLibre> constanciasVecinas =
                    enTransaccionDe(
                            otraMunicipalidad,
                            () ->
                                    constancias.buscar(
                                            new CriterioDeConstancias(
                                                    null, null, null, null, "RLS-001"),
                                            Paginacion.de(0, 20, "fechaEmision")),
                            "vecino");

            assertThat(desdeLaVecina).isEmpty();
            assertThat(constanciasVecinas.contenido()).isEmpty();
        }

        @Test
        @DisplayName("kamayuk_app no puede editar el criterio de una corrida ni una constancia")
        void noSePuedeEditar() {
            Papeleta papeleta = papeletaExigible("rls2");
            CorridaDeValores corrida = corridaDe(papeleta);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE papeleta_masivo SET fecha_criterio ="
                                                            + " DATE '2020-01-01' WHERE id = "
                                                            + corrida.identificador())))
                    .as("V47 no le concede UPDATE: el criterio registrado no se corrige")
                    .isEqualTo("42501");

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE constancia_libre SET verificada_al ="
                                                            + " DATE '2020-01-01'")))
                    .as("una constancia se entrega: una equivocada se deja sin efecto con otra")
                    .isEqualTo("42501");
        }

        /**
         * V20 — de la papeleta solo se pueden mover `numero` y `estado` (#243).
         *
         * <p>Hasta V20 `kamayuk_app` tenia UPDATE sobre la TABLA: la placa, la hora, el lugar y el
         * importe a pagar —lo que el inspector escribio en la calle— se podian reescribir desde la
         * aplicacion. Se le deja `numero`, que es lo unico que este sistema escribe (#46), y
         * `estado`, que es la unica otra columna que un acto futuro puede mover.
         *
         * <p>Se comprueban las DOS direcciones en la misma prueba, y no es adorno: con solo la
         * mitad negativa, un `REVOKE UPDATE` sin el `GRANT` de vuelta —o sea dejar la papeleta sin
         * poder renumerarse— pasaria en verde.
         */
        @Test
        @DisplayName("de la papeleta solo se mueven `numero` y `estado`, no lo que se midio (#243)")
        void deLaPapeletaSoloSeMueveElNumero() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("priv1");

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE papeleta SET placa = 'ZZZ-999'"
                                                            + " WHERE id = "
                                                            + papeleta.identificador())))
                    .as("la placa la anoto el inspector: corregirla no es un UPDATE")
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE papeleta SET importe_a_pagar = 1"
                                                            + " WHERE id = "
                                                            + papeleta.identificador())))
                    .as("ni el importe del acta, que es lo que se cobra")
                    .isEqualTo("42501");

            ejecutarComoApp(
                    "UPDATE papeleta SET numero = 'PT-PRIV1-B' WHERE id = "
                            + papeleta.identificador());
        }

        @Test
        @DisplayName("y tampoco borrarlas: no hay DELETE en sanciones (regla 4)")
        void noSePuedenBorrar() {
            assertThat(estadoSqlDelFallo(() -> ejecutarComoApp("DELETE FROM constancia_libre")))
                    .isEqualTo("42501");
            assertThat(estadoSqlDelFallo(() -> ejecutarComoApp("DELETE FROM papeleta_masivo_item")))
                    .isEqualTo("42501");
        }
    }

    // ==================================================================
    //  #396 / #398 — el emisor, la hoja informativa y la agrupacion por ano
    // ==================================================================

    @Nested
    @DisplayName("#222 — «Notificadas» no consta, y lo que consta es la RESOLUCION notificada")
    class LoQueConstaDeLaNotificacion {

        /**
         * Las tres ramas del predicado, en una sola prueba y por delta.
         *
         * <p>El resumen es del padron entero del ejercicio, asi que una prueba por rama contaria
         * tambien las papeletas de las otras. Lo que se mide es lo que esta llamada anade, que es
         * lo unico que esta prueba controla.
         */
        @Test
        @DisplayName(
                "cuenta la que tiene su resolucion notificada, y NO la que no, ni la NO_UBICADO")
        void cuentaLaResolucionNotificada() {
            long antes = conResolucionNotificada();

            // 1. Una papeleta a secas: sin resolucion, no hay nada que notificar.
            papeletaDeTransito("cnot1");
            assertThat(conResolucionNotificada())
                    .as("una papeleta sin resolucion no tiene notificacion que constar")
                    .isEqualTo(antes);

            // 2. Con su ordinaria dictada y NOTIFICADA: esta es la que cuenta.
            papeletaExigible("cnot2");
            assertThat(conResolucionNotificada())
                    .as("la diligencia consta en `notificacion` con objeto = 'RESOLUCION'")
                    .isEqualTo(antes + 1);

            // 3. Dictada y diligenciada con NO_UBICADO: no surtio efecto, no cuenta.
            Papeleta noUbicada = papeletaDeTransito("cnot3");
            ResolverConResolucionDeGerencia.ResolucionDictada dictada = dictarOrdinaria(noUbicada);
            enTransaccion(
                    () ->
                            notificar.registrar(
                                    dictada.resolucion().numero(),
                                    new NotificarResolucionDeGerencia.Peticion(
                                            DILIGENCIA,
                                            ModalidadDeNotificacion.PERSONAL,
                                            ResultadoDeNotificacion.NO_UBICADO,
                                            "V. RETO SANTOS",
                                            "AV. JOSE DE LAMA 1180 - SULLANA",
                                            null,
                                            null,
                                            null,
                                            null),
                                    PORQUE),
                    "notificador");

            assertThat(conResolucionNotificada())
                    .as(
                            "NO_UBICADO es el unico resultado que no surte efecto, y es el unico"
                                    + " que se reintenta: contarlo diria que se notifico a quien no"
                                    + " se encontro")
                    .isEqualTo(antes + 1);
        }

        @Test
        @DisplayName("y NO es «papeleta.estado = NOTIFICADA», que nadie escribe nunca")
        void elEstadoNotificadaNoLoEscribeNadie() {
            papeletaExigible("cnot4");

            ResumenDePapeletas porEstado = resumenPorEstado();

            assertThat(porEstado.lineas())
                    .as(
                            "el unico `UPDATE papeleta` de src/main es `SET numero`: el estado se"
                                    + " escribe en el INSERT y siempre IMPUESTA, asi que la linea"
                                    + " NOTIFICADA no existe y contarla daria cero para siempre")
                    .noneMatch(linea -> "NOTIFICADA".equals(linea.clave()));
            assertThat(conResolucionNotificada())
                    .as("mientras que lo que SI consta se cuenta y es positivo")
                    .isPositive();
        }

        /** Lo que la linea IMPUESTA del resumen por estado dice de las resoluciones notificadas. */
        private long conResolucionNotificada() {
            return lineaDe(resumenPorEstado(), "IMPUESTA").conResolucionNotificada();
        }
    }

    @Nested
    @DisplayName("#398 — la agrupacion por ano y el total por mes")
    class ElAnoYElTotalPorMes {

        @Test
        @DisplayName("agrupado por ANO, la clave es el ano y la linea publica su ano")
        void agrupadoPorAno() {
            papeletaDeTransito("ano1");

            ResumenDePapeletas resumen = resumenAgrupadoPor(AgrupacionDelResumen.ANO);
            LineaDelResumen linea = lineaDe(resumen, "2026");

            assertThat(linea.ano())
                    .as("la columna «Ano» de transito_resumen_papeletas se dibuja con esto")
                    .isEqualTo(2026);
            assertThat(resumen.lineas()).allSatisfy(l -> assertThat(l.ano()).isNotNull());
        }

        @Test
        @DisplayName("agrupado por MES, la linea sigue diciendo de que ano es")
        void agrupadoPorMes() {
            papeletaDeTransito("ano2");

            LineaDelResumen linea =
                    lineaDe(resumenAgrupadoPor(AgrupacionDelResumen.MES), "2026-03");

            assertThat(linea.ano())
                    .as(
                            "'YYYY-MM' determina el ano; PostgreSQL no lo deduce, y por eso se"
                                    + " agrupa tambien por el")
                    .isEqualTo(2026);
        }

        @Test
        @DisplayName("agrupado por estado, codigo o placa el ano va NULO: el grupo mezcla anos")
        void sinAnoDeterminado() {
            papeletaDeTransito("ano3");

            for (AgrupacionDelResumen agrupacion :
                    List.of(
                            AgrupacionDelResumen.ESTADO,
                            AgrupacionDelResumen.CODIGO,
                            AgrupacionDelResumen.PLACA)) {

                assertThat(resumenAgrupadoPor(agrupacion).lineas())
                        .as("agrupado por " + agrupacion)
                        .isNotEmpty()
                        .allSatisfy(linea -> assertThat(linea.ano()).isNull());
            }
        }

        @Test
        @DisplayName("el resumen sigue cuadrando con el total, agrupe por lo que agrupe")
        void losCincoAgrupadoresCuadran() {
            papeletaDeTransito("ano4");

            for (AgrupacionDelResumen agrupacion : AgrupacionDelResumen.values()) {
                ResumenDePapeletas resumen = resumenAgrupadoPor(agrupacion);
                assertThat(resumen.lineas()).as("agrupado por " + agrupacion).isNotEmpty();
                assertThat(resumen.total())
                        .as("las lineas suman el total, sea cual sea el agrupador")
                        .isEqualTo(
                                resumen.lineas().stream()
                                        .mapToLong(LineaDelResumen::cantidad)
                                        .sum());
            }
        }

        @Test
        @DisplayName("el GET sin «agrupadoPor» agrupa por ANO: es la primera columna de su tabla")
        void elGetAgrupaPorAnoPorOmision() {
            papeletaDeTransito("ano5");

            ResumenDePapeletasResource resumen =
                    enTransaccion(() -> resumenesDeTransito.resumenDePapeletas(null, null, null));

            assertThat(resumen.agrupadoPor())
                    .as(
                            "con ESTADO —lo que hacia antes de #398— la columna «Año» se llenaria de"
                                    + " nombres de estado (RNF-080)")
                    .isEqualTo("ANO");
            assertThat(resumen.lineas())
                    .isNotEmpty()
                    .allSatisfy(linea -> assertThat(linea.ano()).isNotNull());
        }

        @Test
        @DisplayName("la recaudacion publica el total POR MES, sumado en el servidor")
        void elTotalPorMes() {
            Papeleta papeleta = papeletaExigible("mes1");
            cobrarIntegro(papeleta, "RECIBO 001-9200001");

            RecaudacionDeMultasResource recurso =
                    RecaudacionDeMultasResource.de(recaudacionDe2026());

            assertThat(recurso.porMes())
                    .as("la pantalla dibuja una fila por mes; sin esto «Total S/» no existe")
                    .isNotEmpty();
            for (RecaudacionDeMultasResource.LineaDeUnMes mes : recurso.porMes()) {
                Dinero suma = Dinero.CERO;
                for (RecaudacionDeMultasResource.PorFase fase : mes.porFase()) {
                    suma = suma.mas(fase.recaudado());
                }
                assertThat(mes.total())
                        .as(
                                "el total del mes es la suma de TODAS sus fases, no de las que se"
                                        + " dibujan")
                        .isEqualTo(suma);
                assertThat(mes.actualizadoA())
                        .as("toda cifra indica su fecha (RNF-075, regla 9)")
                        .isEqualTo(LocalDate.of(2026, 4, 20));
            }
        }

        @Test
        @DisplayName("y lo que suman los meses es exactamente el total general del libro")
        void losMesesSumanElTotalGeneral() {
            Papeleta papeleta = papeletaExigible("mes2");
            cobrarIntegro(papeleta, "RECIBO 001-9200002");

            RecaudacionDeMultasResource recurso =
                    RecaudacionDeMultasResource.de(recaudacionDe2026());

            Dinero suma = Dinero.CERO;
            for (RecaudacionDeMultasResource.LineaDeUnMes mes : recurso.porMes()) {
                suma = suma.mas(mes.total());
            }
            assertThat(suma)
                    .as("agrupar por mes no puede perder ni un centimo del total del libro")
                    .isEqualTo(recurso.total());
        }
    }

    @Nested
    @DisplayName("#396 — el emisor de reportes de transito")
    class ElEmisorDeReportes {

        @Test
        @DisplayName("un reporte que no existe se rechaza nombrando los nueve que si")
        void nombraLosNueve() {
            assertThatThrownBy(() -> emitir(peticionDelReporte("PADRON_DE_LO_QUE_SEA")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("PADRON_COACTIVA")
                    .hasMessageContaining("RECORD_VEHICULAR")
                    .hasMessageContaining("RESUMEN_PLACA");
        }

        @Test
        @DisplayName("un criterio que el reporte no usa se RECHAZA nombrandolo, no se ignora")
        void elCriterioDeMasSeRechaza() {
            PeticionDeReporteDeTransito conPlaca =
                    new PeticionDeReporteDeTransito(
                            "RESUMEN_RECAUDACION",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "P1T-234",
                            "2026",
                            null,
                            null,
                            null,
                            null);

            assertThatThrownBy(() -> emitir(conPlaca))
                    .as(
                            "pedir la recaudacion «de una placa» devolveria la de todas, y el papel"
                                    + " no lo diria")
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("placa")
                    .hasMessageContaining("RESUMEN_RECAUDACION");
        }

        @Test
        @DisplayName("un criterio en blanco no es una pregunta: la pantalla manda su formulario")
        void elCriterioEnBlancoNoEstorba() {
            papeletaDeTransito("emi1");

            PeticionDeReporteDeTransito conBlancos =
                    new PeticionDeReporteDeTransito(
                            "RESUMEN_RECAUDACION",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "2026",
                            "",
                            "",
                            "",
                            null);

            assertThat(cuerpoDe(emitir(conBlancos)).recaudacion()).isNotNull();
        }

        @Test
        @DisplayName("el padron sale con las papeletas de esta municipalidad")
        void elPadron() {
            Papeleta papeleta = papeletaDeTransito("emi2");

            ReporteDeTransitoResource reporte = cuerpoDe(emitir(peticionDelReporte("PADRON")));

            assertThat(reporte.reporte()).isEqualTo("PADRON");
            assertThat(reporte.papeletas()).isNotNull();
            assertThat(
                            reporte.papeletas().contenido().stream()
                                    .map(PapeletaDelPadronResource::numero)
                                    .toList())
                    .contains(papeleta.numero());
        }

        @Test
        @DisplayName("una papeleta de otra municipalidad no se emite: RLS la deja fuera")
        void elAislamientoDelEmisor() {
            Papeleta papeleta = papeletaDeTransito("emi3");

            ReporteDeTransitoResource desdeLaVecina =
                    cuerpoDe(
                            enTransaccionDe(
                                    otraMunicipalidad,
                                    () -> emisorDeReportes.emitir(peticionDelReporte("PADRON")),
                                    "vecino"));

            assertThat(desdeLaVecina.papeletas()).isNotNull();
            assertThat(
                            desdeLaVecina.papeletas().contenido().stream()
                                    .map(PapeletaDelPadronResource::numero)
                                    .toList())
                    .doesNotContain(papeleta.numero());
        }

        @Test
        @DisplayName("el resumen de papeletas del emisor agrupa por ANO, como su GET (#398)")
        void elResumenDelEmisorAgrupaPorAno() {
            papeletaDeTransito("emi4");

            ReporteDeTransitoResource reporte =
                    cuerpoDe(emitir(peticionDelReporte("RESUMEN_PAPELETAS")));

            assertThat(reporte.resumenDePapeletas()).isNotNull();
            assertThat(reporte.resumenDePapeletas().agrupadoPor()).isEqualTo("ANO");
            assertThat(reporte.resumenDePapeletas().lineas())
                    .isNotEmpty()
                    .allSatisfy(linea -> assertThat(linea.ano()).isNotNull());
        }

        @Test
        @DisplayName("un record sin sujeto sigue siendo el padron con otro titulo: 422")
        void elRecordSinSujeto() {
            assertThatThrownBy(() -> emitir(peticionDelReporte("RECORD_VEHICULAR")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("necesita la placa");
        }

        @Test
        @DisplayName("con formato, el emisor devuelve el documento y no el JSON")
        void conFormatoSaleElDocumento() {
            papeletaDeTransito("emi5");

            PeticionDeReporteDeTransito enPdf =
                    new PeticionDeReporteDeTransito(
                            "PADRON", null, null, null, null, null, null, null, null, null, null,
                            null, null, "PDF");

            Object cuerpo = enTransaccion(() -> emisorDeReportes.emitir(enPdf)).getBody();
            assertThat(cuerpo).isInstanceOf(byte[].class);
            assertThat((byte[]) cuerpo).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("#396 — la hoja informativa de una papeleta")
    class LaHojaInformativa {

        @Test
        @DisplayName("la hoja trae el acta con su desglose, y su fecha es la de la infraccion")
        void laHojaDeUnaPapeleta() {
            Papeleta papeleta = papeletaDeTransito("hoja1");

            HojaInformativaResource hoja =
                    enTransaccion(() -> hojaDePapeleta.hoja(papeleta.numero()));

            assertThat(hoja.numero()).isEqualTo(papeleta.numero());
            assertThat(hoja.importeAPagar()).isEqualTo(MULTA);
            assertThat(hoja.descripcionInfraccion()).isEqualTo("Infraccion de la prueba");
            assertThat(hoja.obligadoNombre()).isEqualTo("PEÑA GARCÍA, JOSÉ");
            assertThat(hoja.actualizadoA())
                    .as("los seis importes son los del acta: su fecha es la de la infraccion")
                    .isEqualTo(INFRACCION);
            assertThat(hoja.emitidaEl())
                    .as("y el dia en que sale la hoja va aparte, del reloj inyectado")
                    .isEqualTo(LocalDate.of(2026, 4, 20));
        }

        @Test
        @DisplayName("una papeleta que no existe responde NO_ENCONTRADO, no una hoja vacia")
        void laQueNoExiste() {
            assertThatThrownBy(() -> enTransaccion(() -> hojaDePapeleta.hoja("PT-NO-EXISTE")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .extracting(problema -> ((ProblemaDeNegocio) problema).codigo())
                    .isEqualTo(CodigoDeError.NO_ENCONTRADO);
        }

        @Test
        @DisplayName("y una de otra municipalidad tampoco se encuentra: RLS la deja fuera")
        void laDeLaVecina() {
            Papeleta papeleta = papeletaDeTransito("hoja2");

            assertThatThrownBy(
                            () ->
                                    enTransaccionDe(
                                            otraMunicipalidad,
                                            () -> hojaDePapeleta.hoja(papeleta.numero()),
                                            "vecino"))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .extracting(problema -> ((ProblemaDeNegocio) problema).codigo())
                    .isEqualTo(CodigoDeError.NO_ENCONTRADO);
        }

        @Test
        @DisplayName("la hoja sale en los tres formatos, con su pie y su punto de firma")
        void losTresFormatosDeLaHoja() {
            Papeleta papeleta = papeletaDeTransito("hoja3");

            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                byte[] archivo =
                        enTransaccion(
                                        () ->
                                                hojaDePapeleta.hojaComoDocumento(
                                                        papeleta.numero(), formato.name()))
                                .getBody();
                assertThat(archivo).as("RF-132 promete los tres: " + formato).isNotEmpty();
            }

            String rtf =
                    new String(
                            enTransaccion(
                                            () ->
                                                    hojaDePapeleta.hojaComoDocumento(
                                                            papeleta.numero(), "RTF"))
                                    .getBody(),
                            StandardCharsets.ISO_8859_1);
            assertThat(rtf)
                    .as("la hoja sale de la municipalidad, se firma y se archiva (RNF-084)")
                    .contains("Unidad responsable");
            assertThat(rtf)
                    .as("y dice a que fecha son sus importes")
                    .contains("Cifras al " + INFRACCION);
        }
    }

    // ==================================================================
    //  #267 — la papeleta con resolucion de multa emitida no se anula
    // ==================================================================

    /**
     * El equivalente exacto de {@code ActaConLiquidacionViva} (#214), y por el mismo motivo: anular
     * la papeleta cuya multa ya se formalizo dejaria ese valor —y quiza su expediente coactivo—
     * cobrando una sancion que no existe. {@code sanciones} no tiene ningun puerto para anular un
     * valor, asi que lo que hace es rechazar nombrando el valor que lo impide.
     *
     * <p>Las dos direcciones en la misma prueba, y no es adorno: con solo la mitad negativa, una
     * guarda que rechazara SIEMPRE —por ejemplo leyendo mal el estado del item— pasaria en verde.
     */
    @Nested
    @DisplayName("#267 — anular una papeleta que ya tiene resolucion de multa")
    class LaAnulacionContraLaCorrida {

        @Test
        @DisplayName("con la resolucion de multa emitida no se anula, y se dice cual lo impide")
        void conResolucionDeMultaNoSeAnula() {
            Papeleta papeleta = papeletaExigible("anul1");
            CorridaDeValores corrida = corridaDe(papeleta);
            generar.generar(corrida.identificador());

            String numeroDelValor = itemsDe(corrida).get(0).valorNumero();
            assertThat(numeroDelValor).as("la corrida tuvo que emitirlo de verdad").isNotNull();

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    anularPapeleta.anular(
                                                            Familia.TRANSITO,
                                                            papeleta.numero(),
                                                            EXIGIBLE_DESDE,
                                                            PORQUE)))
                    .isInstanceOf(AnularPapeleta.PapeletaConResolucionDeMulta.class)
                    .hasMessageContaining(numeroDelValor);

            assertThat(enTransaccion(() -> papeletas.porId(papeleta.identificador())))
                    .as("y no escribe nada: la papeleta se queda como estaba")
                    .get()
                    .extracting(Papeleta::estado)
                    .isEqualTo(EstadoDePapeleta.IMPUESTA);
        }

        @Test
        @DisplayName("sin resolucion de multa emitida si se anula")
        void sinResolucionDeMultaSiSeAnula() {
            Papeleta papeleta = papeletaDeTransito("anul2");

            AnularPapeleta.Anulada anulada =
                    enTransaccion(
                            () ->
                                    anularPapeleta.anular(
                                            Familia.TRANSITO,
                                            papeleta.numero(),
                                            EXIGIBLE_DESDE,
                                            PORQUE));

            assertThat(anulada.papeleta().estado()).isEqualTo(EstadoDePapeleta.ANULADA);
        }

        @Test
        @DisplayName("un candidato que NO PROCEDE no impide anular: no hay valor que lo sostenga")
        void unCandidatoQueNoProcedeNoImpideAnular() {
            Papeleta papeleta = papeletaDeTransito("anul3");
            CorridaDeValores corrida = corridaDe(papeleta);
            generar.generar(corrida.identificador());

            assertThat(itemsDe(corrida).get(0).estado())
                    .as("sin ordinaria dictada la corrida no lo formaliza")
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);

            assertThat(
                            enTransaccion(
                                            () ->
                                                    anularPapeleta.anular(
                                                            Familia.TRANSITO,
                                                            papeleta.numero(),
                                                            EXIGIBLE_DESDE,
                                                            PORQUE))
                                    .papeleta()
                                    .estado())
                    .isEqualTo(EstadoDePapeleta.ANULADA);
        }
    }

    // ==================================================================
    //  #371 — la corrida y la obligacion que dos papeletas comparten
    // ==================================================================

    /**
     * <b>La siembra que distingue (#371).</b> Los ayudantes de esta clase crean un contribuyente
     * por papeleta, y la prueba «las dos papeletas se formalizan» de {@code LaNumeracion} usa dos
     * obligados: con esa muestra, «formalizar la obligacion de la papeleta» y «formalizar la
     * papeleta» son lo mismo. Aqui el obligado es UNO, del mismo ejercicio y sin vehiculo del
     * padron: T-001 por 440 y T-002 por 220 en una sola obligacion del libro, con 660.
     */
    @Nested
    @DisplayName("#371 — la corrida no formaliza la multa de otra papeleta ni emite dos veces")
    class LaObligacionCompartida {

        private static final Dinero T001 = Dinero.de("440.00");
        private static final Dinero T002 = Dinero.de("220.00");
        private static final BigDecimal LAS_DOS = new BigDecimal("660.00");

        @Test
        @DisplayName("T-001 exigible y T-002 sin resolucion: la RM nunca es de 660")
        void laExigibleNoArrastraALaQueNoLoEs() {
            long obligado = crearContribuyente("oc1");
            Papeleta t001 = papeletaExigible("oc1-t001", obligado, T001);
            Papeleta t002 = papeletaDeTransito("oc1-t002", obligado, T002);

            CorridaDeValores corrida = corridaDe(t001, t002);
            generar.generar(corrida.identificador());

            assertThat(totalesDeLasRm(obligado))
                    .as(
                            "una RM por 660 formalizaria tambien la multa de T-002, que no tiene"
                                    + " ningun acto que ordene su cobranza")
                    .doesNotContain(LAS_DOS);
            assertThat(itemDe(corrida, t001))
                    .as("y el item de T-001 dice con que papeleta comparte la obligacion")
                    .satisfies(
                            item -> {
                                assertThat(item.estado())
                                        .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
                                assertThat(item.motivo()).contains(t002.numero());
                            });
        }

        @Test
        @DisplayName("las dos exigibles: nunca dos RM por lo mismo, y ORDINARIA nunca en negativo")
        void lasDosExigiblesNoSeFormalizanDosVeces() {
            long obligado = crearContribuyente("oc2");
            Papeleta t001 = papeletaExigible("oc2-t001", obligado, T001);
            Papeleta t002 = papeletaExigible("oc2-t002", obligado, T002);

            CorridaDeValores corrida = corridaDe(t001, t002);
            generar.generar(corrida.identificador());

            assertThat(totalesDeLasRm(obligado))
                    .as("dos titulos por la misma deuda, y coactiva admitiria los dos")
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(saldoEnOrdinaria(obligado))
                    .as("moverAValor por segunda vez deja ORDINARIA en -660")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(itemDe(corrida, t001).motivo())
                    .as("cada item dice con que papeleta comparte la obligacion")
                    .contains(t002.numero());
            assertThat(itemDe(corrida, t002).motivo()).contains(t001.numero());
        }

        @Test
        @DisplayName("una obligacion que ya no esta en ORDINARIA no se vuelve a formalizar")
        void laYaFormalizadaNoSeFormalizaOtraVez() {
            long obligado = crearContribuyente("oc3");
            Papeleta sola = papeletaExigible("oc3-t001", obligado, T001);
            // Otro valor ya formalizo esa obligacion: la llevo entera a VALOR.
            enTransaccion(
                    () ->
                            registrarValor.emitir(
                                    kamayuk.rentas.valores.dominio.TipoValor.RESOLUCION_DE_MULTA,
                                    obligado,
                                    List.of(
                                            new kamayuk.rentas.valores.dominio.SelectorDeObligacion(
                                                    "MULTA_TRANSITO",
                                                    new Ejercicio(2026),
                                                    null,
                                                    null)),
                                    PORQUE,
                                    EXIGIBLE_DESDE));

            CorridaDeValores corrida = corridaDe(sola);
            generar.generar(corrida.identificador());

            assertThat(totalesDeLasRm(obligado))
                    .as("la corrida no emite una segunda RM por la deuda que ya esta en VALOR")
                    .hasSize(1);
            assertThat(saldoEnOrdinaria(obligado))
                    .as("ni abona otra vez una ORDINARIA que ya no debe nada")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(itemsDe(corrida).get(0))
                    .as("y lo dice: NO_PROCEDE porque la obligacion ya salio de ORDINARIA")
                    .satisfies(
                            item -> {
                                assertThat(item.estado())
                                        .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
                                assertThat(item.motivo()).contains("ORDINARIA");
                            });
        }
    }

    // ==================================================================
    //  #372 — el valor vivo que sostiene la papeleta, lo emita quien lo emita
    // ==================================================================

    /**
     * <b>La siembra que distingue (#372).</b> Las pruebas de {@code LaAnulacionContraLaCorrida}
     * emiten la resolucion de multa <b>por la corrida</b>, que es el unico camino que deja la fila
     * {@code GENERADO} de {@code papeleta_masivo_item}: con esa muestra, «la corrida dejo un valor»
     * y «hay un valor vivo sobre la obligacion» son lo mismo. Aqui la RM sale de la emision
     * individual —{@code RegistrarValor.emitir}, el camino de {@code POST /api/v1/valores}— sobre
     * la obligacion de T-005 y <b>sin corrida</b>, que es exactamente el escenario del issue.
     *
     * <p>Y la gemela, con la misma RM ya {@code ANULADA}: sin ella, una guarda que preguntara «hubo
     * alguna vez un valor» pasaria la primera prueba y bloquearia para siempre una anulacion
     * legitima.
     */
    @Nested
    @DisplayName("#372 — la RM de la emision individual tambien impide anular la papeleta")
    class ElValorVivoFueraDeLaCorrida {

        private static final Dinero T005 = Dinero.de("440.00");

        @Test
        @DisplayName("una RM individual viva sobre su obligacion: 409 nombrando la RM")
        void laRmIndividualVivaImpideAnular() {
            long obligado = crearContribuyente("vv1");
            Papeleta t005 = papeletaDeTransito("vv1-t005", obligado, T005);
            String rm = emitirRmIndividual(obligado).numero();

            assertThat(cuantosValoresGeneradosDe(t005))
                    .as("la emision individual no deja fila en papeleta_masivo_item: sin corrida")
                    .isZero();

            Throwable rechazo = catchThrowable(() -> anularPorLaApi(t005));
            assertThat(rechazo)
                    .as(
                            "anular dejaria %s EMITIDA cobrando una sancion que ya no existe, y la"
                                    + " baja se llevaria los 440 que esa RM formaliza",
                            rm)
                    .isInstanceOfSatisfying(
                            ProblemaDeNegocio.class,
                            problema -> {
                                assertThat(problema.codigo()).isEqualTo(CodigoDeError.CONFLICTO);
                                assertThat(problema.codigo().estado().value()).isEqualTo(409);
                                assertThat(problema.getMessage()).contains(rm);
                            });

            assertThat(enTransaccion(() -> papeletas.porId(t005.identificador())))
                    .as("y no escribe nada: la papeleta se queda como estaba")
                    .get()
                    .extracting(Papeleta::estado)
                    .isEqualTo(EstadoDePapeleta.IMPUESTA);
        }

        @Test
        @DisplayName("la misma RM ya ANULADA no la sostiene: la papeleta se anula")
        void laRmAnuladaNoImpideAnular() {
            long obligado = crearContribuyente("vv2");
            Papeleta t005 = papeletaDeTransito("vv2-t005", obligado, T005);
            Valor rm = emitirRmIndividual(obligado);
            enTransaccion(
                    () ->
                            repositorioDeValores.cambiarEstado(
                                    java.util.Objects.requireNonNull(rm.id()),
                                    EstadoDeValor.ANULADO));

            assertThat(anularPorLaApi(t005).papeleta().estado())
                    .as("existio un valor, pero ya no hay ninguno vivo que la sostenga")
                    .isEqualTo(EstadoDePapeleta.ANULADA.name());
        }

        /** {@code POST /api/v1/transito/papeletas/{numero}/anulacion}, con su traduccion a 409. */
        private AnulacionDePapeletaController.PapeletaAnuladaResource anularPorLaApi(
                Papeleta papeleta) {
            return enTransaccion(
                    () ->
                            anulacion.anular(
                                    papeleta.numero(),
                                    new AnulacionDePapeletaController.PeticionDeAnulacion(
                                            "Error material en la placa",
                                            EXIGIBLE_DESDE.toString(),
                                            null)));
        }
    }

    // ==================================================================
    //  #495 — la resolucion que deja la multa sin efecto, con un valor vivo encima
    // ==================================================================

    /**
     * <b>El hermano de #372 (#495).</b> Anular la papeleta ya preguntaba a {@code valores} si un
     * valor vivo formaliza su multa; la resolucion de gerencia que la deja sin efecto no, y llegaba
     * al mismo estado final: la obligacion extinguida en el libro y la RM viva, cobrable en
     * coactiva sobre una deuda que ya no existe.
     *
     * <p>La siembra es la de {@link ElValorVivoFueraDeLaCorrida}: la RM sale de la emision
     * individual y no de la corrida, que es el camino que ninguna copia propia de {@code sanciones}
     * ve. Y lleva su gemela con la RM ya {@code ANULADA}: una guarda que preguntara «hubo alguna
     * vez un valor» pasaria la primera y bloquearia para siempre una resolucion fundada legitima.
     */
    @Nested
    @DisplayName("#495 — la resolucion que deja la multa sin efecto con un valor vivo encima")
    class LaResolucionContraElValorVivo {

        @Test
        @DisplayName("una RM viva sobre su obligacion: 409 nombrando la RM, y la deuda intacta")
        void laRmVivaImpideDejarLaMultaSinEfecto() {
            long obligado = crearContribuyente("sev1");
            Papeleta papeleta = papeletaDeTransito("sev1-t", obligado, MULTA);
            String rm = emitirRmIndividual(obligado).numero();
            SeleccionDeObligacion obligacion =
                    new SeleccionDeObligacion(
                            "MULTA_TRANSITO", new Ejercicio(2026), null, papeleta.vehiculoId());
            Dinero antes = loQueDebeAlDia(obligado, obligacion);
            assertThat(antes.esPositivo())
                    .as("la siembra tiene que deber algo, o «intacta» no distinguiria nada")
                    .isTrue();

            Throwable rechazo = catchThrowable(() -> dejarSinEfectoPorLaApi(papeleta, "EXP-SEV1"));

            assertThat(rechazo)
                    .as(
                            "dejarla sin efecto extinguiria los %s que %s formaliza, y %s seguiria"
                                    + " cobrandolos en coactiva",
                            MULTA, rm, rm)
                    .isInstanceOfSatisfying(
                            ProblemaDeNegocio.class,
                            problema -> {
                                assertThat(problema.codigo()).isEqualTo(CodigoDeError.CONFLICTO);
                                assertThat(problema.codigo().estado().value()).isEqualTo(409);
                                assertThat(problema.getMessage()).contains(rm);
                            });
            assertThat(loQueDebeAlDia(obligado, obligacion))
                    .as("y la deuda intacta: ni un asiento de baja")
                    .isEqualTo(antes);
            assertThat(resolucionesDe(papeleta))
                    .as(
                            "ni una resolucion que dice «sin efecto» sobre una multa que se sigue debiendo")
                    .isZero();
        }

        @Test
        @DisplayName("la misma RM ya ANULADA no la sostiene: la resolucion se dicta y da de baja")
        void laRmAnuladaNoImpideDejarLaMultaSinEfecto() {
            long obligado = crearContribuyente("sev2");
            Papeleta papeleta = papeletaDeTransito("sev2-t", obligado, MULTA);
            Valor rm = emitirRmIndividual(obligado);
            enTransaccion(
                    () ->
                            repositorioDeValores.cambiarEstado(
                                    java.util.Objects.requireNonNull(rm.id()),
                                    EstadoDeValor.ANULADO));

            ResolucionesDeGerenciaController.ResolucionResource dictada =
                    dejarSinEfectoPorLaApi(papeleta, "EXP-SEV2");

            assertThat(dictada.efectoSobreLaMulta())
                    .as("existio un valor, pero ya no hay ninguno vivo que la sostenga")
                    .isEqualTo("SE_DEJA_SIN_EFECTO");
            assertThat(dictada.asientosDeBaja())
                    .as("y la baja se asienta: la guarda no puede decir que no a todo")
                    .isPositive();
        }

        /** {@code POST /api/v1/transito/resoluciones/ordinaria}, fundada y con su traduccion. */
        private ResolucionesDeGerenciaController.ResolucionResource dejarSinEfectoPorLaApi(
                Papeleta papeleta, String expediente) {
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            expediente,
                                            INFRACCION.plusDays(2),
                                            TipoDeRecurso.DESCARGO,
                                            "El vehiculo estaba en el taller"),
                                    PORQUE),
                    "mesa.partes");
            return enTransaccion(
                    () ->
                            resolucionesPorLaApi.ordinaria(
                                    new ResolucionesDeGerenciaController.PeticionDeResolucion(
                                            "Se declara fundado el descargo",
                                            papeleta.numero(),
                                            ORDINARIA.toString(),
                                            expediente,
                                            "FUNDADO",
                                            "SE_DEJA_SIN_EFECTO",
                                            null,
                                            "Sustento de la prueba",
                                            null,
                                            null)),
                    "gerente");
        }

        private long resolucionesDe(Papeleta papeleta) {
            return enTransaccion(
                    () ->
                            jdbc.sql(
                                            "SELECT count(*) FROM resolucion_gerencia WHERE"
                                                    + " papeleta_id = :papeleta")
                                    .param("papeleta", papeleta.identificador())
                                    .query(Long.class)
                                    .single());
        }
    }

    // ==================================================================
    //  #384 — el bucle distingue un error de datos de uno pasajero
    // ==================================================================

    /**
     * #384, su tercer paso — Lo que el bucle hace con una excepción de persistencia.
     *
     * <p>Hasta #384 toda {@code DataAccessException} contaba como «fallido»: el candidato se
     * quedaba {@code PENDIENTE} para el próximo relanzamiento. Para un corte de conexión es lo
     * correcto; para {@code IncorrectResultSizeDataAccessException} no, porque sale de los datos de
     * <b>esa</b> papeleta y ningún reintento la arregla. Esa sale {@code NO_PROCEDE} con su
     * mensaje; la pasajera sigue contando como fallida; y un error del programa —una consulta mal
     * escrita, un permiso que falta— también, porque lo arregla un despliegue y no los datos, y
     * cerrarlo como «no procede» apagaría la alarma del proceso batch sobre todos los candidatos.
     *
     * <p>Los tres fallos se provocan con {@link ProcesarQueRevienta}: el defecto de datos que los
     * producía de verdad lo cierra la política de la corrida, así que ya no hay siembra que lo haga
     * salir.
     */
    @Nested
    @DisplayName("#384 — el bucle distingue un error de datos de uno pasajero")
    class ElErrorDeDatosNoSeReintenta {

        @Test
        @DisplayName(
                "el de datos sale NO_PROCEDE con su mensaje; el pasajero, el del programa y la"
                        + " restriccion violada, no")
        void elDeDatosNoProcedeYElPasajeroSigue() {
            Papeleta deDatos = papeletaExigible("err384a");
            Papeleta pasajero = papeletaExigible("err384b");
            Papeleta delPrograma = papeletaExigible("err384c");
            Papeleta sana = papeletaExigible("err384d");
            Papeleta restriccion = papeletaExigible("err384e");
            CorridaDeValores corrida = corridaDe(deDatos, pasajero, delPrograma, sana, restriccion);

            GenerarCorridaDeValores conFallos =
                    new GenerarCorridaDeValores(
                            envolver(new ConsultaDeLaCorridaDeValores(corridas)),
                            envolver(
                                    new ProcesarQueRevienta(
                                            Map.of(
                                                    deDatos.identificador(),
                                                    new IncorrectResultSizeDataAccessException(
                                                            1, 2),
                                                    pasajero.identificador(),
                                                    new TransientDataAccessResourceException(
                                                            "La conexion se corto a mitad"),
                                                    delPrograma.identificador(),
                                                    new BadSqlGrammarException(
                                                            "leer la papeleta",
                                                            "SELECT columna_que_no_existe",
                                                            new SQLException(
                                                                    "column does not exist",
                                                                    "42703")),
                                                    restriccion.identificador(),
                                                    new DataIntegrityViolationException(
                                                            "null value in column \"valor_id\""
                                                                    + " violates not-null"
                                                                    + " constraint",
                                                            new SQLException(
                                                                    "not-null violation",
                                                                    "23502"))))));
            GenerarCorridaDeValores.Informe informe = conFallos.generar(corrida.identificador());

            ItemDeCorrida itemDeDatos = itemDe(corrida, deDatos);
            assertThat(itemDeDatos.estado())
                    .as(
                            "reintentar una lectura que devuelve dos filas donde espera una no la arregla")
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(itemDeDatos.motivo())
                    .contains("Incorrect result size: expected 1, actual 2");
            assertThat(itemDe(corrida, pasajero).estado())
                    .as("un corte de conexion si lo arregla el relanzamiento")
                    .isEqualTo(EstadoDeItemDeCorrida.PENDIENTE);
            assertThat(itemDe(corrida, delPrograma).estado())
                    .as(
                            "una consulta mal escrita la arregla un despliegue, no los datos de esta papeleta")
                    .isEqualTo(EstadoDeItemDeCorrida.PENDIENTE);
            assertThat(itemDe(corrida, restriccion).estado())
                    .as(
                            "un NOT NULL violado sale tan a menudo de un defecto del codigo como de"
                                    + " los datos, y entonces revienta en todos: no se da por"
                                    + " resuelto")
                    .isEqualTo(EstadoDeItemDeCorrida.PENDIENTE);
            assertThat(itemDe(corrida, sana).estado()).isEqualTo(EstadoDeItemDeCorrida.GENERADO);
            assertThat(informe)
                    .as("generados, sin deuda, no proceden, fallidos")
                    .extracting(
                            GenerarCorridaDeValores.Informe::generados,
                            GenerarCorridaDeValores.Informe::sinDeuda,
                            GenerarCorridaDeValores.Informe::noProceden,
                            GenerarCorridaDeValores.Informe::fallidos)
                    .containsExactly(1, 0, 1, 3);
        }
    }

    /**
     * Un {@link ProcesarPapeletaDeLaCorrida} de verdad salvo en las papeletas que se le digan, que
     * revientan con el fallo dado antes de tocar nada (#384).
     *
     * <p>No es {@code final} ni privada: el proxy transaccional de la prueba es una subclase.
     */
    static class ProcesarQueRevienta extends ProcesarPapeletaDeLaCorrida {

        private final Map<Long, RuntimeException> fallos;

        ProcesarQueRevienta(Map<Long, RuntimeException> fallos) {
            super(
                    papeletas,
                    repositorioDeResoluciones,
                    repositorioDeDiligencias,
                    emisionDeMultas,
                    corridas);
            this.fallos = fallos;
        }

        @Override
        public Resultado procesar(
                CorridaDeValores corrida, ItemDeCorrida item, Observacion observacion) {
            RuntimeException fallo = fallos.get(item.papeletaId());
            if (fallo != null) {
                throw fallo;
            }
            return super.procesar(corrida, item, observacion);
        }
    }

    // ==================================================================
    //  #384 — varias resoluciones ADMINISTRATIVA sobre la misma papeleta
    // ==================================================================

    /**
     * #384 — La corrida administrativa sobre una papeleta con <b>dos</b> resoluciones {@code
     * ADMINISTRATIVA}.
     *
     * <p>Hasta #384 la corrida pedía «la» resolución del tipo que ordena la cobranza con una
     * consulta sin orden ni límite, y el esquema no garantiza una sola administrativa: la RIS y la
     * que resuelve su reconsideración son del mismo tipo, y un doble clic en «Emitir RIS» deja dos.
     * Con dos filas la lectura lanzaba {@code IncorrectResultSizeDataAccessException}, el bucle la
     * contaba como un fallo pasajero y el candidato se quedaba {@code PENDIENTE} en cada
     * relanzamiento.
     *
     * <h2>La siembra que distingue</h2>
     *
     * <p>La papeleta con <b>una sola</b> RIS —la única siembra que había— da verde con el código de
     * antes y con el de ahora: está como contraprueba, y no basta. Las fechas están escritas a mano
     * y no recalculadas con el código que se verifica: la RIS es firme el 1 de abril y la
     * resolución que resuelve la reconsideración el 15, así que una corrida al 14 separa «la
     * última» de «la primera».
     */
    @Nested
    @DisplayName("#384 — una papeleta administrativa con dos resoluciones ADMINISTRATIVA")
    class VariasResolucionesAdministrativas {

        /** El día de la RIS: jueves 5 de marzo. */
        private static final LocalDate RIS_DICTADA = LocalDate.of(2026, 3, 5);

        /** El día en que se diligencia la RIS: lunes 9 de marzo. */
        private static final LocalDate RIS_DILIGENCIADA = LocalDate.of(2026, 3, 9);

        /**
         * Desde cuándo es firme la RIS, con los quince días hábiles del recurso.
         *
         * <p>La diligencia es el lunes 9; surte efecto el martes 10; los quince días hábiles son
         * 11, 12, 13, 16, 17, 18, 19, 20, 23, 24, 25, 26, 27, 30 y 31; el plazo para impugnar vence
         * el martes 31 y la RIS es exigible el <b>miércoles 1 de abril</b>.
         */
        private static final LocalDate RIS_FIRME = LocalDate.of(2026, 4, 1);

        /** El día en que se presenta la reconsideración: martes 10 de marzo. */
        private static final LocalDate RECONSIDERACION = LocalDate.of(2026, 3, 10);

        /** El día en que se resuelve: lunes 16 de marzo. */
        private static final LocalDate RECONSIDERACION_RESUELTA = LocalDate.of(2026, 3, 16);

        /** El día en que se diligencia lo resuelto: lunes 23 de marzo. */
        private static final LocalDate RESUELTA_DILIGENCIADA = LocalDate.of(2026, 3, 23);

        /**
         * Desde cuándo es firme lo resuelto, con los mismos quince días.
         *
         * <p>La diligencia es el lunes 23; surte efecto el martes 24; los quince días hábiles son
         * 25, 26, 27, 30, 31, 1, 2, 3, 6, 7, 8, 9, 10, 13 y 14; el plazo vence el martes 14 de
         * abril y es exigible el <b>miércoles 15</b>, que es {@link #EXIGIBLE_DESDE}.
         */
        private static final LocalDate RESUELTA_FIRME = LocalDate.of(2026, 4, 15);

        @Test
        @DisplayName("la RIS y su reconsideracion INFUNDADA: GENERADO, y ningun fallido")
        void laReconsideracionInfundadaNoRevientaLaCorrida() {
            Papeleta papeleta = papeletaAdministrativa("adm384a");
            risNotificada(papeleta);
            reconsideracionResuelta(
                    papeleta,
                    "EXP-384A",
                    SentidoDelFallo.INFUNDADO,
                    EfectoSobreLaMulta.SE_MANTIENE);

            CorridaDeValores corrida = corridaAdministrativa(papeleta, RESUELTA_FIRME);
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.fallidos())
                    .as(
                            "dos ADMINISTRATIVA sobre la papeleta no son un fallo pasajero: con"
                                    + " la lectura de «la» resolucion del tipo, cada relanzamiento"
                                    + " reventaba igual")
                    .isZero();
            assertThat(itemsDe(corrida).get(0).estado())
                    .as("la multa firme recibe su resolucion de multa")
                    .isEqualTo(EstadoDeItemDeCorrida.GENERADO);
        }

        @Test
        @DisplayName("el plazo lo abre la notificacion de la ultima: al 14 de abril todavia corre")
        void elPlazoLoAbreLaNotificacionDeLaUltima() {
            Papeleta papeleta = papeletaAdministrativa("adm384b");
            risNotificada(papeleta);
            String resuelta =
                    reconsideracionResuelta(
                            papeleta,
                            "EXP-384B",
                            SentidoDelFallo.INFUNDADO,
                            EfectoSobreLaMulta.SE_MANTIENE);

            // La RIS es firme desde el 1 de abril; lo que resuelve la reconsideracion, desde el 15.
            CorridaDeValores corrida = corridaAdministrativa(papeleta, RESUELTA_FIRME.minusDays(1));
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.fallidos()).isZero();
            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado())
                    .as(
                            "con la RIS —la primera— la multa ya se habria formalizado, con el"
                                    + " plazo para apelar lo resuelto todavia abierto")
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo()).contains(resuelta).contains(RESUELTA_FIRME.toString());
        }

        @Test
        @DisplayName("si la ultima deja la multa sin efecto no se formaliza, y el motivo la nombra")
        void laQueLaDejaSinEfectoNoSeFormaliza() {
            Papeleta papeleta = papeletaAdministrativa("adm384c");
            risNotificada(papeleta);
            String fundada =
                    reconsideracionResuelta(
                            papeleta,
                            "EXP-384C",
                            SentidoDelFallo.FUNDADO,
                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);

            CorridaDeValores corrida = corridaAdministrativa(papeleta, RESUELTA_FIRME);
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.fallidos()).isZero();
            ItemDeCorrida item = itemsDe(corrida).get(0);
            assertThat(item.estado())
                    .as(
                            "no hay acto que ordene la cobranza: NO_PROCEDE diciendolo, y no un"
                                    + " SIN_DEUDA que solo cuenta que el libro esta en cero")
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo()).contains(fundada).contains("sin efecto");
            assertThat(item.valorId()).isNull();
        }

        @Test
        @DisplayName(
                "el doble clic en «Emitir RIS»: dos RIS del mismo dia, y la corrida no revienta")
        void elDobleClicNoRevientaLaCorrida() {
            Papeleta papeleta = papeletaAdministrativa("adm384d");
            risNotificada(papeleta);
            risNotificada(papeleta);

            CorridaDeValores corrida = corridaAdministrativa(papeleta, RESUELTA_FIRME);
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.fallidos()).isZero();
            assertThat(itemsDe(corrida).get(0).estado()).isEqualTo(EstadoDeItemDeCorrida.GENERADO);
        }

        @Test
        @DisplayName("contraprueba: con UNA sola RIS se formaliza, antes y despues de #384")
        void conUnaSolaRisSeFormaliza() {
            Papeleta papeleta = papeletaAdministrativa("adm384e");
            risNotificada(papeleta);

            CorridaDeValores corrida = corridaAdministrativa(papeleta, RIS_FIRME);
            GenerarCorridaDeValores.Informe informe = generar.generar(corrida.identificador());

            assertThat(informe.fallidos()).isZero();
            assertThat(itemsDe(corrida).get(0).estado()).isEqualTo(EstadoDeItemDeCorrida.GENERADO);
        }

        /** La RIS de la papeleta, dictada y notificada; devuelve su número. */
        private String risNotificada(Papeleta papeleta) {
            String numero =
                    dictarAdministrativa(papeleta, RIS_DICTADA, null, null, null)
                            .resolucion()
                            .numero();
            exigirFirmeza(numero, RIS_DILIGENCIADA, RIS_FIRME);
            return numero;
        }

        /**
         * La reconsideración contra la RIS, resuelta con ese fallo y notificada; devuelve el número
         * de la resolución que la resuelve.
         */
        private String reconsideracionResuelta(
                Papeleta papeleta,
                String expediente,
                SentidoDelFallo sentido,
                EfectoSobreLaMulta efecto) {
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.ADMINISTRATIVA,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            expediente,
                                            RECONSIDERACION,
                                            TipoDeRecurso.RECONSIDERACION,
                                            "La infraccion no se cometio en mi predio"),
                                    PORQUE),
                    "mesa.partes");
            String numero =
                    dictarAdministrativa(
                                    papeleta, RECONSIDERACION_RESUELTA, expediente, sentido, efecto)
                            .resolucion()
                            .numero();
            exigirFirmeza(numero, RESUELTA_DILIGENCIADA, RESUELTA_FIRME);
            return numero;
        }

        private ResolverConResolucionDeGerencia.ResolucionDictada dictarAdministrativa(
                Papeleta papeleta,
                LocalDate fecha,
                String expediente,
                SentidoDelFallo sentido,
                EfectoSobreLaMulta efecto) {
            return enTransaccion(
                    () ->
                            resolver.dictar(
                                    new ResolverConResolucionDeGerencia.Peticion(
                                            Familia.ADMINISTRATIVA,
                                            papeleta.numero(),
                                            TipoDeResolucionDeGerencia.ADMINISTRATIVA,
                                            fecha,
                                            expediente,
                                            sentido,
                                            efecto,
                                            null,
                                            "Sustento de la prueba",
                                            null),
                                    FormatoDeDocumento.PDF,
                                    PORQUE),
                    "gerente");
        }

        /**
         * Notifica la resolución y comprueba, contra la fecha escrita a mano, desde cuándo es
         * firme.
         */
        private void exigirFirmeza(String numero, LocalDate diligencia, LocalDate firme) {
            NotificarResolucionDeGerencia.Diligencia registrada =
                    enTransaccion(
                            () ->
                                    notificar.registrar(
                                            numero,
                                            new NotificarResolucionDeGerencia.Peticion(
                                                    diligencia,
                                                    ModalidadDeNotificacion.PERSONAL,
                                                    ResultadoDeNotificacion.NOTIFICADO,
                                                    "V. RETO SANTOS",
                                                    "AV. JOSE DE LAMA 1180 - SULLANA",
                                                    "RUIZ INGA, FERNANDO",
                                                    "DNI 10027723",
                                                    "REPRESENTANTE",
                                                    "CARGO-RIS"),
                                            PORQUE),
                            "notificador");
            assertThat(registrada.notificacion().exigibleDesde())
                    .as("la siembra cuenta los quince dias habiles de RG_RECURSO a mano")
                    .isEqualTo(firme);
        }

        private CorridaDeValores corridaAdministrativa(Papeleta papeleta, LocalDate fechaCriterio) {
            return enTransaccion(
                    () ->
                            iniciar.porSeleccion(
                                    Familia.ADMINISTRATIVA,
                                    List.of(papeleta.numero()),
                                    fechaCriterio,
                                    PORQUE));
        }

        private Papeleta papeletaAdministrativa(String sufijo) {
            String codigo = ("A-" + sufijo).toUpperCase(java.util.Locale.ROOT);
            crearCodigo(Familia.ADMINISTRATIVA, codigo);
            long obligado = crearContribuyente(sufijo);
            return enTransaccion(
                    () ->
                            registrarPapeleta.registrarAdministrativa(
                                    ("PA-" + sufijo).toUpperCase(java.util.Locale.ROOT),
                                    codigo,
                                    INFRACCION,
                                    null,
                                    "Av. Grau",
                                    obligado,
                                    null,
                                    null,
                                    obligado,
                                    Dinero.de("5350.00"),
                                    Alicuota.de("8"),
                                    MULTA,
                                    Alicuota.de("100"),
                                    MULTA,
                                    null,
                                    PORQUE));
        }
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    /**
     * Una RM por el camino de {@code POST /api/v1/valores}: sin corrida y sin tocar sanciones
     * (#372, #495).
     */
    private static Valor emitirRmIndividual(long obligado) {
        return enTransaccion(
                () ->
                        registrarValor.emitir(
                                TipoValor.RESOLUCION_DE_MULTA,
                                obligado,
                                List.of(
                                        new SelectorDeObligacion(
                                                "MULTA_TRANSITO", new Ejercicio(2026), null, null)),
                                PORQUE,
                                EXIGIBLE_DESDE));
    }

    private static EmitirConstanciaLibre.Peticion peticionDe(String placa, LocalDate verificadaAl) {
        return new EmitirConstanciaLibre.Peticion(
                placa, null, null, "SERNAQUE VILLEGAS, DORIS", verificadaAl);
    }

    private static RecaudadoEnElLibro recaudacionDe2026() {
        return enTransaccion(
                () ->
                        consultaDeResumenes.recaudacion(
                                Familia.TRANSITO,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 12, 31),
                                LocalDate.of(2026, 4, 20)));
    }

    private static ResumenDePapeletas resumenPorEstado() {
        return enTransaccion(
                () ->
                        consultaDeResumenes.resumir(
                                CriterioDePadron.de(
                                        Familia.TRANSITO,
                                        LocalDate.of(2026, 1, 1),
                                        LocalDate.of(2026, 12, 31)),
                                AgrupacionDelResumen.ESTADO,
                                LocalDate.of(2026, 4, 20)));
    }

    private static ResumenDePapeletas resumenAgrupadoPor(AgrupacionDelResumen agrupacion) {
        return enTransaccion(
                () ->
                        consultaDeResumenes.resumir(
                                CriterioDePadron.de(
                                        Familia.TRANSITO,
                                        LocalDate.of(2026, 1, 1),
                                        LocalDate.of(2026, 12, 31)),
                                agrupacion,
                                LocalDate.of(2026, 4, 20)));
    }

    /** Una peticion del emisor con solo el tipo de reporte: sin ningun criterio. */
    private static PeticionDeReporteDeTransito peticionDelReporte(String reporte) {
        return new PeticionDeReporteDeTransito(
                reporte, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    private static ResponseEntity<?> emitir(PeticionDeReporteDeTransito peticion) {
        return enTransaccion(() -> emisorDeReportes.emitir(peticion));
    }

    private static ReporteDeTransitoResource cuerpoDe(ResponseEntity<?> respuesta) {
        Object cuerpo = respuesta.getBody();
        assertThat(cuerpo)
                .as("sin «formato» el emisor devuelve el JSON de la union, no el documento")
                .isInstanceOf(ReporteDeTransitoResource.class);
        return (ReporteDeTransitoResource) cuerpo;
    }

    private static LineaDelResumen lineaDe(ResumenDePapeletas resumen, String clave) {
        return resumen.lineas().stream()
                .filter(linea -> linea.clave().equals(clave))
                .findFirst()
                .orElseThrow(() -> new AssertionError("El resumen no trae la linea " + clave));
    }

    private static List<String> numerosDelPadron(CriterioDePadron criterio) {
        Pagina<PapeletaDelPadron> pagina =
                enTransaccion(
                        () ->
                                consultaDePadrones.papeletas(
                                        criterio, Paginacion.de(0, 200, "fechaInfraccion")));
        return pagina.contenido().stream().map(PapeletaDelPadron::numero).toList();
    }

    private static void cobrarIntegro(Papeleta papeleta, String documento) {
        SeleccionDeObligacion obligacion =
                new SeleccionDeObligacion(
                        "MULTA_TRANSITO",
                        Ejercicio.de(papeleta.fechaInfraccion()),
                        null,
                        papeleta.vehiculoId());
        Dinero cobrado = loQueDebeAlDia(papeleta.obligadoId(), obligacion);
        enTransaccion(
                () ->
                        abonos.abonarPagoIntegro(
                                List.of(new ObligacionDelDeudor(papeleta.obligadoId(), obligacion)),
                                cobrado,
                                EXIGIBLE_DESDE,
                                documento,
                                PORQUE),
                "cajero");
    }

    /**
     * Lo que el libro dice que esa obligacion debe a {@code EXIGIBLE_DESDE}, que es lo que una
     * orden de cobro emitida ese dia habria congelado. Se pregunta al puerto, no se escribe a mano:
     * una cifra escrita a mano dejaria la comprobacion de #39 comparandose contra si misma.
     */
    private static Dinero loQueDebeAlDia(long contribuyenteId, SeleccionDeObligacion obligacion) {
        return enTransaccion(() -> deudas.todasDe(contribuyenteId, EXIGIBLE_DESDE), "cajero")
                .stream()
                .filter(
                        publica ->
                                publica.tributo().equals(obligacion.tributo())
                                        && publica.ejercicio().equals(obligacion.ejercicio())
                                        && java.util.Objects.equals(
                                                publica.vehiculoId(), obligacion.vehiculoId()))
                .map(ObligacionPublica::total)
                .reduce(Dinero.CERO, Dinero::mas);
    }

    private static long cuantasCorridas() {
        return enTransaccion(
                () -> jdbc.sql("SELECT count(*) FROM papeleta_masivo").query(Long.class).single());
    }

    private static CorridaDeValores corridaDe(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        iniciar.porSeleccion(
                                Familia.TRANSITO,
                                List.of(papeleta.numero()),
                                EXIGIBLE_DESDE,
                                PORQUE));
    }

    /** Una corrida sobre varias papeletas a la vez, con la fecha en que todas son exigibles. */
    private static CorridaDeValores corridaDe(Papeleta una, Papeleta... otras) {
        List<String> numeros = new java.util.ArrayList<>();
        numeros.add(una.numero());
        for (Papeleta otra : otras) {
            numeros.add(otra.numero());
        }
        return enTransaccion(
                () -> iniciar.porSeleccion(Familia.TRANSITO, numeros, EXIGIBLE_DESDE, PORQUE));
    }

    private static ItemDeCorrida itemDe(CorridaDeValores corrida, Papeleta papeleta) {
        return itemsDe(corrida).stream()
                .filter(item -> item.papeletaId() == papeleta.identificador())
                .findFirst()
                .orElseThrow();
    }

    /** El total de cada RM emitida al obligado, leido de la tabla y no del informe (#371). */
    private static List<BigDecimal> totalesDeLasRm(long obligado) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT monto_total FROM valor WHERE contribuyente_id ="
                                                + " :obligado AND tipo = 'RM' ORDER BY id")
                                .param("obligado", obligado)
                                .query(BigDecimal.class)
                                .list());
    }

    /** Lo que el libro dice que queda en ORDINARIA: cargos menos abonos de esa fase (#371). */
    private static BigDecimal saldoEnOrdinaria(long obligado) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT coalesce(sum(CASE WHEN tipo = 'CARGO' THEN monto"
                                                + " ELSE -monto END), 0) FROM"
                                                + " cuenta_corriente_asiento WHERE contribuyente_id"
                                                + " = :obligado AND fase = 'ORDINARIA'")
                                .param("obligado", obligado)
                                .query(BigDecimal.class)
                                .single());
    }

    private static List<ItemDeCorrida> itemsDe(CorridaDeValores corrida) {
        return enTransaccion(() -> corridas.items(corrida.identificador(), 0, 100));
    }

    private static List<String> numerosEmitidosDe(CorridaDeValores corrida) {
        return itemsDe(corrida).stream()
                .filter(item -> item.estado() == EstadoDeItemDeCorrida.GENERADO)
                .map(ItemDeCorrida::valorNumero)
                .map(numero -> java.util.Objects.requireNonNull(numero, "un GENERADO trae numero"))
                .sorted()
                .toList();
    }

    private static int ordinalDe(String numero) {
        return Integer.parseInt(numero.substring(numero.lastIndexOf('-') + 1));
    }

    private static String numeroDelValor(Long valorId) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT numero FROM valor WHERE id = :id")
                                .param("id", valorId)
                                .query(String.class)
                                .single());
    }

    private static long correlativoDe(String tipo, int ejercicio) {
        Long ultimo =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT coalesce(max(ultimo), 0) FROM"
                                                        + " valor_correlativo WHERE tipo = :tipo AND"
                                                        + " ejercicio = :ejercicio")
                                        .param("tipo", tipo)
                                        .param("ejercicio", ejercicio)
                                        .query(Long.class)
                                        .single());
        return ultimo == null ? 0 : ultimo;
    }

    private static long cuantosValoresGeneradosDe(Papeleta papeleta) {
        Long cuantos =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM papeleta_masivo_item"
                                                        + " WHERE papeleta_id = :papeleta AND estado ="
                                                        + " 'GENERADO'")
                                        .param("papeleta", papeleta.identificador())
                                        .query(Long.class)
                                        .single());
        return cuantos == null ? 0 : cuantos;
    }

    private static long cuantosValoresDeLaMulta(Papeleta papeleta) {
        Long cuantos =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM valor"
                                                        + " WHERE contribuyente_id = :contribuyente"
                                                        + "   AND tipo = 'RM'")
                                        .param("contribuyente", papeleta.obligadoId())
                                        .query(Long.class)
                                        .single());
        return cuantos == null ? 0 : cuantos;
    }

    /** El plan de la búsqueda por prefijo, para comprobar que no degrada a {@code LIKE}. */
    private static String planDelPrefijo(String prefijo) {
        List<String> lineas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "EXPLAIN SELECT p.id FROM papeleta p"
                                                        + " WHERE p.familia = 'TRANSITO'"
                                                        + "   AND p.placa ~>=~ :desde AND p.placa ~<~"
                                                        + " :hasta")
                                        .param("desde", prefijo)
                                        .param(
                                                "hasta",
                                                java.util.Objects.requireNonNull(
                                                        kamayuk.rentas.persistencia.RangoDePrefijo
                                                                .siguienteA(prefijo)))
                                        .query(String.class)
                                        .list());
        return String.join("\n", lineas);
    }

    /**
     * Una papeleta de tránsito con su cargo asentado en el libro.
     *
     * <p>El código va en mayúsculas porque el repositorio lo busca así: {@code CodigoInfraccion}
     * normaliza a mayúsculas al leer, y un código sembrado en minúsculas no se encuentra nunca.
     */
    /** El codigo de infraccion que {@link #papeletaDeTransito} le pone a esa papeleta. */
    private static String codigoDe(String sufijo) {
        return ("G-" + sufijo).toUpperCase(java.util.Locale.ROOT);
    }

    private static Papeleta papeletaDeTransito(String sufijo) {
        return papeletaDeTransito(sufijo, crearContribuyente(sufijo), MULTA);
    }

    /**
     * Una papeleta de tránsito <b>de un obligado que ya existe</b>, por el importe que se pida
     * (#371): es lo que deja sembrar dos papeletas en la misma obligación del libro.
     */
    private static Papeleta papeletaDeTransito(String sufijo, long obligado, Dinero multa) {
        String codigo = ("G-" + sufijo).toUpperCase(java.util.Locale.ROOT);
        crearCodigo(codigo);
        return enTransaccion(
                () ->
                        registrarPapeleta.registrarTransito(
                                ("PT-" + sufijo).toUpperCase(java.util.Locale.ROOT),
                                codigo,
                                INFRACCION,
                                null,
                                "Av. Grau",
                                placaDe(sufijo),
                                null,
                                "Q-" + sufijo,
                                null,
                                null,
                                obligado,
                                Dinero.de("5350.00"),
                                Alicuota.de("8"),
                                multa,
                                Alicuota.de("100"),
                                multa,
                                null,
                                PORQUE));
    }

    /** Una papeleta con su ordinaria dictada, notificada y con el plazo ya vencido. */
    private static Papeleta papeletaExigible(String sufijo) {
        return papeletaExigible(sufijo, crearContribuyente(sufijo), MULTA);
    }

    /** La misma, de un obligado que ya existe y por el importe que se pida (#371). */
    private static Papeleta papeletaExigible(String sufijo, long obligado, Dinero multa) {
        Papeleta papeleta = papeletaDeTransito(sufijo, obligado, multa);
        ResolverConResolucionDeGerencia.ResolucionDictada dictada = dictarOrdinaria(papeleta);
        NotificarResolucionDeGerencia.Diligencia diligencia =
                enTransaccion(
                        () ->
                                notificar.registrar(
                                        dictada.resolucion().numero(),
                                        new NotificarResolucionDeGerencia.Peticion(
                                                DILIGENCIA,
                                                ModalidadDeNotificacion.PERSONAL,
                                                ResultadoDeNotificacion.NOTIFICADO,
                                                "V. RETO SANTOS",
                                                "AV. JOSE DE LAMA 1180 - SULLANA",
                                                "RUIZ INGA, FERNANDO",
                                                "DNI 10027723",
                                                "REPRESENTANTE",
                                                "CARGO-RG"),
                                        PORQUE),
                        "notificador");
        if (!EXIGIBLE_DESDE.equals(diligencia.notificacion().exigibleDesde())) {
            throw new IllegalStateException(
                    "El plazo parametrizado no dio el "
                            + EXIGIBLE_DESDE
                            + " sino el "
                            + diligencia.notificacion().exigibleDesde());
        }
        return papeleta;
    }

    private static ResolverConResolucionDeGerencia.ResolucionDictada dictarOrdinaria(
            Papeleta papeleta) {
        return enTransaccion(
                () ->
                        resolver.dictar(
                                new ResolverConResolucionDeGerencia.Peticion(
                                        Familia.TRANSITO,
                                        papeleta.numero(),
                                        TipoDeResolucionDeGerencia.ORDINARIA,
                                        ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "Sustento de la prueba",
                                        null),
                                FormatoDeDocumento.PDF,
                                PORQUE),
                "gerente");
    }

    /**
     * La ordinaria que declara fundado el recurso y deja la multa sin efecto: es el unico camino
     * del sistema que llama a {@code ExtincionDeDeuda} (#50, RF-064).
     */
    private static ResolverConResolucionDeGerencia.ResolucionDictada dejarSinEfecto(
            Papeleta papeleta, String expediente) {
        enTransaccion(
                () ->
                        registrarDescargo.registrar(
                                Familia.TRANSITO,
                                papeleta.numero(),
                                new RegistrarDescargo.Peticion(
                                        expediente,
                                        INFRACCION.plusDays(2),
                                        TipoDeRecurso.DESCARGO,
                                        "El vehiculo estaba en el taller"),
                                PORQUE),
                "mesa.partes");
        return enTransaccion(
                () ->
                        resolver.dictar(
                                new ResolverConResolucionDeGerencia.Peticion(
                                        Familia.TRANSITO,
                                        papeleta.numero(),
                                        TipoDeResolucionDeGerencia.ORDINARIA,
                                        ORDINARIA,
                                        expediente,
                                        SentidoDelFallo.FUNDADO,
                                        EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO,
                                        null,
                                        "Sustento de la prueba",
                                        null),
                                FormatoDeDocumento.PDF,
                                PORQUE),
                "gerente");
    }

    private static String placaDe(String sufijo) {
        return "P"
                + Math.abs(sufijo.hashCode() % 9)
                + "T-"
                + Math.abs(sufijo.hashCode() % 900 + 100);
    }

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

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

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
     * El conjunto sellado de 2026 con los tres plazos que este archivo necesita.
     *
     * <p>Los días entran como <b>dato</b>, no como constante del programa (regla 5). Que esta
     * prueba tenga que sembrarlos es la demostración: sin el de la ordinaria, notificar la
     * resolución falla; sin el del descargo (#662), no se puede registrar el recurso que la
     * resolución que deja la multa sin efecto tiene que resolver; sin el del recurso (#384), la RIS
     * de una papeleta administrativa no se puede dictar ni notificar.
     */
    private static void crearConjuntoConElPlazo(long municipalidadId) throws SQLException {
        long ordinaria =
                cargarParametro(
                        "RG_ORDINARIA_CUMPLIMIENTO",
                        "7 DIAS_HABILES",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF");
        long descargo =
                cargarParametro(
                        "DESCARGO_PAPELETA",
                        "5 DIAS_HABILES",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF");
        // #384: lo que la RIS —y la resolucion que resuelve su reconsideracion— conceden para
        // impugnarlas. Con una cifra DISTINTA de la de la ordinaria, para que contar con la
        // llave equivocada de una fecha distinta y la prueba lo vea.
        long recurso =
                cargarParametro(
                        "RG_RECURSO",
                        "15 DIAS_HABILES",
                        "TUO de la Ley 27444, art. 218.2: plazo para interponer el recurso");

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
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id, conjunto_id,"
                                    + " parametro_id) VALUES (?, ?, ?)")) {
                for (long parametro : new long[] {ordinaria, descargo, recurso}) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO', fecha_sellado ="
                                    + " now(), usuario_sellado = 'siembra' WHERE id = ?")) {
                sentencia.setLong(1, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long cargarParametro(String clave, String valor, String fuente)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'PLAZO', ?, ?, DATE"
                                        + " '2026-01-01', ?, true, 'siembra') RETURNING id")) {
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
                        + "', 'NATURAL', 'PEÑA GARCÍA, JOSÉ', 'siembra') RETURNING id");
    }

    private static String dniDe(String codigo) {
        return "45" + Math.abs(codigo.hashCode() % 1000000 + 1000000);
    }

    private static void crearCodigo(String codigo) {
        crearCodigo(Familia.TRANSITO, codigo);
    }

    private static void crearCodigo(Familia familia, String codigo) {
        insertar(
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                        + municipalidad
                        + ", '"
                        + familia.name()
                        + "', '"
                        + codigo
                        + "', 'Infraccion de la prueba', 8.0000, 'D.S. 016-2009-MTC',"
                        + " DATE '2026-01-01') RETURNING id");
    }

    /** Un cargo suelto en el libro, para la emisión individual que comprueba la serie. */
    private static void cargoSuelto(long contribuyenteId, String tributo, Dinero importe) {
        enTransaccion(
                () -> {
                    jdbc.sql(
                                    "INSERT INTO cuenta_corriente_asiento (municipalidad_id,"
                                            + " contribuyente_id, tributo, ejercicio, periodo, fase,"
                                            + " tipo, concepto, monto, fecha_valor, documento_origen,"
                                            + " referencia_externa, usuario_id, motivo)"
                                            + " VALUES (current_setting('app.municipalidad_id')::bigint,"
                                            + " :contribuyente, :tributo, 2026, 0, 'ORDINARIA',"
                                            + " 'CARGO', 'INSOLUTO', :monto, DATE '2026-03-04',"
                                            + " 'SIEMBRA', 'SIEMBRA', 'siembra', 'cargo de prueba')")
                            .param("contribuyente", contribuyenteId)
                            .param("tributo", tributo)
                            .param("monto", importe.valor())
                            .update();
                    return null;
                });
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
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    /** El directorio de contribuyentes, leído de la misma base. */
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
            // Nada de esta prueba busca por nombre: el obligado sale de la papeleta.
            return List.of();
        }

        private static ResumenDeContribuyente mapear(ResultSet fila, int numeroDeFila)
                throws SQLException {
            return new ResumenDeContribuyente(
                    fila.getLong("id"),
                    fila.getString("codigo_contribuyente"),
                    fila.getString("nombre_razon_social"),
                    fila.getString("numero_documento"));
        }
    }
}
