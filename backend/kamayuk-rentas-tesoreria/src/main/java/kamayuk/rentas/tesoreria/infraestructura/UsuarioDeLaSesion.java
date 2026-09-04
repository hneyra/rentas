package kamayuk.rentas.tesoreria.infraestructura;

import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;

/**
 * Quien esta operando, para las columnas {@code usuario_registro} y {@code usuario_apertura}.
 *
 * <p>Sale del {@link Origen} que el filtro de la peticion fijo, nunca de un argumento: si viajara
 * en la firma, una peticion podria decir que la hizo otro cajero, y el arqueo del turno dejaria de
 * significar nada.
 */
final class UsuarioDeLaSesion {

    private UsuarioDeLaSesion() {}

    static String actual() {
        return OrigenContext.actual().usuario();
    }
}
