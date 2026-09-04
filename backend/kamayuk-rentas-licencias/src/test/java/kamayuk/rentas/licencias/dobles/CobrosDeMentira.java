package kamayuk.rentas.licencias.dobles;

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
 * Un {@link CobrosDeTasas} con los cobros que la prueba le siembra (#54).
 *
 * <p>Es el <b>segundo puerto publico de tesoreria</b> que {@code licencias} usa, junto a {@link
 * CajaDeMentira}. Los dos hacen falta y no se solapan: {@code RecibosDeTramite} dice si un recibo
 * respalda el derecho —y con que identificador—, y este dice <b>cuanto</b> cobro por ese concepto y
 * a que fecha, que es la cifra que acaba impresa en el certificado.
 *
 * <p>Que sea un doble del puerto y no del repositorio de recibos es el punto: si esta prueba
 * pudiera montar un doble de {@code tesoreria.dominio}, seria porque {@code licencias} lo conoce,
 * que es lo que Spring Modulith verifica que no ocurra.
 */
public final class CobrosDeMentira implements CobrosDeTasas {

    private final List<TasaCobrada> cobros = new ArrayList<>();
    private final List<Recaudado> recaudaciones = new ArrayList<>();

    /** Siembra un cobro acreditable: numero de recibo, concepto, importe y fecha. */
    public CobrosDeMentira con(
            String numeroDeRecibo, String codigoDeTasa, String importe, LocalDate fecha) {
        cobros.add(new TasaCobrada(numeroDeRecibo, codigoDeTasa, 1, Dinero.de(importe), fecha));
        return this;
    }

    /** Siembra lo recaudado por un concepto en un año, para el resumen anual. */
    public CobrosDeMentira recaudadoEn(String codigoDeTasa, int ano, String cobrado) {
        recaudaciones.add(new Recaudado(codigoDeTasa, ano, Dinero.de(cobrado)));
        return this;
    }

    @Override
    public Optional<TasaCobrada> acreditar(String numeroDeRecibo, String codigoDeTasa) {
        String recibo = numeroDeRecibo == null ? "" : numeroDeRecibo.strip();
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
        String concepto = codigoDeTasa.strip().toUpperCase(Locale.ROOT);
        Dinero total = Dinero.CERO;
        for (Recaudado fila : recaudaciones) {
            if (fila.concepto().equals(concepto)
                    && fila.ano() >= desde.getYear()
                    && fila.ano() <= hasta.getYear()) {
                total = total.mas(fila.cobrado());
            }
        }
        return new RecaudacionDeTasa(concepto, total, Dinero.CERO, desde, hasta);
    }

    private record Recaudado(String concepto, int ano, Dinero cobrado) {}
}
