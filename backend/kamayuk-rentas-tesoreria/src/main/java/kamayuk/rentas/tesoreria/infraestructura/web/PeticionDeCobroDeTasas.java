package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/tesoreria/caja/tasas} (RF-081). <b>Lista blanca</b>: lo que no
 * esta aqui no entra.
 *
 * <p>Tampoco hay importes: el precio de cada concepto sale de la tabla {@code tasa}, vigente a la
 * fecha del cobro (regla 5). Lo que el cajero elige es <b>que</b> y <b>cuantas veces</b>.
 *
 * @param caja el codigo de la ventanilla
 * @param cajero quien cobra
 * @param codContribuyente el codigo del contribuyente, como lo escribe la pantalla
 * @param formaDePago EFECTIVO, CHEQUE, DEPOSITO, TARJETA o TRANSFERENCIA
 * @param fechaDeCobro la fecha a la que se resuelve la tarifa vigente, en ISO; si falta, hoy
 * @param conceptos los del TUPA, con su cantidad
 * @param observacion por que se cobra (regla 10)
 */
public record PeticionDeCobroDeTasas(
        @Nullable String caja,
        @Nullable String cajero,
        @Nullable String codContribuyente,
        @Nullable String formaDePago,
        @Nullable String fechaDeCobro,
        @Nullable List<PeticionDeConcepto> conceptos,
        @Nullable String observacion) {

    /**
     * Un concepto del TUPA marcado.
     *
     * @param conceptoTupa el codigo del concepto
     * @param cantidad cuantas veces se cobra; si falta, 1
     */
    public record PeticionDeConcepto(@Nullable String conceptoTupa, @Nullable Integer cantidad) {}
}
