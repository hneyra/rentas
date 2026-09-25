package kamayuk.rentas.sanciones.aplicacion;

/**
 * El vehiculo que la peticion nombra por su identificador no esta en el padron vehicular de esta
 * municipalidad (#422).
 *
 * <p>La lanzan los dos casos de uso que emiten un papel sobre un vehiculo del padron —la constancia
 * de no adeudo y el acta de internamiento—, y los dos <b>antes</b> de dibujarlo. Hasta #422 nadie
 * preguntaba: el papel se renderizaba, se insertaba {@code documento_emitido}, la clave foranea
 * hacia {@code vehiculo} rechazaba la fila y todo se revertia como un 500 con incidencia ERROR. La
 * clave sigue siendo la que lo impide; esto es lo que permite decir que, con un 404.
 *
 * <p>Una placa que no esta inscrita se sigue admitiendo sin {@code vehiculoId}: se interna lo que
 * se interna y se acredita lo que se pide. Lo que no se admite es citar un identificador que no
 * existe.
 */
public final class VehiculoFueraDelPadron extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    VehiculoFueraDelPadron(long vehiculoId) {
        super(
                "No hay ningun vehiculo con identificador "
                        + vehiculoId
                        + " en el padron vehicular de esta municipalidad");
    }
}
