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
 * borre una fila, y {@code V60} tampoco le concede a {@code kamayuk_app} el privilegio (regla 4).
 */
public interface MuestraDelProgramaRepository {

    /**
     * Escribe la muestra entera. La observación, el usuario y el instante son los mismos para todas
     * las filas: es <b>un</b> acto, no una fila a una.
     */
    int insertar(List<MuestraDelPrograma> filas, Observacion observacion, Instant fechaRegistro);

    /**
     * Si el programa ya sorteó su muestra (#346). Sortearla otra vez no la reemplaza: responde 409.
     *
     * <p>Lo dice la fila del sorteo, no las de la muestra: un sorteo en el que no entró nadie no
     * deja ninguna fila en la muestra y sigue siendo un sorteo. Los programas sorteados antes de
     * que el sorteo tuviera fila propia ({@code V41}) cuentan por sus filas.
     */
    boolean yaSorteo(long programaId);

    /**
     * Registra el sorteo del programa con su reparto (#346).
     *
     * @return {@code false} si ese programa ya tenía su sorteo: dos sorteos simultáneos del mismo
     *     programa chocan en la clave de {@code programa_sorteo}, y el segundo no escribe nada
     */
    boolean registrarSorteo(
            long programaId,
            ResultadoDelSorteo resultado,
            Observacion observacion,
            Instant fechaRegistro);

    /**
     * Serializa los sorteos de la municipalidad hasta el fin de la transacción (#346).
     *
     * <p>La exclusión entre programas —un predio, un programa abierto (ADR-0023)— exige que el
     * segundo sorteo vea las filas que el primero escribió, y en {@code READ COMMITTED} sólo lo
     * garantiza esperar a que el primero confirme. Se toma <b>antes</b> de la primera lectura en
     * que el sorteo se apoya. Sortear es una acción rara: esperar no cuesta nada.
     */
    void bloquearLosSorteos();

    /**
     * La grilla de la muestra de un programa, opcionalmente acotada a un predio — que es como
     * {@code fisc_predial} resuelve su fila para abrir el acta.
     */
    Pagina<MuestraDelPrograma> delPrograma(
            long programaId, @Nullable Long predioId, Paginacion paginacion);

    /**
     * Cuántas unidades sorteó la muestra del programa: la segunda etapa del embudo (#196).
     *
     * <p>Es un {@code count} y no el {@code totalElementos} de una página de tamaño uno: pedir una
     * página para leer su sobre trae además una fila y deriva su columna «Estado», que es trabajo
     * que nadie va a mirar.
     */
    int tamanoDeLaMuestra(long programaId);

    /**
     * Cuáles de esos predios ya están en la muestra de <b>otro</b> programa que admite visitas
     * ({@code ABIERTO} o {@code EN_PROCESO}): la primera mitad de la exclusión de #481.
     *
     * <p>Un programa {@code CERRADO} no excluye: si lo hiciera, un programa de 2021 bloquearía el
     * padrón para siempre. Y desde #341 un programa <b>llega</b> a {@code CERRADO}: lo cierra
     * {@code CerrarProgramaFiscalizacion}. Hasta entonces ninguno llegaba, y esta salvedad no
     * apartaba nada.
     */
    Set<Long> prediosEnProgramasAbiertos(long programaPropio, Set<Long> predios);
}
