package kamayuk.rentas.verificaciones.muestras.aplicacion;

import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Muestras para {@code ApiPublicaSinJavadocTest} (#642): lo que la regla tiene que acusar y lo que
 * no.
 *
 * <p>No las instancia nadie, y la regla de produccion no las ve: lee {@code src/main}. La prueba
 * las lee aparte, <b>como fuente</b> y con el mismo {@code javac}, porque el javadoc no llega al
 * bytecode. La convencion es el nombre: lo que termina en {@code Acusado} tiene que salir en la
 * lista, y nada mas.
 *
 * <p>Los {@code @Service} son clases internas NO estaticas por lo mismo que en {@link
 * MuestrasDePuertasSinTransaccion}: una independiente la registraria como bean {@code
 * ArranqueDeLaAplicacionTest}, que escanea {@code kamayuk.rentas} con las pruebas en el classpath.
 */
@SuppressWarnings("unused")
public final class MuestrasDeApiSinJavadoc {

    private MuestrasDeApiSinJavadoc() {}

    public static final class TipoAcusado {}

    // Privado de paquete: javadoc no lo publica, asi que no se mira ni el ni lo que tenga dentro.
    static final class TipoQueNoSePublica {
        public void tampocoSePublica() {}
    }

    /** El caso de uso: un {@code @Service} de un paquete {@code ..aplicacion..}. */
    @Service
    public class CasoDeUso {

        // Fuera del alcance: el de inyeccion no dice nada que la clase no diga (1 de 184 en #642).
        public CasoDeUso() {}

        /** Hace lo que el caso de uso existe para hacer. */
        public void documentado() {}

        public void metodoAcusado() {}

        // Un comentario de linea no es javadoc.
        public void conComentarioDeLineaAcusado() {}

        /* Ni uno de bloque. */
        public void conComentarioDeBloqueAcusado() {}

        /** */
        public void conJavadocVacioAcusado() {}

        public static void estaticoAcusado() {}

        void dePaqueteNoSePublica() {}

        @Override
        public String toString() {
            return "sobrescribe: javadoc hereda el comentario del que sobrescribe";
        }
    }

    /**
     * El que implementa un puerto sin {@code @Override}: sigue sobrescribiendo, y hereda el
     * comentario igual. La regla lo resuelve con los tipos, no con la anotacion.
     */
    @Service
    public class CasoQueImplementa implements Contrato {

        public void cumplir() {}
    }

    /** Un componente de aplicacion que no es un {@code @Service}: no es un caso de uso. */
    public static class NoEsCasoDeUso {

        public void hacer() {}
    }

    /**
     * Un record: sus componentes y sus metodos quedan fuera.
     *
     * @param valor lo que lleva
     */
    public record Fila(String valor) {

        public String enMayusculas() {
            return valor.toUpperCase(Locale.ROOT);
        }
    }

    /** Un puerto: cada metodo es un contrato entre dos que no se ven. */
    public interface Puerto {

        /** Lo que el puerto promete. */
        void documentado();

        void metodoDeInterfazAcusado();

        default void porOmisionAcusado() {}

        /** Una interfaz anidada tambien es un contrato. */
        interface Anidada {

            void anidadoAcusado();
        }

        interface InterfazAnidadaAcusado {}
    }

    /** El contrato que {@link CasoQueImplementa} cumple. */
    public interface Contrato {

        /** Cumple el contrato. */
        void cumplir();
    }
}
