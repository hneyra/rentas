package kamayuk.rentas.fiscalizacion.infraestructura;

/**
 * «Lo declarado de un predio en un ejercicio», escrito <b>una</b> vez para las dos lecturas que lo
 * publican (#344): la detección de omisos y el contraste del acta.
 *
 * <p>Hasta #344 eran dos transcripciones de la misma pregunta con dos respuestas distintas. La
 * detección leía la ficha que referencia la declaración jurada del ejercicio —lo que el titular
 * declaró—, y el acta leía la ficha vigente el día de la visita —lo que catastro tenía inscrito—.
 * Las dos salían de {@code ficha_ref}, pero de otra fila: a un omiso el acta le publicaba un área
 * «declarada», y a quien declaró 200 m² sobre una ficha que catastro amplió después le publicaba
 * diferencia cero mientras su liquidación determinaba la diferencia entera. Con el fragmento aquí,
 * las dos lecturas no pueden volver a separarse sin que una de las dos deje de compilar contra él.
 *
 * <p>Pide un parámetro {@code :estados} con {@link #ESTADOS_VIGENTES}, y entra con {@code LEFT
 * JOIN}: sin declaración vigente, {@code dj} y {@code fd} salen nulos y eso es lo que hace omiso a
 * alguien. Ninguno de los dos cambia el número de filas: el {@code LATERAL} lleva {@code LIMIT 1} y
 * {@code fd} entra por la clave de la versión de ficha.
 */
final class LaDeclaracionDelEjercicio {

    /**
     * Los dos estados en que una declaración sustenta algo, escritos aquí y no leídos de {@code
     * EstadoDeDeclaracion}: ese enumerado es {@code rentas.dominio}, y este contexto sólo importa
     * el paquete raíz de los demás (ARQ-01 §4 regla 1). Que los dos digan lo mismo lo comprueba
     * {@code DeteccionDeOmisosJdbcTest}, que siembra una declaración {@code SUSTITUIDA} y otra
     * {@code ANULADA} y exige que su predio salga OMISO.
     */
    static final String[] ESTADOS_VIGENTES = {"PRESENTADA", "OBSERVADA"};

    private LaDeclaracionDelEjercicio() {}

    /**
     * La declaración vigente del ejercicio ({@code dj}) y la versión de ficha que referencia
     * ({@code fd}), unidas a la fila de quien la pregunta.
     *
     * @param municipalidad la expresión SQL de la municipalidad de la fila, p. ej. {@code
     *     p.municipalidad_id}
     * @param predio la del predio
     * @param ejercicio la del ejercicio: un parámetro ({@code :ejercicio}) o una columna
     */
    static String unidaA(String municipalidad, String predio, String ejercicio) {
        return """
                 LEFT JOIN LATERAL (
                       SELECT d.id, d.fuera_de_plazo, d.ficha_catastral_id
                         FROM declaracion_jurada d
                        WHERE d.municipalidad_id = %1$s
                          AND d.predio_id = %2$s
                          AND d.ejercicio = %3$s
                          AND d.estado = ANY(:estados)
                        ORDER BY d.fecha_presentacion DESC, d.id DESC
                        LIMIT 1
                     ) dj ON true
                 LEFT JOIN ficha_ref fd
                   ON fd.municipalidad_id = %1$s
                  AND fd.ficha_id = dj.ficha_catastral_id
                """
                .formatted(municipalidad, predio, ejercicio);
    }
}
