package kamayuk.rentas.nucleo.infraestructura.web;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import org.jspecify.annotations.Nullable;

/**
 * La ultima corrida de emision, tal como sale por {@code GET /rentas/predial/corridas/ultima}
 * (#523).
 *
 * <p>Es la misma lectura que {@link CorridaPredialResource} publica en la respuesta del {@code
 * POST} que ejecuta la corrida, con dos diferencias que importan:
 *
 * <ul>
 *   <li><b>Lleva {@code id}</b>, que es con lo que la pantalla pide los observados. En la respuesta
 *       del {@code POST} no hacia falta —los traia dentro— y aqui si: son cientos y viajan aparte.
 *   <li><b>No trae los observados</b>, por lo mismo.
 * </ul>
 *
 * <p>Las etapas se componen igual que alli, y a proposito: dos formas distintas del mismo hecho
 * dirian dos cosas de la misma corrida, y la que se leyera al abrir la pantalla seria la que nadie
 * compara.
 *
 * <h2>Los dos agregados que se publican como campo, y por que hacia falta (#271)</h2>
 *
 * <p>{@link #determinados} y {@link #montoEmitido} son <b>columnas de {@code corrida_predial}</b>
 * —{@code determinados} y {@code monto_emitido}—: lo que la corrida escribio al terminar, no una
 * suma que nadie recalcula aqui. Viajaban solo dentro de {@link #etapas}, donde son un rotulo y una
 * celda de una tabla, y el panel del modulo los dibuja ademas <b>arriba</b>, como cifra suelta.
 *
 * <p>Sacarlos de la tabla en el navegador seria leer «Determinados» de la segunda fila y confiar en
 * que la fila siga siendo la segunda y siga llamandose asi. Son el mismo hecho, y por eso se
 * publica <b>una vez</b> y en los dos sitios se lee el mismo campo.
 *
 * <h2>Y el derecho de emision, que la corrida ya SELLA (#312, D-02b)</h2>
 *
 * <p>El panel dibuja un tercer campo —«Derecho de emision»— y hasta #312 este recurso <b>no lo
 * traia</b>, con cuatro motivos medidos en #271: {@code corrida_predial} no tenia columna para el,
 * lo unico que guardaba del conjunto era su <b>nombre</b> —{@code varchar(60)}, y la cadena vacia
 * en una corrida que no determino a nadie—, el unico camino restante era {@code
 * vigenteEn(ejercicio)} —el conjunto de <b>hoy</b>, que no tiene por que ser aquel— y esa lectura
 * ademas contestaria 422, porque {@code DERECHO_EMISION_PREDIAL} es de ordenanza local y no lo
 * publica nadie.
 *
 * <p>Lo que {@code V23} cambia es el primero, que es el unico que se podia arreglar aqui: la
 * corrida <b>sella</b> el derecho que aplico y el {@code conjunto_id} del que salio, el dia de la
 * emision. Asi que {@link #derechoDeEmision} no se lee de ningun conjunto: es la <b>columna</b>, y
 * viaja en texto y no en coma flotante (regla 1).
 *
 * <p><b>Y es anulable, que no es lo mismo que cero.</b> Nulo significa «esa corrida no lo guardo»
 * —es anterior a {@code V23}, o no determino a nadie y entonces no hubo conjunto que sellar—. Cero
 * significaria «no se cobro derecho de emision», que es falso: se cobro, y esta sumado dentro de
 * {@link #montoEmitido}. Son dos ausencias distintas y la pantalla las dice con palabras distintas
 * ({@code palabrasDeHueco.ts}).
 *
 * <p><b>Esto no cierra D-02b</b>, y conviene no leerlo asi: el valor efectivo lo fija la ordenanza
 * local y hoy sigue sin publicarlo nadie. Lo que se cierra es que la corrida lo aplicara y lo
 * olvidara.
 *
 * @param id el de la corrida, con el que se piden sus observados
 * @param ejercicio el ejercicio recalculado
 * @param alcance TODOS o SECTOR
 * @param sector cual, cuando el alcance es SECTOR
 * @param simulacion si la corrida no asento ninguna determinacion
 * @param conjunto el conjunto sellado con que se emitio (ARQ-09 §3)
 * @param conjuntoId el identificador de ese conjunto, con el que se vuelve a leer su cuadro; nulo
 *     si la corrida no lo sello. Es el mismo par —nombre e identificador— que publica {@code GET
 *     /rentas/predial/determinaciones}
 * @param fechaCalculo el dia al que corresponden sus cifras (regla 9)
 * @param determinados cuantas cuentas quedaron determinadas; en una simulacion, cuantas se
 *     simularon
 * @param montoEmitido lo que la corrida determino en total —impuesto mas derecho de emision—, en
 *     texto y no en coma flotante (regla 1)
 * @param derechoDeEmision el derecho de emision que la corrida aplico a cada cuenta, en texto y no
 *     en coma flotante (regla 1); <b>nulo</b> cuando esa corrida no lo sello, que no es cero
 * @param observados cuantos quedaron fuera; la lista se pide aparte
 * @param etapas el resumen por etapa, en el orden en que ocurrieron
 */
