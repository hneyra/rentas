package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que le paso a un FUE (#48, V43 §7).
 *
 * <p>Tres, y es deliberado. La pantalla enumera cinco tramites, pero dos de ellos <b>no le pasan a
 * este expediente</b>: producen otro. Una ampliacion es un FUE nuevo con su numero y su vigencia
 * (AC 3), y un anteproyecto en consulta no llega a licencia. Meterlos aqui haria que el estado
 * derivado dejara de significar nada: «AMPLIADA» no es un estado de la licencia ampliada, es la
 * existencia de otra —el mismo argumento con que {@code TipoDeMovimientoDeLicencia} dejo fuera la
 * renovacion y el cambio de titular en #44—.
 *
 * <p>La revalidacion <b>si</b> esta, y no se contradice con lo anterior: no produce otra licencia,
 * agrega un tramo de vigencia a la que ya existe (AC 4).
 */
public enum TipoDeMovimientoDeEdificacion {

    /** Se otorgo la licencia: es el movimiento que la numera y le da su primera vigencia. */
    EMISION("Licencia de edificacion"),

    /** Se prorrogo el plazo. La licencia sigue siendo la misma; la vigencia es otra. */
    REVALIDACION("Resolucion de revalidacion de licencia de edificacion"),

    /** La licencia quedo sin efecto, con su resolucion y su motivo (regla 4, RNF-051). */
    ANULACION("Resolucion de anulacion de licencia de edificacion");

    private final String titulo;

    TipoDeMovimientoDeEdificacion(String titulo) {
        this.titulo = titulo;
    }

    /** El titulo del documento que lo materializa. */
    public String titulo() {
        return titulo;
    }

    public static TipoDeMovimientoDeEdificacion porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
