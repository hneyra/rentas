package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla «Vehículos en depósito» de la pantalla {@code internamiento} (#50, RF-064).
 *
 * <p>Existe como caso de uso y no como llamada directa del controlador por lo mismo que {@code
 * ConsultaDeVias}: sin {@code @Transactional} no hay {@code SET LOCAL}, y sin {@code SET LOCAL} la
 * política RLS <b>falla</b> —{@code current_setting} sin valor por omisión lanza—. Eso ya se
 * descubrió una vez en marcha blanca con {@code GET /catastro/vias}, y la lección quedó escrita.
 *
 * <p>La fecha con la que se cuentan los días entra por argumento y viaja en cada fila (regla 9,
 * RNF-075): «11 días» sin decir a qué día es una cifra que mañana es 12.
 */
@Service
public class ConsultaDeInternamientos {

    private final InternamientoRepository internamientos;

    public ConsultaDeInternamientos(InternamientoRepository internamientos) {
        this.internamientos = internamientos;
    }

    @Transactional(readOnly = true)
    public Pagina<InternamientoEnConsulta> listar(
            CriterioDeInternamiento criterio, LocalDate aLaFecha, Paginacion paginacion) {
        return internamientos.buscar(criterio, aLaFecha, paginacion);
    }
}
