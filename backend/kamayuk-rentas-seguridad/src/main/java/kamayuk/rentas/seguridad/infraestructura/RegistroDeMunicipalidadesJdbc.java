package kamayuk.rentas.seguridad.infraestructura;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import kamayuk.rentas.seguridad.dominio.RegistroDeMunicipalidades;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * La unica clase del sistema que se conecta como {@code sgtm_owner}, y por eso conviene mirarla con
 * atencion.
 *
 * <h2>Por que no usa el pool de la aplicacion</h2>
 *
 * <p>Porque no puede: el pool es {@code sgtm_app}, y {@code municipalidad} solo la escribe {@code
 * sgtm_owner} (ARQ-03 §4, {@code V6__rls.sql}). La conexion se abre para una sentencia y se cierra;
 * no queda en ningun pool ni la puede tomar nadie mas.
 *
 * <h2>Las tres condiciones que la mantienen encerrada</h2>
 *
 * <ul>
 *   <li>{@code @Profile("batch")}: no existe en el proceso que atiende HTTP. El artefacto es el
 *       mismo (ADR-0003), pero este componente solo se instancia donde no hay puerto expuesto.
 *   <li>{@code @ConditionalOnProperty}: tampoco existe en una ejecucion batch normal —una
 *       determinacion masiva, por ejemplo—. Hace falta pedir la implantacion explicitamente.
 *   <li>Sus credenciales llegan por propiedades propias, distintas de las de la aplicacion, asi que
 *       un despliegue que no las ponga no obtiene un componente a medias: no lo obtiene.
 * </ul>
 *
 * <p>Las tres juntas dicen lo mismo de tres formas: esto corre cuando alguien implanta una
 * municipalidad, y en ningun otro momento.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.implantacion.ubigeo")
public class RegistroDeMunicipalidadesJdbc implements RegistroDeMunicipalidades {

    private final String url;
    private final String usuario;
    private final String clave;

    public RegistroDeMunicipalidadesJdbc(
            @Value("${sgtm.implantacion.url}") String url,
            @Value("${sgtm.implantacion.owner-usuario:sgtm_owner}") String usuario,
            @Value("${sgtm.implantacion.owner-clave}") String clave) {
        this.url = url;
        this.usuario = usuario;
        this.clave = clave;
    }

    @Override
    public long darDeAltaSiFalta(
            String ubigeo, String nombre, String tipo, boolean esDemostracion) {
        try (Connection conexion = DriverManager.getConnection(url, usuario, clave)) {
            insertarSiFalta(conexion, ubigeo, nombre, tipo, esDemostracion);
            return identificador(conexion, ubigeo);
        } catch (SQLException e) {
            // Sin el ubigeo, el mensaje de PostgreSQL no dice de que municipalidad habla.
            throw new IllegalStateException("No se pudo dar de alta la municipalidad " + ubigeo, e);
        }
    }

    /**
     * {@code ON CONFLICT (ubigeo) DO NOTHING} y despues la consulta.
     *
     * <p>Es lo que hace el paso idempotente sin leer primero: leer y luego insertar deja una
     * ventana entre las dos cosas, y dos despliegues a la vez acabarian uno de ellos con un error
     * de clave duplicada. Asi los dos acaban con la misma fila.
     */
    private static void insertarSiFalta(
            Connection conexion, String ubigeo, String nombre, String tipo, boolean esDemostracion)
            throws SQLException {
        try (PreparedStatement alta =
                conexion.prepareStatement(
                        "INSERT INTO municipalidad (ubigeo, nombre, tipo, es_demostracion)"
                                + " VALUES (?, ?, ?, ?)"
                                + " ON CONFLICT (ubigeo) DO NOTHING")) {
            alta.setString(1, ubigeo);
            alta.setString(2, nombre);
            alta.setString(3, tipo);
            alta.setBoolean(4, esDemostracion);
            alta.executeUpdate();
        }
    }

    private static long identificador(Connection conexion, String ubigeo) throws SQLException {
        try (PreparedStatement consulta =
                conexion.prepareStatement("SELECT id FROM municipalidad WHERE ubigeo = ?")) {
            consulta.setString(1, ubigeo);
            try (ResultSet fila = consulta.executeQuery()) {
                if (!fila.next()) {
                    throw new IllegalStateException(
                            "La municipalidad " + ubigeo + " no quedo dada de alta");
                }
                return fila.getLong("id");
            }
        }
    }
}
