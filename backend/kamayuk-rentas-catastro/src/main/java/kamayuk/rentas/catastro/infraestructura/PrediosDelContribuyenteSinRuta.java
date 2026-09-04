package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import org.springframework.stereotype.Component;

/**
 * Los predios de un contribuyente: sin ruta todavia (P5C). Ver {@link SinRutaTodavia}, que explica
 * por que no devuelve una lista vacia y por que esto son tres clases y no una.
 *
 * <p>Es el puerto que la determinacion predial usa para saber sobre qué predios calcula, y el que
 * lleva el <b>porcentaje de propiedad</b> con el que se pondera la base. Devolver vacio aqui
 * dejaria la base del contribuyente en cero — el defecto que #395 midio al reves, cuando no
 * ponderaba.
 */
@Component
public class PrediosDelContribuyenteSinRuta implements PrediosDelContribuyente {

    @Override
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                "los predios del contribuyente " + contribuyenteId,
                "GET catastro/api/v1/predios?contribuyente={id}");
    }
}
