package kamayuk.rentas.parametros;

import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * <b>DOBLE DE PRUEBA</b>: un {@link LectorDeParametros} cuyo conjunto vigente <b>cambia entre dos
 * llamadas</b> (#361).
 *
 * <p>La primera resolucion contesta {@code antes}; todas las siguientes, {@code despues}. Es lo que
 * pasa en produccion cuando {@code normativa} sella «2026 v2» mientras corre una operacion, o
 * cuando esta intermitente y {@code LectorDeParametrosCacheados} se repliega al conjunto cacheado
 * en una llamada y en la siguiente ya contesta el nuevo: cada resolucion es independiente.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Todos los dobles del lector que habia hasta #361 devolvian <b>siempre el mismo conjunto</b>, y
 * esa es la muestra uniforme: con ella, resolver una vez o cuatro da el mismo verde, y ninguna
 * prueba podia ver que una determinacion calculaba con los tramos de un conjunto y guardaba el
 * identificador de otro. Con este, cualquier operacion que resuelva dos veces mezcla dos conjuntos
 * distintos <b>en lo que cuenta</b> —los dos que se le pasan tienen que diferir en las cifras que
 * la prueba mide—, y {@link #resoluciones()} cuenta cuantas veces se pregunto.
 *
 * <h2>Imita al lector de produccion, no lo simplifica</h2>
 *
 * <p>{@link #vigenteEn} es {@code porConjunto(conjuntoVigenteEn(ejercicio))}, igual que en {@code
 * LectorDeParametrosCacheados}: por eso cuenta como una resolucion. {@link #porConjunto} no
 * resuelve nada —parte de un identificador ya fijado— y no se cuenta.
 */
public final class NormativaQueCambiaEntreLlamadas implements LectorDeParametros {

    private final IdentificadorDeConjunto antes;
    private final IdentificadorDeConjunto despues;
    private final Map<IdentificadorDeConjunto, ParametrosSellados> sellados;
    private int resoluciones;

    public NormativaQueCambiaEntreLlamadas(
            IdentificadorDeConjunto antes,
            ParametrosSellados delDeAntes,
            IdentificadorDeConjunto despues,
            ParametrosSellados delDeDespues) {
        if (antes.equals(despues)) {
            throw new IllegalArgumentException(
                    "Un doble que cambia de conjunto necesita dos conjuntos: con uno solo es la"
                            + " muestra uniforme que #361 retira");
        }
        this.antes = antes;
        this.despues = despues;
        this.sellados = Map.of(antes, delDeAntes, despues, delDeDespues);
    }

    /** Cuantas veces se resolvio «que conjunto rige»: una por operacion, si esta bien escrita. */
    public int resoluciones() {
        return resoluciones;
    }

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        return porConjunto(conjuntoVigenteEn(ejercicio));
    }

    @Override
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        resoluciones++;
        return resoluciones == 1 ? antes : despues;
    }

    @Override
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        ParametrosSellados delConjunto = sellados.get(identificador);
        if (delConjunto == null) {
            throw new ConjuntoNoSellado(identificador);
        }
        return delConjunto;
    }
}
