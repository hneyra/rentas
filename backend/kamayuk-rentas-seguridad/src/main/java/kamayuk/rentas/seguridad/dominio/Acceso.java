package kamayuk.rentas.seguridad.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Algo sobre lo que se otorgan privilegios: una opcion de menu o una politica.
 *
 * <p>El {@code codigo} de una opcion de menu es el <b>id de la pantalla en el catalogo</b>
 * (NEG-03), y es lo que un controlador declara en {@code @RequiereAcceso}. Que sea el mismo
 * identificador en los tres sitios —catalogo, tabla y anotacion— es lo que permite que sembrar los
 * accesos sea copiar el catalogo, y lo que hace cierta la promesa del manual (RF-122).
 *
 * @param id nulo mientras no se ha guardado
 * @param activo un acceso retirado se desactiva; los permisos que cuelgan de el son constancia
 */
public record Acceso(
        @Nullable Long id,
        long moduloId,
        TipoDeAcceso tipo,
        String codigo,
        String nombre,
        boolean activo) {

    private static final int CODIGO_MAXIMO = 60;
    private static final int NOMBRE_MAXIMO = 160;

    public Acceso {
        Objects.requireNonNull(tipo, "El acceso necesita su tipo");
        Objects.requireNonNull(codigo, "El acceso necesita su codigo");
        Objects.requireNonNull(nombre, "El acceso necesita su nombre");
        codigo = codigo.strip();
        nombre = nombre.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de acceso va de 1 a " + CODIGO_MAXIMO + ": '" + codigo + "'");
        }
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de acceso va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
    }
}
