package kamayuk.rentas.valores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La relacion de #674 declara su acceso <b>en el metodo</b>, y cual.
 *
 * <h2>Por que esto no lo cubre ArchUnit</h2>
 *
 * <p>Su regla exige la anotacion «en la clase <b>o</b> en cada endpoint», y aqui la clase no
 * declara ninguna: los dos verbos de esta ruta piden privilegios distintos —declarar una
 * prescripcion es {@link Privilegio#REGISTRO} y leerla es {@link Privilegio#LECTURA}—, asi que
 * ponerla en la clase daria a la lectura el privilegio de la escritura o al reves. Lo que ArchUnit
 * no puede ver, y esta prueba si, es <b>cual</b> acceso se exige: cambiarlo por otra opcion del
 * catalogo deja {@code verificarArquitectura} en verde y decide quien puede abrir la pantalla
 * (#431, #543, #555, #559).
 */
@DisplayName("El acceso de la relacion de prescripciones (#674)")
class AccesoDeLaRelacionDePrescripcionesTest {

    @Test
    @DisplayName("la relacion exige LECTURA sobre «prescripcion», la misma opcion que la declara")
    void laRelacionExigeLecturaSobrePrescripcion() throws Exception {
        RequiereAcceso enElMetodo =
                PrescripcionController.class
                        .getMethod(
                                "relacion",
                                String.class,
                                String.class,
                                Integer.class,
                                String.class,
                                ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as("la clase no declara ninguna: sin la del metodo esta lectura no tiene guardia")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as("es la misma pantalla que declara la prescripcion, no una consulta aparte")
                .isEqualTo("prescripcion");
        assertThat(enElMetodo.privilegio())
                .as("leer quien tiene deuda inexigible no es declararla")
                .isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("y el acto que la declara sigue exigiendo REGISTRO, que es lo que separa los dos")
    void elActoSigueExigiendoRegistro() throws Exception {
        RequiereAcceso enElMetodo =
                PrescripcionController.class
                        .getMethod("declarar", PeticionDePrescripcion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo).isNotNull();
        assertThat(enElMetodo.acceso()).isEqualTo("prescripcion");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.REGISTRO);
    }
}
