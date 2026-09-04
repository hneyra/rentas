package kamayuk.rentas.auditoria;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * De donde viene la operacion: quien la hace, desde que equipo y desde que direccion.
 *
 * <p>Son las tres columnas que el manual enumera —«el ID del usuario, el Nombre de la Maquina (PC)
 * y el IP de la PC desde la cual ocurre la modificacion»—, trasladadas a un sistema web: el usuario
 * sale del token, y el equipo y la IP, de la peticion.
 *
 * <p>El equipo y la IP admiten nulo a proposito. La columna tambien: hay escrituras legitimas sin
 * peticion HTTP detras —el perfil {@code batch}, una migracion— y forzar un valor inventado seria
 * peor que dejarlo vacio, porque haria indistinguible lo que vino de una maquina de lo que no vino
 * de ninguna.
 *
 * @param usuario identificador del usuario; el ancho es el de {@code auditoria.usuario_id}
 * @param equipo nombre del equipo, si se conoce
 * @param ip direccion de origen, si se conoce; va a una columna {@code inet}
 */
public record Origen(String usuario, @Nullable String equipo, @Nullable String ip) {

    private static final int USUARIO_MAXIMO = 60;
    private static final int EQUIPO_MAXIMO = 80;

    public Origen {
        Objects.requireNonNull(
                usuario, "Toda escritura la hace alguien: el usuario es obligatorio");
        usuario = usuario.strip();
        if (usuario.isEmpty() || usuario.length() > USUARIO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El usuario va de 1 a " + USUARIO_MAXIMO + " caracteres: '" + usuario + "'");
        }
        if (equipo != null) {
            equipo = equipo.strip().toUpperCase(Locale.ROOT);
            if (equipo.isEmpty()) {
                equipo = null;
            } else if (equipo.length() > EQUIPO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El nombre de equipo excede " + EQUIPO_MAXIMO + " caracteres");
            }
        }
        if (ip != null) {
            ip = ip.strip();
            if (ip.isEmpty()) {
                ip = null;
            }
        }
    }

    /** Origen de un proceso sin peticion detras: el perfil batch, una carga programada. */
    public static Origen deProceso(String usuario) {
        return new Origen(usuario, null, null);
    }
}
