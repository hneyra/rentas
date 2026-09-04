package kamayuk.rentas.auditoria;

/**
 * Que clase de acto administrativo se esta registrando.
 *
 * <p>Son exactamente los valores que admite la restriccion {@code auditoria.operacion} del esquema:
 * si aqui apareciera uno mas, la insercion fallaria en tiempo de ejecucion, que es tarde. Al
 * agregar uno hay que tocar los dos sitios, y el diff lo muestra.
 *
 * <p>Nota sobre lo que <b>no</b> hay: {@code ELIMINACION}. La aplicacion no borra (RNF-051); lo que
 * parece un borrado es una {@link #BAJA}, una {@link #ANULACION} o una {@link #REVERSION}, segun de
 * que se trate, y cada una deja la fila original en su sitio.
 */
public enum Operacion {

    /** Se creo un registro. */
    ALTA,

    /** Se cambio un registro existente; los datos anteriores quedan en la propia auditoria. */
    MODIFICACION,

    /** Se desactivo sin borrar: un usuario fuera de un grupo, una via retirada del catalogo. */
    BAJA,

    /** Un acto administrativo queda sin efecto: un recibo, un valor, una papeleta. */
    ANULACION,

    /** Un asiento del libro se compensa con otro de signo contrario (ADR-0006). */
    REVERSION,

    /**
     * Cambio en la configuracion de seguridad.
     *
     * <p>El manual no pide auditar esto; ADR-0008 §5 lo agrega. Sin ello, quien administra la
     * seguridad puede alterar su propia pista, que es el unico agujero que deja una auditoria por
     * lo demas completa.
     */
    PERMISO,

    /** Entrada o salida del sistema. */
    ACCESO
}
