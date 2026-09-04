package kamayuk.rentas.parametros;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El calculo de un contribuyente: el detalle de cada predio y lo que resulta de agregarlos.
 *
 * <p>Los dos niveles se conservan porque la determinacion muestra los dos. M02 lo confirma: la
 * pantalla del SRTM tiene una grilla «detalle de los predios» <b>dentro</b> de una unica
 * determinacion, con el aporte de cada uno a la base. Guardar solo el total impediria explicarle a
 * un contribuyente de donde sale su base.
 */
public record ResultadoDelContribuyente(
        List<ResultadoDelCalculo> porPartida,
        EstadoDelCalculo agregado,
        Ejercicio ejercicio,
        List<IdentificadorDeRegla> agregacionesAplicadas) {

    public ResultadoDelContribuyente {
        Objects.requireNonNull(porPartida, "El detalle por predio es parte del resultado");
        Objects.requireNonNull(agregado, "Todo resultado agregado tiene su estado");
        Objects.requireNonNull(ejercicio, "Todo resultado dice con que ejercicio se calculo");
        Objects.requireNonNull(agregacionesAplicadas, "La lista de reglas es vacia, no nula");
        porPartida = List.copyOf(porPartida);
        agregacionesAplicadas = List.copyOf(agregacionesAplicadas);
    }

    public Optional<Dinero> valor(Concepto concepto) {
        return agregado.valor(concepto);
    }

    public Dinero exigir(Concepto concepto) {
        return agregado.valor(concepto)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La agregacion no produjo "
                                                + concepto
                                                + ". Los conceptos agregados fueron "
                                                + agregado.conceptos()));
    }

    public int cantidadDePartidas() {
        return porPartida.size();
    }

    /** Todas las reglas que intervinieron: las de cada partida y las de agregacion. */
    public List<String> reglasComoTexto() {
        List<String> todas = new java.util.ArrayList<>();
        for (ResultadoDelCalculo resultado : porPartida) {
            todas.addAll(resultado.reglasComoTexto());
        }
        for (IdentificadorDeRegla regla : agregacionesAplicadas) {
            todas.add(regla.valor());
        }
        return List.copyOf(todas);
    }
}
