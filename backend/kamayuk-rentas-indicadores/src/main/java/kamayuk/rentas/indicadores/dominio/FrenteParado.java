package kamayuk.rentas.indicadores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un frente con su recuento y, cuando se puede, su importe (#549, RF-130).
 *
 * <h2>{@link #importe} nulo NO es cero, y esa es la decision que esta clase sostiene</h2>
 *
 * <p>Un frente que no se puede cifrar se publica <b>con el recuento y sin importe</b>. La
 * alternativa comoda —{@code Dinero.CERO} por omision— produce «S/ 0.00» junto a «412 valores», que
 * se lee como «esos 412 no valen nada» y es exactamente lo contrario de lo que pasa. Es el mismo
 * reparto que {@link LineaDeCartera#avance}, que es un {@code OptionalInt} y no un cero, y el mismo
 * defecto que #51 midio con la tasa por omision: una cifra plausible y equivocada no se distingue
 * de la correcta al leerla.
 *
 * <p>Y por eso hace falta que un frente cuyo importe <b>si</b> es cero siga trayendo la cifra: cero
 * papeletas impuestas suman S/ 0.00, que es un hecho, y tiene que poder distinguirse de «esto no se
 * sabe cifrar».
 *
 * <p>{@link #actualizadoA} es obligatorio y va en <b>cada</b> frente: los cuatro se leen en la
 * misma transaccion, pero cada uno describe su poblacion a esa fecha y una cifra sin fecha es una
 * cifra que manana es otra (regla 9, RNF-075).
 *
 * @param frente cual de los frentes es; de el salen el modulo y que esta parado
 * @param cuantos el recuento, nunca negativo
 * @param importe lo que suma, cuando el modulo lo publica; nulo cuando no se puede cifrar
 * @param actualizadoA a que fecha esta el recuento
 */
public record FrenteParado(
        FrenteDeTrabajo frente, long cuantos, @Nullable Dinero importe, LocalDate actualizadoA) {

    public FrenteParado {
        Objects.requireNonNull(frente, "El frente dice de que modulo es");
        if (cuantos < 0) {
            throw new IllegalArgumentException("Un recuento no es negativo: " + cuantos);
        }
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }

    /** Un frente que el modulo sabe cifrar. */
    public static FrenteParado cifrado(
            FrenteDeTrabajo frente, long cuantos, Dinero importe, LocalDate actualizadoA) {
        return new FrenteParado(
                frente,
                cuantos,
                Objects.requireNonNull(importe, "Un frente cifrado trae su importe"),
                actualizadoA);
    }

    /** Un frente que se cuenta y no se cifra. El importe va <b>nulo</b>, jamas cero. */
    public static FrenteParado soloContado(
            FrenteDeTrabajo frente, long cuantos, LocalDate actualizadoA) {
        return new FrenteParado(frente, cuantos, null, actualizadoA);
    }

    /** Si este frente trae importe. Lo contrario no es «vale cero»: es «no se sabe cifrar». */
    public boolean estaCifrado() {
        return importe != null;
    }
}
