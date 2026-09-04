package kamayuk.rentas.rentas.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lo que solo PostgreSQL puede decir de la determinacion predial de #395.
 *
 * <ul>
 *   <li><b>V56</b>: la parte exonerada del autovaluo se guarda y vuelve. Sin ella, recalcular el
 *       padron tendria que elegir entre suponer que nadie tiene nada exonerado —lo que <b>sube</b>
 *       la base de todo el que si lo tiene— o despejarla dividiendo, que reintroduce el error de
 *       redondeo que ADR-0018 evita. Las dos producen cifras plausibles.
 *   <li>Los dos {@code CHECK} de V56 rechazan la fila incoherente <b>aunque se escriba por SQL
 *       directo</b>, sin pasar por el dominio.
 *   <li>{@code sgtm_app} no puede modificar ni borrar el detalle: la unica forma de recalcular es
 *       insertar otra determinacion (ADR-0007).
 *   <li>Las dos lecturas nuevas devuelven <b>la ultima</b> de cada contribuyente, no una
 *       cualquiera, y <b>bajo RLS</b>: con el contexto de la municipalidad B, la determinacion de A
 *       no existe.
 * </ul>
 *
 * <p>La conexion es la de {@code sgtm_app}, nunca la de superusuario: un superusuario omite RLS
 * incluso con {@code FORCE ROW LEVEL SECURITY}, y una prueba escrita sobre esa conexion pasa en
 * verde sin verificar nada (DAT-01 §0).
 */
