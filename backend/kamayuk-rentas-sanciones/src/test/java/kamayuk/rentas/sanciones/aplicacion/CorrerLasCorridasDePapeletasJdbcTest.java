package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import java.util.Locale;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.EstadoDeItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.infraestructura.CodigoInfraccionRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.CorridaDeValoresRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.NotificacionDeResolucionRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.PadronDePapeletasRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.PapeletaRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.ResolucionDeGerenciaRepositoryJdbc;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #400 — El invocador de la generacion masiva por papeletas, contra PostgreSQL de verdad y
 * conectado como {@code kamayuk_app}.
 *
 * <p>La siembra distingue lo que un runner mal escrito confundiria: <b>dos</b> municipalidades,
 * cada una con una corrida {@code PENDIENTE} de dos papeletas distintas —una sin resolucion que
 * ordene la cobranza, que sale {@code NO_PROCEDE} diciendolo, y una que falla de verdad dentro de
 * su transaccion—, y una tercera sin nada. Con una sola municipalidad, un runner que fijara el
 * contexto una vez pasaria igual.
 *
 * <p>Lo que <b>no</b> siembra es una papeleta exigible que llegue a emitir: esa cadena —resolucion
 * ordinaria dictada, notificada y con el plazo sellado vencido— la cubre {@code
 * ValoresMasivosYReportesJdbcTest}, llamando a la misma {@link GenerarCorridaDeValores}. Aqui la
 * emision es un doble que falla si alguien la alcanza: lo que esta prueba mide es el recorrido, y
 * que la etapa corra bajo el contexto de <b>cada</b> municipalidad. La siembra de tres desenlaces
 * con deuda de verdad es la de su gemelo, {@code valores.GeneracionMasivaJdbcTest}.
 */
