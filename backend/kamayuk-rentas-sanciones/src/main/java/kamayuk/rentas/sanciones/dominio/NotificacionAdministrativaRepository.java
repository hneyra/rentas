package kamayuk.rentas.sanciones.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * La notificación administrativa previa (#47). Ningún método recibe la municipalidad (regla 2):
 * sale del token y la aplica la política RLS.
 *
 * <p><b>No hay {@code delete}.</b> Cerrar por subsanación es una actualización de {@link
 * NotificacionAdministrativa#estado}, nunca borra la fila.
 */
public interface NotificacionAdministrativaRepository {

    NotificacionAdministrativa insertar(NotificacionAdministrativa notificacion);

    Optional<NotificacionAdministrativa> porNumero(String numero);

    Pagina<NotificacionAdministrativa> buscarVencidas(
            CriterioDeNotificacion criterio, Paginacion paginacion);

    /**
     * El padrón de notificaciones emitidas en un intervalo, con la papeleta que las siguió cuando
     * la hay ({@code adm_padron_notificaciones}, #53).
     *
     * <p>Devuelve {@link NotificacionDelPadron} y no {@link NotificacionAdministrativa}: las tres
     * columnas de la papeleta salen del {@code LEFT JOIN}, no de esta tabla.
     */
    Pagina<NotificacionDelPadron> buscarPadron(
            CriterioDelPadronDeNotificaciones criterio, Paginacion paginacion);

    /**
     * Cierra la notificación por subsanación. Quien llama ya decidió que corresponde —dentro del
     * plazo (#47 AC2)—; este método solo guarda la transición.
     */
    NotificacionAdministrativa subsanar(long notificacionId);
}
