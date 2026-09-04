package kamayuk.rentas.parametros.aplicacion;

import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trae un conjunto sellado de {@code normativa} y lo deja en la cache local, <b>en su propia
 * transaccion</b>.
 *
 * <h2>Por que `REQUIRES_NEW`, y por que esto NO es el defecto de #52</h2>
 *
 * <p>Porque quien pide un valor normativo casi siempre esta dentro de una lectura:
 * {@code @Transactional(readOnly = true)}. En esa transaccion PostgreSQL rechaza todo {@code
 * INSERT} —«cannot execute INSERT in a read-only transaction»—, asi que descargar dentro de ella es
 * imposible; y quitarle el {@code readOnly} a las doce lecturas que calculan seria abrirlas a
 * escritura para poder cachear.
 *
 * <p>#52 midio que un {@code REQUIRES_NEW} deja sobrevivir al fallo del paso siguiente lo que la
 * transaccion interna ya escribio, y ahi eso era el defecto: la ficha versionada sobrevivia a una
 * transferencia que no llego a emitir resolucion. <b>Aqui es al reves, y por una propiedad del
 * dato:</b> lo que se escribe es una copia <b>inmutable y verificada</b> de un conjunto ya sellado.
 * Que sobreviva al fallo de lo que venia despues no deja nada a medias — deja exactamente lo mismo
 * que dejaria volver a descargarlo, byte a byte, porque {@code normativa} no puede servir otra cosa
 * bajo ese identificador (el disparador de `V9` lo vuelve inmutable al sellarse).
 *
 * <p>Lo que si seria un defecto es cachear algo que no se pudo verificar, y eso lo impide {@link
 * PublicadorDeNormativa.HuellaQueNoCuadra}: la excepcion sale <b>antes</b> de llegar aqui, asi que
 * la transaccion nueva ni se abre.
 */
@Service
public class DescargaDeNormativa {

    private final CacheDeSnapshots cache;
    private final PublicadorDeNormativa normativa;

    public DescargaDeNormativa(CacheDeSnapshots cache, PublicadorDeNormativa normativa) {
        this.cache = cache;
        this.normativa = normativa;
    }

    /**
     * Descarga el conjunto si no esta ya.
     *
     * <p>La comprobacion se repite <b>dentro</b> de la transaccion nueva y no solo fuera: entre el
     * «no esta» de quien llama y el {@code INSERT} de aqui cabe otra peticion que lo descargue, y
     * las dos escribirian la misma clave. El candado de {@link CacheDeSnapshots#guardar} cierra la
     * carrera; esta comprobacion evita la espera en el caso normal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void asegurarDescargado(long conjuntoId, String ambito) {
        if (cache.tiene(conjuntoId, ambito)) {
            return;
        }
        cache.guardar(normativa.descargar(conjuntoId, ambito));
    }
}
