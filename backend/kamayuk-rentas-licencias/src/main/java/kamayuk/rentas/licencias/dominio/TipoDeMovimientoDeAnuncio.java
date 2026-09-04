package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que le paso a la autorizacion de anuncio (#51, V45).
 *
 * <p>Cuatro, y dos de ellas <b>devengan tasa</b>. Esa es la division que importa en este contexto y
 * la que {@link #devenga()} publica: la deuda del anuncio no nace de una columna ni de un proceso
 * nocturno, nace de un acto, y cada acto que devenga deja su fila con la referencia del cargo que
 * pidio.
 *
 * <ul>
 *   <li>{@link #AUTORIZACION} y {@link #RENOVACION} devengan: la primera por el ejercicio en que se
 *       autoriza, la segunda por cada ejercicio que se renueva.
 *   <li>{@link #CESE} y {@link #RETIRO} no. Y no solo no devengan: el cese <b>impide</b> que se
 *       devengue mas, porque renovar exige que el estado derivado no sea CESADO ni RETIRADO. Es la
 *       mitad «detiene la deuda futura» del AC de #51; la otra mitad —«no borra la pasada»— la
 *       sostienen las tablas protegidas del escaner y la inmutabilidad del libro.
 * </ul>
 */
public enum TipoDeMovimientoDeAnuncio {

    /** Nace la autorizacion. Es el primer movimiento y no puede haber dos. */
    AUTORIZACION("Autorizacion de anuncio", true),

    /**
     * Se prorroga la vigencia por otro ejercicio, y se devenga otra vez la tasa.
     *
     * <p>No lleva indice unico propio, al reves que los otros tres: un anuncio se renueva todos los
     * anios. Lo que si es unico es su <b>cargo</b>, y por ejercicio ({@code
     * anuncio_movimiento_cargo_uq}).
     */
    RENOVACION("Renovacion de anuncio", true),

    /**
     * La autorizacion queda sin efecto, con su motivo.
     *
     * <p>No se borra la fila ni se reversa el cargo ya asentado (regla 4, RNF-051): se agrega esta.
     */
    CESE("Cese de la autorizacion", false),

    /**
     * El elemento se retiro, comprobado en campo.
     *
     * <p>Es el {@code chk} «Anuncio retirado» de la pantalla, y va despues del cese: primero la
     * autorizacion deja de regir, despues el soporte desaparece de la calle. Registrarlo sin cese
     * previo diria que se retiro un anuncio que sigue autorizado.
     */
    RETIRO("Retiro del elemento", false);

    private final String titulo;
    private final boolean devenga;

    TipoDeMovimientoDeAnuncio(String titulo, boolean devenga) {
        this.titulo = titulo;
        this.devenga = devenga;
    }

    /** Como se lee en el historial de la pantalla. */
    public String titulo() {
        return titulo;
    }

    /**
     * Si este acto pide un cargo por la tasa. Lo comprueba tambien {@code
     * anuncio_movimiento_devengo_ck}.
     */
    public boolean devenga() {
        return devenga;
    }

    /** Si este acto exige motivo. Lo comprueba tambien {@code anuncio_movimiento_motivo_ck}. */
    public boolean exigeMotivo() {
        return this == CESE || this == RETIRO;
    }

    public static TipoDeMovimientoDeAnuncio porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
