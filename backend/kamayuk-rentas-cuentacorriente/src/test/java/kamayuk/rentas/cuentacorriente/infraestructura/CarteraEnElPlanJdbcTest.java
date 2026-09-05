package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.PendienteAgregado;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #639 — el plan de la cartera del panel, medido con {@code EXPLAIN} contra PostgreSQL de verdad.
 *
 * <h2>Por que hace falta una prueba de plan y no basta con que las cifras cuadren</h2>
 *
 * <p>#639 mueve la cartera de {@code saldo_proyectado} —un cache— al libro, y el libro es la tabla
 * que mas crece del sistema. La pantalla que la dibuja es la de <b>aterrizaje</b>: la paga cada
 * usuario en cada entrada. #23 escribio la decision contraria por ese motivo, asi que revertirla
 * exige medir, y medir el <b>plan</b> y no solo el reloj: #561 midio una consulta vecina en 8,5 s
 * por pagina sobre el padron real, y #313 y #536 dejaron escrito que <b>un plan puede decir «Index»
 * y estar leyendo la tabla entera del inquilino</b> —lo que hay que exigir es que la columna que
 * acota salga <b>dentro del {@code Index Cond}</b>—.
 *
 * <h2>Lo que se midio, y con que cifras</h2>
 *
 * <p>Sobre dos padrones del tamano real de Catacaos en la <b>misma instalacion</b> —14 422 predios
 * y 10 603 contribuyentes cada uno, 210 210 asientos en la particion de 2026, 84 824 en la de 2027
 * y 285 024 filas proyectadas—, como {@code kamayuk_app} y con RLS activa, con {@code EXPLAIN
 * (ANALYZE, BUFFERS)} y mediana de tres repeticiones:
 *
 * <table border="1">
 *   <caption>El panel, antes y despues de #639</caption>
 *   <tr><th>consulta</th><th>tiempo</th><th>paginas</th><th>filas leidas</th></tr>
 *   <tr><td>la cartera sobre {@code saldo_proyectado} (antes)</td><td>76,4 ms</td><td>3 944</td>
 *       <td>95 094 propias <b>y 63 310 ajenas descartadas</b></td></tr>
 *   <tr><td>{@code cargadoPorTributo}, que el panel <b>ya</b> paga</td><td>127,9 ms</td>
 *       <td>4 210</td><td>105 106, todas del inquilino</td></tr>
 *   <tr><td>{@code pendientePorTributo} (ahora)</td><td>178,1 ms</td><td>4 166 + 595 temporales</td>
 *       <td>94 503, todas del inquilino</td></tr>
 * </table>
 *
 * <p><b>La consulta nueva toca menos paginas que la que el panel ya venia pagando sobre la misma
 * particion</b>, asi que no estrena una clase de coste. Y hay una mejora que no se esperaba: la
 * consulta vieja recorria {@code saldo_proyectado} <b>entera</b> —no esta particionada y no tiene
 * indice por {@code municipalidad_id}—, leyendo 63 310 filas de la municipalidad vecina y de otros
 * ejercicios para descartarlas; la nueva lee <b>cero</b> filas ajenas, porque el mapa de bits las
 * excluye antes de tocar el heap.
 *
 * <p>Lo unico que sale mas caro es CPU, no E/S: el agregado por obligacion resuelve 25 025 grupos y
 * con el {@code work_mem} por omision (4 MB) desborda a disco —{@code Batches: 5, Disk Usage: 1 768
 * kB}—. Con {@code work_mem = 32MB} la misma consulta baja a <b>157,8 ms</b> en un solo lote, o sea
 * que el desborde cuesta unos 20 ms de los 178 y no hay indice que lo arregle: es agrupar, no leer.
 *
 * <p>El panel entero pasa de 204 ms (76 + 128) a <b>306 ms</b> (128 + 178) en esta maquina. Es lo
 * que cuesta que la cifra de la pantalla de aterrizaje sea la misma que la de ventanilla.
 *
 * <p><b>Y un indice se midio antes de descartarlo</b> (no se propone ninguno): {@code
 * (municipalidad_id, concepto, fecha_valor) INCLUDE (tributo, tipo, monto, contribuyente_id,
 * predio_id, vehiculo_id)} ocupa <b>18 MB sobre una tabla de 51 MB</b> y el planificador <b>no lo
 * usa ni una vez</b> ({@code idx_scan = 0}): la consulta necesita el 90 % de las filas del
 * inquilino —94 503 de 105 106—, y para eso el mapa de bits sobre {@code municipalidad_id} y el
 * recorrido del heap ya son lo optimo. Un indice de mas en la tabla que escribe cada cobranza, a
 * cambio de nada.
 *
 * <h2>Lo que esta prueba fija</h2>
 *
 * <p>No el tiempo —que depende de la maquina— sino la <b>forma</b> del plan, que es lo que
 * sobrevive al cambio de maquina (la metrica de #561): que la particion pode, que {@code
 * municipalidad_id} sea <b>condicion del indice</b> y no un filtro, y que no se lea la tabla
 * entera. Y ata la transcripcion: la consulta escrita aqui tiene que dar <b>la misma cifra, tributo
 * a tributo</b>, que el repositorio de produccion.
 *
 * <p><b>Y esa atadura compara resultados, no textos</b>, asi que solo caza divergencias que se vean
 * en los datos sembrados: se midio, y anadirle a produccion un {@code AND a.fase <> 'CONVENIO'}
 * pasaba en VERDE porque la siembra masiva no produce ni una obligacion en esa fase. Por eso la
 * siembra anade dos casos sueltos —una obligacion en fase {@code CONVENIO} y una con insoluto
 * negativo—, y con ellos la misma rotura dice «repositorio {ARBITRIOS, PREDIAL} vs transcrita
 * {ARBITRIOS, PREDIAL, VEHICULAR=500.00}».
 */
