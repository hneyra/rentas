package kamayuk.rentas.cuentacorriente.dominio;

import java.util.List;
import java.util.Optional;

/**
 * La proyeccion del saldo (#23). Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p><b>Es la unica tabla de este contexto que admite {@code UPDATE}</b>, y es legitimo
 * precisamente porque no es la verdad: el libro no se toca nunca, y esto es un cache que se
 * recalcula. V7 le concede {@code SELECT, INSERT, UPDATE} a {@code sgtm_app} por eso, y solo por
 * eso.
 */
public interface SaldoRepository {

    /** El saldo proyectado de una obligacion, si ya se proyecto alguna vez. */
    Optional<SaldoProyectado> buscar(ClaveDeSaldo clave);

    /** Los saldos proyectados de un contribuyente, para conciliar o para consultar. */
    List<SaldoProyectado> deContribuyente(long contribuyenteId);

    /**
     * Las filas de la proyeccion que pertenecen a una obligacion: una por cuota.
     *
     * <p>Ordenadas por periodo, que es como la ventanilla las imputa: primero la cuota mas vieja.
     */
    List<SaldoProyectado> deLaObligacion(ClaveDeObligacion obligacion);

    /*
     * Aqui vivio `pendientePorTributo` hasta #639, y se fue a `AsientoRepository`.
     *
     * La proyeccion netea el insoluto de la obligacion entera SIN fecha de corte —no tiene
     * ninguna columna con la que aplicarla—, asi que la cartera del panel incluia la cuota
     * que todavia no vence y la misma cifra salia igual preguntando por enero que por
     * diciembre. Lo pendiente A UNA FECHA solo se puede decir desde el libro, que es donde
     * esta la fecha valor de cada asiento.
     */

    /**
     * <b>Bloquea</b> en la base las filas de esa obligacion hasta que termine la transaccion, y
     * devuelve cuantas bloqueo.
     *
     * <p>Es lo que hace que cobrar dos veces la misma deuda sea imposible y no solo improbable
     * (#33). Sin el, dos cobranzas simultaneas de la misma obligacion leen las dos el mismo saldo
     * —ninguna ha llegado a asentar todavia—, las dos concluyen que hay deuda, y las dos cobran: la
     * segunda no «ve» el abono de la primera porque la primera aun no ha confirmado. Ningun {@code
     * if} de Java puede impedirlo; un {@code SELECT ... FOR UPDATE} si, porque la segunda se queda
     * esperando en el motor y cuando entra ya lee el libro con el abono dentro.
     *
     * <p>Devuelve 0 cuando la obligacion no tiene ninguna fila proyectada, que es tanto como decir
     * que nunca tuvo un asiento: no hay nada que bloquear, y tampoco nada que cobrar.
     */
    int bloquear(ClaveDeObligacion obligacion);

    /**
     * Deja la fila con exactamente este contenido: la inserta si no estaba y la reemplaza si
     * estaba.
     *
     * <p>Reemplazar y no acumular es deliberado. Un {@code UPDATE ... SET saldo = saldo + :monto}
     * es correcto solo si se aplica exactamente una vez por asiento, y basta un reintento de la
     * transaccion para que se aplique dos —y entonces la proyeccion queda mal sin que nada falle,
     * que es el modo de fallo que este issue existe para evitar—. Escribir el total recalculado es
     * idempotente: aplicarlo dos veces deja lo mismo.
     */
    void proyectar(SaldoProyectado saldo);
}
