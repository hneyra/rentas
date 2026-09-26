package kamayuk.rentas.seguridad.aplicacion;

import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.dominio.ConsultaDeAuditoria;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Respaldo;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.seguridad.dominio.SesionRepository;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que el operador usa todos los dias: el ejercicio de trabajo, la consulta de auditoria y el
 * estado de las copias (RF-124 a RF-126).
 */
@Service
public class AdministrarSesion {

    private final SesionRepository sesiones;
    private final LecturaDeLaCopiaLocal administracion;
    private final Auditoria auditoria;

    public AdministrarSesion(
            SesionRepository sesiones, LecturaDeLaCopiaLocal administracion, Auditoria auditoria) {
        this.sesiones = sesiones;
        this.administracion = administracion;
        this.auditoria = auditoria;
    }

    /**
     * Cambia el ejercicio de trabajo de la sesion del usuario en curso (RF-125).
     *
     * <p><b>No toca el contexto de municipalidad</b>, y no puede tocarlo: ese sale del token
     * (ADR-0005, regla 2) y este metodo ni lo recibe ni lo devuelve. Es la confusion mas natural
     * del mundo —«la sesion decide sobre que trabajo»— y la que convertiria una pantalla de
     * comodidad en la forma de leer la deuda de otra municipalidad.
     *
     * <p>Tampoco sustituye a la fecha de calculo. Ninguna regla tributaria lee este valor (regla
     * 6): la fecha entra como argumento del calculo. Si pudiera sustituirla, recalcular un padron
     * con la sesion mal puesta produciria cifras equivocadas sin ningun error de por medio.
     */
    @Transactional
    public Sesion cambiarEjercicioDeTrabajo(Ejercicio ejercicio, Observacion observacion) {
        Usuario usuario = usuarioEnCurso();
        long usuarioId = java.util.Objects.requireNonNull(usuario.id());

        Sesion sesion = sesiones.abiertaDe(usuarioId).orElseGet(() -> sesiones.abrir(usuarioId));
        Sesion actualizada = sesiones.fijarEjercicioDeTrabajo(sesion.id(), ejercicio);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "sesion",
                                String.valueOf(actualizada.id()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(null, "{\"ejercicioDeTrabajo\":" + ejercicio.valor() + "}"));

        return actualizada;
    }

    /**
     * Consulta de auditoria (RF-124).
     *
     * <p>Solo lectura, y no por convencion: la aplicacion tiene sobre {@code auditoria} unicamente
     * {@code SELECT} e {@code INSERT} (V7), y este puerto no expone ningun metodo que escriba.
     */
    @Transactional(readOnly = true)
    public Pagina<RegistroAuditado> auditoria(ConsultaDeAuditoria consulta, Paginacion paginacion) {
        return sesiones.auditoria(consulta, paginacion);
    }

    /**
     * Estado de las copias de seguridad (RF-126).
     *
     * <p>Consulta, no ejecucion. La aplicacion no hace copias y no debe poder hacerlas: se conecta
     * como {@code kamayuk_app}, que no tiene DDL ni es superusuario. Un boton «respaldar ahora»
     * detras de un endpoint exigiria darle privilegios que se le quitaron a proposito.
     */
    @Transactional(readOnly = true)
    public Pagina<Respaldo> respaldos(Paginacion paginacion) {
        return sesiones.respaldos(paginacion);
    }

    private Usuario usuarioEnCurso() {
        String cuenta = OrigenContext.actual().usuario();
        return administracion
                .usuarioPorCuenta(cuenta)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "El token identifica a '"
                                                + cuenta
                                                + "', que no es un usuario de esta municipalidad"));
    }
}
