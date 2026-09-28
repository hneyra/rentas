package kamayuk.rentas.verificaciones.muestras.aplicacion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
        private final Intermedio intermedio = new Intermedio();

        public void hacer() {
            intermedio.seguir();
        }
    }

    /** El que solo llama otro caso de uso vivo: vivo, porque su llamador lo esta (#629). */
    @Service
    public static class Intermedio {
        public void seguir() {}
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

    /**
     * El que solo llama un caso de uso muerto: tiene un llamador de otra clase, y hasta #629 eso
     * bastaba para pasar por vivo. Es lo que el marcado desde las raices acusa.
     */
    @Service
    public static class LlamadoSoloPorUnMuerto {
        public void hacer() {}
    }

    /** El muerto que lo llama: nadie lo llama a el. */
    @Service
    public static class MuertoQueLlama {
        private final LlamadoSoloPorUnMuerto otro = new LlamadoSoloPorUnMuerto();

        public void hacer() {
            otro.hacer();
        }
    }

    /** El que solo llama una corrida del perfil batch: vivo, porque {@code run} es una raiz. */
    @Service
    public static class PorLaCorrida {
        public void correr() {}
    }

    /** La corrida: Spring llama a {@code run}, y desde el bytecode no se ve quien. */
    @Component
    public static class Corrida implements ApplicationRunner {
        private final PorLaCorrida caso = new PorLaCorrida();

        @Override
        public void run(ApplicationArguments argumentos) {
            caso.correr();
        }
    }

    /** El que solo se llama dentro de una lambda del controlador: la lambda es de su metodo. */
    @Service
    public static class DesdeUnaLambda {
        public void hacer() {}
    }

    /** Un puerto que un caso de uso implementa. */
    public interface Puerto {
        void consultar();
    }

    /** El que se llama por su interfaz: el receptor es el puerto, y cuenta. */
    @Service
    public static class PorSuInterfaz implements Puerto {
        private final TrasLaInterfaz siguiente = new TrasLaInterfaz();

        @Override
        public void consultar() {
            siguiente.hacer();
        }
    }

    /**
     * El que solo llama la implementacion del puerto: vivo, porque llamar al puerto marca viva su
     * implementacion (el despacho virtual) y la propagacion sigue desde ahi.
     */
    @Service
    public static class TrasLaInterfaz {
        public void hacer() {}
    }

    /** Quien llama: un controlador de mentira, con su ruta. Es la raiz de las muestras. */
    @RestController
    public static class Controlador {
        private final ConLlamador conLlamador = new ConLlamador();
        private final Puerto puerto = new PorSuInterfaz();
        private final DesdeUnaLambda desdeUnaLambda = new DesdeUnaLambda();

        @GetMapping("/muestra")
        public void atender() {
            conLlamador.hacer();
            puerto.consultar();
            Runnable enDiferido = () -> desdeUnaLambda.hacer();
            enDiferido.run();
        }
    }
}
