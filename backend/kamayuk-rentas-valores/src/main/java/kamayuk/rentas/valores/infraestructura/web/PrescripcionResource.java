package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import org.jspecify.annotations.Nullable;

/**
 * Como sale una declaracion de prescripcion por HTTP (RF-094, #39).
 *
 * <p>Sale el computo entero, ejercicio por ejercicio, y no solo el resultado: una resolucion de
 * prescripcion tiene que poder sustentarse, y sustentarla es decir de que dia a que dia se conto,
 * que actos lo interrumpieron y por que la fecha de prescripcion no es "el inicio mas el plazo".
 *
 * <p>No lleva ninguna cifra de dinero: la prescripcion no extingue un importe, deja sin accion su
 * cobro. La deuda sigue asentada en el libro, con sus asientos intactos.
 */
public record PrescripcionResource(
        long id,
        String codContribuyente,
        String tributo,
        int ejercicioDesde,
        int ejercicioHasta,
        String fechaDePresentacion,
        String plazoAplicable,
        String plazo,
        String resultado,
        @Nullable String nDeResolucion,
        List<EjercicioResource> ejercicios,
        List<HechoResource> hechos,
        String observacion) {

    public static PrescripcionResource de(Prescripcion prescripcion, String codContribuyente) {
        return new PrescripcionResource(
                java.util.Objects.requireNonNull(
                        prescripcion.id(), "Una prescripcion que sale por HTTP ya esta guardada"),
                codContribuyente,
                prescripcion.tributo(),
                prescripcion.ejercicioDesde().valor(),
                prescripcion.ejercicioHasta().valor(),
                prescripcion.fechaPresentacion().toString(),
                prescripcion.causal().name(),
                prescripcion.plazo().toString(),
                prescripcion.resultado().name(),
                prescripcion.resolucion(),
                prescripcion.ejercicios().stream().map(EjercicioResource::de).toList(),
                prescripcion.hechos().stream().map(HechoResource::de).toList(),
                prescripcion.observacion().texto());
    }

    /** El computo de un ejercicio, con los dos inicios que la resolucion tiene que explicar. */
    public record EjercicioResource(
            int ejercicio,
            String inicioDelComputo,
            String nuevoInicioDelComputo,
            String fechaDePrescripcion,
            boolean prescrita) {

        static EjercicioResource de(ComputoDeEjercicio computo) {
            return new EjercicioResource(
                    computo.ejercicio().valor(),
                    computo.inicioComputo().toString(),
                    computo.inicioVigente().toString(),
                    computo.fechaPrescripcion().toString(),
                    computo.prescrita());
        }
    }

    /** Un acto que interrumpio o suspendio el computo. */
    public record HechoResource(
            String clase, String causal, String fechaDesde, @Nullable String fechaHasta) {

        static HechoResource de(HechoDelComputo hecho) {
            return new HechoResource(
                    hecho.clase().name(),
                    hecho.causal(),
                    hecho.desde().toString(),
                    hecho.hasta() == null ? null : hecho.hasta().toString());
        }
    }
}
