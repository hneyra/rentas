package kamayuk.rentas.fiscalizacion.aplicacion;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla de programas de fiscalización (RF-050, {@code fisc_programa}, #431).
 *
 * <p><b>Es la lectura que faltaba.</b> {@code /fiscalizacion/programas} declaraba sólo {@code
 * post}, así que un programa se podía registrar y no se podía volver a encontrar: ni la pantalla
 * que lo programa, ni las dos actas —{@code fisc_predial} y {@code fisc_vehicular}—, que necesitan
 * el {@code programaId} de un programa ya generado y no tenían ninguna fila real de la que sacarlo.
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transacción no hay {@code SET LOCAL}, y sin él la
 * política RLS falla en vez de devolver filas. Es el defecto que {@code ConsultaDeVias} cerró (#12)
 * y que se ha repetido en cinco issues; se anota aquí para que no haga falta descubrirlo otra vez.
 */
@Service
public class ConsultaDeProgramas {

    private final ProgramaFiscalizacionRepository programas;

    public ConsultaDeProgramas(ProgramaFiscalizacionRepository programas) {
        this.programas = programas;
    }

    @Transactional(readOnly = true)
    public Pagina<ProgramaFiscalizacion> buscar(
            CriterioDeProgramas criterio, Paginacion paginacion) {
        return programas.consultar(criterio, paginacion);
    }
}
