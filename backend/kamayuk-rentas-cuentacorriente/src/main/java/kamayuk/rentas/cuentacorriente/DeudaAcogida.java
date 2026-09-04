package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una cuota que {@link AcogimientoAConvenio} movio —o puede mover— a fase de convenio, con la fase
 * en la que estaba y cuanto debia a la fecha de corte (#35, RF-084).
 *
 * <h2>Por que la granularidad es la cuota y no la obligacion</h2>
 *
 * <p>{@link SeleccionDeObligacion} llega con la granularidad de la ventanilla —«predial 2026 del
 * predio 7»—, pero el libro cuenta por cuota y <b>cada cuota puede estar en una fase distinta</b>:
 * una en coactiva, dos ordinarias. Acoger la obligacion entera a una sola fase dejaria la cuota
 * coactiva sin sustento, y devolverla al quebrar el convenio no sabria a donde. Por eso lo que
 * cruza la frontera es una fila por cuota.
 *
 * <h2>{@code faseOrigen} es opaca a proposito</h2>
 *
 * <p>Viaja como texto y no como {@code Fase}: ese tipo vive en {@code .dominio} y no cruza el
 * limite del modulo (ARQ-01 §4). Y no hace falta que lo cruce: quien acoge no tiene que
 * <b>interpretar</b> la fase, solo guardarla y devolverla tal cual cuando el convenio se cierre. Es
 * el mismo trato que ya recibe {@code tributo}, un texto que tesoreria transporta sin saber que
 * significa.
 *
 * <p>Interpretarla —«esto venia de coactiva, luego el expediente sigue vivo»— es cosa de este
 * contexto, y aqui se vuelve a convertir en {@code Fase} al asentar.
 *
 * @param tributo el tributo de la cuota, tal como lo nombra el libro
 * @param ejercicio el ejercicio de la obligacion
 * @param periodo la cuota o el mes; 0 es «anual», igual que en la proyeccion del saldo
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param faseOrigen en que fase estaba la cuota antes de acogerse; se guarda y se devuelve tal cual
 * @param fecha la fecha de corte con la que se leyo el desglose (regla 9, RNF-075)
 * @param insoluto el tributo determinado, sin reajuste ni interes
 * @param reajuste el reajuste
 * @param interes el interes moratorio
 * @param gasto los gastos
 */
public record DeudaAcogida(
        String tributo,
        Ejercicio ejercicio,
        int periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String faseOrigen,
        LocalDate fecha,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto) {

    /** {@code periodo smallint}: de 0 (anual) a 12 (la division mas fina, la mensual). */
    private static final int PERIODO_MAXIMO = 12;

    public DeudaAcogida {
        Objects.requireNonNull(tributo, "La deuda acogida necesita su tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La deuda acogida necesita su ejercicio");
        if (periodo < 0 || periodo > PERIODO_MAXIMO) {
            throw new IllegalArgumentException(
                    "Periodo fuera de rango: "
                            + periodo
                            + ". Se admite de 0 (anual) a "
                            + PERIODO_MAXIMO);
        }
        Objects.requireNonNull(faseOrigen, "Sin la fase de origen no se puede devolver la deuda");
        faseOrigen = faseOrigen.strip().toUpperCase(Locale.ROOT);
        if (faseOrigen.isEmpty()) {
            throw new IllegalArgumentException("La fase de origen no puede estar vacia");
        }
        Objects.requireNonNull(fecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        if (predioId != null && vehiculoId != null) {
            throw new IllegalArgumentException(
                    "Una obligacion es de un predio o de un vehiculo, no de los dos");
        }
    }

    /** La suma de las cuatro partes, nunca una quinta cifra calculada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
