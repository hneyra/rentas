package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
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
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.dominio.EstadoDeItemMasivo;
import kamayuk.rentas.valores.dominio.OrigenDeCriterio;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.infraestructura.ValorMasivoRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

/**
 * #400 — La generacion masiva de valores contra PostgreSQL de verdad, conectado como {@code
 * kamayuk_app}.
 *
 * <p>Hasta #400 la unica prueba de {@link GenerarCorridaMasiva} usaba dobles en memoria, y con
 * dobles el defecto que {@code sanciones} ya habia pagado no aparece nunca: el bucle <b>no
 * puede</b> llevar transaccion —cada candidato va en la suya—, y sin transaccion no hay {@code SET
 * LOCAL}, asi que la politica RLS de {@code valor_masivo} no puede evaluar {@code
 * current_setting('app.municipalidad_id')} y la primera lectura revienta antes de procesar un solo
 * candidato. Aqui la corrida se genera <b>fuera de transaccion</b>, como la llama el proceso batch.
 */
@DisplayName("#400 — La generacion masiva de valores contra PostgreSQL")
class GeneracionMasivaJdbcTest {

    /** El dia de la fecha de criterio: la deuda de 2026 ya esta cargada. */
    private static final LocalDate CRITERIO = LocalDate.of(2026, 4, 20);

