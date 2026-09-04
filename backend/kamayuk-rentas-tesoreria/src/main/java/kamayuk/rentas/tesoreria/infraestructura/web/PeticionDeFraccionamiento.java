package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/tesoreria/fraccionamientos} (RF-084). <b>Lista blanca</b>: lo
 * que no esta aqui no entra.
 *
 * <p><b>No hay ningun importe, ni el interes.</b> Ni el total acogido, ni el monto de la cuota, ni
 * el interes de fraccionamiento. El cuanto lo resuelve {@code cuentacorriente} releyendo su libro a
 * la fecha de corte, y el interes y el maximo de cuotas salen del conjunto sellado (regla 5,
 * D-02b). Admitirlos aqui seria admitir que el cliente decida cuanto se fracciona y a que precio.
 *
 * <p>La pantalla del prototipo muestra «Monto de Cuota (S/)» y «Interes de fraccionamiento» como
 * campos, y son <b>de salida</b>: los devuelve la simulacion. Que aqui no entren es lo que impide
 * que un cliente mal escrito —o uno que lo intenta— mande los suyos.
 *
 * @param codContribuyente el codigo del contribuyente, como lo escribe la pantalla
 * @param tipo ORDINARIO o COACTIVO; si falta, ORDINARIO
 * @param fecha el dia del convenio, en ISO; si falta, hoy
 * @param fechaDeCorte la fecha a la que se lee la deuda que se acoge; si falta, la del convenio
 * @param nroDeCuotas cuantas cuotas se piden, sin contar la inicial
 * @param cuotaInicial el porcentaje de cuota inicial, en tanto por ciento
 * @param primeraCuotaVence cuando vence la primera cuota, en ISO
 * @param tipoDeGarantia el ofrecimiento de garantia, si lo hubo; solo constancia (D-02b)
 * @param detalleDelOfrecimiento la descripcion del bien o documento ofrecido
 * @param resolucion la resolucion que aprueba el convenio, si consta
 * @param obligaciones las deudas marcadas en la grilla
 * @param simular si es {@code true} no registra nada: solo devuelve el cronograma
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeFraccionamiento(
        @Nullable String codContribuyente,
        @Nullable String tipo,
        @Nullable String fecha,
        @Nullable String fechaDeCorte,
        @Nullable Integer nroDeCuotas,
        @Nullable String cuotaInicial,
        @Nullable String primeraCuotaVence,
        @Nullable String tipoDeGarantia,
        @Nullable String detalleDelOfrecimiento,
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
