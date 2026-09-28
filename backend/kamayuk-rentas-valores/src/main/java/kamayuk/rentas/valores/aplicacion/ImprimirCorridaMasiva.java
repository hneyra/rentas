package kamayuk.rentas.valores.aplicacion;

import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import org.springframework.stereotype.Service;

/**
 * La tercera etapa de una generacion masiva: imprime, en el formato de cada tipo, todos los valores
 * que la etapa anterior emitio (RF-091, #38).
 *
 * <h2>Miles de valores, sin tenerlos todos en memoria a la vez</h2>
 *
 * <p>Delega en {@link EmitirDocumento#emitirEnLote}, que recibe un {@link Iterator} y escribe cada
 * documento en su flujo antes de pedir el siguiente. El {@link Iterator} de este metodo construye
 * el modelo de un valor <b>solo cuando se pide</b> -{@link ConstruirModeloDeValor#de} dentro de
 * {@code next()}-, nunca antes: con miles de items, la lista de {@code valorId} completa cabe sin
 * problema en memoria, pero los miles de {@link ModeloDeDocumento} con su desglose no tendrian por
 * que.
 *
 * <h2>No es la misma reanudacion que la generacion</h2>
 *
 * <p>Imprimir no muta nada: no consume correlativo, no mueve fase, no cambia el estado de ningun
 * item. Repetir una impresion interrumpida no arriesga duplicar nada -es exactamente lo que ya hace
 * {@code EmitirDocumento#reimprimir} para un valor individual-, asi que esta etapa no necesita su
 * propia marca de progreso: si se corta, se vuelve a llamar sobre la misma corrida y listo.
 *
 * <h2>Lee por {@link ConsultaDeLaCorridaMasiva}, como la generacion (#400)</h2>
 *
 * <p>Por el mismo motivo: quien la llame desde un proceso sin peticion —fuera de transaccion— no
 * tendria {@code SET LOCAL}, y la politica RLS de {@code valor_masivo_item} haria fallar la
 * lectura.
 *
 * <h2>Quien la llama (#631)</h2>
 *
 * <p>{@code GET /valores/masivo/{id}/impresion}, con el privilegio de impresion de {@code
 * valores_masivo}: la tercera etapa de RF-091. Hasta #631 no la llamaba nadie —#400 cerro el
 * <i>procesamiento</i> de la corrida, no su impresion— y una corrida generaba sus valores sin que
 * hubiera manera de sacar el lote en papel desde la aplicacion.
 *
 * <p>Una corrida que no existe en esta municipalidad y una que existe sin ningun valor {@code
 * GENERADO} <b>no se imprimen vacias</b>: son {@link CorridaInexistente} y {@link
 * SinValoresGenerados}. Un archivo sin documentos dentro se lee igual que «no habia nada que
 * notificar», y lo que pasa es que la ventana de lote todavia no la proceso, o que ninguno de sus
 * candidatos tenia deuda — dos cosas que quien opera arregla distinto, y ninguna de las dos es un
 * papel.
 */
@Service
public class ImprimirCorridaMasiva {

    private final ConsultaDeLaCorridaMasiva lectura;
    private final ConstruirModeloDeValor construirModelo;
    private final EmitirDocumento emitirDocumento;

    public ImprimirCorridaMasiva(
            ConsultaDeLaCorridaMasiva lectura,
            ConstruirModeloDeValor construirModelo,
            EmitirDocumento emitirDocumento) {
        this.lectura = lectura;
        this.construirModelo = construirModelo;
        this.emitirDocumento = emitirDocumento;
    }

    /**
     * Imprime todos los valores {@code GENERADO} de la corrida.
     *
     * @param formato en que formato se imprime; el mismo para toda la corrida
     * @param destino de donde sacar el flujo de cada documento -el llamador lo abre y lo cierra-,
     *     tipicamente un archivo por valor nombrado con su numero
     * @return cuantos documentos se escribieron; nunca cero
     * @throws CorridaInexistente si la corrida no es de esta municipalidad, o no existe
     * @throws SinValoresGenerados si la corrida no tiene ningun valor {@code GENERADO} todavia
     */
    public long imprimir(
            long corridaId,
            FormatoDeDocumento formato,
            Function<ModeloDeDocumento, OutputStream> destino) {

        if (lectura.porId(corridaId).isEmpty()) {
            throw new CorridaInexistente(corridaId);
        }
        List<ValorMasivoItem> generados = lectura.itemsGenerados(corridaId);
        if (generados.isEmpty()) {
            throw new SinValoresGenerados(corridaId);
        }
        return emitirDocumento.emitirEnLote(modelosDe(generados), formato, destino);
    }

    private Iterator<ModeloDeDocumento> modelosDe(List<ValorMasivoItem> items) {
        Iterator<ValorMasivoItem> origen = items.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return origen.hasNext();
            }

            @Override
            public ModeloDeDocumento next() {
                ValorMasivoItem item = origen.next();
                long valorId =
                        Objects.requireNonNull(
                                item.valorId(), "Un item GENERADO siempre lleva su valorId");
                return construirModelo.de(valorId);
            }
        };
    }

    /** No hay ninguna corrida masiva con ese identificador en esta municipalidad. */
    public static final class CorridaInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CorridaInexistente(long corridaId) {
            super("No hay ninguna corrida masiva de valores con el identificador " + corridaId);
        }
    }

    /**
     * La corrida existe y no tiene ni un valor {@code GENERADO}: no hay papel que sacar todavia.
     */
    public static final class SinValoresGenerados extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinValoresGenerados(long corridaId) {
            super(
                    "La corrida masiva "
                            + corridaId
                            + " no tiene ningun valor generado que imprimir: o la ventana de lote"
                            + " todavia no la proceso, o ninguno de sus candidatos tenia deuda");
        }
    }
}
