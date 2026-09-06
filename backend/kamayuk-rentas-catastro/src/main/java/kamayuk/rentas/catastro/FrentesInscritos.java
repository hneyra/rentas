package kamayuk.rentas.catastro;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Los frentes de un predio, con la constancia de cuando se derivaron.
 *
 * <p><b>La lista vacia NO viaja sola, y ese es el punto de este tipo.</b> «Este predio no da a
 * ninguna calle» y «a este predio no le ha pasado el derivador» son la misma lista vacia y dos
 * problemas distintos: el primero se arregla midiendo en campo y el segundo cargando la
 * cartografia. Hoy no hay ni un poligono en ninguna instalacion, asi que la respuesta que se va a
 * dar siempre al principio es la segunda — y determinar arbitrios sobre cero metros lineales
 * cobraria de menos a todo el padron sin que ninguna cifra pareciera mal (#48).
 *
 * @param derivadoEn cuando corrio el derivador sobre este predio; {@code null} si no ha corrido
 *     nunca. Es texto y no una fecha porque {@code catastro} publica ahi un instante
 * @param frentesDerivados cuantos propuso esa corrida; {@code null} si no ha corrido nunca
 * @param motivoDeLaDerivacion por que no propuso ninguno; {@code null} si propuso alguno o si no
 *     corrio
 */
public record FrentesInscritos(
        long predioId,
        List<FrenteInscrito> frentes,
        @Nullable String derivadoEn,
        @Nullable Integer frentesDerivados,
        @Nullable String motivoDeLaDerivacion) {}
