package kamayuk.rentas.seguridad.infraestructura;

import java.util.Optional;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.Municipalidad;
import kamayuk.rentas.seguridad.dominio.MunicipalidadRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La municipalidad de la sesion, resuelta por el motor y no por Java.
 *
 * <h2>El {@code WHERE} es aqui la barrera, y conviene decir por que</h2>
 *
 * <p>En las tablas de tenant el filtrado no se escribe: lo pone la politica RLS, y un repositorio
 * que anadiera {@code WHERE municipalidad_id = ?} estaria repitiendo lo que ya hace el motor.
 * {@code municipalidad} es la excepcion, y no por descuido: es el <b>registro</b> de tenants, no
 * una tabla de tenant, y {@code V6} le pone a proposito {@code FOR SELECT USING (true)} porque los
 * procesos masivos la recorren entera —una por una, con {@code SET LOCAL} en cada rama—.
 *
 * <p>De modo que aqui hay {@code WHERE}. Lo que no hay es un parametro de Java: el identificador lo
 * pone el motor con {@link RepositorioJdbc#MUNICIPALIDAD_ACTUAL}, el mismo parametro de sesion que
 * consultan las politicas RLS y que fija {@code SET LOCAL} al abrir la transaccion. Es la misma
 * forma que usa {@code RegimenDeLaInstalacionJdbc} para leer {@code es_demostracion} de esa misma
 * tabla, y por el mismo motivo: sin contexto la consulta <b>falla</b> —«unrecognized configuration
 * parameter» o «invalid input syntax for type bigint: ""»— en lugar de responder por la
 * municipalidad equivocada.
 *
 * <p>Se usa la forma estricta de {@code current_setting}, sin el segundo argumento, para que ese
 * fallo sea ruidoso. La forma tolerante devolveria {@code NULL}, la comparacion no casaria con nada
 * y el resultado seria «esta municipalidad no existe», que se parece demasiado a una respuesta
 * legitima.
 */
@Repository
public class MunicipalidadRepositoryJdbc extends RepositorioJdbc
        implements MunicipalidadRepository {

    private static final String CONSULTA =
            "SELECT id, ubigeo, nombre, tipo FROM municipalidad WHERE id = " + MUNICIPALIDAD_ACTUAL;

    public MunicipalidadRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Municipalidad> deLaSesion() {
        return jdbc().sql(CONSULTA)
                .query(
                        (fila, numero) ->
                                new Municipalidad(
                                        fila.getLong("id"),
                                        // char(6): PostgreSQL lo devuelve relleno con espacios si
                                        // alguna fila se escribio corta, y el ubigeo se compara.
                                        fila.getString("ubigeo").strip(),
                                        fila.getString("nombre"),
                                        fila.getString("tipo")))
                .optional();
    }
}
