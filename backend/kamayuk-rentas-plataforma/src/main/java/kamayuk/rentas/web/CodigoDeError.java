package kamayuk.rentas.web;

import org.springframework.http.HttpStatus;

/**
 * Catalogo de codigos de error, estable.
 *
 * <p>Estable quiere decir que <b>el nombre no cambia</b> aunque cambie el mensaje: la interfaz
 * reacciona al codigo —deshabilitar un boton, ofrecer otra accion—, y un texto en castellano no
 * sirve para eso porque se reescribe en cuanto alguien lo lee en voz alta.
 *
 * <p>Cada codigo lleva su estado HTTP para que la respuesta no dependa de que quien lanza la
 * excepcion se acuerde de cual toca.
 */
public enum CodigoDeError {

    /**
     * La peticion no trae token, o el que trae no es valido.
     *
     * <p>No distingue entre «no hay token», «esta vencido» y «la firma no es de nuestro emisor», y
     * es deliberado: quien no ha podido autenticarse es justo quien no debe recibir pistas sobre
     * cual de las tres le paso.
     */
    NO_AUTENTICADO(HttpStatus.UNAUTHORIZED, "La peticion no trae un token valido"),

    /**
     * El token no identifica una municipalidad (ADR-0005). No hay valor por omision ni modo sin
     * municipalidad.
     */
    SIN_MUNICIPALIDAD(HttpStatus.FORBIDDEN, "El token no identifica una municipalidad"),

    /**
     * El token del ciudadano no identifica un documento (ADR-0020). Gemelo exacto de {@link
     * #SIN_MUNICIPALIDAD} para la otra poblacion: tampoco hay valor por omision ni modo sin
     * documento, porque un recorrido sin sujeto es una consulta por cualquiera.
     */
    SIN_DOCUMENTO(HttpStatus.FORBIDDEN, "El token no identifica un documento de identidad"),

    /** El usuario no tiene el privilegio que la operacion exige (RF-121). */
    SIN_PRIVILEGIO(HttpStatus.FORBIDDEN, "No tiene el privilegio necesario para esta operacion"),

    /** La peticion no cumple una regla de validacion o de negocio. */
    VALIDACION(HttpStatus.UNPROCESSABLE_ENTITY, "La peticion no cumple una regla de validacion"),

    /** Se pidio ordenar por un campo que la operacion no admite. */
    ORDEN_NO_ADMITIDO(HttpStatus.UNPROCESSABLE_ENTITY, "No se puede ordenar por ese campo"),

    /**
     * El marco pedido contiene mas lotes de los que caben, y hay que acercarse (#611).
     *
     * <p>Es un codigo propio y no {@link #VALIDACION} porque las dos respuestas se arreglan de
     * maneras opuestas: un {@code bbox} del reves o un {@code limite} fuera de rango dicen «esta
     * peticion esta mal, corrigela», y este dice «la peticion esta bien, hay demasiado dentro:
     * acercate». Con el mismo codigo, lo unico que las separaba era el texto en castellano, que se
     * reescribe en cuanto alguien lo lee en voz alta — y entonces el plano deja de saber cuando
     * puede ofrecer «acercarse» (ADR-0022 §2).
     *
     * <p>Las dos cifras —cuantos lotes hay y cual es el tope— viajan en {@code detalles}, como dato
     * y no dentro de la frase, por lo mismo.
     */
    MARCO_CON_DEMASIADOS_LOTES(
            HttpStatus.UNPROCESSABLE_ENTITY, "El marco contiene mas lotes de los que caben"),

    /** Lo pedido no existe en esta municipalidad. */
    NO_ENCONTRADO(HttpStatus.NOT_FOUND, "No se encontro lo solicitado"),

    /**
     * La ruta existe, pero no con el verbo que se pidio (#556).
     *
     * <p>Es un codigo propio y no {@link #NO_ENCONTRADO} porque las dos respuestas se arreglan de
     * maneras distintas: un {@code 404} dice «esa operacion no esta publicada» y un {@code 405}
     * dice «esta publicada, y la estas pidiendo con el verbo equivocado». Y sobre todo no es {@link
     * #ERROR_INTERNO}: la interfaz ofrece «Reintentar» sobre ese, y reintentar un verbo equivocado
     * no puede funcionar nunca.
     */
    METODO_NO_ADMITIDO(HttpStatus.METHOD_NOT_ALLOWED, "El verbo HTTP no se admite en esta ruta"),

    /** El estado actual no admite la operacion: un recibo ya anulado, un convenio quebrado. */
    CONFLICTO(HttpStatus.CONFLICT, "El estado actual no admite esta operacion"),

    /**
     * Otro sistema del que este depende no contesta (P5D, ADR-0026).
     *
     * <p>Es un codigo propio y no {@link #ERROR_INTERNO} porque la respuesta correcta del cliente
     * es la contraria: ante un 500 reintentar no cambia nada y ante esto <b>si</b>. Y no es {@link
     * #VALIDACION}, porque no hay nada que el cliente pueda arreglar en su peticion.
     *
     * <p>Hoy lo produce una sola cosa: que {@code caja} no conteste al emitir una orden de cobro.
     * No devolver un identificador inventado ni dejar la orden «por emitir» es deliberado — una
     * orden que se cree emitida y no lo esta deja al contribuyente delante de una ventanilla que no
     * encuentra su deuda, y el sintoma no se parece a la causa.
     */
    SERVICIO_NO_DISPONIBLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Un sistema del que este depende no contesta"),

    /**
     * Cualquier otra cosa.
     *
     * <p>Su mensaje es deliberadamente inutil para quien lo recibe y util para quien lo investiga:
     * el detalle va al registro con el identificador que aparece en la respuesta. Es la unica forma
     * de no filtrar nombres de tabla ni SQL sin renunciar a poder diagnosticar.
     */
    ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo completar la operacion");

    private final HttpStatus estado;
    private final String mensaje;

    CodigoDeError(HttpStatus estado, String mensaje) {
        this.estado = estado;
        this.mensaje = mensaje;
    }

    public HttpStatus estado() {
        return estado;
    }

    public String mensaje() {
        return mensaje;
    }
}
