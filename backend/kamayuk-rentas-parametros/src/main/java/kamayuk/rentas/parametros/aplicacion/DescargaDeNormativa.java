package kamayuk.rentas.parametros.aplicacion;

import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import org.springframework.stereotype.Service;

/**
 * Trae un conjunto sellado de {@code normativa} y lo deja en la copia local: <b>primero descarga,
 * despues abre la transaccion, y solo para guardar</b> (#450).
 *
 * <h2>Por que el orden importa</h2>
 *
 * <p>Hasta #450 este metodo era {@code @Transactional(propagation = REQUIRES_NEW)} y descargaba
 * <b>dentro</b>: la primera peticion despues de sellarse un conjunto nuevo retenia <b>dos</b>
 * conexiones —la de quien calculaba y la nueva— durante la descarga, que tiene 5 minutos de espera
 * porque el snapshot con el anexo vehicular son 54 000 filas. Y el javadoc decia que ante una
 * huella que no cuadraba «la transaccion nueva ni se abre», que no era cierto: la descarga corria
 * dentro del metodo que ya la habia abierto.
 *
 * <p>Ahora la descarga no abre nada, y la transaccion nueva la abre {@link
 * CopiaLocalDeNormativa#guardarSiNoEsta} cuando ya hay algo verificado que guardar. Con una {@link
 * PublicadorDeNormativa.HuellaQueNoCuadra}, <b>ahora si</b>, la transaccion nueva ni se abre.
 *
 * <h2>Por que la escritura sigue siendo `REQUIRES_NEW`, y por que NO es el defecto de #52</h2>
 *
 * <p>Porque quien pide un valor normativo casi siempre esta dentro de una lectura:
 * {@code @Transactional(readOnly = true)}. En esa transaccion PostgreSQL rechaza todo {@code
 * INSERT} —«cannot execute INSERT in a read-only transaction»—, asi que guardar dentro de ella es
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
 */
@Service
public class DescargaDeNormativa {

    private final CopiaLocalDeNormativa copia;
    private final PublicadorDeNormativa normativa;

    public DescargaDeNormativa(CopiaLocalDeNormativa copia, PublicadorDeNormativa normativa) {
        this.copia = copia;
        this.normativa = normativa;
    }

    /**
     * Descarga el conjunto si no esta ya.
     *
     * <p>Sin {@code @Transactional}, y es todo el arreglo de #450: la pregunta «esta?» abre y
     * cierra la suya, la descarga no tiene ninguna, y guardar abre otra al final. Si quien llama ya
     * tenia una, la espera a la red la paga esa —y {@link kamayuk.rentas.plataforma.ViajeDeRed} lo
     * avisa nombrandolo—, pero ya no hay una segunda conexion esperando con ella.
     *
     * <p>Lo que cierra la carrera de dos primeras lecturas simultaneas <b>no</b> es la comprobacion
     * de aqui: esta va antes del candado, igual que la de quien llama, y las dos peticiones pueden
     * ver «no esta» a la vez. La cierra {@code CacheDeSnapshots.guardar}, que toma el candado y
     * <b>vuelve a mirar despues</b>: quien llega segundo espera, encuentra lo que el primero
     * confirmo y no escribe nada (#353). Esta comprobacion es solo un atajo: ahorra la descarga
     * cuando otra peticion ya confirmo entre el «no esta» de quien llama y esta.
     *
     * <p>Lo que no ahorra es la <b>segunda descarga</b> en la carrera: las dos peticiones se bajan
     * el snapshot y una lo tira. Tomar el candado antes de descargar lo evitaria, pero lo tendria
     * abierto durante la peticion HTTP —justo lo que #450 quita—; se dejo fuera de #353 a
     * proposito.
     */
    public void asegurarDescargado(long conjuntoId, String ambito) {
        if (copia.tiene(conjuntoId, ambito)) {
            return;
        }
        copia.guardarSiNoEsta(normativa.descargar(conjuntoId, ambito));
    }
}
