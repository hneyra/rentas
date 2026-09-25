package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboYaAplicado;

/**
 * Un {@link AplicacionDeRecibos} en memoria, para la capa web (#383).
 *
 * <p>La cuenta —lo ya aplicado mas lo que el acto consume no pasa de lo cobrado— <b>no se escribe
 * aqui otra vez</b>: la decide {@link ReciboYaAplicado#exigirSaldo}, la misma que usa el adaptador
 * JDBC. Lo que este doble no puede demostrar —que dos consumos simultaneos del ultimo saldo choquen
 * en {@code recibo_aplicado_uq}, y que el rechazo deshaga el acto— lo demuestran {@code
 * AplicacionDeRecibosJdbcTest} y las pruebas JDBC de cada acto, contra PostgreSQL.
 *
 * <p>Y no deshace nada: en memoria no hay transaccion, asi que el acto que el rechazo deberia
 * deshacer se queda en su propio doble. Las pruebas de la capa web miden el codigo HTTP, no eso.
 */
public final class AplicacionesEnMemoria implements AplicacionDeRecibos {

    private final List<Aplicada> aplicadas = new ArrayList<>();

    @Override
    public void aplicar(
            String numeroDeRecibo,
            String concepto,
            int unidadesQueConsume,
            int unidadesCobradas,
            Acto acto) {
        int yaAplicadas =
                aplicadas.stream()
                        .filter(a -> a.recibo().equals(numeroDeRecibo))
                        .filter(a -> a.concepto().equals(concepto))
                        .mapToInt(Aplicada::unidades)
                        .sum();
        ReciboYaAplicado.exigirSaldo(
                numeroDeRecibo, concepto, yaAplicadas, unidadesQueConsume, unidadesCobradas);
        aplicadas.add(new Aplicada(numeroDeRecibo, concepto, unidadesQueConsume, acto));
    }

    /** Lo que se aplico, en orden: para que una prueba pueda decir que acto gasto que recibo. */
    public List<Aplicada> aplicadas() {
        return List.copyOf(aplicadas);
    }

    /** Una aplicacion anotada. */
    public record Aplicada(String recibo, String concepto, int unidades, Acto acto) {}
}
