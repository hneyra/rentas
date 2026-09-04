package kamayuk.rentas.rentas.dominio.arbitrios;

/**
 * Los tres arbitrios que el manual determina por predio (#31): limpieza pública, parques y
 * jardines, y serenazgo. Los valores coinciden exactamente con el {@code CHECK} de {@code
 * determinacion_arbitrio.servicio} (V23): si aquí apareciera un cuarto, el insert fallaría en
 * tiempo de ejecución, que es tarde.
 *
 * <p>Cada servicio se excluye por separado: un predio sin recojo de residuos puede seguir pagando
 * serenazgo, así que la exclusión de un beneficio (#27) se busca por {@link #codigoTributo()}, no
 * por «arbitrios» en general.
 */
public enum Servicio {
    LIMPIEZA_PUBLICA("ARB_LIMPIEZA"),
    PARQUES_JARDINES("ARB_PARQUES"),
    SERENAZGO("ARB_SERENAZGO");

    private final String codigoTributo;

    Servicio(String codigoTributo) {
        this.codigoTributo = codigoTributo;
    }

    /**
     * El código con el que este servicio aparece en {@code beneficio.tributo} (regla del dominio
     * compartido: como mucho 20 caracteres — ver {@code Beneficio}).
     */
    public String codigoTributo() {
        return codigoTributo;
    }
}
