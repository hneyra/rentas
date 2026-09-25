package kamayuk.rentas.seguridad.dominio;

/**
 * Lo que la fila de {@code municipalidad} dice despues del alta: su identificador y su regimen.
 *
 * <h2>Por que existe, y que defecto cierra</h2>
 *
 * <p>El alta es idempotente y no toca una fila que ya existe (#122), asi que lo que se <b>pidio</b>
 * y lo que <b>quedo</b> pueden no coincidir: una municipalidad dada de alta como demostracion y
 * relanzada con {@code esDemostracion=false} sigue marcada. La implantacion registraba el regimen
 * pedido, y en ese caso su linea afirmaba «instalacion real» mientras todo documento salia marcado
 * —o al reves, que es peor: «DEMOSTRACION» con papeles sin marca— (#348). Esa linea existe porque
 * es el unico sitio donde el regimen se comprueba sin mirar un papel.
 *
 * <p>Con este valor, la fuente de verdad es <b>una</b>: la fila, leida en la misma conexion que la
 * dio de alta. La peticion solo sirve para compararla con ella.
 *
 * <p>No lleva {@code MunicipalidadId} sino un {@code long}, por lo mismo que {@link Municipalidad}:
 * el tipo del dominio no aparece en ninguna firma (regla 2, ARQ-03 §3.1).
 *
 * @param id el identificador de la fila, el que llevara el claim del token
 * @param esDemostracion {@code municipalidad.es_demostracion} tal como esta en la base, que es lo
 *     que lee {@code RegimenDeLaInstalacionJdbc} para marcar los documentos
 */
public record MunicipalidadImplantada(long id, boolean esDemostracion) {

    public MunicipalidadImplantada {
        if (id <= 0) {
            throw new IllegalArgumentException(
                    "El identificador de municipalidad debe ser positivo");
        }
    }

    /** La palabra con que el registro de la implantacion dice el regimen. */
    public String regimen() {
        return esDemostracion ? "DEMOSTRACION" : "instalacion real";
    }
}
