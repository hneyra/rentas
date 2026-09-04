package kamayuk.rentas.autorizacion;

import java.time.Clock;
import kamayuk.rentas.web.Api;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra el guardia sobre <b>todas</b> las rutas de la API.
 *
 * <p>Sobre todas y no sobre una lista: una lista de rutas protegidas es una lista a la que hay que
 * acordarse de agregar la ruta nueva, y el dia que se olvide el endpoint queda abierto. Al reves
 * —proteger todo y que el guardia niegue lo que no declara acceso— el olvido se manifiesta como un
 * 403 en la primera prueba, no como una puerta abierta en produccion.
 *
 * <p>El {@code order} se declara desde #539: hay un segundo interceptor —{@code
 * GuardiaDeParametros}— y este tiene que correr <b>antes</b>, porque el 422 de aquel nombra los
 * parametros que la operacion admite. Dejar el orden al azar del orden de los beans convertiria una
 * decision en una casualidad.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConfiguracionDeAutorizacion implements WebMvcConfigurer {

    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public ConfiguracionDeAutorizacion(ComprobadorDeAcceso comprobador, Clock reloj) {
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    /** Delante de todo lo demas: primero se decide si puede entrar. */
    static final int ORDEN = 0;

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(new GuardiaDeAcceso(comprobador, reloj))
                .addPathPatterns(Api.RAIZ + "/**")
                .order(ORDEN);
    }
}
