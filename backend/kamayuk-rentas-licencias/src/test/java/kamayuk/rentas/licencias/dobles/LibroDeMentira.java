package kamayuk.rentas.licencias.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un {@link GeneradorDeCargos} que anota lo que se le pidio, sin libro detras (#51).
 *
 * <p>Sirve para la capa web, donde lo que se prueba es el transporte: que un reenvio devuelva 200 y
 * <b>no vuelva a pedir el cargo</b> se ve contando lo que este doble recibio. Lo que <b>no</b>
 * demuestra es que la deuda entre de verdad en el libro —eso es el AC 2, y lo prueba {@code
 * AnunciosYPropagandaJdbcTest} contra PostgreSQL con el {@code GeneradorDeCargos} de verdad—.
 *
 * <p>Que este doble implemente el <b>puerto publico</b> y no un repositorio de {@code
 * cuentacorriente} no es casualidad: es el unico tipo de ese modulo que {@code licencias} conoce.
 */
public final class LibroDeMentira implements GeneradorDeCargos {

    /**
     * Un cargo tal como se pidio.
     *
     * @param referenciaExterna la cadena con la que entra al libro; es la que #51 declara unica
     */
    public record CargoPedido(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Long predioId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen) {}

    private final List<CargoPedido> pedidos = new ArrayList<>();

    public List<CargoPedido> pedidos() {
        return List.copyOf(pedidos);
    }

    public int cuantos() {
        return pedidos.size();
    }

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
        pedidos.add(
                new CargoPedido(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        predioId,
                        referenciaExterna,
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
                "Un anuncio no devenga costas del procedimiento coactivo: eso es de #42");
    }
}
