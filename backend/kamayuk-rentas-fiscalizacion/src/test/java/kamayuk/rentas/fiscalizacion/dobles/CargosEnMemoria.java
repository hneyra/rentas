package kamayuk.rentas.fiscalizacion.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El puerto por el que se asientan cargos, en memoria.
 *
 * <p>No reproduce el libro —eso es {@code cuentacorriente} y tiene sus propias pruebas contra
 * PostgreSQL—: guarda <b>que</b> se pidio asentar, que es lo que la transferencia decide. Que el
 * asiento acabe donde debe lo demuestra {@code TransferenciaJdbcTest} con el generador real.
 */
public final class CargosEnMemoria implements GeneradorDeCargos {

    private final List<Cargo> asentados = new ArrayList<>();

    @Override
    public void generarCargo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        asentados.add(
                new Cargo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        periodo,
                        predioId,
                        vehiculoId,
                        monto,
                        fechaValor,
                        documentoOrigen));
    }

    @Override
    public void generarGastoDelProcedimiento(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        throw new UnsupportedOperationException(
                "La transferencia a rentas no asienta gastos del procedimiento coactivo");
    }

    public List<Cargo> asentados() {
        return List.copyOf(asentados);
    }

    /** Un cargo tal como se pidio asentarlo. */
    public record Cargo(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen) {}
}
