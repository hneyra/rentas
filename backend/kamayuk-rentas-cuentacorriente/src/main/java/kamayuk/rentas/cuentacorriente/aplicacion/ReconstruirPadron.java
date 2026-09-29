package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.Divergencia;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconstruye —y concilia— el saldo proyectado de <b>todo el padron</b>, en lotes y reanudable
 * (#23, #630).
 *
 * <h2>Por que es una clase aparte de {@link ReconstruirSaldo}</h2>
 *
 * <p>Cada contribuyente tiene que reconstruirse en <b>su propia transaccion</b>: con miles de
 * contribuyentes, una sola transaccion para todo mantendria abierta una conexion y un bloqueo
 * durante la carga entera, y un fallo a la mitad desharia lo ya hecho —con lo que reanudar no
 * serviria de nada—.
 *
 * <p>Eso se consigue llamando a {@link ReconstruirSaldo#deContribuyente}, que es un
 * {@code @Service} <b>distinto</b> y con su propio {@code @Transactional}, desde un metodo que
 * <b>no</b> lleva la anotacion: cada llamada atraviesa el proxy de Spring y abre la suya. Escribir
 * este bucle dentro de {@code ReconstruirSaldo} lo rompe entero, porque una llamada a otro metodo
 * de la misma clase no pasa por el proxy y no abre transaccion ninguna. Es el mismo reparto que
 * {@code ImportarVias} y {@code RegistrarVia} en {@code catastro}, y por el mismo motivo.
 *
 * <h2>Reanudable de verdad</h2>
 *
 * <p>El recorrido va por <b>cursor de identificador</b>, no por {@code OFFSET}: el proceso devuelve
 * el ultimo contribuyente terminado, y volver a lanzarlo con ese valor sigue exactamente desde ahi.
 * Con {@code OFFSET}, un contribuyente insertado a mitad del proceso desplaza la ventana y hace que
 * otro se salte sin que nada avise.
 *
 * <h2>La lectura del lote tambien necesita su transaccion</h2>
 *
 * <p>Toda consulta a una tabla de tenant necesita el {@code SET LOCAL} que abre la transaccion: sin
 * el, la politica RLS no encuentra contexto y la consulta <b>falla</b> —que es exactamente lo que
 * debe pasar (ARQ-03)—. Por eso el cursor se lee dentro de un {@link TransactionTemplate} corto y
 * propio, en vez de anotar este metodo: anotarlo metaria el padron entero en una sola transaccion y
 * destruiria justo la propiedad que esta clase existe para conservar.
 *
 * <h2>Y la conciliacion recorre el mismo padron (#630)</h2>
 *
 * <p>{@link #conciliar} es el mismo recorrido con {@link ReconstruirSaldo#conciliar} en vez de
 * {@link ReconstruirSaldo#deContribuyente}, y vive aqui por los mismos tres motivos: una
 * transaccion por contribuyente —de lectura—, que tiene que atravesar el proxy; el cursor por
 * identificador; y la lectura del lote en su transaccion corta. Escrito en otra clase habria sido
 * este bucle copiado.
 *
 * <h2>Dos padrones, y no uno (#641)</h2>
 *
 * <p>El cursor del libro solo da los contribuyentes <b>con</b> asientos. Hasta #641 eso dejaba
 * fuera, dicho aqui mismo, a uno <i>sin ningun asiento</i> y con filas en la proyeccion —restos de
 * una migracion, o una fila escrita sin su asiento—: {@link ReconstruirSaldo#conciliar} lo habria
 * informado, pero ese cursor no llegaba a el, y el estado de cuenta ensenaba una cifra que el libro
 * no respalda sin que nada lo dijera.
 *
 * <p>Por eso {@link #conciliar} hace <b>dos</b> recorridos con el mismo bucle: el del libro, y el
 * de {@link SaldoRepository#contribuyentesConSaldoSinLibro}, que da los contribuyentes con alguna
 * fila distinta de cero y ningun asiento. A cada uno de esos se le aplica la misma {@link
 * ReconstruirSaldo#conciliar} —su libro esta vacio, asi que informa cada fila que no dice cero—, y
 * lo que sale se devuelve aparte, en {@link Conciliacion#sinLibro}, porque se repara distinto.
 *
 * <p>La reconstruccion no los repara: solo reescribe las obligaciones que el libro tiene, y {@code
 * kamayuk_app} no puede borrar una fila de {@code saldo_proyectado} (V1 le concede {@code SELECT,
 * INSERT, UPDATE}). Los repara {@link #ponerACeroSinLibro}, con un {@code UPDATE} que deja sus
 * filas a cero, que es lo que el libro dice de ellos. Un contribuyente <b>con</b> asientos y una
 * fila de mas, de una obligacion que su libro no tiene, sigue siendo cosa del primer recorrido: se
 * informa si no dice cero, y la reconstruccion no la toca.
 */
@Service
public class ReconstruirPadron {

    /** Cuantos contribuyentes se piden por vuelta. Ni uno —un viaje por fila— ni todos. */
    private static final int TAMANO_DEL_LOTE = 200;

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final ReconstruirSaldo reconstruir;
    private final TransactionTemplate transacciones;

    public ReconstruirPadron(
            AsientoRepository asientos,
            SaldoRepository saldos,
            ReconstruirSaldo reconstruir,
            PlatformTransactionManager gestor) {
        this.asientos = asientos;
        this.saldos = saldos;
        this.reconstruir = reconstruir;
        this.transacciones = new TransactionTemplate(gestor);
        this.transacciones.setReadOnly(true);
    }

    /**
     * Reconstruye desde {@code desdeContribuyente} hasta el final del padron.
     *
     * @param desdeContribuyente el ultimo identificador ya terminado; 0 para empezar de cero
     * @return el ultimo identificador reconstruido, con el que reanudar si hiciera falta
     */
    public long reconstruir(long desdeContribuyente) {
        return recorrer(
                desdeContribuyente,
                asientos::contribuyentesConAsientos,
                reconstruir::deContribuyente);
    }

    /**
     * Compara la proyeccion de <b>cada</b> contribuyente del padron contra su libro, y
     * <b>reporta</b> lo que no cuadra (#630).
     *
     * <p>No repara nada, igual que {@link ReconstruirSaldo#conciliar}: la reparacion es {@link
     * #reconstruir} —y {@link #ponerACeroSinLibro} para quien no tiene libro—, y la pide quien lea
     * el informe. Cada contribuyente se lee en su propia transaccion de lectura, de modo que
     * conciliar el padron entero no retiene una conexion durante toda la pasada.
     *
     * <p>Recorre los dos padrones (#641): el del libro, y el de los contribuyentes con proyeccion
     * distinta de cero y sin ningun asiento, que el primero no puede ver.
     *
     * @return cuantos contribuyentes se miraron y, de los que no cuadran, sus divergencias
     */
    public Conciliacion conciliar() {
        long[] mirados = {0};
        Map<Long, List<Divergencia>> divergentes =
                conciliarCada(asientos::contribuyentesConAsientos, mirados);
        Map<Long, List<Divergencia>> sinLibro =
                conciliarCada(saldos::contribuyentesConSaldoSinLibro, mirados);
        return new Conciliacion(mirados[0], divergentes, sinLibro);
    }

    /**
     * Pone a cero la proyeccion de cada contribuyente que no tiene ningun asiento, cada uno en su
     * propia transaccion (#641).
     *
     * <p>Es la reparacion del segundo recorrido de {@link #conciliar}, y como {@link #reconstruir}
     * la pide quien lee el informe: no la llama la conciliacion.
     *
     * @return cuantas filas quedaron a cero
     */
    public long ponerACeroSinLibro() {
        long[] puestas = {0};
        recorrer(
                0L,
                saldos::contribuyentesConSaldoSinLibro,
                contribuyenteId -> puestas[0] += reconstruir.ponerACeroSinLibro(contribuyenteId));
        return puestas[0];
    }

    /** Concilia cada contribuyente de un padron y devuelve los que no cuadran, en su orden. */
    private Map<Long, List<Divergencia>> conciliarCada(Padron padron, long[] mirados) {
        Map<Long, List<Divergencia>> divergentes = new LinkedHashMap<>();
        recorrer(
                0L,
                padron,
                contribuyenteId -> {
                    mirados[0]++;
                    List<Divergencia> suyas = reconstruir.conciliar(contribuyenteId);
                    if (!suyas.isEmpty()) {
                        divergentes.put(contribuyenteId, suyas);
                    }
                });
        return divergentes;
    }

    /**
     * El recorrido comun: un padron por cursor de identificador, en lotes, y el paso de cada
     * contribuyente fuera de toda transaccion para que abra la suya.
     *
     * @return el ultimo identificador recorrido, con el que reanudar
     */
    private long recorrer(long desdeContribuyente, Padron padron, LongConsumer paso) {
        long ultimo = desdeContribuyente;
        while (true) {
            long desde = ultimo;
            List<Long> lote =
                    transacciones.execute(estado -> padron.siguientes(desde, TAMANO_DEL_LOTE));
            if (lote == null || lote.isEmpty()) {
                return ultimo;
            }
            for (long contribuyenteId : lote) {
                paso.accept(contribuyenteId);
                ultimo = contribuyenteId;
            }
        }
    }

    /**
     * Un padron recorrible por cursor: los contribuyentes despues de {@code despuesDe}, en orden de
     * identificador, y como mucho {@code cuantos}.
     */
    @FunctionalInterface
    private interface Padron {
        List<Long> siguientes(long despuesDe, int cuantos);
    }

    /**
     * Lo que una conciliacion del padron encontro.
     *
     * @param contribuyentes cuantos contribuyentes se conciliaron, de los dos padrones
     * @param divergencias las de cada contribuyente <b>con</b> asientos que no cuadra, en el orden
     *     del recorrido; se reparan reconstruyendo
     * @param sinLibro las de cada contribuyente <b>sin ningun</b> asiento y con proyeccion distinta
     *     de cero (#641), en el orden del recorrido; se reparan poniendolas a cero
     */
    public record Conciliacion(
            long contribuyentes,
            Map<Long, List<Divergencia>> divergencias,
            Map<Long, List<Divergencia>> sinLibro) {

        public Conciliacion {
            // Sin `Map.copyOf`, que no conserva el orden: el informe sale en el del padron.
            divergencias = Collections.unmodifiableMap(new LinkedHashMap<>(divergencias));
            sinLibro = Collections.unmodifiableMap(new LinkedHashMap<>(sinLibro));
        }

        /** Si la proyeccion coincide con el libro en todo el padron, tenga libro o no. */
        public boolean cuadra() {
            return divergencias.isEmpty() && sinLibro.isEmpty();
        }
    }
}
