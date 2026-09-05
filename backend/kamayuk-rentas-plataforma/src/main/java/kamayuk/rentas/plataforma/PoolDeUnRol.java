package kamayuk.rentas.plataforma;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import kamayuk.rentas.plataforma.tenant.TenantConnectionGuard;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Un pool con OTRO rol de base de datos, con el mismo camino al {@code SET LOCAL} (C-8).
 *
 * <h2>Para que existe</h2>
 *
 * <p>Casi todo este backend habla con la base como {@code kamayuk_app}, y {@code
 * ConfiguracionDeTenant} cablea ese unico pool. Pero hay trabajo que <b>no puede</b> correr con esa
 * credencial: la proyeccion local de {@code catastro} la escribe {@code rol_ingestor_catastro} y
 * `V4` y `V5` no le dan a {@code kamayuk_app} mas que {@code SELECT} sobre ella (ADR-0027 §3). Un
 * proceso que la escriba necesita su propio pool.
 *
 * <p>Lo que este objeto evita es que ese proceso se lo monte por su cuenta: si lo hiciera, se
 * llevaria consigo <b>dos piezas de plataforma que viven en un subpaquete interno</b> —el gestor de
 * transacciones que emite el {@code SET LOCAL} y el guardia que vigila la devolucion al pool—, y
 * Spring Modulith lo rechaza con razon. Peor que el rechazo seria la salida facil: un {@code
 * JdbcTransactionManager} normal, que abre transacciones <b>sin contexto de tenant</b> y hace que
 * toda consulta a una tabla con RLS reviente (#486).
 *
 * <h2>Las dos piezas, y por que las dos</h2>
 *
 * <ul>
 *   <li>{@link TenantTransactionManager} es lo unico que lleva la municipalidad a la base. Sin el
 *       no hay {@code SET LOCAL} y la politica RLS no devuelve vacio: falla.
 *   <li>{@link TenantConnectionGuard} descarta toda conexion que vuelva al pool con {@code
 *       app.municipalidad_id} todavia puesto. Con {@code SET LOCAL} eso no puede pasar —muere con
 *       la transaccion—, y por eso el guardia es lo que <b>demuestra</b> que se uso {@code SET
 *       LOCAL} y no {@code SET SESSION} (regla 3).
 * </ul>
 */
public final class PoolDeUnRol {

    private PoolDeUnRol() {}

    /**
     * Un pool para ese rol, ya con el guardia puesto.
     *
     * @param maximo cuantas conexiones como mucho. Un proceso por lotes de un solo hilo pide dos:
     *     un pool grande no acelera nada y compite por las conexiones del motor con el proceso que
     *     atiende la ventanilla
     */
    public static DataSource con(
            String url, String usuario, String clave, int maximo, String nombre) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(usuario);
        pool.setPassword(clave);
        pool.setMaximumPoolSize(maximo);
        pool.setPoolName(nombre);
        return new TenantConnectionGuard(pool, pool::evictConnection);
    }

    /** El gestor de transacciones que emite el {@code SET LOCAL} sobre ese pool. */
    public static PlatformTransactionManager transaccionesDe(DataSource fuente) {
        return new TenantTransactionManager(fuente);
    }
}