@DisplayName("#639 — El plan de la cartera, con dos padrones en la misma instalacion")
class CarteraEnElPlanJdbcTest {

    /** Contribuyentes por municipalidad. Con menos, el planificador prefiere el secuencial. */
    private static final int CONTRIBUYENTES = 6000;

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate CORTE = LocalDate.of(2026, 9, 1);

    /**
     * La misma consulta que {@link AsientoRepositoryJdbc#pendientePorTributo}, con el parametro ya
     * puesto: {@code EXPLAIN} no se puede pedir desde el repositorio.
     *
     * <p>Es una segunda transcripcion, y por eso {@link #laTranscripcionEsLaDeProduccion} compara
     * su resultado contra el del repositorio real sobre los mismos datos. Sin esa atadura, cambiar
     * la de produccion y no esta dejaria la prueba de plan midiendo una consulta que ya no existe
     * —que es la clase de defecto que este issue investiga—.
     */
    private static final String CONSULTA =
            "SELECT tributo, sum(insoluto) AS pendiente, count(*) AS obligaciones"
                    + " FROM (SELECT a.tributo,"
                    + "              sum(CASE WHEN a.tipo = 'CARGO' THEN a.monto"
                    + "                       ELSE -a.monto END) AS insoluto"
                    + "         FROM cuenta_corriente_asiento a"
                    + "        WHERE a.ejercicio = 2026"
                    + "          AND a.concepto = 'INSOLUTO'"
                    + "          AND a.fecha_valor <= DATE '2026-09-01'"
                    + "        GROUP BY a.contribuyente_id, a.tributo, a.ejercicio,"
                    + "                 a.predio_id, a.vehiculo_id) obligacion"
                    + " WHERE insoluto > 0"
                    + " GROUP BY tributo"
                    + " ORDER BY tributo";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("230311", "Padron grande A");
        municipalidadB = crearMunicipalidad("230312", "Padron grande B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        asientos = new AsientoRepositoryJdbc(jdbc);

        sembrar(municipalidadA);
        sembrar(municipalidadB);
        analizar();
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
    }

    @Test
    @DisplayName("la municipalidad es CONDICION del indice, no un filtro sobre la tabla entera")
    void elInquilinoEsCondicionDelIndice() {
        String plan = plan();

        // Es literalmente lo que #313 dejo escrito y #536 repitio con el operador espacial:
        // que el plan diga «Index» no basta. Un plan que use un indice por otra columna y
        // deje `municipalidad_id` en el Filter vuelve a leer la particion entera —las dos
        // municipalidades— y seguiria diciendo «Index».
        assertThat(plan)
                .as("el plan medido fue:%n%s", plan)
                .contains("Index Cond: (municipalidad_id = ");
        assertThat(plan)
                .as("y no puede quedarse en un recorrido secuencial de la particion")
                .doesNotContain("Seq Scan on cuenta_corriente_asiento_2026");
    }

    @Test
    @DisplayName("la particion del ejercicio poda: el libro de 2027 no se toca")
    void laParticionPoda() {
        String plan = plan();

        // `a.ejercicio = :ejercicio` no es un filtro mas: es la clave de particion. Si
        // dejara de podar, la cartera de 2026 recorreria tambien el libro de 2027, que en
        // una instalacion con varios ejercicios abiertos es la mitad de la tabla.
        assertThat(plan)
                .as("el plan medido fue:%n%s", plan)
                .doesNotContain("cuenta_corriente_asiento_2027");
        assertThat(plan).contains("cuenta_corriente_asiento_2026");
    }

