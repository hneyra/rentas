package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Set;

/**
 * Por que se asienta: los diez valores que admite {@code cuenta_corriente_asiento_concepto_check}
 * (V2), en el mismo orden.
 *
 * <p>Si algun dia aparece un concepto mas, la insercion fallaria en tiempo de ejecucion, que es
 * tarde; agregarlo aqui sin agregarlo al {@code CHECK} de la base falla igual de tarde, en sentido
 * contrario. Los dos sitios se tocan juntos, y el diff lo muestra.
 */
public enum Concepto {
    INSOLUTO,
    REAJUSTE,
    INTERES,
    GASTO,
    PAGO,
    COMPENSACION,
    ANULACION,
    CONDONACION,
    AJUSTE,
    FRACCIONAMIENTO;

    /**
     * Los tres que alteran la deuda <b>sin que medie cobro</b>: exigen {@code motivo}, por {@code
     * asiento_motivo_ck} (RNF-052).
     *
     * <p>En la practica todo asiento lleva su {@code motivo}, porque {@link Asiento#reversionDe} y
     * {@code RegistrarAsiento} lo llenan siempre con la {@code Observacion} de quien lo asienta
     * (regla 10). Esta lista es la que decide cuando la base lo <b>exige</b>, no cuando se escribe.
     */
    private static final Set<Concepto> EXIGEN_MOTIVO = Set.of(ANULACION, CONDONACION, AJUSTE);

    public boolean exigeMotivo() {
        return EXIGEN_MOTIVO.contains(this);
    }
}