@DisplayName("#395 — La determinacion predial contra PostgreSQL")
class DeterminacionPredialJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static DeterminacionRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270601", "Municipalidad del detalle predial");
        municipalidadB = crearMunicipalidad("270602", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new DeterminacionRepositoryJdbc(jdbc);
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
    @DisplayName("V56 — la parte exonerada se guarda y vuelve con el detalle")
    void laParteExoneradaSobrevive() throws SQLException {
        enA();
        long titular = crearContribuyente(municipalidadA, "DET-3001", "80300301");
        long predio = crearPredio(municipalidadA, "000000000000000301");

        Determinacion guardada =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        cabecera(titular, Dinero.de("70000.00")),
                                        List.of(
                                                DetalleDeterminacionPredio.nuevo(
                                                        predio,
                                                        Dinero.de("100000.00"),
                                                        Dinero.de("30000.00"),
                                                        Porcentaje.total(),
                                                        Dinero.de("70000.00")))));

        List<DetalleDeterminacionPredio> detalle =
                transaccion.execute(estado -> repositorio.detalleDe(guardada.id()));

        assertThat(detalle).hasSize(1);
        assertThat(detalle.get(0).autovaluo()).isEqualTo(Dinero.de("100000.00"));
        assertThat(detalle.get(0).valuoExonerado()).isEqualTo(Dinero.de("30000.00"));
        assertThat(detalle.get(0).valuoAfecto()).isEqualTo(Dinero.de("70000.00"));
    }

    @Test
    @DisplayName("V56 — un exonerado mayor que el autovaluo lo rechaza la base, no solo el dominio")
    void elExoneradoNoPuedeSuperarAlAutovaluo() throws SQLException {
        enA();
        long titular = crearContribuyente(municipalidadA, "DET-3002", "80300302");
        long predio = crearPredio(municipalidadA, "000000000000000302");
        Determinacion guardada =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        cabecera(titular, Dinero.de("1000.00")),
                                        List.of(
                                                DetalleDeterminacionPredio.nuevo(
                                                        predio,
                                                        Dinero.de("1000.00"),
                                                        Porcentaje.total(),
                                                        Dinero.de("1000.00")))));

        assertThatThrownBy(() -> insertarDetallePorSql(guardada.id(), predio, "1000.00", "1500.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("det_predio_detalle_exonerado_cabe_ck");

        assertThatThrownBy(() -> insertarDetallePorSql(guardada.id(), predio, "1000.00", "-1.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("det_predio_detalle_exonerado_ck");
    }

    @Test
    @DisplayName("sgtm_app no puede modificar ni borrar el detalle de una determinacion")
    void elDetalleNoSeEdita() throws SQLException {
        enA();
        long titular = crearContribuyente(municipalidadA, "DET-3003", "80300303");
        long predio = crearPredio(municipalidadA, "000000000000000303");
        transaccion.execute(
                estado ->
                        repositorio.insertar(
                                cabecera(titular, Dinero.de("1000.00")),
                                List.of(
                                        DetalleDeterminacionPredio.nuevo(
                                                predio,
                                                Dinero.de("1000.00"),
                                                Porcentaje.total(),
                                                Dinero.de("1000.00")))));

        assertThatThrownBy(
                        () ->
                                ejecutarComoApp(
                                        municipalidadA,
                                        "UPDATE determinacion_predio_detalle SET autovaluo = 1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for table determinacion_predio_detalle");
        assertThatThrownBy(
                        () ->
                                ejecutarComoApp(
                                        municipalidadA, "DELETE FROM determinacion_predio_detalle"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for table determinacion_predio_detalle");
    }

    @Test
    @DisplayName("«la ultima del ejercicio» es la ultima, no una cualquiera")
    void laUltimaEsLaUltima() throws SQLException {
        enA();
        long titular = crearContribuyente(municipalidadA, "DET-3004", "80300304");
        long predio = crearPredio(municipalidadA, "000000000000000304");

        transaccion.execute(
                estado ->
                        repositorio.insertar(
                                cabecera(titular, Dinero.de("1000.00")),
                                List.of(detalleDe(predio, "1000.00"))));
        Determinacion segunda =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        cabecera(titular, Dinero.de("2000.00")),
                                        List.of(detalleDe(predio, "2000.00"))));

        Optional<Determinacion> ultima =
                transaccion.execute(estado -> repositorio.ultimaPredialDe(EJERCICIO, titular));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().id()).isEqualTo(segunda.id());
        assertThat(ultima.get().baseImponible()).isEqualTo(Dinero.de("2000.00"));

        List<Determinacion> padron =
                transaccion.execute(estado -> repositorio.ultimasPredialesDe(EJERCICIO));

        assertThat(padron)
                .as("una fila por contribuyente, aunque tenga tres determinaciones")
                .filteredOn(fila -> fila.contribuyenteId() == titular)
                .hasSize(1);
        assertThat(padron)
                .filteredOn(fila -> fila.contribuyenteId() == titular)
                .allMatch(fila -> fila.baseImponible().equals(Dinero.de("2000.00")));
    }

    @Test
    @DisplayName("RLS — desde la municipalidad B, la determinacion de A no existe")
    void elAislamientoSeSostiene() throws SQLException {
        enA();
        long titular = crearContribuyente(municipalidadA, "DET-3005", "80300305");
        long predio = crearPredio(municipalidadA, "000000000000000305");
        Determinacion deA =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        cabecera(titular, Dinero.de("5000.00")),
                                        List.of(detalleDe(predio, "5000.00"))));

        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        Optional<Determinacion> cabeceraDesdeB =
                transaccion.execute(estado -> repositorio.findById(deA.id()));
        List<DetalleDeterminacionPredio> detalleDesdeB =
                transaccion.execute(estado -> repositorio.detalleDe(deA.id()));
        List<Determinacion> padronDeB =
                transaccion.execute(estado -> repositorio.ultimasPredialesDe(EJERCICIO));
        Optional<Determinacion> ultimaDesdeB =
                transaccion.execute(estado -> repositorio.ultimaPredialDe(EJERCICIO, titular));

        assertThat(cabeceraDesdeB).isEmpty();
        assertThat(detalleDesdeB).isEmpty();
        assertThat(padronDeB)
                .as("el padron de B no puede contener a un contribuyente de A")
                .noneMatch(fila -> fila.contribuyenteId() == titular);
        assertThat(ultimaDesdeB).isEmpty();
    }

    // ---------------------------------------------------------------- utilidades

    private static void enA() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    private static Determinacion cabecera(long titular, Dinero base) {
        return Determinacion.nuevaPredial(
                EJERCICIO, titular, conjuntoDeA(), base, Dinero.de("8.00"), List.of("RT-011"));
    }

    private static DetalleDeterminacionPredio detalleDe(long predio, String importe) {
        return DetalleDeterminacionPredio.nuevo(
                predio, Dinero.de(importe), Porcentaje.total(), Dinero.de(importe));
    }

    /**
     * El conjunto se crea una sola vez por municipalidad: {@code determinacion_conjunto_fk} exige
     * que exista, y esta prueba no verifica el sellado —eso ya lo hace {@code
     * RegistrarDeterminacionPredialTest}—, solo necesita una clave valida.
     */
    private static long conjuntoDeA() {
        return CONJUNTO_A.get();
    }

    private static final java.util.function.Supplier<Long> CONJUNTO_A =
            new java.util.function.Supplier<>() {
                private Long id;

                @Override
                public Long get() {
                    if (id == null) {
                        try {
                            id = crearConjunto(municipalidadA);
                        } catch (SQLException fallo) {
                            throw new IllegalStateException(fallo);
                        }
                    }
                    return id;
                }
            };

    private static long crearConjunto(long municipalidad) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version,"
                                    + " estado, fecha_sellado, usuario_sellado)"
                                    + " VALUES (?, 2026, 1, 'SELLADO', now(), 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                return devolverId(app, sentencia);
            }
        }
    }

    private static void insertarDetallePorSql(
            long determinacionId, long predio, String autovaluo, String exonerado)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO determinacion_predio_detalle (municipalidad_id, ejercicio,"
                                    + " determinacion_id, predio_id, autovaluo, valuo_exonerado,"
                                    + " porcentaje_propiedad, base_imponible_predio)"
                                    + " VALUES (?, 2026, ?, ?, ?::numeric, ?::numeric, 100, 1)")) {
                sentencia.setLong(1, municipalidadA);
                sentencia.setLong(2, determinacionId);
                sentencia.setLong(3, predio);
                sentencia.setString(4, autovaluo);
                sentencia.setString(5, exonerado);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static void ejecutarComoApp(long municipalidad, String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
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
            return devolverId(owner, sentencia);
        }
    }

    private static long crearContribuyente(long municipalidad, String codigo, String dni)
            throws SQLException {
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
                return devolverId(app, sentencia);
            }
        }
    }

    private static long crearPredio(long municipalidad, String codigoRefCatastral)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 123')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigoRefCatastral);
                return devolverId(app, sentencia);
            }
        }
    }

    private static long devolverId(Connection conexion, PreparedStatement sentencia)
            throws SQLException {
        try (ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            long id = resultado.getLong(1);
            conexion.commit();
            return id;
        }
    }
}
