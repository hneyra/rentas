package kamayuk.rentas.nucleo.aplicacion;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientosDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Agregacion;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeudaPorContribuyente;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.TipoDeDeclaracion;
import kamayuk.rentas.nucleo.infraestructura.DeclaracionJuradaRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.ConvenioDelContribuyente;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.ConveniosDelContribuyenteTesoreria;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.infraestructura.ConvenioRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeConvenioRepositoryJdbc;
import kamayuk.rentas.valores.ValorDelContribuyente;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores;
import kamayuk.rentas.valores.aplicacion.ValoresDelContribuyenteValores;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
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
 * #25 — La consulta unificada contra PostgreSQL de verdad, conectada como {@code sgtm_app}
 * (RF-046).
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>Que la ficha no discrepe de las consultas individuales.</b> Cada seccion se compara con
 *       lo que su contexto dueño responde por separado, sobre los mismos datos y en la misma base:
 *       las deudas contra {@code ConsultarDeuda#porContribuyente}, los pagos contra {@code
 *       AsientoRepository#pagos}, los convenios contra {@code ConsultaDeConvenios#listar} y los
 *       valores contra {@code ConsultaDeValores#buscar}. Contra dobles esto solo probaria que los
 *       dobles coinciden consigo mismos.
 *   <li><b>Que las cinco cifras del resumen las suma el servidor</b> (RNF-083), sobre todas las
 *       obligaciones y no sobre la pagina.
 *   <li><b>El aislamiento</b>. Con el contexto de la municipalidad B, el contribuyente de A no
 *       existe: la ficha responde 404, no una ficha ajena ni una vacia.
 *   <li><b>Que la lectura tenga contexto de tenant.</b> Sin {@code @Transactional} no hay {@code
 *       SET LOCAL} y RLS no puede evaluar su politica; el caso de uso se envuelve en un proxy
 *       transaccional <b>de verdad</b> para que lo que se verifique sea su anotacion y no una
 *       transaccion abierta por la prueba.
 * </ul>
 */
@DisplayName("#25 — La consulta unificada contra PostgreSQL")
class ConsultaUnificadaJdbcTest {

    /**
     * 2026 y no 2025: {@code cuenta_corriente_asiento} se particiona por ejercicio y V2 solo
     * declara las particiones de 2026 y 2027.
     */
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 8, 28);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Paginacion PAGINA =
            new Paginacion(0, 20, "ejercicio", Paginacion.Direccion.DESCENDENTE);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;

    private static ConsultaUnificada consulta;
    private static RegistrarAsiento registrarAsiento;
    private static ConsultarDeuda consultarDeuda;
    private static AsientoRepositoryJdbc asientos;
    private static ConvenioRepositoryJdbc convenios;
    private static ConsultaDeConvenios consultaDeConvenios;
    private static ValorRepositoryJdbc valores;
    private static ConsultaDeValores consultaDeValores;
    private static DeclaracionJuradaRepositoryJdbc declaraciones;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad de la ficha unificada");
        otraMunicipalidad = crearMunicipalidad("260102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));

        // La misma politica de mora y de redondeo que la aplicacion cablea hoy: sin
        // acumulacion. El interes devengado es cero, asi que lo que esta prueba mide es la
        // composicion de la ficha y no una regla de calculo -que sigue bloqueada por D-02-.
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        consultarDeuda = envolver(new ConsultarDeuda(asientos, saldos, calculo, redondeo, RELOJ));

        convenios = new ConvenioRepositoryJdbc(jdbc);
        consultaDeConvenios =
                envolver(
                        new ConsultaDeConvenios(
                                convenios, new MovimientoDeConvenioRepositoryJdbc(jdbc), RELOJ));

        DirectorioJdbc padron =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)));

        valores = new ValorRepositoryJdbc(jdbc);
        consultaDeValores = envolver(new ConsultaDeValores(valores, padron));

        declaraciones = new DeclaracionJuradaRepositoryJdbc(jdbc);

        consulta =
                envolver(
                        new ConsultaUnificada(
                                padron,
                                envolver(new ConsultaDeDeudaCuentaCorriente(consultarDeuda)),
                                envolver(new MovimientosDelLibroCuentaCorriente(asientos)),
                                envolver(
                                        new ConveniosDelContribuyenteTesoreria(
                                                consultaDeConvenios)),
                                envolver(new ValoresDelContribuyenteValores(consultaDeValores)),
                                declaraciones,
                                RELOJ));
    }

    /**
     * Envuelve el objeto en un proxy transaccional <b>de verdad</b>.
     *
     * <p>Lo que se quiere verificar es la anotacion {@code @Transactional} del codigo de
     * produccion. Si la prueba abriera la transaccion ella misma, quitarsela a {@link
     * ConsultaUnificada#de} no pondria nada en rojo.
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
    @DisplayName("El contribuyente completo")
    class DelContribuyenteCompleto {

        @Test
        @DisplayName("las seis secciones traen lo suyo, y ninguna llega vacia")
        void lasSeisSeccionesTraenLoSuyo() {
            String codigo = contribuyenteCompleto("UNIF-1");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);

            assertThat(ficha.contribuyente().codigo()).isEqualTo(codigo);
            assertThat(ficha.deudas().contenido())
                    .as("el predial de 2026 y el arbitrio de 2026, cada uno su obligacion")
                    .hasSize(2);
            assertThat(ficha.pagos().contenido()).as("el abono del recibo").hasSize(1);
            assertThat(ficha.altasYBajas().contenido())
                    .as("el alta de deuda registrada; los tres cargos son la emision (#640)")
                    .hasSize(1);
            assertThat(ficha.fraccionamientos().contenido()).hasSize(1);
            assertThat(ficha.valores().contenido()).hasSize(1);
            assertThat(ficha.declaraciones().contenido()).hasSize(1);
        }

        @Test
        @DisplayName("las deudas dicen lo mismo que consulta_deuda sobre los mismos asientos")
        void lasDeudasNoDiscrepanDeConsultaDeuda() {
            String codigo = contribuyenteCompleto("UNIF-2");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);
            Pagina<ObligacionConDeuda> individual =
                    consultarDeuda.porContribuyente(
                            new CriterioDeDeudaPorContribuyente(
                                    codigo, HOY, null, Agregacion.POR_OBLIGACION),
                            PAGINA);

            assertThat(ficha.deudas().totalElementos())
                    .as("las mismas obligaciones, contadas igual")
                    .isEqualTo(individual.totalElementos());
            for (ObligacionConDeuda esperada : individual.contenido()) {
                ObligacionPublica enLaFicha =
                        ficha.deudas().contenido().stream()
                                .filter(
                                        o ->
                                                o.tributo().equals(esperada.tributo())
                                                        && o.ejercicio()
                                                                .equals(esperada.ejercicio()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new AssertionError(
                                                        "la ficha no trae la obligacion "
                                                                + esperada.tributo()));
                assertThat(enLaFicha.total())
                        .as(
                                "la unificada no puede decir un importe y consulta_deuda otro"
                                        + " sobre el mismo tributo")
                        .isEqualTo(esperada.deuda().total());
                assertThat(enLaFicha.fecha())
                        .as("y las dos a la misma fecha de corte (regla 9)")
                        .isEqualTo(esperada.deuda().fecha());
            }
        }

        @Test
        @DisplayName("los pagos dicen lo mismo que consulta_pagos")
        void losPagosNoDiscrepanDeConsultaPagos() {
            String codigo = contribuyenteCompleto("UNIF-3");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);
            Pagina<Asiento> individual =
                    transaccion.execute(
                            estado ->
                                    asientos.pagos(
                                            new CriterioDePagos(codigo, null, null),
                                            new Paginacion(
                                                    0,
                                                    20,
                                                    "fecha_valor",
                                                    Paginacion.Direccion.DESCENDENTE)));

            assertThat(individual).isNotNull();
            assertThat(ficha.pagos().totalElementos()).isEqualTo(individual.totalElementos());
            List<Long> enLaFicha =
                    ficha.pagos().contenido().stream().map(MovimientoDelLibro::id).toList();
            List<Long> enLaConsulta = individual.contenido().stream().map(Asiento::id).toList();
            assertThat(enLaFicha)
                    .as("los mismos asientos, en el mismo orden")
                    .isEqualTo(enLaConsulta);
            assertThat(ficha.pagos().contenido().getFirst().monto())
                    .isEqualTo(individual.contenido().getFirst().monto());
            assertThat(ficha.pagos().contenido().getFirst().fechaValor())
                    .as("cada pago con SU fecha valor, no con la de la consulta (regla 9)")
                    .isEqualTo(individual.contenido().getFirst().fechaValor());
        }

        @Test
        @DisplayName("las altas y bajas dicen lo mismo que consulta_altas_bajas")
        void lasAltasYBajasNoDiscrepanDeConsultaAltasBajas() {
            String codigo = contribuyenteCompleto("UNIF-12");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);
            Pagina<Asiento> individual =
                    transaccion.execute(
                            estado ->
                                    asientos.altasYBajas(
                                            new CriterioDeAltasBajas(codigo, null, null, null),
                                            new Paginacion(
                                                    0,
                                                    20,
                                                    "fecha_valor",
                                                    Paginacion.Direccion.DESCENDENTE)));

            assertThat(individual).isNotNull();
            assertThat(ficha.altasYBajas().totalElementos())
                    .as(
                            "un pago NO es un alta ni una baja: la distincion la mantiene"
                                    + " cuentacorriente, no quien pregunta")
                    .isEqualTo(individual.totalElementos());
            assertThat(ficha.altasYBajas().contenido().stream().map(MovimientoDelLibro::id))
                    .containsExactlyElementsOf(
                            individual.contenido().stream().map(Asiento::id).toList());
        }

        @Test
        @DisplayName("los fraccionamientos dicen lo mismo que consulta_convenios")
        void losConveniosNoDiscrepanDeConsultaConvenios() {
            String codigo = contribuyenteCompleto("UNIF-4");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);
            Pagina<ConvenioEnConsulta> individual =
                    consultaDeConvenios.listar(
                            new CriterioDeConvenios(null, codigo, null, null, null, HOY),
                            new Paginacion(0, 20, "fecha", Paginacion.Direccion.DESCENDENTE));

            assertThat(ficha.fraccionamientos().totalElementos())
                    .isEqualTo(individual.totalElementos());
            ConvenioDelContribuyente enLaFicha = ficha.fraccionamientos().contenido().getFirst();
            ConvenioEnConsulta esperado = individual.contenido().getFirst();
            assertThat(enLaFicha.numero()).isEqualTo(esperado.numero().impreso());
            assertThat(enLaFicha.deudaAcogida()).isEqualTo(esperado.deudaAcogida());
            assertThat(enLaFicha.saldo()).isEqualTo(esperado.saldo());
            assertThat(enLaFicha.estado()).isEqualTo(esperado.estado().name());
            assertThat(enLaFicha.fechaCorte())
                    .as("la deuda acogida se congelo el dia del convenio, no hoy (regla 9)")
                    .isEqualTo(esperado.fechaCorte())
                    .isNotEqualTo(enLaFicha.saldoA());
        }

        @Test
        @DisplayName("los valores dicen lo mismo que consulta_valores")
        void losValoresNoDiscrepanDeConsultaValores() {
            String codigo = contribuyenteCompleto("UNIF-5");
            long id = idDe(codigo);

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);
            Pagina<ConsultaDeValores.FilaDeValor> individual =
                    consultaDeValores.buscar(
                            new CriterioDeConsultaDeValores(null, id, null, null, null, HOY),
                            new Paginacion(
                                    0, 20, "fecha_emision", Paginacion.Direccion.DESCENDENTE));

            assertThat(ficha.valores().totalElementos()).isEqualTo(individual.totalElementos());
            ValorDelContribuyente enLaFicha = ficha.valores().contenido().getFirst();
            ValorEnConsulta esperado = individual.contenido().getFirst().valor();
            assertThat(enLaFicha.numero()).isEqualTo(esperado.valor().numero());
            assertThat(enLaFicha.total()).isEqualTo(esperado.valor().total());
            assertThat(enLaFicha.situacion()).isEqualTo(esperado.situacion().name());
            assertThat(enLaFicha.proyectadoA())
                    .as(
                            "el desglose de un valor esta congelado: su fecha es la de la"
                                    + " emision, nunca la de la consulta (AC de #37, regla 9)")
                    .isEqualTo(esperado.valor().proyectadoA())
                    .isNotEqualTo(HOY);
        }

        @Test
        @DisplayName("el resumen suma TODAS las obligaciones, no la pagina que se devuelve")
        void elResumenSumaTodasLasObligaciones() {
            String codigo = contribuyenteCompleto("UNIF-6");

            // Una pagina de una sola fila: si el resumen sumara la pagina, diria la mitad.
            ConsultaUnificada.Ficha ficha =
                    consulta.de(
                            criterio(codigo),
                            new Paginacion(0, 1, "ejercicio", Paginacion.Direccion.DESCENDENTE));

            assertThat(ficha.deudas().contenido()).hasSize(1);
            assertThat(ficha.deudas().totalElementos()).isEqualTo(2);

            List<ObligacionPublica> todas =
                    consulta.de(criterio(codigo), PAGINA).deudas().contenido();
            Dinero esperado = Dinero.CERO;
            for (ObligacionPublica obligacion : todas) {
                esperado = esperado.mas(obligacion.total());
            }
            assertThat(ficha.resumen().total())
                    .as("las dos obligaciones sumadas por el servidor (RNF-083)")
                    .isEqualTo(esperado);
            assertThat(ficha.resumen().obligaciones()).isEqualTo(2);
            assertThat(ficha.resumen().aLaFecha()).isEqualTo(HOY);
            assertThat(ficha.resumen().estadoDeLaConsulta())
                    .as("la frase la redacta el servidor, con su fecha dentro (RNF-083, regla 9)")
                    .isEqualTo("2 obligaciones con saldo al 2026-08-28");
        }

        @Test
        @DisplayName("el total del resumen es la suma de sus cuatro partes")
        void elTotalEsLaSumaDeSusPartes() {
            String codigo = contribuyenteCompleto("UNIF-7");

            ConsultaUnificada.ResumenDeSaldos resumen =
                    consulta.de(criterio(codigo), PAGINA).resumen();

            assertThat(resumen.total())
                    .isEqualTo(
                            resumen.insoluto()
                                    .mas(resumen.reajuste())
                                    .mas(resumen.interes())
                                    .mas(resumen.gasto()));
        }
    }

    @Nested
    @DisplayName("El filtro «Impresion»")
    class DelAlcance {

        @Test
        @DisplayName("PREDIAL deja fuera los arbitrios, y el resumen baja con ellos")
        void predialDejaFueraLosArbitrios() {
            String codigo = contribuyenteCompleto("UNIF-8");

            ConsultaUnificada.Ficha todo = consulta.de(criterio(codigo), PAGINA);
            ConsultaUnificada.Ficha soloPredial =
                    consulta.de(
                            new ConsultaUnificada.Criterio(
                                    codigo, HOY, ConsultaUnificada.Alcance.PREDIAL),
                            PAGINA);

            assertThat(soloPredial.deudas().contenido())
                    .extracting(ObligacionPublica::tributo)
                    .containsExactly("PREDIAL");
            assertThat(soloPredial.resumen().obligaciones()).isEqualTo(1);
            assertThat(soloPredial.resumen().total())
                    .as("un filtro que no filtrara dejaria las dos cifras iguales")
                    .isNotEqualTo(todo.resumen().total());
        }

        @Test
        @DisplayName("ARBITRIOS filtra por el tributo ARBITRIO, que es como se asienta")
        void arbitriosFiltraPorElTributoEnSingular() {
            String codigo = contribuyenteCompleto("UNIF-9");

            ConsultaUnificada.Ficha ficha =
                    consulta.de(
                            new ConsultaUnificada.Criterio(
                                    codigo, HOY, ConsultaUnificada.Alcance.ARBITRIOS),
                            PAGINA);

            assertThat(ficha.deudas().contenido())
                    .extracting(ObligacionPublica::tributo)
                    .containsExactly("ARBITRIO");
        }
    }

    @Nested
    @DisplayName("Los casos que no traen datos")
    class DeLosCasosVacios {

        @Test
        @DisplayName("un contribuyente sin nada da seis secciones vacias, no un error")
        void unContribuyenteSinNadaDaSeccionesVacias() {
            String codigo = crearContribuyente(municipalidad, "UNIF-10");

            ConsultaUnificada.Ficha ficha = consulta.de(criterio(codigo), PAGINA);

            assertThat(ficha.contribuyente().codigo()).isEqualTo(codigo);
            assertThat(ficha.deudas().contenido()).isEmpty();
            assertThat(ficha.pagos().contenido()).isEmpty();
            assertThat(ficha.altasYBajas().contenido()).isEmpty();
            assertThat(ficha.fraccionamientos().contenido()).isEmpty();
            assertThat(ficha.valores().contenido()).isEmpty();
            assertThat(ficha.declaraciones().contenido()).isEmpty();
            assertThat(ficha.resumen().total()).isEqualTo(Dinero.CERO);
            assertThat(ficha.resumen().estadoDeLaConsulta())
                    .as("y lo dice con su fecha, no con un cero suelto")
                    .isEqualTo("Sin deuda pendiente al 2026-08-28");
        }

        @Test
        @DisplayName("un codigo que no existe es 404, no una ficha vacia")
        void unCodigoQueNoExisteEs404() {
            assertThatThrownBy(() -> consulta.de(criterio("NO-EXISTE-1"), PAGINA))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("NO-EXISTE-1")
                    .extracting(problema -> ((ProblemaDeNegocio) problema).codigo())
                    .hasToString("NO_ENCONTRADO");
        }
    }

    @Nested
    @DisplayName("Aislamiento")
    class DelAislamiento {

        @Test
        @DisplayName("desde la municipalidad vecina, el contribuyente de A no existe")
        void desdeLaVecinaElContribuyenteDeANoExiste() {
            String codigo = contribuyenteCompleto("UNIF-11");

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            assertThatThrownBy(() -> consulta.de(criterio(codigo), PAGINA))
                    .as(
                            "no una ficha vacia ni la ajena: para B ese contribuyente no esta en"
                                    + " el padron")
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining(codigo);
        }

        @Test
        @DisplayName("el mismo codigo en las dos municipalidades devuelve dos fichas distintas")
        void elMismoCodigoEnDosMunicipalidadesDaDosFichas() {
            String codigo = "UNIF-GEMELO";
            crearContribuyente(municipalidad, codigo);
            crearContribuyente(otraMunicipalidad, codigo);
            asentarCargo(idDe(codigo), "PREDIAL", Dinero.de("500.00"));

            ConsultaUnificada.Ficha enA = consulta.de(criterio(codigo), PAGINA);

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            ConsultaUnificada.Ficha enB = consulta.de(criterio(codigo), PAGINA);

            assertThat(enA.deudas().contenido()).hasSize(1);
            assertThat(enB.deudas().contenido())
                    .as("la deuda de A no se ve desde B, aunque el codigo se repita")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    //  Siembra
    // ------------------------------------------------------------------

    private static ConsultaUnificada.Criterio criterio(String codigo) {
        return new ConsultaUnificada.Criterio(
                codigo, HOY, ConsultaUnificada.Alcance.PREDIAL_Y_ARBITRIOS);
    }

    /**
     * Un contribuyente con algo en cada seccion: dos obligaciones con deuda —predial y arbitrio—,
     * un pago, un convenio, un valor emitido y una declaracion jurada.
     */
    private String contribuyenteCompleto(String sufijo) {
        String codigo = crearContribuyente(municipalidad, sufijo);
        long id = idDe(codigo);

        asentarCargo(id, "PREDIAL", Dinero.de("800.00"));
        asentarCargo(id, "ARBITRIO", Dinero.de("300.00"));
        asentarCargo(id, "PREDIAL", Dinero.de("120.00"));
        asentarPago(id, "PREDIAL", Dinero.de("120.00"));

        registrarConvenio(id);
        emitirValor(id);
        presentarDeclaracion(id, sufijo);

        // Un ALTA de deuda de verdad, y al final: los tres cargos de arriba son la emision
        // de la determinacion, que desde #640 no es un movimiento de esta seccion —lo que
        // la pestana audita son los ACTOS de RF-043 y RF-044—. Va detras del convenio para
        // no cambiar lo que aquel acogio.
        asentarAltaDeDeuda(id, "PREDIAL", Dinero.de("60.00"));

        return codigo;
    }

    private void asentarCargo(long contribuyenteId, String tributo, Dinero monto) {
        transaccion.execute(
                estado ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyenteId,
                                        tributo,
                                        Concepto.INSOLUTO,
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

    /**
     * Un ALTA de deuda, por el camino que la produce de verdad: {@link
     * MovimientoDeDeuda#enAsientos}, que es el unico sitio del sistema que estampa el acto del que
     * nace el asiento. Un cargo escrito con {@code Asiento.nuevo} —la emision— no lleva acto y no
     * sale en la relacion de altas y bajas, con razon (#640).
     */
    private void asentarAltaDeDeuda(long contribuyenteId, String tributo, Dinero monto) {
        MovimientoDeDeuda alta =
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.ALTA,
                        new ClaveDeSaldo(contribuyenteId, tributo, EJERCICIO, 1, null, null),
                        monto,
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        LocalDate.of(2026, 3, 4),
                        "RES-ALTA DE LA PRUEBA",
                        null);
        transaccion.execute(
                estado -> {
                    for (Asiento asiento : alta.enAsientos()) {
                        registrarAsiento.asentar(
                                asiento, Observacion.de("Se da de alta la deuda de la prueba"));
                    }
                    return null;
                });
    }

    private void asentarPago(long contribuyenteId, String tributo, Dinero monto) {
        transaccion.execute(
                estado ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyenteId,
                                        tributo,
                                        Concepto.PAGO,
                                        TipoAsiento.ABONO,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        LocalDate.of(2026, 3, 15),
                                        "RECIBO 001-0000123"),
                                Observacion.de("Se asienta el cobro de la prueba")));
    }

    /**
     * Un preconvenio: sin movimiento de formalizacion, porque formalizar exige un recibo de caja y
     * lo que esta prueba necesita es que la seccion traiga una fila, no reproducir #35.
     */
    private void registrarConvenio(long contribuyenteId) {
        transaccion.execute(
                estado -> {
                    NumeroDeConvenio numero = convenios.siguienteNumero(EJERCICIO);
                    List<CuotaDeConvenio> cronograma =
                            List.of(
                                    new CuotaDeConvenio(
                                            0,
                                            LocalDate.of(2026, 2, 10),
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.CERO),
                                    new CuotaDeConvenio(
                                            1,
                                            LocalDate.of(2026, 3, 10),
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.CERO));
                    Convenio convenio =
                            new Convenio(
                                    null,
                                    numero,
                                    contribuyenteId,
                                    TipoDeConvenio.ORDINARIO,
                                    LocalDate.of(2026, 2, 1),
                                    // La fecha de corte NO es hoy: es lo que hace visible que la
                                    // deuda acogida y el saldo van con fechas distintas.
                                    LocalDate.of(2026, 1, 31),
                                    new CondicionesDelConvenio(
                                            Alicuota.de("0.01"), 12, Alicuota.de("0.20"), 1L),
                                    List.of(
                                            new kamayuk.rentas.cuentacorriente.DeudaAcogida(
                                                    "PREDIAL",
                                                    EJERCICIO,
                                                    0,
                                                    null,
                                                    null,
                                                    "ORDINARIA",
                                                    LocalDate.of(2026, 1, 31),
                                                    Dinero.de("200.00"),
                                                    Dinero.CERO,
                                                    Dinero.CERO,
                                                    Dinero.CERO)),
                                    cronograma,
                                    null,
                                    null,
                                    null,
                                    null,
                                    RELOJ.instant(),
                                    null,
                                    Observacion.de("Se registra el preconvenio de la prueba"));
                    return convenios.registrar(convenio, null);
                });
    }

    private void emitirValor(long contribuyenteId) {
        transaccion.execute(
                estado -> {
                    long correlativo =
                            valores.siguienteCorrelativo(TipoValor.ORDEN_DE_PAGO, EJERCICIO);
                    Valor valor =
                            new Valor(
                                    null,
                                    TipoValor.ORDEN_DE_PAGO,
                                    "OP-2026-" + String.format("%06d", correlativo),
                                    EJERCICIO,
                                    contribuyenteId,
                                    TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                    Dinero.de("400.00"),
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    // Proyectado al dia de la emision, no a hoy: es lo que hace
                                    // visible que el desglose de un valor esta congelado.
                                    LocalDate.of(2026, 4, 3),
                                    EstadoDeValor.EMITIDO,
                                    LocalDate.of(2026, 4, 3),
                                    null,
                                    Observacion.de("Se emite la orden de pago de la prueba"));
                    return valores.insertar(
                            valor,
                            List.of(
                                    new ValorDetalle(
                                            null,
                                            null,
                                            "PREDIAL",
                                            EJERCICIO,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Dinero.de("400.00"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO)));
                });
    }

    private void presentarDeclaracion(long contribuyenteId, String sufijo) {
        transaccion.execute(
                estado ->
                        declaraciones.insertar(
                                new DeclaracionJurada(
                                        null,
                                        "DJ-" + sufijo,
                                        EJERCICIO,
                                        contribuyenteId,
                                        TipoDeDeclaracion.HR,
                                        null,
                                        null,
                                        null,
                                        LocalDate.of(2026, 2, 20),
                                        LocalDate.of(2026, 2, 28),
                                        EstadoDeDeclaracion.PRESENTADA,
                                        null,
                                        null,
                                        Observacion.de(
                                                "Se presenta la declaracion de la prueba"))));
    }

    private long idDe(String codigo) {
        Long id =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT id FROM contribuyente"
                                                        + " WHERE codigo_contribuyente = :codigo")
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
                                    + " 'TITULAR DE LA FICHA, PRUEBA', 'siembra')")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("%08d", 20_000_000 + orden));
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
