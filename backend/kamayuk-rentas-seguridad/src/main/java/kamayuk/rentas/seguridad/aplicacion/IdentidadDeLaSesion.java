package kamayuk.rentas.seguridad.aplicacion;

import java.util.Objects;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.seguridad.dominio.AdministracionRepository;
import kamayuk.rentas.seguridad.dominio.Identidad;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.seguridad.dominio.SesionRepository;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quien es la sesion (#559). La hermana de {@link MunicipalidadDeLaSesion}: aquella dice a quien
 * pertenecen las cifras, esta dice quien las esta mirando.
 *
 * <h2>Sin ningun argumento, y eso es la mitad de la decision</h2>
 *
 * <p>El sujeto sale de la cuenta del token —{@code OrigenContext}, que llena el filtro que ya
 * valido el token— y se resuelve contra {@code usuario} <b>dentro</b> del contexto de tenant.
 * Admitir un identificador convertiria esta lectura en el padron de usuarios sin su permiso: quien
 * pregunta elegiria por quien pregunta, y el {@code usuarioId} que devuelve es justamente el que el
 * cambio de clave usa para decidir si la clave es la propia.
 *
 * <h2>La transaccion no es cosmetica</h2>
 *
 * <p>Las dos consultas comparan {@code current_setting('app.municipalidad_id')} —{@code usuario} y
 * {@code sesion} son tablas de tenant y su politica RLS lo lee (V6)—, y ese valor solo existe
 * dentro de la transaccion que lo fijo con {@code SET LOCAL}. Sin la anotacion no hay una respuesta
 * equivocada: hay un {@code 500} con «invalid input syntax for type bigint», el defecto de clase de
 * #486.
 *
 * <h2>Y las dos consultas van en la MISMA transaccion</h2>
 *
 * <p>No por ahorro: {@link Identidad} afirma que ese ejercicio es el de <b>esa</b> sesion, y con
 * dos transacciones entre medias cabe un cambio de ejercicio que dejaria la respuesta diciendo
 * quien es uno y sobre que ejercicio trabajaba hace un instante.
 */
@Service
public class IdentidadDeLaSesion {

    private final AdministracionRepository administracion;
    private final SesionRepository sesiones;

    public IdentidadDeLaSesion(AdministracionRepository administracion, SesionRepository sesiones) {
        this.administracion = administracion;
        this.sesiones = sesiones;
    }

    /**
     * La persona autenticada, tal como la conoce esta municipalidad.
     *
     * @throws ProblemaDeNegocio con {@code NO_ENCONTRADO} si el token trae una cuenta que no es
     *     usuario de esta municipalidad. Es la misma negativa que ya da {@code
     *     AdministrarSesion.cambiarEjercicioDeTrabajo}, y sale dicha en vez de devolver un {@code
     *     usuarioId} inventado: un cero ahi acabaria en la ruta del cambio de clave
     */
    @Transactional(readOnly = true)
    public Identidad actual() {
        String cuenta = OrigenContext.actual().usuario();
        Usuario usuario =
                administracion
                        .usuarioPorCuenta(cuenta)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "El token identifica a '"
                                                        + cuenta
                                                        + "', que no es un usuario de esta"
                                                        + " municipalidad"));

        long usuarioId = Objects.requireNonNull(usuario.id(), "El usuario leido no tiene id");
        return new Identidad(
                usuarioId, usuario.cuenta(), usuario.nombre(), ejercicioDeTrabajoDe(usuarioId));
    }

    /**
     * El ejercicio de trabajo registrado, si lo hay.
     *
     * <p><b>No abre sesion.</b> Esta lectura no puede tener el efecto de crear una fila en {@code
     * sesion}: abrirla es lo que hace {@code cambiarEjercicioDeTrabajo}, que es un acto con su
     * observacion y su privilegio {@code ESPECIAL} sobre {@code cambiar_anio} (regla 10). Una
     * lectura que escribe seria, ademas, una lectura que no se puede repetir.
     */
    private @Nullable Ejercicio ejercicioDeTrabajoDe(long usuarioId) {
        return sesiones.abiertaDe(usuarioId).map(Sesion::ejercicioDeTrabajo).orElse(null);
    }
}
