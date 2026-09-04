package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Lo que sigue pendiente de cobro en un ejercicio <b>a una fecha</b> (#56, #639, RF-130).
 *
 * <p>Es la respuesta de {@link CarteraDelLibro#pendientePorTributo}, y sale del <b>libro</b> con la
 * fecha de corte aplicada: es la suma, sobre el padron entero, de la misma cifra que {@code
 * consulta_deuda} publica obligacion por obligacion —{@code deudaActualizadaA(fecha).insoluto()}—.
 *
 * <p><b>Una sola fecha, y ahora significa algo.</b> Hasta #639 llevaba dos —{@code aLaFecha} y
 * {@code proyectadaDesde}— porque la cifra salia de un cache que podia estar parado, y la propia
 * {@code aLaFecha} no la cambiaba: la cartera daba lo mismo preguntando por enero que por
 * diciembre, incluida la cuota que aun no vencia. Ahora {@link #aLaFecha} es la fecha de corte de
 * verdad —cambiarla cambia la cifra— y no hay cache del que declarar la frescura.
 *
 * @param lineas una por tributo con insoluto pendiente en el ejercicio a esa fecha
 * @param ejercicio el ejercicio que se sumo
 * @param aLaFecha la fecha de corte con la que se sumo (regla 9, RNF-075)
 */
public record CarteraPendiente(
        List<PendienteDeUnTributo> lineas, Ejercicio ejercicio, LocalDate aLaFecha) {

    public CarteraPendiente {
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        Objects.requireNonNull(ejercicio, "La cartera siempre es de un ejercicio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        lineas = List.copyOf(lineas);
    }

    /** La suma de todas las lineas: el insoluto pendiente del ejercicio a esa fecha, al centimo. */
    public Dinero total() {
        Dinero total = Dinero.CERO;
        for (PendienteDeUnTributo linea : lineas) {
            total = total.mas(linea.pendiente());
        }
        return total;
    }

    /** Cuantas obligaciones —no cuotas— componen la cartera. */
    public long obligaciones() {
        long cuantas = 0;
        for (PendienteDeUnTributo linea : lineas) {
            cuantas += linea.obligaciones();
        }
        return cuantas;
    }
}
