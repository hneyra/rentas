package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La liquidación de fiscalización contra PostgreSQL de verdad, conectado como {@code sgtm_app}
 * (#49).
 *
 * <p>Conectado como {@code sgtm_app} y no como el superusuario que Testcontainers entrega por
 * omisión: un superusuario <b>omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}</b>, y una
 * prueba escrita sobre esa conexión pasa en verde sin verificar nada (CAL-01 §3.2).
 */
@DisplayName("#49 — Liquidacion de fiscalizacion contra PostgreSQL")
class LiquidacionJdbcTest {

    private static final Observacion OBSERVACION = Observacion.de("Se liquida para la prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final Ejercicio E2026 = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static LiquidacionRepositoryJdbc liquidaciones;
    private static MovimientoDeLiquidacionRepositoryJdbc movimientos;
    private static JdbcClient jdbc;
    private static DriverManagerDataSource pool;

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(1);

    private static final java.util.Map<Long, Long> CONJUNTOS = new java.util.HashMap<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250901", "Municipalidad de liquidacion A");
        municipalidadB = crearMunicipalidad("250902", "Municipalidad de liquidacion B");

        pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        liquidaciones = new LiquidacionRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeLiquidacionRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("fiscalizador.campo", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura y lectura")
    class EscrituraYLectura {

        @Test
        @DisplayName("una liquidacion se guarda con su detalle y su conjunto sellado")
        void seGuardaConSuDetalle() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);

            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(
                                                    linea(
                                                            escenario,
                                                            CondicionFiscalizada.SUBVALUADOR))));

            assertThat(guardada.id()).isNotNull();
            assertThat(guardada.usuarioRegistro()).isEqualTo("fiscalizador.campo");

