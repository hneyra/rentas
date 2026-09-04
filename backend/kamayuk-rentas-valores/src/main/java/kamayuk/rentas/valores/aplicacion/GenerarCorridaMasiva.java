package kamayuk.rentas.valores.aplicacion;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.valores.aplicacion.ProcesarItemMasivo.Resultado;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * La segunda etapa de una generacion masiva: recorre los candidatos pendientes de una corrida y los
 * resuelve (RF-091, #38).
 *
 * <h2>Reanudable sin tabla de progreso aparte</h2>
 *
 * <p>{@link #generar} siempre pregunta por los items {@code PENDIENTE} que quedan -nunca por "los
 * que faltan desde donde me corte"-, asi que llamarlo dos veces sobre la misma corrida hace
 * exactamente lo mismo que llamarlo una vez hasta el final: la segunda llamada no encuentra nada
 * pendiente y termina de inmediato. Un corte a mitad -el proceso batch se cae, el nodo se reinicia-
 * no pierde nada: lo que ya se proceso quedo {@code GENERADO} o {@code SIN_DEUDA} en su propia
 * transaccion (ver {@link ProcesarItemMasivo}), y lo que no, sigue {@code PENDIENTE} para la
 * proxima llamada.
 *
 * <h2>Este metodo NO lleva {@code @Transactional}</h2>
 *
 * <p>A proposito, igual que {@code catastro.ImportarVias}: cada candidato se resuelve con una
 * llamada a {@link ProcesarItemMasivo#procesar}, que es un bean distinto con su propia transaccion.
 * Si este metodo fuera transaccional, todos los candidatos de un lote caerian en la misma
 * transaccion y una obligacion que revienta una restriccion se llevaria consigo a los candidatos ya
 * resueltos antes que ella.
 *
 * <h2>Corre en el perfil batch (ADR-0003)</h2>
 *
 * <p>Este servicio es el que invoca el proceso batch -analogo a {@code
 * catastro.CargarCatalogoVial}-, nunca la peticion web que registra el criterio: una corrida de
 * miles de contribuyentes puede tardar minutos, y esa espera no tiene que competir con la caja por
 * el mismo proceso.
 */
@Service
public class GenerarCorridaMasiva {

    private static final Logger log = LoggerFactory.getLogger(GenerarCorridaMasiva.class);

    /**
     * Cuantos candidatos se leen de la base por vuelta. No es un limite de negocio: es memoria -una
     * corrida de decenas de miles de candidatos no tiene por que traerlos todos de una vez para
     * saber por donde seguir.
     */
    private static final int TAMANO_DE_LOTE = 200;

    private final ValorMasivoRepository repositorio;
    private final ProcesarItemMasivo procesar;

    public GenerarCorridaMasiva(ValorMasivoRepository repositorio, ProcesarItemMasivo procesar) {
        this.repositorio = repositorio;
        this.procesar = procesar;
    }

    /**
     * Procesa todos los candidatos {@code PENDIENTE} de la corrida hasta agotarlos.
     *
     * @throws CorridaNoEncontrada si el identificador no corresponde a ninguna corrida de esta
     *     municipalidad
     */
    public Informe generar(long corridaId) {
        ValorMasivo corrida =
                repositorio.porId(corridaId).orElseThrow(() -> new CorridaNoEncontrada(corridaId));

        int generados = 0;
        int sinDeuda = 0;
        int fallidos = 0;

        // El cursor avanza por "id", no por "lo que siga PENDIENTE": un item que falla se
        // queda PENDIENTE (ver el catch de abajo), y sin el cursor la misma consulta lo
        // volveria a traer en la siguiente vuelta para siempre. Con el cursor, esta pasada
        // intenta cada candidato como mucho una vez; una llamada posterior a generar() -otra
        // corrida del batch- es la que lo vuelve a intentar.
        long cursor = 0;
        List<ValorMasivoItem> lote;
        while (!(lote = repositorio.itemsPendientes(corridaId, cursor, TAMANO_DE_LOTE)).isEmpty()) {
            for (ValorMasivoItem item : lote) {
                try {
                    Resultado resultado = procesar.procesar(corrida, item, corrida.observacion());
                    if (resultado == Resultado.GENERADO) {
                        generados++;
                    } else {
                        sinDeuda++;
                    }
                } catch (DataAccessException fallo) {
                    // La transaccion de este item se deshizo entera: sigue PENDIENTE, y una
                    // proxima llamada a generar() lo vuelve a intentar. No se detiene la
                    // corrida por un candidato: el resto puede resolverse igual.
                    fallidos++;
                    log.warn(
                            "No se pudo procesar el candidato {} de la corrida {}: {}",
                            item.contribuyenteId(),
                            corridaId,
                            fallo.getMessage());
                }
            }
            cursor =
                    Objects.requireNonNull(
                            lote.get(lote.size() - 1).id(),
                            "Un item leido de la base ya tiene su id");
        }

        return new Informe(corridaId, generados, sinDeuda, fallidos);
    }

    /** El resultado de una llamada a {@link #generar}. */
    public record Informe(long corridaId, int generados, int sinDeuda, int fallidos) {}

    /** No hay ninguna corrida con ese identificador en esta municipalidad. */
    public static final class CorridaNoEncontrada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CorridaNoEncontrada(long corridaId) {
            super("No hay ninguna corrida masiva con el identificador " + corridaId);
        }
    }
}
