package kamayuk.rentas.plataforma;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import kamayuk.rentas.plataforma.tenant.DocumentoCiudadanoContextFilter;
import kamayuk.rentas.plataforma.tenant.OrigenContextFilter;
import kamayuk.rentas.plataforma.tenant.TenantConnectionGuard;
import kamayuk.rentas.plataforma.tenant.TenantContextFilter;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cablea el camino completo de ARQ-03 §2: del claim del token al {@code SET LOCAL}, y la
 * verificacion al devolver la conexion al pool.
 *
 * <p>Vive en el paquete raiz del modulo y no junto a las clases que cablea: es lo unico que otro
 * modulo necesita ver, y Spring Modulith trata como interno todo lo que esta en un subpaquete.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeTenant {

    /**
     * La cadena de Spring Security se registra en el orden -100. Cualquier valor por encima corre
     * por dentro de ella y ve el {@code SecurityContextHolder} poblado.
     *
     * <p>Se usa un numero propio y no la constante de Spring Boot porque esa constante cambio de
     * sitio entre versiones. Si el orden alguna vez quedara mal, el filtro no veria autenticacion y
     * no fijaria contexto: toda consulta a datos de tenant fallaria en la base. Es un fallo
     * ruidoso, no una fuga.
     */
    private static final int ORDEN_DESPUES_DE_SEGURIDAD = 0;

    /**
     * Envuelve el DataSource que autoconfigura Spring Boot.
     *
     * <p>Es un {@link BeanPostProcessor} y no un {@code @Bean} de tipo {@code DataSource} porque lo
     * segundo haria que la autoconfiguracion de Boot se retirara —es condicional a que no exista ya
     * un DataSource— y entonces no habria pool que envolver.
     */
    @Bean
    static BeanPostProcessor guardiaDeConexiones() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String nombre) {
                if (bean instanceof HikariDataSource pool) {
                    return new TenantConnectionGuard(pool, pool::evictConnection);
                }
                return bean;
            }
        };
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantTransactionManager(dataSource);
    }

    /**
     * Registra el filtro <b>despues</b> de la cadena de Spring Security.
     *
     * <p>El orden no es cosmetico: el filtro lee el token ya validado del {@code
     * SecurityContextHolder}, asi que tiene que correr dentro de la cadena de seguridad, no antes.
     * Como los filtros de servlet se anidan, uno registrado con orden posterior se ejecuta dentro
     * del anterior y ve el contexto puesto.
     *
     * <p>Solo en el perfil {@code web}: el perfil {@code batch} no atiende HTTP y fija el contexto
     * municipalidad por municipalidad.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<TenantContextFilter> filtroDeContextoDeTenant() {
        FilterRegistrationBean<TenantContextFilter> registro =
                new FilterRegistrationBean<>(new TenantContextFilter());
        registro.setOrder(ORDEN_DESPUES_DE_SEGURIDAD);
        return registro;
    }

    /**
     * El del ciudadano, el otro medio del par (ADR-0020).
     *
     * <p>Los dos se registran, y los dos se excluyen por camino: bajo {@code /api/v1/portal/**}
     * corre este y no el de tenant; fuera, al reves. Registrarlos asi —y no con un {@code if}
     * dentro de uno de los dos— es lo que hace que la exclusion se lea en una linea y que ninguna
     * peticion pueda acabar con los dos sujetos puestos a la vez.
     *
     * <p>Mismo orden que el de tenant: nunca coinciden, asi que no hay ninguna precedencia que
     * fijar entre ellos. Lo que si importa es que los dos corran <b>dentro</b> de la cadena de
     * seguridad y <b>antes</b> que el de origen.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<DocumentoCiudadanoContextFilter> filtroDelCiudadano() {
        FilterRegistrationBean<DocumentoCiudadanoContextFilter> registro =
                new FilterRegistrationBean<>(new DocumentoCiudadanoContextFilter());
        registro.setOrder(ORDEN_DESPUES_DE_SEGURIDAD);
        return registro;
    }

    /**
     * El gemelo del anterior para el origen de la peticion: quien la hace y desde donde.
     *
     * <p>Va <b>despues</b> del de tenant, y el orden no es indiferente: si el token no trae
     * municipalidad, la peticion se corta con 403 antes de fijar ningun origen, y no queda un hilo
     * con el usuario puesto de una peticion que nunca llego a ocurrir.
     *
     * <p>Son dos filtros y no uno porque son dos contextos con dos vidas: {@code TenantContext}
     * alimenta el {@code SET LOCAL} de cada transaccion y {@code OrigenContext} alimenta la
     * auditoria. Juntarlos ahorraria una lectura del token y haria que una regla sobre uno tuviera
     * que razonar sobre el otro.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<OrigenContextFilter> filtroDeOrigen() {
        FilterRegistrationBean<OrigenContextFilter> registro =
                new FilterRegistrationBean<>(new OrigenContextFilter());
        registro.setOrder(ORDEN_DESPUES_DE_SEGURIDAD + 1);
        return registro;
    }
}
