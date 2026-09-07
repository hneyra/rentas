package kamayuk.rentas.nucleo.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada;

/**
 * <b>DOBLE DE PRUEBA</b>: lo que {@code catastro} sello, en memoria (#38).
 *
 * <h2>Nace VACIO, y eso es lo que hay hoy en casi todo el padron</h2>
 *
 * <p>Un predio del que nadie sembro nada no tiene valuacion, y entonces la determinacion cae al
 * autovaluo declarado — que es exactamente el comportamiento que todas las pruebas anteriores a #38
 * miden. Sembrar por omision una valuacion las haria medir otra cosa sin que nadie lo pidiera.
 *
 * <p>Y siembra las dos formas del hecho: {@link #conCifra} y {@link #sinCifra}. La segunda hace
 * falta tanto como la primera, porque hoy es el caso normal: {@code catastro} valoriza 4 de los 23
 * predios de la demostracion y los otros 19 traen el motivo de RT-004. Una prueba que solo sembrara
 * cifras no mediria nunca el camino que de verdad recorre el padron.
 */
public final class ValuacionesSelladasEnMemoria implements ValuacionRecibida {

    private final Map<String, ValuacionSellada> selladas = new LinkedHashMap<>();
    private final List<String> loQuePreguntaron = new ArrayList<>();
    private CierreDeCorrida cierre;

    /** Ese predio se valorizo, y esta es su cifra. */
    public ValuacionesSelladasEnMemoria conCifra(
            Ejercicio ejercicio, long predioId, String valorDelPredio, long conjuntoId) {
        selladas.put(
                clave(ejercicio, predioId),
                new ValuacionSellada(
                        predioId,
                        LocalDate.of(ejercicio.valor() - 1, 12, 31),
                        Dinero.de(valorDelPredio),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.de(valorDelPredio),
                        null,
                        null,
                        conjuntoId,
                        "v1",
                        "h".repeat(64)));
        return this;
    }

    /** Ese predio NO se pudo valorizar, y este es el motivo con que `catastro` lo dijo. */
    public ValuacionesSelladasEnMemoria sinCifra(
            Ejercicio ejercicio, long predioId, String motivo, String llaveQueFalta) {
        selladas.put(
                clave(ejercicio, predioId),
                new ValuacionSellada(
                        predioId,
                        LocalDate.of(ejercicio.valor() - 1, 12, 31),
                        null,
                        null,
                        null,
                        null,
                        motivo,
                        llaveQueFalta,
                        7L,
                        "v1",
                        "h".repeat(64)));
        return this;
    }

    /** La corrida del ejercicio, cerrada y cuadrando, para que el candado deje pasar. */
    public ValuacionesSelladasEnMemoria conLaCorridaCerrada(Ejercicio ejercicio, long conjuntoId) {
        this.cierre =
                new CierreDeCorrida(
                        1L,
                        conjuntoId,
                        LocalDate.of(ejercicio.valor() - 1, 12, 31),
                        "v1",
                        (int) contarDe(ejercicio),
                        "agregada");
        return this;
    }

    /**
     * Las lecturas que se le hicieron.
     *
     * <p>Es lo que permite afirmar que la corrida <b>lee</b> las valuaciones, y no solo que el
     * resultado coincide: un resultado puede coincidir por casualidad —el declarado y el sellado
     * pueden ser la misma cifra— y esta lista no.
     */
    public List<String> loQuePreguntaron() {
        return List.copyOf(loQuePreguntaron);
    }

    // ------------------------------------------------------------------

    @Override
    public Optional<CierreDeCorrida> cierreDe(Ejercicio ejercicio) {
        return Optional.ofNullable(cierre);
    }

    @Override
    public long valuacionesRecibidasDe(Ejercicio ejercicio) {
        return contarDe(ejercicio);
    }

    @Override
    public String huellaDeLoRecibido(Ejercicio ejercicio) {
        return cierre == null ? "" : cierre.huella();
    }

    @Override
    public Optional<ValuacionSellada> delPredio(Ejercicio ejercicio, long predioId) {
        loQuePreguntaron.add(clave(ejercicio, predioId));
        return Optional.ofNullable(selladas.get(clave(ejercicio, predioId)));
    }

    @Override
    public Map<Long, ValuacionSellada> deLosPredios(Ejercicio ejercicio, List<Long> predioIds) {
        Map<Long, ValuacionSellada> encontradas = new LinkedHashMap<>();
        for (long predioId : predioIds) {
            loQuePreguntaron.add(clave(ejercicio, predioId));
            ValuacionSellada sellada = selladas.get(clave(ejercicio, predioId));
            if (sellada != null) {
                encontradas.put(predioId, sellada);
            }
        }
        return encontradas;
    }

    private long contarDe(Ejercicio ejercicio) {
        return selladas.keySet().stream()
                .filter(clave -> clave.startsWith(ejercicio.valor() + ":"))
                .count();
    }

    private static String clave(Ejercicio ejercicio, long predioId) {
        return ejercicio.valor() + ":" + predioId;
    }
}
