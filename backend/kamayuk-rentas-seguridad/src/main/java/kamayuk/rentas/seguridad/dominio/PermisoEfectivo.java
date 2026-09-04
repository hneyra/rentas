package kamayuk.rentas.seguridad.dominio;

import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que un usuario puede hacer sobre un acceso, y <b>de donde le viene</b> (#543).
 *
 * <h2>Por que el origen va en el dato y no lo deduce quien pregunta</h2>
 *
 * <p>La regla de precedencia que documenta {@code ComprobadorDeAccesoJdbc} es que una fila de
 * excepcion de usuario <b>sustituye</b> al grupo entero para ese acceso, otorgue o niegue: no se
 * suma. Deducirla comparando dos listas —los permisos del grupo y los del usuario— obliga a quien
 * pregunta a reimplementar esa regla, y es justo la que no se puede equivocar: el frontend la tenia
 * invertida (calculaba {@code on = esPropio || esHeredado}), que convierte una excepcion que
 * <b>restringe</b> en una que amplia.
 *
 * <p>Por eso esto no es la union de dos lecturas sino <b>una fila por acceso</b>, ya resuelta, que
 * dice cual de las dos fuentes mando.
 *
 * <h2>Las dos cosas que su forma decide</h2>
 *
 * <ol>
 *   <li><b>Una excepcion aparece aunque no otorgue nada.</b> Es la unica forma de distinguir «se le
 *       nego expresamente» de «nunca lo tuvo», y negar es la mitad del motivo por el que la
 *       excepcion existe. Un acceso sin excepcion y sin nada del grupo, en cambio, no produce fila:
 *       serian 134 filas vacias por usuario.
 *   <li><b>{@code grupoId} es nulo cuando el permiso viene de mas de un grupo.</b> La union de dos
 *       grupos vigentes no tiene <b>un</b> grupo que nombrar, y elegir el primero por orden de id
 *       daria un dato plausible y equivocado. Nulo ahi significa «no hay uno solo», y con {@code
 *       origen = GRUPO} eso se lee sin ambiguedad.
 * </ol>
 *
 * @param codigoDeAcceso el id de la opcion en el catalogo (NEG-03)
 * @param privilegios los efectivos; vacio solo cuando una excepcion los niega todos
 * @param origen quien mando, la excepcion del usuario o sus grupos
 * @param grupoId el grupo, cuando el origen es {@code GRUPO} y hay uno solo
 */
public record PermisoEfectivo(
        String codigoDeAcceso,
        Set<Privilegio> privilegios,
        OrigenDelPermiso origen,
        @Nullable Long grupoId) {

    public PermisoEfectivo {
        Objects.requireNonNull(codigoDeAcceso, "Un permiso efectivo es sobre un acceso");
        Objects.requireNonNull(privilegios, "Sin privilegios es un conjunto vacio, no null");
        Objects.requireNonNull(origen, "Un permiso efectivo dice de donde viene");
        privilegios = Set.copyOf(privilegios);
        if (origen == OrigenDelPermiso.EXCEPCION && grupoId != null) {
            throw new IllegalArgumentException(
                    "Una excepcion de usuario no viene de ningun grupo: " + codigoDeAcceso);
        }
    }

    /** De donde sale el permiso efectivo. */
    public enum OrigenDelPermiso {
        /** La fila de {@code permiso} del propio usuario, que sustituye a la del grupo. */
        EXCEPCION,
        /** La union de los grupos vigentes a los que pertenece. */
        GRUPO
    }
}
