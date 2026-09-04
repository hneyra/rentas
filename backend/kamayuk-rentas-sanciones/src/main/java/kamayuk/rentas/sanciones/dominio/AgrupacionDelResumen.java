package kamayuk.rentas.sanciones.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Por qué se agrupan las papeletas en un resumen (#53, RF-073, #398).
 *
 * <p>Las pantallas de resumen del manual piden la misma cuenta agrupada por cosas distintas:
 * «pendientes y pagadas» por año, «por código de infracción» por su código y «por iniciales de
 * placa» por las dos primeras letras. Un solo agregado con cinco agrupadores, y no cinco consultas:
 * cinco consultas para la misma cuenta son cinco oportunidades de divergir, y la que se mira menos
 * es la que se queda mal.
 *
 * <h2>Estas cadenas se concatenan al SQL, y por eso son constantes</h2>
 *
 * <p>{@code GROUP BY} no admite parámetros de enlace: lo que llegue aquí acaba dentro de la
 * consulta sí o sí. Son constantes de un enumerado y <b>nunca</b> texto del cliente —el mismo
 * principio que {@code OrdenSeguro}, y el motivo por el que el controlador traduce el filtro de la
 * pantalla a uno de estos cinco valores en vez de pasarlo tal cual—.
 *
 * <h2>{@link #ANO} existe porque una columna decía «Año» y no había año (#398)</h2>
 *
 * <p>{@code transito_resumen_papeletas} dibuja «Año» como primera columna. Hasta #398 el agrupador
 * más parecido era {@link #ESTADO} —el que el controlador toma por omisión—, y conectarla así
 * habría puesto nombres de estado bajo un rótulo que dice «Año», que es exactamente lo que RNF-080
 * no permite. El agrupador que faltaba es este.
 */
public enum AgrupacionDelResumen {

    /** Una línea por estado de la papeleta: impuesta, notificada, pagada, coactiva… */
    ESTADO("p.estado", "p.estado", null, null),

    /** Una línea por código del catálogo de infracciones, con su descripción. */
    CODIGO("ci.codigo", "ci.codigo, ci.descripcion", "ci.descripcion", null),

    /**
     * Una línea por las dos letras iniciales de la placa.
     *
     * <p>{@code left(placa, 2)} en el {@code GROUP BY}, no en el {@code WHERE}: agrupar no es
     * filtrar, y el filtro por un prefijo concreto sigue yendo por rango para que el índice sirva.
     */
    PLACA("left(p.placa, 2)", "left(p.placa, 2)", null, null),

    /**
     * Una línea por mes de la fecha de infracción.
     *
     * <p>Agrupa además por el año, y no es redundante: {@code 'YYYY-MM'} ya lo determina, pero
     * PostgreSQL no lo deduce, y sin esa segunda columna en el {@code GROUP BY} la expresión del
     * año del {@code SELECT} sería «una columna que no está agrupada». Los grupos son exactamente
     * los mismos.
     */
    MES(
            "to_char(p.fecha_infraccion, 'YYYY-MM')",
            "to_char(p.fecha_infraccion, 'YYYY-MM'), to_char(p.fecha_infraccion, 'YYYY')",
            null,
            "to_char(p.fecha_infraccion, 'YYYY')::int"),

    /** Una línea por año de la fecha de infracción (#398). */
    ANO(
            "to_char(p.fecha_infraccion, 'YYYY')",
            "to_char(p.fecha_infraccion, 'YYYY')",
            null,
            "to_char(p.fecha_infraccion, 'YYYY')::int");

    private final String expresion;
    private final String agrupacion;
    private final @Nullable String descripcion;
    private final @Nullable String ano;

    AgrupacionDelResumen(
            String expresion,
            String agrupacion,
            @Nullable String descripcion,
            @Nullable String ano) {
        this.expresion = expresion;
        this.agrupacion = agrupacion;
        this.descripcion = descripcion;
        this.ano = ano;
    }

    /** La expresión SQL que produce la clave de la línea. */
    public String expresion() {
        return expresion;
    }

    /** Las columnas del {@code GROUP BY}, que incluyen la descripción o el año cuando los hay. */
    public String agrupacion() {
        return agrupacion;
    }

    /**
     * La expresión SQL de la descripción de la clave, o {@code null} si este agrupador no tiene.
     *
     * <p>Solo el código del catálogo la tiene. Un estado se explica solo y unas iniciales de placa
     * no describen nada; inventar un texto para ellas sería dibujar en la hoja una columna que no
     * dice nada.
     */
    public @Nullable String descripcion() {
        return descripcion;
    }

    /**
     * La expresión SQL del <b>año</b> de la línea, o {@code null} si este agrupador no determina
     * ninguno (#398).
     *
     * <p>Solo {@link #ANO} y {@link #MES} lo determinan. Agrupar por estado, por código o por
     * iniciales de placa mezcla años dentro de un mismo grupo: publicar ahí «el año» obligaría a
     * elegir uno —el primero, el último, el del rango— y cualquiera de los tres sería una cifra
     * plausible y falsa. Va nulo, y la columna «Año» de la pantalla enseña «—».
     */
    public @Nullable String ano() {
        return ano;
    }
}
