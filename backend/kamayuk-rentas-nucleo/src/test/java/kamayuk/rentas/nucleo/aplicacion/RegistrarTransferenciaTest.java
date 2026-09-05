package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.prueba.TitularidadDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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

/**
 * {@code RegistrarTransferencia} contra PostgreSQL real (#29).
 *
 * <p>Las pruebas de la clase {@link Predio} cubren, una por una, los criterios de aceptacion del
 * issue:
 *
 * <ul>
 *   <li>{@link Predio#unaTransferenciaTotalDejaElPorcentajeEnCien} — el intermedio descuadrado es
 *       legitimo, y al cerrar la transaccion el total no excede 100.
 *   <li>{@link Predio#unaTransferenciaNoTocaLaCuentaCorriente} — la deuda no se traslada sola.
 *   <li>{@link Predio#ningunaTransferenciaBorraLaTitularidadAnterior} — la fila anterior queda,
 *       cerrada.
 *   <li>{@link Predio#elHistoricoReconstruyeLaCadenaCompleta} — con fechas.
 *   <li>{@link Predio#unaTransferenciaParcialDejaUnRemanente} — la venta del 40 %.
 * </ul>
 */
@DisplayName("#29 — Transferencias de predio y de vehiculo")
class RegistrarTransferenciaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static TransferenciaRepositoryJdbc transferenciaRepositorio;
    private static VehiculoRepositoryJdbc vehiculoRepositorio;
    private static RegistrarTransferencia registrar;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("280101", "Municipalidad de las transferencias");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        transferenciaRepositorio = new TransferenciaRepositoryJdbc(jdbc);
        vehiculoRepositorio = new VehiculoRepositoryJdbc(jdbc);

        GestorDeTitularidad gestorDeTitularidad = new TitularidadDelEscenario(jdbc);

        registrar =
                envolver(
                        new RegistrarTransferencia(
                                transferenciaRepositorio,
                                gestorDeTitularidad,
                                vehiculoRepositorio,
                                new AuditoriaJdbc(jdbc, RELOJ)));
    }

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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Predio")
    class Predio {

        @Test
        @DisplayName(
                "AC1: una transferencia total deja el porcentaje vigente en 100, no en 200 —el"
                        + " intermedio descuadrado es legitimo")
        void unaTransferenciaTotalDejaElPorcentajeEnCien() throws SQLException {
            long a = crearContribuyente("TR-0001", "80300001");
            long b = crearContribuyente("TR-0002", "80300002");
            long predio = crearPredio("000000000000000101");
            long titularidadDeA =
                    sembrarTitularidad(predio, a, "100", LocalDate.of(2020, 1, 1), null);

            registrar.transferirPredio(
                    predio,
                    a,
                    b,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 3, 1),
                    Dinero.de("150000.00"),
                    Porcentaje.total(),
                    true,
                    "Escritura publica N.° 001-2026",
                    Observacion.de("Compraventa del predio completo"));

            BigDecimal total =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT COALESCE(sum(porcentaje), 0) FROM"
                                                            + " titularidad_de_prueba WHERE predio_id = :predio"
                                                            + " AND vigencia_hasta IS NULL")
                                            .param("predio", predio)
                                            .query(BigDecimal.class)
                                            .single());

            assertThat(total)
                    .as(
                            "el disparador diferido dejo pasar el intermedio pero el final no excede 100")
                    .isEqualByComparingTo("100");

            Boolean laDeAEstaCerrada =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT vigencia_hasta IS NOT NULL FROM"
                                                            + " titularidad_de_prueba WHERE id = :id")
                                            .param("id", titularidadDeA)
                                            .query(Boolean.class)
                                            .single());
            assertThat(laDeAEstaCerrada).isTrue();
        }

        @Test
        @DisplayName(
                "AC2: registrar una transferencia no genera ni mueve ningun asiento de cuenta"
                        + " corriente —la deuda no se traslada sola")
        void unaTransferenciaNoTocaLaCuentaCorriente() throws SQLException {
            long a = crearContribuyente("TR-0003", "80300003");
            long b = crearContribuyente("TR-0004", "80300004");
            long predio = crearPredio("000000000000000102");
            sembrarTitularidad(predio, a, "100", LocalDate.of(2020, 1, 1), null);

            Long asientosAntes = contarAsientosDe(predio);

            registrar.transferirPredio(
                    predio,
                    a,
                    b,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 3, 1),
                    Dinero.de("150000.00"),
                    Porcentaje.total(),
                    true,
                    "Escritura publica N.° 002-2026",
                    Observacion.de(
                            "La deuda queda del transferente hasta que se decida otra cosa"));

            Long asientosDespues = contarAsientosDe(predio);
            assertThat(asientosDespues)
                    .as("ningun asiento se creo: trasladar la deuda es una decision aparte (#24)")
                    .isEqualTo(asientosAntes);
        }

        @Test
        @DisplayName("AC3: ninguna transferencia borra la titularidad anterior")
        void ningunaTransferenciaBorraLaTitularidadAnterior() throws SQLException {
            long a = crearContribuyente("TR-0005", "80300005");
            long b = crearContribuyente("TR-0006", "80300006");
            long predio = crearPredio("000000000000000103");
            long titularidadDeA =
                    sembrarTitularidad(predio, a, "100", LocalDate.of(2020, 1, 1), null);

            registrar.transferirPredio(
                    predio,
                    a,
                    b,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 3, 1),
                    Dinero.de("150000.00"),
                    Porcentaje.total(),
                    true,
                    "Escritura publica N.° 003-2026",
                    Observacion.de("La fila anterior tiene que seguir en la base"));

            Long filas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM titularidad_de_prueba WHERE id = :id")
                                            .param("id", titularidadDeA)
                                            .query(Long.class)
                                            .single());
            assertThat(filas).as("la fila de A sigue existiendo, solo cerrada").isEqualTo(1L);
        }

        @Test
        @DisplayName("AC4: el historico reconstruye la cadena completa de titulares con sus fechas")
        void elHistoricoReconstruyeLaCadenaCompleta() throws SQLException {
            long a = crearContribuyente("TR-0007", "80300007");
            long b = crearContribuyente("TR-0008", "80300008");
            long c = crearContribuyente("TR-0009", "80300009");
            long predio = crearPredio("000000000000000104");
            sembrarTitularidad(predio, a, "100", LocalDate.of(2020, 1, 1), null);

            registrar.transferirPredio(
                    predio,
                    a,
                    b,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 3, 1),
                    Dinero.de("100000.00"),
                    Porcentaje.total(),
                    true,
                    "Escritura publica N.° 004-2026",
                    Observacion.de("Primera transferencia: de A a B"));
            registrar.transferirPredio(
                    predio,
                    b,
                    c,
                    TipoTransferencia.DONACION,
                    LocalDate.of(2027, 6, 1),
                    Dinero.de("0"),
                    Porcentaje.total(),
                    false,
                    "Escritura publica N.° 004-2027",
                    Observacion.de("Segunda transferencia: de B a C"));

            List<Transferencia> historico =
                    transaccion.execute(
                            estado -> transferenciaRepositorio.historicoDePredio(predio));

            assertThat(historico).hasSize(2);
            assertThat(historico.get(0).transferenteId()).isEqualTo(a);
            assertThat(historico.get(0).adquirienteId()).isEqualTo(b);
            assertThat(historico.get(0).fechaTransferencia()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(historico.get(1).transferenteId()).isEqualTo(b);
            assertThat(historico.get(1).adquirienteId()).isEqualTo(c);
            assertThat(historico.get(1).fechaTransferencia()).isEqualTo(LocalDate.of(2027, 6, 1));
        }

        @Test
        @DisplayName(
                "AC5: transferencia parcial —venta del 40 %— deja un remanente para el transferente")
        void unaTransferenciaParcialDejaUnRemanente() throws SQLException {
            long a = crearContribuyente("TR-0010", "80300010");
            long b = crearContribuyente("TR-0011", "80300011");
            long predio = crearPredio("000000000000000105");
            sembrarTitularidad(predio, a, "100", LocalDate.of(2020, 1, 1), null);

            registrar.transferirPredio(
                    predio,
                    a,
                    b,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 3, 1),
                    Dinero.de("60000.00"),
                    Porcentaje.de("40"),
                    true,
                    "Escritura publica N.° 005-2026",
                    Observacion.de("Venta parcial del 40 por ciento del predio"));

            List<TitularVigente> vigentes =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT contribuyente_id, porcentaje FROM"
                                                            + " titularidad_de_prueba WHERE predio_id = :predio"
                                                            + " AND vigencia_hasta IS NULL ORDER BY"
                                                            + " porcentaje DESC")
                                            .param("predio", predio)
                                            .query(
                                                    (fila, numero) ->
                                                            new TitularVigente(
                                                                    fila.getLong(
                                                                            "contribuyente_id"),
                                                                    fila.getBigDecimal(
                                                                            "porcentaje")))
                                            .list());

            assertThat(vigentes)
                    .as("A conserva el remanente (60) y B tiene lo comprado (40)")
                    .hasSize(2);
            assertThat(vigentes.get(0).contribuyenteId()).isEqualTo(a);
            assertThat(vigentes.get(0).porcentaje()).isEqualByComparingTo("60");
            assertThat(vigentes.get(1).contribuyenteId()).isEqualTo(b);
            assertThat(vigentes.get(1).porcentaje()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("transferir mas de lo que el transferente tiene se rechaza")
        void transferirMasDeLoQueTieneSeRechaza() throws SQLException {
            long a = crearContribuyente("TR-0012", "80300012");
            long b = crearContribuyente("TR-0013", "80300013");
            long predio = crearPredio("000000000000000106");
            sembrarTitularidad(predio, a, "40", LocalDate.of(2020, 1, 1), null);

            assertThatThrownBy(
                            () ->
                                    registrar.transferirPredio(
                                            predio,
                                            a,
                                            b,
                                            TipoTransferencia.COMPRA_VENTA,
                                            LocalDate.of(2026, 3, 1),
                                            Dinero.de("1"),
                                            Porcentaje.de("60"),
                                            false,
                                            "Escritura",
                                            Observacion.de(
                                                    "A solo tiene 40, no puede transferir 60")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("solo tiene");
        }

        @Test
        @DisplayName("un transferente sin titularidad vigente sobre el predio se rechaza")
        void unTransferenteSinTitularidadSeRechaza() throws SQLException {
            long a = crearContribuyente("TR-0014", "80300014");
            long b = crearContribuyente("TR-0015", "80300015");
            long predio = crearPredio("000000000000000107");
            // Nadie tiene titularidad vigente sobre este predio.

            assertThatThrownBy(
                            () ->
                                    registrar.transferirPredio(
                                            predio,
                                            a,
                                            b,
                                            TipoTransferencia.COMPRA_VENTA,
                                            LocalDate.of(2026, 3, 1),
                                            Dinero.de("1"),
                                            Porcentaje.total(),
                                            false,
                                            "Escritura",
                                            Observacion.de("A nunca fue titular de este predio")))
                    .isInstanceOf(RegistrarTransferencia.TransferenteSinTitularidad.class);
        }
    }

    @Nested
    @DisplayName("Vehiculo")
    class VehiculoAnidado {

        @Test
        @DisplayName("transferir un vehiculo cambia su titular, sin crear una fila nueva")
        void transferirUnVehiculoCambiaSuTitular() throws SQLException {
            long a = crearContribuyente("TR-0020", "80300020");
            long b = crearContribuyente("TR-0021", "80300021");
            Vehiculo vehiculo =
                    transaccion.execute(
                            estado ->
                                    vehiculoRepositorio.save(
                                            Vehiculo.nuevo(
                                                    Placa.de("V2A-222"),
                                                    a,
                                                    "TOYOTA",
                                                    "YARIS",
                                                    "M1",
                                                    new Ejercicio(2020),
                                                    new Ejercicio(2021))));
            long vehiculoId = java.util.Objects.requireNonNull(vehiculo.id());

            Transferencia guardada =
                    registrar.transferirVehiculo(
                            vehiculoId,
                            b,
                            TipoTransferencia.COMPRA_VENTA,
                            LocalDate.of(2026, 3, 1),
                            Dinero.de("15000.00"),
                            false,
                            "Tarjeta de propiedad",
                            Observacion.de("Se vende el vehiculo a otro contribuyente"));

            assertThat(guardada.transferenteId())
                    .as("el transferente se leyo del vehiculo, no de la peticion")
                    .isEqualTo(a);
            assertThat(guardada.adquirienteId()).isEqualTo(b);
            assertThat(guardada.porcentajeTransferido()).isEqualTo(Porcentaje.total());

            Long titularAhora =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT contribuyente_id FROM vehiculo WHERE id"
                                                            + " = :id")
                                            .param("id", vehiculoId)
                                            .query(Long.class)
                                            .single());
            assertThat(titularAhora).isEqualTo(b);

            Long filasDeVehiculo =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql("SELECT count(*) FROM vehiculo WHERE id = :id")
                                            .param("id", vehiculoId)
                                            .query(Long.class)
                                            .single());
            assertThat(filasDeVehiculo)
                    .as("un vehiculo transferido sigue siendo la misma fila, no una nueva")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("transferir un vehiculo inexistente falla")
        void transferirUnVehiculoInexistenteFalla() {
            long b = 999_999L;
            assertThatThrownBy(
                            () ->
                                    registrar.transferirVehiculo(
                                            999_998L,
                                            b,
                                            TipoTransferencia.COMPRA_VENTA,
                                            LocalDate.of(2026, 3, 1),
                                            Dinero.de("1"),
                                            false,
                                            "Tarjeta",
                                            Observacion.de("No deberia llegar a escribirse")))
                    .isInstanceOf(RegistrarTransferencia.VehiculoInexistente.class);
        }
    }

    // ------------------------------------------------------------------

    private record TitularVigente(long contribuyenteId, BigDecimal porcentaje) {}

    private Long contarAsientosDe(long predioId) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento WHERE"
                                                + " predio_id = :predio")
                                .param("predio", predioId)
                                .query(Long.class)
                                .single());
    }

    /** Siembra una titularidad directamente en la base, sin pasar por el caso de uso. */
    private static long sembrarTitularidad(
            long predioId,
            long contribuyenteId,
            String porcentaje,
            LocalDate desde,
            @Nullable LocalDate hasta)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            String condicion = "100".equals(porcentaje) ? "PROPIETARIO_UNICO" : "COPROPIETARIO";
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " vigencia_hasta, documento_origen)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 'Siembra de la prueba')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setString(4, condicion);
                sentencia.setBigDecimal(5, new BigDecimal(porcentaje));
                sentencia.setObject(6, desde);
                sentencia.setObject(7, hasta);
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

    private static long crearContribuyente(String codigo, String dni) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearPredio(String codigoRefCatastral) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 123')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigoRefCatastral);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
