package kamayuk.rentas.parametros;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que se sabe del calculo en un momento dado: cada concepto con su importe.
 *
 * <p>Inmutable. Agregar un concepto devuelve otro estado; asi el motor no puede pisar un valor ya
 * calculado, y la traza del calculo es la sucesion de estados y no un objeto que fue cambiando.
 */
public final class EstadoDelCalculo {

    private final Map<Concepto, Dinero> valores;

    private EstadoDelCalculo(Map<Concepto, Dinero> valores) {
        this.valores = valores;
    }

    public static EstadoDelCalculo vacio() {
        return new EstadoDelCalculo(Map.of());
    }

    /** Los datos declarados con los que arranca el calculo: area, antiguedad, porcentajes. */
    public static EstadoDelCalculo con(Concepto concepto, Dinero valor) {
        return vacio().mas(concepto, valor);
    }

    public EstadoDelCalculo mas(Concepto concepto, Dinero valor) {
        Objects.requireNonNull(concepto, "Todo valor del calculo va bajo su concepto");
        Objects.requireNonNull(valor, "Un concepto ausente se omite, no se guarda como nulo");
        if (valores.containsKey(concepto)) {
            throw new ConceptoYaCalculado(concepto);
        }
        Map<Concepto, Dinero> ampliado = new LinkedHashMap<>(valores);
        ampliado.put(concepto, valor);
        return new EstadoDelCalculo(Map.copyOf(ampliado));
    }

    public Optional<Dinero> valor(Concepto concepto) {
        return Optional.ofNullable(valores.get(concepto));
    }

    public boolean conoce(Concepto concepto) {
        return valores.containsKey(concepto);
    }

    public Set<Concepto> conceptos() {
        return valores.keySet();
    }

    public boolean estaVacio() {
        return valores.isEmpty();
    }

    /**
     * Dos reglas que producen el mismo concepto, o una regla que recalcula un dato declarado. En
     * ambos casos el resultado dependeria del orden y dejaria de ser reproducible.
     */
    public static final class ConceptoYaCalculado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ConceptoYaCalculado(Concepto concepto) {
            super(
                    "El concepto "
                            + concepto
                            + " ya tiene valor. Sobrescribirlo haria que el importe dependiera del"
                            + " orden en que se aplicaron las reglas, que es exactamente lo que el"
                            + " grafo evita");
        }
    }
}