    @Test
    @DisplayName("no lee las filas de la municipalidad vecina: las descarta el mapa de bits")
    void noLeeLasFilasDeLaVecina() {
        long enLaParticion = contarComoAdmin("SELECT count(*) FROM cuenta_corriente_asiento_2026");
        long delInquilino =
                contarComoAdmin(
                        "SELECT count(*) FROM cuenta_corriente_asiento_2026"
                                + " WHERE municipalidad_id = "
                                + municipalidadA);
        assertThat(enLaParticion)
                .as("las dos municipalidades comparten la particion; si no, no hay nada que medir")
                .isGreaterThan(delInquilino);

        long leidas = filasDelHeap(plan());

        // El aislamiento no se mide por la cifra que sale —que ya lo hace
        // CarteraCuadraConLaConsultaJdbcTest— sino por cuanto se LEE: una consulta que
        // filtrara en memoria daria la misma cartera y recorreria el padron de la vecina.
        assertThat(leidas)
                .as(
                        "lee %d filas; el inquilino tiene %d y la particion %d",
                        leidas, delInquilino, enLaParticion)
                .isLessThanOrEqualTo(delInquilino);
    }

    @Test
    @DisplayName("y todo esto se mide con el rol de la aplicacion, no con el dueno de las tablas")
    void seConectaComoKamayukApp() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        // El centinela de #545, y aqui hace mas falta que en ninguna otra: escrita con
        // `kamayuk_owner` —la rotura que uno teclea por costumbre— la prueba de «no lee las
        // filas de la vecina» pasa en VERDE, porque con FORCE ROW LEVEL SECURITY el dueno
        // tambien queda sujeto a la politica. Quien la omite es el superusuario del
        // cluster, y con el la misma prueba lee 72 000 filas de las 96 000 de la particion.
        String rol =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());

        assertThat(rol).isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("y la consulta transcrita aqui es la del repositorio de produccion")
    void laTranscripcionEsLaDeProduccion() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        List<PendienteAgregado> delRepositorio =
                transaccion.execute(estado -> asientos.pendientePorTributo(EJERCICIO, CORTE));
        java.util.Map<String, Dinero> transcritas = new java.util.LinkedHashMap<>();
        transaccion.executeWithoutResult(
                estado ->
                        jdbc.sql(CONSULTA)
                                .query(
                                        (fila, numero) ->
                                                transcritas.put(
                                                        fila.getString("tributo"),
                                                        new Dinero(
                                                                fila.getBigDecimal("pendiente"))))
                                .list());

        java.util.Map<String, Dinero> delRepo = new java.util.LinkedHashMap<>();
        for (PendienteAgregado linea : delRepositorio) {
            delRepo.put(linea.tributo(), linea.pendiente());
        }

        assertThat(transcritas)
                .as(
                        "repositorio %s vs consulta transcrita en la prueba de plan %s: si"
                                + " divergen, esta prueba esta midiendo el plan de una consulta que"
                                + " ya no existe",
                        delRepo, transcritas)
                .isEqualTo(delRepo);
        assertThat(delRepo.keySet())
                .as(
                        "la siembra tiene que ejercitar la fase y el saldo negativo, o una"
                                + " divergencia en esas dos condiciones no cambiaria ninguna cifra")
                .containsExactly("ARBITRIO", "PREDIAL", "VEHICULAR");
    }

    // ------------------------------------------------------------------

    /** El plan de la consulta real, medido como {@code kamayuk_app} y con el contexto fijado. */
    private static String plan() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        List<String> lineas =
                transaccion.execute(
                        estado ->
                                jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + CONSULTA)
                                        .query(String.class)
                                        .list());
        return String.join(System.lineSeparator(), lineas);
    }

    /** Las filas que el nodo que lee la tabla llego a devolver, segun {@code EXPLAIN ANALYZE}. */
    private static long filasDelHeap(String plan) {
        java.util.regex.Matcher nodo =
                java.util.regex.Pattern.compile(
                                "(?:Bitmap Heap Scan|Seq Scan|Index Scan) on"
                                        + " cuenta_corriente_asiento_2026.*?rows=(\\d+) loops=")
                        .matcher(plan.replace(System.lineSeparator(), " "));
        if (!nodo.find()) {
            throw new AssertionError("No se encontro el nodo que lee la tabla en:\n" + plan);
        }
        // El primer `rows=` de la linea es la estimacion y el segundo el real; la expresion
        // toma el ultimo antes de `loops=`, que es el medido.
        return Long.parseLong(nodo.group(1));
    }

    // ------------------------------------------------------------------
    //  Siembra: dos padrones grandes, por SQL directo
    // ------------------------------------------------------------------

    /**
     * Un padron de {@link #CONTRIBUYENTES} obligaciones con cuatro cuotas de dos tributos, mas el
     * ejercicio siguiente, insertado de una vez.
     *
     * <p>Por SQL directo y no por los casos de uso: lo que se mide es el plan de una consulta sobre
     * un volumen, no el camino de escritura —que ya lo miden las otras clases de este paquete—, y
     * cien mil asientos uno a uno tardarian minutos.
     */
    private static void sembrar(long municipalidadId) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (Statement sentencia = app.createStatement()) {
                sentencia.execute(
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro)"
                                + " SELECT "
                                + municipalidadId
                                + ", 'G-' || lpad(n::text, 7, '0'), 'DNI',"
                                + " ("
                                + municipalidadId
                                + " * 10000000 + n)::text, 'NATURAL', 'PADRON ' || n, 'siembra'"
                                + " FROM generate_series(1, "
                                + CONTRIBUYENTES
                                + ") AS n");
                for (int ejercicio : new int[] {2026, 2027}) {
                    sentencia.execute(cargos(municipalidadId, ejercicio, "PREDIAL", null));
                    sentencia.execute(cargos(municipalidadId, ejercicio, "ARBITRIO", "c.id"));
                }
                // Los dos casos que hacen OBSERVABLE una divergencia entre la consulta de
                // produccion y la transcrita aqui. Sin ellos, anadirle a produccion un
                // `AND a.fase <> 'CONVENIO'` no cambia ninguna cifra y la atadura pasa en
                // verde: se midio, y por eso estan.
                sentencia.execute(unCargo(municipalidadId, "VEHICULAR", "CONVENIO", "500.00"));
                sentencia.execute(unAbono(municipalidadId, "ALCABALA", "700.00"));
                sentencia.execute(unCargo(municipalidadId, "ALCABALA", "ORDINARIA", "300.00"));
                app.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /** Un solo cargo, para los casos que la siembra masiva no produce. */
    private static String unCargo(long municipalidadId, String tributo, String fase, String monto) {
        return asientoSuelto(municipalidadId, tributo, fase, "CARGO", monto);
    }

    /** Un solo abono sin su cargo: la obligacion neta en negativo (pago en exceso). */
    private static String unAbono(long municipalidadId, String tributo, String monto) {
        return asientoSuelto(municipalidadId, tributo, "ORDINARIA", "ABONO", monto);
    }

    private static String asientoSuelto(
            long municipalidadId, String tributo, String fase, String tipo, String monto) {
        return "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                + " contribuyente_id, tributo, concepto, tipo, fase, periodo, predio_id,"
                + " vehiculo_id, monto, fecha_valor, documento_origen, usuario_id)"
                + " SELECT "
                + municipalidadId
                + ", 2026, min(c.id), '"
                + tributo
                + "', 'INSOLUTO', '"
                + tipo
                + "', '"
                + fase
                + "', 1, NULL, NULL, "
                + monto
                + ", DATE '2026-03-31', 'SUELTO', 'siembra'"
                + " FROM contribuyente c WHERE c.municipalidad_id = "
                + municipalidadId;
    }

    private static String cargos(
            long municipalidadId, int ejercicio, String tributo, String predio) {
        return "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                + " contribuyente_id, tributo, concepto, tipo, fase, periodo, predio_id,"
                + " vehiculo_id, monto, fecha_valor, documento_origen, usuario_id)"
                + " SELECT "
                + municipalidadId
                + ", "
                + ejercicio
                + ", c.id, '"
                + tributo
                + "', 'INSOLUTO', 'CARGO', 'ORDINARIA', q, "
                + (predio == null ? "NULL" : predio)
                + ", NULL,"
                + " 60.00 + (c.id % 300), (DATE '"
                + ejercicio
                + "-02-28' + (q - 1) * 91), 'EMISION', 'siembra'"
                + " FROM contribuyente c, generate_series(1, 4) AS q"
                + " WHERE c.municipalidad_id = "
                + municipalidadId;
    }

    /** Sin estadisticas frescas el planificador elige a ciegas y la prueba mediria ruido. */
    private static void analizar() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute("VACUUM ANALYZE cuenta_corriente_asiento");
        }
    }

    private static long contarComoAdmin(String consulta) {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(consulta);
                ResultSet fila = sentencia.executeQuery()) {
            fila.next();
            return fila.getLong(1);
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
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
