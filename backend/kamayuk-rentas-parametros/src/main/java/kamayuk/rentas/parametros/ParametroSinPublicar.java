package kamayuk.rentas.parametros;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Lo que una excepcion tiene que decir <b>por programa</b> cuando el conjunto sellado no puede dar
 * la cifra que el calculo necesita (#604).
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
 * en cuanto alguien lo lee en voz alta. Este contrato es lo que permite que la respuesta lo diga
 * sin prosa: quien la lanza publica el ejercicio y, si la hay, la llave.
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
 * falta exactamente una fila, y el {@code TIPO} solo cuando falta el bloque entero.</b> Nunca se
 * inventa una clave para rellenar el hueco.
 *
 * <h2>Quien la declara, y por que importa que sean todas</h2>
 *
 * <p>#604 la creo con seis implementaciones —las de convenios— y dejo veintidos puntos de
 * traduccion sin discriminador en seis modulos. #691 los cerro, y para eso la declaran <b>trece</b>
 * excepciones mas: las de los plazos de coactiva, valores y sanciones, el arancel de costas, la
 * tasa de anuncios, el derecho del TUPA, la cifra del cuadro del predial, las tres de las campanas
 * de beneficio, la fila que falta de {@link ParametrosSellados} y el envoltorio de frontera de
 * {@code FraccionamientoCoactivo}.
 *
 * <p>Declararla no es documentacion: es lo que hace a la excepcion <b>traducible</b>. {@code
 * FaltaPublicar} acota su parametro a {@code RuntimeException & ParametroSinPublicar} —el tipo que
 * da un {@code catch} multiple—, asi que una excepcion que no la declare no se puede pasar por ahi,
 * y una guarda del codigo fuente exige que todo {@code catch} que la nombre y conteste 422 pase por
 * ahi ({@code DiscriminadorDeLoQueFaltaPublicarTest}).
 *
 * <p>La <b>unica</b> excepcion de esa familia que no puede declararla es {@code
 * PoliticasDeRedondeo.PuntoSinPolitica}: vive en {@code kamayuk-rentas-dominio-compartido}, que
 * esta por debajo de este modulo en el grafo, y ademas no sabe de que ejercicio salieron sus
 * politicas (regla 7). Se traduce con una sobrecarga que recibe el ejercicio de quien lo pidio.
 *
 * <h2>Lo que no lleva</h2>
 *
 * <p>Ni tabla, ni columna, ni restriccion, ni SQL (RNF-033). El ejercicio y la llave son datos del
 * corpus normativo —lo que hay que publicar y para que ano—, no del esquema.
 */
public interface ParametroSinPublicar {

    /** El ejercicio de cuyo conjunto sellado se trata. Siempre lo hay. */
    Ejercicio ejercicio();

    /**
     * La llave que hay que publicar, {@code TIPO:CLAVE}, o el {@code TIPO} solo cuando falta el
     * bloque entero. Vacia cuando lo que falta es el conjunto: no hay donde publicar nada.
     */
    Optional<String> llave();
}
