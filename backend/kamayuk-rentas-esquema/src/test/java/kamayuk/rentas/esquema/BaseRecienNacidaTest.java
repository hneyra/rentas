package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La base de cada corrida la creamos nosotros, se llegue por Testcontainers o por motor externo.
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>{@code MotorPostgres} tenia dos caminos que parecian equivalentes y no lo eran. El externo
 * creaba la base con {@code MotorPostgres.sentenciaDeCreacion} —desde {@code template0}, sin
 * heredar nada—; el de Testcontainers se quedaba con la base por omision del contenedor, que {@code
 * initdb} crea desde {@code template1}. Y la imagen es {@code postgis/postgis}, que instala PostGIS
 * <b>en {@code template1}</b>.
 *
 * <p>El resultado, medido en CI el 2026-09-05: la base de prueba nacia con {@code postgis}, {@code
 * postgis_topology}, {@code postgis_tiger_geocoder} y {@code fuzzystrmatch} dentro, y con ellas
 * {@code spatial_ref_sys} — una tabla que este esquema no crea y que dejaba a {@code
 * AislamientoMultiTenantTest} en rojo. <b>La prueba bloqueante mas importante del sistema no
 * llegaba a ejecutarse en CI</b>, y en local nunca fallaba porque en local se usa el otro camino.
 *
 * <h2>Por que esta prueba y no una exencion</h2>
 *
 * <p>Porque eximir {@code spatial_ref_sys} habria cerrado el rojo dejando en pie la divergencia:
 * local probando una base sin extensiones y CI una con ellas. Lo que hay que sujetar no es que esa
 * tabla no aparezca, sino que <b>los dos caminos entreguen la misma base</b>.
 *
 * <h2>Donde muerde, y donde no</h2>
 *
 * <p>Muerde en los dos caminos, pero solo el de Testcontainers podia estar mal: contra un motor
 * externo esta prueba pasaba ya antes del arreglo. Asi que la mutacion que la mide —devolverle a
 * {@code resolver()} el {@code contenedor.getJdbcUrl()} pelado— <b>solo se puede medir donde corre
 * ese camino</b>, que es CI. Se dice aqui para que nadie concluya de un verde local que la ha
 * comprobado.
 */
@DisplayName("C-11 — La base de prueba la crea este motor, por los dos caminos")
class BaseRecienNacidaTest {

    @Test
    @DisplayName("la base sobre la que se prueba la creo `sentenciaDeCreacion`, no el contenedor")
    void laBaseLaCreamosNosotros() throws Exception {
        try (MotorPostgres motor = MotorPostgres.iniciar()) {
            assertThat(baseActual(motor))
                    .as(
                            "la base de esta corrida no la creo `MotorPostgres`: se llama «%s» y"
                                    + " toda base creada por `sentenciaDeCreacion` empieza por «%s». Una"
                                    + " base heredada —la por omision del contenedor, o la que alguien"
                                    + " nombro en la URL— no pasa por `TEMPLATE template0`, asi que trae"
                                    + " lo que su plantilla tuviera: con la imagen de PostGIS, cuatro"
                                    + " extensiones y la tabla `spatial_ref_sys`",
                            baseActual(motor), MotorPostgres.PREFIJO_DE_LA_BASE)
                    .startsWith(MotorPostgres.PREFIJO_DE_LA_BASE);
        }
    }

    @Test
    @DisplayName("EL CONTRASTE: y esa base es de verdad la que la URL del motor nombra")
    void yEsLaQueLaUrlNombra() throws Exception {
        // Sin esto, «empieza por el prefijo» seria compatible con leer `current_database()` de
        // otra conexion cualquiera. Lo que se afirma es que la URL que el motor entrega —la que
        // usan todas las pruebas de base— apunta a esa misma base.
        try (MotorPostgres motor = MotorPostgres.iniciar()) {
            assertThat(motor.url())
                    .as("la URL del motor tiene que nombrar la base que se acaba de crear")
                    .contains("/" + baseActual(motor));
        }
    }

    private static String baseActual(MotorPostgres motor) throws Exception {
        try (Connection conexion =
                        DriverManager.getConnection(
                                motor.url(), motor.usuarioAdmin(), motor.claveAdmin());
                Statement sentencia = conexion.createStatement();
                ResultSet fila = sentencia.executeQuery("SELECT current_database()")) {
            fila.next();
            return fila.getString(1);
        }
    }
}
