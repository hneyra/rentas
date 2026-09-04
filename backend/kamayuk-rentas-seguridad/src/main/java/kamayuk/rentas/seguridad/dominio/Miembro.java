package kamayuk.rentas.seguridad.dominio;

/**
 * La pertenencia de un usuario a un grupo.
 *
 * <p><b>Sacar a alguien de un grupo es darlo de baja, no borrar la fila</b> (RNF-051): la fila dice
 * que entre tal dia y tal otro esa persona pudo hacer lo que el grupo permite, y eso es exactamente
 * lo que alguien querra saber dentro de dos anios. La aplicacion, ademas, no tiene {@code DELETE}
 * sobre ninguna tabla.
 *
 * @param activo falso cuando se le dio de baja; la fila sigue ahi
 */
public record Miembro(long grupoId, long usuarioId, boolean activo) {

    public Miembro {
        if (grupoId <= 0 || usuarioId <= 0) {
            throw new IllegalArgumentException(
                    "Una pertenencia necesita grupo y usuario: " + grupoId + ", " + usuarioId);
        }
    }

    public static Miembro alta(long grupoId, long usuarioId) {
        return new Miembro(grupoId, usuarioId, true);
    }

    public Miembro dadoDeBaja() {
        return new Miembro(grupoId, usuarioId, false);
    }
}
