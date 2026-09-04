package kamayuk.rentas.sanciones.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.RecaudacionDeTasa;
import kamayuk.rentas.tesoreria.TasaCobrada;

/**
 * Un {@link CobrosDeTasas} con los cobros que la prueba le siembra (#50, AC 3).
 *
 * <h2>Por que aparece en P5D, y que NO se pierde</h2>
 *
 * <p>Hasta P5D, {@code SancionesJdbcTest} cobraba la custodia <b>con la caja de verdad</b>:
 * insertaba el recibo y su detalle en {@code recibo} y {@code recibo_detalle}, y la anulacion en
 * {@code recibo_movimiento}. `V7` retiro esas tres tablas —el recibo vive en {@code caja}— asi que
 * ese camino ya no existe en este repositorio.
 *
 * <p>Lo que el AC 3 mide <b>no cambia</b>, y conviene decirlo porque parece que si: lo que se
 * prueba es que la liberacion del vehiculo depende de lo que la caja conteste y no de una casilla
 * que marca quien entrega el vehiculo. Los cuatro casos siguen siendo los cuatro —un recibo que no
 * existe, uno anulado, uno que cobro otro concepto, y el bueno— y siguen entrando por {@link
 * CobrosDeTasas}, que es exactamente por donde entraban antes: {@code LiberarVehiculoInternado}
 * nunca leyo {@code recibo}, porque ARQ-01 §4 no se lo permite.
 *
 * <p>Lo que si se pierde, dicho aqui: que la <b>anulacion</b> del recibo ocurriera de verdad, con
 * su turno y su autorizacion. Ese acto es hoy de {@code caja} y se prueba en {@code caja}; aqui lo
 * que hay es su consecuencia, que es lo unico que este sistema puede ver.
 */
public final class CobrosDeMentira implements CobrosDeTasas {

    private final List<TasaCobrada> cobros = new ArrayList<>();
    private final List<String> anulados = new ArrayList<>();

    /** Siembra un cobro acreditable: numero de recibo, concepto, importe y fecha. */
    public CobrosDeMentira con(
            String numeroDeRecibo, String codigoDeTasa, Dinero importe, LocalDate fecha) {
        cobros.add(
                new TasaCobrada(
                        numeroDeRecibo, codigoDeTasa.toUpperCase(Locale.ROOT), 1, importe, fecha));
        return this;
    }

    /**
     * Como si {@code caja} hubiera registrado la anulacion de ese recibo.
     *
     * <p>Un recibo anulado <b>deja de acreditar</b>, y esa regla vive en {@code caja} —no en quien
     * pregunta—: el puerto promete «vacio si el recibo no existe, no cobro ese concepto o esta
     * anulado», y las tres respuestas son la misma para quien libera un vehiculo. Este doble lo
     * reproduce dejando de encontrarlo.
     */
    public CobrosDeMentira anular(String numeroDeRecibo) {
        anulados.add(numeroDeRecibo);
        return this;
    }

    @Override
    public Optional<TasaCobrada> acreditar(String numeroDeRecibo, String codigoDeTasa) {
        String recibo = numeroDeRecibo == null ? "" : numeroDeRecibo.strip();
        if (anulados.contains(recibo)) {
            return Optional.empty();
        }
        String concepto = codigoDeTasa.strip().toUpperCase(Locale.ROOT);
        return cobros.stream()
                .filter(
                        cobro ->
                                cobro.numeroDeRecibo().equals(recibo)
                                        && cobro.codigoDeTasa().equals(concepto))
                .findFirst();
    }

    @Override
    public RecaudacionDeTasa recaudado(String codigoDeTasa, LocalDate desde, LocalDate hasta) {
        // Esta prueba no ejercita el agregado —lo hace el resumen anual de licencias (#54)— y el
        // constructor lo pide igual. Ceros CON sus dos fechas, que es lo que el puerto promete para
        // un rango sin cobros; devolver null seria mentir de otra manera.
        return RecaudacionDeTasa.nada(codigoDeTasa, desde, hasta);
    }
}
