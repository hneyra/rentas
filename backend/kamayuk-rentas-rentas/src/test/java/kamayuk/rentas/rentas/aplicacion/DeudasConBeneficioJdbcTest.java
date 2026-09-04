package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.beneficios.BaseDelBeneficio;
import kamayuk.rentas.web.ProblemaDeNegocio;
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

/**
 * #72 — La simulacion del acogimiento contra PostgreSQL de verdad, conectada como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>Que un conjunto ABIERTO no se lea.</b> Con la campana cargada pero el conjunto sin
 *       sellar, simular falla nombrando la llave: un descuento leido de un conjunto que todavia se
 *       puede corregir es una cifra que manana es otra, y el contribuyente ya se llevo la anterior
 *       (ADR-0007).
 *   <li><b>Que la campana sea de <i>esta</i> municipalidad.</b> {@code parametro_tributario} es
 *       catalogo —{@code municipalidad_id} nulo—, y lo que hace suya a una campana es que sea
 *       <b>su</b> conjunto el que la incluye. Desde la municipalidad vecina, la misma campana no
 *       existe.
 *   <li><b>Que la lectura de deuda tenga contexto de tenant.</b> Va por {@code
 *       ConsultaDeDeudaPublica}, que trae su propia transaccion; el caso de uso se envuelve en un
 *       proxy transaccional <b>de verdad</b> para que lo que se verifique sea el reparto real de
 *       anotaciones.
 *   <li><b>Que el anfitrion NO abra transaccion.</b> Es lo que hace que el ejercicio sin conjunto
 *       sellado —lo que ocurre hoy— devuelva una lista de campanas vacia en vez de reventar con
 *       {@code UnexpectedRollbackException}: la excepcion capturada dentro de una transaccion ajena
 *       la habria marcado <i>rollback-only</i> (#54).
 *   <li><b>Que el resumen se sume sobre todas las obligaciones y no sobre la pagina</b> (#25).
 * </ul>
 */
@DisplayName("#72 — La simulacion del acogimiento contra PostgreSQL")
class DeudasConBeneficioJdbcTest {

