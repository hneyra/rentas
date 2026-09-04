package kamayuk.rentas.parametros.infraestructura;

import java.time.Clock;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: la puerta de escritura a la cache local que las pruebas usan para
 * sembrar un conjunto ya sellado.
 *
 * <p>Conserva el nombre que tenia el repositorio de produccion antes de P5B, y es deliberado: lo
 * que las pruebas de los otros ocho modulos necesitan no ha cambiado —«dado un conjunto sellado con
 * estos valores, calcula»— y renombrarlo habria mezclado el cambio de arquitectura con un
 * renombrado de veinte clases.
 *
 * <p><b>Lo que si cambio:</b> ya no escribe en {@code parametro_tributario} —esa tabla se fue en
 * `V2`— sino en la <b>cache local</b> ({@code normativa_*}, `V3`), que es donde en produccion deja
 * las filas la descarga verificada. La lectura que las pruebas ejercitan despues es la real: {@link
 * kamayuk.rentas.parametros.aplicacion.LectorDeParametrosCacheados}, sin ningun doble.
 */
public class ParametrosRepositoryJdbc {

    private final JdbcClient jdbc;
    private final CacheDeSnapshotsJdbc cache;

    public ParametrosRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.cache = new CacheDeSnapshotsJdbc(jdbc, Clock.systemUTC());
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public CacheDeSnapshots cache() {
        return cache;
    }
}
