package kamayuk.rentas.seguridad.dominio;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import org.jspecify.annotations.Nullable;

/**
 * Que privilegios tiene un grupo o un usuario sobre un acceso.
 *
 * <p><b>Un grupo o un usuario, nunca los dos.</b> Es la restriccion {@code permiso_sujeto_ck} del
 * esquema, y aqui se repite a proposito: un permiso con los dos rellenos no tiene una lectura
 * evidente —¿se suman?, ¿gana el mas restrictivo?—, y la ambiguedad en una tabla de autorizacion se
 * resuelve tarde y a favor de quien no debia entrar.
 *
 * @param id nulo mientras el permiso no se ha guardado
 * @param accesoId opcion de menu o politica sobre la que se otorga
 * @param grupoId a quien, si es un grupo
 * @param usuarioId a quien, si es un usuario
 * @param privilegios los que se otorgan; los no incluidos quedan en falso
 */
public record Permiso(
        @Nullable Long id,
        long accesoId,
        @Nullable Long grupoId,
        @Nullable Long usuarioId,
        Set<Privilegio> privilegios) {

    public Permiso {
        Objects.requireNonNull(
                privilegios, "Un permiso sin privilegios es una lista vacia, no null");
        privilegios = Set.copyOf(privilegios);
        if ((grupoId == null) == (usuarioId == null)) {
            throw new IllegalArgumentException(
                    "Un permiso se otorga a un grupo o a un usuario, nunca a los dos ni a ninguno");
        }
        if (accesoId <= 0) {
            throw new IllegalArgumentException("El permiso necesita su acceso: " + accesoId);
        }
    }

    public static Permiso paraGrupo(long accesoId, long grupoId, Privilegio... privilegios) {
        return new Permiso(null, accesoId, grupoId, null, conjunto(privilegios));
    }

    public static Permiso paraUsuario(long accesoId, long usuarioId, Privilegio... privilegios) {
        return new Permiso(null, accesoId, null, usuarioId, conjunto(privilegios));
    }

    public boolean tiene(Privilegio privilegio) {
        return privilegios.contains(privilegio);
    }

    public boolean esNuevo() {
        return id == null;
    }

    private static Set<Privilegio> conjunto(Privilegio... privilegios) {
        return privilegios.length == 0
                ? EnumSet.noneOf(Privilegio.class)
                : EnumSet.copyOf(java.util.List.of(privilegios));
    }
}
