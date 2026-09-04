package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;

/**
 * Los efectos de una transferencia sobre la titularidad de un predio, para otro contexto acotado
 * (ARQ-01 §2, la arista {@code catastro} ──► {@code rentas}; #29).
 *
 * <p>Vive en el paquete raiz de {@code catastro}, que es su API publica (ARQ-01 §4, regla 1). Lo
 * que expone es deliberadamente poco: quien orquesta una transferencia no necesita saber que la
 * comprobacion de que los porcentajes no exceden 100 es un disparador diferido, ni que cerrar y
 * abrir tienen que ir en la misma transaccion (DAT-01 §4.2) — eso es exactamente lo que este tipo
 * hace por quien lo llama.
 */
public interface GestorDeTitularidad {

    /**
     * La cuota vigente de un contribuyente sobre un predio, si tiene alguna a esa fecha.
     *
     * <p>Es el primer paso de toda transferencia de predio: sin esto, quien orquesta no sabe cuanto
     * tiene el transferente ni cual es la fila que hay que cerrar.
     */
    Optional<CuotaDeTitularidad> vigenteDe(long predioId, long contribuyenteId, LocalDate fecha);

    /**
     * Transfiere una cuota: cierra la titularidad indicada el dia anterior a {@code fecha} y abre
     * una para el adquiriente por {@code porcentajeTransferido}. Si el transferente conserva un
     * remanente —la transferencia es parcial—, tambien abre una titularidad para el por la
     * diferencia. Las tres escrituras van en la misma transaccion: el disparador diferido de
     * titularidad tolera que el total intermedio quede por encima de 100 mientras la anterior ya se
     * cerro y las nuevas todavia no se abrieron del todo (DAT-01 §4.2).
     *
     * <p><b>Ninguna fila se borra</b> (regla 4): la titularidad anterior queda en la base, cerrada.
     *
     * @return la cuota nueva del adquiriente
     * @throws IllegalArgumentException si {@code porcentajeTransferido} excede lo que tiene la
     *     titularidad indicada
     */
    CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion);
}
