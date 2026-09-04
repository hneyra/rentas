package kamayuk.rentas.catastro.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los dos filtros con que se busca en ventanilla, sin el marco: sector y manzana, por codigo.
 *
 * <h2>Por que existe, y por que no es «FiltroDelPlano con el marco opcional»</h2>
 *
 * <p>Porque hay <b>dos</b> lecturas del plano y solo una tiene marco. {@link FiltroDelPlano} acota
 * los lotes que se dibujan y por eso su marco es obligatorio —sin el la consulta seria el padron
 * entero—; el marco de lo levantado (#612) es justamente la lectura que <b>no puede</b> tenerlo,
 * porque existe para calcularlo. Aflojar aquel invariante para que sirviera a las dos abriria el
 * camino a pedir el plano sin acotar, que es lo que ADR-0022 §2 impide.
 *
 * <p>Y sobre todo, existe para que la <b>acotacion sea la misma</b>. Las dos lecturas tienen que
 * responder sobre el mismo conjunto de predios: si el marco se calculara sobre un «sector» que se
 * compara de otra manera que en el plano, el visor abriria sobre un encuadre que no contiene lo que
 * despues dibuja, y sobre un plano sin base cartografica eso <b>no se ve</b>. Un solo tipo, una
 * sola normalizacion, y un solo sitio donde el SQL deriva el {@code WHERE}.
 *
 * @param codigoDeSector filtro «Sector» de la pantalla, por codigo; {@code null} es «todos»
 * @param codigoDeManzana filtro «Manzana» de la pantalla, por codigo; {@code null} es «todas»
 */
public record AcotacionDelPlano(@Nullable String codigoDeSector, @Nullable String codigoDeManzana) {

    public AcotacionDelPlano {
        codigoDeSector = limpio(codigoDeSector);
        codigoDeManzana = limpio(codigoDeManzana);
    }

    /**
     * Un codigo en blanco es un filtro que no se escribio.
     *
     * <p>Se normaliza aqui y no en cada capa porque la cadena vacia que manda un formulario sin
     * rellenar y el {@code null} de un parametro ausente significan lo mismo, y comparar la vacia
     * contra {@code s.codigo} produciria cero filas: «este sector no tiene lotes» dicho de un
     * filtro que nadie puso.
     */
    private static @Nullable String limpio(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
