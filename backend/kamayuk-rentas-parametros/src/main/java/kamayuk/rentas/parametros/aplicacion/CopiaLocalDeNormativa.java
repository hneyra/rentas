package kamayuk.rentas.parametros.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La copia local de los conjuntos sellados, con la transaccion que cada lectura y la escritura
 * necesitan — y <b>solo</b> con ella (#450).
 *
 * <h2>Por que existe esta clase</h2>
 *
 * <p>Hasta #450 las lecturas de {@link LectorDeParametrosCacheados} eran {@code @Transactional(
 * readOnly = true)} enteras, y dentro preguntaban a {@code normativa} por red: la conexion de la
 * base quedaba tomada mientras {@code normativa} contestaba —hasta 5 minutos—, y diez liquidadores
 * a la vez dejaban a rentas sin pool. Sacar la pregunta de la transaccion exige que lo que SI
 * necesita transaccion —leer la copia, que esta bajo RLS, y guardarla— la abra por su cuenta, y eso
 * no puede hacerlo el propio lector: una llamada a un metodo de la misma clase no pasa por el
 * proxy.
 *
 * <p>Asi que el reparto es este: el lector y la descarga preguntan a {@code normativa} <b>sin
 * transaccion</b>, y esta clase abre la suya para leer o escribir la copia, que dura lo que duran
 * esas sentencias. Si quien llama ya tiene una —los liquidadores—, las lecturas se unen a ella
 * ({@code REQUIRED}); lo que eso cuesta es el aviso de {@link kamayuk.rentas.plataforma.ViajeDeRed}
 * nombrando a ese llamador, que es lo que tiene que pasar.
 *
 * <h2>La escritura, en su propia transaccion</h2>
 *
 * <p>{@link #guardarSiNoEsta} es {@code REQUIRES_NEW} por el motivo que {@link DescargaDeNormativa}
 * explica: quien pide un valor casi siempre esta dentro de una lectura, y en ella PostgreSQL
 * rechaza el {@code INSERT}. Lo que cambia con #450 es <b>cuando</b> se abre: despues de descargar
 * y no antes, asi que la segunda conexion dura lo que dura guardar y no lo que tarda la red.
 */
@Service
public class CopiaLocalDeNormativa {

    private final CacheDeSnapshots cache;

    public CopiaLocalDeNormativa(CacheDeSnapshots cache) {
        this.cache = cache;
    }

    /** Si ese conjunto, en ese ambito, ya esta en la copia. */
    @Transactional(readOnly = true)
    public boolean tiene(long conjuntoId, String ambito) {
        return cache.tiene(conjuntoId, ambito);
    }

    /** El conjunto de mayor version que la copia tiene para el ejercicio, si tiene alguno. */
    @Transactional(readOnly = true)
    public Optional<Long> conjuntoCacheadoDe(Ejercicio ejercicio) {
        return cache.conjuntoCacheadoDe(ejercicio);
    }

    /**
     * El conjunto de mayor version del ejercicio con su ejercicio y su version, sin el snapshot.
     *
     * <p>Dos {@code SELECT} en una transaccion: el primero da el conjunto, el segundo quien es. Es
     * lo que pregunta la pantalla de las senias (#25, AC-3), que no necesita los parametros.
     */
    @Transactional(readOnly = true)
    public Optional<LectorDeParametros.ConjuntoYaDescargado> loQueYaEstaDescargado(
            Ejercicio ejercicio) {
        return cache.conjuntoCacheadoDe(ejercicio)
                .flatMap(
                        conjunto ->
                                cache.identidadDe(conjunto)
                                        .map(
                                                identidad ->
                                                        new LectorDeParametros.ConjuntoYaDescargado(
                                                                conjunto,
                                                                identidad.ejercicio(),
                                                                identidad.version())));
    }

    /**
     * La identidad del conjunto y sus parametros, en <b>una</b> lectura.
     *
     * <p>Juntas y no por separado: son la misma foto del mismo conjunto, y leerlas en dos
     * transacciones no cambiaria nada —la copia es inmutable— pero abriria dos veces lo que basta
     * abrir una.
     */
    @Transactional(readOnly = true)
    public Optional<ConjuntoCopiado> leer(long conjuntoId) {
        return cache.identidadDe(conjuntoId)
                .map(identidad -> new ConjuntoCopiado(identidad, cache.parametrosDe(conjuntoId)));
    }

    /**
     * Guarda el snapshot ya descargado y verificado, si otra peticion no lo guardo antes.
     *
     * <p>La comprobacion se repite <b>dentro</b> de la transaccion nueva: entre el «no esta» de
     * quien descargo y este {@code INSERT} cabe otra peticion que lo haya guardado. El candado de
     * {@link CacheDeSnapshots#guardar} cierra la carrera; esta comprobacion evita la espera en el
     * caso normal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void guardarSiNoEsta(SnapshotDeNormativa snapshot) {
        if (cache.tiene(snapshot.conjuntoId(), snapshot.ambito())) {
            return;
        }
        cache.guardar(snapshot);
    }

    /** Lo que la copia tiene de un conjunto: quien es y sus parametros. */
    public record ConjuntoCopiado(
            CacheDeSnapshots.IdentidadDelConjunto identidad,
            List<SnapshotDeNormativa.Parametro> parametros) {

        public ConjuntoCopiado {
            parametros = List.copyOf(parametros);
        }
    }
}
