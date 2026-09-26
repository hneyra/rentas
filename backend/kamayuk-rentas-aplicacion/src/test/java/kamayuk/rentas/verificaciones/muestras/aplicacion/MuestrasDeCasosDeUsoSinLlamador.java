package kamayuk.rentas.verificaciones.muestras.aplicacion;

import org.springframework.stereotype.Service;

/**
 * Muestras para {@code CasosDeUsoSinLlamadorTest} (#394): lo que la regla tiene que acusar y lo que
 * no.
 *
 * <p>Viven en {@code src/test}: la regla de produccion solo importa {@code src/main}, asi que no
 * pueden ponerla roja por accidente. Se leen aparte, importando esta clase y sus anidadas.
 */
@SuppressWarnings("unused")
public final class MuestrasDeCasosDeUsoSinLlamador {

    private MuestrasDeCasosDeUsoSinLlamador() {}

    /** El caso de uso que se llama desde un controlador: vivo. */
    @Service
    public static class ConLlamador {
        public void hacer() {}
    }

    /** El que nadie llama: es lo que la regla acusa. */
    @Service
    public static class Huerfano {
        public void hacer() {}
    }

    /** El que solo se llama a si mismo: una llamada desde dentro no es un llamador. */
    @Service
    public static class SeLlamaASiMismo {
        public void hacer() {
            otraVez();
        }

        public void otraVez() {
            hacer();
        }
    }

    /** Un puerto que un caso de uso implementa. */
    public interface Puerto {
        void consultar();
    }

    /** El que se llama por su interfaz: el receptor es el puerto, y cuenta. */
    @Service
    public static class PorSuInterfaz implements Puerto {
        @Override
        public void consultar() {}
    }

    /** Quien llama: un controlador de mentira. */
    public static class Controlador {
        private final ConLlamador conLlamador = new ConLlamador();
        private final Puerto puerto = new PorSuInterfaz();

        public void atender() {
            conLlamador.hacer();
            puerto.consultar();
        }
    }
}
