package kamayuk.rentas.tesoreria.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El documento que se entrega en ventanilla (V3, V29, RF-080, RF-081).
 *
 * <h2>No se edita</h2>
 *
 * <p>V29 le retira a {@code sgtm_app} el privilegio de {@code UPDATE} sobre {@code recibo} y sobre
 * {@code recibo_detalle}, y el escaner de fuentes rechaza cualquier {@code UPDATE recibo SET} antes
 * de que llegue a ejecutarse. No es purismo: un recibo es un documento con numeracion correlativa
 * que el contribuyente se lleva impreso, y corregirlo en el sitio deja al papel y a la base
 * diciendo cosas distintas sin que nada lo delate. La anulacion (#34) se registrara como un
 * movimiento que se <b>agrega</b>, igual que {@code valor_movimiento} en V28.
 *
 * <h2>Toda cifra con su fecha</h2>
 *
 * <p>{@link #actualizadoA} es la fecha a la que estaban actualizados los importes que cobro (regla
 * 9, RNF-075). En caja tributaria es la fecha de pago con la que se releyo {@code
 * deudaActualizadaA}; en caja de tasas, la fecha a la que la tarifa del TUPA estaba vigente. Sin
 * ella, el duplicado de un recibo de marzo no podria explicar por que su interes no es el de hoy.
 *
 * @param id nulo mientras no se haya guardado
 * @param numero la serie de la caja y su correlativo
 * @param cajaId la ventanilla que lo emitio
 * @param turnoId la apertura contra la que se cobro
 * @param cajero quien cobro
 * @param contribuyenteId a quien se le cobro
 * @param emitidoEn el instante de emision; sale del reloj inyectado, no de un {@code DEFAULT now()}
 * @param formaDePago con que se pago
 * @param tipoDePago que clase de cobranza es
 * @param campaniaBeneficio la campana declarada en ventanilla, si la hubo. <b>Solo constancia</b>:
 *     su efecto sobre el importe esta bloqueado por D-02b y aqui se cobra el integro
 * @param actualizadoA a que fecha estaban actualizados los importes
 * @param observacion por que se cobro, escrito por quien cobro (regla 10)
 * @param lineas el desglose congelado; nunca vacio
 */
public record Recibo(
        @Nullable Long id,
        NumeroDeRecibo numero,
        long cajaId,
        long turnoId,
        String cajero,
        long contribuyenteId,
        Instant emitidoEn,
        FormaDePago formaDePago,
        TipoDePago tipoDePago,
        @Nullable String campaniaBeneficio,
        LocalDate actualizadoA,
        Observacion observacion,
        List<LineaDeRecibo> lineas) {

    public Recibo {
        Objects.requireNonNull(numero, "Un recibo sin numero no es un recibo");
        Objects.requireNonNull(cajero, "El recibo dice quien cobro");
        Objects.requireNonNull(emitidoEn, "El recibo dice cuando se emitio");
        Objects.requireNonNull(formaDePago, "El recibo dice con que se pago");
        Objects.requireNonNull(tipoDePago, "El recibo dice que clase de cobranza es");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        Objects.requireNonNull(lineas, "El recibo lleva su desglose");
        cajero = cajero.strip();
        if (cajero.isEmpty()) {
            throw new IllegalArgumentException("El cajero no puede estar vacio");
        }
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un recibo sin lineas no documenta nada: no se emite");
        }
        lineas = List.copyOf(lineas);
        if (campaniaBeneficio != null && campaniaBeneficio.isBlank()) {
            campaniaBeneficio = null;
        }
    }

    /**
     * El total cobrado: la suma de las lineas, nunca una cifra aparte.
     *
     * <p>Que se calcule y no se guarde como campo independiente es lo que impide que el total del
     * papel y su desglose puedan discrepar. En la base, {@code recibo.total} guarda esta misma suma
     * porque las consultas de recaudacion la necesitan sin recorrer el detalle.
     */
    public Dinero total() {
        Dinero total = Dinero.CERO;
        for (LineaDeRecibo linea : lineas) {
            total = total.mas(linea.monto());
        }
        return total;
    }
}
