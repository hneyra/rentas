package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las lecturas de un usuario —y desde #585 tambien su escritura— declaran su acceso <b>en el
 * metodo</b>, y cual.
 *
 * <h2>Por que esto no lo cubre ArchUnit</h2>
 *
 * <p>La regla de {@code verificarArquitectura} exige la anotacion «en la clase <b>o</b> en cada
 * endpoint». {@link PermisosDeUsuarioController} declara un {@code @RequestMapping} de clase y
 * anota cada metodo: si alguien le añadiera una escritura sin anotarla, heredaria el {@code
 * LECTURA} de las lecturas y ArchUnit seguiria en verde. Es el hueco que #431 encontro en {@code
 * ProgramasController} y #489 en el alta de predio, aqui puesto por delante — y lo que #585 vino a
 * añadir es justo esa escritura, asi que la prevision dejo de ser hipotetica.
 *
 * <p>Y sobre {@link SeguridadController} la comprobacion dice otra cosa: <b>que acceso</b> exige la
 * lectura nueva. Elegir uno u otro no es un detalle de estilo —decide quien puede abrir la pantalla
 * que la consume— y no hay ninguna otra prueba que lo fije.
 */
@DisplayName("El acceso de las lecturas de un usuario, y de su escritura (#543, #585)")
class AccesoDeLasLecturasDeUsuarioTest {

    @Test
    @DisplayName("los grupos de un usuario exigen LECTURA sobre «usuarios»")
    void losGruposExigenLecturaSobreUsuarios() throws Exception {
        RequiereAcceso enElMetodo =
                SeguridadController.class
                        .getMethod("gruposDeUsuario", long.class, ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as("la clase no declara ninguna: sin la del metodo esta lectura no tiene guardia")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as(
                        "es una lectura SOBRE un usuario, y la grilla de «Usuarios del sistema» del"
                                + " manual dibuja la columna «Grupo»; afiliarlo sigue siendo"
                                + " «miembros» con REGISTRO")
                .isEqualTo("usuarios");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("los permisos efectivos exigen LECTURA sobre «permisos»")
    void losPermisosExigenLecturaSobrePermisos() throws Exception {
        RequiereAcceso enElMetodo =
                PermisosDeUsuarioController.class
                        .getMethod("deUsuario", long.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as("una escritura añadida despues no puede heredar en silencio este LECTURA")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as("la misma opcion del catalogo que la matriz de un grupo")
                .isEqualTo("permisos");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("los permisos configurados exigen lo mismo, y en el metodo (#583)")
    void losConfiguradosExigenLecturaSobrePermisos() throws Exception {
        RequiereAcceso enElMetodo =
                PermisosDeUsuarioController.class
                        .getMethod("configuradosDeUsuario", long.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as(
                        "la clase declara la suya en el otro metodo, no en la clase: sin esta, la"
                                + " lectura de lo configurado se quedaria sin guardia y"
                                + " verificarArquitectura seguiria en verde")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as(
                        "lo configurado de una cuenta es la misma pregunta con otra guarda: quien"
                                + " puede ver su matriz efectiva puede ver que conserva. Otro acceso"
                                + " aqui abriria o cerraria la puerta sin que nada mas cambiara, y"
                                + " ArchUnit no ve CUAL es")
                .isEqualTo("permisos");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("y la escritura de la excepcion exige REGISTRO sobre «permisos» (#585)")
    void laEscrituraExigeRegistroSobrePermisos() throws Exception {
        RequiereAcceso enElMetodo =
                PermisosDeUsuarioController.class
                        .getMethod("fijar", long.class, PermisosController.CambioDePermisos.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as(
                        "la clase no declara ninguna, asi que sin esta ArchUnit SI se pondria rojo;"
                                + " lo que no puede ver es lo que comprueban las dos lineas de"
                                + " abajo")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as("la misma opcion del catalogo que la matriz de un grupo")
                .isEqualTo("permisos");
        assertThat(enElMetodo.privilegio())
                .as(
                        "REGISTRO y no LECTURA: es lo unico que impide que quien solo puede MIRAR la"
                                + " matriz de una cuenta pueda ademas negarle un privilegio. Cambiar"
                                + " el enumerado aqui deja verificarArquitectura en VERDE (#431,"
                                + " #543, #555, #559)")
                .isEqualTo(Privilegio.REGISTRO);
    }

    @Test
    @DisplayName("y «quien tiene el privilegio» exige LECTURA sobre «permisos» (#583)")
    void losTitularesExigenLecturaSobrePermisos() throws Exception {
        RequiereAcceso enElMetodo =
                TitularesDelPrivilegioController.class
                        .getMethod(
                                "quienesTienen",
                                String.class,
                                String.class,
                                ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as("la clase no declara ninguna: sin la del metodo esta lectura no tiene guardia")
                .isNotNull();
        assertThat(enElMetodo.acceso())
                .as(
                        "enumerar quien tiene la llave de la caja es administrar permisos, no"
                                + " consultar el catalogo de opciones: pedir «accesos» —el listado"
                                + " que publica el codigo de la ruta— dejaria la lista de quien"
                                + " tiene ESPECIAL detras de un permiso de lectura del menu."
                                + " ArchUnit no ve cual acceso es: cambiarlo deja el build en VERDE")
                .isEqualTo("permisos");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.LECTURA);
    }
}
