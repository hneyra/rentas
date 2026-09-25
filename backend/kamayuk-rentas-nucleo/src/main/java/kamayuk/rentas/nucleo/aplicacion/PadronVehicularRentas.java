package kamayuk.rentas.nucleo.aplicacion;

import kamayuk.rentas.nucleo.PadronVehicular;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que {@code rentas} contesta cuando otro contexto pregunta si un vehiculo esta en su padron
 * (#422).
 *
 * <p>No tiene consulta propia: es {@link VehiculoRepository#findById}, la misma lectura que usa la
 * ficha del vehiculo. Lleva su transaccion de solo lectura porque {@code RepositorioJdbc} no abre
 * ninguna, y sin una activa no hay {@code SET LOCAL} y la politica RLS evalua sobre la cadena
 * vacia; dentro de la transaccion de quien pregunta —un acta, una constancia— simplemente se une a
 * ella.
 */
@Service
public class PadronVehicularRentas implements PadronVehicular {

    private final VehiculoRepository vehiculos;

    public PadronVehicularRentas(VehiculoRepository vehiculos) {
        this.vehiculos = vehiculos;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean estaEnElPadron(long vehiculoId) {
        return vehiculos.findById(vehiculoId).isPresent();
    }
}
