package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra la notificación administrativa previa (#47, RF-070): {@code POST
 * /api/v1/infracciones/administrativas/notificaciones}.
 *
 * <p>No exige contribuyente ni predio identificados —el manual describe el registro sobre "la
 * vivienda o el negocio inspeccionado", que puede tomarse sin haber resuelto todavía quién
 * responde—; tampoco exige un plazo: sin uno, la notificación nunca aparece en {@code
 * adm_notificaciones_vencidas} (#47 AC3).
 *
 * <p>La tabla no lleva columna {@code observacion} (ver {@link NotificacionAdministrativa}); esta
 * clase igual la exige como argumento para poder auditar el alta (regla 10, ADR-0008).
 *
 * <p><b>El contribuyente, si viene, tiene que estar en el padron</b> (#422). No es obligatorio
 * identificarlo; lo que no se admite es nombrar a uno que no existe. Hasta #422 ese identificador
 * llegaba al {@code INSERT} y {@code notif_adm_contribuyente_fk} lo rechazaba como un 500 con
 * incidencia ERROR; ahora se pregunta antes al {@link DirectorioDeContribuyentes}, el puerto que
 * este contexto ya usa, y el borde contesta 404.
 */
@Service
public class RegistrarNotificacionAdministrativa {

    private static final String TABLA_AUDITADA = "notificacion_administrativa";

    private final NotificacionAdministrativaRepository notificaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Auditoria auditoria;

    public RegistrarNotificacionAdministrativa(
            NotificacionAdministrativaRepository notificaciones,
            DirectorioDeContribuyentes contribuyentes,
            Auditoria auditoria) {
        this.notificaciones = notificaciones;
        this.contribuyentes = contribuyentes;
        this.auditoria = auditoria;
    }

    @Transactional
    public NotificacionAdministrativa registrar(
            String numero,
            LocalDate fecha,
            @Nullable Long contribuyenteId,
            @Nullable Long predioId,
            String direccion,
            String motivo,
            @Nullable Short plazoDias,
            Observacion observacion) {

        if (contribuyenteId != null
                && !contribuyentes.porIds(Set.of(contribuyenteId)).containsKey(contribuyenteId)) {
            throw new ContribuyenteInexistente(contribuyenteId);
        }

        NotificacionAdministrativa guardada =
                notificaciones.insertar(
                        NotificacionAdministrativa.emitida(
                                numero,
                                fecha,
                                contribuyenteId,
                                predioId,
                                direccion,
                                motivo,
                                plazoDias));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));

        return guardada;
    }

    /** El contribuyente notificado no esta en el padron de esta municipalidad (#422). */
    public static final class ContribuyenteInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(long id) {
            super(
                    "No hay ningun contribuyente con identificador "
                            + id
                            + " en esta municipalidad: no se le puede notificar");
        }
    }

    private static String descripcion(NotificacionAdministrativa notificacion) {
        return "{\"numero\":\""
                + notificacion.numero()
                + "\",\"estado\":\""
                + notificacion.estado()
                + "\"}";
    }
}
