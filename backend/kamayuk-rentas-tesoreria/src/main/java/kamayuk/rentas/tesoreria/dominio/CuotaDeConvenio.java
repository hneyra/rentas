package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una cuota del cronograma, congelada (V31, #35).
 *
 * <p>El desglose se guarda parte por parte y no como un total, por el mismo motivo que {@code
 * recibo_detalle} (V29) y {@code valor_detalle} (V3): el compromiso de pago que el contribuyente
 * firma tiene que poder explicar su cifra sin volver a calcular nada. Recomponerla mas adelante
 * —con otro interes, con otros parametros— daria un papel distinto cada vez.
 *
 * <p>El total es la <b>suma de las tres partes</b>, nunca una cuarta cifra calculada aparte; {@code
 * convenio_cuota_desglose_ck} lo comprueba tambien en la base.
 *
 * <p>{@link #gasto} esta hoy siempre en cero y no es un olvido: el gasto administrativo del
 * convenio —«Gasto.Conv.» y «Gasto.Cuota» en la pantalla— es una cifra de ordenanza local, y por
 * tanto D-02b (#191). Cobrar uno inventado seria un cobro sin sustento normativo repetido en toda
 * la cartera fraccionada. La columna existe para cuando la ordenanza este firmada.
 *
 * @param numero el orden en el cronograma; <b>0 es la cuota inicial</b>
 * @param vencimiento cuando vence
 * @param capital la parte de deuda acogida que amortiza
 * @param interes el interes de fraccionamiento de esta cuota
 * @param gasto el gasto administrativo; cero mientras D-02b siga abierta
 */
public record CuotaDeConvenio(
        int numero, LocalDate vencimiento, Dinero capital, Dinero interes, Dinero gasto) {

    public CuotaDeConvenio {
        if (numero < 0) {
            throw new IllegalArgumentException(
                    "La cuota inicial es la 0 y las demas van desde 1; llego " + numero);
        }
        Objects.requireNonNull(vencimiento, "Una cuota vence en una fecha concreta");
        Objects.requireNonNull(capital, "El desglose siempre trae sus tres partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus tres partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus tres partes");
        if (capital.esNegativo() || interes.esNegativo() || gasto.esNegativo()) {
            throw new IllegalArgumentException(
                    "Una cuota no se cobra en negativo: eso es una devolucion, y se documenta"
                            + " aparte");
        }
    }

    /** El total de la cuota: la suma de sus tres partes. */
    public Dinero monto() {
        return capital.mas(interes).mas(gasto);
    }

    /**
     * Si la cuota ya venció a {@code fecha} (#411).
     *
     * <p><b>El día del vencimiento todavía se puede pagar</b>: vence al día siguiente. Es el mismo
     * criterio que {@code Exigibilidad} escribió para cualquier plazo —«el día en que vence,
     * tampoco»—. Hasta #411 el listado y la ficha contaban la cuota como vencida ese mismo día, e
     * invitaban a tratar como incumplido a quien todavía estaba a tiempo.
     *
     * <p>Su copia en SQL es {@link #vencidaEnSql}, y no hay otra.
     */
    public boolean vencidaA(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Vencida a que fecha: toda cifra dice la suya (regla 9)");
        return vencimiento.isBefore(fecha);
    }

    /**
     * {@link #vencidaA} escrita en SQL, para una fila de {@code convenio_cuota} con alias {@code
     * alias} contra el parámetro con nombre {@code parametro} (#411): la misma comparación
     * estricta, y junto a la Java para que no puedan divergir sin que se vea.
     *
     * @param alias el alias de {@code convenio_cuota} en la consulta
     * @param parametro el nombre, sin los dos puntos, del parámetro con la fecha
     */
    public static String vencidaEnSql(String alias, String parametro) {
        return "(" + alias + ".vencimiento < :" + parametro + ")";
    }

    /** La cuota inicial es la 0: la que se cobra en caja y formaliza el convenio. */
    public boolean esInicial() {
        return numero == 0;
    }
}
