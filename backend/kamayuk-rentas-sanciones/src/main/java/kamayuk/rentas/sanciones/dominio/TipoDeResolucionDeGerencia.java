package kamayuk.rentas.sanciones.dominio;

/**
 * Qué resolución de gerencia es (V41: {@code resolucion_gerencia.tipo}).
 *
 * <p>Tres tipos y una sola tabla, por lo mismo que {@code papeleta} es una sola para las dos
 * familias: mismo esqueleto, distinta base legal (ARQ-01 §3.6).
 *
 * <ul>
 *   <li>{@link #ORDINARIA} — la que ordena la cobranza de la papeleta de tránsito y abre el plazo
 *       de pago. Pantalla {@code transito_rg_ordinaria} (RF-074).
 *   <li>{@link #SANCIONADORA} — la que la sigue, con carácter sancionador, y deriva la sanción
 *       accesoria a la Dirección Regional de Transportes. Pantalla {@code
 *       transito_rg_sancionadora}.
 *   <li>{@link #ADMINISTRATIVA} — la del procedimiento administrativo sancionador municipal.
 *       Pantalla {@code adm_resolucion_gerencia} (RF-065).
 * </ul>
 */
public enum TipoDeResolucionDeGerencia {
    ORDINARIA("Resolucion de gerencia ordinaria", "RGO"),
    SANCIONADORA("Resolucion de gerencia sancionadora", "RGS"),
    ADMINISTRATIVA("Resolucion de gerencia", "RGA");

    private final String titulo;
    private final String tipoDeDocumento;

    TipoDeResolucionDeGerencia(String titulo, String tipoDeDocumento) {
        this.titulo = titulo;
        this.tipoDeDocumento = tipoDeDocumento;
    }

    /** Cómo se titula el documento que la materializa. */
    public String titulo() {
        return titulo;
    }

    /**
     * Si esta resolución exige que la ordinaria esté notificada y con el plazo vencido.
     *
     * <p>Solo la sancionadora: «segunda resolución, emitida luego de la ordinaria» (pantalla {@code
     * transito_rg_sancionadora}). Es el análogo exacto de {@code
     * TipoDeActoCoactivo.exigeRec1Vencida()} en el procedimiento coactivo (#41).
     */
    public boolean exigeOrdinariaVencida() {
        return this == SANCIONADORA;
    }

    /**
     * El tipo con el que se numera su documento emitido: {@code RGO-2026-000001}.
     *
     * <p><b>Tres letras, y no {@code RG_ORDINARIA}, por una restricción de la base.</b> La
     * resolución se notifica con filas de {@code notificacion}, cuya columna {@code numero} es
     * {@code varchar(20)} desde V3, y el número de una diligencia es {@code «numero del acto» /
     * «intento»} —el patrón que #39 fijó y #41 repitió—. Con un prefijo largo, {@code
     * RG_ORDINARIA-2026-000001/1} son 26 caracteres y la segunda notificación de la primera
     * resolución del año no entraría. Se descubrió ejecutando, no leyendo.
     */
    public String tipoDeDocumento() {
        return tipoDeDocumento;
    }
}
