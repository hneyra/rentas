package kamayuk.rentas.parametros.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * La cache local de conjuntos sellados (ADR-0025 §1): lo que hace que este sistema calcule con
 * {@code normativa} apagada.
 *
 * <p><b>No hay ningun metodo que borre ni que actualice</b>, y no es una omision: lo que se guarda
 * aqui es un conjunto ya sellado, que por construccion no cambia —el disparador de {@code V9} de
 * {@code normativa} lo vuelve inmutable a el y a su contenido—. Una cache de contenido inmutable no
 * tiene invalidacion que disenar. {@code sgtm_app} tampoco tiene el privilegio: {@code V3} le
 * concede {@code INSERT} y {@code SELECT} y nada mas.
 */
public interface CacheDeSnapshots {

    /** Si ese conjunto y ese ambito ya estan descargados. */
    boolean tiene(long conjuntoId, String ambito);

    /**
     * El conjunto cacheado de mayor version del ejercicio, si hay alguno.
     *
     * <p>Es el repliegue de {@code conjuntoVigenteEn} cuando {@code normativa} no contesta. No es
     * lo mismo que preguntarselo a {@code normativa} —puede haberse sellado una version mas nueva
     * que aqui no esta— y por eso quien lo usa lo <b>dice</b> en el registro en vez de callarlo.
     */
    Optional<Long> conjuntoCacheadoDe(Ejercicio ejercicio);

    /** El ejercicio y la version de un conjunto cacheado. */
    Optional<IdentidadDelConjunto> identidadDe(long conjuntoId);

    /** Los parametros del conjunto, con su vigencia sin resolver. */
    List<SnapshotDeNormativa.Parametro> parametrosDe(long conjuntoId);

    /**
     * Guarda el snapshot entero, en una sola transaccion.
     *
     * <p>Toma un candado de transaccion sobre {@code (municipalidad, conjunto)} antes de escribir:
     * los parametros van en los <b>dos</b> ambitos y se escriben con el primero que llegue, asi que
     * dos descargas simultaneas —una de cada ambito— los meterian dos veces. El candado es de
     * transaccion y no de sesion, porque una sesion que se lo lleva al pool contamina la peticion
     * de otra municipalidad, que es la regla 3 aplicada a los candados.
     */
    void guardar(SnapshotDeNormativa snapshot);

    /** Ejercicio y version de un conjunto ya descargado. */
    record IdentidadDelConjunto(Ejercicio ejercicio, int version) {}
}