    /**
     * 2026: {@code cuenta_corriente_asiento} se particiona por ejercicio y V2 declara 2026/2027.
     */
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 8, 28);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Paginacion PAGINA =
            new Paginacion(0, 20, "ejercicio", Paginacion.Direccion.DESCENDENTE);

    /** Ninguna cifra de esta clase es normativa: son datos de prueba (regla 5, D-02b). */
    private static final String MITAD = "50";

    private static final String ESCALA = "2";
    private static final String MODO = "HALF_UP";

    /**
     * Las dos campanas del conjunto sellado de la municipalidad A.
     *
     * <p>Se siembran <b>una sola vez</b>, y no una por prueba, porque la base no deja hacer otra
     * cosa: {@code conjunto_sellado_uq} (V9) admite <b>un</b> conjunto sellado por ejercicio y el
     * disparador de inmutabilidad rechaza anadirle un parametro despues de sellarlo. Las dos
     * restricciones juntas son las que hacen reproducible una determinacion, y esta prueba se
     * adapta a ellas en vez de esquivarlas.
     */
    private static final String CAMPANIA_TOTAL = "AMNISTIA TOTAL DE PRUEBA";

    private static final String CAMPANIA_INSOLUTO = "PRONTO PAGO DE PRUEBA";
    private static final String CAMPANIA_ABIERTA = "AMNISTIA SIN SELLAR";

    private static BaseDeDatosDePrueba base;
    private static JdbcClient jdbc;

    /** A: con su conjunto sellado y sus dos campanas. */
    private static long municipalidad;

    /** B: la vecina. Tiene un conjunto ABIERTO, que es como no tener ninguna campana. */
    private static long otraMunicipalidad;

    /** C: sin ningun conjunto. Es el estado de <b>todas</b> las municipalidades hoy. */
    private static long sinConjunto;

    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;

    private static SimularAcogimiento simulacion;
    private static RegistrarAsiento registrarAsiento;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260401", "Municipalidad del acogimiento");
        otraMunicipalidad = crearMunicipalidad("260402", "Municipalidad vecina");
        sinConjunto = crearMunicipalidad("260403", "Municipalidad sin parametros sellados");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));

        // La misma politica de mora que cablea la aplicacion: sin acumulacion. Lo que esta prueba
        // mide es la simulacion del acogimiento, no una regla de calculo (bloqueada por D-02).
        ConsultarDeuda consultarDeuda =
                envolver(
                        new ConsultarDeuda(
                                asientos,
                                saldos,
                                new CalculoDeDeuda(new SinAcumulacion()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                                RELOJ));

        DirectorioJdbc padron =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)));

        CampaniasDeBeneficioParametrizadas campanias =
                new CampaniasDeBeneficioParametrizadas(
                        envolver(
                                new LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

        simulacion =
                envolver(
                        new SimularAcogimiento(
                                padron,
                                envolver(new ConsultaDeDeudaCuentaCorriente(consultarDeuda)),
                                campanias,
                                RELOJ));

        sembrarCampanias();
    }

    /**
     * El conjunto sellado de A con sus dos campanas, y el conjunto ABIERTO de B con la suya.
     *
     * <p>Todo en el arranque y una sola vez: sellar es irreversible y el ejercicio admite un solo
     * conjunto sellado.
     */
    private static void sembrarCampanias() throws SQLException {
        crearConjunto(
                municipalidad,
                true,
                parametroDelCatalogo(
                        "BENEFICIO", CAMPANIA_TOTAL, MITAD, BaseDelBeneficio.TOTAL.name()),
                parametroDelCatalogo("BENEFICIO_REDONDEO", CAMPANIA_TOTAL, ESCALA, MODO),
                parametroDelCatalogo(
                        "BENEFICIO", CAMPANIA_INSOLUTO, MITAD, BaseDelBeneficio.INSOLUTO.name()),
                parametroDelCatalogo("BENEFICIO_REDONDEO", CAMPANIA_INSOLUTO, ESCALA, MODO));

        crearConjunto(
                otraMunicipalidad,
                false,
                parametroDelCatalogo(
                        "BENEFICIO", CAMPANIA_ABIERTA, MITAD, BaseDelBeneficio.TOTAL.name()),
                parametroDelCatalogo("BENEFICIO_REDONDEO", CAMPANIA_ABIERTA, ESCALA, MODO));
    }

    /**
     * Envuelve el objeto en un proxy transaccional <b>de verdad</b>.
     *
     * <p>Lo que se quiere verificar es el reparto de {@code @Transactional} del codigo de
     * produccion —quien la lleva y quien no—. Si la prueba abriera la transaccion ella misma,
     * ponersela o quitarsela a {@link SimularAcogimiento} no cambiaria nada.
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
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
        OrigenContext.fijar(new Origen("orientadora.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Una municipalidad sin ningun conjunto sellado: hoy, todas")
    class SinConjuntoSellado {

        @Test
        @DisplayName("la consulta responde, y dice que no hay ninguna campana")
        void respondeSinCampanias() {
            TenantContext.fijar(new MunicipalidadId(sinConjunto));
            String codigo = crearContribuyente(sinConjunto, "BEN-1-C");
            asentarCargo(idDe(codigo), "PREDIAL", Dinero.de("1000.00"));

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, null), PAGINA);

            assertThat(resultado.campaniasPublicadas()).isEmpty();
            assertThat(resultado.acogimiento())
                    .as("sin campana no hay descuento: nulo, nunca un cero")
                    .isNull();
            assertThat(resultado.deudaTotal()).isEqualTo(Dinero.de("1000.00"));
            assertThat(resultado.deudaAcogida()).isEqualTo(Dinero.de("1000.00"));
            assertThat(resultado.estadoDeLaSimulacion())
                    .contains("No hay ninguna campa\u00f1a de beneficio publicada");
        }

        @Test
        @DisplayName("pedir una campana que nadie publico falla nombrando la llave")
        void pedirUnaQueNoExiste() {
            TenantContext.fijar(new MunicipalidadId(sinConjunto));
            String codigo = crearContribuyente(sinConjunto, "BEN-2-C");

            assertThatThrownBy(
                            () ->
                                    simulacion.de(
                                            criterio(codigo, null, "AMNISTIA INVENTADA"), PAGINA))
                    .isInstanceOf(CampaniasDeBeneficioParametrizadas.CampaniaSinParametrizar.class)
                    .hasMessageContaining("BENEFICIO:AMNISTIA INVENTADA");
        }
    }

    @Nested
    @DisplayName("Con la campana en un conjunto SELLADO")
    class ConCampaniaSellada {

        @Test
        @DisplayName("simula el acogimiento: base, ahorro y lo que quedaria")
        void simula() {
            String codigo = contribuyenteConDeuda("BEN-3");

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, CAMPANIA_TOTAL), PAGINA);

            assertThat(resultado.campania()).isNotNull();
            assertThat(resultado.acogimiento()).isNotNull();
            assertThat(resultado.acogimiento().baseDelBeneficio()).isEqualTo(Dinero.de("1000.00"));
            assertThat(resultado.acogimiento().ahorro()).isEqualTo(Dinero.de("500.00"));
            assertThat(resultado.acogimiento().deudaConBeneficio()).isEqualTo(Dinero.de("500.00"));
            assertThat(resultado.estadoDeLaSimulacion())
                    .as("la frase la redacta el servidor, con el nombre de la campana dentro")
                    .contains(CAMPANIA_TOTAL);
        }

        @Test
        @DisplayName("la base es la que dice la ordenanza, no el total por omision")
        void laBaseEsLaDeLaOrdenanza() {
            String codigo = crearContribuyente(municipalidad, "BEN-3B");
            long id = idDe(codigo);
            asentarCargo(id, "PREDIAL", Concepto.INSOLUTO, Dinero.de("800.00"));
            asentarCargo(id, "PREDIAL", Concepto.INTERES, Dinero.de("200.00"));

            SimularAcogimiento.Simulacion sobreElTotal =
                    simulacion.de(criterio(codigo, null, CAMPANIA_TOTAL), PAGINA);
            SimularAcogimiento.Simulacion sobreElInsoluto =
                    simulacion.de(criterio(codigo, null, CAMPANIA_INSOLUTO), PAGINA);

            assertThat(sobreElTotal.acogimiento()).isNotNull();
            assertThat(sobreElInsoluto.acogimiento()).isNotNull();
            assertThat(sobreElTotal.acogimiento().baseDelBeneficio())
                    .isEqualTo(Dinero.de("1000.00"));
            assertThat(sobreElInsoluto.acogimiento().baseDelBeneficio())
                    .as("la misma deuda, y la campana del insoluto no descuenta sobre el interes")
                    .isEqualTo(Dinero.de("800.00"));
            assertThat(sobreElInsoluto.acogimiento().ahorro()).isEqualTo(Dinero.de("400.00"));
        }

        @Test
        @DisplayName("las campanas selladas aparecen en la lista de aplicables")
        void apareceEnLaLista() {
            String codigo = contribuyenteConDeuda("BEN-4");

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, null), PAGINA);

            assertThat(resultado.campaniasPublicadas())
                    .as("el desplegable de la pantalla se llena de aqui, no de un enum")
                    .anySatisfy(c -> assertThat(c.nombre()).isEqualTo(CAMPANIA_TOTAL))
                    .anySatisfy(c -> assertThat(c.nombre()).isEqualTo(CAMPANIA_INSOLUTO));
        }

        @Test
        @DisplayName("desde la municipalidad vecina, esa campana no existe")
        void noCruzaLaFrontera() {
            String deA = contribuyenteConDeuda("BEN-5");

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            assertThatThrownBy(() -> simulacion.de(criterio(deA, null, CAMPANIA_TOTAL), PAGINA))
                    .as("el contribuyente de A no existe en B: 404 antes de llegar a la campana")
                    .isInstanceOf(ProblemaDeNegocio.class);

            String propio = crearContribuyente(otraMunicipalidad, "BEN-5-B");
            assertThatThrownBy(() -> simulacion.de(criterio(propio, null, CAMPANIA_TOTAL), PAGINA))
                    .as("y la campana de A tampoco: el conjunto que la incluye es el de A")
                    .isInstanceOf(CampaniasDeBeneficioParametrizadas.CampaniaSinParametrizar.class)
                    .hasMessageContaining("BENEFICIO:" + CAMPANIA_TOTAL);
        }
    }

    @Nested
    @DisplayName("Con la campana en un conjunto ABIERTO")
    class ConCampaniaEnConjuntoAbierto {

        @Test
        @DisplayName("no se lee: un conjunto abierto todavia se puede corregir (ADR-0007)")
        void elAbiertoNoSeLee() {
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            String codigo = crearContribuyente(otraMunicipalidad, "BEN-6-B");
            asentarCargo(idDe(codigo), "PREDIAL", Dinero.de("500.00"));

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, null), PAGINA);
            assertThat(resultado.campaniasPublicadas())
                    .as("una campana de un conjunto abierto no esta publicada")
                    .isEmpty();

            assertThatThrownBy(
                            () -> simulacion.de(criterio(codigo, null, CAMPANIA_ABIERTA), PAGINA))
                    .as("y pedirla falla nombrando la llave, como si no existiera")
                    .isInstanceOf(CampaniasDeBeneficioParametrizadas.CampaniaSinParametrizar.class)
                    .hasMessageContaining("BENEFICIO:" + CAMPANIA_ABIERTA);
        }
    }

    @Nested
    @DisplayName("La pagina y el resumen")
    class PaginaYResumen {

        @Test
        @DisplayName("el resumen se suma sobre TODAS las obligaciones, no sobre la pagina")
        void elResumenNoDependeDeLaPagina() {
            String codigo = crearContribuyente(municipalidad, "BEN-7");
            long id = idDe(codigo);
            asentarCargo(id, "PREDIAL", Dinero.de("800.00"));
            asentarCargo(id, "MULTA_TRANSITO", Dinero.de("200.00"));

            Paginacion deUnaEnUna =
                    new Paginacion(0, 1, "ejercicio", Paginacion.Direccion.DESCENDENTE);
            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, CAMPANIA_TOTAL), deUnaEnUna);

            assertThat(resultado.obligaciones().contenido())
                    .as("la rejilla trae una fila")
                    .hasSize(1);
            assertThat(resultado.obligaciones().totalElementos()).isEqualTo(2);
            assertThat(resultado.registrosAcogidos())
                    .as("pero lo acogido son las dos obligaciones")
                    .isEqualTo(2);
            assertThat(resultado.acogimiento()).isNotNull();
            assertThat(resultado.acogimiento().ahorro())
                    .as("la mitad de 1 000, no la mitad de la fila que se ve")
                    .isEqualTo(Dinero.de("500.00"));
        }

        @Test
        @DisplayName("el filtro por tributo acota lo acogido y deja la deuda total intacta")
        void elFiltroAcotaLoAcogido() {
            String codigo = crearContribuyente(municipalidad, "BEN-8");
            long id = idDe(codigo);
            asentarCargo(id, "PREDIAL", Dinero.de("800.00"));
            asentarCargo(id, "MULTA_TRANSITO", Dinero.de("200.00"));

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, "MULTA_TRANSITO", CAMPANIA_TOTAL), PAGINA);

            assertThat(resultado.deudaTotal()).isEqualTo(Dinero.de("1000.00"));
            assertThat(resultado.deudaAcogida()).isEqualTo(Dinero.de("200.00"));
            assertThat(resultado.registrosAcogidos()).isEqualTo(1);
            assertThat(resultado.acogimiento()).isNotNull();
            assertThat(resultado.acogimiento().ahorro()).isEqualTo(Dinero.de("100.00"));
        }

        @Test
        @DisplayName("un orden que la rejilla no admite se rechaza en vez de ignorarse")
        void ordenNoAdmitido() {
            String codigo = contribuyenteConDeuda("BEN-9");

            assertThatThrownBy(
                            () ->
                                    simulacion.de(
                                            criterio(codigo, null, null),
                                            new Paginacion(
                                                    0,
                                                    20,
                                                    "insoluto",
                                                    Paginacion.Direccion.ASCENDENTE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("insoluto");
        }
    }

    @Nested
    @DisplayName("El contribuyente")
    class DelContribuyente {

        @Test
        @DisplayName("un codigo que no esta en el padron es 404, no una simulacion vacia")
        void codigoDesconocido() {
            assertThatThrownBy(() -> simulacion.de(criterio("NO-EXISTE", null, null), PAGINA))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("NO-EXISTE");
        }

        @Test
        @DisplayName("un contribuyente sin deuda simula cero, y lo dice sin inventar campana")
        void sinDeuda() {
            String codigo = crearContribuyente(municipalidad, "BEN-10");

            SimularAcogimiento.Simulacion resultado =
                    simulacion.de(criterio(codigo, null, null), PAGINA);

            assertThat(resultado.deudaTotal()).isEqualTo(Dinero.CERO);
            assertThat(resultado.registrosAcogidos()).isZero();
            assertThat(resultado.acogimiento()).isNull();
        }
    }

    // ------------------------------------------------------------------

    private static SimularAcogimiento.Criterio criterio(
            String codigo,
            @org.jspecify.annotations.Nullable String tributo,
            @org.jspecify.annotations.Nullable String campania) {
        return new SimularAcogimiento.Criterio(codigo, HOY, tributo, campania);
    }

    private String contribuyenteConDeuda(String sufijo) {
        String codigo = crearContribuyente(municipalidad, sufijo);
        asentarCargo(idDe(codigo), "PREDIAL", Dinero.de("1000.00"));
        return codigo;
    }

    private void asentarCargo(long contribuyenteId, String tributo, Dinero monto) {
        asentarCargo(contribuyenteId, tributo, Concepto.INSOLUTO, monto);
    }

    private void asentarCargo(
            long contribuyenteId, String tributo, Concepto concepto, Dinero monto) {
        transaccion.execute(
                estado ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyenteId,
                                        tributo,
                                        concepto,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        LocalDate.of(2026, 1, 2),
                                        "DETERMINACION DE LA PRUEBA"),
                                Observacion.de("Se asienta la deuda de la prueba")));
    }

    private static long parametroDelCatalogo(String tipo, String clave, String numero, String texto)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, valor_texto, vigencia_desde,"
                                        + " documento_fuente, sellado, usuario_carga)"
                                        + " VALUES (NULL, ?, ?, ?::numeric, ?, DATE '2026-01-01',"
                                        + " 'Ordenanza de la prueba', true, 'siembra')"
                                        + " RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setString(3, numero);
            sentencia.setString(4, texto);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    /**
     * Un conjunto del ejercicio con esos parametros dentro, sellado o no.
     *
     * <p>{@code conjunto_sellado_uq} (V9) admite <b>un solo</b> conjunto sellado por ejercicio, asi
     * que las campanas selladas se acumulan en el mismo: si ya hay uno, se le anaden los parametros
     * en vez de crear otro.
     */
    private static void crearConjunto(long municipalidadId, boolean sellado, long... parametros)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            @org.jspecify.annotations.Nullable
            Long existente = sellado ? conjuntoSellado(app, municipalidadId) : null;
            long conjunto = existente == null ? nuevoConjunto(app, municipalidadId) : existente;

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
            if (sellado && existente == null) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "UPDATE conjunto_parametros SET estado = 'SELLADO',"
                                        + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                        + " WHERE municipalidad_id = ? AND id = ?")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.executeUpdate();
                }
            }
            app.commit();
        }
    }

    private static @org.jspecify.annotations.Nullable Long conjuntoSellado(
            Connection app, long municipalidadId) throws SQLException {
        try (PreparedStatement sentencia =
                app.prepareStatement(
                        "SELECT id FROM conjunto_parametros WHERE municipalidad_id = ?"
                                + " AND ejercicio = ? AND estado = 'SELLADO'")) {
            sentencia.setLong(1, municipalidadId);
            sentencia.setInt(2, EJERCICIO.valor());
            try (ResultSet resultado = sentencia.executeQuery()) {
                return resultado.next() ? resultado.getLong(1) : null;
            }
        }
    }

    private static long nuevoConjunto(Connection app, long municipalidadId) throws SQLException {
        try (PreparedStatement sentencia =
                app.prepareStatement(
                        "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                                + " VALUES (?, ?, ?) RETURNING id")) {
            sentencia.setLong(1, municipalidadId);
            sentencia.setInt(2, EJERCICIO.valor());
            sentencia.setInt(3, CONTADOR.incrementAndGet());
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }

    private long idDe(String codigo) {
        Long id =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT id FROM contribuyente WHERE"
                                                        + " codigo_contribuyente = :codigo")
                                        .param("codigo", codigo)
                                        .query(Long.class)
                                        .single());
        if (id == null) {
            throw new IllegalStateException("No se sembro el contribuyente " + codigo);
        }
        return id;
    }

    private static String crearContribuyente(long muni, String codigo) {
        int orden = CONTADOR.incrementAndGet();
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id,"
                                    + " codigo_contribuyente, tipo_documento, numero_documento,"
                                    + " tipo_persona, nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL',"
                                    + " 'TITULAR DEL ACOGIMIENTO, PRUEBA', 'siembra')")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("%08d", 30_000_000 + orden));
                sentencia.executeUpdate();
            }
            owner.commit();
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo sembrar el contribuyente", noSePudo);
        }
        return codigo;
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
