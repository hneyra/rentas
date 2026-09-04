package kamayuk.rentas.dominio;

import java.util.Objects;

/**
 * Tramo de tiempo al que se imputa una obligacion: un ejercicio y, dentro de el, su division.
 *
 * <p>El sistema original guarda las dos cosas por separado —{@code ejercicio} y {@code periodo}— y
 * aqui viajan juntas, porque separadas se acaban cruzando: la cuota 2 de 2026 y la cuota 2 de 2027
 * son dos deudas distintas y se distinguen por un {@code smallint} suelto que es facil de olvidar
 * en un {@code group by}.
 *
 * <h2>Que significa el numero</h2>
 *
 * <p><b>Depende del tributo</b>, exactamente como en la columna: el predial se divide en cuatro
 * cuotas trimestrales y los arbitrios en doce mensuales. El numero 0 es el periodo <b>anual</b>: la
 * obligacion que no se divide, que es como se imputa el predial cuando se paga al contado y como
 * llegan alcabala, patrimonio vehicular y espectaculos.
 *
 * <p>Este tipo no decide cuantas divisiones admite cada tributo —eso es del contexto que emite la
 * deuda—; solo garantiza que el numero cae en el rango que la columna admite.
 */
public record Periodo(Ejercicio ejercicio, int numero) implements Comparable<Periodo> {

    /** El periodo que no divide el ejercicio. */
    public static final int ANUAL = 0;

    /** Doce: el maximo que admite la division mas fina del sistema, la mensual. */
    private static final int MAXIMO = 12;

    public Periodo {
        Objects.requireNonNull(ejercicio, "El periodo necesita su ejercicio");
        if (numero < ANUAL || numero > MAXIMO) {
            throw new IllegalArgumentException(
                    "Numero de periodo fuera de rango: "
                            + numero
                            + ". Se admite de "
                            + ANUAL
                            + " (anual) a "
                            + MAXIMO);
        }
    }

    /** La obligacion del ejercicio completo, sin dividir. */
    public static Periodo anual(Ejercicio ejercicio) {
        return new Periodo(ejercicio, ANUAL);
    }

    /** Una division del ejercicio: la cuota del predial o el mes de los arbitrios. */
    public static Periodo cuota(Ejercicio ejercicio, int numero) {
        if (numero == ANUAL) {
            throw new IllegalArgumentException(
                    "La cuota 0 no existe: el periodo anual se construye con anual(ejercicio)");
        }
        return new Periodo(ejercicio, numero);
    }

    public boolean esAnual() {
        return numero == ANUAL;
    }

    @Override
    public int compareTo(Periodo otro) {
        int porEjercicio = ejercicio.compareTo(otro.ejercicio);
        return porEjercicio != 0 ? porEjercicio : Integer.compare(numero, otro.numero);
    }

    @Override
    public String toString() {
        return esAnual() ? ejercicio.toString() : ejercicio + "-" + numero;
    }
}
