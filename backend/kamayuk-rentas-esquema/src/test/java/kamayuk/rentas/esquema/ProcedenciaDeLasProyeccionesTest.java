package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P5E — Toda fila proyectada dice de que evento salio, en que orden y con que contenido (`V9`).
 *
 * <h2>Por que hace falta una prueba y no basta con `V9`</h2>
 *
 * <p>Porque la regla no es «estas cuatro tablas llevan tres columnas» sino «<b>toda</b> proyeccion
 * las lleva», y esa es la mitad que una migracion no puede sostener: la proyeccion numero cinco se
 * anade en otra etapa, con otro `ALTER TABLE`, y nada la obligaria. El sintoma de que falte no es
 * un error sino una fila plausible —y desde P5C estas tablas son la unica referencia que `rentas`
 * tiene de lo que ya no esta en su base—, asi que la pregunta «por que esta ficha dice 180 m2»
 * dejaria de tener respuesta sin que nada se pusiera rojo.
 *
 * <h2>La lista NO se escribe a mano: se deriva del motor</h2>
 *
 * <p>Una proyeccion se reconoce por su privilegio, no por su nombre: es una tabla que la aplicacion
 * <b>lee y no escribe</b>, y que escribe un rol ingestor que no atiende peticiones. Eso esta en el
 * catalogo de PostgreSQL, asi que se pregunta ahi. Con una lista escrita a mano, la tabla que
 * alguien olvidara anadir seria justamente la que diria que todo esta bien —el defecto que #718
 * midio con los desplegables y #711 con la fila de la tabla—.
 *
 * <p><b>Lo que esta prueba NO puede ver, dicho aqui:</b> que el valor de `huella` signifique algo.
 * En produccion la emite el sistema de origen sobre el cuerpo que mando, y `V9` dice que aqui no se
 * recalcula; los fixtures la fabrican. Lo que se sostiene es que la columna exista, sea obligatoria
 * y que el evento que nombra se pueda seguir hasta el buzon.
 */
@DisplayName("P5E — La procedencia por fila de las proyecciones")
class ProcedenciaDeLasProyeccionesTest {

    /** Las tres columnas de `V9`, y lo que decide cada una. */
    private static final List<String> PROCEDENCIA = List.of("evento_id", "secuencia", "huella");

    /**
     * El buzon no es una proyeccion: es el registro de los eventos aplicados.
     *
     * <p>Lleva las tres columnas igual —`V9` le anadio la huella— pero no puede referenciarse a si
     * mismo, asi que se excluye de la comprobacion de la clave foranea y de ninguna otra.
     */
    private static final String BUZON = "catastro_evento_aplicado";

    /**
     * La cola de muertos tampoco es una proyeccion, y no puede referenciar al buzon.
     *
     * <p>`V12` (C-8). Sus filas son los hechos que NO se pudieron aplicar, o sea exactamente los
     * que no estan en {@link #BUZON}: una clave foranea ahi haria imposible escribir la unica fila
     * que esta tabla existe para escribir. Lleva las tres columnas de procedencia igual —de que
     * hecho venia, con que secuencia y con que huella— porque son lo que permite volver a aplicarlo
     * a mano cuando la causa se arregle.
     */
    private static final String COLA_DE_MUERTOS = "catastro_evento_muerto";

