package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import kamayuk.rentas.autorizacion.ConfiguracionDeAutorizacion;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * La guarda de #539 esta <b>puesta</b> en la aplicacion, y en el orden que se decidio.
 *
 * <p>Sin esta prueba, todo lo que mide {@link GuardiaDeParametrosTest} seguiria en verde con el
 * interceptor sin registrar: aquellas pruebas lo montan a mano —como hacen las 200 pruebas de capa
 * web del repositorio— y ninguna comprueba que la aplicacion de verdad lo instale. Es el mismo
 * hueco que {@code GuardiaDeAcceso} tenia y que aqui se cierra para los dos.
 */
@DisplayName("Capa web — La guarda de parametros esta registrada, y detras del acceso (#539)")
class ConfiguracionDeLaConsultaTest {

    private static final Clock RELOJ = Clock.systemUTC();

    @Test
    @DisplayName("cubre todas las rutas de la API y ninguna de fuera")
    void cubreLaApiEntera() {
        MappedInterceptor guarda = registrado(GuardiaDeParametros.class);

        assertThat(guarda.matches(peticion("/rentas/api/v1/rentas/contribuyentes")))
                .as("sobre todas, no sobre una lista a la que hay que acordarse de anadir la nueva")
                .isTrue();
        assertThat(guarda.matches(peticion("/actuator/health")))
                .as("lo que no es la API no lo vigila: sus parametros no son de este contrato")
                .isFalse();
    }

    @Test
    @DisplayName("y corre DESPUES del guardia de acceso")
    void correDespuesDelGuardiaDeAcceso() {
        InterceptorRegistry registro = new InterceptorRegistry();
        // En el orden contrario al que tienen que quedar, a proposito: lo que los ordena es el
        // `order` que cada configuracion declara, no el orden en que Spring cree sus beans.
        new ConfiguracionDeLaConsulta().addInterceptors(registro);
        new ConfiguracionDeAutorizacion((usuario, acceso, privilegio, fecha) -> true, RELOJ)
                .addInterceptors(registro);

        assertThat(interceptoresDe(registro).stream().map(ConfiguracionDeLaConsultaTest::tipo))
                .as(
                        "el 422 nombra los parametros que la operacion admite: quien no ha"
                                + " demostrado que puede llamarla no tiene por que recibir su"
                                + " catalogo de filtros a base de peticiones mal escritas")
                .containsExactly(GuardiaDeAcceso.class, GuardiaDeParametros.class);
    }

    // ------------------------------------------------------------------

    private static MappedInterceptor registrado(Class<?> tipo) {
        InterceptorRegistry registro = new InterceptorRegistry();
        new ConfiguracionDeLaConsulta().addInterceptors(registro);
        return interceptoresDe(registro).stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapeado -> tipo.isInstance(mapeado.getInterceptor()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "La aplicacion no registra " + tipo.getSimpleName()));
    }

    /** {@code getInterceptors()} es {@code protected} y devuelve la lista ya ordenada. */
    private static List<Object> interceptoresDe(InterceptorRegistry registro) {
        List<Object> interceptores = ReflectionTestUtils.invokeMethod(registro, "getInterceptors");
        return interceptores == null ? List.of() : interceptores;
    }

    private static Class<?> tipo(Object interceptor) {
        return interceptor instanceof MappedInterceptor mapeado
                ? mapeado.getInterceptor().getClass()
                : interceptor.getClass();
    }

    /**
     * Una peticion con su ruta ya analizada, que es lo que {@code MappedInterceptor} espera.
     *
     * <p>Sin {@code parseAndCache} la comparacion no se hace: lanza «Neither a pre-parsed
     * RequestPath nor a pre-resolved String lookupPath is available», que en produccion prepara el
     * {@code DispatcherServlet} antes de consultar a ningun interceptor.
     */
    private static MockHttpServletRequest peticion(String ruta) {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", ruta);
        ServletRequestPathUtils.parseAndCache(peticion);
        return peticion;
    }
}
