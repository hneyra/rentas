package kamayuk.rentas.nucleo;

/**
 * Si un vehiculo esta en el padron vehicular de esta municipalidad, publicado para los contextos
 * que lo citan por su identificador (#422).
 *
 * <p>Es la <b>API publica</b> de {@code rentas} para esa pregunta, y vive en el paquete raiz por lo
 * mismo que {@link DeclaracionesDelEjercicio}: Spring Modulith trata como interno todo lo que esta
 * en un subpaquete, y la alternativa —que {@code fiscalizacion} o {@code sanciones} consultaran
 * {@code vehiculo} directamente— cruzaria el limite del contexto que es dueno de la tabla.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Actas de fiscalizacion vehicular, constancias de no adeudo e internamientos guardan un {@code
 * vehiculo_id} con su clave foranea hacia {@code vehiculo}. Hasta #422 nadie preguntaba antes si
 * existia: un identificador que no esta en el padron llegaba al {@code INSERT} —en las emisiones,
 * despues de haber dibujado el papel— y la clave foranea lo rechazaba como un 500 con incidencia
 * ERROR. La clave sigue siendo la que lo <b>impide</b>; esta pregunta es la que permite <b>decir
 * que</b> —un 404 que nombra el identificador— sin gastar el trabajo.
 *
 * <p>Contesta un {@code boolean} y no el vehiculo: quien pregunta solo necesita saber si puede
 * citarlo, y entregarle el agregado seria invitarle a leer lo que no es suyo. Ningun metodo recibe
 * la municipalidad (regla 2): la pone la politica RLS de {@code vehiculo}.
 */
public interface PadronVehicular {

    /**
     * Si hay un vehiculo con ese identificador en el padron de esta municipalidad.
     *
     * <p>Falso no distingue «no existe» de «es de otra municipalidad», y esa es la respuesta
     * correcta: RLS ya hizo que las dos cosas sean la misma para quien pregunta, y la clave foranea
     * {@code (municipalidad_id, vehiculo_id)} tambien las trata igual.
     */
    boolean estaEnElPadron(long vehiculoId);
}
