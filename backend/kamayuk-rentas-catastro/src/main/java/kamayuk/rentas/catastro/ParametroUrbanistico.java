package kamayuk.rentas.catastro;

import org.jspecify.annotations.Nullable;

/**
 * Un parametro urbanistico de una zona: altura maxima, area libre minima, lote minimo.
 *
 * <p><b>El valor es texto y no un numero</b>, y lo es en los dos lados de la frontera: una
 * ordenanza fija «4 pisos», «30 %» y «120 m2», pero tambien «segun perfil de la via» y «no aplica».
 * Convertirlo aqui a un decimal obligaria a inventar una cifra para lo que no la tiene, y lo que se
 * inventa es justamente lo que despues alguien opera.
 *
 * @param unidad {@code PISOS}, {@code M2}, {@code %}; {@code null} cuando el parametro no la tiene
 */
public record ParametroUrbanistico(String clave, String valor, @Nullable String unidad) {}
