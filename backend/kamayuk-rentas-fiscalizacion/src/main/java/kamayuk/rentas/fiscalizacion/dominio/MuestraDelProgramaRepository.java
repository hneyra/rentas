package kamayuk.rentas.fiscalizacion.dominio;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La muestra sorteada de un programa (#481). <b>Sólo se agrega</b>: no hay método que edite ni que
 * borre una fila, y {@code V60} tampoco le concede a {@code sgtm_app} el privilegio (regla 4).
 */
public interface MuestraDelProgramaRepository {

    /**
     * Escribe la muestra entera. La observación, el usuario y el instante son los mismos para todas
     * las filas: es <b>un</b> acto, no una fila a una.
     */
    int insertar(List<MuestraDelPrograma> filas, Observacion observacion, Instant fechaRegistro);

    /** Si el programa ya sorteó su muestra. Sortearla otra vez no la reemplaza: responde 409. */
    boolean tieneMuestra(long programaId);

    /**
     * La grilla de la muestra de un programa, opcionalmente acotada a un predio — que es como
     * {@code fisc_predial} resuelve su fila para abrir el acta.
     */
    Pagina<MuestraDelPrograma> delPrograma(
            long programaId, @Nullable Long predioId, Paginacion paginacion);

    /**
     * Cuáles de esos predios ya están en la muestra de <b>otro</b> programa que admite visitas
     * ({@code ABIERTO} o {@code EN_PROCESO}): la primera mitad de la exclusión de #481.
     *
     * <p>Un programa {@code CERRADO} no excluye: si lo hiciera, un programa de 2021 bloquearía el
     * padrón para siempre.
     */
    Set<Long> prediosEnProgramasAbiertos(long programaPropio, Set<Long> predios);
}
