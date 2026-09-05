package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La ficha del vehiculo y su historial de placas, en <b>una</b> transaccion.
 *
 * <h2>Por que existe, y no basta con que el controlador llame al repositorio</h2>
 *
 * <p>Porque una lectura fuera de transaccion no tiene {@code SET LOCAL}, y sin el la politica RLS
 * no puede evaluar {@code current_setting('app.municipalidad_id')}: la consulta <b>falla</b>. Es el
 * comportamiento correcto —mejor fallar que devolver de mas—, pero significa que un controlador que
 * llame al repositorio directamente no funciona nunca. Se descubre ejecutandolo, no leyendolo: en
 * la revision se ve una lectura simple que no parece necesitar transaccion.
 *
 * <p>Y ademas las dos consultas tienen que ir juntas: la ficha y su historial se leen en el mismo
 * instante o el historial puede describir un vehiculo que ya cambio.
 *
 * @param vehiculo la ficha
 * @param historial las placas que tuvo, de la mas reciente a la mas antigua
 */
@Service
public class ConsultaDeVehiculos {

    private final VehiculoRepository repositorio;
    private final ConsultaDeDeudaPublica deuda;

    public ConsultaDeVehiculos(VehiculoRepository repositorio, ConsultaDeDeudaPublica deuda) {
        this.repositorio = repositorio;
        this.deuda = deuda;
    }

    /** La ficha con su historial, o {@code NO_ENCONTRADO} si esa placa no esta en el padron. */
    @Transactional(readOnly = true)
    public FichaDeVehiculo porPlaca(Placa placa) {
        Vehiculo vehiculo =
                repositorio
                        .findByPlaca(placa)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun vehiculo con la placa " + placa));
        long id = Objects.requireNonNull(vehiculo.id(), "Un vehiculo leido de la base tiene id");
        return new FichaDeVehiculo(vehiculo, repositorio.historialDePlacas(id));
    }

    /**
     * El padron vehicular que pide el criterio, con la deuda vigente de cada vehiculo (RF-024,
     * #25).
     *
     * <p>«Base imponible» no sale en esta fila: el impuesto al patrimonio vehicular necesita la
     * tabla de valores referenciales, y eso sigue bloqueado por D-02 (ver el javadoc de {@link
     * Vehiculo}). La deuda si: es dinero ya asentado en el libro, no una cifra que este metodo
     * determine.
     */
    @Transactional(readOnly = true)
    public Pagina<VehiculoConDeuda> buscar(
            CriterioDeVehiculo criterio, LocalDate fecha, Paginacion paginacion) {
        Pagina<VehiculoEncontrado> pagina = repositorio.buscar(criterio, paginacion);
        return pagina.mapear(fila -> new VehiculoConDeuda(fila, deudaDe(fila.vehiculo(), fecha)));
    }

    /**
     * Los vehiculos activos que pide el criterio, <b>sin</b> su deuda.
     *
     * <p>La usa el calculo vehicular para resolver sobre que vehiculos calcula, y no {@link
     * #buscar}: ahi cada fila cuesta una consulta de deuda al libro, y el calculo no la mira.
     */
    @Transactional(readOnly = true)
    public Pagina<VehiculoEncontrado> activosDe(
            CriterioDeVehiculo criterio, Paginacion paginacion) {
        return repositorio.buscar(criterio, paginacion);
    }

    /** El vehiculo por su placa, tal cual. Vacio si no esta en el padron vehicular. */
    @Transactional(readOnly = true)
    public Optional<Vehiculo> vehiculoPorPlaca(Placa placa) {
        return repositorio.findByPlaca(placa);
    }

    /** El vehiculo por su identificador interno. */
    @Transactional(readOnly = true)
    public Optional<Vehiculo> vehiculoPorId(long id) {
        return repositorio.findById(id);
    }

    /**
     * La deuda de <b>este</b> vehiculo, sumando las obligaciones de su contribuyente que le
     * pertenecen a el —no las de otros predios o vehiculos que tambien tenga—.
     *
     * <p>Sumar varias {@link ObligacionPublica} ya calculadas es una agregacion legitima del
     * backend, no la composicion que RNF-083 prohibe en la interfaz: aqui hay contexto completo —el
     * mismo contribuyente, la misma fecha de corte— en una sola transaccion, y el resultado sigue
     * llevando su fecha.
     */
    private ImporteActualizado deudaDe(Vehiculo vehiculo, LocalDate fecha) {
        long id = Objects.requireNonNull(vehiculo.id(), "Un vehiculo leido de la base tiene id");
        Dinero total = Dinero.CERO;
        for (ObligacionPublica obligacion :
                deuda.deTodoElContribuyente(vehiculo.contribuyenteId(), fecha)) {
            if (Objects.equals(obligacion.vehiculoId(), id)) {
                total = total.mas(obligacion.total());
            }
        }
        return new ImporteActualizado(total, fecha);
    }

    /** Lo que la pantalla de la ficha necesita, leido de una vez. */
    public record FichaDeVehiculo(Vehiculo vehiculo, List<CambioDePlaca> historial) {}

    /** Una fila de la consulta: el vehiculo, su titular y cuanto debe a la fecha de corte. */
    public record VehiculoConDeuda(VehiculoEncontrado fila, ImporteActualizado deuda) {}
}
