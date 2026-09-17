package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Objects;

/**
 * En qué punto de su notificación está un acto de la papeleta (#185, RF-065).
 *
 * <h2>No es una columna, y no puede serlo</h2>
 *
 * <p>Por lo mismo que {@code coactiva.EstadoDelExpediente}: ninguno de los tres registros de los
 * que salen estos actos —{@code resolucion_gerencia}, {@code internamiento} y {@code
 * internamiento_movimiento}— admite {@code UPDATE}, así que una columna diría lo que dijo el día de
 * la emisión para siempre. El estado se <b>deriva</b> de las diligencias, y {@link #de} es el único
 * sitio donde se deriva: que sea uno solo es lo que impide que la tabla del expediente diga una
 * cosa y el acuse de al lado otra.
 *
 * <p>Función pura (regla 6): entran la clase del acto y sus acuses, y sale el estado. Sin base, sin
 * reloj y sin parámetros.
 *
 * <h2>Derivar el estado NO es resumir los acuses</h2>
 *
 * <p>{@code ConsultaDeActosDeLaPapeleta} prohíbe en su javadoc quedarse con la última diligencia:
 * «escondería que las dos anteriores no encontraron a nadie, que es justamente lo que hay que poder
 * mostrar cuando el administrado discute la notificación». Eso <b>sigue siendo verdad y sigue
 * cumpliéndose</b>: la respuesta publica {@code acuses[]} entero, una fila por intento, y este
 * campo se añade <b>al lado</b>, no en su lugar. Lo que se resume no es la traza sino el hecho que
 * la traza produce —si el acto llegó a surtir efecto o no—, y ese hecho no es «la última»: {@link
 * #NOTIFICADO} lo da <b>cualquiera</b> de las diligencias que surtiera efecto, aunque después
 * hubiera otras que no encontraran a nadie.
 *
 * <h2>Lo que este estado NO dice, y por qué no puede decirlo aquí</h2>
 *
 * <p>El artboard dibuja la columna «Estado» con «Conforme», «Por vencer» y «Pendiente», o sea un
 * estado <b>del plazo</b>. Aquí no está, y es una decisión medida: el plazo de sanciones no es un
 * literal —la regla 5 lo prohíbe— sino un valor del conjunto sellado que rige el ejercicio ({@code
 * PlazosDeSancionesParametrizados}), y pedirlo desde esta consulta haría que {@code GET
 * /transito/papeletas/&#123;numero&#125;/actos} lanzara {@code EjercicioSinSellar} —un 422— en toda
 * municipalidad sin conjunto sellado, que hoy son todas. Un listado que deja de contestar para
 * poder pintar una palabra es peor que la palabra que falta.
 *
 * <p>Lo que sí se puede decir sin ningún parámetro es esto: si el acto <b>surtió efecto</b>. Y es
 * justamente la mitad que no se puede inventar: una papeleta cuyo acto nunca llegó a notificarse
 * válidamente no abre plazo ninguno, así que escribir «Conforme» sobre ella afirmaría que todavía
 * se puede cobrar cuando puede estar caducada. Con {@link #NO_NOTIFICADO} delante, quien mire ve
 * que ahí no hay nada conforme.
 */
public enum EstadoDelActoDeLaPapeleta {

    /**
     * El acto no lleva notificación, y no es que le falte.
     *
     * <p>Las actas del depósito se entregan en mano al conductor o a quien retira, con su firma en
     * el papel: no hay diligencia que registrar. Decir {@link #SIN_DILIGENCIAR} de una de ellas
     * sería afirmar que alguien tiene pendiente notificarla.
     */
    SIN_NOTIFICACION,

    /** Emitido y todavía sin ningún intento de notificación. */
    SIN_DILIGENCIAR,

    /**
     * Hubo diligencias y <b>ninguna</b> surtió efecto.
     *
     * <p>El acto existe, y no abre plazo. Es el estado que no se puede confundir con conforme: un
     * acto así no hace exigible nada, y en una papeleta eso decide si todavía se puede cobrar.
     */
    NO_NOTIFICADO,

    /** Alguna diligencia surtió efecto, y desde ella corre el plazo. */
    NOTIFICADO;

    /**
     * El estado que describen estos acuses.
     *
     * <p>«Surtir efecto» no lo decide este enumerado: lo decide {@code
     * ResultadoDeNotificacion.surteEfecto()}, en el dominio compartido, que es donde está escrito
     * que {@code RECHAZADO} cuenta —negarse a recibir no deja al deudor sin notificar— y que {@code
     * NO_UBICADO} es el único que no. Repetir aquí ese criterio sería tener dos sitios que un día
     * difieren.
     *
     * @param seNotifica si este acto se notifica; las actas del depósito no
     * @param acuses las diligencias practicadas, todas
     */
    public static EstadoDelActoDeLaPapeleta de(boolean seNotifica, List<AcuseDelActo> acuses) {
        Objects.requireNonNull(acuses, "La lista de acuses es vacia, no nula");
        if (!seNotifica) {
            return SIN_NOTIFICACION;
        }
        if (acuses.isEmpty()) {
            return SIN_DILIGENCIAR;
        }
        for (AcuseDelActo acuse : acuses) {
            if (acuse.resultado().surteEfecto()) {
                return NOTIFICADO;
            }
        }
        return NO_NOTIFICADO;
    }

    /** Si el acto llegó a surtir efecto: lo único sobre lo que cabe hablar de plazos. */
    public boolean surtioEfecto() {
        return this == NOTIFICADO;
    }
}
