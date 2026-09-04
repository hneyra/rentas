package kamayuk.rentas.seguridad.infraestructura;

import java.time.LocalDate;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve el permiso contra {@code acceso}, {@code grupo}, {@code miembro}, {@code permiso} y
 * {@code usuario}: el modelo del manual, tal cual.
 *
 * <h2>La precedencia, que es la decision que hay que conocer</h2>
 *
 * <p><b>La excepcion del usuario decide; si no la hay, mandan sus grupos.</b> Concretamente: si
 * existe una fila de {@code permiso} para ese usuario y ese acceso, esa fila resuelve —otorgue o
 * niegue—; si no existe, se toma la union de los permisos de los grupos vigentes a los que
 * pertenece.
 *
 * <p>Se eligio asi porque es la unica lectura que sirve para los dos casos reales, y el segundo es
 * el que importa. <b>Ampliar</b>: alguien de Mesa de Partes que ademas puede imprimir valores. Y
 * <b>restringir</b>: alguien de Mesa de Partes que, por lo que sea, no debe poder anular recibos.
 * Con una union pura el segundo caso no se puede expresar; la unica salida seria sacarlo del grupo
 * y repetirle veinte permisos a mano, que es como se acaba con un padron de permisos que nadie
 * entiende.
 *
 * <p>El precio esta a la vista y se acepta: una fila de excepcion <b>sustituye</b> al grupo entero
 * para ese acceso, no se combina con el. Quien otorgue una excepcion tiene que escribir en ella
 * todos los privilegios que quiera dejar, no solo el que agrega. La pantalla de permisos parte de
 * los del grupo justamente por eso.
 *
 * <p>Las otras dos cosas que la consulta decide, y conviene que esten a la vista:
 *
 * <ol>
 *   <li><b>La vigencia se comprueba en los tres sitios</b> (RF-123): la del usuario, la del grupo y
 *       la de la pertenencia. Comprobar solo una deja abierta la puerta mas comoda: dar de baja al
 *       usuario y que siga entrando por un grupo vigente.
 *   <li><b>Las tres tablas llevan {@code activo}/{@code habilitado}</b>, y se respetan. Dar de baja
 *       no es borrar (RNF-051), asi que la fila sigue ahi y hay que mirarla.
 * </ol>
 *
 * <p>La consulta no filtra por municipalidad: lo hace la politica RLS con el contexto de la
 * transaccion. Un usuario de otra municipalidad, sencillamente, no existe desde aqui.
 */
@Component
public class ComprobadorDeAccesoJdbc extends RepositorioJdbc implements ComprobadorDeAcceso {

    public ComprobadorDeAccesoJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * <b>{@code @Transactional} no es decorativo aqui.</b> Esta consulta lee {@code usuario},
     * {@code permiso}, {@code grupo} y {@code miembro}, que son tablas de tenant con RLS: sus
     * politicas leen {@code app.municipalidad_id}, y ese parametro lo fija {@code
     * TenantTransactionManager} con {@code SET LOCAL} <b>al abrir una transaccion</b>. Sin
     * transaccion no hay parametro, y PostgreSQL no devuelve cero filas sino que falla con
     * «unrecognized configuration parameter» —que es el comportamiento correcto del aislamiento
     * (DAT-01 §0) y aqui llegaba como un 500—.
     *
     * <p>Se descubrio con la primera peticion autenticada del sistema: el guardia corre en un
     * {@code preHandle}, antes de que ningun caso de uso abra su transaccion, asi que era el unico
     * sitio que consultaba tablas de tenant sin una. Las pruebas no lo veian porque abren la suya.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean autoriza(String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {

        String columna = privilegio.columna();

        String sql =
                "SELECT COALESCE("
                        // 1. La excepcion del usuario, si la hay: decide, otorgue o niegue.
                        + "  (SELECT p."
                        + columna
                        + "     FROM permiso p"
                        + "     JOIN acceso a ON a.id = p.acceso_id AND a.codigo = :acceso"
                        + "     JOIN usuario u ON u.id = p.usuario_id"
                        + "    WHERE u.cuenta = :usuario),"
                        // 2. Si no la hay: la union de los grupos vigentes.
                        + "  EXISTS ("
                        + "    SELECT 1 FROM usuario u"
                        + "      JOIN miembro m ON m.usuario_id = u.id AND m.activo"
                        + "      JOIN grupo g ON g.id = m.grupo_id"
                        + "                  AND g.habilitado"
                        + "                  AND (g.vigencia_desde IS NULL OR g.vigencia_desde <= :fecha)"
                        + "                  AND (g.vigencia_hasta IS NULL OR g.vigencia_hasta >= :fecha)"
                        + "      JOIN permiso p ON p.grupo_id = g.id"
                        + "      JOIN acceso a ON a.id = p.acceso_id AND a.codigo = :acceso AND a.activo"
                        + "     WHERE u.cuenta = :usuario AND p."
                        + columna
                        + "  ), false)"
                        // 3. Y por encima de todo: el usuario tiene que estar habilitado y
                        //    vigente. Va al final para que se lea como lo que es, una
                        //    condicion que anula cualquier permiso.
                        + " AND EXISTS ("
                        + "   SELECT 1 FROM usuario u"
                        + "    WHERE u.cuenta = :usuario"
                        + "      AND u.habilitado"
                        + "      AND (u.vigencia_desde IS NULL OR u.vigencia_desde <= :fecha)"
                        + "      AND (u.vigencia_hasta IS NULL OR u.vigencia_hasta >= :fecha))";

        return Boolean.TRUE.equals(
                jdbc().sql(sql)
                        .param("usuario", usuario)
                        .param("acceso", acceso)
                        .param("fecha", fecha)
                        .query(Boolean.class)
                        .single());
    }
}
