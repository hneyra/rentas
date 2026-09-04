package kamayuk.rentas.dominio;

import java.time.LocalDate;

/**
 * Ejercicio tributario: el año al que pertenece una obligacion.
 *
 * <p>Es un tipo propio y no un {@code int} por dos motivos que aparecen a diario en este dominio:
 *
 * <ul>
 *   <li>El ejercicio es la <b>clave de particion</b> de la determinacion, del libro de asientos y
 *       de la auditoria (ADR-0004). Confundirlo con un periodo o con un año calendario cualquiera
 *       tiene consecuencias fisicas en la base.
 *   <li>Toda regla tributaria se evalua <b>con los parametros de su ejercicio</b> (ADR-0007). Un
 *       ejercicio que viaja como entero suelto se acaba tomando de {@code LocalDate.now()}, y
 *       entonces recalcular 2027 en 2037 da otra cifra.
 * </ul>
 *
 * <p>El rango admitido es el mismo que el dominio {@code ejercicio} de PostgreSQL, para que la
 * restriccion no dependa de cual de las dos capas se revisa.
 */
public record Ejercicio(int valor) implements Comparable<Ejercicio> {

    private static final int ANIO_MINIMO = 1990;
    private static final int ANIO_MAXIMO = 2100;

    public Ejercicio {
        if (valor < ANIO_MINIMO || valor > ANIO_MAXIMO) {
            throw new IllegalArgumentException(
                    "Ejercicio fuera de rango: "
                            + valor
                            + ". Se admite de "
                            + ANIO_MINIMO
                            + " a "
                            + ANIO_MAXIMO);
        }
    }

    /**
     * Ejercicio al que pertenece una fecha.
     *
     * <p>La fecha entra como argumento a proposito: ningun metodo de este dominio consulta el reloj
     * (regla 6 de ARQ-04 §2).
     */
    public static Ejercicio de(LocalDate fecha) {
        return new Ejercicio(fecha.getYear());
    }

    /**
     * El 1 de enero del ejercicio.
     *
     * <p>No es un detalle de formato: es <b>la fecha del ejercicio</b> en este dominio. El caracter
     * de sujeto del impuesto se atribuye con arreglo a la situacion juridica configurada al 1 de
     * enero del año al que corresponde la obligacion (TUO LTM, art. 10), y el minimo imponible del
     * predial se calcula sobre «la UIT vigente al 1 de enero del año al que corresponde el
     * impuesto» (art. 13, ultimo parrafo). Cuando hay que resolver que valor normativo rige un
     * ejercicio, este es el dia contra el que se compara.
     */
    public LocalDate primerDia() {
        return LocalDate.of(valor, 1, 1);
    }

    /** El 31 de diciembre del ejercicio. */
    public LocalDate ultimoDia() {
        return LocalDate.of(valor, 12, 31);
    }

    public Ejercicio anterior() {
        return new Ejercicio(valor - 1);
    }

    public Ejercicio siguiente() {
        return new Ejercicio(valor + 1);
    }

    @Override
    public int compareTo(Ejercicio otro) {
        return Integer.compare(valor, otro.valor);
    }

    @Override
    public String toString() {
        return Integer.toString(valor);
    }
}
