package kamayuk.rentas.esquema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * Fija el contexto de municipalidad de una transaccion.
 *
 * <p>{@code set_config(clave, valor, true)} es la forma parametrizada de {@code SET LOCAL}: el
 * tercer argumento significa "local a la transaccion". Se usa esta y no {@code SET LOCAL} literal
 * solo porque {@code SET} no admite parametros de enlace y concatenar el valor invitaria a
 * inyeccion.
 *
 * <p>Nunca {@code SET SESSION}: sobrevive al retorno de la conexion al pool y contaminaria la
 * peticion de otra municipalidad (ARQ-03 §3.2).
 */
public final class ContextoDeTenant {

    /**
     * Los dos {@code SQLSTATE} con que puede fallar una consulta a una tabla de tenant sin contexto
     * fijado. <b>Son dos, no uno</b>, y la diferencia no es cosmetica:
     *
     * <ul>
     *   <li>{@code 42704} <i>undefined_object</i> — en una conexion que nunca tuvo el parametro.
     *       {@code current_setting} sin valor por omision falla porque el parametro no existe.
     *   <li>{@code 22P02} <i>invalid_text_representation</i> — en una conexion <b>reutilizada del
     *       pool</b>. Una vez que {@code SET LOCAL} fijo el parametro alguna vez, PostgreSQL lo
     *       deja <b>definido</b> en la sesion; al terminar la transaccion vuelve a su valor previo,
     *       que es la <b>cadena vacia</b>, no la inexistencia. {@code ''::bigint} falla.
     * </ul>
     *
     * <p>Lo que importa se cumple igual en los dos casos: la consulta <b>falla</b>, no devuelve
     * vacio ni devuelve todo (RNF-032). Pero quien escriba la alerta de observabilidad de consultas
     * sin contexto tiene que reconocer los dos codigos, y quien escriba una politica RLS tiene que
     * recordar que sin contexto el valor es la cadena vacia: una politica que la tratara como "sin
     * filtro" abriria la fuga que la forma estricta de {@code current_setting} impide.
     */
    public static final Set<String> ESTADOS_SIN_CONTEXTO = Set.of("42704", "22P02");

    private ContextoDeTenant() {}

    public static void fijar(Connection conexion, long municipalidadId) throws SQLException {
        try (PreparedStatement sentencia =
                conexion.prepareStatement("SELECT set_config('app.municipalidad_id', ?, true)")) {
            sentencia.setString(1, Long.toString(municipalidadId));
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
            }
        }
    }
}