    private static final LocalDate VENCIMIENTO = LocalDate.of(2026, 2, 27);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Observacion PORQUE = Observacion.de("Corrida masiva de la prueba de #400");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long municipalidadSinNada;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static ValorMasivoRepositoryJdbc repositorioMasivo;
    private static RegistrarAsiento registrarAsiento;
    private static ConsultaDeLaCorridaMasiva lectura;
    private static GenerarCorridaMasiva generar;
    private static RecorridoPorMunicipalidades registro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("400101", "Municipalidad A de #400");
        municipalidadB = crearMunicipalidad("400102", "Municipalidad B de #400");
        municipalidadSinNada = crearMunicipalidad("400103", "Municipalidad sin corridas de #400");

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
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));

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
        RegistrarValor registrarValor =
                envolver(
                        new RegistrarValor(
                                new ValorRepositoryJdbc(jdbc), deudas, fases, auditoria, RELOJ));

        repositorioMasivo = new ValorMasivoRepositoryJdbc(jdbc);
        ProcesarItemMasivo procesar =
                envolver(new ProcesarItemMasivo(deudas, registrarValor, repositorioMasivo));

        // La lectura SI se envuelve —su @Transactional es lo que esta clase defiende—, y el
        // bucle NO: es exactamente como lo llama el proceso batch, fuera de toda transaccion
        // (GenerarCorridaMasiva, «Este metodo NO lleva @Transactional»).
        lectura = envolver(new ConsultaDeLaCorridaMasiva(repositorioMasivo));
        generar = new GenerarCorridaMasiva(lectura, procesar);
        registro = new RecorridoPorMunicipalidades(jdbc, gestor);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("La lectura de la corrida")
    class LaLectura {

        /**
         * La prueba que el issue pide: la corrida se genera fuera de transaccion, con el contexto
         * de tenant puesto como lo pone el proceso batch y nada mas.
         *
         * <p>Con la corrida leida por el repositorio desnudo sale roja en {@code porId}, antes de
         * procesar un solo candidato: {@code unrecognized configuration parameter
         * "app.municipalidad_id"}.
         */
        @Test
        @DisplayName("generar fuera de transaccion lee la corrida y resuelve sus candidatos")
        void generarFueraDeTransaccionLeeLaCorrida() {
            long conDeuda = crearContribuyente(municipalidadA, "L-DEUDA");
            long pagado = crearContribuyente(municipalidadA, "L-PAGADO");
            cargo(municipalidadA, conDeuda, "300.00");
            cargo(municipalidadA, pagado, "120.00");
            abono(municipalidadA, pagado, "120.00");
            long corrida = corridaDe(municipalidadA, conDeuda, pagado);

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            OrigenContext.fijar(Origen.deProceso("prueba-400"));
            GenerarCorridaMasiva.Informe informe = generar.generar(corrida);

            assertThat(informe.generados()).as("el candidato con deuda").isEqualTo(1);
            assertThat(informe.sinDeuda()).as("el candidato que ya pago").isEqualTo(1);
            assertThat(informe.fallidos()).isZero();
            assertThat(estadoDe(municipalidadA, corrida, conDeuda))
                    .isEqualTo(EstadoDeItemMasivo.GENERADO);
            assertThat(estadoDe(municipalidadA, corrida, pagado))
                    .isEqualTo(EstadoDeItemMasivo.SIN_DEUDA);
        }
    }

    @Nested
    @DisplayName("El invocador: CorrerLasCorridasDeValores")
    class ElInvocador {

        /**
         * La siembra que distingue (#400): dos municipalidades, cada una con una corrida {@code
         * PENDIENTE} de tres candidatos distintos —uno con deuda, uno que ya pago y uno que falla—,
         * y una tercera sin nada.
         *
         * <p>Con una sola municipalidad, un runner que fijara el contexto una vez y no por
         * municipalidad pasaria igual. Con tres candidatos iguales, uno que marcara todo {@code
         * GENERADO} tambien. El que falla lo hace <b>de verdad</b>: un disparador rechaza su valor
         * dentro de la transaccion del candidato, y lo que se comprueba es que esa transaccion se
         * deshizo entera —ni valor ni item movido— y que el resto de la corrida siguio.
         */
        @Test
        @DisplayName(
                "cada candidato queda GENERADO, SIN_DEUDA o PENDIENTE en su municipalidad, y la"
                        + " corrida que no avanza pone el proceso en rojo")
        void recorreLasMunicipalidadesUnaAUna() throws SQLException {
            Siembra enA = sembrar(municipalidadA, "A");
            Siembra enB = sembrar(municipalidadB, "B");
            hacerFallarLaEmisionDe(enA.falla(), enB.falla());

            CorrerLasCorridasDeValores runner =
                    new CorrerLasCorridasDeValores(registro, lectura, generar);
            runner.run(null);

            for (Siembra siembra : List.of(enA, enB)) {
                assertThat(estadoDe(siembra.municipalidad(), siembra.corrida(), siembra.conDeuda()))
                        .as(
                                "el candidato con deuda de la municipalidad %d",
                                siembra.municipalidad())
                        .isEqualTo(EstadoDeItemMasivo.GENERADO);
                assertThat(estadoDe(siembra.municipalidad(), siembra.corrida(), siembra.pagado()))
                        .as("el que ya pago, en la municipalidad %d", siembra.municipalidad())
                        .isEqualTo(EstadoDeItemMasivo.SIN_DEUDA);
                assertThat(estadoDe(siembra.municipalidad(), siembra.corrida(), siembra.falla()))
                        .as(
                                "el que fallo se queda PENDIENTE para la ventana siguiente, en la"
                                        + " municipalidad %d",
                                siembra.municipalidad())
                        .isEqualTo(EstadoDeItemMasivo.PENDIENTE);
            }

            assertThat(valoresDe(enA, enB))
                    .as(
                            "un valor por municipalidad, del contribuyente con deuda de ESA"
                                    + " municipalidad: ninguno cruza, y el que fallo no dejo nada")
                    .containsExactlyInAnyOrder(
                            municipalidadA + ":" + enA.conDeuda(),
                            municipalidadB + ":" + enB.conDeuda());
            assertThat(corridasDe(municipalidadSinNada))
                    .as("la tercera municipalidad no tenia nada, y sigue sin nada")
                    .isZero();
            assertThat(runner.getExitCode())
                    .as("las dos corridas avanzaron: el proceso sale con 0")
                    .isZero();

            // La ventana siguiente: lo unico que queda es el que falla, y vuelve a fallar.
            runner.run(null);

            assertThat(runner.getExitCode())
                    .as("dos corridas que ya no avanzan: el proceso sale distinto de cero")
                    .isEqualTo(1);
            assertThat(runner.sinAvance())
                    .anySatisfy(
                            linea ->
                                    assertThat(linea)
                                            .contains("municipalidad " + municipalidadA)
                                            .contains("corrida " + enA.corrida()))
                    .anySatisfy(
                            linea ->
                                    assertThat(linea)
                                            .contains("municipalidad " + municipalidadB)
                                            .contains("corrida " + enB.corrida()));
            assertThat(TenantContext.actualSiHay())
                    .as("el contexto se limpia al acabar: nada corre despues con el de la ultima")
                    .isEmpty();
        }
    }

    /** Lo sembrado en una municipalidad: su corrida y sus tres candidatos. */
    private record Siembra(
            long municipalidad, long corrida, long conDeuda, long pagado, long falla) {}

    private static Siembra sembrar(long municipalidad, String sufijo) {
        long conDeuda = crearContribuyente(municipalidad, "R-DEUDA-" + sufijo);
        long pagado = crearContribuyente(municipalidad, "R-PAGADO-" + sufijo);
        long falla = crearContribuyente(municipalidad, "R-FALLA-" + sufijo);
        cargo(municipalidad, conDeuda, "300.00");
        cargo(municipalidad, pagado, "120.00");
        abono(municipalidad, pagado, "120.00");
        cargo(municipalidad, falla, "200.00");
        long corrida = corridaDe(municipalidad, conDeuda, pagado, falla);
        return new Siembra(municipalidad, corrida, conDeuda, pagado, falla);
    }

    /**
     * Un disparador que rechaza el valor de esos contribuyentes con una violacion de {@code CHECK}:
     * un fallo de la base dentro de la transaccion del candidato, que es lo que {@code generar}
     * atrapa ({@code DataAccessException}) para seguir con el resto.
     */
    private static void hacerFallarLaEmisionDe(long... contribuyentes) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (long id : contribuyentes) {
            ids.add(Long.toString(id));
        }
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute(
                    "CREATE FUNCTION prueba_400_rechaza_el_valor() RETURNS trigger"
                            + " LANGUAGE plpgsql AS $$ BEGIN"
                            + " IF NEW.contribuyente_id IN ("
                            + String.join(", ", ids)
                            + ") THEN RAISE EXCEPTION 'candidato que falla (#400)'"
                            + " USING ERRCODE = 'check_violation'; END IF;"
                            + " RETURN NEW; END $$");
            sentencia.execute(
                    "CREATE TRIGGER prueba_400_rechaza_el_valor BEFORE INSERT ON valor"
                            + " FOR EACH ROW EXECUTE FUNCTION prueba_400_rechaza_el_valor()");
        }
    }

    /**
     * Los valores emitidos a los seis contribuyentes de la siembra, como {@code
     * municipalidad:contribuyente}, leidos como superusuario —que no pasa por la RLS— para ver
     * <b>todas</b> las filas y no las de una municipalidad.
     */
    private static List<String> valoresDe(Siembra... siembras) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (Siembra siembra : siembras) {
            ids.add(Long.toString(siembra.conDeuda()));
            ids.add(Long.toString(siembra.pagado()));
            ids.add(Long.toString(siembra.falla()));
        }
        List<String> valores = new ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet filas =
                        sentencia.executeQuery(
                                "SELECT municipalidad_id, contribuyente_id FROM valor"
                                        + " WHERE contribuyente_id IN ("
                                        + String.join(", ", ids)
                                        + ")")) {
            while (filas.next()) {
                valores.add(filas.getLong(1) + ":" + filas.getLong(2));
            }
        }
        return valores;
    }

    private static long corridasDe(long municipalidad) {
        return enTransaccionDe(
                municipalidad,
                () -> jdbc.sql("SELECT count(*) FROM valor_masivo").query(Long.class).single());
    }

    // ------------------------------------------------------------------
    //  Siembra
    // ------------------------------------------------------------------

    private static long corridaDe(long municipalidad, Long... contribuyentes) {
        ValorMasivo corrida =
                new ValorMasivo(
                        null,
                        TipoValor.ORDEN_DE_PAGO,
                        "PREDIAL",
                        EJERCICIO,
                        EJERCICIO,
                        CRITERIO,
                        OrigenDeCriterio.SELECCION,
                        contribuyentes.length,
                        null,
                        null,
                        PORQUE);
        ValorMasivo guardada =
                enTransaccionDe(
                        municipalidad,
                        () -> repositorioMasivo.iniciar(corrida, List.of(contribuyentes)));
        return java.util.Objects.requireNonNull(guardada.id());
    }

    private static EstadoDeItemMasivo estadoDe(
            long municipalidad, long corrida, long contribuyente) {
        return enTransaccionDe(
                municipalidad,
                () ->
                        EstadoDeItemMasivo.valueOf(
                                jdbc.sql(
                                                "SELECT estado FROM valor_masivo_item"
                                                        + " WHERE corrida_id = :corrida"
                                                        + "   AND contribuyente_id = :contribuyente")
                                        .param("corrida", corrida)
                                        .param("contribuyente", contribuyente)
                                        .query(String.class)
                                        .single()));
    }

    private static void cargo(long municipalidad, long contribuyente, String monto) {
        asentar(municipalidad, contribuyente, TipoAsiento.CARGO, monto, "DJ-2026");
    }

    /** Lo pagado: el abono del mismo insoluto, asentado por la puerta del libro. */
    private static void abono(long municipalidad, long contribuyente, String monto) {
        asentar(municipalidad, contribuyente, TipoAsiento.ABONO, monto, "RECIBO-2026");
    }

    private static void asentar(
            long municipalidad,
            long contribuyente,
            TipoAsiento tipo,
            String monto,
            String documento) {
        enTransaccionDe(
                municipalidad,
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyente,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        tipo,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Dinero.de(monto),
                                        VENCIMIENTO,
                                        documento),
                                Observacion.de("Siembra de la prueba de #400")));
    }

    private static <T> T enTransaccionDe(long municipalidad, Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(Origen.deProceso("siembra-400"));
        try {
            return transaccion.execute(estado -> accion.get());
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
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

    private static long crearContribuyente(long municipalidad, String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'QUISPE ROJAS, ANA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo + "-" + municipalidad);
                sentencia.setString(3, dniDe(codigo + municipalidad));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar el contribuyente " + codigo, fallo);
        }
    }

    private static String dniDe(String semilla) {
        return "4" + String.format("%07d", Math.abs(semilla.hashCode() % 10_000_000));
    }
}
