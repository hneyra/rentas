package kamayuk.rentas.seguridad.dominio;

import java.util.Objects;

/**
 * Un modulo del menu: la agrupacion de opciones que ve el usuario al entrar.
 *
 * <p>Son los doce del manual, y se siembran del catalogo (NEG-03). No se crean a mano: si alguien
 * pudiera, habria modulos que no corresponden a ninguna pantalla.
 *
 * @param id nulo mientras no se ha guardado
 * @param codigo generado del nombre del modulo en el catalogo
 * @param orden posicion en el menu
 * @param activo un modulo retirado se desactiva; no se borra (RNF-051)
 */
public record Modulo(Long id, String codigo, String nombre, int orden, boolean activo) {

    private static final int CODIGO_MAXIMO = 30;
    private static final int NOMBRE_MAXIMO = 120;

    public Modulo {
        Objects.requireNonNull(codigo, "El modulo necesita su codigo");
        Objects.requireNonNull(nombre, "El modulo necesita su nombre");
        codigo = codigo.strip();
        nombre = nombre.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de modulo va de 1 a " + CODIGO_MAXIMO + ": '" + codigo + "'");
        }
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de modulo va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
    }
}
