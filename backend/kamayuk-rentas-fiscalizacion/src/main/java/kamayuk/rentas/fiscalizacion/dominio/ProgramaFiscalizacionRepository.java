package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

public interface ProgramaFiscalizacionRepository {

    ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa);

    Optional<ProgramaFiscalizacion> findById(long id);

    /** La grilla de programas de la pantalla {@code fisc_programa} (RF-050, #431). */
    Pagina<ProgramaFiscalizacion> consultar(CriterioDeProgramas criterio, Paginacion paginacion);
}
