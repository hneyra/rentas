package kamayuk.rentas.cuentacorriente.dominio;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * La proyeccion del saldo (#23). Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p><b>Es la unica tabla de este contexto que admite {@code UPDATE}</b>, y es legitimo
 * precisamente porque no es la verdad: el libro no se toca nunca, y esto es un cache que se
 * recalcula. V7 le concede {@code SELECT, INSERT, UPDATE} a {@code kamayuk_app} por eso, y solo por
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
     * Bloquea <b>varias</b> obligaciones, cada una una sola vez y siempre en el mismo orden, y
     * devuelve cuantas filas bloqueo en total (#364).
     *
     * <p>Es la unica politica de bloqueo de este contexto. Dos operaciones que se solapan sobre las
     * mismas obligaciones tienen que pedir los mismos candados en el mismo orden, o se abrazan y
     * PostgreSQL aborta una con {@code 40P01}. Hasta #364 cada escritor ordenaba por su cuenta y el
     * convenio lo hacia distinto que la cobranza; ahora quien bloquea varias le pasa aqui sus
     * claves, en el orden que quiera y con repetidas, y el orden lo pone {@link
     * ClaveDeObligacion#ORDEN_DE_BLOQUEO}, que fuera de este paquete no se ve.
     *
     * <p>Deduplica por {@code equals} y <b>despues</b> ordena, y no con un {@code TreeSet} del
     * comparador, que haria las dos cosas de una vez. Medido al escribirlo: con un {@code TreeSet},
     * quitarle al comparador el desempate por deudor no desordenaba a los dos condominos de #431,
     * los <b>fundia</b> en uno, y el cobro abonaba una obligacion que nadie habia bloqueado. Un
     * comparador que deja de ser total tiene que costar, como mucho, un orden peor; nunca un
     * candado menos.
     */
    default int bloquearEnOrden(Collection<ClaveDeObligacion> obligaciones) {
        List<ClaveDeObligacion> enOrden =
                obligaciones.stream()
                        .distinct()
                        .sorted(ClaveDeObligacion.ORDEN_DE_BLOQUEO)
                        .toList();
        int bloqueadas = 0;
        for (ClaveDeObligacion obligacion : enOrden) {
            bloqueadas += bloquear(obligacion);
        }
        return bloqueadas;
    }

    /**
     * Los contribuyentes con alguna fila de la proyeccion <b>distinta de cero</b> y <b>ningun</b>
     * asiento en el libro, en orden de identificador, desde {@code despuesDe} y como mucho {@code
     * cuantos} (#641).
     *
     * <p>Es el padron que {@link AsientoRepository#contribuyentesConAsientos} no puede dar: el de
     * una proyeccion que ningun asiento respalda —restos de una migracion, o una fila escrita sin
     * su asiento—. Tiene la misma forma, cursor por identificador y no {@code OFFSET}, por el mismo
     * motivo.
     *
     * <p>Las filas que ya estan a cero no lo hacen salir: el libro de un contribuyente sin asientos
     * dice cero, y una fila a cero dice lo mismo. Es solo una preseleccion; quien decide si cuadra
     * es la conciliacion de cada contribuyente, que vuelve a leer el libro y la proyeccion en una
     * sola instantanea.
     */
    List<Long> contribuyentesConSaldoSinLibro(long despuesDe, int cuantos);

    /**
     * Pone a cero el insoluto de las filas de un contribuyente que <b>no tiene ningun asiento</b>,
     * y devuelve cuantas cambio (#641).
     *
     * <p>Es un {@code UPDATE} y no un {@code DELETE}, y no por gusto: {@code kamayuk_app} no tiene
     * {@code DELETE} sobre {@code saldo_proyectado} (V1), y la regla 4 no se negocia. La fila se
     * queda, diciendo lo que dice el libro: cero.
     *
     * <p>Que el contribuyente no tenga libro se comprueba <b>en la misma sentencia</b>, y no se da
     * por supuesto porque saliera en {@link #contribuyentesConSaldoSinLibro}: entre el cursor y
     * esta escritura puede llegarle su primer asiento, y entonces sus filas ya no son un resto sino
     * un cache con quien lo respalde. Solo toca {@code insoluto_saldo} y {@code fecha_calculo}: la
     * fase y el ultimo asiento de una obligacion que el libro no tiene no se pueden sacar del
     * libro, y la conciliacion no los compara.
     *
     * @param calculadoEn lo que queda en {@code fecha_calculo}: cuando se dejo a cero
     */
    int ponerACeroSinLibro(long contribuyenteId, Instant calculadoEn);

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
