package kamayuk.rentas.nucleo.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;

/**
 * Los valores referenciales, siempre <b>acotados a un conjunto</b>.
 *
 * <p>Ningun metodo admite solo el ejercicio, y es deliberado: resolver por ejercicio devuelve la
 * version que rige hoy, no la que se uso al determinar. Con dos versiones selladas del mismo
 * ejercicio —un valor corregido a mitad de año— recalcular una determinacion daria otra cifra sin
 * ningun error de por medio. Quien traduce el ejercicio a un conjunto es {@code
 * LectorDeParametros}, y una sola vez.
 */
public interface ValorReferencialRepository {

    Optional<ValorReferencial> buscar(
            IdentificadorDeConjunto conjunto, String marca, String modelo, int anioFabricacion);

    /** El catalogo de marcas y modelos del conjunto, ordenado y sin repetir. */
    List<MarcaYModelo> catalogo(IdentificadorDeConjunto conjunto);
}
