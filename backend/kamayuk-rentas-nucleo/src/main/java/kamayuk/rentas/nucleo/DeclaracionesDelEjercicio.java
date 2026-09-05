package kamayuk.rentas.nucleo;

import java.util.Collection;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Qué se declaró en un ejercicio, publicado para otros contextos acotados (ARQ-01 §4, #49).
 *
 * <p>Es la <b>primera</b> API pública de este módulo: vive en el paquete raíz, no en {@code
 * .aplicacion} ni en {@code .dominio}, porque Spring Modulith trata como interno todo lo que está
 * en un subpaquete (mismo patrón que {@code catastro.LectorDeFichas} y {@code
 * parametros.LectorDeParametros}).
 *
 * <h2>Para qué existe</h2>
 *
 * <p>Para la detección de omisos y subvaluadores de {@code fiscalizacion} (RF-055). La pregunta
 * —«¿este predio tiene declaración jurada de este ejercicio?»— solo la puede contestar {@code
 * rentas}, que es donde vive la declaración; sin este puerto la única salida habría sido que {@code
 * fiscalizacion} consultara {@code declaracion_jurada} directamente, cruzando el límite del
 * contexto.
 *
 * <p>Devuelve {@link DeclaracionDelEjercicio} y no {@code DeclaracionJurada}: ese tipo vive en
 * {@code .dominio} y lleva el número, el tipo de formulario, la ficha catastral que la sustenta y
 * la cadena de rectificatorias. Quien detecta omisos necesita tres cosas —si declaró, cuándo, y si
 * fue dentro del plazo—, y traer el resto obligaría a este contexto a exponer su modelo interno.
 *
 * <h2>Por lote, no una por una</h2>
 *
 * <p>La detección recorre páginas del padrón. Preguntar predio a predio sería una consulta por
 * fila; el método recibe la página entera de identificadores y devuelve un mapa.
 */
public interface DeclaracionesDelEjercicio {

    /**
     * La declaración vigente de cada uno de esos predios en ese ejercicio, si la hay.
     *
     * <p>Los predios sin declaración <b>no aparecen en el mapa</b>. Devolver una entrada con valor
     * nulo obligaría a quien consulta a distinguir «no declaró» de «no pregunté», que es
     * exactamente la confusión que produce marcar omisos de más.
     *
     * <p>Vigente significa la que no está sustituida ni anulada: una declaración rectificada por
     * otra no es lo que el contribuyente declara hoy, y compararla contra lo hallado acusaría de
     * subvaluación a quien ya corrigió.
     */
    Map<Long, DeclaracionDelEjercicio> dePredios(Collection<Long> predioIds, Ejercicio ejercicio);
}
