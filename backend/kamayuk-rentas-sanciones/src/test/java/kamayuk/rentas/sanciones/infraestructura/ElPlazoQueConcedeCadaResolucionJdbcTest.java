package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.OrigenDeLaObligacionCuentaCorriente;
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
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeLaCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.GenerarCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.IniciarCorridaDeValores;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.ProcesarPapeletaDeLaCorrida;
import kamayuk.rentas.sanciones.aplicacion.RegistrarPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.EstadoDeItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.sanciones.infraestructura.web.ResolucionesDeGerenciaController;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import kamayuk.rentas.valores.aplicacion.EmisionDeValoresDeMultasValores;
import kamayuk.rentas.valores.aplicacion.RegistrarValor;
import kamayuk.rentas.valores.aplicacion.ValoresSobreUnaObligacionValores;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import kamayuk.rentas.web.ParametroQueFalta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * #410 — El plazo que abre una notificacion es el <b>del acto notificado</b>, contra PostgreSQL de
 * verdad y conectado como {@code kamayuk_app}.
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>La diligencia de <b>cualquier</b> resolucion de gerencia calculaba su exigibilidad con {@code
 * PLAZO:RG_ORDINARIA_CUMPLIMIENTO}, que es lo que la ordinaria de <b>transito</b> concede para
 * pagar. La RIS —la {@link TipoDeResolucionDeGerencia#ADMINISTRATIVA}, documento {@code RGA-…}— no
 * imprime ese plazo ni lo concede, y sin embargo su {@code exigible_desde} salia de el. Dos
 * sintomas, uno de hoy y uno latente:
 *
 * <ul>
 *   <li><b>Hoy</b>, con esa llave sin sellar, notificar la RIS contesta 422 nombrando una cifra de
 *       transito: el procedimiento administrativo no puede dejar constancia de su notificacion.
 *   <li><b>El dia que se selle</b> con siete dias habiles, la corrida {@code ADMINISTRATIVA} emite
 *       el RM al octavo dia habil, cuando la RIS todavia es impugnable (art. 218.2 del TUO de la
 *       Ley 27444: quince dias habiles), contra el art. 9.1 del TUO de la Ley 26979.
 * </ul>
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>El conjunto de la municipalidad principal lleva <b>las dos llaves con cifras distintas</b>
 * —siete para la ordinaria, quince para el recurso—: con la misma cifra en las dos, leer la llave
 * equivocada daria la misma fecha y la prueba pasaria en verde. Y la segunda municipalidad sella un
 * conjunto <b>sin</b> la llave de la ordinaria: es lo que hace visible el 422 de hoy, y la
 * contraprueba de que esa llave de verdad falta ahi —la ordinaria de esa misma municipalidad sigue
 * sin poder dictarse—.
 *
 * <p>Las fechas estan escritas a mano, y no recalculadas con el codigo que se verifica: una prueba
 * que derivara la fecha esperada de {@code Exigibilidad} no verificaria nada.
 */
@DisplayName("#410 — El plazo que abre una notificacion es el del acto notificado")
class ElPlazoQueConcedeCadaResolucionJdbcTest {

    /** El dia de la infraccion: miercoles 1 de julio de 2026. */
    private static final LocalDate INFRACCION = LocalDate.of(2026, 7, 1);

    /** El dia en que se dictan las resoluciones: viernes 31 de julio. */
    private static final LocalDate DICTADA = LocalDate.of(2026, 7, 31);

    /** El dia en que se diligencian: lunes 3 de agosto. */
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 8, 3);

    /**
     * Desde cuando se puede cobrar la multa de la <b>ordinaria</b>, con siete dias habiles.
     *
     * <p>La diligencia es el lunes 3; surte efecto el martes 4; los siete dias habiles son 5, 6, 7,
     * 10, 11, 12 y 13; el plazo vence el jueves 13 y la deuda es exigible el <b>viernes 14</b>.
     */
    private static final LocalDate EXIGIBLE_LA_ORDINARIA = LocalDate.of(2026, 8, 14);

    /**
     * Desde cuando es firme la <b>RIS</b>, con los quince dias habiles del recurso.
     *
     * <p>La misma cuenta: 5, 6, 7, 10, 11, 12, 13, 14, 17, 18, 19, 20, 21, 24 y 25; el plazo para
     * impugnar vence el martes 25 y la RIS es exigible el <b>miercoles 26</b>. Sin feriados
     * declarados, que es el calendario que trae el conjunto de esta siembra.
     */
    private static final LocalDate EXIGIBLE_LA_RIS = LocalDate.of(2026, 8, 26);

    private static final String SIETE_DIAS = "7 DIAS_HABILES";
    private static final String QUINCE_DIAS = "15 DIAS_HABILES";

    private static final Dinero MULTA = Dinero.de("428.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    /**
     * Hoy, pasada la firmeza de la RIS: ninguna fecha de la siembra es «posterior a hoy» (#402).
     */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;

    /** La que sella las dos llaves, con cifras distintas. */
    private static long conLasDos;

    /** La que sella la del recurso y NO la de la ordinaria: la del 422 de hoy. */
    private static long sinLaDeLaOrdinaria;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static RegistrarPapeleta registrarPapeleta;
    private static ResolverConResolucionDeGerencia resolver;
    private static NotificarResolucionDeGerencia notificar;
    private static ResolucionesDeGerenciaController porLaApi;
    private static IniciarCorridaDeValores iniciar;
    private static GenerarCorridaDeValores generar;
    private static CorridaDeValoresRepositoryJdbc corridas;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        conLasDos = crearMunicipalidad("250811", "Municipalidad de #410 con las dos llaves");
        sinLaDeLaOrdinaria = crearMunicipalidad("250812", "Municipalidad de #410 sin la ordinaria");

        long ordinaria =
                cargarParametro(
                        "RG_ORDINARIA_CUMPLIMIENTO",
                        SIETE_DIAS,
                        "Ordenanza de la prueba: plazo de pago de la ordinaria");
        long recurso =
                cargarParametro(
                        "RG_RECURSO",
                        QUINCE_DIAS,
                        "TUO de la Ley 27444, art. 218.2: plazo para interponer el recurso");
        sellarConjunto(conLasDos, ordinaria, recurso);
        sellarConjunto(sinLaDeLaOrdinaria, recurso);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        PapeletaRepositoryJdbc papeletas = new PapeletaRepositoryJdbc(jdbc);
        PadronDePapeletasRepositoryJdbc padron = new PadronDePapeletasRepositoryJdbc(jdbc);
        corridas = new CorridaDeValoresRepositoryJdbc(jdbc);
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
        ConsultaDeDeudaPublica deudas =
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

        DirectorioDeContribuyentes directorio = new PadronDeLaPrueba();
        ValorRepositoryJdbc repositorioDeValores = new ValorRepositoryJdbc(jdbc);
        RegistrarValor registrarValor =
                envolver(new RegistrarValor(repositorioDeValores, deudas, fases, auditoria, RELOJ));
        EmisionDeValoresDeMultas emision =
                envolver(
                        new EmisionDeValoresDeMultasValores(
                                registrarValor,
                                deudas,
                                envolver(
                                        new OrigenDeLaObligacionCuentaCorriente(
                                                asientos, saldos))));
        ValoresSobreUnaObligacion valoresVivos =
                envolver(new ValoresSobreUnaObligacionValores(repositorioDeValores));

        registrarPapeleta =
                envolver(
                        new RegistrarPapeleta(
                                papeletas,
                                new CodigoInfraccionRepositoryJdbc(jdbc),
                                cargos,
                                auditoria));
        resolver =
                envolver(
                        new ResolverConResolucionDeGerencia(
                                papeletas,
                                new DescargoRepositoryJdbc(jdbc),
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
        porLaApi = new ResolucionesDeGerenciaController(resolver, notificar);
        iniciar =
                envolver(
                        new IniciarCorridaDeValores(papeletas, padron, corridas, auditoria, RELOJ));
        ProcesarPapeletaDeLaCorrida procesar =
                envolver(
                        new ProcesarPapeletaDeLaCorrida(
                                papeletas,
                                resoluciones,
                                diligencias,
                                emision,
                                corridas,
                                new DescargoRepositoryJdbc(jdbc)));
        generar =
                new GenerarCorridaDeValores(
                        envolver(new ConsultaDeLaCorridaDeValores(corridas)), procesar);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    // ==================================================================
    //  La RIS: su plazo es el del recurso, no el de pago de la ordinaria
    // ==================================================================

    @Nested
    @DisplayName("Con las dos llaves selladas y cifras distintas (7 y 15)")
    class ConLasDosLlaves {

        @Test
        @DisplayName("la RIS notificada el 2026-08-03 es exigible el 2026-08-26, no el 08-14")
        void laRisEsExigibleCuandoVenceElRecurso() {
            Papeleta papeleta = papeletaAdministrativa(conLasDos, "ris1");
            String ris = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ADMINISTRATIVA);

            NotificarResolucionDeGerencia.Diligencia diligencia = notificar(conLasDos, ris);

            assertThat(diligencia.notificacion().exigibleDesde())
                    .as(
                            "la RIS concede el plazo para impugnarla (15 dias habiles), no el de"
                                    + " pago de la ordinaria de transito (7): con este ultimo la"
                                    + " fecha seria "
                                    + EXIGIBLE_LA_ORDINARIA)
                    .isEqualTo(EXIGIBLE_LA_RIS);
        }

        @Test
        @DisplayName("una corrida ADMINISTRATIVA al 2026-08-14 la deja NO_PROCEDE, con su fecha")
        void laCorridaNoEmiteElRmMientrasLaRisEsImpugnable() {
            Papeleta papeleta = papeletaAdministrativa(conLasDos, "ris2");
            String ris = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ADMINISTRATIVA);
            notificar(conLasDos, ris);

            CorridaDeValores corrida = corrida(conLasDos, papeleta, EXIGIBLE_LA_ORDINARIA);
            enTransaccionDe(conLasDos, () -> generar.generar(corrida.identificador()), "cajero");

            ItemDeCorrida item = itemDe(conLasDos, corrida);
            assertThat(item.estado())
                    .as(
                            "al 14 la RIS todavia se puede recurrir: emitir el RM la pondria camino"
                                    + " de la coactiva con el acto sin quedar firme (art. 9.1 del"
                                    + " TUO de la Ley 26979)")
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(item.motivo())
                    .as("y dice cuando vence, que es lo unico que quien opera puede hacer: esperar")
                    .contains(EXIGIBLE_LA_RIS.toString());
        }

        @Test
        @DisplayName("y al 2026-08-26 la misma corrida la formaliza: el plazo corre, no bloquea")
        void venceElRecursoYLaCorridaEmite() {
            Papeleta papeleta = papeletaAdministrativa(conLasDos, "ris3");
            String ris = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ADMINISTRATIVA);
            notificar(conLasDos, ris);

            CorridaDeValores corrida = corrida(conLasDos, papeleta, EXIGIBLE_LA_RIS);
            enTransaccionDe(conLasDos, () -> generar.generar(corrida.identificador()), "cajero");

            assertThat(itemDe(conLasDos, corrida).estado())
                    .as("es la contraprueba: sin ella, un NO_PROCEDE para siempre tambien pasaba")
                    .isEqualTo(EstadoDeItemDeCorrida.GENERADO);
        }

        @Test
        @DisplayName("la ordinaria de transito sigue concediendo sus 7 dias: exigible el 08-14")
        void laOrdinariaSigueConSuPlazoDePago() {
            Papeleta papeleta = papeletaDeTransito(conLasDos, "ord1");
            String ordinaria = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ORDINARIA);

            NotificarResolucionDeGerencia.Diligencia diligencia = notificar(conLasDos, ordinaria);

            assertThat(diligencia.notificacion().exigibleDesde())
                    .as(
                            "la ordinaria concede el plazo de PAGO: leer para todas la llave del"
                                    + " recurso daria el "
                                    + EXIGIBLE_LA_RIS)
                    .isEqualTo(EXIGIBLE_LA_ORDINARIA);
        }

        @Test
        @DisplayName("el papel de la RIS imprime «Plazo para impugnar» con la cifra que se cuenta")
        void elPapelDeLaRisDiceLoQueLaDiligenciaCuenta() {
            Papeleta papeleta = papeletaAdministrativa(conLasDos, "ris4");
            String ris = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ADMINISTRATIVA);

            String papel = datosDelDocumento(conLasDos, ris);

            assertThat(papel)
                    .as(
                            "la RIS salia sin ningun plazo impreso, y su diligencia contaba siete"
                                    + " dias habiles: el papel y la cuenta se contradecian")
                    .contains("Plazo para impugnar")
                    .contains(QUINCE_DIAS)
                    .doesNotContain("Plazo de pago")
                    .doesNotContain(SIETE_DIAS);
        }

        @Test
        @DisplayName("y el de la ordinaria sigue imprimiendo «Plazo de pago» con sus 7 dias")
        void elPapelDeLaOrdinariaSigueDiciendoPlazoDePago() {
            Papeleta papeleta = papeletaDeTransito(conLasDos, "ord2");
            String ordinaria = dictar(conLasDos, papeleta, TipoDeResolucionDeGerencia.ORDINARIA);

            assertThat(datosDelDocumento(conLasDos, ordinaria))
                    .contains("Plazo de pago")
                    .contains(SIETE_DIAS)
                    .doesNotContain("Plazo para impugnar")
                    .doesNotContain(QUINCE_DIAS);
        }
    }

    @Nested
    @DisplayName("Con la llave del recurso y SIN la de la ordinaria")
    class SinLaLlaveDeLaOrdinaria {

        @Test
        @DisplayName("notificar la RIS por la API contesta 201, no 422 por una cifra de transito")
        void notificarLaRisNoDependeDeUnaCifraDeTransito() {
            Papeleta papeleta = papeletaAdministrativa(sinLaDeLaOrdinaria, "ris5");
            String ris =
                    dictar(sinLaDeLaOrdinaria, papeleta, TipoDeResolucionDeGerencia.ADMINISTRATIVA);

            Throwable fallo =
                    catchThrowable(
                            () ->
                                    enTransaccionDe(
                                            sinLaDeLaOrdinaria,
                                            () ->
                                                    porLaApi.notificarAdministrativa(
                                                            ris, cuerpoDeLaDiligencia()),
                                            "notificador"));

            assertThat(fallo)
                    .as(
                            "la ruta declara @ResponseStatus(CREATED): sin excepcion es 201. Hoy"
                                    + " salia 422 con parametroQueFalta"
                                    + " PLAZO:RG_ORDINARIA_CUMPLIMIENTO — "
                                    + describir(fallo))
                    .isNull();
            assertThat(diligenciasDe(sinLaDeLaOrdinaria, ris))
                    .as("y la diligencia queda escrita, con la exigibilidad del recurso")
                    .containsExactly(EXIGIBLE_LA_RIS);
        }

        @Test
        @DisplayName("contraprueba: en esa municipalidad la ordinaria SI sigue pidiendo su llave")
        void laOrdinariaDeEsaMunicipalidadSigueSinPoderDictarse() {
            Papeleta papeleta = papeletaDeTransito(sinLaDeLaOrdinaria, "ord3");

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            sinLaDeLaOrdinaria,
                                            papeleta,
                                            TipoDeResolucionDeGerencia.ORDINARIA))
                    .as(
                            "la siembra dice lo que dice: esa municipalidad no tiene el plazo de"
                                    + " pago, y la ordinaria no se dicta con uno inventado")
                    .isInstanceOf(PlazosDeSancionesParametrizados.PlazoSinParametrizar.class)
                    .hasMessageContaining("PLAZO:RG_ORDINARIA_CUMPLIMIENTO");
        }
    }

    // ------------------------------------------------------------------

    private static ResolucionesDeGerenciaController.PeticionDeNotificacionDeResolucion
            cuerpoDeLaDiligencia() {
        return new ResolucionesDeGerenciaController.PeticionDeNotificacionDeResolucion(
                "Se registra la diligencia de la RIS",
                DILIGENCIA.toString(),
                "PERSONAL",
                "NOTIFICADO",
                "V. RETO SANTOS",
                "AV. JOSE DE LAMA 1180 - SULLANA",
                "RUIZ INGA, FERNANDO",
                "DNI 10027723",
                "REPRESENTANTE",
                "CARGO-RIS");
    }

    private static String describir(Throwable fallo) {
        if (fallo instanceof ProblemaDeNegocio problema) {
            return problema.codigo()
                    + " ("
                    + problema.codigo().estado().value()
                    + ") parametroQueFalta="
                    + problema.parametroQueFalta().map(ParametroQueFalta::llave).orElse(null)
                    + ": "
                    + problema.getMessage();
        }
        return String.valueOf(fallo);
    }

    private static String dictar(long tenant, Papeleta papeleta, TipoDeResolucionDeGerencia tipo) {
        return enTransaccionDe(
                        tenant,
                        () ->
                                resolver.dictar(
                                        new ResolverConResolucionDeGerencia.Peticion(
                                                papeleta.familia(),
                                                papeleta.numero(),
                                                tipo,
                                                DICTADA,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "Sustento de la prueba",
                                                null),
                                        FormatoDeDocumento.PDF,
                                        PORQUE),
                        "gerente")
                .resolucion()
                .numero();
    }

    private static NotificarResolucionDeGerencia.Diligencia notificar(long tenant, String numero) {
        return enTransaccionDe(
                tenant,
                () ->
                        notificar.registrar(
                                numero,
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
    }

    private static CorridaDeValores corrida(
            long tenant, Papeleta papeleta, LocalDate fechaCriterio) {
        return enTransaccionDe(
                tenant,
                () ->
                        iniciar.porSeleccion(
                                papeleta.familia(),
                                List.of(papeleta.numero()),
                                fechaCriterio,
                                PORQUE),
                "cajero");
    }

    private static ItemDeCorrida itemDe(long tenant, CorridaDeValores corrida) {
        List<ItemDeCorrida> items =
                enTransaccionDe(
                        tenant, () -> corridas.items(corrida.identificador(), 0, 100), "cajero");
        assertThat(items).as("la corrida se hizo sobre UNA papeleta").hasSize(1);
        return items.get(0);
    }

    /** Lo que el documento guardo para dibujarse: el papel que el administrado tiene en la mano. */
    private static String datosDelDocumento(long tenant, String numero) {
        return enTransaccionDe(
                tenant,
                () ->
                        jdbc.sql("SELECT datos::text FROM documento_emitido WHERE numero = :numero")
                                .param("numero", numero)
                                .query(String.class)
                                .single(),
                "consulta");
    }

    private static List<LocalDate> diligenciasDe(long tenant, String numeroDeResolucion) {
        return enTransaccionDe(
                tenant,
                () ->
                        jdbc.sql(
                                        "SELECT n.exigible_desde FROM notificacion n JOIN"
                                                + " resolucion_gerencia r ON r.id = n.objeto_id"
                                                + " AND r.municipalidad_id = n.municipalidad_id"
                                                + " WHERE n.objeto = 'RESOLUCION' AND r.numero ="
                                                + " :numero")
                                .param("numero", numeroDeResolucion)
                                .query(LocalDate.class)
                                .list(),
                "consulta");
    }

    private static Papeleta papeletaAdministrativa(long tenant, String sufijo) {
        String codigo = crearCodigo(tenant, Familia.ADMINISTRATIVA, "A-" + sufijo);
        long obligado = crearContribuyente(tenant, sufijo);
        return enTransaccionDe(
                tenant,
                () ->
                        registrarPapeleta.registrarAdministrativa(
                                ("PA-" + sufijo).toUpperCase(Locale.ROOT),
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
                                PORQUE),
                "inspector");
    }

    private static Papeleta papeletaDeTransito(long tenant, String sufijo) {
        String codigo = crearCodigo(tenant, Familia.TRANSITO, "G-" + sufijo);
        long obligado = crearContribuyente(tenant, sufijo);
        return enTransaccionDe(
                tenant,
                () ->
                        registrarPapeleta.registrarTransito(
                                ("PT-" + sufijo).toUpperCase(Locale.ROOT),
                                codigo,
                                INFRACCION,
                                null,
                                "Av. Grau",
                                ("P" + sufijo + "-410").toUpperCase(Locale.ROOT),
                                null,
                                "Q-" + sufijo,
                                null,
                                null,
                                obligado,
                                Dinero.de("5350.00"),
                                Alicuota.de("8"),
                                MULTA,
                                Alicuota.de("100"),
                                MULTA,
                                null,
                                PORQUE),
                "inspector");
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
     * Sella el conjunto de 2026 de esa municipalidad con los plazos dados, y con ninguno mas.
     *
     * <p>Los dias entran como <b>dato</b>, no como constante del programa (regla 5).
     */
    private static void sellarConjunto(long municipalidadId, long... parametros)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id,"
                                    + " ejercicio, version) VALUES (?, 2026, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                    + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                for (long parametro : parametros) {
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
        }
    }

    private static long cargarParametro(String clave, String valor, String fuente)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id,"
                                        + " tipo, clave, valor_texto, vigencia_desde,"
                                        + " documento_fuente, sellado, usuario_carga) VALUES"
                                        + " (NULL, 'PLAZO', ?, ?, DATE '2026-01-01', ?, true,"
                                        + " 'siembra') RETURNING id")) {
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

    private static long crearContribuyente(long tenant, String sufijo) {
        return insertar(
                tenant,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES ("
                        + tenant
                        + ", 'C-"
                        + sufijo
                        + "', 'DNI', '"
                        + ("45" + Math.abs(sufijo.hashCode() % 1000000 + 1000000))
                        + "', 'NATURAL', 'PEÑA GARCÍA, JOSÉ', 'siembra') RETURNING id");
    }

    private static String crearCodigo(long tenant, Familia familia, String codigo) {
        String enMayusculas = codigo.toUpperCase(Locale.ROOT);
        insertar(
                tenant,
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                        + tenant
                        + ", '"
                        + familia.name()
                        + "', '"
                        + enMayusculas
                        + "', 'Infraccion de la prueba', 8.0000, 'Ordenanza de la prueba',"
                        + " DATE '2026-01-01') RETURNING id");
        return enMayusculas;
    }

    private static long insertar(long tenant, String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, tenant);
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

    /** El directorio de contribuyentes, leido de la misma base. */
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
