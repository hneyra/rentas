package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una linea del recibo: lo que se cobro y por que concepto (V3, V29).
 *
 * <p><b>Congelada.</b> El desglose se guarda parte por parte y no como un total, por el mismo
 * motivo que {@code valor_detalle}: dentro de dos anios el libro dira otra cosa —habra mas
 * asientos— y el duplicado tiene que salir identico al original. Recomponer la cifra volviendo a
 * consultar el libro daria un papel distinto cada vez.
 *
 * <p>El total es la <b>suma de las cuatro partes</b>, nunca una quinta cifra calculada aparte;
 * {@code recibo_detalle_desglose_ck} lo comprueba en la base tambien. Una linea de tasa lleva su
 * importe integro en {@code insoluto}: un derecho de tramite no tiene reajuste, ni interes
 * moratorio, ni gastos de cobranza.
 *
 * @param tributo el tributo cobrado, o el codigo de la tasa
 * @param concepto {@code PAGO} en caja tributaria, {@code TASA} en caja de tasas
 * @param ejercicio el ejercicio de la obligacion; nulo en una tasa
 * @param periodo la cuota, si la linea es de una sola; nulo cuando agrega todas las del ejercicio
 * @param tasaId la tasa del TUPA, si la linea es de caja de tasas
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param referenciaExterna origen no tributario (papeleta, licencia), sin clave foranea
 * @param cantidad cuantas veces se cobro la tasa; nulo si la linea no es de tasa
 * @param precioUnitario la tarifa vigente que se aplico; nulo si la linea no es de tasa
 * @param insoluto la parte de tributo determinado
 * @param reajuste la parte de reajuste
 * @param interes la parte de interes moratorio
 * @param gasto la parte de gastos
 */
public record LineaDeRecibo(
        String tributo,
        String concepto,
        @Nullable Ejercicio ejercicio,
        @Nullable Integer periodo,
        @Nullable Long tasaId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable String referenciaExterna,
        @Nullable Integer cantidad,
        @Nullable Dinero precioUnitario,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto) {

    public LineaDeRecibo {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(concepto, "La linea necesita su concepto");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        concepto = concepto.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        if (insoluto.esNegativo()
                || reajuste.esNegativo()
                || interes.esNegativo()
                || gasto.esNegativo()) {
            throw new IllegalArgumentException(
                    "Una linea de recibo no cobra en negativo: eso es una devolucion, y se"
                            + " documenta aparte");
        }
        boolean esTasa = tasaId != null;
        if (esTasa != (cantidad != null) || esTasa != (precioUnitario != null)) {
            throw new IllegalArgumentException(
                    "Una linea de tasa lleva su cantidad y su precio unitario, y una que no lo es"
                            + " no lleva ninguno de los dos");
        }
        if (esTasa && Objects.requireNonNull(cantidad) <= 0) {
            throw new IllegalArgumentException("La cantidad de una tasa es al menos 1");
        }
    }

    /** El total de la linea: la suma de sus cuatro partes. */
    public Dinero monto() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
