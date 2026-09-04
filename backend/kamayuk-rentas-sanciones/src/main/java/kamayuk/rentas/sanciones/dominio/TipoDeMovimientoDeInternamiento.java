package kamayuk.rentas.sanciones.dominio;

/**
 * Qué le pasa a un vehículo internado (V41: {@code internamiento_movimiento.tipo}).
 *
 * <p>El ingreso no está aquí: el ingreso <b>es</b> la fila de {@code internamiento}. Lo que esta
 * tabla registra es lo que ocurre después, y por eso solo tiene dos valores.
 */
public enum TipoDeMovimientoDeInternamiento {
    LIBERACION("Acta de liberacion de vehiculo internado"),
    ABANDONO("Declaracion de abandono de vehiculo internado");

    private final String titulo;

    TipoDeMovimientoDeInternamiento(String titulo) {
        this.titulo = titulo;
    }

    /** Cómo se titula el acta que lo materializa. */
    public String titulo() {
        return titulo;
    }

    /** Si este movimiento exige el recibo de la custodia y quién retira (AC de #50). */
    public boolean exigeCustodiaPagada() {
        return this == LIBERACION;
    }

    /** El tipo con el que se numera su documento emitido. */
    public String tipoDeDocumento() {
        return "ACTA_" + name();
    }
}
