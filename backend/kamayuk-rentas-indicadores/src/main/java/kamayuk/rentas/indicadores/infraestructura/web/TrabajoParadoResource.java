package kamayuk.rentas.indicadores.infraestructura.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.indicadores.dominio.FrenteParado;
import kamayuk.rentas.indicadores.dominio.TrabajoParado;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El trabajo parado por modulo, tal como sale por HTTP (#549, RF-130).
 *
 * <h2>La lista trae solo lo que el perfil puede ver</h2>
 *
 * <p>Un frente que quien pregunta no puede abrir <b>no aparece</b>. No aparece vacio, no aparece
 * con un guion y no aparece con una nota que diga que le falta permiso: una fila vacia ya dice que
 * ahi hay algo que mirar (ADR-0016 §2, #297).
 *
 * <h2>{@code importe} nulo no es cero</h2>
 *
 * <p>De los cuatro frentes, uno se puede cifrar y tres no. Los tres salen con {@code importe: null}
 * —nunca {@code "0.00"}— para que la interfaz pueda dibujar «sin cifrar» y «S/ 0.00» distinto, que
 * es lo mismo que {@code avanceConocido} hace con las barras del panel de recaudacion. Cuando el
 * frente cifrado no tiene ni una fila, su importe es {@code "0.00"} de verdad, y esa es la
 * diferencia que hay que poder ver.
 *
 * <p>Va como {@link ImporteActualizado} y no como un {@code Dinero} suelto: la regla de ArchUnit
 * {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} lo verifica sobre esta clase (RNF-075, regla 9).
 *
 * @param ejercicio el ejercicio contra el que se cuenta lo que depende de el
 * @param fechaCalculo el dia al que corresponden los recuentos
 * @param calculadoEn el instante exacto en que se leyeron
 * @param frentes uno por frente visible; vacia si el perfil no puede ver ninguno
 */
public record TrabajoParadoResource(
        int ejercicio, LocalDate fechaCalculo, Instant calculadoEn, List<Frente> frentes) {

    public static TrabajoParadoResource de(TrabajoParado parado) {
        return new TrabajoParadoResource(
                parado.ejercicio().valor(),
                parado.fechaCalculo(),
                parado.calculadoEn(),
                parado.frentes().stream().map(Frente::de).toList());
    }

    /**
     * Un frente.
     *
     * @param frente el nombre del frente en el enumerado, para que la interfaz enrute sin traducir
     * @param modulo el modulo del manual donde se desatasca
     * @param queEstaParado que es lo que esta parado
     * @param porQueCuestaDinero por que cuesta dinero tenerlo asi
     * @param cuantos el recuento
     * @param importe lo que suma, con su fecha; <b>nulo</b> cuando el frente no se puede cifrar
     */
    public record Frente(
            String frente,
            String modulo,
            String queEstaParado,
            String porQueCuestaDinero,
            long cuantos,
            @Nullable ImporteActualizado importe) {

        static Frente de(FrenteParado parado) {
            return new Frente(
                    parado.frente().name(),
                    parado.frente().modulo(),
                    parado.frente().queEstaParado(),
                    parado.frente().porQueCuestaDinero(),
                    parado.cuantos(),
                    parado.importe() == null
                            ? null
                            : new ImporteActualizado(parado.importe(), parado.actualizadoA()));
        }
    }
}
