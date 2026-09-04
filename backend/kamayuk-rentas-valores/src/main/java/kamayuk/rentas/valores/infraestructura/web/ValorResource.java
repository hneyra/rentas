package kamayuk.rentas.valores.infraestructura.web;

import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.valores.dominio.Valor;
import org.jspecify.annotations.Nullable;

/**
 * Como sale un {@link Valor} por HTTP.
 *
 * <p>Lleva el nombre del contribuyente, no solo su identificador: la grilla de {@code
 * valores_busqueda} lo muestra, y pedirselo a {@code contribuyentes} pantalla por pantalla en vez
 * de aqui multiplicaria las consultas (RF-092).
 */
public record ValorResource(
        long id,
        String tipo,
        String numero,
        int ejercicio,
        String codContribuyente,
        String nombreContribuyente,
        String baseLegal,
        String estado,
        String proyectadoA,
        String total,
        String fechaEmision,
        String observacion) {

    public static ValorResource de(Valor valor, @Nullable ResumenDeContribuyente contribuyente) {
        return new ValorResource(
                requerido(valor.id()),
                valor.tipo().codigo(),
                valor.numero(),
                valor.ejercicio().valor(),
                contribuyente == null ? "" : contribuyente.codigo(),
                contribuyente == null ? "" : contribuyente.nombre(),
                valor.baseLegal(),
                valor.estado().name(),
                valor.proyectadoA().toString(),
                valor.total().valor().toPlainString(),
                valor.fechaEmision().toString(),
                valor.observacion().texto());
    }

    private static long requerido(@Nullable Long id) {
        return java.util.Objects.requireNonNull(id, "Un valor que sale por HTTP ya esta guardado");
    }
}
