package kamayuk.rentas.catastro.dominio;

import java.util.List;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;

/**
 * Aranceles, valores unitarios de edificacion y depreciacion, siempre <b>acotados a un conjunto</b>
 * (#17).
 *
 * <p>Ningun metodo admite solo el ejercicio, por el mismo motivo que {@code
 * ValorReferencialRepository} de {@code rentas}: resolver por ejercicio devuelve la version que
 * rige hoy, no la que se uso al determinar. Con dos versiones selladas del mismo ejercicio —un
 * arancel corregido a mitad de ano— recalcular una determinacion daria otra cifra sin ningun error
 * de por medio. Quien traduce el ejercicio a un conjunto es {@code LectorDeParametros}, una sola
 * vez.
 *
 * <p>{@code guardarArancel} inserta sin comprobar el estado del conjunto: esa comprobacion no vive
 * aqui porque no puede vivir aqui de forma confiable —una carga masiva concurrente burlaria una
 * comprobacion de aplicacion—. La vive el disparador {@code
 * valuacion_de_conjunto_sellado_es_inmutable} de {@code V18}, en la base, que es quien la hace
 * imposible de rodear.
 *
 * <h2>Solo el arancel se escribe desde aqui (D-13, ADR-0017)</h2>
 *
 * <p>Los valores unitarios y la depreciacion <b>ya no se guardan por este camino</b>. Desde V55 son
 * catalogos nacionales: {@code sgtm_app} solo los lee, y escribirlos es un acto de {@code
 * rol_carga_parametros} desde el proceso de publicacion. Un {@code guardarValorUnitario} en un
 * repositorio de tenant no podria ni compilar la intencion —¿la fila de que municipalidad?—, y esa
 * es precisamente la pregunta que D-13 contesta con «de ninguna».
 *
 * <p>Las dos lecturas siguen exactamente igual de firmadas —{@link IdentificadorDeConjunto}, nunca
 * un ejercicio— y siguen devolviendo lo que el conjunto sello: lo que cambio es que ahora el
 * conjunto lo <b>compone</b> por {@code conjunto_parametro_detalle} en vez de poseerlo.
 */
public interface ValuacionRepository {

    List<Arancel> arancelesDe(IdentificadorDeConjunto conjunto);

    Arancel guardarArancel(Arancel arancel, IdentificadorDeConjunto conjunto);

    List<ValorUnitarioEdificacion> valoresUnitariosDe(IdentificadorDeConjunto conjunto);

    List<Depreciacion> depreciacionesDe(IdentificadorDeConjunto conjunto);
}
