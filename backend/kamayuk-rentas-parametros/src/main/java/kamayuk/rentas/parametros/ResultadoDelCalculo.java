package kamayuk.rentas.parametros;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El calculo de una partida: todos los conceptos que se produjeron, no solo el ultimo.
 *
 * <p>Devolver un unico importe seria perder la mitad del resultado. Una determinacion muestra su
 * desarrollo —terreno, construccion, obras, autovaluo— y una reclamacion se responde ensenando el
 * paso donde esta la diferencia, no el total.
 *
 * <p>{@code reglasAplicadas} es lo que va a {@code determinacion.reglas_aplicadas}: con que
 * versiones se calculo. Junto al conjunto sellado, es lo que permite reproducir el importe en 2037.
 */
public record ResultadoDelCalculo(
        EstadoDelCalculo estado,
        Ejercicio ejercicio,
        int versionDeParametros,
        List<IdentificadorDeRegla> reglasAplicadas) {

    public ResultadoDelCalculo {
        Objects.requireNonNull(estado, "Todo resultado tiene su estado");
        Objects.requireNonNull(ejercicio, "Todo resultado dice con que ejercicio se calculo");
        Objects.requireNonNull(reglasAplicadas, "La lista de reglas es vacia, no nula");
        reglasAplicadas = List.copyOf(reglasAplicadas);
    }

    public Optional<Dinero> valor(Concepto concepto) {
        return estado.valor(concepto);
    }

    /** El concepto que se pide o un error que lo nombra: nunca un cero silencioso. */
    public Dinero exigir(Concepto concepto) {
        return estado.valor(concepto)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El calculo no produjo "
                                                + concepto
                                                + ". Los conceptos calculados fueron "
                                                + estado.conceptos()));
    }

    public List<String> reglasComoTexto() {
        return reglasAplicadas.stream().map(IdentificadorDeRegla::valor).toList();
    }
}
