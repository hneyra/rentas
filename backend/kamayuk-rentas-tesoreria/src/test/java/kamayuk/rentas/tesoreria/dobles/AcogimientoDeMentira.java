package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;

/**
 * Un {@link AcogimientoAConvenio} de mentira que <b>se comporta como el libro</b>: lo que acoge lo
 * saca de la fase de origen, y lo que devuelve vuelve a estar donde estaba.
 *
 * <p>Reproducir esa propiedad en el doble no es adorno. Lo que se prueba con el es que el convenio
 * <b>no traiga su propia cifra</b>: si {@code FormalizarConvenio} guardara el importe que el
 * preconvenio congelo en vez de usar el que el libro devuelve, un pago hecho entre la firma y el
 * cobro de la inicial acabaria acogido de todos modos. La demostracion de que los asientos son de
 * verdad —y de que el quiebre devuelve a la fase correcta— la hace {@code ConvenioJdbcTest} contra
 * PostgreSQL.
 */
public final class AcogimientoDeMentira implements AcogimientoAConvenio {

    private final Map<SeleccionDeObligacion, DeudaAcogida> deuda = new LinkedHashMap<>();
    private final List<String> documentosAcogidos = new ArrayList<>();
    private final List<String> documentosDevueltos = new ArrayList<>();

    /** Declara la deuda de una obligacion, con su fase de origen y su desglose. */
    public AcogimientoDeMentira con(
            SeleccionDeObligacion obligacion, String faseOrigen, Dinero insoluto, LocalDate fecha) {
        deuda.put(
                obligacion,
                new DeudaAcogida(
                        obligacion.tributo(),
                        obligacion.ejercicio(),
                        0,
                        obligacion.predioId(),
                        obligacion.vehiculoId(),
                        faseOrigen,
                        fecha,
                        insoluto,
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO));
        return this;
    }

    /**
     * Deja la obligacion sin deuda: es lo que pasa si alguien la paga entre la firma y el cobro.
     */
    public void vaciar(SeleccionDeObligacion obligacion) {
        deuda.remove(obligacion);
    }

    public List<String> documentosAcogidos() {
        return List.copyOf(documentosAcogidos);
    }

    public List<String> documentosDevueltos() {
        return List.copyOf(documentosDevueltos);
    }

    @Override
    public List<DeudaAcogida> deudaAcogible(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fechaDeCorte) {
        List<DeudaAcogida> acogible = new ArrayList<>();
        for (SeleccionDeObligacion obligacion : obligaciones) {
            DeudaAcogida fila = deuda.get(obligacion);
            if (fila != null) {
                acogible.add(fila);
            }
        }
        return List.copyOf(acogible);
    }

    @Override
    public MovimientoAsentado acoger(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        documentosAcogidos.add(documentoOrigen);
        List<DeudaAcogida> movidas = new ArrayList<>();
        for (DeudaAcogida cuota : acogidas) {
            if (deuda.containsValue(cuota)) {
                movidas.add(cuota);
            }
        }
        return new MovimientoAsentado(movidas, movidas.size() * 2, fecha);
    }

    @Override
    public MovimientoAsentado devolver(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        documentosDevueltos.add(documentoOrigen);
        return new MovimientoAsentado(acogidas, acogidas.size() * 2, fecha);
    }
}
