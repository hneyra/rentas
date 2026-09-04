package kamayuk.rentas.parametros;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Desde que ejercicio rige una regla, y hasta cual.
 *
 * <p>Por ejercicio y no por fecha, siguiendo ARQ-09 §1.3: la implementacion aplicable es la del
 * <b>ejercicio del hecho imponible</b>, nunca la del ano en curso. Una firma que recibe una fecha
 * admite que alguien pase «hoy» y calcule 2027 con las reglas de 2037; una que recibe el ejercicio
 * no da esa opcion. Es la misma defensa que ARQ-09 §2.2 aplica a los parametros.
 *
 * <p>Sin {@code hasta} la regla sigue vigente. Es lo normal: la mayoria de las reglas del predial
 * rigen «desde 2004 en adelante».
 */
public record RangoDeEjercicios(Ejercicio desde, @Nullable Ejercicio hasta) {

    public RangoDeEjercicios {
        Objects.requireNonNull(desde, "Toda regla dice desde que ejercicio rige");
        if (hasta != null && hasta.valor() < desde.valor()) {
            throw new IllegalArgumentException(
                    "El ejercicio final no puede ser anterior al inicial: " + desde + ".." + hasta);
        }
    }

    /** Rige desde ese ejercicio y no termina. */
    public static RangoDeEjercicios desde(Ejercicio desde) {
        return new RangoDeEjercicios(desde, null);
    }

    public static RangoDeEjercicios entre(Ejercicio desde, Ejercicio hasta) {
        return new RangoDeEjercicios(desde, hasta);
    }

    public boolean rigeEn(Ejercicio ejercicio) {
        Objects.requireNonNull(ejercicio, "Preguntar por la vigencia exige el ejercicio");
        if (ejercicio.valor() < desde.valor()) {
            return false;
        }
        return hasta == null || ejercicio.valor() <= hasta.valor();
    }

    public Optional<Ejercicio> ejercicioFinal() {
        return Optional.ofNullable(hasta);
    }

    /** Dos rangos se solapan si existe algun ejercicio en el que ambos rigen. */
    public boolean seSolapaCon(RangoDeEjercicios otro) {
        int inicio = Math.max(desde.valor(), otro.desde.valor());
        int fin =
                Math.min(
                        hasta == null ? Integer.MAX_VALUE : hasta.valor(),
                        otro.hasta == null ? Integer.MAX_VALUE : otro.hasta.valor());
        return inicio <= fin;
    }

    @Override
    public String toString() {
        return hasta == null ? desde + " en adelante" : desde + ".." + hasta;
    }
}
