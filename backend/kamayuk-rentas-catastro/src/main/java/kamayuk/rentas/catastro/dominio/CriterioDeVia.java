package kamayuk.rentas.catastro.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Lo que acota una busqueda del catalogo vial (#565). Todos los criterios son opcionales y se
 * combinan con Y; sin ninguno, el catalogo entero.
 *
 * <p>Existe porque hasta #565 {@code GET /catastro/vias} recibia <b>solo la paginacion</b>: para
 * elegir una via habia que traerse el catalogo entero —1 110 en Catacaos, tres peticiones— y buscar
 * en el cliente.
 *
 * @param codigo por prefijo del codigo de via, tal como se teclea
 * @param nombre por prefijo del nombre, sin distinguir mayusculas ni tildes
 * @param tipo por igualdad de tipo de via
 * @param activa {@code true} solo las vigentes, {@code false} solo las dadas de baja, {@code null}
 *     las dos
 */
public record CriterioDeVia(
        @Nullable String codigo,
        @Nullable String nombre,
        @Nullable TipoVia tipo,
        @Nullable Boolean activa) {

    public CriterioDeVia {
        codigo = limpiar(codigo);
        nombre = limpiar(nombre);
    }

    /** Sin acotar: el catalogo entero, que es lo que esta operacion hacia siempre. */
    public static CriterioDeVia todas() {
        return new CriterioDeVia(null, null, null, null);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
