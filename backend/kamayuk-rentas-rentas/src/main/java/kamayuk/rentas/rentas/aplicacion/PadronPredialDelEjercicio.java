package kamayuk.rentas.rentas.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Que autovaluos hay ya declarados en un ejercicio, para que la corrida masiva pueda volver a
 * determinar sin inventarlos (#395).
 *
 * <p>Existe como servicio aparte, y no como un metodo mas de {@link DeterminarPredialMasivo}, por
 * una razon que ya costo una vez: <b>la corrida no puede abrir una transaccion</b> —cada
 * contribuyente tiene que determinarse en la suya para que el que falla no se lleve por delante al
 * siguiente (#328)— y a la vez <b>la lectura si necesita una</b>, porque sin transaccion no hay
 * {@code SET LOCAL} y la politica de RLS falla con «unrecognized configuration parameter», que es
 * el defecto que {@code ConsultaDeVias} cerro. Un metodo privado del propio orquestador no
 * serviria: la anotacion la aplica el proxy de Spring, y una llamada a si mismo no pasa por el.
 */
@Service
public class PadronPredialDelEjercicio {

    private final DeterminacionRepository repositorio;

    public PadronPredialDelEjercicio(DeterminacionRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * La ultima determinacion predial de cada contribuyente del ejercicio, con su detalle por
     * predio.
     */
    @Transactional(readOnly = true)
    public List<DeterminacionConDetalle> ultimasDe(Ejercicio ejercicio) {
        List<DeterminacionConDetalle> padron = new ArrayList<>();
        for (Determinacion cabecera : repositorio.ultimasPredialesDe(ejercicio)) {
            Long id = cabecera.id();
            List<DetalleDeterminacionPredio> detalle =
                    id == null ? List.of() : repositorio.detalleDe(id);
            padron.add(new DeterminacionConDetalle(cabecera, detalle));
        }
        return List.copyOf(padron);
    }

    /**
     * Los autovaluos ya declarados de un contribuyente en el ejercicio, si alguien los declaro.
     *
     * <p>Es lo que permite volver a determinar sin volver a teclearlos: la declaracion vive en
     * {@code determinacion_predio_detalle} desde la primera vez. Solo del <b>mismo</b> ejercicio:
     * traer los del anterior seria aplicar en silencio un {@code % actualizacion} de cero (D-11).
     */
    @Transactional(readOnly = true)
    public List<DetalleDeterminacionPredio> autovaluosDeclaradosDe(
            Ejercicio ejercicio, long contribuyenteId) {
        return repositorio
                .ultimaPredialDe(ejercicio, contribuyenteId)
                .map(Determinacion::id)
                .map(repositorio::detalleDe)
                .orElseGet(List::of);
    }

    /** Una determinacion ya guardada y los predios que la integraron. */
    public record DeterminacionConDetalle(
            Determinacion cabecera, List<DetalleDeterminacionPredio> detalle) {

        public DeterminacionConDetalle {
            detalle = List.copyOf(detalle);
        }
    }
}
