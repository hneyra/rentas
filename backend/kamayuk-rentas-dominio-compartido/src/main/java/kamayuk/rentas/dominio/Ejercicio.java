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
     *
     * <p><b>Pero quien es el sujeto no se lee a este dia</b>, sino a {@link
     * #fechaDeLaTitularidad()}: una transferencia fechada el mismo 1 de enero ya figura en el
     * padron a este dia, y no cambia al obligado del ejercicio.
     */
    public LocalDate primerDia() {
        return LocalDate.of(valor, 1, 1);
    }

    /**
     * El dia al que se lee <b>quien</b> es el sujeto del impuesto del ejercicio y con que cuota: el
     * 31 de diciembre del año anterior (#328).
     *
     * <p>La situacion juridica «configurada al 1 de enero» (TUO LTM art. 10, primer parrafo) es la
     * de <b>antes</b> de cualquier transferencia de ese dia, porque el segundo parrafo del mismo
     * articulo lo dice sin excepcion: «cuando se efectue cualquier transferencia, el adquirente
     * asume la condicion de contribuyente a partir del 1 de enero del año siguiente de producido el
     * hecho» —la misma regla que el art. 31 da para el vehicular, que es el que el corpus de {@code
     * normativa} transcribe ({@code vehicular-valores-referenciales-2026.md})—. Una venta fechada
     * el 2026-01-01 es un hecho de 2026: el comprador asume en 2027 y 2026 sigue siendo del
     * vendedor. Una fechada el 2025-12-31 es un hecho de 2025: el comprador asume en 2026.
     *
     * <p>Leer la titularidad a {@link #primerDia()} da lo contrario en el primer caso, porque el
     * padron cierra la cuota anterior <b>el dia antes</b> de la transferencia ({@code
     * GestorDeTitularidad} de {@code catastro}): con la venta del 1 de enero, al 1 de enero ya
     * consta el comprador. Al 31 de diciembre del año anterior, en cambio, las dos ventas caen del
     * lado que la ley manda. Las caracteristicas del predio no tienen esa regla y se siguen leyendo
     * a {@link #primerDia()}.
     *
     * <p>Se calcula sin pasar por {@link #anterior()} a proposito: el ejercicio minimo tambien
     * tiene titulares, y su año anterior no es un ejercicio admitido.
     */
    public LocalDate fechaDeLaTitularidad() {
        return primerDia().minusDays(1);
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
