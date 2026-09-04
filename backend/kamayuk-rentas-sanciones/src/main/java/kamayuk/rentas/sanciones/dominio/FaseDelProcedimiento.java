package kamayuk.rentas.sanciones.dominio;

/**
 * En qué fase del <b>procedimiento sancionador</b> está una infracción administrativa (#397,
 * RF-071).
 *
 * <h2>Esto NO es {@link EstadoDePapeleta}, y por eso son dos tipos</h2>
 *
 * <p>Son dos cosas distintas y el manual las escribe con dos vocabularios distintos:
 *
 * <ul>
 *   <li><b>El procedimiento</b> —esto— va {@code PREVENTIVA} → {@code CONSTATADA} → {@code
 *       SANCIONADA} → {@code PAGADA} → {@code COACTIVA}. Es lo que dibuja el filtro «Estado» de la
 *       pantalla «Infracción administrativa» y lo que describe su propio subtítulo: «notificación
 *       preventiva, acta de constatación y resolución de infracción y sanción».
 *   <li><b>La deuda</b> —{@link EstadoDePapeleta}— va {@code IMPUESTA} → {@code NOTIFICADA} →
 *       {@code RESUELTA} → {@code PAGADA} → {@code COACTIVA}, más {@code ANULADA} y {@code
 *       PRESCRITA}. Es la columna {@code papeleta.estado} (V4), y es lo que miran los padrones y
 *       los estados de cuenta.
 * </ul>
 *
 * <p>Coinciden en la cola —{@code PAGADA} y {@code COACTIVA} son la misma noticia en los dos
 * idiomas— y difieren en la cabeza, que es donde está el trabajo del procedimiento. <b>Ninguno de
 * los dos se renombra para parecerse al otro</b> (RNF-080): los dos son del manual. Lo que se hace
 * es publicarlos con nombres distintos —{@code fase} y {@code estadoDeLaDeuda}— para que ninguna
 * pantalla pueda dibujar uno donde promete el otro, que es exactamente el defecto por el que {@code
 * infracciones_adm} se quedó sin conectar en #78.
 *
 * <h2>Se DERIVA de los hechos; no hay columna que actualizar</h2>
 *
 * <p>Es la misma decisión —y el mismo motivo— que ya tomaron {@code descargo} y {@code
 * internamiento} en V41 (§2 y §5), {@code expediente_coactivo} en V33 y {@code cierre_caja} en V32:
 * el estado sale de los hechos registrados, nunca de un campo que alguien tenga que mantener al
 * día. Guardarlo aquí crearía <b>dos verdades</b> sobre la misma fila —la columna y los actos— y la
 * que se lee en pantalla sería la que nadie recalculó.
 *
 * <p>Los hechos son tres, y cada uno tiene dueño:
 *
 * <ol>
 *   <li>{@code papeleta.estado}, para la cola del procedimiento. Que el cobro y el pase a coactiva
 *       se digan en el vocabulario de la deuda no es un accidente: <b>son</b> de la deuda.
 *   <li>Que exista una {@code resolucion_gerencia} de tipo {@code ADMINISTRATIVA} sobre la papeleta
 *       (V41 §3) — la RIS que la pantalla emite con «Emitir RIS».
 *   <li>Que la notificación preventiva que originó el acta ({@code
 *       papeleta.notificacion_previa_id}, V4) siga <b>abierta</b>: {@code EMITIDA} y con su plazo
 *       sin vencer a la fecha de corte. El vencimiento se calcula igual que en {@code
 *       adm_notificaciones_vencidas} —{@code fecha + plazo_dias}, y sin plazo nada la vence (#47
 *       AC3)—, de modo que las dos pantallas nunca pueden discrepar sobre si una notificación sigue
 *       viva.
 * </ol>
 *
 * <h2>Una fila puede no tener fase, y entonces lo dice</h2>
 *
 * <p>Una papeleta {@code ANULADA} o {@code PRESCRITA} es un procedimiento que terminó sin que
 * ninguna de las cinco palabras del manual lo nombre. La expresión devuelve {@code NULL} y la
 * pantalla dibuja «—». Elegir «la más parecida» —{@code CONSTATADA}, que es la que saldría sola—
 * pondría en la grilla una cifra plausible y equivocada, que es peor que un hueco visible.
 *
 * <h2>Por qué la expresión SQL vive aquí</h2>
 *
 * <p>Mismo motivo que {@link AgrupacionDelResumen}: es una constante que se concatena a la
 * consulta, nunca texto del cliente. Y está <b>una sola vez</b> a propósito, porque el {@code
 * SELECT} que la publica y el {@code WHERE} que la filtra tienen que decir lo mismo: dos copias del
 * mismo {@code CASE} son dos copias que divergen, y la que se mira menos —el filtro— dejaría de
 * encontrar lo que la columna enseña.
 */
