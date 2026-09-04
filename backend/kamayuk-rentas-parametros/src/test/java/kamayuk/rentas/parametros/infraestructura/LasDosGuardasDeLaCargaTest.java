package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.PublicacionDeCuadros;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Las <b>dos</b> guardas que separan a la aplicacion de las cuatro tablas normativas, medidas por
 * separado y contra PostgreSQL real (issue #435).
 *
 * <h2>Por que hace falta una prueba propia, teniendo ya las negativas</h2>
 *
 * <p>Cuando #380 midio la guarda salio el matiz que este archivo existe para conservar: devolverle
 * el {@code INSERT} a {@code sgtm_app} deja las pruebas <b>en verde</b>, porque quien lo para no es
 * el privilegio sino que la politica de RLS nombra solo a {@code rol_carga_parametros}. RLS y
 * {@code GRANT} son dos guardas independientes, <b>basta una</b>, y <b>las dos dan {@code
 * 42501}</b>: el sintoma no distingue cual actuo.
 *
 * <p>Una prueba que solo comprueba «el {@code INSERT} falla» pasa igual con una guarda que con dos,
 * asi que no puede detectar que alguien afloje una. Estas miran el <b>catalogo</b> —{@code
 * has_table_privilege} por un lado, {@code pg_policies} por el otro—, que es lo unico que las
 * distingue, y ademas <b>demuestran</b> que el sintoma no lo hace: conceden el privilegio y
 * comprueban que el {@code INSERT} sigue fallando con el mismo codigo de siempre.
 *
 * <h2>Las cuatro tablas</h2>
 *
 * <p>{@code parametro_tributario} desde V6/V7, y las tres de valuacion nacional desde V55 (D-13,
 * ADR-0017). Las cuatro tienen la misma forma: {@code FORCE ROW LEVEL SECURITY}, una politica de
 * lectura abierta a lo nacional, y una politica de escritura {@code FOR ALL TO
 * rol_carga_parametros}.
 *
 * <p>La lista se comprueba contra el catalogo en vez de darse por buena: una tabla normativa nueva
 * que nadie anada aqui deja de estar vigilada sin que nada lo diga.
 */
@DisplayName("Las dos guardas de la carga de valores normativos (#435)")
class LasDosGuardasDeLaCargaTest {

    /**
     * Las cuatro tablas que solo {@code rol_carga_parametros} puede escribir.
     *
     * <p>Escritas a mano a proposito: derivarlas de la misma consulta que despues las comprueba
     * haria que una tabla que perdiera su politica saliera tambien de la lista, y la prueba pasaria
     * en verde vigilando tres.
     */
    private static final List<String> TABLAS =
            List.of(
                    "parametro_tributario",
                    "valor_unitario_edificacion",
                    "depreciacion",
                    "valor_referencial_vehiculo");

    /** El rol que la politica de escritura de V6 y V55 nombra, y el unico. */
    private static final String ROL_DE_CARGA = "rol_carga_parametros";

    /** «insufficient_privilege». Lo dan las DOS guardas, y ese es el problema. */
    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    private static BaseDeDatosDePrueba base;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    private static String consultar(String sql) throws SQLException {
        try (Connection conexion = base.conexionAdmin();
                Statement sentencia = conexion.createStatement();
                ResultSet fila = sentencia.executeQuery(sql)) {
            return fila.next() ? fila.getString(1) : null;
        }
    }

    private static void comoSuperusuario(String sql) throws SQLException {
        try (Connection conexion = base.conexionAdmin();
                Statement sentencia = conexion.createStatement()) {
            sentencia.execute(sql);
        }
    }

    /**
     * Un {@code INSERT} minimo, valido en cuanto a restricciones: lo que tiene que pararlo es una
     * guarda, no un {@code CHECK}. Si un dia lo para un {@code CHECK}, la prueba seguiria en verde
     * midiendo otra cosa — por eso el mismo texto se ejecuta tambien con el rol de carga, donde
     * tiene que entrar.
     */
    private static final String INSERCION_MINIMA =
            """
            INSERT INTO parametro_tributario
                   (tipo, clave, valor_numerico, vigencia_desde, documento_fuente, usuario_carga)
            VALUES ('FICTICIO_GUARDAS', ?, 1, DATE '2026-01-01', 'prueba de las dos guardas', 'JNA')
            """;

    /** Un pool de un solo rol, para preguntarle a la base con la credencial que se quiere medir. */
    private static PublicacionDeCuadros puertoDeCuadros(String rol) {
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(rol);
        pool.setPassword(base.clave(rol));
        return new PublicacionDeCuadrosJdbc(JdbcClient.create(pool));
    }

    /**
     * La cabecera de la edicion la escribe quien puede: sin ella, el {@code publicacion_id} de las
     * filas del cuadro no referenciaria nada y el rechazo seria de la clave foranea, no de la
     * guarda.
     */
    private static long abrirUnaEdicionComoRolDeCarga() {
        return puertoDeCuadros(BaseDeDatosDePrueba.CARGA_PARAMETROS)
                .abrirEdicion(
                        new ParametroTributario(
                                null,
                                "FICTICIO_CUADRO_GUARDAS",
                                "EDICION",
                                null,
                                "cabecera de la prueba de las dos guardas",
                                new Vigencia(LocalDate.of(2026, 1, 1), null),
                                "prueba de las dos guardas"),
                        "JNA",
                        "HNA");
    }

    private static void insertar(Connection conexion, String clave) throws SQLException {
        try (var sentencia = conexion.prepareStatement(INSERCION_MINIMA)) {
            sentencia.setString(1, clave);
            sentencia.executeUpdate();
        }
        conexion.commit();
    }

    @Nested
    @DisplayName("Por el catalogo — que es lo unico que distingue las dos")
    class PorElCatalogo {

        @Test
        @DisplayName("sgtm_app no tiene INSERT, UPDATE ni DELETE sobre ninguna de las cuatro")
        void laAplicacionNoTienePrivilegioDeEscritura() throws SQLException {
            List<String> conPrivilegioDeMas = new ArrayList<>();
            for (String tabla : TABLAS) {
                for (String verbo : List.of("INSERT", "UPDATE", "DELETE")) {
                    String tiene =
                            consultar(
                                    "SELECT has_table_privilege('"
                                            + BaseDeDatosDePrueba.APP
                                            + "', '"
                                            + tabla
                                            + "', '"
                                            + verbo
                                            + "')");
                    if ("t".equals(tiene) || "true".equals(tiene)) {
                        conPrivilegioDeMas.add(tabla + "." + verbo);
                    }
                }
            }
            assertThat(conPrivilegioDeMas)
                    .as(
                            "la primera guarda es el GRANT. V55 se lo retiro a sgtm_app sobre las"
                                    + " tres tablas de valuacion, y V7 nunca se lo dio sobre"
                                    + " parametro_tributario: una peticion HTTP no puede tener el"
                                    + " camino mas corto hasta el cuadro de valores unitarios de todas"
                                    + " las municipalidades del pais")
                    .isEmpty();
        }

        @Test
        @DisplayName(
                "la politica de escritura de las cuatro nombra a rol_carga_parametros, y a nadie mas")
        void laPoliticaDeEscrituraNombraSoloAlRolDeCarga() throws SQLException {
            for (String tabla : TABLAS) {
                String roles =
                        consultar(
                                "SELECT coalesce(string_agg(array_to_string(roles, '+'), ' | '), '—')"
                                        + " FROM pg_policies"
                                        + " WHERE schemaname = 'public' AND tablename = '"
                                        + tabla
                                        + "' AND cmd = 'ALL'");
                assertThat(roles)
                        .as(
                                "la segunda guarda es la politica de RLS de %s. Es independiente del"
                                        + " GRANT —basta una para dar 42501— y por eso hay que mirarla"
                                        + " aparte",
                                tabla)
                        .isEqualTo(ROL_DE_CARGA);
            }
        }

        @Test
        @DisplayName("y las cuatro tienen la RLS FORZADA: sin FORCE, el dueno de la tabla la omite")
        void lasCuatroTienenRlsForzada() throws SQLException {
            for (String tabla : TABLAS) {
                String estado =
                        consultar(
                                "SELECT relrowsecurity::text || ' ' || relforcerowsecurity::text"
                                        + " FROM pg_class c JOIN pg_namespace n ON n.oid ="
                                        + " c.relnamespace WHERE n.nspname = 'public' AND c.relname"
                                        + " = '"
                                        + tabla
                                        + "'");
                assertThat(estado)
                        .as(
                                "%s sin FORCE: sgtm_owner —que es su dueno— escribiria saltandose la"
                                        + " politica, y el GRANT solo no lo impide",
                                tabla)
                        .isEqualTo("true true");
            }
        }

        @Test
        @DisplayName("y el rol de carga escribe esas cuatro, y ninguna otra tabla del esquema")
        void elRolDeCargaNoEscribeNadaMas() throws SQLException {
            String escribibles =
                    consultar(
                            "SELECT coalesce(string_agg(c.relname, ',' ORDER BY c.relname), '')"
                                    + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND"
                                    + " (has_table_privilege('"
                                    + ROL_DE_CARGA
                                    + "', c.oid, 'INSERT') OR has_table_privilege('"
                                    + ROL_DE_CARGA
                                    + "', c.oid, 'UPDATE') OR has_table_privilege('"
                                    + ROL_DE_CARGA
                                    + "', c.oid, 'DELETE'))");
            assertThat(List.of(escribibles.split(",")))
                    .as(
                            "la leccion de sgtm_respaldo (#155) aplicada al otro rol privilegiado:"
                                    + " sus privilegios son los minimos que la carga necesita, y se"
                                    + " comprueban ENUMERANDOLOS. Ni el conjunto, ni su detalle, ni la"
                                    + " auditoria: es la separacion de funciones SoD-1 de REQ-03"
                                    + " escrita en los privilegios")
                    .containsExactlyInAnyOrderElementsOf(TABLAS);
        }

        @Test
        @DisplayName("y no puede BORRAR en ninguna: una cifra normativa se republica, no se borra")
        void elRolDeCargaNoBorra() throws SQLException {
            String conBorrado =
                    consultar(
                            "SELECT coalesce(string_agg(c.relname, ',' ORDER BY c.relname), '')"
                                    + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND"
                                    + " has_table_privilege('"
                                    + ROL_DE_CARGA
                                    + "', c.oid, 'DELETE')");
            assertThat(conBorrado)
                    .as(
                            "regla 4 de CLAUDE.md: en el corpus normativo no se borra, se republica con"
                                    + " otra vigencia")
                    .isEmpty();
        }

        @Test
        @DisplayName("ninguna otra tabla normativa se queda fuera de la lista vigilada")
        void laListaVigiladaEsLaQueElEsquemaTiene() throws SQLException {
            String delCatalogo =
                    consultar(
                            "SELECT coalesce(string_agg(tablename, ',' ORDER BY tablename), '')"
                                    + " FROM pg_policies WHERE schemaname = 'public'"
                                    + " AND cmd = 'ALL' AND roles = ARRAY['"
                                    + ROL_DE_CARGA
                                    + "']::name[]");
            assertThat(List.of(delCatalogo.split(",")))
                    .as(
                            "una tabla normativa nueva cuya politica nombre al rol de carga y que"
                                    + " nadie anada a TABLAS quedaria sin las comprobaciones de arriba,"
                                    + " y esta prueba seguiria en verde vigilando cuatro")
                    .containsExactlyInAnyOrderElementsOf(TABLAS);
        }
    }

    @Nested
    @DisplayName("Por el sintoma — que NO las distingue, y aqui se demuestra")
    class PorElSintoma {

        @Test
        @DisplayName("sgtm_app no puede escribir parametro_tributario")
        void laAplicacionNoPuedeEscribirElCatalogoDeParametros() throws SQLException {
            try (Connection conexion = base.conexion(BaseDeDatosDePrueba.APP)) {
                assertThatThrownBy(() -> insertar(conexion, "app-sin-nada"))
                        .as("con las dos guardas puestas")
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo(PRIVILEGIO_INSUFICIENTE);
            }
        }

        @Test
        @DisplayName(
                "ni las dos tablas de cuadro que tienen escritor: depreciacion y valor_referencial_vehiculo")
        void laAplicacionNoPuedeEscribirLosCuadros() throws SQLException {
            long edicion = abrirUnaEdicionComoRolDeCarga();
            PublicacionDeCuadros conLaApp = puertoDeCuadros(BaseDeDatosDePrueba.APP);

            assertThatThrownBy(
                            () ->
                                    conLaApp.agregarDepreciacion(
                                            edicion,
                                            "01",
                                            "CONCRETO",
                                            "MUY BUENO",
                                            5,
                                            Alicuota.de("3"),
                                            "prueba de las dos guardas"))
                    .as(
                            "depreciacion no tenia ninguna prueba negativa antes de #435: la unica"
                                    + " que existia era la de valor_unitario_edificacion, en"
                                    + " kamayuk-rentas-catastro")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);

            assertThatThrownBy(
                            () ->
                                    conLaApp.agregarValorReferencial(
                                            edicion,
                                            2026,
                                            "AUTOMOVIL",
                                            "FICTICIA",
                                            "MODELO DE PRUEBA",
                                            2020,
                                            Dinero.de("1000.00"),
                                            "prueba de las dos guardas"))
                    .as("valor_referencial_vehiculo tampoco la tenia")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        @Test
        @DisplayName("devolverle el GRANT no abre nada, y el codigo de error es EL MISMO: 42501")
        void elSintomaNoDistingueCualDeLasDosGuardasActuo() throws SQLException {
            comoSuperusuario("GRANT INSERT ON parametro_tributario TO " + BaseDeDatosDePrueba.APP);
            try {
                assertThat(
                                consultar(
                                        "SELECT has_table_privilege('"
                                                + BaseDeDatosDePrueba.APP
                                                + "', 'parametro_tributario', 'INSERT')"))
                        .as("el catalogo SI ve que la primera guarda ya no esta")
                        .isIn("t", "true");

                try (Connection conexion = base.conexion(BaseDeDatosDePrueba.APP)) {
                    assertThatThrownBy(() -> insertar(conexion, "app-con-grant"))
                            .as(
                                    "y el sintoma no: con el privilegio devuelto, la politica de RLS"
                                            + " sigue parandolo con el mismo 42501 de antes. Es"
                                            + " exactamente el matiz que #380 midio, y el motivo por el"
                                            + " que una prueba de sintoma no puede vigilar dos guardas")
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo(PRIVILEGIO_INSUFICIENTE);
                }
            } finally {
                comoSuperusuario(
                        "REVOKE INSERT ON parametro_tributario FROM " + BaseDeDatosDePrueba.APP);
            }
        }

        @Test
        @DisplayName("y hay que quitar LAS DOS para que entre: es lo que «basta una» significa")
        void quitarLasDosAbreLaPuerta() throws SQLException {
            comoSuperusuario("GRANT INSERT ON parametro_tributario TO " + BaseDeDatosDePrueba.APP);
            comoSuperusuario(
                    "CREATE POLICY parametro_escritura_de_prueba ON parametro_tributario"
                            + " FOR ALL TO "
                            + BaseDeDatosDePrueba.APP
                            + " USING (true) WITH CHECK (true)");
            try (Connection conexion = base.conexion(BaseDeDatosDePrueba.APP)) {
                assertThatCode(() -> insertar(conexion, "app-con-las-dos-fuera"))
                        .as(
                                "con el GRANT y una politica que nombre a sgtm_app, la aplicacion"
                                        + " publica un valor normativo. Es el estado que las dos guardas"
                                        + " existen para impedir, y la unica forma de demostrar que las"
                                        + " dos hacen falta")
                        .doesNotThrowAnyException();
            } finally {
                comoSuperusuario(
                        "DROP POLICY parametro_escritura_de_prueba ON parametro_tributario");
                comoSuperusuario(
                        "REVOKE INSERT ON parametro_tributario FROM " + BaseDeDatosDePrueba.APP);
            }
        }

        @Test
        @DisplayName("el rol de carga si escribe: lo que para a sgtm_app no es un CHECK")
        void elRolDeCargaSiEscribe() throws SQLException {
            try (Connection conexion = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
                assertThatCode(() -> insertar(conexion, "carga-si-entra"))
                        .as(
                                "sin esto, las tres pruebas de arriba podrian estar pasando en verde"
                                        + " porque la fila viola una restriccion y no porque una guarda"
                                        + " la pare")
                        .doesNotThrowAnyException();
            }
        }
    }
}
