package kamayuk.rentas.coactiva.aplicacion;

import kamayuk.rentas.coactiva.ExpedientesSinRec;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que {@code coactiva} le contesta al panel de trabajo parado (#549).
 *
 * <p>Una sola linea de negocio, y esta en el criterio: {@link EstadoDelExpediente#INICIADO}. Es el
 * estado de un expediente que se importo y todavia no tiene ni un movimiento con estado —o sea, sin
 * REC-1 dictada—, y el propio enumerado lo dice: {@code INICIADO} no esta en el desplegable del
 * historial porque no se elige, se deriva.
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL} y la
 * politica RLS no puede evaluar {@code app.municipalidad_id} (#486).
 */
@Service
public class ExpedientesSinRecCoactiva implements ExpedientesSinRec {

    private final ExpedienteRepository expedientes;

    public ExpedientesSinRecCoactiva(ExpedienteRepository expedientes) {
        this.expedientes = expedientes;
    }

    @Override
    @Transactional(readOnly = true)
    public long cuantosSinRec1() {
        return expedientes.contar(
                new CriterioDeExpedientes(null, null, null, EstadoDelExpediente.INICIADO, null));
    }
}