public enum FaseDelProcedimiento {

    /**
     * La notificación preventiva sigue abierta: el administrado todavía está en plazo de subsanar,
     * y el procedimiento no ha pasado de ahí.
     */
    PREVENTIVA,

    /** El acta de constatación está levantada y todavía no se ha dictado la RIS. */
    CONSTATADA,

    /** Hay resolución de infracción y sanción sobre esta acta. */
    SANCIONADA,

    /** La multa se pagó ({@link EstadoDePapeleta#PAGADA}). */
    PAGADA,

    /** La multa pasó a cobranza coactiva ({@link EstadoDePapeleta#COACTIVA}). */
    COACTIVA;

    /**
     * La expresión SQL que resuelve la fase de una fila de {@code papeleta}.
     *
     * <p>Espera los alias {@code p} —la papeleta— y {@code np} —el {@code LEFT JOIN} con su
     * notificación previa—, y el parámetro con nombre {@code aLaFecha}: la fase de un procedimiento
     * cuya preventiva vence mañana es otra mañana, y una lectura que resolviera «hoy» con {@code
     * current_date} devolvería cosas distintas el mismo día según a qué hora se pida (RNF-075).
     *
     * <p>El orden de las ramas es el del procedimiento leído <b>desde el final</b>: la fase más
     * avanzada gana. Una papeleta pagada después de la RIS dice {@code PAGADA}, que es lo que la
     * pantalla del manual enseña, y no {@code SANCIONADA}, que también sería cierto y ya no es la
     * noticia.
     */
    public static final String EXPRESION =
            "CASE"
                    // Ni ANULADA ni PRESCRITA tienen palabra en el vocabulario del
                    // procedimiento. Se dice que no la hay; no se elige la mas parecida.
                    + " WHEN p.estado IN ('ANULADA', 'PRESCRITA') THEN NULL"
                    + " WHEN p.estado = 'COACTIVA' THEN 'COACTIVA'"
                    + " WHEN p.estado = 'PAGADA' THEN 'PAGADA'"
                    + " WHEN EXISTS (SELECT 1 FROM resolucion_gerencia rg"
                    + "               WHERE rg.papeleta_id = p.id"
                    + "                 AND rg.tipo = 'ADMINISTRATIVA') THEN 'SANCIONADA'"
                    // La preventiva sigue abierta: EMITIDA y sin vencer a la fecha de
                    // corte. El vencimiento se escribe igual que en la consulta de
                    // notificaciones vencidas, y sin plazo nada la vence (#47 AC3).
                    + " WHEN np.id IS NOT NULL AND np.estado = 'EMITIDA'"
                    + "      AND (np.plazo_dias IS NULL"
                    + "           OR (np.fecha + (np.plazo_dias || ' days')::interval)"
                    + "              > :aLaFecha) THEN 'PREVENTIVA'"
                    + " ELSE 'CONSTATADA'"
                    + " END";
}
