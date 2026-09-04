package kamayuk.rentas.dominio;

/**
 * Identidad de una municipalidad dentro del producto.
 *
 * <p>Existe como tipo propio, y no como {@code long}, porque el codigo mantiene la municipalidad
 * como concepto explicito: es lo que deja abierta la puerta a volver a un aislamiento por esquema
 * si alguna vez hubiera que reabrir ADR-0002.
 *
 * <p>No aparece en la firma de ningun metodo de dominio: sale del token y se fija una sola vez en
 * {@link kamayuk.rentas.compartido.TenantContext} (ARQ-03 §3.1). Lo verifica ArchUnit.
 */
public record MunicipalidadId(long valor) {

    public MunicipalidadId {
        if (valor <= 0) {
            throw new IllegalArgumentException(
                    "El identificador de municipalidad debe ser positivo");
        }
    }

    @Override
    public String toString() {
        return Long.toString(valor);
    }
}
