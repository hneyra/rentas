package kamayuk.rentas.autorizacion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * El cliente confidencial con que un sistema del producto pide su token: {@code
 * kamayuk-<sistema>-servicio-<ubigeo>} (ADR-0028 §2), leido del {@code azp}.
 *
 * <h2>Una sola copia de la forma en este repositorio (#429)</h2>
 *
 * <p>La forma la compone {@code clienteDeServicio()} de {@code
 * infra/verificaciones/identidad-de-servicio.ts} en {@code infrastructure}, y aqui se lee en dos
 * sitios: el guardia, para saber si quien llama a una operacion de servicio es el sistema que la
 * operacion espera ({@link RequiereIdentidadDeServicio}), y el consumidor del buzon de {@code
 * identidad}, para saber de que municipalidad es el buzon que va a leer. Hasta #429 el segundo
 * tenia su propia expresion; con dos, la que se quedaria vieja es la que nadie compara.
 *
 * <p>El sistema se captura <b>sin enumerar los que existen</b> —igual que {@code Consumidor} de
 * {@code identidad}—: un {@code azp} de {@code kamayuk-catastro-servicio-…} tiene la forma y falla
 * <b>diciendo de quien es</b>, en vez de salir como «esto no parece un cliente de servicio», que
 * manda a mirar el emisor cuando lo que pasa es que llamo otro sistema. A quien si se espera lo
 * dice cada sitio que lo usa, con {@link #esDe(String)}.
 *
 * <p>No es dominio tributario ni lo pretende: es la forma de una credencial, y vive junto al
 * guardia que la exige. Sin Spring y sin reloj, para que se pruebe sin levantar nada.
 *
 * @param sistema el sistema del producto al que pertenece el cliente, en minusculas
 * @param ubigeo los seis digitos del INEI de la municipalidad para la que se emitio
 */
public record ClienteDeServicio(String sistema, String ubigeo) {

    /** {@code kamayuk-<sistema>-servicio-<ubigeo>}, con el ubigeo de seis digitos del INEI. */
    private static final Pattern FORMA = Pattern.compile("^kamayuk-([a-z]+)-servicio-([0-9]{6})$");

    /** Como se escribe la forma en un mensaje. */
    public static final String FORMA_LEGIBLE = "kamayuk-<sistema>-servicio-<ubigeo>";

    public ClienteDeServicio {
        if (!FORMA.matcher(azpDe(sistema, ubigeo)).matches()) {
            throw new IllegalArgumentException(
                    "«"
                            + azpDe(sistema, ubigeo)
                            + "» no tiene la forma «"
                            + FORMA_LEGIBLE
                            + "» de ADR-0028 §2");
        }
    }

    /**
     * El cliente que nombra ese {@code azp}, o el rechazo que dice por que no nombra ninguno.
     *
     * @param azp el claim {@code azp} del token ya validado, o el cliente configurado; {@code null}
     *     si no viene
     * @throws NoEsUnClienteDeServicio si falta o no tiene la forma
     */
    public static ClienteDeServicio desdeAzp(@Nullable String azp) {
        if (azp == null || azp.isBlank()) {
            throw new NoEsUnClienteDeServicio(
                    "El token no trae `azp`, asi que no dice que cliente lo pidio. Esta operacion"
                            + " la llama un sistema con su cuenta de servicio («"
                            + FORMA_LEGIBLE
                            + "», ADR-0028 §2)");
        }
        Matcher forma = FORMA.matcher(azp.strip());
        if (!forma.matches()) {
            throw new NoEsUnClienteDeServicio(
                    "«"
                            + azp
                            + "» no es el cliente de una cuenta de servicio: se esperaba «"
                            + FORMA_LEGIBLE
                            + "» (ADR-0028 §2). Un token de usuario —el de la interfaz— no la"
                            + " sustituye, tenga los privilegios que tenga");
        }
        return new ClienteDeServicio(forma.group(1), forma.group(2));
    }

    /** Si es el cliente de ese sistema. */
    public boolean esDe(String sistemaEsperado) {
        return sistema.equals(sistemaEsperado);
    }

    /** La otra direccion de {@link #desdeAzp(String)}: el {@code azp} que lo nombra. */
    public String azp() {
        return azpDe(sistema, ubigeo);
    }

    private static String azpDe(String sistema, String ubigeo) {
        return "kamayuk-" + sistema + "-servicio-" + ubigeo;
    }

    /**
     * El {@code azp} no nombra ningun cliente de servicio.
     *
     * <p>Un tipo propio y no una {@code IllegalArgumentException} porque el guardia lo contesta con
     * {@code SIN_IDENTIDAD_DE_SERVICIO} y no con {@code SIN_PRIVILEGIO}: lo que falta no se arregla
     * concediendo un privilegio, sino pidiendo el token con el cliente que toca.
     */
    public static final class NoEsUnClienteDeServicio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String motivo;

        public NoEsUnClienteDeServicio(String mensaje) {
            super(mensaje);
            this.motivo = mensaje;
        }

        /** Lo mismo que {@code getMessage()}, sin nulo posible. */
        public String motivo() {
            return motivo;
        }
    }
}
