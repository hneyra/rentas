package kamayuk.rentas.seguridad.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Vigencia;
import org.jspecify.annotations.Nullable;

/**
 * Un grupo de usuarios, que es donde viven los permisos en la practica.
 *
 * <p>Los permisos se otorgan al grupo y los usuarios se afilian: dar de alta a alguien es meterlo
 * en «Mesa de Partes», no repetirle veinte permisos. Es como se administra un sistema municipal, y
 * es lo que hace manejable el modelo de siete privilegios sobre 134 opciones.
 *
 * @param id nulo mientras no se ha guardado
 * @param habilitado inhabilitar retira el acceso de todos sus miembros, sin borrar ninguna relacion
 */
public record Grupo(
        @Nullable Long id,
        String nombre,
        @Nullable String descripcion,
        boolean habilitado,
        Vigencia vigencia) {

    private static final int NOMBRE_MAXIMO = 80;
    private static final int DESCRIPCION_MAXIMO = 300;

    public Grupo {
        Objects.requireNonNull(nombre, "El grupo necesita su nombre");
        Objects.requireNonNull(vigencia, "El grupo necesita su vigencia; use Vigencia.SIEMPRE");
        nombre = nombre.strip();
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de grupo va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
        if (descripcion != null && descripcion.length() > DESCRIPCION_MAXIMO) {
            throw new IllegalArgumentException(
                    "La descripcion de grupo excede " + DESCRIPCION_MAXIMO + " caracteres");
        }
    }

    public static Grupo nuevo(String nombre, @Nullable String descripcion) {
        return new Grupo(null, nombre, descripcion, true, Vigencia.SIEMPRE);
    }

    /** Habilitado <b>y</b> dentro de su vigencia: las dos cosas, no una. */
    public boolean autorizaEn(LocalDate fecha) {
        return habilitado && vigencia.vigenteEn(fecha);
    }

    public Grupo inhabilitado() {
        return new Grupo(id, nombre, descripcion, false, vigencia);
    }

    public Grupo habilitadoDeNuevo() {
        return new Grupo(id, nombre, descripcion, true, vigencia);
    }

    public Grupo con(Vigencia otra) {
        return new Grupo(id, nombre, descripcion, habilitado, otra);
    }
}
