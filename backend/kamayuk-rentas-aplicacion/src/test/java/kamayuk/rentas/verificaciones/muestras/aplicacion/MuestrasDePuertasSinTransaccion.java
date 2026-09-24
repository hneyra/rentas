package kamayuk.rentas.verificaciones.muestras.aplicacion;

import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Muestras que violan —y que no violan— la regla de #406: todo metodo publico de un caso de uso que
 * recibe una {@link Observacion} abre su transaccion.
 *
 * <p>No las instancia nadie. Existen para que {@code TodaPuertaConObservacionAbreSuTransaccionTest}
 * demuestre que la regla muerde: una regla que no puede fallar no protege nada.
 *
 * <p><b>Son clases internas NO estaticas, y es a proposito</b>: llevan {@code @Service} de verdad
 * —la regla mira esa anotacion— y el escaner de componentes de Spring descarta las clases que no
 * son independientes. Una muestra de primer nivel con {@code @Service} se registraria como bean en
 * {@code ArranqueDeLaAplicacionTest}, que escanea {@code kamayuk.rentas} con las clases de prueba
 * en el classpath. Y viven en un paquete {@code ..aplicacion..} porque es el que la regla mira.
 */
public final class MuestrasDePuertasSinTransaccion {

    private MuestrasDePuertasSinTransaccion() {}

    /**
     * MALO: la forma exacta de #406. La sobrecarga anotada llama a la otra, y el borde llama a la
     * otra: el proxy mira el metodo que se invoca, asi que por el borde no hay transaccion.
     */
    @Service
    public class DosPuertasYLaAnotacionEnLaQueNoUsaElBorde {

        @Transactional
        public String registrar(String peticion, Observacion observacion) {
            return registrar(peticion, null, observacion);
        }

        public String registrar(String peticion, @Nullable String clave, Observacion observacion) {
            return peticion + clave + observacion;
        }
    }

    /** MALO: una sola puerta, y sin transaccion. */
    @Service
    public class UnaPuertaSinTransaccion {

        public String anular(long id, Observacion observacion) {
            return id + " " + observacion;
        }
    }

    /** BUENO: la transaccion en el metodo que recibe la observacion. */
    @Service
    public class LaTransaccionEnElMetodo {

        @Transactional
        public String anular(long id, Observacion observacion) {
            return id + " " + observacion;
        }
    }

    /** BUENO: la transaccion en la clase alcanza a todos sus metodos publicos. */
    @Service
    @Transactional
    public class LaTransaccionEnLaClase {

        public String anular(long id, Observacion observacion) {
            return id + " " + observacion;
        }
    }

    /** BUENO: lo que no recibe observacion, o no es publico, no es una puerta de escritura. */
    @Service
    public class SinPuertaQueMirar {

        public String consultar(long id) {
            return Long.toString(id);
        }

        String interno(Observacion observacion) {
            return observacion.toString();
        }
    }

    /** BUENO: sin {@code @Service} no hay proxy, y la anotacion no significaria nada. */
    public class NoEsUnBean {

        public String anular(long id, Observacion observacion) {
            return id + " " + observacion;
        }
    }
}
