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
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
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
 */
@Service
public class ImprimirCorridaMasiva {

    private final ValorMasivoRepository repositorioMasivo;
    private final ConstruirModeloDeValor construirModelo;
    private final EmitirDocumento emitirDocumento;

    public ImprimirCorridaMasiva(
            ValorMasivoRepository repositorioMasivo,
            ConstruirModeloDeValor construirModelo,
            EmitirDocumento emitirDocumento) {
        this.repositorioMasivo = repositorioMasivo;
        this.construirModelo = construirModelo;
        this.emitirDocumento = emitirDocumento;
    }

    /**
     * Imprime todos los valores {@code GENERADO} de la corrida.
     *
     * @param formato en que formato se imprime; el mismo para toda la corrida
     * @param destino de donde sacar el flujo de cada documento -el llamador lo abre y lo cierra-,
     *     tipicamente un archivo por valor nombrado con su numero
     * @return cuantos documentos se escribieron
     */
    public long imprimir(
            long corridaId,
            FormatoDeDocumento formato,
            Function<ModeloDeDocumento, OutputStream> destino) {

        List<ValorMasivoItem> generados = repositorioMasivo.itemsGenerados(corridaId);
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
}
