package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;

/**
 * El lector de parametros de mentira.
 *
 * <p><b>Ningun valor normativo dentro.</b> Los conjuntos que sella esta clase estan <b>vacios</b>:
 * no hay UIT, ni valores unitarios, ni multa. Poner cifras inventadas para «probar el calculo»
 * seria exactamente lo que el corpus de NEG-05 prohibe —comparar contra parametros ficticios—, y
 * ademas innecesario: lo que #49 comprueba es <b>que conjunto</b> se usa, no cuanto vale lo que hay
 * dentro.
 *
 * <p>Por eso {@link #conjuntosPedidos} y {@link #ejerciciosResueltos} llevan la cuenta de las dos
 * lecturas. Que la valorizacion pregunte {@code porConjunto} y no {@code vigenteEn} es el AC 1, y
 * se comprueba mirando estas dos listas.
 */
public final class ParametrosDeMentira implements LectorDeParametros {

    private final Map<Integer, Long> selladoPorEjercicio = new HashMap<>();
    private final Map<Long, Integer> ejercicioDelConjunto = new HashMap<>();
    private final Map<Long, Integer> versionDelConjunto = new HashMap<>();

    /** Los conjuntos que alguien pidio por identificador. */
    public final List<Long> conjuntosPedidos = new ArrayList<>();

    /** Los ejercicios que alguien resolvio «al vigente». */
    public final List<Integer> ejerciciosResueltos = new ArrayList<>();

    /** Sella un conjunto para ese ejercicio, con esa version. El que rige pasa a ser este. */
    public ParametrosDeMentira sellar(int ejercicio, long conjuntoId, int version) {
        selladoPorEjercicio.put(ejercicio, conjuntoId);
        ejercicioDelConjunto.put(conjuntoId, ejercicio);
        versionDelConjunto.put(conjuntoId, version);
        return this;
    }

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        ejerciciosResueltos.add(ejercicio.valor());
        Long conjunto = selladoPorEjercicio.get(ejercicio.valor());
        if (conjunto == null) {
            throw new EjercicioSinSellar(ejercicio);
        }
        return ParametrosSellados.de(ejercicio, versionDelConjunto.get(conjunto)).construir();
    }

    @Override
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        conjuntosPedidos.add(identificador.valor());
        Integer ejercicio = ejercicioDelConjunto.get(identificador.valor());
        if (ejercicio == null) {
            throw new ConjuntoNoSellado(identificador);
        }
        return ParametrosSellados.de(
                        new Ejercicio(ejercicio), versionDelConjunto.get(identificador.valor()))
                .construir();
    }

    @Override
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        ejerciciosResueltos.add(ejercicio.valor());
        Long conjunto = selladoPorEjercicio.get(ejercicio.valor());
        if (conjunto == null) {
            throw new EjercicioSinSellar(ejercicio);
        }
        return IdentificadorDeConjunto.de(conjunto);
    }
}
