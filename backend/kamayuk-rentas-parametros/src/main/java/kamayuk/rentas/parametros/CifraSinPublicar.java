package kamayuk.rentas.parametros;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * La familia «falta publicar»: el conjunto sellado no puede dar la cifra que el calculo necesita, y
 * la excepcion lo dice <b>por programa</b> (#604, #435).
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>#547 tradujo estas excepciones a {@code 422 VALIDACION} y con eso desbloqueo el area de
 * convenios. Pero el cuerpo del problema solo lleva {@code codigo} y {@code mensaje}, y con <b>el
 * mismo par</b> salen dos cosas que se arreglan de dos maneras distintas:
 *
 * <ul>
 *   <li>«Falta el campo {@code nroDeCuotas}» — lo arregla quien atiende, en la misma pantalla;
 *   <li>«El ejercicio 2026 no tiene un conjunto de parametros sellado» — <b>no lo arregla nadie
 *       desde la pantalla</b>: hay que sellar el conjunto o publicar la cifra (D-02a, D-02b).
 * </ul>
 *
 * <p>Sin discriminador, la interfaz solo puede separarlas leyendo el texto, y el texto se reescribe
 * en cuanto alguien lo lee en voz alta. Esta clase es lo que permite que la respuesta lo diga sin
 * prosa: quien la lanza publica el ejercicio y, si la hay, la llave.
 *
 * <h2>Una clase, y no una interfaz mas una base</h2>
 *
 * <p>#604 lo escribio como una interfaz, {@code ParametroSinPublicar}, y hasta #435 la
 * implementaban veintiuna excepciones, cada una con el mismo bloque copiado —{@code
 * serialVersionUID}, el campo del ejercicio con su {@code @SuppressWarnings}, la llave y los dos
 * accesores—, y cada {@code catch} las nombraba una a una: la cifra que se parametrizara despues y
 * se olvidara en uno de ellos contestaba {@code 500} con incidencia en vez del {@code 422} con
 * {@code parametroQueFalta} que promete el contrato, y ni el compilador ni la guarda lo veian. Una
 * interfaz no se puede capturar como familia; una clase si.
 *
 * <p>#435 anadio esta base y dejo la interfaz sosteniendo solo el contrato de lectura; #629 las
 * fundio, porque toda implementacion ya extendia la base. Con una sola, la familia es lo que el
 * compilador ya sabe: {@code ManejadorDeLoQueFaltaPublicar} la traduce <b>entera</b> en un sitio, y
 * la cifra que nazca mañana entra en el 422 con solo extenderla. Lo que ya no se puede escribir es
 * la excepcion que publica ejercicio y llave sin ser de la familia; que tampoco se escriba a mano,
 * copiando los dos accesores, lo exige {@code LaFamiliaFaltaPublicarEsUnTipoTest}.
 *
 * <h2>Por que la llave es opcional y el ejercicio no</h2>
 *
 * <p>Todas estas excepciones hablan del conjunto sellado <b>de un ejercicio</b>, asi que el
 * ejercicio siempre se puede dar. La llave no: cuando lo que falta es el conjunto entero ({@link
 * LectorDeParametros.EjercicioSinSellar}) no hay ninguna fila que nombrar —no hay donde
 * publicarla—, y cuando lo que falta es el bloque entero de un tipo ({@link
 * PoliticasDeRedondeoSelladas.SinPuntosObservados}) tampoco hay <b>una</b>: nombrar un punto
 * cualquiera seria una afirmacion verosimil y equivocada, porque quien lee las politicas no sabe
 * cual de los trece puntos queria el que llamo.
 *
 * <p>De ahi la regla, que es la que el contrato declara: <b>la llave es {@code TIPO:CLAVE} cuando
 * falta exactamente una fila, el {@code TIPO} solo cuando falta el bloque entero, y ninguna —{@code
 * null}— cuando falta el conjunto.</b> Nunca se inventa una clave para rellenar el hueco.
 *
 * <h2>Lo que la subclase sigue decidiendo</h2>
 *
 * <p>El mensaje, redactado en lenguaje del dominio, y como se compone la llave.
 *
 * <p>La <b>unica</b> excepcion de la familia que no puede extenderla es {@code
 * PoliticasDeRedondeo.PuntoSinPolitica}: vive en {@code kamayuk-rentas-dominio-compartido}, que
 * esta por debajo de este modulo en el grafo, y ademas no sabe de que ejercicio salieron sus
 * politicas (regla 7). Se traduce con una sobrecarga que recibe el ejercicio de quien lo pidio.
 *
 * <h2>Lo que no lleva</h2>
 *
 * <p>Ni tabla, ni columna, ni restriccion, ni SQL (RNF-033). El ejercicio y la llave son datos del
 * corpus normativo —lo que hay que publicar y para que ano—, no del esquema.
 */
public abstract class CifraSinPublicar extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no implementa
    // Serializable, y una excepcion de negocio nunca se serializa —se lanza, se traduce a
    // problem+json y muere ahi—.
    @SuppressWarnings("serial")
    private final Ejercicio ejercicio;

    private final @Nullable String llave;

    protected CifraSinPublicar(String mensaje, Ejercicio ejercicio, @Nullable String llave) {
        super(mensaje);
        this.ejercicio = Objects.requireNonNull(ejercicio, "Siempre se sabe de que ejercicio es");
        this.llave = llave;
    }

    protected CifraSinPublicar(
            String mensaje, Ejercicio ejercicio, @Nullable String llave, Throwable causa) {
        super(mensaje, causa);
        this.ejercicio = Objects.requireNonNull(ejercicio, "Siempre se sabe de que ejercicio es");
        this.llave = llave;
    }

    /** El ejercicio de cuyo conjunto sellado se trata. Siempre lo hay. */
    public final Ejercicio ejercicio() {
        return ejercicio;
    }

    /**
     * La llave que hay que publicar, {@code TIPO:CLAVE}, o el {@code TIPO} solo cuando falta el
     * bloque entero. Vacia cuando lo que falta es el conjunto: no hay donde publicar nada.
     */
    public final Optional<String> llave() {
        return Optional.ofNullable(llave);
    }
}
