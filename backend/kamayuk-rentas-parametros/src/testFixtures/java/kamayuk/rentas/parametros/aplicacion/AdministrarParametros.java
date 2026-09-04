package kamayuk.rentas.parametros.aplicacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>FIXTURE DE PRUEBA</b>: escribe en la cache local el conjunto que las tablas {@code _de_prueba}
 * describen.
 *
 * <h2>Que es, y que dejo de ser</h2>
 *
 * <p>Antes de P5B era el caso de uso que abria una version, le agregaba parametros publicados y la
 * sellaba. Desde ADR-0025 esos tres actos ocurren en {@code normativa} —con su rol de carga, su
 * doble firma y su base—, y aqui no hay forma de hacerlos ni debe haberla.
 *
 * <p>Lo que hace ahora es dejar la base <b>en el estado en que la deja una descarga verificada</b>:
 * copia de {@code parametro_tributario_de_prueba} a {@code normativa_parametro}, y los cuadros a
 * los suyos. Es el equivalente exacto de lo que estas pruebas hacian antes al sembrar la tabla real
 * — escribir la premisa, no simular el mecanismo—, y lo que se ejercita despues es la <b>lectura de
 * produccion</b> ({@link LectorDeParametrosCacheados}) sin ningun doble.
 *
 * <h2>La auditoria que recibe y no escribe</h2>
 *
 * <p>Se conserva en la firma para no reescribir veinte clases de prueba. No se escribe ninguna
 * fila, y es deliberado: sellar un conjunto se audita <b>donde ocurre</b>, que desde P5B es {@code
 * normativa}; dejar la fila aqui afirmaria que esta base registro un acto que no paso en ella. Se
 * comprobo antes de decidirlo que ninguna de las pruebas afectadas mira esa fila.
 */
public class AdministrarParametros {

    private static final AtomicLong SIGUIENTE = new AtomicLong(9_000_000L);

    private final JdbcClient jdbc;
    private final ParametrosRepositoryJdbc repositorio;

    public AdministrarParametros(ParametrosRepositoryJdbc repositorio, Auditoria auditoria) {
        this(repositorio, auditoria, Clock.systemUTC());
    }

    public AdministrarParametros(
            ParametrosRepositoryJdbc repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.jdbc = repositorio.jdbc();
    }

    /**
     * Abre una version en la tabla de escenario y devuelve su identificador.
     *
     * <p>La municipalidad sale del contexto y no de un argumento (regla 2), y NO se deja nula: hay
     * pruebas que despues buscan «el conjunto de esta municipalidad» con SQL propio, y una fila sin
     * municipalidad no la encuentra ninguna — el sintoma es un {@code ResultSet} vacio en la
     * siembra, que no se parece a su causa.
     */
    @Transactional
    public ConjuntoDeParametros abrirVersion(Ejercicio ejercicio, Observacion observacion) {
        long id =
                jdbc.sql(
                                """
                                INSERT INTO conjunto_parametros_de_prueba
                                    (municipalidad_id, ejercicio, version, estado)
                                VALUES (
                                    NULLIF(current_setting('app.municipalidad_id', true), '')::bigint,
                                    :ejercicio, :version, 'ABIERTO')
                                RETURNING id
                                """)
                        .param("ejercicio", ejercicio.valor())
                        .param("version", (int) (SIGUIENTE.incrementAndGet() % 1000) + 1)
                        .query(Long.class)
                        .single();
        return new ConjuntoDeParametros(id, ejercicio, versionDe(id));
    }

    /** Agrega al conjunto un parametro ya «publicado» en la tabla de escenario. */
    @Transactional
    public void agregarParametro(long conjuntoId, long parametroId, Observacion observacion) {
        jdbc.sql(
                        """
                        INSERT INTO conjunto_parametro_detalle_de_prueba
                            (municipalidad_id, conjunto_id, parametro_id)
                        VALUES (
                            NULLIF(current_setting('app.municipalidad_id', true), '')::bigint,
                            :conjunto, :parametro)
                        """)
                .param("conjunto", conjuntoId)
                .param("parametro", parametroId)
                .update();
    }

    /**
     * Sella: copia el conjunto entero a la cache local.
     *
     * <p>Va en transaccion porque escribe tablas con RLS y necesita el {@code SET LOCAL} del
     * contexto de tenant, como cualquier otra escritura de este sistema.
     */
    /**
     * Sella: marca el conjunto y deja que el disparador del escenario haga de descarga.
     *
     * <p>La copia a la cache local NO se hace aqui, y no es un olvido: la hace {@code
     * escenario_al_sellar}, el disparador que {@code EscenarioDeNormativa} instala sobre esta misma
     * tabla. Tiene que ser el disparador y no este metodo porque <b>la mayoria de las clases de
     * prueba sellan con SQL directo</b> y nunca pasan por aqui; con las dos vias escribiendo, las
     * que si pasan chocaban contra `normativa_conjunto_pk` —«duplicate key»—, que es como se
     * descubrio.
     */
    @Transactional
    public ConjuntoDeParametros sellar(long conjuntoId, Observacion observacion) {
        jdbc.sql(
                        """
                        UPDATE conjunto_parametros_de_prueba
                           SET estado = 'SELLADO', fecha_sellado = now(),
                               usuario_sellado = 'siembra'
                         WHERE id = :conjunto
                        """)
                .param("conjunto", conjuntoId)
                .update();
        return new ConjuntoDeParametros(conjuntoId, ejercicioDe(conjuntoId), versionDe(conjuntoId));
    }

