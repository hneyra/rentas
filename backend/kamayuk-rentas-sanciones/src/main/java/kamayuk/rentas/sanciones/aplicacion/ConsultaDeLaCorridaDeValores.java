package kamayuk.rentas.sanciones.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecturas que el bucle de una generación masiva necesita, cada una en su propia transacción
 * (#53).
 *
 * <h2>Por qué existe: lo destapó la marcha blanca contra PostgreSQL</h2>
 *
 * <p>{@link GenerarCorridaDeValores#generar} <b>no puede</b> llevar {@code @Transactional} —si lo
 * llevara, todos los candidatos caerían en la misma transacción y el primero que reventara se
 * llevaría por delante a los ya resueltos—. Pero sin transacción tampoco puede <b>leer</b>: sin
 * {@code SET LOCAL} no hay contexto de tenant, la política RLS no puede evaluar {@code
 * current_setting('app.municipalidad_id')} y la consulta falla con «unrecognized configuration
 * parameter». Es el mismo defecto exacto que la marcha blanca de seguridad encontró en {@code GET
 * /catastro/vias} y que se arregló con {@code ConsultaDeVias}, y aquí lo encontró la primera
 * ejecución de la prueba de #53 contra la base de verdad: con dobles en memoria no aparece nunca.
 *
 * <p>La salida es la misma: las lecturas pasan por <b>otro bean</b>, con {@code @Transactional} en
 * cada método. Cada llamada abre y cierra su transacción corta, el bucle sigue sin la suya, y la
 * escritura de cada candidato conserva la que {@link ProcesarPapeletaDeLaCorrida} le abre.
 *
 * <p>{@code readOnly = true} y ni un bloqueo: leer los pendientes de una corrida no tiene por qué
 * frenar a la ventanilla.
 */
@Service
public class ConsultaDeLaCorridaDeValores {

    private final CorridaDeValoresRepository corridas;

    public ConsultaDeLaCorridaDeValores(CorridaDeValoresRepository corridas) {
        this.corridas = corridas;
    }

    @Transactional(readOnly = true)
    public Optional<CorridaDeValores> porId(long corridaId) {
        return corridas.porId(corridaId);
    }

    /** El siguiente lote de candidatos {@code PENDIENTE}, acotado. */
    @Transactional(readOnly = true)
    public List<ItemDeCorrida> pendientes(long corridaId, long despuesDe, int cuantos) {
        return corridas.pendientes(corridaId, despuesDe, cuantos);
    }

    /** El siguiente lote de candidatos, resueltos o no. */
    @Transactional(readOnly = true)
    public List<ItemDeCorrida> items(long corridaId, long despuesDe, int cuantos) {
        return corridas.items(corridaId, despuesDe, cuantos);
    }
}
