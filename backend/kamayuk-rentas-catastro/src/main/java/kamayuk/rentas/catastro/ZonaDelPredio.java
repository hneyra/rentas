package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * La zona urbanistica de un predio a una fecha, tal como {@code catastro} la publica.
 *
 * <p><b>{@code aLaFecha} va dentro y no es redundante con el argumento que se paso</b> (regla 9):
 * lo que se afirma no es «este predio es RDM» sino «este predio era RDM el 30 de junio de 2026».
 * Una respuesta guardada, pegada en un expediente o cacheada sin su fecha es una respuesta que
 * dentro de un mes es otra sin que nadie pueda decir cual se dio.
 *
 * <p><b>Y lleva la ordenanza que la aprobo.</b> Sin ella, quien niegue un giro por la zona no puede
 * citar la norma que lo sustenta, y una denegacion sin norma no se puede notificar.
 *
 * @param vigenciaHasta hasta cuando rige el plan; {@code null} si no tiene fin declarado
 * @param parametros los urbanisticos de la zona —altura, area libre, lote minimo—. Cada uno lleva
 *     su valor como texto y su unidad al lado, que es como {@code catastro} los publica: no son
 *     cifras que este sistema opere, son lo que la ordenanza dice
 */
public record ZonaDelPredio(
        LocalDate aLaFecha,
        String codigo,
        String nombre,
        String plan,
        String ordenanza,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        List<ParametroUrbanistico> parametros) {}
