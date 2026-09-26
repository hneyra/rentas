package kamayuk.rentas.cuentacorriente.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Que deuda del libro origino un conjunto de documentos (#342): la Specification que responde «que
 * nacio de esta resolucion».
 *
 * <h2>Por que el documento, y no la clave</h2>
 *
 * <p>La clave del libro —contribuyente, tributo, ejercicio, periodo y unidad— dice <b>de que</b> es
 * una deuda, no <b>de donde</b> viene. La deuda ordinaria del vehicular 2025 del vehiculo 7 y la
 * que una fiscalizacion determino de oficio sobre el mismo vehiculo y el mismo ejercicio comparten
 * tributo, ejercicio y unidad; quien preguntaba por esa combinacion —el estado de cuenta de
 * fiscalizacion— se llevaba las dos y las presentaba como fiscalizadas. Lo unico que el libro
 * guarda del origen de un cargo es su {@code documento_origen}: el numero de la RDF que la
 * transferencia escribe en cada cargo que asienta. Es la unica fuente de verdad de «que nacio de la
 * fiscalizacion».
 *
 * <h2>Por que se agrupa por clave de saldo, y no por asiento</h2>
 *
 * <p>Porque la deuda no es la suma de los cargos de un documento: el abono de un recibo, la
 * imputacion de un pago o la reversion de un cargo llevan <b>otro</b> documento y son parte de la
 * misma deuda. Una clave de saldo cuenta entera en cuanto tiene un cargo de origen con alguno de
 * los documentos; asi una deuda cobrada sale en 0,00 —asentada y saldada— y no desaparece (#401).
 *
 * <p>La clave es <b>con periodo</b>: la transferencia asienta la diferencia de oficio sin periodo
 * —el 0 de la proyeccion—, distinta de las cuotas ordinarias del mismo ejercicio. Agrupar sin el
 * periodo, como hace {@link ClaveDeObligacion}, volveria a juntarlas.
 *
 * <p><b>Lo que esta Specification no puede separar</b>: un cargo ordinario asentado en la
 * <b>misma</b> clave de saldo —mismo tributo, ejercicio, unidad y periodo 0— que el de la RDF. Para
 * el libro son una sola obligacion, con los abonos imputados a las dos a la vez, y no hay manera de
 * repartirlos sin inventar una regla de imputacion (D-14). Esa clave cuenta entera.
 *
 * <p>Que cargo cuenta como «de origen» es la definicion de {@link CargosDeUnSoloOrigen}: un {@code
 * CARGO} de insoluto que no reversa a otro asiento. El movimiento de fase, la reversion de un abono
 * y el interes que cristaliza una cobranza no originan deuda aunque lleven el documento.
 *
 * <p>Pura (regla 6): recibe los asientos y los documentos, y no lee nada.
 */
public final class LoOriginadoPor {

    private LoOriginadoPor() {}

    /**
     * Los asientos de cada clave de saldo que alguno de esos documentos origino, en el orden del
     * libro.
     *
     * <p>El documento se compara sin espacios a los lados y sin distinguir mayusculas: el asiento
     * lo guarda tal como se lo dieron, y quien pregunta —{@code ResolucionDeDeterminacion}— lo
     * normaliza a mayusculas. Una diferencia de caja no puede dejar sin cifra una deuda que existe.
     *
     * @param asientos los del contribuyente, de cualquier clave
     * @param documentos los documentos de origen por los que se pregunta
     * @return por clave de saldo, <b>todos</b> sus asientos —tambien los abonos, con su documento—
     *     y los documentos preguntados que la originaron; vacio si ninguno origino nada
     */
    public static Map<ClaveDeSaldo, Originada> clavesDe(
            List<Asiento> asientos, Set<String> documentos) {
        Objects.requireNonNull(asientos, "Sin asientos no hay deuda que mirar");
        Objects.requireNonNull(documentos, "Hay que decir por que documentos se pregunta");
        Set<String> buscados = new LinkedHashSet<>();
        for (String documento : documentos) {
            buscados.add(normalizado(documento));
        }

        Map<ClaveDeSaldo, List<Asiento>> porClave = new LinkedHashMap<>();
        Map<ClaveDeSaldo, Set<String>> origenes = new LinkedHashMap<>();
        for (Asiento asiento : asientos) {
            ClaveDeSaldo clave = ClaveDeSaldo.de(asiento);
            porClave.computeIfAbsent(clave, k -> new ArrayList<>()).add(asiento);
            String documento = normalizado(asiento.documentoOrigen());
            if (CargosDeUnSoloOrigen.esCargoDeOrigen(asiento) && buscados.contains(documento)) {
                origenes.computeIfAbsent(clave, k -> new LinkedHashSet<>()).add(documento);
            }
        }

        Map<ClaveDeSaldo, Originada> originadas = new LinkedHashMap<>();
        for (Map.Entry<ClaveDeSaldo, Set<String>> origen : origenes.entrySet()) {
            ClaveDeSaldo clave = origen.getKey();
            originadas.put(
                    clave,
                    new Originada(
                            Objects.requireNonNull(
                                    porClave.get(clave), "la clave es de un asiento"),
                            origen.getValue()));
        }
        return originadas;
    }

    private static String normalizado(String documento) {
        return Objects.requireNonNull(documento, "Un documento de origen no es nulo")
                .strip()
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Lo que un grupo de documentos origino en una clave de saldo.
     *
     * @param asientos todos los de la clave, con su documento cada uno
     * @param documentos los preguntados que tienen en ella un cargo de origen, normalizados
     */
    public record Originada(List<Asiento> asientos, Set<String> documentos) {

        public Originada {
            asientos = List.copyOf(asientos);
            documentos = Set.copyOf(documentos);
            if (documentos.isEmpty()) {
                throw new IllegalArgumentException(
                        "Una clave originada lo fue por algun documento");
            }
        }
    }
}
