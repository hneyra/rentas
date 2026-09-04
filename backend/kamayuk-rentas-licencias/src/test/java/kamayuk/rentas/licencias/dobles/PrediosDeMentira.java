package kamayuk.rentas.licencias.dobles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.dominio.Porcentaje;

/**
 * El puerto {@link PrediosDelContribuyente} de {@code catastro}, con los predios que la prueba le
 * siembra (#54).
 *
 * <p>Es el <b>puerto publico</b> y no el repositorio de predios, y eso es el punto: {@code
 * licencias} no puede unir {@code certificado} con {@code predio} en un {@code JOIN} sin cruzar el
 * limite que Spring Modulith vigila, asi que el predio de un certificado se le <b>pide</b> a
 * catastro.
 *
 * <p>Que la lista sea <b>por titular</b> tampoco es casualidad: un certificado de numeracion se le
 * entrega al titular del predio, y este doble puede devolver vacio para el que no lo es.
 */
public final class PrediosDeMentira implements PrediosDelContribuyente {

    private final Map<Long, List<PredioDelContribuyente>> porTitular = new LinkedHashMap<>();

    public PrediosDeMentira con(
            long contribuyenteId, long predioId, String codigo, String direccion) {
        porTitular
                .computeIfAbsent(contribuyenteId, clave -> new ArrayList<>())
                .add(
                        new PredioDelContribuyente(
                                predioId,
                                codigo,
                                "URBANO",
                                direccion,
                                new Porcentaje(new BigDecimal("100.0000"))));
        return this;
    }

    @Override
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        return porTitular.getOrDefault(contribuyenteId, List.of());
    }
}
