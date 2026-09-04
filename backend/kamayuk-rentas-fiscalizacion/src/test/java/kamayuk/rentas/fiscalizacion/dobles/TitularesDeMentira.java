package kamayuk.rentas.fiscalizacion.dobles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;

/**
 * Los titulares de un predio, de mentira (#545).
 *
 * <p>Va aparte de {@link PadronDeMentira} porque {@code TitularesDelPredio.de(long, LocalDate)} y
 * {@code LectorDeCaracteristicas.de(long, LocalDate)} tienen la misma firma con distinto tipo de
 * retorno: una sola clase no puede implementar los dos puertos.
 *
 * <p><b>Solo lee</b>, como el resto de los dobles de este paquete: no hay un metodo por el que
 * {@code fiscalizacion} pudiera escribir en catastro.
 */
public final class TitularesDeMentira implements TitularesDelPredio {

    private final Map<Long, List<TitularDelPredio>> porPredio = new LinkedHashMap<>();

    private int lecturasDeUno;

    private int lecturasPorLote;

    /** Un titular mas del predio, con el porcentaje que se le da. */
    public TitularesDeMentira con(long predioId, long contribuyenteId, String porcentaje) {
        porPredio
                .computeIfAbsent(predioId, predio -> new ArrayList<>())
                .add(
                        new TitularDelPredio(
                                contribuyenteId,
                                "COPROPIETARIO",
                                new kamayuk.rentas.dominio.Porcentaje(new BigDecimal(porcentaje))));
        return this;
    }

    /** El caso corriente: un titular unico, al 100 %. */
    public TitularesDeMentira con(long predioId, long contribuyenteId) {
        porPredio
                .computeIfAbsent(predioId, predio -> new ArrayList<>())
                .add(
                        new TitularDelPredio(
                                contribuyenteId,
                                "PROPIETARIO_UNICO",
                                new kamayuk.rentas.dominio.Porcentaje(new BigDecimal("100.00"))));
        return this;
    }

    /**
     * Cuantas veces se pregunto por UN predio. Es lo que #545 exige que no crezca con las filas.
     */
    public int lecturasDeUno() {
        return lecturasDeUno;
    }

    /** Cuantas veces se pregunto por un lote: una por pagina, sea cual sea su tamano. */
    public int lecturasPorLote() {
        return lecturasPorLote;
    }

    @Override
    public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
        lecturasDeUno++;
        return List.copyOf(porPredio.getOrDefault(predioId, List.of()));
    }

    /** Aqui esta en el padron exactamente el predio del que se declaro algun titular (#680). */
    @Override
    public boolean estaEnElPadron(long predioId) {
        return porPredio.containsKey(predioId);
    }

    @Override
    public Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha) {
        lecturasPorLote++;
        Map<Long, List<TitularDelPredio>> encontrados = new LinkedHashMap<>();
        for (Long predioId : predioIds) {
            List<TitularDelPredio> cuotas = porPredio.get(predioId);
            if (cuotas != null && !cuotas.isEmpty()) {
                encontrados.put(predioId, List.copyOf(cuotas));
            }
        }
        return Map.copyOf(encontrados);
    }
}