@DisplayName("#400 — CorrerLasCorridasDePapeletas contra PostgreSQL")
class CorrerLasCorridasDePapeletasJdbcTest {

    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);
    private static final LocalDate CRITERIO = LocalDate.of(2026, 4, 15);
    private static final Dinero MULTA = Dinero.de("428.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba de #400");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long municipalidadSinNada;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static RegistrarPapeleta registrarPapeleta;
    private static IniciarCorridaDeValores iniciar;
    private static ConsultaDeLaCorridaDeValores lectura;
    private static GenerarCorridaDeValores generar;
    private static RecorridoPorMunicipalidades registro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("400201", "Municipalidad A de papeletas de #400");
        municipalidadB = crearMunicipalidad("400202", "Municipalidad B de papeletas de #400");
        municipalidadSinNada = crearMunicipalidad("400203", "Municipalidad sin papeletas de #400");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        RegistrarAsiento registrarAsiento =
                envolver(
                        new RegistrarAsiento(
                                new AsientoRepositoryJdbc(jdbc),
                                new SaldoRepositoryJdbc(jdbc),
                                auditoria,
                                RELOJ));
        PapeletaRepositoryJdbc papeletas = new PapeletaRepositoryJdbc(jdbc);
        CorridaDeValoresRepositoryJdbc corridas = new CorridaDeValoresRepositoryJdbc(jdbc);

        registrarPapeleta =
                envolver(
                        new RegistrarPapeleta(
                                papeletas,
                                new CodigoInfraccionRepositoryJdbc(jdbc),
                                envolver(new GeneradorDeCargosCuentaCorriente(registrarAsiento)),
                                auditoria));
        iniciar =
                envolver(
                        new IniciarCorridaDeValores(
                                papeletas,
                                new PadronDePapeletasRepositoryJdbc(jdbc),
                                corridas,
                                auditoria,
                                RELOJ));

        // Ninguna papeleta de esta siembra tiene la resolucion que ordena la cobranza, asi que
        // la emision no se alcanza nunca: si alguien la alcanzara, la prueba lo diria.
        EmisionDeValoresDeMultas sinEmision =
                (contribuyente, tributo, ejercicio, predio, vehiculo, referencia, fecha, obs) -> {
                    throw new AssertionError(
                            "Ninguna papeleta de esta siembra llega a emitir: no tiene resolucion");
                };
        ProcesarPapeletaDeLaCorrida procesar =
                envolver(
                        new ProcesarPapeletaDeLaCorrida(
                                papeletas,
                                new ResolucionDeGerenciaRepositoryJdbc(jdbc),
                                new NotificacionDeResolucionRepositoryJdbc(jdbc),
                                sinEmision,
                                corridas,
                                new kamayuk.rentas.sanciones.infraestructura.DescargoRepositoryJdbc(
                                        jdbc)));

        lectura = envolver(new ConsultaDeLaCorridaDeValores(corridas));
        // El bucle NO se envuelve: el runner lo llama fuera de toda transaccion.
        generar = new GenerarCorridaDeValores(lectura, procesar);
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

    @Test
    @DisplayName(
            "cada papeleta queda NO_PROCEDE o PENDIENTE en su municipalidad, y la corrida que no"
                    + " avanza pone el proceso en rojo")
    void recorreLasMunicipalidadesUnaAUna() throws SQLException {
        Siembra enA = sembrar(municipalidadA, "a");
        Siembra enB = sembrar(municipalidadB, "b");
        hacerFallarElCandidatoDe(enA.falla(), enB.falla());

        CorrerLasCorridasDePapeletas runner =
                new CorrerLasCorridasDePapeletas(registro, lectura, generar);
        runner.run(null);

        for (Siembra siembra : List.of(enA, enB)) {
            assertThat(
                            estadoDe(
                                    siembra.municipalidad(),
                                    siembra.corrida(),
                                    siembra.sinResolucion()))
                    .as(
                            "la papeleta sin resolucion se resuelve NO_PROCEDE en la municipalidad"
                                    + " %d",
                            siembra.municipalidad())
                    .isEqualTo(EstadoDeItemDeCorrida.NO_PROCEDE);
            assertThat(estadoDe(siembra.municipalidad(), siembra.corrida(), siembra.falla()))
                    .as(
                            "la que fallo se queda PENDIENTE en la municipalidad %d",
                            siembra.municipalidad())
                    .isEqualTo(EstadoDeItemDeCorrida.PENDIENTE);
        }
        assertThat(corridasDe(municipalidadSinNada))
                .as("la tercera municipalidad no tenia nada, y sigue sin nada")
                .isZero();
        assertThat(runner.getExitCode())
                .as("las dos corridas avanzaron: el proceso sale con 0")
                .isZero();

        // La ventana siguiente: solo queda la que falla, y vuelve a fallar.
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
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    // ------------------------------------------------------------------
    //  Siembra
    // ------------------------------------------------------------------

    /** Lo sembrado en una municipalidad: su corrida y sus dos papeletas. */
    private record Siembra(long municipalidad, long corrida, long sinResolucion, long falla) {}

    private static Siembra sembrar(long municipalidad, String sufijo) {
        long obligado = crearContribuyente(municipalidad, "OB-" + sufijo);
        crearCodigo(municipalidad, codigoDe(sufijo));
        Papeleta sinResolucion = papeleta(municipalidad, "sr" + sufijo, obligado, sufijo);
        Papeleta falla = papeleta(municipalidad, "fa" + sufijo, obligado, sufijo);
        CorridaDeValores corrida =
                enTransaccionDe(
                        municipalidad,
                        () ->
                                iniciar.porSeleccion(
                                        Familia.TRANSITO,
                                        List.of(sinResolucion.numero(), falla.numero()),
                                        CRITERIO,
                                        PORQUE));
        return new Siembra(
                municipalidad,
                corrida.identificador(),
                sinResolucion.identificador(),
                falla.identificador());
    }

    private static Papeleta papeleta(
            long municipalidad, String sufijo, long obligado, String sufijoDelCodigo) {
        return enTransaccionDe(
                municipalidad,
                () ->
                        registrarPapeleta.registrarTransito(
                                ("PT-" + sufijo).toUpperCase(Locale.ROOT),
                                codigoDe(sufijoDelCodigo),
                                INFRACCION,
                                null,
                                "Av. Grau",
                                "P4T-" + (100 + Math.abs(sufijo.hashCode() % 900)),
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
                                PORQUE));
    }

    private static String codigoDe(String sufijo) {
        return ("G-" + sufijo).toUpperCase(Locale.ROOT);
    }

    /**
     * Un disparador que rechaza con una violacion de {@code CHECK} cualquier cambio de estado del
     * candidato de esas papeletas: un fallo de la base dentro de la transaccion del candidato, que
     * es lo que {@code generar} atrapa ({@code DataAccessException}) para seguir con el resto.
     */
    private static void hacerFallarElCandidatoDe(long... papeletas) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (long id : papeletas) {
            ids.add(Long.toString(id));
        }
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute(
                    "CREATE FUNCTION prueba_400_rechaza_el_candidato() RETURNS trigger"
                            + " LANGUAGE plpgsql AS $$ BEGIN"
                            + " IF NEW.papeleta_id IN ("
                            + String.join(", ", ids)
                            + ") THEN RAISE EXCEPTION 'candidato que falla (#400)'"
                            + " USING ERRCODE = 'check_violation'; END IF;"
                            + " RETURN NEW; END $$");
            sentencia.execute(
                    "CREATE TRIGGER prueba_400_rechaza_el_candidato BEFORE UPDATE ON"
                            + " papeleta_masivo_item FOR EACH ROW EXECUTE FUNCTION"
                            + " prueba_400_rechaza_el_candidato()");
        }
    }

    private static EstadoDeItemDeCorrida estadoDe(long municipalidad, long corrida, long papeleta) {
        return enTransaccionDe(
                municipalidad,
                () ->
                        EstadoDeItemDeCorrida.valueOf(
                                jdbc.sql(
                                                "SELECT estado FROM papeleta_masivo_item"
                                                        + " WHERE corrida_id = :corrida"
                                                        + "   AND papeleta_id = :papeleta")
                                        .param("corrida", corrida)
                                        .param("papeleta", papeleta)
                                        .query(String.class)
                                        .single()));
    }

    private static long corridasDe(long municipalidad) {
        return enTransaccionDe(
                municipalidad,
                () -> jdbc.sql("SELECT count(*) FROM papeleta_masivo").query(Long.class).single());
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
        return insertar(
                municipalidad,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES ("
                        + municipalidad
                        + ", 'C-"
                        + codigo
                        + "', 'DNI', '4"
                        + String.format(
                                "%07d", Math.abs((codigo + municipalidad).hashCode() % 10_000_000))
                        + "', 'NATURAL', 'PEÑA GARCÍA, JOSÉ', 'siembra') RETURNING id");
    }

    private static void crearCodigo(long municipalidad, String codigo) {
        insertar(
                municipalidad,
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                        + municipalidad
                        + ", 'TRANSITO', '"
                        + codigo
                        + "', 'Infraccion de la prueba', 8.0000, 'D.S. 016-2009-MTC',"
                        + " DATE '2026-01-01') RETURNING id");
    }

    private static long insertar(long municipalidad, String sql) {
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
}