    private static final String VIOLA_CLAVE_FORANEA = "23503";
    private static final String NO_ADMITE_NULO = "23502";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "201101", "Municipalidad A");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidad, parametroId, "PC");
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName(
            "lo que el ingestor escribe se deriva del privilegio: las cinco de V4/V5 y la cola de V12")
    void lasProyeccionesSeDerivanDelPrivilegio() throws SQLException {
        // Si el censo se quedara vacio, las dos pruebas de abajo pasarian sin revisar nada. Esta
        // fija cuantas hay hoy, para que anadir o quitar una sea una decision y no un descuido.
        //
        // `catastro_evento_muerto` entra con `V12` (C-8) y NO es una proyeccion: es lo contrario,
        // el registro de los hechos que NO se pudieron proyectar. Aparece aqui porque el criterio
        // es el PRIVILEGIO —`sgtm_app` lee y no escribe, el ingestor escribe— y ese criterio es
        // justo lo que hace que una tabla nueva entre sola en el censo en vez de olvidarse.
        //
        // Lleva las tres columnas de procedencia igual, asi que la prueba de abajo la cubre; lo
        // que NO puede llevar es la clave foranea al buzon, porque sus filas son exactamente los
        // eventos que nunca entraron en el.
        assertThat(proyecciones())
                .as(
                        "toda tabla que `sgtm_app` lee y no escribe, y que escribe un rol ingestor:"
                                + " o es una proyeccion alimentada por otro sistema, o es el"
                                + " registro de lo que de el no se pudo aplicar")
                .containsExactlyInAnyOrder(
                        BUZON,
                        COLA_DE_MUERTOS,
                        "predio_ref",
                        "ficha_ref",
                        "valuacion_predio",
                        "valuacion_corrida");
    }

    @Test
    @DisplayName("cada una lleva las tres columnas de procedencia, y ninguna admite nulo")
    void cadaProyeccionLlevaSuProcedencia() throws SQLException {
        Map<String, Map<String, Boolean>> columnas = columnasDeLasProyecciones();
        List<String> proyecciones = proyecciones();

        SoftAssertions.assertSoftly(
                blandas -> {
                    for (String tabla : proyecciones) {
                        Map<String, Boolean> suyas = columnas.getOrDefault(tabla, Map.of());
                        for (String columna : PROCEDENCIA) {
                            blandas.assertThat(suyas)
                                    .as(
                                            "`%s` es una proyeccion y no dice «%s»: una fila que no"
                                                    + " nombra el hecho que la escribio no se puede"
                                                    + " explicar, y esa explicacion es todo lo que"
                                                    + " queda de lo que ya no esta en esta base",
                                            tabla, columna)
                                    .containsKey(columna);
                            blandas.assertThat(suyas.get(columna))
                                    .as(
                                            "`%s.%s` admite nulo: una procedencia opcional es una"
                                                    + " procedencia que la mitad de las filas no"
                                                    + " tendra, y no se sabra cual mitad",
                                            tabla, columna)
                                    .isNotEqualTo(Boolean.TRUE);
                        }
                    }
                });
    }

    @Test
    @DisplayName("una fila que nombra un evento que nadie aplico no entra")
    void laProcedenciaApuntaAUnEventoDeVerdad() throws SQLException {
        // Es lo que separa una procedencia de una decoracion. Sin la clave foranea de `V9` esta
        // fila entraria: el uuid tiene la forma buena y no apunta a nada, que es peor que no
        // ponerlo, porque parece trazable.
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);

            assertThatThrownBy(
                            () ->
                                    proyectarPredio(
                                            ingestor,
                                            990_001L,
                                            "990000000000990001",
                                            UUID.randomUUID().toString()))
                    .as("el evento no esta en el buzon")
                    .isInstanceOf(SQLException.class)
                    .extracting(fallo -> ((SQLException) fallo).getSQLState())
                    .isEqualTo(VIOLA_CLAVE_FORANEA);
            ingestor.rollback();
        }
    }

    @Test
    @DisplayName("una fila proyectada sin decir de que evento salio no entra")
    void sinProcedenciaNoEntra() throws SQLException {
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);

            assertThatThrownBy(
                            () -> proyectarPredio(ingestor, 990_002L, "990000000000990002", null))
                    .as("`evento_id` es obligatorio: `V9` no le puso DEFAULT a proposito")
                    .isInstanceOf(SQLException.class)
                    .extracting(fallo -> ((SQLException) fallo).getSQLState())
                    .isEqualTo(NO_ADMITE_NULO);
            ingestor.rollback();
        }
    }

    // ------------------------------------------------------------------

    private static void proyectarPredio(
            Connection ingestor, long predioId, String codigo, String eventoId)
            throws SQLException {
        try (PreparedStatement sentencia =
                ingestor.prepareStatement(
                        "INSERT INTO predio_ref (municipalidad_id, predio_id,"
                                + " codigo_ref_catastral, direccion, estado, secuencia,"
                                + " proyectado_en, evento_id, huella)"
                                + " VALUES (?, ?, ?, 'AV. DE MENTIRA 1', 'ACTIVO', 1, now(),"
                                + " CAST(? AS uuid), repeat('a', 64))")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, predioId);
            sentencia.setString(3, codigo);
            sentencia.setString(4, eventoId);
            sentencia.executeUpdate();
        }
    }

    /**
     * Las tablas que la aplicacion lee y no escribe, y que escribe un rol ingestor.
     *
     * <p>Se pregunta al catalogo y no a una lista: es lo unico que hace que una proyeccion nueva
     * entre sola en el censo.
     */
    private static List<String> proyecciones() throws SQLException {
        List<String> tablas = new ArrayList<>();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement consulta =
                        app.prepareStatement(
                                """
                                SELECT c.relname
                                  FROM pg_catalog.pg_class c
                                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'public'
                                   AND c.relkind = 'r'
                                   AND     has_table_privilege('sgtm_app', c.oid, 'SELECT')
                                   AND NOT has_table_privilege('sgtm_app', c.oid, 'INSERT')
                                   AND EXISTS (
                                         SELECT 1
                                           FROM pg_catalog.pg_roles r
                                          WHERE r.rolname LIKE 'rol_ingestor_%'
                                            AND has_table_privilege(r.rolname, c.oid, 'INSERT'))
                                 ORDER BY c.relname
                                """);
                ResultSet filas = consulta.executeQuery()) {
            while (filas.next()) {
                tablas.add(filas.getString(1));
            }
        }
        return tablas;
    }

    /** Por tabla, sus columnas con si admiten nulo. */
    private static Map<String, Map<String, Boolean>> columnasDeLasProyecciones()
            throws SQLException {
        Map<String, Map<String, Boolean>> porTabla = new LinkedHashMap<>();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement consulta =
                        app.prepareStatement(
                                "SELECT c.relname, a.attname, NOT a.attnotnull"
                                        + "  FROM pg_catalog.pg_attribute a"
                                        + "  JOIN pg_catalog.pg_class c ON c.oid = a.attrelid"
                                        + "  JOIN pg_catalog.pg_namespace n"
                                        + "    ON n.oid = c.relnamespace"
                                        + " WHERE n.nspname = 'public' AND c.relkind = 'r'"
                                        + "   AND a.attnum > 0 AND NOT a.attisdropped");
                ResultSet filas = consulta.executeQuery()) {
            while (filas.next()) {
                porTabla.computeIfAbsent(filas.getString(1), t -> new LinkedHashMap<>())
                        .put(filas.getString(2), filas.getBoolean(3));
            }
        }
        return porTabla;
    }
}
