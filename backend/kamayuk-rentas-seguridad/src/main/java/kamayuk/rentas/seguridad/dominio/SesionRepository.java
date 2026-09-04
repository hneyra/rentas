package kamayuk.rentas.seguridad.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/** Puerto de la sesion y de la lectura de la auditoria. Las dos son datos de esta pantalla. */
public interface SesionRepository {

    /** La sesion abierta del usuario, si la hay. */
    Optional<Sesion> abiertaDe(long usuarioId);

    /** Abre una sesion para el usuario, con el origen del contexto. */
    Sesion abrir(long usuarioId);

    /** Fija el ejercicio de trabajo de una sesion abierta. */
    Sesion fijarEjercicioDeTrabajo(long sesionId, Ejercicio ejercicio);

    /**
     * Lee la auditoria. <b>Solo lectura</b>: no hay ningun metodo que escriba, y la aplicacion
     * tampoco tiene el privilegio (V7).
     */
    Pagina<RegistroAuditado> auditoria(ConsultaDeAuditoria consulta, Paginacion paginacion);

    /** Estado de las copias de seguridad (RF-126). Consulta; la aplicacion no respalda. */
    Pagina<Respaldo> respaldos(Paginacion paginacion);
}
