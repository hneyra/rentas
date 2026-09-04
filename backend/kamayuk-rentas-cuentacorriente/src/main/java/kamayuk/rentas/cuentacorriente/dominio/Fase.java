package kamayuk.rentas.cuentacorriente.dominio;

/**
 * En que etapa de la cobranza esta la obligacion que asienta un {@link Asiento} (V2).
 *
 * <p>No es un estado que este contexto calcule: quien asienta —tesoreria, valores, coactiva— es
 * quien sabe en que fase esta lo que registra. {@code cuentacorriente} solo lo guarda.
 */
public enum Fase {
    ORDINARIA,
    VALOR,
    COACTIVA,
    CONVENIO
}
