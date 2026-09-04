package kamayuk.rentas.plataforma.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Emite {@code SET LOCAL app.municipalidad_id} al abrir cada transaccion.
 *
 * <p>Es el <b>unico</b> lugar del sistema donde el identificador de municipalidad llega a la base
 * de datos (ARQ-03 §2). Ningun repositorio, servicio ni consulta lo recibe como parametro: si el
 * desarrollador no lo maneja, no puede olvidarlo.
 *
 * <p><b>{@code SET LOCAL}, nunca {@code SET SESSION}.</b> {@code SET LOCAL} muere con la
 * transaccion; {@code SET SESSION} sobrevive al retorno de la conexion al pool y contaminaria la
 * peticion de otra municipalidad. Es el error mas peligroso posible en este diseno, y por eso
 * {@link TenantConnectionGuard} verifica al devolver la conexion en lugar de confiar en que nadie
 * lo escriba.
 *
 * <p>El valor se fija con {@code set_config(clave, valor, true)}, que es la forma parametrizada de
 * {@code SET LOCAL}: {@code SET} no admite parametros de enlace y concatenar el valor invitaria a
 * inyeccion.
 *
 * <h2>Transacciones sin contexto</h2>
 *
 * <p>Una transaccion abierta sin contexto <b>no</b> falla aqui: falla despues, al tocar la primera
 * tabla de tenant, porque la politica RLS usa {@code current_setting} sin valor por omision. Se
 * deja asi a proposito. Hay transacciones legitimas sin municipalidad —leer el catalogo nacional,
 * listar las municipalidades activas antes de iterarlas en un proceso masivo—, y quien decide si el
 * acceso es legitimo es la politica de la tabla, no este objeto.
 */
public class TenantTransactionManager extends JdbcTransactionManager {

    @java.io.Serial private static final long serialVersionUID = 1L;

    static final String PARAMETRO = "app.municipalidad_id";

    public TenantTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws java.sql.SQLException {
        super.prepareTransactionalConnection(con, definition);

        MunicipalidadId municipalidadId = TenantContext.actualSiHay().orElse(null);
        if (municipalidadId == null) {
            return;
        }
        try (PreparedStatement sentencia = con.prepareStatement("SELECT set_config(?, ?, true)")) {
            sentencia.setString(1, PARAMETRO);
            sentencia.setString(2, Long.toString(municipalidadId.valor()));
            sentencia.execute();
        }
    }
}
