package kamayuk.rentas.tesoreria.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las lecturas agregadas de la recaudacion (#36, RF-088, RF-089). Ningun metodo recibe la
 * municipalidad (regla 2): la filtra la politica RLS.
 *
 * <p><b>Solo lee, y sin bloquear nada.</b> Ninguna de estas consultas pide {@code FOR UPDATE}: el
 * avance se mira mientras la ventanilla sigue cobrando, y una lectura que bloqueara la fila del
 * turno pondria la cola a esperar por un informe. La foto que devuelven es la del instante en que
 * se leyeron, y por eso todas las respuestas viajan con su fecha (regla 9).
 */
public interface RecaudacionRepository {

    /** Lo recaudado por tributo en el rango del criterio, de mayor a menor. */
    List<RecaudacionDeTributo> porTributo(CriterioDeRecaudacion criterio);

    /**
     * Lo recaudado por area generadora, partida presupuestal y tributo.
     *
     * <p>Las filas tributarias salen con area y partida en nulo, porque el dato no existe: ver
     * {@link RecaudacionDePartida}. Con el criterio filtrado por area, esas filas <b>no</b>
     * aparecen —no se puede filtrar por un area que no consta—, y el reporte lo dice.
     */
    List<RecaudacionDePartida> porPartida(CriterioDeRecaudacion criterio);

    /**
     * El turno de esa caja y ese cajero en ese dia, <b>sin bloquear</b>, si existe.
     *
     * <p>Lo necesita el avance en vivo: parte de (caja, cajero, fecha) como la pantalla, y tiene
     * que resolverlos a un turno sin pasar por {@code TurnoDeCajaRepository#bloquear}, que es la
     * puerta de la cobranza y bloquea a proposito.
     */
    Optional<TurnoDeCaja> turnoDe(String codigoDeCaja, String cajero, java.time.LocalDate fecha);
}
