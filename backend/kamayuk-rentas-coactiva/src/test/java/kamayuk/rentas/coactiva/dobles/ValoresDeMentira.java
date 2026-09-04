package kamayuk.rentas.coactiva.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.valores.ValoresEnCoactiva;

/**
 * Lo minimo del modulo de valores que el expediente necesita: que valores tiene el contribuyente,
 * con su situacion, y la aceptacion que coactiva responde.
 *
 * <p>Registra las aceptaciones para poder comprobar que la importacion cierra el ciclo del pase sin
 * levantar el modulo entero.
 */
public final class ValoresDeMentira implements ValoresEnCoactiva {

    private final Map<String, ValorParaCoactiva> porNumero = new LinkedHashMap<>();
    private final List<Long> aceptados = new ArrayList<>();

    public ValoresDeMentira con(ValorParaCoactiva valor) {
        porNumero.put(valor.numero().toUpperCase(Locale.ROOT), valor);
        return this;
    }

    @Override
    public List<ValorParaCoactiva> delContribuyente(long contribuyenteId, LocalDate aLaFecha) {
        return porNumero.values().stream()
                .filter(valor -> valor.contribuyenteId() == contribuyenteId)
                .toList();
    }

    @Override
    public Optional<ValorParaCoactiva> porNumero(String numero, LocalDate aLaFecha) {
        return Optional.ofNullable(porNumero.get(numero.strip().toUpperCase(Locale.ROOT)));
    }

    @Override
    public void aceptarEnCoactiva(long valorId, LocalDate fecha, Observacion observacion) {
        aceptados.add(valorId);
    }

    /** Los valores que coactiva acepto (ACO). */
    public List<Long> aceptados() {
        return List.copyOf(aceptados);
    }
}
