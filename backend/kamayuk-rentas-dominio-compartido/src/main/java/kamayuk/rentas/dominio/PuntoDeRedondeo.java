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
 *
 * <p><b>Y desde ADR-0018 de {@code normativa} (2026-08-28), tambien con un cierre de regla.</b> El
 * ADR cierra D-03a, D-03b y D-03c: «se redondea al cierre de cada regla, a centimo (escala 2),
 * {@code HALF_UP}». Los cuatro ultimos puntos no salen de una observacion del SRTM sino de ese
 * cierre, en las tres reglas de otros tributos y en la valorizacion del FUE, que hasta #378 no
 * redondeaban: su respuesta y su auditoria decian una cifra y la columna {@code dinero} guardaba
 * otra.
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
    INTERES,

    /**
     * El impuesto vehicular del vehiculo, ya comparado con el minimo imponible (TUO LTM arts. 30 a
     * 37; #378). Es el cierre de su regla: ADR-0018 redondea ahi, y no en {@code base × alicuota}.
     */
    IMPUESTO_VEHICULAR,

    /** El impuesto de alcabala: {@code excedente afecto × alicuota} (TUO LTM art. 25; #378). */
    IMPUESTO_ALCABALA,

    /**
     * El impuesto de espectaculos publicos no deportivos: {@code ingreso × alicuota de la clase}
     * (TUO LTM arts. 54 a 59; #378).
     */
    IMPUESTO_ESPECTACULO,

    /**
     * El importe de cada linea de la valorizacion de obra del FUE, {@code area × valor unitario}
     * (#48, #378); el total es la suma de esas lineas ya redondeadas, que es lo que el papel
     * imprime renglon a renglon. <b>No es {@link #VALOR_DE_OBRA}</b>: aquel es la obra
     * complementaria del autovaluo predial (RT-005); este es la obra que autoriza una licencia de
     * edificacion.
     */
    VALOR_DE_OBRA_DEL_FUE
}
