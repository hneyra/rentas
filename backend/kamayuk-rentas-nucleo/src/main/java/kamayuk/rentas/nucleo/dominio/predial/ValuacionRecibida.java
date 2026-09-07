package kamayuk.rentas.nucleo.dominio.predial;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * La valuacion que {@code catastro} sello para ese predio en ese ejercicio (#38, AC-1).
     *
     * <p>Hasta este issue no habia forma de pedirla. La tabla se escribia, se contaba y se le
     * calculaba una huella agregada, y <b>ni una consulta leia una cifra</b>: el candado de emision
     * exigia que llegaran todas y la determinacion calculaba con el autovaluo declarado, de modo
     * que una valuacion completa y una incompleta producian exactamente el mismo recibo.
     *
     * <p>Vacio significa que ese predio no tiene valuacion de ese ejercicio. <b>No</b> significa
     * que valga cero, y por eso {@link ValuacionSellada} obliga a traer la cifra o el motivo.
     */
    Optional<ValuacionSellada> delPredio(Ejercicio ejercicio, long predioId);

    /**
     * Las valuaciones selladas de varios predios de una vez, por su identificador.
     *
     * <p>Existe para que la determinacion no pregunte una vez por predio dentro de un bucle: un
     * contribuyente con tres predios son tres viajes, y la corrida masiva recorre el padron entero.
     * Es la misma razon por la que {@code PublicadorDeNormativa} no sabe contestar por partida
     * (P5B).
     *
     * <p>Los predios que no tengan valuacion sencillamente no salen en el mapa. Una entrada con
     * cifras nulas seria un cero disfrazado.
     */
    Map<Long, ValuacionSellada> deLosPredios(Ejercicio ejercicio, List<Long> predioIds);

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
