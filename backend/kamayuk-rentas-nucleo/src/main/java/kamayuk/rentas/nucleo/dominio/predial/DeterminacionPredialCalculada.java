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
        if (cabecera.modalidad() == null) {
            throw new IllegalArgumentException(
                    "Toda determinacion predial calculada dice bajo que cronograma se emite, y lo"
                            + " dice su CABECERA: es el dato que V21 guarda y el unico que se"
                            + " puede volver a leer (#234)");
        }
        Objects.requireNonNull(nombreDelConjunto, "Necesita el nombre del conjunto sellado");
        Objects.requireNonNull(codContribuyente, "Necesita el codigo del contribuyente");
        Objects.requireNonNull(sujeto, "Necesita de quien es (ADR-0016 §1)");
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9, RNF-075)");
    }

    /**
     * Como se paga: la modalidad cuyo cronograma se aplico.
     *
     * <p>No es un componente propio, y no es un detalle: es la de la <b>cabecera</b>, o sea la que
     * queda escrita en {@code determinacion.modalidad} (V21). Llevarla dos veces —una en la fila y
     * otra al lado— es la segunda verdad sobre el mismo hecho que #214 retiro del acta de
     * fiscalizacion, y aqui la respuesta y la fila podrian no coincidir.
     */
    public ModalidadDelPredial modalidad() {
        return Objects.requireNonNull(cabecera.modalidad());
    }

    /** Lo que se paga en total: el impuesto mas el derecho de emision. */
    public Dinero totalAPagar() {
        return impuestoInsoluto.mas(derechoDeEmision);
    }

    /** Si esto no se guardo: una simulacion no deja fila de {@code determinacion}. */
    public boolean esSimulacion() {
        return cabecera.esNueva();
    }

    /**
     * La misma determinacion, ahora con la cabecera que quedo <b>asentada</b> (#359).
     *
     * <p>Existe para que el orden lo imponga la forma y no la memoria de quien escribe el caso de
     * uso: la determinacion se compone <b>entera</b> —cabecera calculada, derecho de emision,
     * cuotas, y las validaciones de este record— antes de escribir nada, y solo despues se asienta
     * y se cambia la cabecera sin identificador por la guardada. Lo que puede faltar ya fallo, y lo
     * unico que queda despues del asiento es esta copia.
     *
     * <p>Se niega a cambiar una cabecera por otra que no es la misma determinacion: las cuotas y el
     * desglose se calcularon con la base, el monto, la modalidad y el conjunto de la calculada, y
     * con otra cabecera la respuesta diria una cifra y la fila otra.
     *
     * @param asentada la cabecera que devolvio el asiento, con su identificador
     */
    public DeterminacionPredialCalculada asentadaComo(Determinacion asentada) {
        Objects.requireNonNull(asentada, "Se cambia por la cabecera asentada");
        if (!cabecera.esNueva()) {
            throw new IllegalStateException(
                    "Esta determinacion ya esta asentada con el id " + cabecera.id());
        }
        if (asentada.esNueva()) {
            throw new IllegalArgumentException(
                    "La cabecera asentada trae su identificador: sin el no hay fila que la lleve");
        }
        if (!asentada.baseImponible().equals(cabecera.baseImponible())
                || !asentada.montoDeterminado().equals(cabecera.montoDeterminado())
                || asentada.conjuntoId() != cabecera.conjuntoId()
                || asentada.contribuyenteId() != cabecera.contribuyenteId()
                || !asentada.ejercicio().equals(cabecera.ejercicio())
                || asentada.modalidad() != cabecera.modalidad()) {
            throw new IllegalArgumentException(
                    "La cabecera asentada no es la que se calculo: "
                            + asentada
                            + " frente a "
                            + cabecera);
        }
        return new DeterminacionPredialCalculada(
                asentada,
                predios,
                valuoTotal,
                valuoExonerado,
                valuoAfecto,
                uit,
                tramos,
                minimoImponible,
                impuestoInsoluto,
                derechoDeEmision,
                cuotas,
                nombreDelConjunto,
                codContribuyente,
                sujeto,
                fechaCalculo);
    }
}
