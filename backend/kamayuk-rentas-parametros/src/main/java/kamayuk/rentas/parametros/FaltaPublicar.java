package kamayuk.rentas.parametros;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametroQueFalta;
import kamayuk.rentas.web.ProblemaDeNegocio;

/**
 * El <b>unico</b> sitio donde una cifra normativa sin publicar se convierte en una respuesta de
 * error (#691, #723).
 *
 * <p>Es un solo codigo, el {@code 422} de un calculo que no se pudo hacer. Hasta #435 habia un
 * segundo, el {@code 404} de «un cuadro publicado que no esta», para tres lecturas de catastro que
 * salieron del backend en P5C y lo dejaron sin un solo llamador.
 *
 * <p>Y desde #435 casi nadie lo llama a mano: {@code ManejadorDeLoQueFaltaPublicar} traduce la
 * familia entera —cualquier {@link CifraSinPublicar} que un controlador deje pasar—, asi que la
 * cifra que se parametrice mañana contesta su 422 sin que nadie la anada a ningun {@code catch}.
 * Quien lo sigue llamando es quien <b>no</b> puede dejarla pasar: la del dominio puro, que necesita
 * el ejercicio de quien llama.
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>#604 puso el miembro {@code parametroQueFalta} en el cuerpo del 422 y lo cableo en las tres
 * capturas de {@code ConvenioController}, con un ayudante privado. Fuera de tesoreria las demas
 * rutas seguian contestando un 422 con {@code codigo} y {@code mensaje} y nada mas, o sea
 * <b>indistinguible del de un campo que falta</b>. Dentro de tesoreria la ausencia del miembro
 * significa «es un campo de la peticion, se corrige aqui»; aplicada fuera, esa regla es falsa y
 * manda a quien atiende a buscar en el formulario un dato que no esta mal.
 *
 * <p>Un ayudante privado por controlador habria sido la copia numero veintitres de las mismas seis
 * lineas, y la copia que se quedara atras seria invisible: el sintoma de «este 422 no lleva el
 * miembro» es exactamente el mismo que el de «este 422 es de un campo». Por eso hay uno solo y una
 * guarda que exige usarlo ({@code DiscriminadorDeLoQueFaltaPublicarTest}).
 *
 * <h2>Por que vive en {@code kamayuk-rentas-parametros}</h2>
 *
 * <p>Porque es el unico modulo que puede nombrar a la vez las dos mitades: {@link CifraSinPublicar}
 * —suya— y {@link ProblemaDeNegocio} de {@code kamayuk-rentas-plataforma}, del que todo contexto
 * depende. Al reves no se puede: {@code kamayuk-rentas-plataforma} es la base del grafo y no
 * depende de ningun contexto acotado, asi que {@code kamayuk.rentas.web} no puede nombrar {@code
 * CifraSinPublicar} (lo dice el javadoc de {@link ParametroQueFalta}).
 *
 * <h2>El tipo es la guarda, no el nombre del metodo</h2>
 *
 * <p>El parametro es {@link CifraSinPublicar}: una excepcion que no publique su ejercicio <b>no
 * compila</b> aqui, en vez de producir un 422 sin miembro que nadie distinguiria del de un campo
 * ausente.
 */
public final class FaltaPublicar {

    private FaltaPublicar() {}

    /**
     * El 422 de una cifra normativa que no esta publicada, con su discriminador.
     *
     * <p>El mensaje sigue siendo el de la propia excepcion: ya esta redactado en lenguaje del
     * dominio y nombra el ejercicio o la llave. Lo que se anade es el mismo dato en forma legible
     * por programa.
     */
    public static ProblemaDeNegocio problema(CifraSinPublicar falta) {
        Objects.requireNonNull(falta, "Traducir «falta publicar» exige la excepcion que lo dice");
        return new ProblemaDeNegocio(
                CodigoDeError.VALIDACION, mensajeDe(falta), discriminadorDe(falta));
    }

    /**
     * El discriminador sale del ejercicio y la llave, que es lo que {@link CifraSinPublicar}
     * promete, y nada mas.
     */
    private static ParametroQueFalta discriminadorDe(CifraSinPublicar falta) {
        int ejercicio = falta.ejercicio().valor();
        return falta.llave()
                .map(llave -> ParametroQueFalta.llave(ejercicio, llave))
                .orElseGet(() -> ParametroQueFalta.conjuntoDelEjercicio(ejercicio));
    }

    /**
     * El mismo 422 para la <b>unica</b> de estas excepciones que no puede extender {@link
     * CifraSinPublicar}: la del dominio puro.
     *
     * <p>{@link PoliticasDeRedondeo.PuntoSinPolitica} vive en {@code
     * kamayuk-rentas-dominio-compartido} y no sabe de que ejercicio salieron las politicas —no
     * puede saberlo: la capa {@code dominio} no mira la base ni la configuracion (regla 7)—. Quien
     * si lo sabe es quien resolvio el conjunto sellado, y por eso el ejercicio entra por argumento.
     *
     * <p>La llave se compone con {@link PoliticasDeRedondeoSelladas#llaveDe} a partir de {@link
     * PoliticasDeRedondeo.PuntoSinPolitica#punto()}, o sea de las <b>dos</b> mitades que existen y
     * no de una clave inventada. Nunca sale el {@code TIPO} solo: eso significa «falta el bloque
     * entero» ({@code SinPuntosObservados}) y aqui se sabe exactamente cual falta, porque lo pidio
     * el calculo.
     */
    public static ProblemaDeNegocio problema(
            Ejercicio ejercicio, PoliticasDeRedondeo.PuntoSinPolitica sinPolitica) {
        Objects.requireNonNull(ejercicio, "El dominio no sabe de que ejercicio son sus politicas");
        Objects.requireNonNull(sinPolitica, "Traducir «falta publicar» exige la excepcion");
        return new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                mensajeDe(sinPolitica),
                ParametroQueFalta.llave(
                        ejercicio.valor(),
                        PoliticasDeRedondeoSelladas.llaveDe(sinPolitica.punto())));
    }

    /** Una excepcion sin mensaje no deja un {@code null} en la respuesta. */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null || mensaje.isBlank() ? "El valor recibido no es valido" : mensaje;
    }
}
