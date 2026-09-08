package kamayuk.rentas.dominio;

/**
 * Una operacion que este sistema PUBLICA y que hoy no puede terminar (#40).
 *
 * <h2>Que existe para separar</h2>
 *
 * <p>Este backend publica escrituras cuyo camino acaba <b>siempre</b> en excepcion, y hasta #40 las
 * tres caian en el {@code @ExceptionHandler(Exception.class)} de {@code ManejadorDeErrores} y
 * salian como {@code ERROR_INTERNO} con su numero de incidencia. O sea que quien opera recibia
 * «ocurrio un error interno, avise a soporte» ante una <b>limitacion de diseno conocida, escrita y
 * argumentada</b> — y el mensaje que si explica que pasa, el de la excepcion, no salia por ningun
 * lado. Soporte no puede arreglar ninguna de las dos: una la arregla el dueno de la API vecina
 * publicando una ruta, y la otra un protocolo que todavia no existe.
 *
 * <h2>Por que el tipo vive AQUI y no en cada modulo</h2>
 *
 * <p>Por lo mismo que {@link MotivoDeInalcanzable}: las excepciones que lo extienden viven en
 * modulos distintos —{@code tesoreria} y {@code catastro}—, y {@code ManejadorDeErrores} vive en
 * {@code plataforma}, que no depende de ninguno de los dos. Sin un tipo comun en el dominio
 * compartido, la unica forma de darles un {@code @ExceptionHandler} seria escribir tres, uno por
 * modulo, o mover el manejador a {@code aplicacion} y dejar la traduccion de errores repartida en
 * dos sitios.
 *
 * <h2>Y NO es {@code ...Inalcanzable}</h2>
 *
 * <p>Un {@code CajaInalcanzable} o un {@code CatastroInalcanzable} dice «no se pudo preguntar»: se
 * arregla levantando un despliegue y <b>reintentar puede cambiar el resultado</b>. Esto dice «se
 * puede preguntar perfectamente y la operacion no esta construida»: reintentar no la construye. Las
 * dos salen con codigos distintos a proposito, porque un cliente que reintentara esta lo haria para
 * siempre.
 */
public abstract class OperacionTodaviaNoCompletable extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /**
     * Que es lo que falta, como DATO y no dentro de la frase.
     *
     * <p>Es la leccion de {@link MotivoDeInalcanzable}: quien decide leyendo el castellano deja de
     * decidir bien en cuanto alguien reescribe el mensaje, y nada se pone rojo.
     */
    public enum LoQueFalta {

        /**
         * El vecino no publica todavia la ruta que serviria esta operacion.
         *
         * <p>Se arregla <b>publicandola</b>, y el mensaje la nombra. Es trabajo del dueno de esa
         * API (ADR-0030 §4), no de quien atiende la incidencia.
         */
        LA_RUTA_DEL_VECINO,

        /**
         * La ruta existe y no se puede pedir por HTTP <b>sin perder la atomicidad</b> (C-5).
         *
         * <p><b>Publicar la ruta NO la arregla</b>, y por eso es otra constante: lo que falta es
         * que la escritura del vecino y las que la rodean aqui confirmen o se deshagan juntas, y
         * dos bases y dos procesos no comparten transaccion. Se arregla con un protocolo —reserva y
         * confirmacion, o el buzon de eventos de ADR-0027—, no con un controlador.
         */
        LA_TRANSACCION_COMPARTIDA
    }

    // El aviso [serial] no aplica: es un enum, que se serializa por su nombre.
    @SuppressWarnings("serial")
    private final LoQueFalta loQueFalta;

    protected OperacionTodaviaNoCompletable(LoQueFalta loQueFalta, String mensaje) {
        super(mensaje);
        this.loQueFalta = loQueFalta;
    }

    /** Que falta para que esta operacion pueda terminar. Decide a quien hay que avisar. */
    public LoQueFalta loQueFalta() {
        return loQueFalta;
    }
}
