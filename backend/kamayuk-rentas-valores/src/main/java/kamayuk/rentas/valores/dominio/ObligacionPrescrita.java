package kamayuk.rentas.valores.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Un par (tributo, ejercicio) cuya accion de cobro una resolucion declaro prescrita (#337).
 *
 * <p>Es la unidad en la que se resuelve la prescripcion: la solicitud se presenta por tributo y
 * rango, y el computo sale ejercicio por ejercicio ({@link ComputoDeEjercicio}). No lleva predio ni
 * vehiculo porque la resolucion no los nombra: el PREDIAL 2021 prescrito lo esta en todos los
 * predios del contribuyente.
 *
 * <p>El tributo se normaliza como lo hace {@link ValorDetalle}: sin espacios y en mayusculas. Asi
 * «predial» y «PREDIAL» son el mismo par, igual que la comparacion {@code upper(...)} del SQL, y un
 * {@code Set} de estos pares se puede consultar con la linea de un valor sin mas.
 *
 * @param tributo el tributo, tal como lo nombra la linea del valor
 * @param ejercicio el ejercicio de la obligacion
 */
public record ObligacionPrescrita(String tributo, Ejercicio ejercicio) {

    public ObligacionPrescrita {
        Objects.requireNonNull(tributo, "La obligacion prescrita necesita su tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("La obligacion prescrita necesita su tributo");
        }
        Objects.requireNonNull(ejercicio, "La obligacion prescrita necesita su ejercicio");
    }

    /** El par que formaliza esa linea de un valor, este prescrito o no: se pregunta al conjunto. */
    public static ObligacionPrescrita deLaLinea(ValorDetalle linea) {
        return new ObligacionPrescrita(linea.tributo(), linea.ejercicio());
    }
}
