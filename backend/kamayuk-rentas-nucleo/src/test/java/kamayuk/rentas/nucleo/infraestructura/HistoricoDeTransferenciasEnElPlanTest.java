package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las dos lecturas del historico vehicular, en el plan (#473, {@code V26}).
 *
 * <h2>Que fija</h2>
 *
 * <p>Desde #329 el propietario al 1 de enero sale de la historia de {@code transferencia}, y {@code
 * RegistrarDeterminacionVehicular.vehiculosDe} la lee con dos preguntas: la cadena de un vehiculo
 * ({@link TransferenciaRepositoryJdbc#HISTORICO_DE_VEHICULO}) y los vehiculos que un contribuyente
 * transfirio desde una fecha ({@link TransferenciaRepositoryJdbc#VEHICULOS_QUE_TRANSFIRIO_DESDE}).
 * Hasta {@code V26} la tabla no tenia mas indice que su clave, y cada una de las dos <b>leia la
 * transferencia entera del inquilino</b> por la condicion de la politica —el plan dice «Index», por
 * {@code transferencia_pk}, y descarta fila a fila en el {@code Filter}—. Una persona por peticion
 * lo aguanta; una masiva vehicular, que hace esas preguntas una vez por vehiculo del padron, no.
 *
 * <p>Por eso se cuentan <b>bloques y filas descartadas</b>, no la palabra «Index»: es la leccion de
 * {@code CodigoDelPadronPorPrefijoEnElPlanTest}, y aqui vale literal, porque el plan sin {@code
 * V26} tambien dice «Index Scan».
 *
 * <h2>Las consultas son las del repositorio</h2>
 *
 * <p>Se explican las dos constantes de {@link TransferenciaRepositoryJdbc}, con sus parametros con
 * nombre: si el repositorio cambia la pregunta, esta prueba mide la nueva.
 *
 * <h2>Dos municipalidades, y la conexion de {@code kamayuk_app}</h2>
 *
 * <p>Dos, porque con una sola la politica selecciona el 100 % de la tabla y no hay nada que acotar
 * (#536). Y {@code kamayuk_app} porque un superusuario omite RLS y mediria un plan que la
 * aplicacion nunca obtiene: el centinela lo comprueba. Las dos condiciones —la igualdad de {@code
 * bigint} y la desigualdad de {@code date}— son <i>leakproof</i>, asi que entran en el {@code Index
 * Cond} junto a la de la politica (quinto hallazgo de DAT-01 §0).
 */
@DisplayName("#473 — El historico vehicular llega a sus indices y no lee la tabla del inquilino")
class HistoricoDeTransferenciasEnElPlanTest {

    /** Los de {@code V26}. */
    private static final String POR_VEHICULO = "transferencia_vehiculo_fecha_ix";

    private static final String POR_TRANSFERENTE = "transferencia_transferente_fecha_ix";

    /**
     * Suficientes para que el planificador prefiera un indice si lo tiene: con unos pocos cientos
     * elige recorrer la tabla, y hace bien, y la prueba mediria que la tabla es pequeña.
     */
    private static final int CONTRIBUYENTES = 2_000;

    private static final int VEHICULOS = 20_000;

    /** Dos actos por vehiculo: 40 000 transferencias por municipalidad. */
    private static final int TRANSFERENCIAS_POR_VEHICULO = 2;

    private static final int TRANSFERENCIAS = VEHICULOS * TRANSFERENCIAS_POR_VEHICULO;

    private static final LocalDate PRIMERO_DE_ENERO = LocalDate.of(2026, 1, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    private static long unVehiculo;
    private static long unTransferente;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("210401", "Municipalidad con historico vehicular");
        long vecina = crearMunicipalidad("210402", "Municipalidad vecina, tambien con historico");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));

        sembrar(municipalidad);
        sembrar(vecina);
        // Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza.
        comoAdministrador("ANALYZE contribuyente", "ANALYZE vehiculo", "ANALYZE transferencia");

        unVehiculo = primero("SELECT min(vehiculo_id) FROM transferencia");
        unTransferente = primero("SELECT min(transferente_id) FROM transferencia");
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("el centinela: se mide con la conexion de kamayuk_app y no con la del dueño")
    void seConectaComoKamayukApp() {
        String quien =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(quien)
                .as(
                        "un superusuario omite RLS aun con FORCE ROW LEVEL SECURITY: mediria el plan"
                                + " que la aplicacion nunca obtiene")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("V26 los deja puestos: las migraciones traen los dos indices del historico")
    void lasMigracionesDejanLosDosIndices() throws SQLException {
        assertThat(indicesDeTransferencia())
                .as(
                        "si esta linea se pone roja es que V26 se retiro o cambio de nombre: lee"
                                + " antes su cabecera")
                .contains(POR_VEHICULO, POR_TRANSFERENTE);
    }

    @Test
    @DisplayName("la cadena de un vehiculo entra por su indice y lee un puñado de bloques")
    void laCadenaDeUnVehiculoLlegaAlIndice() {
        String plan = explicarLaCadena();

        assertThat(plan).as("el plan: %s", plan).contains(POR_VEHICULO);
        assertThat(condicionesDeIndice(plan))
                .as(
                        "el vehiculo y la politica van JUNTOS en el Index Cond: el recorrido empieza"
                                + " y acaba en la cadena de ese vehiculo. El plan: %s",
                        plan)
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("vehiculo_id"));
        assertThat(filasDescartadas(plan)).as("y no descarta nada. El plan: %s", plan).isZero();
        assertThat(bloques(plan))
                .as(
                        "un puñado de bloques sobre %d transferencias. El plan: %s",
                        TRANSFERENCIAS, plan)
                .isLessThan(30);
    }

    @Test
    @DisplayName("lo que un contribuyente transfirio desde el 1 de enero entra por el suyo")
    void loQueTransfirioLlegaAlIndice() {
        String plan = explicarLoQueTransfirio();

        assertThat(plan).as("el plan: %s", plan).contains(POR_TRANSFERENTE);
        assertThat(condicionesDeIndice(plan))
                .as(
                        "el transferente, la fecha y la politica, los tres en el Index Cond. El"
                                + " plan: %s",
                        plan)
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("transferente_id")
                                        .contains("fecha_transferencia"));
        assertThat(filasDescartadas(plan)).as("y no descarta nada. El plan: %s", plan).isZero();
        assertThat(bloques(plan))
                .as(
                        "un puñado de bloques sobre %d transferencias. El plan: %s",
                        TRANSFERENCIAS, plan)
                .isLessThan(80);
    }

    @Test
    @DisplayName("sin los indices de V26 las dos leen la transferencia entera del inquilino")
    void sinLosIndicesDeV26LasDosLeenLaTablaEntera() throws SQLException {
        int bloquesDeLaCadena = bloques(explicarLaCadena());
        int bloquesDeLoTransferido = bloques(explicarLoQueTransfirio());
        // Se vuelve a crear exactamente lo que habia, con la definicion que dejo la migracion, y
        // nada mas: recrear a mano un indice que V26 no trajo le daria el verde a
        // lasMigracionesDejanLosDosIndices si corriera despues de esta.
        List<String> antes = indicesDeTransferencia();
        List<String> definiciones = definicionesDe(POR_VEHICULO, POR_TRANSFERENTE);

        try {
            comoAdministrador(
                    "DROP INDEX IF EXISTS " + POR_VEHICULO,
                    "DROP INDEX IF EXISTS " + POR_TRANSFERENTE,
                    "ANALYZE transferencia");
            String cadena = explicarLaCadena();
            String transferido = explicarLoQueTransfirio();

            assertThat(condicionesDeIndice(cadena))
                    .as(
                            "sin V26 lo unico alcanzable es la clave, por la condicion de la"
                                    + " politica: el vehiculo baja al Filter. El plan: %s",
                            cadena)
                    .noneMatch(condicion -> condicion.contains("vehiculo_id"));
            assertThat(filasDescartadas(cadena))
                    .as(
                            "y se descarta casi toda la transferencia del inquilino, fila a fila."
                                    + " Es el defecto de #473. El plan: %s",
                            cadena)
                    .isGreaterThan(TRANSFERENCIAS / 2);
            assertThat(bloques(cadena))
                    .as("un orden de magnitud mas bloques que con el indice. El plan: %s", cadena)
                    .isGreaterThan(bloquesDeLaCadena * 10);

            assertThat(condicionesDeIndice(transferido))
                    .as("lo mismo con el transferente. El plan: %s", transferido)
                    .noneMatch(condicion -> condicion.contains("transferente_id"));
            assertThat(filasDescartadas(transferido))
                    .as("fila a fila. El plan: %s", transferido)
                    .isGreaterThan(TRANSFERENCIAS / 2);
            assertThat(bloques(transferido))
                    .as("y otro orden de magnitud. El plan: %s", transferido)
                    .isGreaterThan(bloquesDeLoTransferido * 10);
        } finally {
            List<String> restaurar = new ArrayList<>(definiciones);
            restaurar.add("ANALYZE transferencia");
            comoAdministrador(restaurar.toArray(String[]::new));
        }
        assertThat(indicesDeTransferencia()).as("y el esquema queda como estaba").isEqualTo(antes);
    }

    @Test
    @DisplayName("y las dos contestan lo suyo: ni una fila de la vecina")
    void lasDosContestanLoSuyo() {
        List<Long> transferidos =
                transaccion.execute(
                        estado ->
                                jdbc.sql(TransferenciaRepositoryJdbc.VEHICULOS_QUE_TRANSFIRIO_DESDE)
                                        .param("transferente", unTransferente)
                                        .param("fecha", PRIMERO_DE_ENERO.minusYears(10))
                                        .query(Long.class)
                                        .list());
        Long vehiculosDelInquilino =
                transaccion.execute(
                        estado ->
                                jdbc.sql("SELECT count(*) FROM vehiculo")
                                        .query(Long.class)
                                        .single());

        assertThat(transferidos)
                .as(
                        "cada contribuyente sembrado transfiere %d actos: los de la vecina tienen"
                                + " otros ids, y solo la politica los separa",
                        VEHICULOS * TRANSFERENCIAS_POR_VEHICULO / CONTRIBUYENTES)
                .hasSize(VEHICULOS * TRANSFERENCIAS_POR_VEHICULO / CONTRIBUYENTES);
        assertThat(vehiculosDelInquilino).isEqualTo((long) VEHICULOS);
    }

    // ------------------------------------------------------------------

    private String explicarLaCadena() {
        return explicar(
                TransferenciaRepositoryJdbc.HISTORICO_DE_VEHICULO, Map.of("vehiculo", unVehiculo));
    }

    private String explicarLoQueTransfirio() {
        return explicar(
                TransferenciaRepositoryJdbc.VEHICULOS_QUE_TRANSFIRIO_DESDE,
                Map.of("transferente", unTransferente, "fecha", PRIMERO_DE_ENERO));
    }

    /** Como la aplicacion: {@code kamayuk_app}, con RLS activa y el contexto de tenant fijado. */
    private static String explicar(String consulta, Map<String, Object> parametros) {
        String plan =
                transaccion.execute(
                        estado -> {
                            JdbcClient.StatementSpec sentencia =
                                    jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + consulta);
                            for (Map.Entry<String, Object> parametro : parametros.entrySet()) {
                                sentencia =
                                        sentencia.param(parametro.getKey(), parametro.getValue());
                            }
                            return String.join("\n", sentencia.query(String.class).list());
                        });
        return plan == null ? "" : plan;
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    /** Cuantas filas descarta el plan a mano, ya leidas del monton. */
    private static int filasDescartadas(String plan) {
        return sumaDe(plan, Pattern.compile("Rows Removed by Filter: (\\d+)"));
    }

    /** Cuantas paginas toca el plan, sin las de la planificacion, que cuentan el catalogo. */
    private static int bloques(String plan) {
        String sinPlanificacion = plan.split("Planning:", 2)[0];
        return sumaDe(sinPlanificacion, Pattern.compile("shared hit=(\\d+)(?: read=(\\d+))?"));
    }

    private static int sumaDe(String texto, Pattern patron) {
        Matcher busqueda = patron.matcher(texto);
        int suma = 0;
        while (busqueda.find()) {
            for (int grupo = 1; grupo <= busqueda.groupCount(); grupo++) {
                String valor = busqueda.group(grupo);
                if (valor != null) {
                    suma += Integer.parseInt(valor);
                }
            }
        }
        return suma;
    }

    private static void comoAdministrador(String... sentencias) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            for (String sql : sentencias) {
                sentencia.execute(sql);
            }
        }
    }

    private static long primero(String consulta) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (Statement sentencia = app.createStatement();
                    ResultSet fila = sentencia.executeQuery(consulta)) {
                fila.next();
                long valor = fila.getLong(1);
                app.commit();
                return valor;
            }
        }
    }

    /** El {@code CREATE INDEX} de cada uno de esos indices que exista hoy, tal como esta. */
    private static List<String> definicionesDe(String... indices) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE tablename = 'transferencia'"
                                        + " AND indexname = ANY (?) ORDER BY indexname")) {
            sentencia.setArray(1, admin.createArrayOf("text", indices));
            try (ResultSet filas = sentencia.executeQuery()) {
                List<String> definiciones = new ArrayList<>();
                while (filas.next()) {
                    definiciones.add(filas.getString(1));
                }
                return List.copyOf(definiciones);
            }
        }
    }

    private static List<String> indicesDeTransferencia() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet filas =
                        sentencia.executeQuery(
                                "SELECT indexname FROM pg_indexes"
                                        + " WHERE tablename = 'transferencia' ORDER BY 1")) {
            List<String> indices = new ArrayList<>();
            while (filas.next()) {
                indices.add(filas.getString(1));
            }
            return List.copyOf(indices);
        }
    }

    /**
     * Dos mil contribuyentes, veinte mil vehiculos y dos transferencias por vehiculo, con fechas
     * repartidas entre 2020 y 2026 y transferentes repartidos entre todos: cada contribuyente
     * transfiere veinte actos, y cada vehiculo tiene dos. Es la forma de un padron de verdad —mucha
     * tabla, cadenas cortas—, que es justo donde un recorrido por la politica cuesta mas.
     */
    private static void sembrar(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement contribuyentes =
                            app.prepareStatement(
                                    """
                                    INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,
                                                               tipo_documento, numero_documento,
                                                               tipo_persona, nombre_razon_social,
                                                               usuario_registro)
                                    SELECT ?, 'H-' || lpad(g::text, 6, '0'), 'DNI',
                                           lpad(g::text, 8, '0'), 'NATURAL',
                                           'HISTORICO ' || g, 'siembra'
                                      FROM generate_series(0, ? - 1) g
                                    """);
                    PreparedStatement vehiculos =
                            app.prepareStatement(
                                    """
                                    INSERT INTO vehiculo (municipalidad_id, contribuyente_id, placa,
                                                          marca, modelo, categoria,
                                                          anio_fabricacion, anio_inscripcion)
                                    SELECT ?, c.id, 'H' || lpad(g::text, 6, '0'), 'TOYOTA', 'YARIS',
                                           'M1', 2022, 2022
                                      FROM generate_series(0, ? - 1) g
                                      JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
                                              FROM contribuyente) c ON c.n = g % ?
                                    """);
                    PreparedStatement transferencias =
                            app.prepareStatement(
                                    """
                                    INSERT INTO transferencia (municipalidad_id, objeto, vehiculo_id,
                                                               transferente_id, adquiriente_id,
                                                               tipo_transferencia,
                                                               fecha_transferencia,
                                                               valor_transferencia,
                                                               porcentaje_transferido,
                                                               afecta_alcabala, documento_origen,
                                                               observacion, usuario_registro)
                                    SELECT ?, 'VEHICULO', v.id, t.id, a.id, 'COMPRA_VENTA',
                                           DATE '2020-01-01' + ((v.n * 13 + k * 101) % 2400)::int,
                                           10000, 100, false, 'CT-' || v.n || '-' || k,
                                           'siembra del historico', 'siembra'
                                      FROM (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
                                              FROM vehiculo) v
                                     CROSS JOIN generate_series(1, ?) k
                                      JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
                                              FROM contribuyente) t
                                        ON t.n = (v.n * ? + k) % ?
                                      JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
                                              FROM contribuyente) a
                                        ON a.n = (v.n * ? + k + 1) % ?
                                    """)) {
                contribuyentes.setLong(1, municipalidadId);
                contribuyentes.setInt(2, CONTRIBUYENTES);
                contribuyentes.executeUpdate();

                vehiculos.setLong(1, municipalidadId);
                vehiculos.setInt(2, VEHICULOS);
                vehiculos.setInt(3, CONTRIBUYENTES);
                vehiculos.executeUpdate();

                transferencias.setLong(1, municipalidadId);
                transferencias.setInt(2, TRANSFERENCIAS_POR_VEHICULO);
                transferencias.setInt(3, TRANSFERENCIAS_POR_VEHICULO);
                transferencias.setInt(4, CONTRIBUYENTES);
                transferencias.setInt(5, TRANSFERENCIAS_POR_VEHICULO);
                transferencias.setInt(6, CONTRIBUYENTES);
                transferencias.executeUpdate();
            }
            app.commit();
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
