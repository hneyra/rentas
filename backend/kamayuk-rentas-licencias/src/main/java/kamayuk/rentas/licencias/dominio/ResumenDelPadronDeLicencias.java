package kamayuk.rentas.licencias.dominio;

/**
 * Lo que el padron de licencias cuenta, sobre <b>todas</b> las licencias del criterio (#54,
 * RF-115).
 *
 * <p>Existe como tipo aparte, y se calcula en la base con un agregado, por un motivo concreto:
 * contar la <b>pagina devuelta</b> daria una cifra que parece un total y no lo es. Es el defecto
 * que #25 destapo en la consulta unificada, donde el resumen decia 300,00 donde debia decir 1
 * 220,00 —la cuarta parte de la deuda, en la cifra que se lee en ventanilla—, y que #51 volvio a
 * cazar en el padron de anuncios.
 *
 * <p><b>Los tres estados salen de la misma fecha de corte</b> (regla 9, RNF-075). «Vencida» no es
 * un hecho de la licencia sino una relacion entre su vigencia y un dia, y «cancelada» depende de si
 * su resolucion ya se habia dictado ese dia. Un padron con corte en marzo y otro con corte en
 * diciembre cuentan cosas distintas, y los dos tienen que decir de cuando son. El {@code Padron}
 * que transporta este resumen lleva su fecha al lado.
 *
 * <p>Ninguna cifra de dinero: una licencia de funcionamiento no lleva importes. Lo que se cobro por
 * ella es el derecho de tramite, esta en su recibo y lo suma el resumen anual, que es otra cosa.
 *
 * @param licencias cuantas encuentra el criterio, en total
 * @param vigentes cuantas de ellas estaban vigentes a la fecha de corte
 * @param vencidas cuantas habian pasado su plazo sin estar canceladas
 * @param canceladas cuantas tenian resolucion de cancelacion a esa fecha
 */
public record ResumenDelPadronDeLicencias(
        long licencias, long vigentes, long vencidas, long canceladas) {

    public ResumenDelPadronDeLicencias {
        if (licencias < 0 || vigentes < 0 || vencidas < 0 || canceladas < 0) {
            throw new IllegalArgumentException(
                    "Un padron no cuenta menos de cero licencias: " + licencias);
        }
        if (vigentes + vencidas + canceladas != licencias) {
            throw new IllegalArgumentException(
                    "Las "
                            + licencias
                            + " licencias del padron tienen que repartirse entre los tres estados,"
                            + " y llegaron "
                            + vigentes
                            + " vigentes, "
                            + vencidas
                            + " vencidas y "
                            + canceladas
                            + " canceladas: un reparto que no suma significa que el estado se"
                            + " derivo dos veces con criterios distintos");
        }
    }

    /** Ninguna licencia encontrada. */
    public static ResumenDelPadronDeLicencias vacio() {
        return new ResumenDelPadronDeLicencias(0, 0, 0, 0);
    }
}
