package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconstruye el saldo proyectado de <b>todo el padron</b>, en lotes y reanudable (#23).
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
                reconstruir.deContribuyente(contribuyenteId);
                ultimo = contribuyenteId;
            }
        }
    }
}
