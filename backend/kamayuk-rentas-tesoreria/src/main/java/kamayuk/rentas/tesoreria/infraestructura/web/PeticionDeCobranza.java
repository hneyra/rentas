package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/tesoreria/caja/cobranza} (RF-080). <b>Lista blanca</b>: lo que
 * no esta aqui no entra.
 *
 * <p><b>No hay ningun importe.</b> Ni total, ni por linea, ni un descuento. El cuanto lo resuelve
 * {@code cuentacorriente} releyendo su libro a la fecha de pago (ARQ-01 §3.8), y admitir aqui una
 * cifra seria admitir que el cliente decida cuanto se cobra.
 *
 * <p>{@code beneficioAplicable} si entra, y se guarda en el recibo tal cual: es la campana que el
 * cajero declaro. Su <b>efecto</b> sobre el importe esta bloqueado por D-02b, asi que hoy se cobra
 * el integro y el campo es constancia. Aceptarlo desde ya es lo que permite que el dia que D-02b
 * cierre el historico diga que campana se invoco en cada ventanilla.
 *
 * @param caja el codigo de la ventanilla
 * @param cajero quien cobra
 * @param codContribuyente el codigo del contribuyente, como lo escribe la pantalla
 * @param formaDePago EFECTIVO, CHEQUE, DEPOSITO, TARJETA o TRANSFERENCIA
 * @param tipoDePago que clase de cobranza es; si falta, NORMAL
 * @param beneficioAplicable la campana declarada; solo constancia (D-02b)
 * @param fechaDePago la fecha a la que se relee la deuda, en ISO; si falta, hoy
 * @param obligaciones las deudas marcadas en la grilla
 * @param numeroDeConvenio el convenio cuya cuota inicial se cobra; <b>solo</b> con {@code
 *     tipoDePago = PRECONVENIO}, y obligatorio en ese caso (#35, RF-084). El importe sigue sin
 *     entrar: lo dice el cronograma congelado del convenio
 * @param observacion por que se cobra (regla 10)
 */
public record PeticionDeCobranza(
        @Nullable String caja,
        @Nullable String cajero,
        @Nullable String codContribuyente,
        @Nullable String formaDePago,
        @Nullable String tipoDePago,
        @Nullable String beneficioAplicable,
        @Nullable String fechaDePago,
        @Nullable List<PeticionDeObligacion> obligaciones,
        @Nullable String numeroDeConvenio,
        @Nullable String observacion) {

    /**
     * Una deuda marcada: la identifica, no la valora.
     *
     * @param tributo el tributo
     * @param ejercicio el ano
     * @param predioId la unidad, si la obligacion es predial o de arbitrios
     * @param vehiculoId la unidad, si la obligacion es vehicular
     */
    public record PeticionDeObligacion(
            @Nullable String tributo,
            @Nullable Integer ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId) {}
}
