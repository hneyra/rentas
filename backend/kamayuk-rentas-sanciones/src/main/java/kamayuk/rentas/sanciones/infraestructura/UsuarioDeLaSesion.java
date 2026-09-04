package kamayuk.rentas.sanciones.infraestructura;

import kamayuk.rentas.auditoria.OrigenContext;

/**
 * Quién está operando, para la columna {@code usuario_registro}.
 *
 * <p>Sale del {@code Origen} que el filtro de la petición fijó, nunca de un argumento: si viajara
 * en la firma, una petición podría decir que la dictó otro gerente, y la traza del expediente
 * dejaría de significar nada.
 */
final class UsuarioDeLaSesion {

    private UsuarioDeLaSesion() {}

    static String actual() {
        return OrigenContext.actual().usuario();
    }
}
