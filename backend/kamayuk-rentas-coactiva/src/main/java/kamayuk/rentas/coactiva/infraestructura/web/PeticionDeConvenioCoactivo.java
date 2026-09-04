package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/coactiva/convenios} (RF-105). <b>Lista blanca</b>: lo que no
 * esta aqui no entra.
 *
 * <p><b>No hay ningun importe, ni el interes, ni el maximo de cuotas</b>, exactamente por lo mismo
 * que {@code PeticionDeFraccionamiento} de tesoreria: el cuanto lo resuelve {@code cuentacorriente}
 * releyendo su libro a la fecha de corte, y el interes y el maximo salen del conjunto sellado
 * (regla 5, D-02b). La pantalla muestra «Deuda total (S/)» y «Monto de cuota (S/)» como campos
 * <b>de salida</b>: los devuelve la simulacion.
 *
 * <p><b>Tampoco hay contribuyente.</b> Sale del expediente. Admitirlo por separado permitiria
 * fraccionar la deuda de una persona bajo el expediente de otra.
 *
 * <p>Y no hay <b>beneficio aplicable</b>: el filtro existe en la pantalla, pero el efecto de un
 * beneficio sobre el importe es D-02b (#191) y admitirlo aqui haria creer que se aplica.
 *
 * @param nroExpedCoact el numero impreso del expediente
 * @param fecha el dia del convenio, en ISO; si falta, hoy
 * @param fechaDeCorte a que fecha se lee la deuda que se acoge; si falta, la del convenio
 * @param nroDeCuotas cuantas cuotas se piden, sin contar la inicial
 * @param cuotaInicial el porcentaje de cuota inicial, en tanto por ciento
 * @param primeraCuotaVence cuando vence la primera cuota, en ISO
 * @param resolucion la resolucion que lo aprueba, si consta
 * @param obligaciones las deudas marcadas en la grilla
 * @param simular si es {@code true} no registra nada: solo devuelve el cronograma
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeConvenioCoactivo(
        @Nullable String nroExpedCoact,
        @Nullable String fecha,
        @Nullable String fechaDeCorte,
        @Nullable Integer nroDeCuotas,
        @Nullable String cuotaInicial,
        @Nullable String primeraCuotaVence,
        @Nullable String resolucion,
        @Nullable List<PeticionDeObligacionAcogida> obligaciones,
        @Nullable Boolean simular,
        @Nullable String observacion) {

    /**
     * Una deuda marcada para acoger: la identifica, no la valora.
     *
     * @param tributo el tributo
     * @param ejercicio el ano
     * @param predioId la unidad, si la obligacion es predial o de arbitrios
     * @param vehiculoId la unidad, si la obligacion es vehicular
     */
    public record PeticionDeObligacionAcogida(
            @Nullable String tributo,
            @Nullable Integer ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId) {}
}
