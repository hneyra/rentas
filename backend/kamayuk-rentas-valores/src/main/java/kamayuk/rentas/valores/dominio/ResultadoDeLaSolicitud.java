package kamayuk.rentas.valores.dominio;

/**
 * Como se resuelve una solicitud de prescripcion que abarca varios ejercicios.
 *
 * <p>{@link #PROCEDE_EN_PARTE} no es un matiz burocratico: una solicitud pide un rango —"2015 a
 * 2020"— y el computo se resuelve ejercicio por ejercicio, asi que lo normal es que los primeros
 * hayan prescrito y los ultimos no. Resolver el rango entero con un si o un no obligaria a
 * redondear en una direccion: hacia el contribuyente, extinguiendo deuda viva; o hacia la
 * municipalidad, cobrando lo prescrito.
 */
public enum ResultadoDeLaSolicitud {

    /** Todos los ejercicios solicitados prescribieron. */
    PROCEDE,

    /** Unos si y otros no. */
    PROCEDE_EN_PARTE,

    /** Ninguno. */
    NO_PROCEDE;

    /** Resume el computo de los ejercicios: cuantos prescribieron de cuantos se pidieron. */
    public static ResultadoDeLaSolicitud de(int prescritos, int solicitados) {
        if (solicitados <= 0) {
            throw new IllegalArgumentException(
                    "Una solicitud sin ejercicios no resuelve nada: " + solicitados);
        }
        if (prescritos < 0 || prescritos > solicitados) {
            throw new IllegalArgumentException(
                    "No pueden prescribir " + prescritos + " de " + solicitados + " ejercicios");
        }
        if (prescritos == 0) {
            return NO_PROCEDE;
        }
        return prescritos == solicitados ? PROCEDE : PROCEDE_EN_PARTE;
    }
}
