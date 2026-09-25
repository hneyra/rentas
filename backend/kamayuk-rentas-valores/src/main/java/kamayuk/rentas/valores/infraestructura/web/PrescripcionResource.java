package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.aplicacion.PrescripcionDeclarada;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.Valor;
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
 *
 * <p>Y dice que hizo con los valores (#337), por su numero: {@code valoresPrescritos} son los que
 * pasaron a {@code PRESCRITO} porque todas sus lineas lo estan, y {@code valoresCubiertosEnParte}
 * los que tocan lo prescrito pero formalizan ademas deuda que no lo esta, y por eso siguen como
 * estaban. El segundo es el que la administracion tiene que ver: nadie mas se lo va a decir.
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
        List<String> valoresPrescritos,
        List<String> valoresCubiertosEnParte,
        String observacion) {

    public static PrescripcionResource de(
            PrescripcionDeclarada declarada, String codContribuyente) {
        Prescripcion prescripcion = declarada.prescripcion();
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
                declarada.valoresPrescritos().stream().map(Valor::numero).toList(),
                declarada.valoresCubiertosEnParte().stream().map(Valor::numero).toList(),
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

    /**
     * Un acto que interrumpio o suspendio el computo.
     *
     * @param ejercicios de que ejercicios era la deuda que tocaba (#334). {@code null} solo en una
     *     resolucion anterior a {@code V25}, que se guardo sin esta columna y cuyo computo aplico
     *     el hecho a todo el rango: se publica en blanco y no reconstruido, igual que {@code V21}
     *     trata la modalidad de las determinaciones de antes
     */
    public record HechoResource(
            String clase,
            String causal,
            String fechaDesde,
            @Nullable String fechaHasta,
            @Nullable List<Integer> ejercicios) {

        static HechoResource de(HechoDelComputo hecho) {
            return new HechoResource(
                    hecho.clase().name(),
                    hecho.causal(),
                    hecho.desde().toString(),
                    hecho.hasta() == null ? null : hecho.hasta().toString(),
                    hecho.alcance().declarado()
                            ? hecho.alcance().ejercicios().stream().map(Ejercicio::valor).toList()
                            : null);
        }
    }
}
