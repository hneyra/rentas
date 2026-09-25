package kamayuk.rentas.indicadores.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.dominio.Ejercicio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las tres cifras del panel que salen del libro de este sistema, leidas en <b>una sola foto</b>
 * (#56, #450).
 *
 * <h2>Una sola transaccion, una sola foto</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} en el metodo, y las tres lecturas se unen a ella
 * —{@code REQUIRED} es la propagacion por omision—. Con tres transacciones separadas, cada cifra
 * saldria de un instante distinto y el panel podria mostrar una cartera que ya recogio un pago que
 * lo recaudado todavia no cuenta. Ademas, sin transaccion no hay {@code SET LOCAL} y la politica
 * RLS no puede evaluar {@code app.municipalidad_id}: la consulta <b>falla</b>.
 *
 * <p><b>Sin bloquear nada.</b> Ninguna de las tres pide {@code FOR UPDATE}. El panel se mira
 * mientras la ventanilla cobra, y una lectura que tomara la fila del turno pondria la cola a
 * esperar por la pantalla de inicio.
 *
 * <h2>Por que es una clase aparte y no el metodo del panel (#450)</h2>
 *
 * <p>Hasta #450 la transaccion era la de {@link PanelDeRecaudacion#del}, y abarcaba tambien la
 * cuarta cifra: el avance del dia, que es un {@code GET} a {@code caja} con 30 s de espera. La «una
 * sola foto» no la necesitaba —no es una lectura de esta base y no cabe en ninguna foto de ella—,
 * pero la esperaba con la conexion tomada, y con {@code caja} lenta diez aperturas de Inicio
 * dejaban a rentas entero sin pool. La foto es de lo que es una foto: estas tres.
 */
@Service
public class LecturaDelLibroParaElPanel {

    private final RecaudacionDelLibro recaudacion;
    private final CarteraDelLibro cartera;

    public LecturaDelLibroParaElPanel(RecaudacionDelLibro recaudacion, CarteraDelLibro cartera) {
        this.recaudacion = recaudacion;
        this.cartera = cartera;
    }

    /** Lo recaudado, lo cargado y la cartera pendiente del ejercicio, a esa fecha. */
    @Transactional(readOnly = true)
    public LoQueDiceElLibro leer(Ejercicio ejercicio, LocalDate aLaFecha) {
        LocalDate primerDia = LocalDate.of(ejercicio.valor(), 1, 1);
        LocalDate ultimoDia = LocalDate.of(ejercicio.valor(), 12, 31);
        return new LoQueDiceElLibro(
                recaudacion.recaudadoDeTodos(primerDia, ultimoDia, aLaFecha),
                cartera.cargadoPorTributo(ejercicio, aLaFecha),
                cartera.pendientePorTributo(ejercicio, aLaFecha));
    }

    /** Las tres cifras del libro, de la misma foto. */
    public record LoQueDiceElLibro(
            RecaudadoEnElLibro recaudado, CargadoEnElLibro cargado, CarteraPendiente pendiente) {

        public LoQueDiceElLibro {
            Objects.requireNonNull(recaudado, "lo recaudado");
            Objects.requireNonNull(cargado, "lo cargado");
            Objects.requireNonNull(pendiente, "la cartera pendiente");
        }
    }
}
