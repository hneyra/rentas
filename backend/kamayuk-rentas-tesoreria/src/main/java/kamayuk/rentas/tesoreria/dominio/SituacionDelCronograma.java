package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;

/**
 * Cuantas cuotas del cronograma vencieron sin cobrarse y cuanto queda por cobrar, a una fecha
 * (#460).
 *
 * <p><b>Solo existe bajo un convenio {@link EstadoDeConvenio#VIGENTE VIGENTE}.</b> Un preconvenio
 * no acoge deuda —la deuda sigue en su fase ordinaria, y es el libro quien la cuenta—, y uno
 * cerrado ya la devolvio a la fase de la que salio. En los dos casos decir «vencidas 2, saldo
 * 514,00» es afirmar que se debe algo del convenio, y no se debe nada: la respuesta es «no aplica»,
 * no un cero, que tambien seria una afirmacion.
 *
 * <p>Hasta #460 el listado lo contaba en SQL sin mirar el estado, y la ficha en Java saltando la
 * inicial: dos respuestas al mismo hecho. Esta es la regla, y el {@code CASE} de {@code
 * ConvenioRepositoryJdbc} es su copia en SQL; {@code ConvenioJdbcTest} comprueba que coinciden
 * celda a celda.
 *
 * @param vencidas las cuotas vencidas y no cobradas; la inicial nunca cuenta
 * @param saldo lo que queda por cobrar del cronograma
 */
public record SituacionDelCronograma(int vencidas, Dinero saldo) {

    public SituacionDelCronograma {
        if (vencidas < 0) {
            throw new IllegalArgumentException("Las vencidas no son negativas: " + vencidas);
        }
        Objects.requireNonNull(saldo, "La situacion trae su saldo");
    }

    /**
     * La situacion del cronograma a {@code fecha}; vacia si el convenio no esta vigente.
     *
     * <p><b>Funcion pura</b> (regla 6): la fecha entra como argumento.
     *
     * @param estado en que situacion esta el convenio
     * @param cronograma sus cuotas, congeladas
     * @param pagadas cuantas se han cobrado, contando la inicial
     * @param fecha a que fecha se pregunta (regla 9)
     */
    public static Optional<SituacionDelCronograma> a(
            EstadoDeConvenio estado,
            List<CuotaDeConvenio> cronograma,
            int pagadas,
            LocalDate fecha) {
        Objects.requireNonNull(estado, "La situacion depende del estado del convenio");
        Objects.requireNonNull(fecha, "Toda cifra indica su fecha (regla 9, RNF-075)");
        if (estado != EstadoDeConvenio.VIGENTE) {
            return Optional.empty();
        }
        int vencidas = 0;
        Dinero saldo = Dinero.CERO;
        for (CuotaDeConvenio cuota : cronograma) {
            // Sin cobrar es «numero >= pagadas»: con la inicial cobrada (pagadas = 1), la
            // cuota 1 sigue pendiente.
            if (cuota.numero() < pagadas) {
                continue;
            }
            saldo = saldo.mas(cuota.monto());
            // Y vencida es la del dominio: el dia en que vence todavia no (#411).
            if (!cuota.esInicial() && cuota.vencidaA(fecha)) {
                vencidas++;
            }
        }
        return Optional.of(new SituacionDelCronograma(vencidas, saldo));
    }
}
