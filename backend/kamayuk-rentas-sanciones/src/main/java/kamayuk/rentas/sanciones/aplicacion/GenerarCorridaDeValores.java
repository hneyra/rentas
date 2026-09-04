package kamayuk.rentas.sanciones.aplicacion;

import java.util.List;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * La segunda etapa de una generación masiva de valores por papeletas: recorrer los candidatos
 * pendientes y resolverlos (#53, RF-066, RF-073).
 *
 * <h2>Reanudable sin tabla de progreso aparte</h2>
 *
 * <p>{@link #generar} siempre pregunta por los candidatos {@code PENDIENTE} que quedan, nunca por
 * «los que faltan desde donde me corté». Llamarlo dos veces sobre la misma corrida hace lo mismo
 * que llamarlo una vez hasta el final: la segunda llamada no encuentra nada pendiente y termina. Un
 * corte a mitad no pierde nada —lo procesado quedó resuelto en su propia transacción— y no duplica
 * nada: la papeleta ya emitida no vuelve a estar {@code PENDIENTE}, y si por cualquier camino lo
 * estuviera, {@code papeleta_valor_unico_uq} (V47) rechaza el segundo valor.
 *
 * <h2>Este método NO lleva {@code @Transactional}</h2>
 *
 * <p>A propósito, igual que {@code valores.GenerarCorridaMasiva} y {@code catastro.ImportarVias}:
 * cada candidato se resuelve llamando a {@link ProcesarPapeletaDeLaCorrida#procesar}, que es un
 * bean distinto con su propia transacción. Si este método fuera transaccional, todos los candidatos
 * caerían en la misma y una papeleta que reventara una restricción se llevaría consigo a las ya
 * resueltas antes que ella.
 *
 * <h2>Corre en el perfil batch (ADR-0003)</h2>
 *
 * <p>Lo invoca el proceso batch, nunca la petición web que registró el criterio: una corrida de
 * miles de papeletas puede tardar minutos y esa espera no tiene por qué competir con la caja.
 */
@Service
public class GenerarCorridaDeValores {

    private static final Logger log = LoggerFactory.getLogger(GenerarCorridaDeValores.class);

    /**
     * Cuántos candidatos se leen de la base por vuelta.
     *
     * <p>No es un límite de negocio: es memoria. Una corrida de decenas de miles de papeletas no
     * tiene por qué traerlas todas de una vez para saber por dónde seguir (AC 5 de #53).
     */
    private static final int TAMANO_DE_LOTE = 200;

    private final ConsultaDeLaCorridaDeValores lectura;
    private final ProcesarPapeletaDeLaCorrida procesar;

    public GenerarCorridaDeValores(
            ConsultaDeLaCorridaDeValores lectura, ProcesarPapeletaDeLaCorrida procesar) {
        this.lectura = lectura;
        this.procesar = procesar;
    }

    /**
     * Procesa todos los candidatos {@code PENDIENTE} de la corrida hasta agotarlos.
     *
     * @throws CorridaNoEncontrada si el identificador no corresponde a ninguna corrida de esta
     *     municipalidad
     */
    public Informe generar(long corridaId) {
        CorridaDeValores corrida =
                lectura.porId(corridaId).orElseThrow(() -> new CorridaNoEncontrada(corridaId));

        int generados = 0;
        int sinDeuda = 0;
        int noProceden = 0;
        int fallidos = 0;

        // El cursor avanza por identificador de candidato y no por «lo que siga
        // PENDIENTE»: un candidato que falla se queda PENDIENTE (ver el catch), y sin
        // cursor la misma consulta lo volveria a traer en la siguiente vuelta para
        // siempre. Con cursor, esta pasada intenta cada candidato como mucho una vez.
        long cursor = 0;
        List<ItemDeCorrida> lote;
        while (!(lote = lectura.pendientes(corridaId, cursor, TAMANO_DE_LOTE)).isEmpty()) {
            for (ItemDeCorrida item : lote) {
                try {
                    ProcesarPapeletaDeLaCorrida.Resultado resultado =
                            procesar.procesar(corrida, item, corrida.observacion());
                    if (resultado == ProcesarPapeletaDeLaCorrida.Resultado.GENERADO) {
                        generados++;
                    } else if (resultado == ProcesarPapeletaDeLaCorrida.Resultado.SIN_DEUDA) {
                        sinDeuda++;
                    } else {
                        noProceden++;
                    }
                } catch (DataAccessException
                        | CorridaDeValoresRepository.PapeletaYaConValor fallo) {
                    // La transaccion de este candidato se deshizo entera: sigue PENDIENTE,
                    // y una proxima llamada lo vuelve a intentar. No se detiene la corrida
                    // por uno: el resto puede resolverse igual.
                    fallidos++;
                    log.warn(
                            "No se pudo procesar la papeleta {} de la corrida {}: {}",
                            item.papeletaId(),
                            corridaId,
                            fallo.getMessage());
                }
            }
            cursor = lote.get(lote.size() - 1).identificador();
        }

        return new Informe(corridaId, generados, sinDeuda, noProceden, fallidos);
    }

    /**
     * El resultado de una llamada a {@link #generar}.
     *
     * @param noProceden cuántos esperan una resolución, su notificación o su plazo
     * @param fallidos cuántos reventaron y siguen pendientes para la próxima pasada
     */
    public record Informe(
            long corridaId, int generados, int sinDeuda, int noProceden, int fallidos) {}

    /** No hay ninguna corrida con ese identificador en esta municipalidad. */
    public static final class CorridaNoEncontrada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CorridaNoEncontrada(long corridaId) {
            super("No hay ninguna corrida masiva de papeletas con el identificador " + corridaId);
        }
    }
}
