package kamayuk.rentas.tesoreria;

/**
 * El recibo ya respaldo todo lo que cobro por ese concepto (#383). Es un 409: la peticion esta
 * bien, y lo que no la admite es que ese pago ya se gasto.
 *
 * <p>La regla que decide —{@link #exigirSaldo}— vive aqui, junto a la excepcion que produce, y no
 * en el adaptador: asi la cuenta es la misma en la base y en cualquier doble de prueba, y el
 * mensaje dice siempre las mismas tres cifras.
 */
public final class ReciboYaAplicado extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private ReciboYaAplicado(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    private ReciboYaAplicado(String mensaje) {
        super(mensaje);
    }

    /**
     * Lo ya aplicado mas lo que este acto consume no puede pasar de lo cobrado.
     *
     * @param numeroDeRecibo el recibo, como esta impreso
     * @param concepto el concepto del TUPA
     * @param aplicadas las unidades que otros actos ya gastaron
     * @param unidadesQueConsume las que este acto necesita
     * @param unidadesCobradas las que el recibo cobro
     * @throws ReciboYaAplicado si no alcanza
     */
    public static void exigirSaldo(
            String numeroDeRecibo,
            String concepto,
            int aplicadas,
            int unidadesQueConsume,
            int unidadesCobradas) {
        if (unidadesQueConsume < 1 || unidadesCobradas < 1) {
            throw new IllegalArgumentException(
                    "Un acto consume al menos una unidad de un recibo que cobro al menos una;"
                            + " llegaron "
                            + unidadesQueConsume
                            + " de "
                            + unidadesCobradas);
        }
        if (aplicadas + unidadesQueConsume > unidadesCobradas) {
            throw new ReciboYaAplicado(
                    "El recibo "
                            + numeroDeRecibo
                            + " ya respaldo lo que cobro por el concepto "
                            + concepto
                            + ": cobro "
                            + unidades(unidadesCobradas)
                            + " y ya se aplicaron "
                            + aplicadas
                            + ", y este acto necesita "
                            + unidadesQueConsume
                            + " mas. Un pago respalda un acto por cada unidad que cobro: hace falta"
                            + " otro recibo");
        }
    }

    /**
     * Otra transaccion gasto el mismo saldo a la vez, y la base se lo dio a ella.
     *
     * <p>Es el choque en {@code recibo_aplicado_uq}, no un {@code if}: las dos leyeron el mismo
     * saldo y las dos lo creyeron suyo. Se dice aparte porque se arregla distinto —puede que al
     * recibo le quede saldo y baste con reintentar—.
     */
    public static ReciboYaAplicado porLaCarrera(
            String numeroDeRecibo, String concepto, Throwable causa) {
        return new ReciboYaAplicado(
                "El recibo "
                        + numeroDeRecibo
                        + " se estaba aplicando a otro acto por el concepto "
                        + concepto
                        + " en este mismo momento, y la base se lo dio a ese. Si al recibo le queda"
                        + " saldo, reintente; si no, hace falta otro recibo",
                causa);
    }

    private static String unidades(int cuantas) {
        return cuantas == 1 ? "1 unidad" : cuantas + " unidades";
    }
}
