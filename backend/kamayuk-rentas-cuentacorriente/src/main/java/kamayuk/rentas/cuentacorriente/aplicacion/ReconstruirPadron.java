package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.Divergencia;
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
 * <p><b>Recorre los contribuyentes con asientos, y eso deja un caso fuera, dicho</b>: el de un
 * contribuyente <i>sin ningun asiento</i> y con filas en la proyeccion. {@link
 * ReconstruirSaldo#conciliar} si las reporta cuando el contribuyente tiene libro —«el libro dice
 * cero y la fila dice otra cosa»—, pero a uno sin libro este cursor no llega. Tampoco lo repararia
 * nadie: la reconstruccion solo reescribe las obligaciones que el libro tiene, y {@code
 * kamayuk_app} no puede borrar una fila de {@code saldo_proyectado} (V1 le concede {@code SELECT,
 * INSERT, UPDATE}).
 */
@Service
public class ReconstruirPadron {

    /** Cuantos contribuyentes se piden por vuelta. Ni uno —un viaje por fila— ni todos. */
    private static final int TAMANO_DEL_LOTE = 200;

    private final AsientoRepository asientos;
    private final ReconstruirSaldo reconstruir;
    private final TransactionTemplate transacciones;

    public ReconstruirPadron(
            AsientoRepository asientos,
            ReconstruirSaldo reconstruir,
            PlatformTransactionManager gestor) {
        this.asientos = asientos;
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
        return recorrer(desdeContribuyente, reconstruir::deContribuyente);
    }

    /**
     * Compara la proyeccion de <b>cada</b> contribuyente del padron contra su libro, y
     * <b>reporta</b> lo que no cuadra (#630).
     *
     * <p>No repara nada, igual que {@link ReconstruirSaldo#conciliar}: la reparacion es {@link
     * #reconstruir}, y la pide quien lea el informe. Cada contribuyente se lee en su propia
     * transaccion de lectura, de modo que conciliar el padron entero no retiene una conexion
     * durante toda la pasada.
     *
     * @return cuantos contribuyentes se miraron y, de los que no cuadran, sus divergencias
     */
    public Conciliacion conciliar() {
        Map<Long, List<Divergencia>> divergentes = new LinkedHashMap<>();
        long[] mirados = {0};
        recorrer(
                0L,
                contribuyenteId -> {
                    mirados[0]++;
                    List<Divergencia> suyas = reconstruir.conciliar(contribuyenteId);
                    if (!suyas.isEmpty()) {
                        divergentes.put(contribuyenteId, suyas);
                    }
                });
        return new Conciliacion(mirados[0], divergentes);
    }

    /**
     * El recorrido comun: el padron del libro por cursor de identificador, en lotes, y el paso de
     * cada contribuyente fuera de toda transaccion para que abra la suya.
     *
     * @return el ultimo identificador recorrido, con el que reanudar
     */
    private long recorrer(long desdeContribuyente, LongConsumer paso) {
        long ultimo = desdeContribuyente;
        while (true) {
            long desde = ultimo;
            List<Long> lote =
                    transacciones.execute(
                            estado -> asientos.contribuyentesConAsientos(desde, TAMANO_DEL_LOTE));
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
     * Lo que una conciliacion del padron encontro.
     *
     * @param contribuyentes cuantos contribuyentes con libro se conciliaron
     * @param divergencias las de cada contribuyente que no cuadra, en el orden del recorrido; vacio
     *     si la proyeccion entera coincide con el libro
     */
    public record Conciliacion(long contribuyentes, Map<Long, List<Divergencia>> divergencias) {

        public Conciliacion {
            // Sin `Map.copyOf`, que no conserva el orden: el informe sale en el del padron.
            divergencias = Collections.unmodifiableMap(new LinkedHashMap<>(divergencias));
        }

        /** Si la proyeccion coincide con el libro en todo el padron. */
        public boolean cuadra() {
            return divergencias.isEmpty();
        }
    }
}
