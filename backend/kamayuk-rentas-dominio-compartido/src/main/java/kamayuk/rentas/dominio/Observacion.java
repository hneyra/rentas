package kamayuk.rentas.dominio;

import java.util.Objects;

/**
 * El «por que» de una escritura, escrito por quien la hace.
 *
 * <p>Regla 10 y ADR-0008. El manual del sistema original lo dice sin rodeos: se registra «una
 * observacion que debe escribir el usuario, de lo contrario no le permite guardar la modificacion».
 * El <i>que cambio</i> lo reconstruye cualquier sistema; el <i>por que</i> solo lo sabe quien lo
 * cambio, en el momento de cambiarlo.
 *
 * <p><b>Por que es un tipo y no un {@code String}.</b> Un parametro {@code String observacion} se
 * cumple pasando {@code ""}, y se cumple asi el dia que corre prisa. Un tipo que no se puede
 * construir vacio convierte la regla en algo que el compilador y el constructor sostienen: quien
 * quiera saltarsela tiene que escribir cinco caracteres a proposito, y eso ya deja rastro.
 *
 * <p>Los limites son los de la base, para que el rechazo ocurra en el dominio y no en un {@code
 * INSERT} a medio camino: al menos 5 caracteres una vez recortada —la restriccion {@code
 * auditoria_observacion_ck}— y como mucho 500, que es el ancho de las columnas {@code observacion
 * NOT NULL} del esquema.
 */
public record Observacion(String texto) {

    /** {@code CHECK (length(btrim(observacion)) >= 5)} en la tabla de auditoria. */
    private static final int LARGO_MINIMO = 5;

    /** El ancho de {@code observacion varchar(500) NOT NULL} de las tablas de negocio. */
    private static final int LARGO_MAXIMO = 500;

    public Observacion {
        Objects.requireNonNull(texto, "Toda escritura exige una observacion (regla 10, ADR-0008)");
        texto = texto.strip();
        if (texto.length() < LARGO_MINIMO) {
            throw new IllegalArgumentException(
                    "La observacion debe explicar el cambio: al menos "
                            + LARGO_MINIMO
                            + " caracteres, y no espacios en blanco (ADR-0008)");
        }
        if (texto.length() > LARGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "La observacion excede "
                            + LARGO_MAXIMO
                            + " caracteres, que es lo que admite la columna: "
                            + texto.length());
        }
    }

    public static Observacion de(String texto) {
        return new Observacion(texto);
    }

    @Override
    public String toString() {
        return texto;
    }
}
