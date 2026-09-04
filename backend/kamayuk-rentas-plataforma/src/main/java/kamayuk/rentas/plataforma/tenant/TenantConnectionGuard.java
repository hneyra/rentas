package kamayuk.rentas.plataforma.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Verifica que ninguna conexion vuelva al pool con el contexto de municipalidad todavia fijado, y
 * descarta la que lo tenga (ARQ-03 §2).
 *
 * <p>Es el punto donde este diseno falla si se descuida. {@code SET LOCAL} muere con la
 * transaccion, asi que en el camino correcto esta verificacion nunca encuentra nada. Encuentra algo
 * cuando alguien escribio {@code SET SESSION}, o fijo el parametro fuera de una transaccion:
 * entonces la conexion queda contaminada y la siguiente peticion —de otra municipalidad— la
 * reutilizaria con el contexto ajeno todavia puesto.
 *
 * <p>Cerrarla no basta: cerrar una conexion de un pool la devuelve, no la destruye. Por eso hace
 * falta {@code descartar}, que en produccion es {@code HikariDataSource::evictConnection}.
 *
 * <p><b>Costo.</b> Una consulta de ida y vuelta por cada devolucion de conexion. Se acepta: la
 * alternativa es confiar en que nadie escriba {@code SET SESSION} en los proximos anios, y una fuga
 * entre municipalidades es el riesgo numero uno del proyecto. La prueba del pool mide el sobrecosto
 * y lo informa en la salida en lugar de convertirlo en una asercion intermitente.
 */
public final class TenantConnectionGuard extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantConnectionGuard.class);

    private final Consumer<Connection> descartar;
    private final AtomicLong contaminadasDetectadas = new AtomicLong();
    private final AtomicLong noVerificadas = new AtomicLong();

    /**
     * @param delegado el DataSource real, normalmente el pool
     * @param descartar como sacar del pool una conexion contaminada. Con HikariCP, {@code
     *     hikari::evictConnection}
     */
    public TenantConnectionGuard(DataSource delegado, Consumer<Connection> descartar) {
        super(delegado);
        this.descartar = descartar;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return envolver(super.getConnection());
    }

    @Override
    public Connection getConnection(String usuario, String clave) throws SQLException {
        return envolver(super.getConnection(usuario, clave));
    }

    /** Conexiones que volvieron al pool contaminadas. Deberia ser siempre cero. */
    public long contaminadasDetectadas() {
        return contaminadasDetectadas.get();
    }

    /**
     * Devoluciones en las que no se pudo verificar, tipicamente porque la transaccion quedo
     * abortada. No son fugas, pero un numero que crece significa que la verificacion esta ciega mas
     * a menudo de lo aceptable.
     */
    public long noVerificadas() {
        return noVerificadas.get();
    }

    private Connection envolver(Connection real) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, metodo, argumentos) -> {
                            if ("close".equals(metodo.getName())
                                    && (argumentos == null || argumentos.length == 0)) {
                                cerrarVerificando(real);
                                return null;
                            }
                            try {
                                return metodo.invoke(real, argumentos);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        });
    }

    private void cerrarVerificando(Connection real) throws SQLException {
        if (real.isClosed()) {
            return;
        }
        String residuo = leerContexto(real);
        if (residuo != null && !residuo.isBlank()) {
            contaminadasDetectadas.incrementAndGet();
            log.error(
                    "La conexion vuelve al pool con {} = '{}'. Se descarta en lugar de"
                            + " reutilizarla. El parametro se fijo con alcance de sesion, o fuera de"
                            + " una transaccion: la siguiente peticion habria consultado con el"
                            + " contexto de otra municipalidad (ARQ-03 §2)",
                    TenantTransactionManager.PARAMETRO,
                    residuo);
            descartarConRegistro(real);
        }
        real.close();
    }

    /**
     * Se captura {@code RuntimeException} a proposito, y se actua. {@code descartar} es codigo del
     * pool: si falla, hay que registrarlo y aun asi cerrar la conexion. Dejar escapar la excepcion
     * aqui filtraria la conexion, que es peor.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void descartarConRegistro(Connection real) {
        try {
            descartar.accept(real);
        } catch (RuntimeException e) {
            log.error("No se pudo descartar la conexion contaminada", e);
        }
    }

    private @Nullable String leerContexto(Connection real) {
        try (Statement sentencia = real.createStatement();
                ResultSet fila =
                        sentencia.executeQuery(
                                "SELECT current_setting('"
                                        + TenantTransactionManager.PARAMETRO
                                        + "', true)")) {
            return fila.next() ? fila.getString(1) : null;
        } catch (SQLException e) {
            // Caso tipico: la transaccion quedo abortada y la conexion no acepta
            // consultas. No se puede concluir nada, y descartar toda conexion de
            // toda transaccion fallida vaciaria el pool ante el primer error.
            noVerificadas.incrementAndGet();
            log.warn(
                    "No se pudo verificar el contexto al devolver la conexion: {}", e.getMessage());
            return null;
        }
    }
}
