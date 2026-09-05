package kamayuk.rentas.nucleo.dominio.predial;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Lo que {@code catastro} dice de la valuacion de un ejercicio, y lo que de ella llego (ADR-0027).
 *
 * <p>Es el puerto del <b>candado antes de emitir</b>. Devuelve dos cosas que hay que comparar y no
 * una sola compuesta, a proposito: lo que la corrida <b>declaro</b> —su conteo y su huella, tal
 * como catastro los emitio— y lo que de verdad <b>esta</b> en esta base. Componer la comparacion
 * aqui dejaria a quien la lee sin poder decir cuantas faltan.
 */
public interface ValuacionRecibida {

    /** El cierre de la corrida del ejercicio, si llego. */
    Optional<CierreDeCorrida> cierreDe(Ejercicio ejercicio);

    /** Cuantas valuaciones de ese ejercicio hay proyectadas en esta base. */
    long valuacionesRecibidasDe(Ejercicio ejercicio);

    /**
     * La huella agregada de lo recibido, calculada aqui sobre lo que hay.
     *
     * <p>Se calcula sobre las huellas de cada valuacion y no sobre sus cifras: lo que se compara es
     * que llegaron LOS MISMOS HECHOS, no que las cuentas cuadren — de eso ya responde cada huella
     * individual, que catastro firmo.
     */
    String huellaDeLoRecibido(Ejercicio ejercicio);

    /**
     * @param corridaId el identificador de la corrida en {@code catastro}
     * @param conjuntoId el conjunto de parametros que LA CORRIDA fijo (ADR-0027 §2)
     * @param conteo cuantas valuaciones dice catastro que emitio
     * @param huella la huella agregada que catastro emitio
     */
    record CierreDeCorrida(
            long corridaId,
            long conjuntoId,
            LocalDate fechaDeCorte,
            String reglasVersion,
            int conteo,
            String huella) {}
}
