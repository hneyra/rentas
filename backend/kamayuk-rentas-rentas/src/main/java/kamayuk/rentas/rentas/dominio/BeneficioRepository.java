package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Beneficios y exoneraciones (RF-029). Ningun metodo recibe la municipalidad (regla 2): sale del
 * token y la aplica la politica RLS.
 *
 * <p><b>No hay {@code delete}.</b> Un beneficio se cesa, no se borra (regla 4): {@link #actualizar}
 * es como se guarda el cese, sin tocar el resto de la fila.
 */
public interface BeneficioRepository {

    Optional<Beneficio> findById(long id);

    Pagina<Beneficio> buscar(CriterioDeBeneficio criterio, Paginacion paginacion);

    /**
     * Los beneficios de ese tipo que tiene el contribuyente, vigentes o no. Es lo que consulta
     * {@code RegistrarBeneficio} antes de dar de alta uno nuevo, para rechazar el que se solape.
     */
    List<Beneficio> delContribuyente(long contribuyenteId, String tipo);

    /**
     * Los beneficios de ese tributo, vigentes a esa fecha, que tiene el predio —independientemente
     * de quién sea hoy su titular (#31): un predio sin servicio de limpieza lo sigue sin tener
     * aunque el predio cambie de dueño.
     */
    List<Beneficio> vigentesDelPredio(long predioId, String tributo, LocalDate fecha);

    /**
     * Los beneficios del contribuyente que <b>rigen</b> a esa fecha, de cualquier tipo y tributo.
     *
     * <p>«Vigentes a la fecha», no «los ultimos» (regla 9): un beneficio cesado en marzo no rige en
     * abril, y resolver «el ultimo» haria que una consulta de enero mostrara el que se dio de alta
     * en junio. Es lo que {@code BeneficiosDelContribuyente} publica para otros contextos (#42).
     */
    List<Beneficio> vigentesDelContribuyente(long contribuyenteId, LocalDate fecha);

    Beneficio insertar(Beneficio beneficio);

    /** Guarda el cese: la unica escritura que admite un beneficio ya guardado. */
    Beneficio actualizar(Beneficio beneficio);
}
