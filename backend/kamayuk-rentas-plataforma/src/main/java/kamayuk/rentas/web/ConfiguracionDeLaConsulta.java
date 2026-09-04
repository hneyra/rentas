package kamayuk.rentas.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra {@link GuardiaDeParametros} sobre <b>todas</b> las rutas de la API.
 *
 * <p>Sobre todas y no sobre una lista, por el mismo motivo que el guardia de acceso: una lista de
 * operaciones vigiladas es una lista a la que hay que acordarse de anadir la operacion nueva, y el
 * dia que se olvide vuelve a haber un filtro que se cae sin ruido.
 *
 * <p>El orden es parte de la decision y por eso se declara en vez de dejarlo al azar del orden de
 * los beans: <b>primero el acceso, despues los parametros</b>. El 422 nombra los parametros que la
 * operacion admite, y eso es informacion sobre la API que no tiene por que recibir quien todavia no
 * ha demostrado que puede llamarla; al reves, cualquiera podria recorrer el catalogo entero de
 * filtros con peticiones mal escritas.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConfiguracionDeLaConsulta implements WebMvcConfigurer {

    /** Detras del guardia de acceso, que se registra con {@code order} 0. */
    static final int ORDEN = 1;

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(new GuardiaDeParametros())
                .addPathPatterns(Api.RAIZ + "/**")
                .order(ORDEN);
    }
}
