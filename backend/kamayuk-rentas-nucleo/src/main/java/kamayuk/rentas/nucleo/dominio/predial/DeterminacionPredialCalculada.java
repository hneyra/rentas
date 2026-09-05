package kamayuk.rentas.nucleo.dominio.predial;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Todo lo que hay que poder decir de una determinacion predial sin volver a calcular nada (#395).
 *
 * <p>Las cinco piezas que la capa web publica, en el orden en que se leen:
 *
 * <ol>
 *   <li>{@link #predios}: los que integran la base, con su codigo, ubicacion, uso, % de propiedad y
 *       autovaluo;
 *   <li>{@link #valuoTotal}, {@link #valuoExonerado} y {@link #valuoAfecto}, y la base del conjunto
 *       ya ponderada por el % de propiedad de cada predio ({@link #cabecera}{@code
 *       .baseImponible()});
 *   <li>{@link #tramos}: cada uno con su limite, su alicuota y lo que aporto, y el conjunto sellado
 *       con que se hizo ({@link #cabecera}{@code .conjuntoId()}, {@link #nombreDelConjunto});
 *   <li>{@link #cuotas} con sus vencimientos, y {@link #derechoDeEmision};
 *   <li>{@link #fechaCalculo}, a la que todo eso esta calculado (regla 9, RNF-075).
 * </ol>
 *
 * <p><b>Ninguna se recompone despues.</b> RNF-083: sumar los autovaluos de {@link #predios} para
 * «adelantar» la base daria una cifra parecida —sin el % de propiedad— y el error seria invisible.
 * Por eso viajan las tres cifras de valuo y la base, en vez de dejar que quien dibuja las derive.
 *
 * <p>{@code cabecera.esNueva()} distingue una simulacion de una determinacion asentada: la simulada
 * no tiene identificador porque no se guardo ninguna fila.
 *
 * @param cabecera la determinacion, con su conjunto, su base y su monto
 * @param predios los predios que la integran, en el orden en que se declararon
 * @param valuoTotal la suma de los autovaluos de los predios, sin ponderar
 * @param valuoExonerado la parte exonerada, sin ponderar
 * @param valuoAfecto lo que queda afecto, sin ponderar
 * @param uit la UIT del ejercicio con que se convirtieron los limites de los tramos
 * @param tramos que aporto cada tramo del articulo 13
 * @param minimoImponible el minimo del ejercicio; se aplica si el impuesto no llega
 * @param impuestoInsoluto el impuesto anual determinado, ya redondeado
 * @param derechoDeEmision el derecho de emision mecanizada
 * @param cuotas el cronograma, cada cuota con su vencimiento
 * @param modalidad como se paga: la modalidad cuyo cronograma se aplico
 * @param nombreDelConjunto como se nombra el conjunto sellado donde lo lee una persona
 * @param codContribuyente el codigo del contribuyente en el padron
 * @param sujeto de quien es esta determinacion, ya redactado
 * @param fechaCalculo el dia al que corresponde todo lo anterior
 */
public record DeterminacionPredialCalculada(
        Determinacion cabecera,
        List<PredioEnLaBase> predios,
        Dinero valuoTotal,
        Dinero valuoExonerado,
        Dinero valuoAfecto,
        Dinero uit,
        List<AporteDeTramo> tramos,
        Dinero minimoImponible,
        Dinero impuestoInsoluto,
        Dinero derechoDeEmision,
        List<CuotaDelPredial> cuotas,
        String modalidad,
        String nombreDelConjunto,
        String codContribuyente,
        String sujeto,
        LocalDate fechaCalculo) {

    public DeterminacionPredialCalculada {
        Objects.requireNonNull(cabecera, "La determinacion calculada necesita su cabecera");
        predios = List.copyOf(Objects.requireNonNull(predios, "Necesita los predios de la base"));
        if (predios.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un contribuyente sin predios no tiene base imponible cero: no tiene"
                            + " determinacion (NEG-05 §1)");
        }
        tramos = List.copyOf(Objects.requireNonNull(tramos, "Necesita el desglose de tramos"));
        cuotas = List.copyOf(Objects.requireNonNull(cuotas, "Necesita su cronograma"));
        Objects.requireNonNull(valuoTotal, "Necesita el valuo total");
        Objects.requireNonNull(valuoExonerado, "Necesita el valuo exonerado");
        Objects.requireNonNull(valuoAfecto, "Necesita el valuo afecto");
        Objects.requireNonNull(uit, "Necesita la UIT con que se convirtieron los tramos");
        Objects.requireNonNull(minimoImponible, "Necesita el minimo imponible del ejercicio");
        Objects.requireNonNull(impuestoInsoluto, "Necesita el impuesto insoluto");
        Objects.requireNonNull(derechoDeEmision, "Necesita el derecho de emision");
        Objects.requireNonNull(modalidad, "Necesita la modalidad de pago");
        Objects.requireNonNull(nombreDelConjunto, "Necesita el nombre del conjunto sellado");
        Objects.requireNonNull(codContribuyente, "Necesita el codigo del contribuyente");
        Objects.requireNonNull(sujeto, "Necesita de quien es (ADR-0016 §1)");
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9, RNF-075)");
    }

    /** Lo que se paga en total: el impuesto mas el derecho de emision. */
    public Dinero totalAPagar() {
        return impuestoInsoluto.mas(derechoDeEmision);
    }

    /** Si esto no se guardo: una simulacion no deja fila de {@code determinacion}. */
    public boolean esSimulacion() {
        return cabecera.esNueva();
    }
}
