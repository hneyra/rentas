package kamayuk.rentas.valores.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecturas que las etapas de una generacion masiva necesitan, cada una en su propia transaccion
 * (#400).
 *
 * <h2>Por que existe: es el defecto que {@code sanciones} ya habia pagado</h2>
 *
 * <p>{@link GenerarCorridaMasiva#generar} <b>no puede</b> llevar {@code @Transactional} —cada
 * candidato va en la suya, y si el bucle tuviera una, el primero que reventara se llevaria por
 * delante a los ya resueltos—. Pero sin transaccion tampoco puede <b>leer</b>: {@code valor_masivo}
 * y {@code valor_masivo_item} tienen {@code FORCE ROW LEVEL SECURITY} sobre {@code
 * current_setting('app.municipalidad_id')}, y sin {@code SET LOCAL} la politica no puede evaluarse.
 * Medido en #400 contra PostgreSQL, con la corrida leida por el repositorio desnudo: {@code
 * BadSqlGrammarException} en {@code porId} —{@code unrecognized configuration parameter
 * "app.municipalidad_id"}— antes de procesar un solo candidato.
 *
 * <p>Es exactamente lo que {@code sanciones.ConsultaDeLaCorridaDeValores} arreglo para su gemelo en
 * #53, y la salida es la misma: las lecturas pasan por <b>otro bean</b>, con {@code Transactional}
 * en cada metodo. Cada llamada abre y cierra su transaccion corta, el bucle sigue sin la suya, y la
 * escritura de cada candidato conserva la que {@link ProcesarItemMasivo} le abre. No se fusionan
 * los dos gemelos en una abstraccion comun a los dos modulos: se replica la lectura transaccional,
 * que es el arreglo proporcionado (#400, «Lo que NO entra»).
 *
 * <p>{@code readOnly = true} y ni un bloqueo: leer los pendientes de una corrida no tiene por que
 * frenar a la ventanilla.
 */
@Service
public class ConsultaDeLaCorridaMasiva {

    private final ValorMasivoRepository corridas;

    public ConsultaDeLaCorridaMasiva(ValorMasivoRepository corridas) {
        this.corridas = corridas;
    }

    @Transactional(readOnly = true)
    public Optional<ValorMasivo> porId(long corridaId) {
        return corridas.porId(corridaId);
    }

    /** El siguiente lote de candidatos {@code PENDIENTE}, acotado y con cursor. */
    @Transactional(readOnly = true)
    public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
        return corridas.itemsPendientes(corridaId, desdeId, maximo);
    }

    /** Los candidatos {@code GENERADO}, para la impresion. */
    @Transactional(readOnly = true)
    public List<ValorMasivoItem> itemsGenerados(long corridaId) {
        return corridas.itemsGenerados(corridaId);
    }

    /** Las corridas de esta municipalidad con algo por resolver: a las que el batch llama. */
    @Transactional(readOnly = true)
    public List<Long> corridasConPendientes() {
        return corridas.corridasConPendientes();
    }
}
