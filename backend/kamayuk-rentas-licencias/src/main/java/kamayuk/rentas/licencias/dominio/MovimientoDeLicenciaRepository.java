package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Los movimientos de una licencia (V37, #44).
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>: V37 le concede a {@code kamayuk_app} solo
 * {@code SELECT} e {@code INSERT}. Lo que le pasa a una licencia se agrega.
 */
public interface MovimientoDeLicenciaRepository {

    /**
     * Registra el movimiento.
     *
     * @throws LicenciaYaCancelada si la licencia ya tenia su cancelacion. Lo decide la base —{@code
     *     licencia_movimiento_cancelacion_uq}—, no un {@code SELECT} previo: dos peticiones
     *     simultaneas pasarian las dos por cualquier comprobacion escrita en Java, y el titular
     *     acabaria con dos resoluciones de cancelacion de la misma licencia
     */
    MovimientoDeLicencia registrar(MovimientoDeLicencia movimiento);

    /** Todos los movimientos de la licencia, del primero al ultimo. */
    List<MovimientoDeLicencia> deLicencia(long licenciaId);

    /**
     * Los movimientos de varias licencias de golpe, indexados por licencia.
     *
     * <p>Existe para que una pagina de veinte licencias derive sus veinte estados con <b>una</b>
     * consulta. Con {@link #deLicencia} en un bucle serian veintiuna, y eso no se nota en la prueba
     * y si en el padron de una provincia.
     */
    Map<Long, List<MovimientoDeLicencia>> deLicencias(Set<Long> licenciaIds);

    /** Esa licencia ya estaba cancelada: el estado actual no admite cancelarla otra vez. */
    final class LicenciaYaCancelada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public LicenciaYaCancelada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
