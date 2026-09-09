package kamayuk.rentas.seguridad.dominio;

import java.util.List;
import java.util.UUID;

/**
 * El buzon de {@code identidad}, visto desde este consumidor (ADR-0028 §3, etapa 4 de ADR-0039).
 *
 * <p>Dos operaciones y nada mas: traer lo pendiente y acusar lo resuelto. Quien pregunta lo decide
 * el token de servicio —{@code kamayuk-rentas-servicio-<ubigeo>}—, nunca un parametro, y el acuse
 * es <b>por consumidor</b>: lo que esta copia acusa no se lo quita a {@code caja}.
 *
 * <p><b>Todo fallo de transporte es {@link IdentidadNoContesta}, y es transitorio a proposito</b>:
 * un 401, un 403, un 5xx o una conexion caida se arreglan en el despliegue y la vuelta siguiente lo
 * reintenta. Lo que no puede pasar es que un fallo de transporte mate un evento. La unica excepcion
 * es {@link AcuseRechazado}: un 4xx de negocio al acusar dice que este consumidor y el buzon no
 * entienden lo mismo por un acuse, y eso no cambia reintentando.
 */
public interface FuenteDeEventosDeIdentidad {

    /** Los eventos que esta municipalidad no ha acusado todavia, en el orden del emisor. */
    Lote pendientes(int limite);

    /** Confirma que estos eventos ya no hace falta volver a servir. */
    Acuse acusar(List<UUID> eventoIds);

    record Lote(List<EventoDeIdentidadRecibido> eventos, long quedan) {}

    record Acuse(int recibidos, int escritos, long quedan) {}

    /** El buzon no se pudo leer o acusar. Transitorio: se reintenta la vuelta siguiente. */
    final class IdentidadNoContesta extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public IdentidadNoContesta(String mensaje) {
            super(mensaje);
        }

        public IdentidadNoContesta(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** {@code identidad} rechazo el acuse con un 4xx de negocio: no cambia reintentando. */
    final class AcuseRechazado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final int estado;

        public AcuseRechazado(int estado, String mensaje) {
            super(mensaje);
            this.estado = estado;
        }

        public int estado() {
            return estado;
        }
    }
}
