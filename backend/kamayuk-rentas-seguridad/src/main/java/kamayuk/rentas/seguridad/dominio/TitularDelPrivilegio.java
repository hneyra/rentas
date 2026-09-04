package kamayuk.rentas.seguridad.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una cuenta que <b>hoy puede ejercer</b> un privilegio sobre un acceso, y de donde le viene
 * (#583).
 *
 * <h2>Por que existe, si la matriz de un usuario ya lo dice</h2>
 *
 * <p>Porque lo dice <b>de uno</b>. {@code GET /seguridad/usuarios/{id}/permisos} contesta que puede
 * una persona; preguntarlo del padron costaba <b>una peticion por cuenta</b> —medido en el issue:
 * 200 peticiones y ~4,2 MB de JSON para pintar una insignia—, y no habia forma de acotar por grupo,
 * porque la excepcion propia de una cuenta <b>sustituye</b> a lo que su grupo le da: alguien cuyo
 * grupo no tiene {@code ESPECIAL} puede tenerlo por excepcion, y al reves.
 *
 * <p>Esta es la pregunta inversa, resuelta por el servidor con la <b>misma</b> expresion de
 * precedencia que el guardia y que la matriz de un usuario. Si se resolviera con otro SQL, la
 * pantalla que audita quien tiene la llave de la caja y el servidor que la abre acabarian diciendo
 * cosas distintas.
 *
 * <h2>Lo que su forma decide</h2>
 *
 * <ol>
 *   <li><b>No publica {@code habilitado}.</b> Aqui salen solo las cuentas que hoy pueden operar
 *       —misma regla que el guardia: habilitacion y vigencia del usuario, del grupo y de la
 *       pertenencia (RF-123)—, asi que la columna valdria {@code true} en todas las filas y afirmar
 *       algo que nunca cambia es afirmar que existe la otra mitad. Lo que una cuenta deshabilitada
 *       <b>conserva</b> lo contesta la otra lectura de este issue, la de lo configurado.
 *   <li><b>{@code grupoId} nombra al grupo que otorga <i>este</i> privilegio</b>, no al unico que
 *       aporta algo sobre el acceso. Son dos cosas distintas cuando alguien pertenece a dos grupos
 *       y solo uno concede {@code ESPECIAL}: nombrar «varios» ahi le quitaria a quien administra el
 *       unico dato con el que sabria de que grupo retirarlo. Nulo sigue significando «no hay uno
 *       solo», y con {@code origen = GRUPO} eso se lee sin ambiguedad.
 * </ol>
 *
 * @param usuarioId el id de la cuenta, el mismo que publica cada fila de {@code GET
 *     /seguridad/usuarios}
 * @param origen quien manda, la excepcion de la propia cuenta o sus grupos
 * @param grupoId el grupo que lo otorga, cuando el origen es {@code GRUPO} y hay uno solo
 */
public record TitularDelPrivilegio(
        long usuarioId,
        String cuenta,
        String nombre,
        PermisoEfectivo.OrigenDelPermiso origen,
        @Nullable Long grupoId) {

    public TitularDelPrivilegio {
        Objects.requireNonNull(cuenta, "Un titular del privilegio es una cuenta");
        Objects.requireNonNull(nombre, "Un titular del privilegio tiene nombre");
        Objects.requireNonNull(origen, "Un titular del privilegio dice de donde le viene");
        if (origen == PermisoEfectivo.OrigenDelPermiso.EXCEPCION && grupoId != null) {
            throw new IllegalArgumentException(
                    "Una excepcion de usuario no viene de ningun grupo: " + cuenta);
        }
    }
}