public record CorridaGuardadaResource(
        long id,
        String ejercicio,
        String alcance,
        @Nullable String sector,
        boolean simulacion,
        String conjunto,
        @Nullable Long conjuntoId,
        String fechaCalculo,
        int determinados,
        String montoEmitido,
        @Nullable String derechoDeEmision,
        int observados,
        List<CorridaPredialResource.Etapa> etapas) {

    private static final String ESTADO_OK = "OK";
    private static final String ESTADO_CON_OBSERVACIONES = "CON OBSERVACIONES";
    private static final String SIN_MONTO = "";

    public CorridaGuardadaResource {
        Objects.requireNonNull(ejercicio, "La corrida necesita su ejercicio");
        Objects.requireNonNull(montoEmitido, "La corrida necesita lo que emitio");
        etapas = List.copyOf(etapas);
    }

    /**
     * El derecho que la corrida sello, en texto, o {@code null} si no sello ninguno (#312).
     *
     * <p>El {@code null} se conserva hasta el JSON <b>a proposito</b>: {@code "0.00"} diria que no
     * se cobro derecho de emision, y de una corrida anterior a {@code V23} eso es falso —se cobro,
     * y esta dentro de {@code monto_emitido}—. Quien dibuja necesita poder distinguirlo.
     */
    @Nullable
    private static String derechoSellado(CorridaDeEmision corrida) {
        Dinero sellado = corrida.derechoDeEmision();
        return sellado == null ? null : sellado.toString();
    }

    public static CorridaGuardadaResource de(CorridaDeEmision corrida) {
        int fuera = corrida.leidos() - corrida.determinados();
        List<CorridaPredialResource.Etapa> etapas =
                List.of(
                        new CorridaPredialResource.Etapa(
                                "Padrón leído", corrida.leidos(), SIN_MONTO, 0, ESTADO_OK),
                        new CorridaPredialResource.Etapa(
                                corrida.simulacion() ? "Simulados" : "Determinados",
                                corrida.determinados(),
                                corrida.montoEmitido().toString(),
                                fuera,
                                fuera == 0 ? ESTADO_OK : ESTADO_CON_OBSERVACIONES));

        return new CorridaGuardadaResource(
                Objects.requireNonNull(corrida.id(), "Una corrida leida de la base tiene id"),
                String.valueOf(corrida.ejercicio().valor()),
                corrida.alcance(),
                corrida.sector(),
                corrida.simulacion(),
                corrida.conjunto(),
                corrida.conjuntoId(),
                corrida.fechaCalculo().toString(),
                corrida.determinados(),
                corrida.montoEmitido().toString(),
                derechoSellado(corrida),
                fuera,
                etapas);
    }
}