    private Ejercicio ejercicioDe(long conjuntoId) {
        return new Ejercicio(
                jdbc.sql("SELECT ejercicio FROM conjunto_parametros_de_prueba WHERE id = :c")
                        .param("c", conjuntoId)
                        .query(Integer.class)
                        .optional()
                        .orElse(2026));
    }

    private int versionDe(long conjuntoId) {
        return jdbc.sql("SELECT version FROM conjunto_parametros_de_prueba WHERE id = :c")
                .param("c", conjuntoId)
                .query(Integer.class)
                .optional()
                .orElse(1);
    }

    private List<SnapshotDeNormativa.Parametro> parametrosDe(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT p.tipo, p.clave, p.valor_numerico, p.valor_texto,
                               p.vigencia_desde, p.vigencia_hasta, p.documento_fuente
                          FROM parametro_tributario_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d ON d.parametro_id = p.id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY p.id
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.Parametro(
                                        fila.getString("tipo"),
                                        fila.getString("clave"),
                                        texto(fila, "valor_numerico"),
                                        fila.getString("valor_texto"),
                                        texto(fila, "vigencia_desde"),
                                        texto(fila, "vigencia_hasta"),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.ValorUnitario> valoresUnitariosDe(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT v.partida, v.categoria, v.anio_construccion_desde,
                               v.anio_construccion_hasta, v.valor_m2, v.documento_fuente
                          FROM valor_unitario_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY v.partida, v.categoria, v.anio_construccion_desde
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.ValorUnitario(
                                        fila.getString("partida"),
                                        fila.getString("categoria"),
                                        fila.getInt("anio_construccion_desde"),
                                        entero(fila, "anio_construccion_hasta"),
                                        fila.getBigDecimal("valor_m2").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.Depreciacion> depreciacionesDe(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT p.uso, p.material, p.estado_conservacion, p.antiguedad_hasta,
                               p.porcentaje, p.documento_fuente
                          FROM depreciacion_de_prueba p
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = p.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY p.uso, p.material, p.estado_conservacion,
                                  p.antiguedad_hasta NULLS LAST
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.Depreciacion(
                                        fila.getString("uso"),
                                        fila.getString("material"),
                                        fila.getString("estado_conservacion"),
                                        entero(fila, "antiguedad_hasta"),
                                        fila.getBigDecimal("porcentaje").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private List<SnapshotDeNormativa.ValorReferencial> valoresReferencialesDe(long conjuntoId) {
        return jdbc.sql(
                        """
                        SELECT v.ejercicio, v.categoria, v.marca, v.modelo, v.anio_fabricacion,
                               v.valor, v.documento_fuente
                          FROM valor_referencial_de_prueba v
                          JOIN conjunto_parametro_detalle_de_prueba d
                            ON d.parametro_id = v.publicacion_id
                         WHERE d.conjunto_id = :conjunto
                         ORDER BY v.categoria, v.marca, v.modelo, v.anio_fabricacion
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new SnapshotDeNormativa.ValorReferencial(
                                        fila.getInt("ejercicio"),
                                        fila.getString("categoria"),
                                        fila.getString("marca"),
                                        fila.getString("modelo"),
                                        fila.getInt("anio_fabricacion"),
                                        fila.getBigDecimal("valor").toPlainString(),
                                        fila.getString("documento_fuente")))
                .list();
    }

    private static String texto(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : valor.toString();
    }

    private static Integer entero(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : ((Number) valor).intValue();
    }

    /** Los conjuntos abiertos de un ejercicio, sin sellar. Lo pide alguna prueba del listado. */
    public List<ConjuntoDeParametros> conjuntos() {
        List<ConjuntoDeParametros> todos = new ArrayList<>();
        jdbc.sql("SELECT id, ejercicio, version FROM conjunto_parametros_de_prueba ORDER BY id")
                .query(
                        (ResultSet fila, int numero) ->
                                new ConjuntoDeParametros(
                                        fila.getLong("id"),
                                        new Ejercicio(fila.getInt("ejercicio")),
                                        fila.getInt("version")))
                .list()
                .forEach(todos::add);
        return todos;
    }
}