            List<LineaDeLiquidacion> lineas =
                    transaccion.execute(estado -> liquidaciones.lineasDe(guardada.identificador()));
            assertThat(lineas).hasSize(1);
            assertThat(lineas.get(0).conjuntoId()).isEqualTo(escenario.conjunto2024);
            assertThat(lineas.get(0).insolutoOmitido())
                    .as("ningun importe: D-02a sigue abierta (#198)")
                    .isNull();
        }

        @Test
        @DisplayName("liquidar no modifica ninguna fila de catastro ni de rentas (AC 4)")
        void liquidarNoTocaElPadron() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);

            long prediosAntes = transaccion.execute(estado -> contar("predio"));
            long fichasAntes = transaccion.execute(estado -> contar("ficha_catastral"));
            long djAntes = transaccion.execute(estado -> contar("declaracion_jurada"));

            transaccion.execute(
                    estado ->
                            liquidaciones.insertar(
                                    primera(escenario),
                                    List.of(linea(escenario, CondicionFiscalizada.SUBVALUADOR))));

            assertThat((long) transaccion.execute(estado -> contar("predio")))
                    .isEqualTo(prediosAntes);
            assertThat((long) transaccion.execute(estado -> contar("ficha_catastral")))
                    .isEqualTo(fichasAntes);
            assertThat((long) transaccion.execute(estado -> contar("declaracion_jurada")))
                    .as("la comparacion se hace sobre copias: rentas no se toca")
                    .isEqualTo(djAntes);
        }

        @Test
        @DisplayName("la linea copia el conjunto SELLADO, y nada lo puede mover despues (AC 1)")
        void laLineaCopiaElConjuntoSelladoYNadieLoMueve() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);

            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(
                                                    linea(
                                                            escenario,
                                                            CondicionFiscalizada.SUBVALUADOR))));

            List<LineaDeLiquidacion> lineas =
                    transaccion.execute(estado -> liquidaciones.lineasDe(guardada.identificador()));

            assertThat(lineas.get(0).conjuntoId()).isEqualTo(escenario.conjunto2024);
            assertThat(estadoDelConjunto(escenario.conjunto2024))
                    .as("y es un conjunto SELLADO: con uno abierto la cifra cambiaria manana")
                    .isEqualTo("SELLADO");
            assertThat(
                            errorDe(
                                    "UPDATE liquidacion_detalle SET conjunto_id = 0 WHERE"
                                            + " liquidacion_id = "
                                            + guardada.identificador()))
                    .as(
                            "cambiar los parametros de hoy no altera una liquidacion emitida: la"
                                    + " columna esta COPIADA y sgtm_app no la puede mover (AC 1)")
                    .contains("42501");
        }

        @Test
        @DisplayName("una liquidacion sin lineas se rechaza")
        void sinLineasSeRechaza() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    liquidaciones.insertar(
                                                            primera(escenario), List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades")
    class Aislamiento {

        @Test
        @DisplayName("desde B no se ve la liquidacion de A")
        void desdeBNoSeVeLaDeA() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario deA = sembrar(municipalidadA);
            Liquidacion deLaA =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(deA),
                                            List.of(linea(deA, CondicionFiscalizada.OMISO))));

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            java.util.Optional<Liquidacion> desdeB =
                    transaccion.execute(estado -> liquidaciones.porNumero(deLaA.numero()));
            List<LineaDeLiquidacion> lineasDesdeB =
                    transaccion.execute(estado -> liquidaciones.lineasDe(deLaA.identificador()));

            assertThat(desdeB)
                    .as("la politica RLS, no un WHERE que alguien puede olvidar")
                    .isEmpty();
            assertThat(lineasDesdeB).isEmpty();
        }
    }

    @Nested
    @DisplayName("Solo se agrega")
    class SoloSeAgrega {

        @Test
        @DisplayName("sgtm_app no puede actualizar la cabecera, ni el detalle, ni el movimiento")
        void sgtmAppNoPuedeActualizar() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);
            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(
                                                    linea(
                                                            escenario,
                                                            CondicionFiscalizada.SUBVALUADOR))));
            transaccion.execute(
                    estado ->
                            movimientos.insertar(
                                    MovimientoDeLiquidacion.apertura(
                                            guardada.identificador(),
                                            HOY,
                                            "emitida",
                                            OBSERVACION)));

            assertThat(
                            errorDe(
                                    "UPDATE liquidacion_fiscalizacion SET motivo_determinante = 'x'"
                                            + " WHERE id = "
                                            + guardada.identificador()))
                    .as("V39 no le concede UPDATE: el papel notificado y la base no pueden diferir")
                    .contains("42501");
            assertThat(
                            errorDe(
                                    "UPDATE liquidacion_detalle SET area_hallada = 1 WHERE"
                                            + " liquidacion_id = "
                                            + guardada.identificador()))
                    .contains("42501");
            assertThat(
                            errorDe(
                                    "UPDATE liquidacion_movimiento SET estado = 'ANULADA' WHERE"
                                            + " liquidacion_id = "
                                            + guardada.identificador()))
                    .contains("42501");
            assertThat(
                            errorDe(
                                    "DELETE FROM liquidacion_fiscalizacion WHERE id = "
                                            + guardada.identificador()))
                    .contains("42501");
        }

        @Test
        @DisplayName("la apertura es unica: la segunda la rechaza la base, no un if")
        void laAperturaEsUnica() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);
            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(linea(escenario, CondicionFiscalizada.OMISO))));

            transaccion.execute(
                    estado ->
                            movimientos.insertar(
                                    MovimientoDeLiquidacion.apertura(
                                            guardada.identificador(),
                                            HOY,
                                            "emitida",
                                            OBSERVACION)));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    movimientos.insertar(
                                                            MovimientoDeLiquidacion.apertura(
                                                                    guardada.identificador(),
                                                                    HOY,
                                                                    "otra vez",
                                                                    OBSERVACION))))
                    .isInstanceOf(MovimientoDeLiquidacionRepository.AperturaDuplicada.class);
        }

        @Test
        @DisplayName("dos versiones del mismo acta con el mismo numero de version se rechazan")
        void dosVersionesIgualesSeRechazan() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);
            transaccion.execute(
                    estado ->
                            liquidaciones.insertar(
                                    primera(escenario),
                                    List.of(linea(escenario, CondicionFiscalizada.OMISO))));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    liquidaciones.insertar(
                                                            primera(escenario),
                                                            List.of(
                                                                    linea(
                                                                            escenario,
                                                                            CondicionFiscalizada
                                                                                    .OMISO)))))
                    .as("liquidacion_version_uq (V39)")
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }
    }

    @Nested
    @DisplayName("La numeracion")
    class Numeracion {

        @Test
        @DisplayName("diez hilos piden correlativo a la vez y salen diez numeros distintos")
        void diezHilosDiezNumerosDistintos() throws Exception {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            ExecutorService piscina = Executors.newFixedThreadPool(hilos);
            List<Future<Long>> resultados = new ArrayList<>();

            try {
                for (int i = 0; i < hilos; i++) {
                    resultados.add(
                            piscina.submit(
                                    () -> {
                                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                        OrigenContext.fijar(
                                                new Origen("fiscalizador.campo", null, null));
                                        salida.await();
                                        try {
                                            return transaccion.execute(
                                                    estado ->
                                                            liquidaciones.siguienteCorrelativo(
                                                                    E2026));
                                        } finally {
                                            TenantContext.limpiar();
                                            OrigenContext.limpiar();
                                        }
                                    }));
                }
                salida.countDown();

                List<Long> numeros = new ArrayList<>();
                for (Future<Long> resultado : resultados) {
                    numeros.add(resultado.get(30, TimeUnit.SECONDS));
                }

                assertThat(numeros)
                        .as(
                                "el UPSERT en una sola sentencia: con SELECT + UPDATE dos"
                                        + " liquidaciones simultaneas saldrian con el mismo numero"
                                        + " impreso")
                        .doesNotHaveDuplicates()
                        .hasSize(hilos);
            } finally {
                piscina.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("La consulta")
    class Consulta {

        @Test
        @DisplayName("«solo la ultima version» descarta la sustituida")
        void soloLaUltimaVersion() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);

            Liquidacion primera =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(linea(escenario, CondicionFiscalizada.OMISO))));
            Liquidacion segunda =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera.reliquidadaPor(
                                                    "LIQ-2026-"
                                                            + String.format(
                                                                    "%06d", SIGUIENTE.get()),
                                                    E2026,
                                                    SIGUIENTE.getAndIncrement(),
                                                    E2024,
                                                    E2024,
                                                    TipoDeFiscalizacion.CIERTA,
                                                    "Area corregida",
                                                    HOY,
                                                    OBSERVACION),
                                            List.of(
                                                    linea(
                                                            escenario,
                                                            CondicionFiscalizada.CONFORME))));

            List<Liquidacion> vigentes =
                    transaccion
                            .execute(
                                    estado ->
                                            liquidaciones.consultar(
                                                    CriterioDeLiquidaciones.vigentes(),
                                                    Paginacion.de(0, 50, "numero")))
                            .contenido();

            assertThat(vigentes.stream().map(Liquidacion::numero))
                    .contains(segunda.numero())
                    .doesNotContain(primera.numero());

            List<Liquidacion> todas =
                    transaccion.execute(estado -> liquidaciones.versionesDeActa(escenario.actaId));
            assertThat(todas)
                    .as("el historico las enseña todas, de la primera a la ultima (AC 5)")
                    .extracting(Liquidacion::version)
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("el filtro de estado usa el ultimo movimiento, no una columna")
        void elFiltroDeEstadoUsaElUltimoMovimiento() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);
            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(linea(escenario, CondicionFiscalizada.OMISO))));
            transaccion.execute(
                    estado ->
                            movimientos.insertar(
                                    MovimientoDeLiquidacion.apertura(
                                            guardada.identificador(),
                                            HOY,
                                            "emitida",
                                            OBSERVACION)));
            transaccion.execute(
                    estado ->
                            movimientos.insertar(
                                    MovimientoDeLiquidacion.cambioDeEstado(
                                            guardada.identificador(),
                                            EstadoDeLiquidacion.NOTIFICADA,
                                            HOY,
                                            "entregada",
                                            OBSERVACION)));

            assertThat(
                            transaccion
                                    .execute(
                                            estado ->
                                                    liquidaciones.consultar(
                                                            new CriterioDeLiquidaciones(
                                                                    guardada.numero(),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    EstadoDeLiquidacion.NOTIFICADA,
                                                                    false),
                                                            Paginacion.de(0, 10, "numero")))
                                    .contenido())
                    .hasSize(1);

            assertThat(
                            transaccion
                                    .execute(
                                            estado ->
                                                    liquidaciones.consultar(
                                                            new CriterioDeLiquidaciones(
                                                                    guardada.numero(),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    EstadoDeLiquidacion.ABIERTA,
                                                                    false),
                                                            Paginacion.de(0, 10, "numero")))
                                    .contenido())
                    .as("ya no esta ABIERTA: el estado es el del ultimo movimiento")
                    .isEmpty();
        }

        @Test
        @DisplayName("el filtro de hallazgo mira el detalle")
        void elFiltroDeHallazgoMiraElDetalle() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Escenario escenario = sembrar(municipalidadA);
            Liquidacion guardada =
                    transaccion.execute(
                            estado ->
                                    liquidaciones.insertar(
                                            primera(escenario),
                                            List.of(
                                                    linea(
                                                            escenario,
                                                            CondicionFiscalizada.SUBVALUADOR))));

            assertThat(
                            transaccion
                                    .execute(
                                            estado ->
                                                    liquidaciones.consultar(
                                                            new CriterioDeLiquidaciones(
                                                                    guardada.numero(),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    CondicionFiscalizada
                                                                            .SUBVALUADOR,
                                                                    null,
                                                                    false),
                                                            Paginacion.de(0, 10, "numero")))
                                    .contenido())
                    .hasSize(1);

            assertThat(
                            transaccion
                                    .execute(
                                            estado ->
                                                    liquidaciones.consultar(
                                                            new CriterioDeLiquidaciones(
                                                                    guardada.numero(),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    CondicionFiscalizada.OMISO,
                                                                    null,
                                                                    false),
                                                            Paginacion.de(0, 10, "numero")))
                                    .contenido())
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------

    private record Escenario(long actaId, long predioId, long contribuyenteId, long conjunto2024) {}

    private static Liquidacion primera(Escenario escenario) {
        int numero = SIGUIENTE.getAndIncrement();
        return Liquidacion.primera(
                "LIQ-2026-" + String.format("%06d", numero),
                E2026,
                numero,
                escenario.actaId,
                E2024,
                E2024,
                TipoDeFiscalizacion.CIERTA,
                "Ampliacion detectada en inspeccion",
                HOY,
                OBSERVACION);
    }

    private static LineaDeLiquidacion linea(Escenario escenario, CondicionFiscalizada condicion) {
        return LineaDeLiquidacion.predialSinCifras(
                E2024,
                escenario.conjunto2024,
                escenario.predioId,
                condicion,
                AreaM2.de("120.00"),
                AreaM2.de("300.00"),
                "CASA_HABITACION",
                "CASA_HABITACION");
    }

    /** El SQLSTATE del error, ejecutando como {@code sgtm_app} con contexto de tenant. */
    private static String errorDe(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                return "sin error";
            }
        } catch (SQLException fallo) {
            return fallo.getSQLState() + " " + fallo.getMessage();
        }
    }

    /**
     * El estado del conjunto, <b>dentro de una transaccion</b>.
     *
     * <p>La transaccion no es adorno: sin ella no hay {@code SET LOCAL}, y la politica RLS de
     * {@code conjunto_parametros} falla en vez de devolver filas. La primera version de esta
     * comprobacion leia con el {@code JdbcClient} desnudo y salia {@code BadSqlGrammarException},
     * que no se parece en nada a su causa.
     */
    private static String estadoDelConjunto(long conjuntoId) {
        return transaccion.execute(
                estado ->
                        jdbc.sql("SELECT estado FROM conjunto_parametros_de_prueba WHERE id = :id")
                                .param("id", conjuntoId)
                                .query(String.class)
                                .single());
    }

    private static long contar(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla).query(Long.class).single();
    }

    private static Escenario sembrar(long municipalidadId) {
        String sufijo = String.valueOf(SIGUIENTE.getAndIncrement());
        long contribuyente =
                ejecutarComoApp(
                        municipalidadId,
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro)"
                                + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                + " 'siembra') RETURNING id",
                        municipalidadId,
                        "L-" + sufijo,
                        String.format("%08d", 61000000 + Integer.parseInt(sufijo)));
        long predio =
                ejecutarComoApp(
                        municipalidadId,
                        "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                + " direccion)"
                                + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                        municipalidadId,
                        String.format("%018d", Integer.parseInt(sufijo)));
        long ficha =
                ejecutarComoApp(
                        municipalidadId,
                        "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                                + " observacion, usuario_registro)"
                                + " VALUES (?, ?, 'UNICA', 1, 300.00, 'CASA_HABITACION', ?,"
                                + "         'DECLARACION_JURADA', 'DJ-001', 'ficha', 'siembra')"
                                + " RETURNING id",
                        municipalidadId,
                        predio,
                        LocalDate.of(2024, 1, 1));
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                        + " contribuyente_id, tipo, predio_id, ficha_catastral_id,"
                        + " fecha_presentacion, fecha_limite, usuario_registro, observacion)"
                        + " VALUES (?, ?, 2024, ?, 'HR', ?, ?, ?, ?, 'siembra', 'dj de prueba')"
                        + " RETURNING id",
                municipalidadId,
                "DJ-" + sufijo,
                contribuyente,
                predio,
                ficha,
                LocalDate.of(2024, 2, 20),
                LocalDate.of(2024, 2, 28));
        long programa =
                ejecutarComoApp(
                        municipalidadId,
                        "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion,"
                                + " tipo, fecha_inicio)"
                                + " VALUES (?, ?, 'Programa de prueba', 'PREDIAL', ?) RETURNING id",
                        municipalidadId,
                        "PF-L" + sufijo,
                        LocalDate.of(2026, 1, 1));
        long acta =
                ejecutarComoApp(
                        municipalidadId,
                        "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                                + " contribuyente_id, predio_id, ficha_id, fecha_visita,"
                                + " fiscalizador, hallazgo, area_hallada, estado, observacion,"
                                + " usuario_registro)"
                                + " VALUES (?, ?, 1, ?, ?, ?, ?, 'J. Perez', 'SUBVALUADOR', 300.00,"
                                + "         'ABIERTA', 'acta de prueba', 'siembra') RETURNING id",
                        municipalidadId,
                        programa,
                        contribuyente,
                        predio,
                        ficha,
                        LocalDate.of(2026, 3, 1));

        return new Escenario(acta, predio, contribuyente, conjuntoSelladoDe(municipalidadId));
    }

    /**
     * El conjunto SELLADO de 2024 de esa municipalidad, creado una sola vez.
     *
     * <p>Memoizado <b>porque la base lo exige</b>: {@code conjunto_sellado_uq} (V9) admite un solo
     * conjunto sellado por municipalidad y ejercicio, de modo que sembrar uno por escenario
     * reventaria en el segundo. Que solo pueda haber uno es justamente lo que hace comprobable el
     * AC 1: la linea copia el identificador de ESE conjunto y nada puede moverlo despues.
     */
    private static long conjuntoSelladoDe(long municipalidadId) {
        return CONJUNTOS.computeIfAbsent(
                municipalidadId,
                id ->
                        ejecutarComoApp(
                                id,
                                "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio,"
                                        + " version, estado, fecha_sellado, usuario_sellado)"
                                        + " VALUES (?, 2024, 1, 'SELLADO', now(), 'siembra')"
                                        + " RETURNING id",
                                id));
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

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
