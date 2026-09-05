package kamayuk.rentas.nucleo.dominio.proyeccion;

import java.time.Instant;

/**
 * El escritor de la proyeccion local de {@code catastro} (C-8, `V4`, `V5`, `V9`).
 *
 * <h2>Lo implementa un adaptador que se conecta con OTRO ROL</h2>
 *
 * <p>{@code rol_ingestor_catastro} y no {@code sgtm_app}: `V4` y `V5` no le dan a la aplicacion mas
 * que {@code SELECT} sobre las cuatro proyecciones, y eso no es una precaucion — es lo que hace
 * cierto ADR-0027 §3 en vez de una promesa. Una proyeccion que la aplicacion pueda escribir deja de
 * ser una proyeccion el dia que alguien «arregle» una fila a mano.
 *
 * <h2>Las tres negativas se distinguen, y se arreglan distinto</h2>
 *
 * <p>Es la misma separacion que `caja` hizo entre {@code NoContesta} y {@code Rechazado}, con una
 * mas: aqui hay un caso que no es un fallo sino una decision — <b>descartar un hecho viejo</b>.
 */
public interface ProyeccionDeCatastro {

    /** Que se hizo con un hecho. */
    enum Aplicacion {
        /** Se escribio. */
        APLICADO,
        /**
         * Ya estaba, con el mismo contenido.
         *
         * <p>Es lo que pasa cada vez que un acuse se pierde despues de confirmar, y es el caso
         * normal de una entrega al menos una vez. No es un error y no se cuenta como tal.
         */
        YA_APLICADO,
        /**
         * El hecho es VIEJO: la fila que hay salio de un hecho con secuencia mayor.
         *
         * <p><b>Se descarta y se DICE</b> (`V4`). Descartarlo en silencio es la mitad del defecto
         * que la secuencia existe para impedir: la fila queda plausible y nadie sabe que se ignoro
         * un hecho.
         */
        DESCARTADO_POR_VIEJO
    }

    /**
     * Aplica el hecho a la proyeccion, en una transaccion con contexto de tenant.
     *
     * @throws NoSePuedeAplicar si el hecho no se podra aplicar nunca. NO se reintenta
     */
    Aplicacion aplicar(HechoRecibido hecho, Instant cuando);

    /** Aparta un hecho que no se puede aplicar, para que deje de bloquear la cola. */
    void matar(HechoRecibido hecho, String motivo, Instant cuando);

    /** Cuantos hechos hay apartados y sin explicar. */
    long muertosSinExplicar();

    /**
     * El hecho no se puede aplicar, y no va a poder aplicarse reintentandolo.
     *
     * <p>Se distingue de {@link FuenteDeHechosDeCatastro.CatastroNoContesta} a proposito: aquello
     * se arregla levantando un despliegue y esto mirando por que el hecho no encaja. Confundirlos
     * hace que un hecho perfectamente aplicable muera porque la base estaba caida, o que uno
     * imposible se reintente para siempre bloqueando la cola detras de el.
     */
    final class NoSePuedeAplicar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public NoSePuedeAplicar(String mensaje) {
            super(mensaje);
        }

        public NoSePuedeAplicar(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
