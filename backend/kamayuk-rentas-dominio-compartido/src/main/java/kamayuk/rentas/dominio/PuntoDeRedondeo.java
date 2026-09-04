package kamayuk.rentas.dominio;

/**
 * Un punto del calculo donde <b>podria</b> redondearse, con el paso de NEG-05 que lo revela.
 *
 * <p>Existe porque {@code PoliticaDeRedondeo} sola no puede expresar <b>D-03c</b>. Una politica es
 * un par {@code (escala, modo)}; D-03c no pregunta con cuantos decimales se redondea sino <b>en que
 * puntos</b>, y esa pregunta no se decide: se responde observando el SRTM del MEF, que redondea en
 * pasos intermedios —M02 muestra un «metrado redondeado» en obras complementarias, que es {@link
 * #METRADO_DE_OBRA}—.
 *
 * <p>Con una politica unica para todo el calculo, un punto no observado <b>no falla</b>: sigue sin
 * redondear y produce un importe plausible, indistinguible del correcto hasta que alguien compara
 * con el SRTM. Enumerar los puntos convierte ese silencio en una pregunta que hay que contestar
 * punto por punto, y {@link PoliticasDeRedondeo#en(PuntoDeRedondeo)} en una excepcion cuando no
 * esta contestada.
 *
 * <p><b>Esta lista solo crece con una determinacion observada</b>, no con una conjetura: cada punto
 * de aqui sale de una secuencia que NEG-05 describe, y la campana de {@code
 * docs/10-negocio/observaciones-srtm-mef/} es la que dice cuales redondean de verdad. Un punto de
 * mas no hace dano —queda sin politica y el calculo que lo pida falla ruidosamente—; un punto de
 * menos es una cifra equivocada en silencio.
 */
public enum PuntoDeRedondeo {

    /**
     * El valor unitario del cuadro tras el incremento del 5 %, <b>antes</b> de depreciar (NEG-05
     * §RT-002). El orden importa: aplicarlo despues da otro resultado, y el motivo normativo del 5
     * % es D-11.
     */
    VALOR_UNITARIO_INCREMENTADO,

    /** El valor unitario ya depreciado, por m² (NEG-05 §RT-002). */
    VALOR_UNITARIO_DEPRECIADO,

    /** Area construida y area comun de un nivel, valorizadas (NEG-05 §RT-002). */
    VALOR_POR_NIVEL,

    /**
     * El «metrado redondeado» de una obra complementaria (NEG-05 §RT-005). <b>Es el punto que M02
     * confirmo</b>, y el que demuestra que el SRTM redondea en pasos intermedios y no solo al
     * cierre de cada regla, como asumia ARQ-09 §1.4.
     */
    METRADO_DE_OBRA,

    /**
     * El total de una obra complementaria, ya con su factor de oficializacion (NEG-05 §RT-005). El
     * factor no tiene fuente identificada: es D-11.
     */
    VALOR_DE_OBRA,

    /** {@code terreno + construccion + obras complementarias} (NEG-05 §RT-010). */
    AUTOVALUO_DEL_PREDIO,

    /** El autovaluo tras el {@code % actualizacion} (NEG-05 §RT-011). El factor es D-11. */
    AUTOVALUO_ACTUALIZADO,

    /** El autovaluo ponderado por el {@code % propiedad} del titular (NEG-05 §RT-011). */
    BASE_IMPONIBLE_DEL_PREDIO,

    /**
     * La suma de las bases de todos los predios del contribuyente (NEG-05 §RT-011). Es la base
     * sobre la que corren los tramos: por contribuyente, nunca por predio.
     */
    BASE_DEL_CONTRIBUYENTE,

    /** La porcion de base que cae en un tramo, por su alicuota (NEG-05 §RT-013). */
    IMPUESTO_POR_TRAMO,

    /**
     * El impuesto del ejercicio, ya comparado con el minimo imponible (NEG-05 §RT-013, §RT-014).
     */
    IMPUESTO_ANUAL,

    /** Cada cuota del fraccionamiento legal del art. 15 (NEG-05 §RT-015). */
    CUOTA,

    /** El reajuste de las cuotas por la variacion del indice (NEG-05 §RT-016). */
    REAJUSTE,

    /** El interes moratorio acumulado (NEG-02 §2.6, fila 19; TUO del Codigo Tributario art. 33). */
    INTERES
}
