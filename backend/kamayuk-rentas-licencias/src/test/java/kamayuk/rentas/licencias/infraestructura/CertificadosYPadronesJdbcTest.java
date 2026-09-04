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
import java.util.LinkedHashMap;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
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
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeCertificados;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.EmitirCertificado;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.aplicacion.MantenerCatalogoCiiu;
import kamayuk.rentas.licencias.aplicacion.ModeloDeLosReportesDeLicencias;
import kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias;
import kamayuk.rentas.licencias.dominio.CriterioDeCertificados;
import kamayuk.rentas.licencias.dominio.CriterioDeLicencias;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeCertificado;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.aplicacion.CobrosDeTasasTesoreria;
import kamayuk.rentas.tesoreria.aplicacion.RecibosDeTramiteTesoreria;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.infraestructura.CajaRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeReciboRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.RecaudacionRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.ReciboRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.TasaRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.TurnoDeCajaRepositoryJdbc;
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
 * #54 — Padrones, resumen anual y certificados contra PostgreSQL de verdad (V51), conectado como
 * {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>AC 1 — El padron refleja el estado A UNA FECHA.</b> Se emiten tres licencias con tres
 *       destinos distintos —una sin plazo, una que vence a mitad de año, una que se cancela en
 *       agosto— y se pide el mismo padron con tres fechas de corte. El estado y los tres recuentos
 *       cambian con la fecha, y ninguno con el reloj. El filtro por estado se resuelve en el motor,
 *       con la misma expresion que el resumen: contra un doble esto solo probaria que el doble
 *       recuerda lo que se le dijo.
 *   <li><b>AC 2 — La reimpresion es identica, con su numero original.</b> Se emite con el reloj en
 *       marzo de 2026 y se reimprime con <b>otro</b> reloj, en setiembre de 2027: el papel sigue
 *       diciendo la fecha de la emision. Es el defecto que #34 dejo documentado.
 *   <li><b>AC 3 — El derecho se comprueba contra {@code tesoreria} de verdad.</b> El recibo se
 *       cobra con {@code CobrarTasa} y se lee por los dos puertos publicos; un recibo anulado, uno
 *       de otro titular y uno de otro concepto no sirven, y sin el parametro sellado —el concepto o
 *       los meses de vigencia— la emision falla nombrando la llave.
 *   <li><b>AC 4 — Exportacion.</b> El padron, el resumen y el certificado salen en hoja de calculo
 *       y en texto enriquecido con la infraestructura de los trece reportes, y el RTF escapa lo que
 *       no es ASCII: «PEÑA GARCÍA» no puede acabar como «PE?A GARC?A» en un documento oficial.
 *   <li><b>AC 5 — RLS.</b> Ni el certificado ni el padron de A existen desde B.
 *   <li>Y el <b>certificado inmutable</b> de V51, comprobado por SQL directo, que es como se salta
 *       cualquier comprobacion escrita en Java.
 * </ul>
 */
@DisplayName("#54 — Certificados y padrones contra PostgreSQL")
class CertificadosYPadronesJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final LocalDate MEDIADOS_DE_2026 = LocalDate.of(2026, 7, 15);
    private static final LocalDate FIN_DE_2026 = LocalDate.of(2026, 12, 31);
    private static final LocalDate VENCE_EN_JUNIO = LocalDate.of(2026, 6, 30);
    private static final LocalDate SE_CANCELA_EN_AGOSTO = LocalDate.of(2026, 8, 1);
    private static final LocalDate EN_SETIEMBRE = LocalDate.of(2026, 9, 1);

    /** El reloj de la emision, y otro muy posterior para la reimpresion (AC 2). */
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final LocalDate MUCHO_DESPUES = LocalDate.of(2027, 9, 10);

    private static final Clock RELOJ_DE_2027 =
            Clock.fixed(MUCHO_DESPUES.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.certificados";
    private static final String CAJA = "C54";
    private static final String CAJERO = "cajera.54";
    private static final Observacion PORQUE = Observacion.de("Se emite para la prueba");

    /**
     * Los conceptos del TUPA <b>de la prueba</b>.
     *
     * <p>Son datos de prueba, no cifras normativas: entran a la base como parametros sellados y
     * ninguna linea de {@code src/main} los conoce. Cada municipalidad numera su TUPA como quiere.
     */
    private static final String DERECHO_LICENCIA = "LF-001";

    private static final String DERECHO_NUMERACION = "CN-001";
    private static final String DERECHO_ZONIFICACION = "CZ-001";
    private static final String DERECHO_PARAMETROS = "CP-001";
    private static final String OTRO_CONCEPTO = "COPIAS";

    /**
     * Los meses de vigencia <b>de la prueba</b>, que es lo que la regla 5 exige: la cifra vive en
     * el conjunto sellado y esta prueba tiene que sembrarla para que la emision funcione.
     *
     * <p>{@code PARAMETROS_URBANISTICOS} se siembra a proposito <b>sin</b> vigencia: es el tipo con
     * el que se comprueba que faltar el parametro no produce un certificado eterno.
     */
    private static final int MESES_DE_NUMERACION = 12;

    private static final int MESES_DE_ZONIFICACION = 36;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long areaId;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static CertificadoRepositoryJdbc certificados;
    private static LicenciaRepositoryJdbc licencias;

    private static CobrarTasa cobrarTasa;
    private static EmitirDocumento documentos;
    private static GeneradorDeDocumentos generador;
    private static MantenerCatalogoCiiu mantenerCatalogo;

    private static EmitirCertificado emitirCertificado;
    private static EmitirCertificado emitirEn2027;
    private static ConsultaDeCertificados consultaDeCertificados;

    private static EmitirLicenciaDeFuncionamiento emitirLicencia;
    private static CancelarLicencia cancelarLicencia;
    private static ConsultaDeLicencias consultaDeLicencias;
    private static ResumenAnualDeLicencias resumenAnual;

    private static final PrediosDeLaPrueba PREDIOS = new PrediosDeLaPrueba();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240540", "Municipalidad de los certificados");
        otraMunicipalidad = crearMunicipalidad("240541", "Municipalidad vecina de #54");
        sembrarLosConjuntos(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        certificados = new CertificadoRepositoryJdbc(jdbc);
        licencias = new LicenciaRepositoryJdbc(jdbc);
        MovimientoDeLicenciaRepositoryJdbc movimientos =
                new MovimientoDeLicenciaRepositoryJdbc(jdbc);
        DuplicadoDeLicenciaRepositoryJdbc duplicados = new DuplicadoDeLicenciaRepositoryJdbc(jdbc);
        CiiuRepositoryJdbc catalogo = new CiiuRepositoryJdbc(jdbc);

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
                                new TasaRepositoryJdbc(jdbc),
                                repositorioDeRecibos,
                                auditoria,
                                RELOJ));

        // Los DOS puertos publicos de tesoreria, de verdad. Es lo que hace que el AC 3 signifique
        // algo: `licencias` no toca la tabla `recibo` ni sabe que la anulacion vive en
        // `recibo_movimiento` desde #34.
        RecibosDeTramite recibos =
                envolver(new RecibosDeTramiteTesoreria(repositorioDeRecibos, movimientosDeRecibo));
        CobrosDeTasas cobros =
                envolver(
                        new CobrosDeTasasTesoreria(
                                repositorioDeRecibos,
                                movimientosDeRecibo,
                                new RecaudacionRepositoryJdbc(jdbc)));

        generador =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorXls(),
                                new RenderizadorRtf()),
                        RegimenDeLaInstalacion.REAL);
        DocumentoRepositoryJdbc repositorioDeDocumentos =
                new DocumentoRepositoryJdbc(
                        jdbc,
                        JsonMapper.builder()
                                .addModule(
                                        new kamayuk.rentas.web.ConfiguracionDeJson()
                                                .moduloDeObjetosDeValor())
                                .build());
        documentos =
                envolver(new EmitirDocumento(repositorioDeDocumentos, generador, auditoria, RELOJ));

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();
        DerechosDeTramiteParametrizados derechos =
                new DerechosDeTramiteParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

        emitirCertificado =
                envolver(
                        new EmitirCertificado(
                                certificados,
                                padron,
                                PREDIOS,
                                recibos,
                                cobros,
                                derechos,
                                documentos,
                                PlantillaDeNumeroDeCertificado.POR_OMISION,
                                auditoria,
                                RELOJ));
        // EL MISMO caso de uso con OTRO reloj, un año y medio despues. Es lo que hace demostrable
        // el AC 2: si la fecha del papel saliera del reloj de quien reimprime, este objeto la
        // cambiaria; como sale de los datos guardados, no la cambia.
        emitirEn2027 =
                envolver(
                        new EmitirCertificado(
                                certificados,
                                padron,
                                PREDIOS,
                                recibos,
                                cobros,
                                derechos,
                                envolver(
                                        new EmitirDocumento(
                                                repositorioDeDocumentos,
                                                generador,
                                                new AuditoriaJdbc(jdbc, RELOJ_DE_2027),
                                                RELOJ_DE_2027)),
                                PlantillaDeNumeroDeCertificado.POR_OMISION,
                                new AuditoriaJdbc(jdbc, RELOJ_DE_2027),
                                RELOJ_DE_2027));
        consultaDeCertificados = envolver(new ConsultaDeCertificados(certificados, padron));

        mantenerCatalogo = envolver(new MantenerCatalogoCiiu(catalogo, auditoria, RELOJ));
        emitirLicencia =
                envolver(
                        new EmitirLicenciaDeFuncionamiento(
                                licencias,
                                movimientos,
                                catalogo,
                                recibos,
                                padron,
                                (predioId, fecha) -> Optional.empty(),
                                derechos,
                                documentos,
                                PlantillaDeNumeroDeLicencia.POR_OMISION,
                                auditoria,
                                RELOJ));
        cancelarLicencia =
                envolver(
                        new CancelarLicencia(
                                licencias, movimientos, padron, documentos, auditoria, RELOJ));
        consultaDeLicencias =
                envolver(new ConsultaDeLicencias(licencias, movimientos, duplicados, padron));
        // SIN envolver: el resumen anual no abre transaccion propia a proposito. Ver su javadoc —y
        // la prueba del resumen de 2024 a 2026, que es la que lo demuestra—.
        resumenAnual = new ResumenAnualDeLicencias(consultaDeLicencias, cobros, derechos);

        areaId = crearArea(municipalidad, "A-54");
        crearCaja(municipalidad, CAJA, "S54", areaId);
        crearTasa(DERECHO_LICENCIA, Dinero.de("120.00"));
        crearTasa(DERECHO_NUMERACION, Dinero.de("25.00"));
        crearTasa(DERECHO_ZONIFICACION, Dinero.de("35.00"));
        crearTasa(DERECHO_PARAMETROS, Dinero.de("48.00"));
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
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — El padron refleja el estado A UNA FECHA, no «hoy»")
    class ElPadronALaFecha {

        @Test
        @DisplayName("las mismas tres licencias dan tres padrones distintos segun la fecha")
        void tresFechasTresPadrones() {
            long titular = crearContribuyente();
            String indefinida = emitirLicenciaDe(titular, null);
            String temporal = emitirLicenciaDe(titular, VENCE_EN_JUNIO);
            String cancelada = emitirLicenciaDe(titular, null);
            enContexto(
                    () ->
                            cancelarLicencia.cancelar(
                                    cancelada,
                                    SE_CANCELA_EN_AGOSTO,
                                    "Cese de actividades",
                                    FormatoDeDocumento.PDF,
                                    PORQUE));

            ConsultaDeLicencias.Padron enMarzo = padronDe(titular, null, HOY);
            assertThat(enMarzo.resumen().licencias()).isEqualTo(3);
            assertThat(enMarzo.resumen().vigentes()).as("en marzo las tres rigen").isEqualTo(3);
            assertThat(enMarzo.resumen().vencidas()).isZero();
            assertThat(enMarzo.resumen().canceladas()).isZero();
            assertThat(enMarzo.aLaFecha()).isEqualTo(HOY);

            ConsultaDeLicencias.Padron enJulio = padronDe(titular, null, MEDIADOS_DE_2026);
            assertThat(enJulio.resumen().vigentes())
                    .as("la temporal vencio el 30 de junio")
                    .isEqualTo(2);
            assertThat(enJulio.resumen().vencidas()).isEqualTo(1);
            assertThat(enJulio.resumen().canceladas())
                    .as("la cancelacion es de agosto: en julio todavia no cuenta")
                    .isZero();

            ConsultaDeLicencias.Padron enSetiembre = padronDe(titular, null, EN_SETIEMBRE);
            assertThat(enSetiembre.resumen().vigentes()).isEqualTo(1);
            assertThat(enSetiembre.resumen().vencidas()).isEqualTo(1);
            assertThat(enSetiembre.resumen().canceladas()).isEqualTo(1);

            assertThat(estadoEn(enSetiembre, indefinida)).isEqualTo(EstadoDeLicencia.VIGENTE);
            assertThat(estadoEn(enSetiembre, temporal)).isEqualTo(EstadoDeLicencia.VENCIDA);
            assertThat(estadoEn(enSetiembre, cancelada)).isEqualTo(EstadoDeLicencia.CANCELADA);
        }

        @Test
        @DisplayName("reimprimir el padron con su misma fecha da el mismo resultado")
        void reimprimirConSuFecha() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, VENCE_EN_JUNIO);

            ConsultaDeLicencias.Padron primera = padronDe(titular, null, HOY);
            ConsultaDeLicencias.Padron segunda = padronDe(titular, null, HOY);

            assertThat(segunda.resumen()).isEqualTo(primera.resumen());
            assertThat(segunda.aLaFecha()).isEqualTo(primera.aLaFecha());
        }

        @Test
        @DisplayName("el filtro por estado se resuelve en el motor y cuadra con su resumen")
        void elFiltroPorEstado() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, null);
            String temporal = emitirLicenciaDe(titular, VENCE_EN_JUNIO);

            ConsultaDeLicencias.Padron vencidas =
                    padronDe(titular, EstadoDeLicencia.VENCIDA, MEDIADOS_DE_2026);

            assertThat(vencidas.resumen().licencias())
                    .as("el resumen del padron filtrado cuenta lo mismo que su pagina")
                    .isEqualTo(1);
            assertThat(vencidas.resumen().vencidas()).isEqualTo(1);
            assertThat(vencidas.pagina().contenido())
                    .singleElement()
                    .satisfies(fila -> assertThat(fila.licencia().numero()).isEqualTo(temporal));

            ConsultaDeLicencias.Padron mismasVencidasEnMarzo =
                    padronDe(titular, EstadoDeLicencia.VENCIDA, HOY);
            assertThat(mismasVencidasEnMarzo.resumen().licencias())
                    .as("en marzo ninguna estaba vencida: el filtro depende de la fecha")
                    .isZero();
        }

        @Test
        @DisplayName("el resumen cuenta TODAS las del criterio, no las de la pagina")
        void elResumenNoEsLaPagina() {
            long titular = crearContribuyente();
            for (int i = 0; i < 3; i++) {
                emitirLicenciaDe(titular, null);
            }

            ConsultaDeLicencias.Padron padron =
                    enContexto(
                            () ->
                                    consultaDeLicencias.padron(
                                            CriterioDeLicencias.ninguno()
                                                    .conTitulares(Set.of(titular)),
                                            null,
                                            null,
                                            HOY,
                                            Paginacion.de(0, 1, "numero")));

            assertThat(padron.pagina().contenido()).as("la pagina trae una").hasSize(1);
            assertThat(padron.resumen().licencias())
                    .as("y el resumen cuenta las tres: contar la pagina daria un total falso (#25)")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("un titular que no existe devuelve nada, no el padron entero")
        void elTitularInexistente() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, null);

            ConsultaDeLicencias.Padron ninguno =
                    enContexto(
                            () ->
                                    consultaDeLicencias.padron(
                                            CriterioDeLicencias.ninguno(),
                                            "NO EXISTE ESTE NOMBRE",
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));

            assertThat(ninguno.resumen().licencias()).isZero();
            assertThat(ninguno.pagina().contenido()).isEmpty();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 2 — La reimpresion es identica, con su numero original")
    class LaReimpresion {

        @Test
        @DisplayName("reimprimir en 2027 sigue diciendo la fecha de la emision de 2026")
        void laFechaNoSaleDelReloj() {
            EmitirCertificado.Emision emitido = emitirCertificadoDe(TipoDeCertificado.NUMERACION);
            String numero = emitido.certificado().numero();

            EmitirCertificado.Emision reimpreso =
                    enContexto(
                            () -> emitirEn2027.reimprimir(numero, FormatoDeDocumento.RTF, PORQUE));

            String papel = texto(reimpreso);
            assertThat(papel)
                    .as("el papel dice de cuando es, y es de la emision (regla 9, #34)")
                    .contains("Datos al " + HOY);
            assertThat(papel)
                    .as("y NO del dia en que alguien pidio la reimpresion")
                    .doesNotContain("Datos al " + MUCHO_DESPUES);
            assertThat(papel).as("conserva el numero original del certificado").contains(numero);
            assertThat(reimpreso.certificado().numero()).isEqualTo(numero);
            assertThat(reimpreso.yaExistia()).isTrue();
        }

        @Test
        @DisplayName("la vigencia impresa es la que se calculo el dia de la emision")
        void laVigenciaImpresa() {
            EmitirCertificado.Emision emitido =
                    emitirCertificadoDe(TipoDeCertificado.ZONIFICACION_VIAS);
            LocalDate esperada = HOY.plusMonths(MESES_DE_ZONIFICACION);

            assertThat(emitido.certificado().vigenciaHasta()).isEqualTo(esperada);

            EmitirCertificado.Emision reimpreso =
                    enContexto(
                            () ->
                                    emitirEn2027.reimprimir(
                                            emitido.certificado().numero(),
                                            FormatoDeDocumento.RTF,
                                            PORQUE));
            assertThat(texto(reimpreso))
                    .as("la vigencia va copiada: no se recalcula con el TUPA de 2027")
                    .contains(esperada.toString());
        }

        @Test
        @DisplayName("sale marcada como duplicado, y las dos reimpresiones se numeran")
        void saleMarcada() {
            EmitirCertificado.Emision emitido = emitirCertificadoDe(TipoDeCertificado.NUMERACION);
            String numero = emitido.certificado().numero();

            EmitirCertificado.Emision primera =
                    enContexto(
                            () ->
                                    emitirCertificado.reimprimir(
                                            numero, FormatoDeDocumento.RTF, PORQUE));
            EmitirCertificado.Emision segunda =
                    enContexto(
                            () ->
                                    emitirCertificado.reimprimir(
                                            numero, FormatoDeDocumento.RTF, PORQUE));

            // El RTF se lee como US-ASCII, asi que el simbolo de grado viaja escapado: es la
            // misma comprobacion que la del apellido, y verla aqui recuerda que el escape no es
            // opcional en un formato que no admite mas que ASCII.
            assertThat(texto(primera)).contains("DUPLICADO N\\u176? 1");
            assertThat(texto(segunda))
                    .as("un duplicado sin marcar circula como si fuera el original")
                    .contains("DUPLICADO N\\u176? 2");
        }

        @Test
        @DisplayName("reimprimir uno que no existe dice que no existe")
        void elQueNoExiste() {
            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitirCertificado.reimprimir(
                                                            "CN-2026-999999",
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirCertificado.CertificadoInexistente.class);
        }

        @Test
        @DisplayName("el reintento con la misma clave devuelve el mismo certificado, sin papel")
        void elReintentoIdempotente() {
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);
            String recibo = cobrar(titular, DERECHO_NUMERACION);
            String clave = "IDEM-" + CONTADOR.incrementAndGet();

            EmitirCertificado.Emision primera =
                    enContexto(
                            () ->
                                    emitirCertificado.emitir(
                                            solicitud(
                                                    TipoDeCertificado.NUMERACION,
                                                    titular,
                                                    predio,
                                                    recibo),
                                            clave,
                                            FormatoDeDocumento.PDF,
                                            PORQUE));
            EmitirCertificado.Emision segunda =
                    enContexto(
                            () ->
                                    emitirCertificado.emitir(
                                            solicitud(
                                                    TipoDeCertificado.NUMERACION,
                                                    titular,
                                                    predio,
                                                    recibo),
                                            clave,
                                            FormatoDeDocumento.PDF,
                                            PORQUE));

            assertThat(segunda.yaExistia()).isTrue();
            assertThat(segunda.certificado().numero()).isEqualTo(primera.certificado().numero());
            assertThat(segunda.documento())
                    .as("un reintento no es un duplicado: no se dibuja ni se marca nada")
                    .isNull();
            assertThat(
                            filas(
                                    "SELECT count(*) FROM certificado WHERE clave_idempotencia = ?",
                                    clave))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("diez emisiones simultaneas con la misma clave dejan un solo certificado")
        // Cada hilo tiene que poder decir «a mi me rechazaron» sin importar por que excepcion.
        @SuppressWarnings("checkstyle:IllegalCatch")
        void diezEmisionesSimultaneas() throws Exception {
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);
            String recibo = cobrar(titular, DERECHO_NUMERACION);
            String clave = "CARRERA-" + CONTADOR.incrementAndGet();

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                try {
                                    enContexto(
                                            () ->
                                                    emitirCertificado.emitir(
                                                            solicitud(
                                                                    TipoDeCertificado.NUMERACION,
                                                                    titular,
                                                                    predio,
                                                                    recibo),
                                                            clave,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE));
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            assertThat(exitos)
                    .as("el reintento del cliente es legitimo: alguno tiene que entrar")
                    .isPositive();
            assertThat(
                            filas(
                                    "SELECT count(*) FROM certificado WHERE clave_idempotencia = ?",
                                    clave))
                    .as("una comprobacion en Java pasaria diez veces; el indice unico, una")
                    .isEqualTo(1);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 3 — El derecho, comprobado contra tesoreria")
    class ElDerecho {

        @Test
        @DisplayName("el certificado copia lo que el recibo cobro, con su fecha")
        void copiaElImporte() {
            EmitirCertificado.Emision emitido =
                    emitirCertificadoDe(TipoDeCertificado.ZONIFICACION_VIAS);

            assertThat(emitido.certificado().derecho())
                    .as("el importe sale de la caja, no del catalogo de hoy")
                    .isEqualTo(Dinero.de("35.00"));
            assertThat(emitido.certificado().derechoA())
                    .as("toda cifra dice a que fecha esta (regla 9)")
                    .isEqualTo(HOY);
        }

        @Test
        @DisplayName("sin recibo, con uno anulado, de otro titular o de otro concepto no se emite")
        void lasCuatroCausas() {
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);

            assertThatThrownBy(() -> emitirCon(titular, predio, ""))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("numero del recibo");

            String anulado = cobrar(titular, DERECHO_NUMERACION);
            anular(anulado);
            assertThatThrownBy(() -> emitirCon(titular, predio, anulado))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("anulado");

            long otro = crearContribuyente();
            String deOtro = cobrar(otro, DERECHO_NUMERACION);
            assertThatThrownBy(() -> emitirCon(titular, predio, deOtro))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining("otro contribuyente");

            String deOtraCosa = cobrar(titular, OTRO_CONCEPTO);
            assertThatThrownBy(() -> emitirCon(titular, predio, deOtraCosa))
                    .isInstanceOf(ComprobacionDelDerecho.DerechoNoPagado.class)
                    .hasMessageContaining(DERECHO_NUMERACION);
        }

        @Test
        @DisplayName("sin el concepto del TUPA sellado, la emision falla nombrando la llave")
        void sinConceptoSellado() {
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);
            String recibo = cobrar(titular, DERECHO_NUMERACION);

            // JURISDICCION no tiene NINGUNA de sus dos llaves en el conjunto sellado.
            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitirCertificado.emitir(
                                                            solicitud(
                                                                    TipoDeCertificado.JURISDICCION,
                                                                    titular,
                                                                    predio,
                                                                    recibo),
                                                            null,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(DerechosDeTramiteParametrizados.DerechoSinParametrizar.class)
                    .hasMessageContaining("DERECHO_CERTIFICADO_JURISDICCION");
        }

        @Test
        @DisplayName("con el concepto pero SIN los meses de vigencia, tambien falla y lo dice")
        void sinVigenciaSellada() {
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);
            String recibo = cobrar(titular, DERECHO_PARAMETROS);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    emitirCertificado.emitir(
                                                            solicitud(
                                                                    TipoDeCertificado
                                                                            .PARAMETROS_URBANISTICOS,
                                                                    titular,
                                                                    predio,
                                                                    recibo),
                                                            null,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .as("un certificado sin caducidad deja construir con los parametros de hoy")
                    .isInstanceOf(DerechosDeTramiteParametrizados.DerechoSinParametrizar.class)
                    .hasMessageContaining("VIGENCIA_CERTIFICADO_PARAMETROS_URBANISTICOS");
        }

        @Test
        @DisplayName("un predio que no es del solicitante no se certifica")
        void elPredioAjeno() {
            long titular = crearContribuyente();
            long otro = crearContribuyente();
            long predioDeOtro = crearPredioDe(otro);
            String recibo = cobrar(titular, DERECHO_NUMERACION);

            assertThatThrownBy(() -> emitirCon(titular, predioDeOtro, recibo))
                    .isInstanceOf(EmitirCertificado.PredioAjeno.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 4 — Exportacion a hoja de calculo y texto enriquecido (RF-132)")
    class LaExportacion {

        @Test
        @DisplayName("el padron sale en los tres formatos, con su fecha de corte dentro")
        void elPadronEnTresFormatos() {
            long titular = crearContribuyente();
            String numero = emitirLicenciaDe(titular, null);
            ConsultaDeLicencias.Padron padron = padronDe(titular, null, HOY);

            for (FormatoDeDocumento formato : FormatoDeDocumento.values()) {
                byte[] archivo =
                        generador.generar(
                                ModeloDeLosReportesDeLicencias.delPadron(padron), formato);
                assertThat(archivo).as("el padron en " + formato).isNotEmpty();
            }

            String hoja =
                    new String(
                            generador.generar(
                                    ModeloDeLosReportesDeLicencias.delPadron(padron),
                                    FormatoDeDocumento.XLS),
                            StandardCharsets.UTF_8);
            assertThat(hoja).contains(numero).contains(HOY.toString());
        }

        @Test
        @DisplayName("el RTF escapa lo que no es ASCII: «PEÑA GARCÍA» no sale «PE?A GARC?A»")
        void elRtfEscapa() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, null);
            ConsultaDeLicencias.Padron padron = padronDe(titular, null, HOY);

            String rtf =
                    new String(
                            generador.generar(
                                    ModeloDeLosReportesDeLicencias.delPadron(padron),
                                    FormatoDeDocumento.RTF),
                            StandardCharsets.US_ASCII);

            assertThat(rtf)
                    .as("la Ñ va como \\u209? y la Í como \\u205?, que es lo que Word entiende")
                    .contains("\\u209?")
                    .contains("\\u205?");
            assertThat(rtf)
                    .as("y el apellido no queda con interrogantes en un documento oficial")
                    .doesNotContain("PE?A");
        }

        @Test
        @DisplayName("el certificado sale en los tres formatos y el RTF escapa su direccion")
        void elCertificadoEnTresFormatos() {
            EmitirCertificado.Emision emitido =
                    emitirCertificadoDe(TipoDeCertificado.ZONIFICACION_VIAS);

            for (FormatoDeDocumento formato :
                    List.of(FormatoDeDocumento.XLS, FormatoDeDocumento.RTF)) {
                EmitirCertificado.Emision copia =
                        enContexto(
                                () ->
                                        emitirCertificado.reimprimir(
                                                emitido.certificado().numero(), formato, PORQUE));
                assertThat(java.util.Objects.requireNonNull(copia.documento()).contenido())
                        .as("el mismo certificado en " + formato)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("el resumen anual sale con su cifra o con su raya, nunca con un cero falso")
        void elResumenAnual() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, null);

            ResumenAnualDeLicencias.Resumen resumen =
                    enContexto(
                            () ->
                                    resumenAnual.entre(
                                            new Ejercicio(2024), new Ejercicio(2026), null, HOY));

            assertThat(resumen.filas()).hasSize(3);
            assertThat(resumen.filas().get(0).derechoDeTramite())
                    .as("2024 no tiene conjunto sellado: la cifra no se puede calcular")
                    .isNull();
            assertThat(resumen.filas().get(0).derechoNoDisponible())
                    .contains("2024")
                    .contains("sellado");
            assertThat(resumen.filas().get(1).derechoDeTramite())
                    .as("2025 tiene conjunto pero no el concepto del TUPA")
                    .isNull();
            assertThat(resumen.filas().get(1).derechoNoDisponible())
                    .contains("DERECHO_LICENCIA_FUNCIONAMIENTO");
            assertThat(resumen.filas().get(2).derechoDeTramite())
                    .as("2026 si: lo que la caja cobro por el derecho de licencia")
                    .isNotNull();
            assertThat(resumen.filas().get(2).emitidas()).isPositive();
            assertThat(resumen.filas().get(2).alCierre())
                    .as("el año en curso cierra en la fecha de corte, no el 31 de diciembre")
                    .isEqualTo(HOY);

            String hoja =
                    new String(
                            generador.generar(
                                    ModeloDeLosReportesDeLicencias.delResumen(resumen),
                                    FormatoDeDocumento.XLS),
                            StandardCharsets.UTF_8);
            assertThat(hoja)
                    .as("la raya y no un cero: un cero se leeria como «no se recaudo nada» (#48)")
                    .contains("—");
            assertThat(hoja).contains("2024").contains("2026");
        }

        @Test
        @DisplayName("un año ya cerrado cierra el 31 de diciembre, no en la fecha de corte")
        void elAnoCerrado() {
            ResumenAnualDeLicencias.Resumen resumen =
                    enContexto(
                            () ->
                                    resumenAnual.entre(
                                            new Ejercicio(2025), new Ejercicio(2025), null, HOY));

            assertThat(resumen.filas())
                    .singleElement()
                    .satisfies(
                            fila ->
                                    assertThat(fila.alCierre())
                                            .isEqualTo(LocalDate.of(2025, 12, 31)));
        }

        @Test
        @DisplayName("un intervalo al reves y uno demasiado largo se rechazan")
        void losIntervalosInvalidos() {
            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    resumenAnual.entre(
                                                            new Ejercicio(2026),
                                                            new Ejercicio(2024),
                                                            null,
                                                            HOY)))
                    .isInstanceOf(ResumenAnualDeLicencias.IntervaloInvalido.class);

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    resumenAnual.entre(
                                                            new Ejercicio(1990),
                                                            new Ejercicio(2026),
                                                            null,
                                                            HOY)))
                    .isInstanceOf(ResumenAnualDeLicencias.IntervaloInvalido.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("V51 — El certificado no se edita ni se borra (regla 4)")
    class Inmutabilidad {

        @Test
        @DisplayName("sgtm_app no puede corregir un certificado en el sitio")
        void noSePuedeEditar() {
            EmitirCertificado.Emision emitido = emitirCertificadoDe(TipoDeCertificado.NUMERACION);
            long id = emitido.certificado().identificador();

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE certificado SET direccion = 'OTRA' WHERE id = "
                                                    + id))
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE certificado SET vigencia_hasta ="
                                                    + " DATE '2099-12-31' WHERE id = "
                                                    + id))
                    .as("alargar la vigencia de un papel entregado es autorizar de mas")
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> ejecutar("DELETE FROM certificado WHERE id = " + id))
                    .hasStackTraceContaining("permission denied");
        }

        @Test
        @DisplayName("el repositorio se niega a reinsertar lo ya guardado")
        void noSeReinserta() {
            EmitirCertificado.Emision emitido = emitirCertificadoDe(TipoDeCertificado.NUMERACION);

            assertThatThrownBy(() -> enContexto(() -> certificados.emitir(emitido.certificado())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("se sustituye emitiendo otro");
        }

        @Test
        @DisplayName("cada emision deja su fila de auditoria con la observacion de quien la hizo")
        void laAuditoria() {
            Observacion propia = Observacion.de("Se emite por expediente 5400-2026");
            long titular = crearContribuyente();
            long predio = crearPredioDe(titular);
            String recibo = cobrar(titular, DERECHO_NUMERACION);

            EmitirCertificado.Emision emitido =
                    enContexto(
                            () ->
                                    emitirCertificado.emitir(
                                            solicitud(
                                                    TipoDeCertificado.NUMERACION,
                                                    titular,
                                                    predio,
                                                    recibo),
                                            null,
                                            FormatoDeDocumento.PDF,
                                            propia));

            assertThat(
                            unicoTexto(
                                    "SELECT observacion FROM auditoria WHERE tabla = 'certificado'"
                                            + " AND clave = ?",
                                    String.valueOf(emitido.certificado().identificador())))
                    .isEqualTo(propia.texto());
            assertThat(
                            unicoTexto(
                                    "SELECT datos_nuevos ->> 'vigenciaHasta' FROM auditoria"
                                            + " WHERE tabla = 'certificado' AND clave = ?",
                                    String.valueOf(emitido.certificado().identificador())))
                    .as("la traza dice hasta cuando valia el papel que se entrego")
                    .isEqualTo(HOY.plusMonths(MESES_DE_NUMERACION).toString());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 5 — Aislamiento")
    class Aislamiento {

        @Test
        @DisplayName("desde otra municipalidad, el certificado de A no existe")
        void elCertificadoDeANoExisteDesdeB() {
            EmitirCertificado.Emision emitido = emitirCertificadoDe(TipoDeCertificado.NUMERACION);
            String numero = emitido.certificado().numero();

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                Optional<ConsultaDeCertificados.CertificadoEnConsulta> desdeB =
                        transaccion.execute(
                                estado -> consultaDeCertificados.porNumero(numero, HOY));
                assertThat(desdeB).as("RLS: el certificado de A no existe desde B").isEmpty();

                Pagina<ConsultaDeCertificados.CertificadoEnConsulta> grilla =
                        transaccion.execute(
                                estado ->
                                        consultaDeCertificados.buscar(
                                                CriterioDeCertificados.ninguno(),
                                                null,
                                                HOY,
                                                Paginacion.de(0, 20, "numero")));
                assertThat(grilla).isNotNull();
                assertThat(grilla.totalElementos()).isZero();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
        }

        @Test
        @DisplayName("el padron y el resumen de B no cuentan las licencias de A")
        void elPadronDeBNoVeLasDeA() {
            long titular = crearContribuyente();
            emitirLicenciaDe(titular, null);

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                ConsultaDeLicencias.Padron desdeB =
                        transaccion.execute(
                                estado ->
                                        consultaDeLicencias.padron(
                                                CriterioDeLicencias.ninguno(),
                                                null,
                                                null,
                                                HOY,
                                                Paginacion.de(0, 20, "numero")));
                assertThat(desdeB).isNotNull();
                assertThat(desdeB.resumen().licencias())
                        .as("RLS: ningun reporte cruza municipalidades")
                        .isZero();

                Long certificadosDesdeB =
                        transaccion.execute(
                                estado ->
                                        jdbc.sql("SELECT count(*) FROM certificado")
                                                .query(Long.class)
                                                .single());
                assertThat(certificadosDesdeB).isZero();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
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

    private static String texto(EmitirCertificado.Emision emision) {
        return new String(
                java.util.Objects.requireNonNull(emision.documento()).contenido(),
                StandardCharsets.US_ASCII);
    }

    private static EstadoDeLicencia estadoEn(ConsultaDeLicencias.Padron padron, String numero) {
        return padron.pagina().contenido().stream()
                .filter(fila -> fila.licencia().numero().equals(numero))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El padron no trajo " + numero))
                .estado();
    }

    private static ConsultaDeLicencias.Padron padronDe(
            long titular, @Nullable EstadoDeLicencia estado, LocalDate aLaFecha) {
        return enContexto(
                () ->
                        consultaDeLicencias.padron(
                                CriterioDeLicencias.ninguno().conTitulares(Set.of(titular)),
                                null,
                                estado,
                                aLaFecha,
                                Paginacion.de(0, 20, "numero")));
    }

    private static String emitirLicenciaDe(long titular, @Nullable LocalDate vigenciaHasta) {
        String giro = "471" + String.format("%02d", CONTADOR.incrementAndGet() % 100);
        enContexto(
                () ->
                        mantenerCatalogo.registrar(
                                new MantenerCatalogoCiiu.Alta(
                                        giro,
                                        "COMERCIO DE LA PRUEBA",
                                        "G",
                                        RiesgoItse.BAJO,
                                        "CV",
                                        false),
                                PORQUE));
        String recibo = cobrar(titular, DERECHO_LICENCIA);
        return enContexto(
                        () ->
                                emitirLicencia.emitir(
                                        new EmitirLicenciaDeFuncionamiento.Solicitud(
                                                "C-" + titular,
                                                null,
                                                "BODEGA SAN MARTIN",
                                                "AV. GRAU 100",
                                                new AreaM2(new BigDecimal("45.50")),
                                                TipoDeLicencia.DEFINITIVA,
                                                "CV",
                                                20,
                                                HOY,
                                                vigenciaHasta,
                                                recibo,
                                                List.of(giro),
                                                giro,
                                                "EXP-54-" + CONTADOR.incrementAndGet(),
                                                HOY),
                                        FormatoDeDocumento.PDF,
                                        PORQUE))
                .licencia()
                .numero();
    }

    private static EmitirCertificado.Emision emitirCertificadoDe(TipoDeCertificado tipo) {
        long titular = crearContribuyente();
        long predio = crearPredioDe(titular);
        String concepto =
                tipo == TipoDeCertificado.NUMERACION ? DERECHO_NUMERACION : DERECHO_ZONIFICACION;
        String recibo = cobrar(titular, concepto);
        return enContexto(
                () ->
                        emitirCertificado.emitir(
                                solicitud(tipo, titular, predio, recibo),
                                null,
                                FormatoDeDocumento.RTF,
                                PORQUE));
    }

    private static EmitirCertificado.Emision emitirCon(long titular, long predio, String recibo) {
        return enContexto(
                () ->
                        emitirCertificado.emitir(
                                solicitud(TipoDeCertificado.NUMERACION, titular, predio, recibo),
                                null,
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static EmitirCertificado.Solicitud solicitud(
            TipoDeCertificado tipo, long titular, long predio, String recibo) {
        return new EmitirCertificado.Solicitud(
                tipo,
                "C-" + titular,
                PREDIOS.codigoDe(predio),
                "EXP-C54-" + CONTADOR.incrementAndGet(),
                HOY,
                recibo,
                new ParametrosUrbanisticos("RDM", "3 pisos", "30 %", "3 m", "1.5 (a+r)"));
    }

    /** Cobra el concepto en la caja de tasas <b>de verdad</b> y devuelve el numero del recibo. */
    private static String cobrar(long contribuyenteId, String concepto) {
        Recibo recibo =
                enContexto(
                        () ->
                                cobrarTasa.cobrar(
                                        new CobrarTasa.CobroDeTasas(
                                                CAJA,
                                                CAJERO,
                                                contribuyenteId,
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

    private static long filas(String sql, Object... parametros) {
        Long total =
                transaccion.execute(
                        estado -> {
                            var peticion = jdbc.sql(sql);
                            for (Object parametro : parametros) {
                                peticion = peticion.param(parametro);
                            }
                            return peticion.query(Long.class).single();
                        });
        return total == null ? 0L : total;
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
     * Los conjuntos sellados de la prueba, y lo que cada uno tiene <b>a proposito</b>.
     *
     * <ul>
     *   <li><b>2026</b>: el derecho de la licencia, los tres conceptos de certificado que la prueba
     *       usa y DOS de sus tres vigencias. Falta la de {@code PARAMETROS_URBANISTICOS} y falta
     *       entero {@code JURISDICCION}: son los dos casos con que se comprueba que la regla 5 no
     *       tiene valor por omision.
     *   <li><b>2025</b>: sellado y <b>vacio</b>. Es el año con el que el resumen anual demuestra
     *       que una recaudacion que no se puede calcular sale con su motivo y no con un cero (#48).
     *   <li><b>2024</b>: sin conjunto. El otro motivo por el que la cifra falta.
     * </ul>
     */
    private static void sembrarLosConjuntos(long municipalidadId) throws SQLException {
        long licencia = conceptoDelTupa("DERECHO_LICENCIA_FUNCIONAMIENTO", DERECHO_LICENCIA);
        long numeracion =
                conceptoDelTupa(TipoDeCertificado.NUMERACION.claveDelDerecho(), DERECHO_NUMERACION);
        long zonificacion =
                conceptoDelTupa(
                        TipoDeCertificado.ZONIFICACION_VIAS.claveDelDerecho(),
                        DERECHO_ZONIFICACION);
        long parametros =
                conceptoDelTupa(
                        TipoDeCertificado.PARAMETROS_URBANISTICOS.claveDelDerecho(),
                        DERECHO_PARAMETROS);
        long mesesDeNumeracion =
                vigenciaDelTupa(
                        TipoDeCertificado.NUMERACION.claveDeLaVigencia(), MESES_DE_NUMERACION);
        long mesesDeZonificacion =
                vigenciaDelTupa(
                        TipoDeCertificado.ZONIFICACION_VIAS.claveDeLaVigencia(),
                        MESES_DE_ZONIFICACION);

        sellarConjunto(
                municipalidadId,
                2026,
                licencia,
                numeracion,
                zonificacion,
                parametros,
                mesesDeNumeracion,
                mesesDeZonificacion);
        sellarConjunto(municipalidadId, 2025);
    }

    private static void sellarConjunto(long municipalidadId, int ejercicio, long... parametros)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, ejercicio);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : parametros) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                    + " WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long conceptoDelTupa(String clave, String codigo) throws SQLException {
        return cargarParametro(
                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave, valor_texto,"
                        + " vigencia_desde, documento_fuente, sellado, usuario_carga)"
                        + " VALUES (NULL, 'TUPA', ?, ?, DATE '2026-01-01',"
                        + " 'TUPA 2026 de la prueba', true, 'siembra') RETURNING id",
                clave,
                codigo);
    }

    private static long vigenciaDelTupa(String clave, int meses) throws SQLException {
        return cargarParametro(
                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave, valor_numerico,"
                        + " vigencia_desde, documento_fuente, sellado, usuario_carga)"
                        + " VALUES (NULL, 'VIGENCIA_CERTIFICADO', ?, ?::numeric,"
                        + " DATE '2026-01-01', 'TUPA 2026 de la prueba', true, 'siembra')"
                        + " RETURNING id",
                clave,
                String.valueOf(meses));
    }

    private static long cargarParametro(String sql, String clave, String valor)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia = carga.prepareStatement(sql)) {
            sentencia.setString(1, clave);
            sentencia.setString(2, valor);
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
                        + " 'PEÑA GARCÍA, LUIS', 'prueba') RETURNING id",
                municipalidad,
                "TMP-" + orden,
                String.format("%08d", 20_000_000 + orden));
    }

    /** Un predio real —para la clave foranea— y su titularidad, que vive en el doble. */
    private static long crearPredioDe(long contribuyenteId) {
        int orden = CONTADOR.incrementAndGet();
        String codigo = "200601010150010101" + String.format("%06d", orden);
        long predio =
                insertarComoApp(
                        "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                + " direccion, lote) VALUES (?, ?, 'URBANO', ?, '01') RETURNING id",
                        municipalidad,
                        codigo,
                        "AV. PEÑA GARCÍA " + orden);
        PREDIOS.con(contribuyenteId, predio, codigo, "AV. PEÑA GARCÍA " + orden);
        return predio;
    }

    private static long crearArea(long muni, String codigo) {
        return insertarComoOwner(
                muni,
                "INSERT INTO area (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Unidad de Rentas') RETURNING id",
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
                        + " DATE '2026-01-01', 'TUPA 2026 de la prueba') RETURNING id",
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

    /** El padron de la prueba: resuelve el codigo {@code C-<id>} al contribuyente sembrado. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            // Solo encuentra a alguien si se le busca por el nombre que siembra
            // `crearContribuyente`. Que pueda devolver la lista vacia es lo que hace demostrable
            // que un titular inexistente no traiga el padron entero.
            return texto.toUpperCase(java.util.Locale.ROOT).contains("PEÑA")
                    ? List.of(new ResumenDeContribuyente(1L, "C-1", "PEÑA GARCÍA, LUIS", "DNI 1"))
                    : List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (!codigo.startsWith("C-")) {
                return Optional.empty();
            }
            long id = Long.parseLong(codigo.substring(2));
            return Optional.of(
                    new ResumenDeContribuyente(id, codigo, "PEÑA GARCÍA, LUIS", "DNI 20000001"));
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            for (Long id : ids) {
                encontrados.put(
                        id,
                        new ResumenDeContribuyente(
                                id, "C-" + id, "PEÑA GARCÍA, LUIS", "DNI 20000001"));
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /**
     * El puerto de predios de {@code catastro}, como doble.
     *
     * <p>Es un doble y no el {@code PrediosDelContribuyenteCatastro} de verdad porque lo que esta
     * prueba necesita del padron catastral es <b>quien es titular de que predio</b>, y montar la
     * titularidad real —con sus versiones, sus porcentajes y su disparador diferido— es lo que #19
     * ya verifica en su propia prueba. Las filas de {@code predio} SI son reales: la clave foranea
     * de {@code certificado} las exige.
     */
    private static final class PrediosDeLaPrueba implements PrediosDelContribuyente {

        private final Map<Long, List<PredioDelContribuyente>> porTitular = new LinkedHashMap<>();
        private final Map<Long, String> codigos = new LinkedHashMap<>();

        void con(long contribuyenteId, long predioId, String codigo, String direccion) {
            porTitular
                    .computeIfAbsent(contribuyenteId, clave -> new ArrayList<>())
                    .add(
                            new PredioDelContribuyente(
                                    predioId,
                                    codigo,
                                    "URBANO",
                                    direccion,
                                    new Porcentaje(new BigDecimal("100.0000"))));
            codigos.put(predioId, codigo);
        }

        String codigoDe(long predioId) {
            return java.util.Objects.requireNonNull(codigos.get(predioId));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return porTitular.getOrDefault(contribuyenteId, List.of());
        }
    }
}
