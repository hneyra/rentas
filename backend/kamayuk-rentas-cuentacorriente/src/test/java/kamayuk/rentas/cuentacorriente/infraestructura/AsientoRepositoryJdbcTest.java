package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeConsulta;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
 * El libro de asientos contra PostgreSQL de verdad, conectado como {@code sgtm_app} (RF-040,
 * ADR-0006).
 *
 * <p>Lo que <b>no</b> repite esta clase: que {@code sgtm_app} tenga solo {@code SELECT} e {@code
 * INSERT} sobre {@code cuenta_corriente_asiento}, que no admita {@code UPDATE}, y que el acceso
 * directo a una particion falle. Eso ya lo demuestra {@code AislamientoMultiTenantTest} en {@code
 * kamayuk-rentas-esquema}, contra esta misma tabla. Lo que defiende esta clase es el
 * <b>repositorio</b>: que la reversion deja dos filas sin tocar la primera, y que el cruce por
 * codigo de contribuyente no se sale del tenant.
 */
@DisplayName("RF-040 — El libro de asientos")
class AsientoRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("230101", "Municipalidad del libro A");
        municipalidadB = crearMunicipalidad("230102", "Municipalidad del libro B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new AsientoRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura")
    class Escritura {

        @Test
        @DisplayName("un cargo se guarda con su id y su usuario, y se relee")
        void unCargoSeGuarda() throws SQLException {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0001", "50100001");

            Asiento guardado =
                    transaccion.execute(
                            estado -> repositorio.registrar(cargoDe(titular, Dinero.de(100))));

            assertThat(guardado).isNotNull();
            assertThat(guardado.id()).isNotNull();
            assertThat(guardado.usuarioId()).isEqualTo("cajera.ventanilla");
            assertThat(guardado.motivo())
                    .as(
                            "el repositorio guarda el motivo tal como llega; quien lo llena es"
                                    + " RegistrarAsiento, no esta capa")
                    .isEqualTo("insoluto de la prueba");

            Optional<Asiento> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));
            assertThat(releido).isPresent();
            assertThat(releido.get().monto()).isEqualTo(Dinero.de(100));
        }

        @Test
        @DisplayName("una reversion deja dos asientos y ninguno modificado")
        void unaReversionDejaDosAsientos() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0002", "50100002");

            Asiento cargo =
                    transaccion.execute(
                            estado -> repositorio.registrar(cargoDe(titular, Dinero.de(200))));

            Asiento reversion =
                    Asiento.reversionDe(
                            cargo, LocalDate.of(2026, 4, 15), "NC-2026-0001", "predio equivocado");
            Asiento reversionGuardada =
                    transaccion.execute(estado -> repositorio.registrar(reversion));

            Optional<Asiento> cargoReleido =
                    transaccion.execute(estado -> repositorio.findById(cargo.id()));

            assertThat(reversionGuardada.id())
                    .as("son dos filas distintas, no la misma editada")
                    .isNotEqualTo(cargo.id());
            assertThat(reversionGuardada.tipo()).isEqualTo(TipoAsiento.ABONO);
            assertThat(reversionGuardada.asientoReversadoId()).isEqualTo(cargo.id());
            assertThat(cargoReleido)
                    .as("el original sigue como se guardo: la reversion no lo toca")
                    .isPresent();
            assertThat(cargoReleido.get().tipo()).isEqualTo(TipoAsiento.CARGO);
            assertThat(cargoReleido.get().monto()).isEqualTo(Dinero.de(200));
        }

        @Test
        @DisplayName("un asiento de ANULACION sin motivo falla en la base (asiento_motivo_ck)")
        void anulacionSinMotivoFallaEnLaBase() throws SQLException {
            long titular = crearContribuyente(municipalidadA, "L-0003", "50100003");

            // Directo por JDBC, saltandose el dominio: la barrera de verdad es el CHECK
            // de V2, no la validacion de Asiento que ya lo impide antes.
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement sentencia =
                                            app.prepareStatement(
                                                    "INSERT INTO cuenta_corriente_asiento"
                                                            + " (municipalidad_id, ejercicio,"
                                                            + " contribuyente_id, tributo, concepto,"
                                                            + " tipo, monto, fecha_valor,"
                                                            + " documento_origen, usuario_id)"
                                                            + " VALUES (?, 2026, ?, 'PREDIAL',"
                                                            + " 'ANULACION', 'ABONO', 100,"
                                                            + " '2026-04-15', 'NC-0001', 'prueba')")) {
                                        sentencia.setLong(1, municipalidadA);
                                        sentencia.setLong(2, titular);
                                        sentencia.executeUpdate();
                                    }
                                })
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Nested
    @DisplayName("Consulta por codigo de contribuyente")
    class Consulta {

        @Test
        @DisplayName("el estado de cuenta no cruza la municipalidad")
        void elEstadoDeCuentaNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titularA = crearContribuyente(municipalidadA, "L-0010", "50100010");
            transaccion.execute(estado -> repositorio.registrar(cargoDe(titularA, Dinero.de(300))));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            // Mismo codigo, otra municipalidad: son dos padrones, y el de B no tiene asientos.
            crearContribuyente(municipalidadB, "L-0010", "50100011");

            Pagina<Asiento> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeConsulta.delContribuyente("L-0010"),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(desdeB.totalElementos())
                    .as("el codigo coincide, pero el asiento es de otra municipalidad")
                    .isZero();
        }

        @Test
        @DisplayName("filtra por ejercicio y por tributo")
        void filtraPorEjercicioYTributo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0020", "50100020");

            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    cargoDe(
                                            titular,
                                            Dinero.de(100),
                                            new Ejercicio(2026),
                                            "PREDIAL",
                                            LocalDate.of(2026, 3, 1))));
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    cargoDe(
                                            titular,
                                            Dinero.de(50),
                                            new Ejercicio(2027),
                                            "ARBITRIO",
                                            LocalDate.of(2027, 3, 1))));

            Pagina<Asiento> de2026 =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeConsulta(
                                                    "L-0020", new Ejercicio(2026), null, null),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(de2026.totalElementos()).isEqualTo(1);
            assertThat(de2026.contenido().get(0).tributo()).isEqualTo("PREDIAL");

            Pagina<Asiento> arbitrios =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeConsulta(
                                                    "L-0020", null, "ARBITRIO", null),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(arbitrios.totalElementos()).isEqualTo(1);
            assertThat(arbitrios.contenido().get(0).ejercicio()).isEqualTo(new Ejercicio(2027));
        }

        @Test
        @DisplayName("un codigo que no existe devuelve vacio, no un error")
        void unCodigoQueNoExisteDevuelveVacio() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Asiento> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeConsulta.delContribuyente("NO-EXISTE"),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(pagina.totalElementos()).isZero();
        }
    }

    @Nested
    @DisplayName("Asientos de una obligacion (#22, RF-041)")
    class ParaDeuda {

        @Test
        @DisplayName("trae los asientos de la obligacion, netos de otro periodo y otro tributo")
        void traeSoloLosDeLaObligacion() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0030", "50100030");

            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 3, 1))));
            // Otra cuota del mismo tributo: no es la misma obligacion.
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            2,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Dinero.de(999),
                                            LocalDate.of(2026, 3, 1))));
            // Otro tributo, misma cuota: tampoco.
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "ARBITRIO",
                                            1,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Dinero.de(999),
                                            LocalDate.of(2026, 3, 1))));

            var asientos =
                    transaccion.execute(
                            estado ->
                                    repositorio.paraDeuda(
                                            new CriterioDeDeuda(
                                                    "L-0030",
                                                    "PREDIAL",
                                                    new Ejercicio(2026),
                                                    1,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    LocalDate.of(2026, 12, 31))));

            assertThat(asientos)
                    .singleElement()
                    .extracting(Asiento::monto)
                    .isEqualTo(Dinero.de(100));
        }

        @Test
        @DisplayName("no trae asientos posteriores a la fecha de corte")
        void noTraeAsientosPosterioresAlCorte() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0031", "50100031");

            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 3, 1))));
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.PAGO,
                                            TipoAsiento.ABONO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 8, 1))));

            var alCorteDeAbril =
                    transaccion.execute(
                            estado ->
                                    repositorio.paraDeuda(
                                            new CriterioDeDeuda(
                                                    "L-0031",
                                                    "PREDIAL",
                                                    new Ejercicio(2026),
                                                    1,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    LocalDate.of(2026, 4, 1))));

            assertThat(alCorteDeAbril)
                    .as("el pago de agosto es posterior al corte de abril: no cuenta todavia")
                    .singleElement()
                    .extracting(Asiento::concepto)
                    .isEqualTo(Concepto.INSOLUTO);
        }
    }

    @Nested
    @DisplayName("Pagos (#25, RF-048)")
    class Pagos {

        @Test
        @DisplayName("solo trae abonos de concepto PAGO, no otros movimientos de deuda")
        void soloTraeAbonosDePago() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0040", "50100040");

            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 3, 1))));
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.PAGO,
                                            TipoAsiento.ABONO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 3, 15))));
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.COMPENSACION,
                                            TipoAsiento.ABONO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 4, 1))));

            Pagina<Asiento> pagos =
                    transaccion.execute(
                            estado ->
                                    repositorio.pagos(
                                            new CriterioDePagos("L-0040", null, null),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(pagos.contenido())
                    .as("ni el cargo insoluto ni la compensacion son un pago")
                    .singleElement()
                    .extracting(Asiento::concepto)
                    .isEqualTo(Concepto.PAGO);
        }

        @Test
        @DisplayName("filtra por rango de fechas")
        void filtraPorRangoDeFechas() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "L-0041", "50100041");

            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            1,
                                            Concepto.PAGO,
                                            TipoAsiento.ABONO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 1, 10))));
            transaccion.execute(
                    estado ->
                            repositorio.registrar(
                                    asiento(
                                            titular,
                                            "PREDIAL",
                                            2,
                                            Concepto.PAGO,
                                            TipoAsiento.ABONO,
                                            Dinero.de(100),
                                            LocalDate.of(2026, 6, 10))));

            Pagina<Asiento> deJunio =
                    transaccion.execute(
                            estado ->
                                    repositorio.pagos(
                                            new CriterioDePagos(
                                                    "L-0041",
                                                    LocalDate.of(2026, 6, 1),
                                                    LocalDate.of(2026, 6, 30)),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(deJunio.contenido())
                    .singleElement()
                    .extracting(Asiento::fechaValor)
                    .isEqualTo(LocalDate.of(2026, 6, 10));
        }

        @Test
        @DisplayName("un codigo que no existe devuelve vacio, no un error")
        void unCodigoQueNoExisteDevuelveVacio() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Asiento> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.pagos(
                                            new CriterioDePagos("NO-EXISTE", null, null),
                                            Paginacion.de(0, 20, "fecha_valor")));

            assertThat(pagina.totalElementos()).isZero();
        }
    }

    // ------------------------------------------------------------------

    private static Asiento asiento(
            long contribuyenteId,
            String tributo,
            int periodo,
            Concepto concepto,
            TipoAsiento tipo,
            Dinero monto,
            LocalDate fechaValor) {
        Asiento nuevo =
                Asiento.nuevo(
                        new Ejercicio(2026),
                        contribuyenteId,
                        tributo,
                        concepto,
                        tipo,
                        Fase.ORDINARIA,
                        periodo,
                        null,
                        null,
                        null,
                        monto,
                        fechaValor,
                        "EM-2026-0001");
        return concepto.exigeMotivo() ? nuevo.conMotivo("motivo de la prueba") : nuevo;
    }

    private static Asiento cargoDe(long contribuyenteId, Dinero monto) {
        return cargoDe(
                contribuyenteId, monto, new Ejercicio(2026), "PREDIAL", LocalDate.of(2026, 3, 1));
    }

    private static Asiento cargoDe(
            long contribuyenteId,
            Dinero monto,
            Ejercicio ejercicio,
            String tributo,
            LocalDate fechaValor) {
        return Asiento.nuevo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        1,
                        null,
                        null,
                        null,
                        monto,
                        fechaValor,
                        "EM-2026-0001")
                .conMotivo("insoluto de la prueba");
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
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
