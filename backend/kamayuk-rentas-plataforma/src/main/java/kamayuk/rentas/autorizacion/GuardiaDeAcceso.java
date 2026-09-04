package kamayuk.rentas.autorizacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Comprueba {@link RequiereAcceso} antes de que el controlador reciba el control.
 *
 * <p>Es un interceptor y no una comprobacion dentro de cada metodo porque una comprobacion que hay
 * que escribir en 134 sitios falta en alguno, y el que falta no se descubre revisando: se descubre
 * cuando alguien entra.
 *
 * <h2>Que pasa si falta la anotacion</h2>
 *
 * <p><b>Se deniega.</b> No se deja pasar «porque no dice nada». Un endpoint sin anotacion es un
 * defecto, y la regla de ArchUnit rompe el build antes de que llegue aqui; pero si por algun camino
 * llegara —un controlador registrado en tiempo de ejecucion, un {@code @ControllerAdvice} que
 * publique rutas—, negar es lo unico razonable. La alternativa, permitir por omision, convierte
 * cualquier olvido en una puerta abierta.
 *
 * <p>Se exceptua lo que no es un metodo de controlador: recursos estaticos, el manejador de errores
 * y las rutas del propio contenedor.
 *
 * <h2>Una operacion que dos opciones cubren</h2>
 *
 * <p>{@code oTambien} declara otras opciones del catalogo cuyo <b>mismo</b> privilegio tambien
 * autoriza (#548). Se pregunta primero por el acceso propio y solo si niega por las alternativas,
 * asi que el caso normal sigue costando una consulta. Lo que <b>no</b> hace es relajar el
 * privilegio: {@code LECTURA} sobre la alternativa, nunca «cualquier privilegio».
 *
 * <p>Y se exceptuan, <b>declarandolos</b>, los dos centinelas:
 *
 * <ul>
 *   <li>{@code SESION_PROPIA}: la lectura de la sesion propia pasa con solo un token valido, sin
 *       comprobar el catalogo. Es lo que permite que la interfaz sepa que puede dibujar (ADR-0013).
 *   <li>{@code CIUDADANO}: la operacion es del portal del contribuyente, que no esta en el catalogo
 *       de permisos porque el ciudadano no tiene fila en {@code usuario} (ADR-0020). Este si lleva
 *       una comprobacion propia —que la peticion venga de la cadena del ciudadano—, porque sin ella
 *       el centinela seria la forma de servir cualquier endpoint sin privilegio.
 * </ul>
 */
public class GuardiaDeAcceso implements HandlerInterceptor {

    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public GuardiaDeAcceso(ComprobadorDeAcceso comprobador, Clock reloj) {
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest peticion, HttpServletResponse respuesta, Object manejador) {

        if (!(manejador instanceof HandlerMethod metodo)) {
            return true;
        }

        RequiereAcceso requisito =
                AnnotatedElementUtils.findMergedAnnotation(
                        metodo.getMethod(), RequiereAcceso.class);
        if (requisito == null) {
            requisito =
                    AnnotatedElementUtils.findMergedAnnotation(
                            metodo.getBeanType(), RequiereAcceso.class);
        }
        if (requisito == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "La operacion no declara que acceso exige, asi que no se autoriza");
        }

        if (RequiereAcceso.CIUDADANO.equals(requisito.acceso())) {
            // El ciudadano no tiene fila en `usuario` y no hay privilegio que comprobar
            // (ADR-0020). Lo que SI se comprueba es que la peticion venga de verdad de la
            // cadena del ciudadano: `CiudadanoContext` lo fija DocumentoCiudadanoContextFilter,
            // que solo corre bajo /api/v1/portal y solo con un token del realm del ciudadano
            // ya validado. Sin esta comprobacion, el centinela seria una forma de servir un
            // endpoint del catalogo sin ningun privilegio.
            if (CiudadanoContext.actualSiHay().isEmpty()) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.SIN_PRIVILEGIO,
                        "Esta operacion es del portal del contribuyente y esta peticion no viene"
                                + " de una sesion de ciudadano");
            }
            return true;
        }

        if (RequiereAcceso.SESION_PROPIA.equals(requisito.acceso())) {
            // La operacion lee la sesion propia del usuario autenticado: no es una opcion
            // del catalogo y no hay privilegio que comprobar. El token ya lo valido Spring
            // Security y el contexto de tenant ya lo fijo su filtro; llegar hasta aqui es,
            // por si mismo, estar autenticado. Ver RequiereAcceso.SESION_PROPIA (ADR-0013).
            return true;
        }

        String usuario = OrigenContext.actual().usuario();
        LocalDate hoy = LocalDate.now(reloj);
        if (autorizaAlguna(usuario, requisito, hoy)) {
            return true;
        }
        // El mensaje dice que falta, no quien lo tiene ni como se configura: eso
        // ya es informacion sobre la organizacion de la municipalidad. Si la operacion
        // admite otra opcion, se nombra tambien: negar diciendo solo la primera dejaria
        // a un cajero leyendo «no tiene LECTURA sobre consulta_deuda», que es una
        // opcion que su perfil no tiene por que tener.
        throw new ProblemaDeNegocio(
                CodigoDeError.SIN_PRIVILEGIO,
                "No tiene el privilegio "
                        + requisito.privilegio()
                        + " sobre "
                        + String.join(" ni sobre ", opciones(requisito)));
    }

    /**
     * El acceso declarado, o cualquiera de sus alternativas, con el <b>mismo</b> privilegio (#548).
     *
     * <p>El orden importa poco —basta una— pero se pregunta primero por el acceso propio: es el que
     * tiene casi todo el mundo, y asi el caso normal cuesta una sola consulta.
     */
    private boolean autorizaAlguna(String usuario, RequiereAcceso requisito, LocalDate hoy) {
        for (String acceso : opciones(requisito)) {
            if (comprobador.autoriza(usuario, acceso, requisito.privilegio(), hoy)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> opciones(RequiereAcceso requisito) {
        List<String> opciones = new ArrayList<>();
        opciones.add(requisito.acceso());
        opciones.addAll(Arrays.asList(requisito.oTambien()));
        return opciones;
    }
}
