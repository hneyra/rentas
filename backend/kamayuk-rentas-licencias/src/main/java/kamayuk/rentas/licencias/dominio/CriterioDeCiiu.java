package kamayuk.rentas.licencias.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Por que se busca en el catalogo de giros (#44, RF-112).
 *
 * <p>Los tres filtros de la pantalla {@code ciiu}: codigo, descripcion y seccion. Los dos primeros
 * son busquedas por <b>prefijo</b>, y por eso el repositorio las escribe como rango con {@code
 * ~&gt;=~} / {@code ~&lt;~} y no con {@code LIKE}: bajo RLS un {@code LIKE 'prefijo%'} no llega
 * nunca al indice (DAT-01 §0, hallazgo 3).
 *
 * @param codigo prefijo del codigo CIIU
 * @param descripcion prefijo de la descripcion
 * @param seccion la letra de seccion, exacta
 */
public record CriterioDeCiiu(
        @Nullable String codigo, @Nullable String descripcion, @Nullable String seccion) {

    public CriterioDeCiiu {
        codigo = limpiar(codigo);
        descripcion = limpiar(descripcion);
        seccion = limpiar(seccion);
    }

    public static CriterioDeCiiu ninguno() {
        return new CriterioDeCiiu(null, null, null);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
