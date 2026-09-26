package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.RangoDeCuotas;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #365 — <b>todo camino que escribe en el libro cristaliza antes el devengo</b>, o lo pierde.
 *
 * <h2>Que se mide</h2>
 *
 * <p>{@link CalculoDeDeuda#deudaActualizadaA} acumula reajuste e interes desde el <b>ultimo
 * movimiento</b> de la cuota, sea del concepto que sea. Cualquier asiento nuevo adelanta ese ancla,
 * y si antes no se asento el cargo de lo devengado —{@code deudaActualizadaA − asentadoA}—, lo
 * devengado hasta ese dia <b>deja de existir</b>: una condonacion sin acto. Y si el asiento nuevo
 * abona interes que no estaba en el libro, {@code netear(INTERES)} queda en negativo para siempre.
 *
 * <p>Hasta #365 esa invariante estaba copiada en la cobranza y en el convenio y olvidada en la
 * extincion, la baja manual, el pase a valor y la reversion. Esta prueba recorre <b>los seis</b>
 * —la baja, dos veces: de insoluto y de interes— con la misma siembra y las mismas dos aserciones:
 *
 * <ol>
 *   <li>el interes asentado nunca queda en negativo;
 *   <li>al dia siguiente del acto la deuda es la de antes, menos lo que el acto extinguio, mas
 *       <b>un dia</b> de mora sobre el insoluto que queda.
 * </ol>
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Con {@code SinAcumulacion} —la unica {@link PoliticaDeMora} de {@code src/main}— no hay
 * devengo y los seis caminos pasan en verde con o sin cristalizar. Aqui se monta {@link
 * MoraQueDevenga}, a 1,00 por dia. Y con el acto el mismo dia del cargo tampoco hay devengo: por
 * eso el cargo es del 2026-01-10 y el acto del 2026-06-10 —151 dias, 151,00 de interes que el libro
 * todavia no tiene—.
 *
 * <p>Contra PostgreSQL de verdad y como {@code kamayuk_app}: lo que se afirma es que las
 * <b>filas</b> del libro dejan la deuda donde estaba, y eso contra un doble no se demuestra.
 */
@DisplayName("#365 — El devengo se cristaliza en todo camino que escribe en el libro")
class ElDevengoSeCristalizaAntesDeEscribirJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /** El dia en que nace la deuda: el cargo de insoluto. */
    private static final LocalDate CARGO = LocalDate.of(2026, 1, 10);

    /** El dia del acto: 151 dias despues del cargo. */
    private static final LocalDate ACTO = LocalDate.of(2026, 6, 10);

    /** Y el dia siguiente, donde se mide si el acto se llevo algo que no debia. */
    private static final LocalDate DIA_SIGUIENTE = ACTO.plusDays(1);

    /**
     * El dia del cobro que el camino de la reversion anula despues: entre el cargo y el acto, para
     * que tambien ahi haya un hueco entre el ultimo asiento y la reversion.
     */
    private static final LocalDate COBRO_ANTERIOR = LocalDate.of(2026, 3, 1);

    /**
     * Lo que la mora de esta prueba devenga por dia. <b>No es la TIM</b> —D-02 sigue abierta y la
     * regla 5 prohibe que una cifra tributaria viva en el codigo—: es un importe elegido para que
     * cada dia se vea entero.
     */
    private static final Dinero POR_DIA = Dinero.de("1.00");

    private static final Dinero INSOLUTO = Dinero.de("400.00");

    /** 400,00 de insoluto mas 151 dias a 1,00: lo que se debe el dia del acto. */
    private static final Dinero DEUDA_EL_DIA_DEL_ACTO = Dinero.de("551.00");

    private static final String TRIBUTO = "MULTA_ADMINISTRATIVA";

    /**
     * La referencia del cargo: la extincion comprueba que la deuda sea de un solo origen (#371).
     */
    private static final String REFERENCIA = "PT-000365";

    private static final Observacion PORQUE = Observacion.de("Prueba de #365: el devengo");

    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-10T15:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static CalculoDeDeuda calculo;
    private static RegistrarAsiento registrar;
    private static RegistroDeAbonos abonos;
    private static AcogimientoAConvenio convenio;
    private static ExtincionDeDeuda extincion;
    private static RegistrarMovimientoDeDeuda movimientos;
    private static MovimientoDeFase fases;

    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("365101", "Municipalidad del devengo");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        calculo = new CalculoDeDeuda(new MoraQueDevenga(POR_DIA));

        // El proxy obedece a la anotacion, como el contenedor (#486, #535).
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrar, calculo, REDONDEO),
                        gestor);
        convenio =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, registrar, calculo, REDONDEO),
                        gestor);
        extincion =
                envolver(
                        new ExtincionDeDeudaCuentaCorriente(
                                asientos, saldos, registrar, calculo, REDONDEO),
                        gestor);
        fases =
                envolver(
                        new MovimientoDeFaseCuentaCorriente(
                                registrar, asientos, saldos, calculo, REDONDEO),
                        gestor);

        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();
        EmitirDocumento documentos =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(jdbc, json),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);
        movimientos =
                envolver(
                        new RegistrarMovimientoDeDeuda(
                                asientos,
                                saldos,
                                registrar,
                                calculo,
                                REDONDEO,
                                documentos,
                                SIN_UNIDAD),
                        gestor);
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
        OrigenContext.fijar(new Origen("prueba.devengo", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    /**
     * Los caminos que escriben en el libro. Cada uno ejerce su acto sobre la misma siembra y dice
     * cuanto <b>extinguio</b>: lo que la deuda tiene que bajar, y ni un centimo mas.
     */
    enum Camino {

        /** La cobranza del importe entero: ya cristalizaba desde #33. */
        COBRANZA {
            @Override
            Dinero ejercer(long titular) {
                cobrar(titular, "RECIBO 001-0000365", DEUDA_EL_DIA_DEL_ACTO, ACTO);
                return DEUDA_EL_DIA_DEL_ACTO;
            }
        },

        /** El acogimiento a convenio: ya cristalizaba desde #35. Mueve de fase, no extingue. */
        CONVENIO {
            @Override
            Dinero ejercer(long titular) {
                List<DeudaAcogida> acogibles =
                        enTransaccion(
                                () -> convenio.deudaAcogible(titular, List.of(seleccion()), ACTO));
                enTransaccion(
                        () ->
                                convenio.acoger(
                                        titular, acogibles, ACTO, "CONVENIO 2026-000365", PORQUE));
                return Dinero.CERO;
            }
        },

        /**
         * Una resolucion de gerencia deja la multa sin efecto: extingue lo que se debe ese dia,
         * devengo incluido. Sin el cargo previo, el abono del interes lo deja en -151,00.
         */
        EXTINCION {
            @Override
            Dinero ejercer(long titular) {
                enTransaccion(
                        () ->
                                extincion.extinguirLoOriginadoPor(
                                        titular,
                                        seleccion(),
                                        ACTO,
                                        "RESOLUCION RG-2026-000365",
                                        REFERENCIA,
                                        CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO,
                                        PORQUE));
                return DEUDA_EL_DIA_DEL_ACTO;
            }
        },

        /**
         * Una baja manual parcial de insoluto. El abono adelanta el ancla, y los 151,00 devengados
         * desde enero dejaban de existir: una condonacion que ningun acto dice.
         */
        BAJA_DE_INSOLUTO {
            @Override
            Dinero ejercer(long titular) {
                Dinero baja = Dinero.de("100.00");
                darDeBaja(titular, baja, Dinero.CERO, "NOTA DE CARGO 365-A");
                return baja;
            }
        },

        /**
         * Una baja manual de interes. La validacion cuenta el devengo, asi que pasa; y sin el cargo
         * previo el abono deja el interes del libro en -100,00.
         */
        BAJA_DE_INTERES {
            @Override
            Dinero ejercer(long titular) {
                Dinero baja = Dinero.de("100.00");
                darDeBaja(titular, Dinero.CERO, baja, "NOTA DE CARGO 365-B");
                return baja;
            }
        },

        /**
         * El pase a valor: la OP congela lo que se debe ese dia —551,00— y el par AJUSTE adelanta
         * el ancla. Sin cristalizar, al dia siguiente el libro ya no tiene lo que la OP exige.
         */
        PASE_A_VALOR {
            @Override
            Dinero ejercer(long titular) {
                Dinero movido =
                        enTransaccion(
                                () ->
                                        fases.moverAValor(
                                                titular,
                                                new ClaveDeObligacionPublica(
                                                        TRIBUTO, EJERCICIO, null, null),
                                                "VALOR-OP-2026-000365",
                                                ACTO,
                                                "OP-2026-000365",
                                                PORQUE));
                // Desde #448 el monto lo decide el libro: lo que la cuota debe el dia del acto,
                // que es lo que la OP congela.
                assertThat(movido).isEqualTo(DEUDA_EL_DIA_DEL_ACTO);
                return Dinero.CERO;
            }
        },

        /**
         * Un cobro del 03-01 que se anula el 06-10. La reversion deshace tambien el cargo del
         * devengo que el cobro cristalizo, y su fecha adelanta el ancla: el interes entre el cargo
         * y la reversion se perdia entero. Anulado el cobro, se debe lo que se debia sin el.
         */
        REVERSION {
            @Override
            Dinero ejercer(long titular) {
                String recibo = "RECIBO 001-0000366";
                cobrar(titular, recibo, deudaA(titular, COBRO_ANTERIOR).total(), COBRO_ANTERIOR);
                enTransaccion(
                        () -> abonos.reversarAbonos(recibo, "ANULACION 001-0000366", ACTO, PORQUE));
                return Dinero.CERO;
            }
        };

        /** Ejerce el acto con fecha {@link #ACTO} y devuelve cuanto extinguio. */
        abstract Dinero ejercer(long titular);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Camino.class)
    @DisplayName("ni interes negativo ni devengo perdido: al dia siguiente, un dia mas de mora")
    void elActoNoPierdeElDevengo(Camino camino) throws SQLException {
        long titular = nuevoTitular();
        sembrarElCargo(titular);

        // La siembra distingue: hay devengo, y no esta en el libro. Sin esto la prueba pasaria
        // con el codigo de antes de #365, igual que pasan todas las que montan SinAcumulacion.
        assertThat(deudaA(titular, ACTO).total())
                .as("400,00 de insoluto mas 151 dias a 1,00 de interes todavia sin asentar")
                .isEqualTo(DEUDA_EL_DIA_DEL_ACTO);
        assertThat(calculo.asentadoA(delLibro(titular), ACTO).interes())
                .as("y ni un centimo de ese interes esta en el libro")
                .isEqualTo(Dinero.CERO);

        Dinero extinguido = camino.ejercer(titular);

        DeudaActualizada asentado = calculo.asentadoA(delLibro(titular), DIA_SIGUIENTE);
        assertThat(asentado.interes().esNegativo())
                .as(
                        camino
                                + ": el interes asentado es "
                                + asentado.interes()
                                + " — un abono de interes que el libro nunca cargo")
                .isFalse();

        DeudaActualizada despues = deudaA(titular, DIA_SIGUIENTE);
        Dinero unDiaMas = despues.insoluto().esPositivo() ? POR_DIA : Dinero.CERO;
        assertThat(despues.total())
                .as(
                        camino
                                + ": al "
                                + DIA_SIGUIENTE
                                + " se debe lo de antes ("
                                + DEUDA_EL_DIA_DEL_ACTO
                                + "), menos lo extinguido ("
                                + extinguido
                                + "), mas un dia de mora sobre el insoluto que queda ("
                                + despues.insoluto()
                                + ")")
                .isEqualTo(DEUDA_EL_DIA_DEL_ACTO.menos(extinguido).mas(unDiaMas));
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    /** El cargo de origen: INSOLUTO 400,00 en la cuota anual, con su referencia. */
    private static void sembrarElCargo(long titular) {
        enTransaccion(
                () ->
                        registrar.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        REFERENCIA,
                                        INSOLUTO,
                                        CARGO,
                                        "ACTA " + REFERENCIA),
                                Observacion.de("Emision de la prueba de #365")));
    }

    private static void cobrar(long titular, String recibo, Dinero cobrado, LocalDate fecha) {
        enTransaccion(
                () ->
                        abonos.abonarPagoIntegro(
                                List.of(new ObligacionDelDeudor(titular, seleccion())),
                                cobrado,
                                fecha,
                                recibo,
                                PORQUE));
    }

    private static void darDeBaja(
            long titular, Dinero insoluto, Dinero interes, String documentoOrigen) {
        MovimientoDeDeuda baja =
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.BAJA,
                        claveDe(titular),
                        insoluto,
                        Dinero.CERO,
                        interes,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        ACTO,
                        documentoOrigen,
                        null,
                        CausalDeBaja.ERROR_MATERIAL);
        enTransaccion(
                () ->
                        movimientos.registrar(
                                baja,
                                RangoDeCuotas.ANUAL,
                                RegistrarMovimientoDeDeuda.ComprobacionDeUnidad.NO_APLICA,
                                "DV-365",
                                PORQUE));
    }

    private static SeleccionDeObligacion seleccion() {
        return new SeleccionDeObligacion(TRIBUTO, EJERCICIO, null, null);
    }

    private static ClaveDeSaldo claveDe(long titular) {
        return new ClaveDeSaldo(titular, TRIBUTO, EJERCICIO, 0, null, null);
    }

    private static List<Asiento> delLibro(long titular) {
        return enTransaccion(() -> asientos.deLaObligacion(claveDe(titular)));
    }

    /** La deuda de la cuota anual a esa fecha, con la mora que devenga. */
    private static DeudaActualizada deudaA(long titular, LocalDate fecha) {
        return calculo.deudaActualizadaA(delLibro(titular), fecha, REDONDEO);
    }

    private static <T> T enTransaccion(Supplier<T> que) {
        return Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    /**
     * Una {@link PoliticaDeMora} que SI devenga: un importe fijo por dia entre el ultimo movimiento
     * y la fecha de corte, sobre cualquier insoluto positivo. No pretende ser la formula real —eso
     * es D-02—: es lo que hace que el devengo exista y se pueda perder.
     */
    private record MoraQueDevenga(Dinero porDia) implements PoliticaDeMora {

        @Override
        public Dinero reajusteAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            long dias = ChronoUnit.DAYS.between(desde, hasta);
            return porDia.por(BigDecimal.valueOf(dias)).redondeadoCon(redondeo);
        }
    }

    /** Ningun camino de esta prueba lleva unidad: la comprobacion de #635 no se ejerce. */
    private static final TitularesDeLaUnidad SIN_UNIDAD =
            new TitularesDeLaUnidad() {
                @Override
                public TitularidadDeLaUnidad delPredio(long predioId, LocalDate fecha) {
                    throw new AssertionError("Esta prueba no siembra ningun predio");
                }

                @Override
                public TitularidadDeLaUnidad delVehiculo(long vehiculoId, LocalDate fecha) {
                    throw new AssertionError("Esta prueba no siembra ningun vehiculo");
                }
            };

    // ------------------------------------------------------------------

    private static long nuevoTitular() throws SQLException {
        siguienteCodigo++;
        String codigo = String.format("DV-%04d", siguienteCodigo);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, DEVENGO',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("7365%04d", siguienteCodigo));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
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
