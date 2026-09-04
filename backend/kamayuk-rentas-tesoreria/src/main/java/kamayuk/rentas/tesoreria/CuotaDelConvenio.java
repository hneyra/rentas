package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una fila del cronograma, tal como cruza la frontera del modulo (#42, RF-105).
 *
 * <p>La cuota <b>0</b> es la inicial: es la que hay que cobrar en caja para que el convenio deje de
 * ser un preconvenio.
 *
 * <p>El desglose viaja entero —capital, interes y gasto— y no solo el total, por lo mismo que
 * {@code ObligacionPublica} trae sus cuatro partes: quien imprime el cronograma tiene que poder
 * explicar la cifra sin volver a calcular nada. Recomponerla al dibujarla seria escribir por
 * segunda vez una cuenta que ya existe (RNF-083).
 *
 * @param numero el orden de la cuota; 0 es la inicial
 * @param vencimiento cuando vence
 * @param monto lo que se paga; es la suma de las otras tres
 * @param capital la parte de deuda acogida
 * @param interes el interes de fraccionamiento
 * @param gasto los gastos
 */
public record CuotaDelConvenio(
        int numero,
        LocalDate vencimiento,
        Dinero monto,
        Dinero capital,
        Dinero interes,
        Dinero gasto) {

    public CuotaDelConvenio {
        if (numero < 0) {
            throw new IllegalArgumentException("El numero de cuota no es negativo: " + numero);
        }
        Objects.requireNonNull(vencimiento, "La cuota vence en una fecha");
        Objects.requireNonNull(monto, "La cuota necesita su importe");
        Objects.requireNonNull(capital, "La cuota trae su desglose entero");
        Objects.requireNonNull(interes, "La cuota trae su desglose entero");
        Objects.requireNonNull(gasto, "La cuota trae su desglose entero");
    }

    /** Si es la cuota inicial, la que formaliza el convenio al cobrarse. */
    public boolean esInicial() {
        return numero == 0;
    }
}
