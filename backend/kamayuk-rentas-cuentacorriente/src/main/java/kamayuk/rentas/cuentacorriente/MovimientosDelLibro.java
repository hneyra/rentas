package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.jspecify.annotations.Nullable;

/**
 * Que se le ha cobrado a un contribuyente y que deuda se le ha dado de alta o de baja (#25, RF-045,
 * RF-046, RF-048).
 *
 * <p>Es la <b>septima</b> API publica de este modulo, despues de {@link ConsultaDeDeudaPublica},
 * {@link GeneradorDeCargos}, {@link MovimientoDeFase}, {@link RegistroDeAbonos}, {@link
 * AcogimientoAConvenio} y {@link ConciliacionDeCaja}. Vive en el paquete raiz por lo mismo que las
 * otras seis: Spring Modulith trata como interno todo lo que esta en un subpaquete, asi que esto es
 * exactamente lo que otro contexto puede ver del libro. Sus tablas, no.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para la consulta unificada de {@code rentas} (#25, RF-046), que dibuja en una sola pantalla
 * las pestañas «Pagos Realizados» y «Altas y Bajas» al lado de las deudas, los convenios y los
 * valores. {@link ConsultaDeDeudaPublica} responde «cuanto debe»; esto responde «que le ha pasado a
 * esa deuda», que es la otra mitad de la misma pregunta y la que ninguna de las otras seis cubria.
 *
 * <p>Sin este puerto, la unica salida habria sido que {@code rentas} consultara {@code
 * cuenta_corriente_asiento} —cruzar el limite del contexto— o que la pantalla llamara a dos
 * endpoints y compusiera; lo segundo deja la composicion en la interfaz, y con ella la tentacion de
 * sumar ahi (RNF-083).
 *
 * <h2>Al reves de la regla 2</h2>
 *
 * <p>ARQ-01 §4: «cuentacorriente no conoce a nadie». Esta interfaz es la excepcion que la regla
 * preve —otro contexto depende de {@code cuentacorriente}, nunca al reves—, y por eso no recibe
 * ningun tipo de otro contexto: solo el <b>codigo</b> del contribuyente, que es lo que teclea quien
 * atiende y con lo que {@code CriterioDePagos} y {@code CriterioDeAltasBajas} ya saben filtrar.
 *
 * <p>Que este puerto pida el codigo y {@link ConsultaDeDeudaPublica} pida el identificador no es un
 * descuido: cada uno pide la clave con la que su propia consulta ya resuelve, y quien llama tiene
 * las dos de una sola lectura del padron. Traducir aqui obligaria a una consulta de mas por
 * seccion.
 *
 * <h2>Solo lectura</h2>
 *
 * <p>No hay ningun metodo que escriba. Asentar es {@link GeneradorDeCargos}, abonar es {@link
 * RegistroDeAbonos} y mover de fase es {@link MovimientoDeFase}; publicar aqui una escritura seria
 * abrir un segundo camino al libro sin la auditoria ni la observacion que esos tres exigen.
 */
public interface MovimientosDelLibro {

    /**
     * El historial de pagos del contribuyente, entre dos fechas opcionales, paginado (RF-048).
     *
     * <p>Un pago es un asiento {@code ABONO} de concepto {@code PAGO}. Los demas abonos no salen
     * todos por {@link #altasYBajasDe}: ahi van solo los que nacen de un <b>acto</b> de alta o de
     * baja (#640), y el abono con que una cobranza cancela el insoluto no es ninguna de las dos
     * cosas. La distincion la mantiene {@code cuentacorriente} y no quien pregunta, que es justo el
     * conocimiento que este puerto existe para no repartir.
     *
     * <p>Paginado, a diferencia de {@link ConsultaDeDeudaPublica#deTodoElContribuyente}: las
     * obligaciones con deuda de un contribuyente son pocas, pero sus pagos son los de todos los
     * anios que lleve pagando.
     *
     * @param codigoContribuyente el codigo del titular, tal como lo teclea la pantalla
     * @param desde fecha valor minima, inclusive; {@code null} trae desde el primer pago
     * @param hasta fecha valor maxima, inclusive; {@code null} trae hasta el ultimo
     */
    Pagina<MovimientoDelLibro> pagosDe(
            String codigoContribuyente,
            @Nullable LocalDate desde,
            @Nullable LocalDate hasta,
            Paginacion paginacion);

    /**
     * Las altas y bajas de deuda del contribuyente, paginadas (RF-045).
     *
     * <p>Son los dos <b>actos</b> de RF-043 y RF-044, no todo movimiento del libro (#640): ni el
     * abono de una cobranza —que tiene su propio metodo—, ni el cargo de la emision masiva, ni el
     * que cristaliza el interes devengado al cobrar. Los tres se escriben con los mismos conceptos
     * del desglose que un acto, asi que el concepto no los separa; lo hace la columna que el libro
     * estampa desde V68.
     *
     * <p>Una baja de deuda es la de RF-044 la teclee quien la teclee: desde #662 salen aqui tambien
     * las que asienta {@code ExtincionDeDeuda} cuando una resolucion de gerencia deja una multa sin
     * efecto, que son los mismos asientos y las mismas causales.
     *
     * <p>Los asientos anteriores a V68 nacieron sin la columna y <b>no salen</b>; ver {@code
     * AsientoRepositoryJdbc#altasYBajas} para por que no se pueden reparar.
     *
     * @param codigoContribuyente el codigo del titular
     * @param tributo filtro opcional de tributo; {@code null} trae todos
     */
    Pagina<MovimientoDelLibro> altasYBajasDe(
            String codigoContribuyente, @Nullable String tributo, Paginacion paginacion);
}
