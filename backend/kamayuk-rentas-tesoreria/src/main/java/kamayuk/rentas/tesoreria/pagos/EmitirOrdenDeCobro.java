package kamayuk.rentas.tesoreria.pagos;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * La mitad de {@code CobrarDeuda} que se quedo en este sistema: <b>pedir que se cobre</b> (P5D).
 *
 * <h2>De donde viene, y que la separacion partio en dos</h2>
 *
 * <p>Antes de P5D, {@code CobrarDeuda} hacia dos cosas en un solo acto y en una sola transaccion:
 * leia el libro para saber cuanto se debe, y cobraba. La extraccion de {@code caja} se llevo la
 * segunda mitad —hoy es {@code CobrarOrdenes}, que cobra una orden sin saber que es— y esta clase
 * es la primera: lee el libro y compone la orden.
 *
 * <p><b>El acto ya no es atomico, y no hay mecanismo que lo devuelva.</b> Entre emitir la orden y
 * cobrarla pasan minutos u horas, y entre cobrarla e imputarla pasa el buzon. Lo que sustituye a la
 * atomicidad es la conciliacion diaria (ADR-0026 §3), que pasa de buena practica a obligacion
 * operativa.
 *
 * <h2>Aqui no se calcula nada</h2>
 *
 * <p>ARQ-01 §3.8 dicho al otro lado de una frontera: «tesoreria asienta abonos; nunca determina».
 * El importe de cada orden es el que {@link ConsultaDeDeudaPublica} devuelve —la suma de las cuatro
 * partes del desglose, {@link ObligacionPublica#total()}—, y la peticion <b>no tiene campo para un
 * importe</b>. Si lo tuviera, la ventanilla podria mandar el que leyo hace cinco minutos, o el que
 * le diera la gana, y la caja lo imprimiria sin discutir: la caja no recalcula, por diseño.
 *
 * <p>Tampoco hay campo para una campaña de beneficio. En el monolito la campaña se guardaba en el
 * recibo <b>como constancia y sin efecto</b>, porque su descuento esta bloqueado por D-02b; aqui no
 * viaja siquiera, y por eso no hay ningun sitio donde un porcentaje inventado pudiera entrar.
 *
 * <h2>Sin transaccion, y es deliberado</h2>
 *
 * <p>Esta clase <b>no</b> lleva {@code @Transactional}. Es un anfitrion que orquesta un lector con
 * su propia transaccion y una llamada HTTP a otro sistema, y abrir una aqui haria dos cosas malas:
 * sostener una conexion del pool mientras se espera a la red, y —lo que ya costo caro en #54, #72 y
 * #430— dejar que una excepcion del lector marque la transaccion como <i>rollback-only</i> y se
 * lleve por delante lo que el anfitrion todavia queria decir.
 *
 * <h2>Emitir dos veces no cobra dos veces</h2>
 *
 * <p>La clave es {@code (sistemaOrigen, referenciaExterna)} y la referencia <b>lleva dentro la
 * fecha</b> a la que se calculo el importe (regla 9): dos emisiones del mismo dia son un reintento
 * y devuelven la orden que ya estaba, con {@code nueva = false}. Y una obligacion ya pagada no
 * llega hasta aqui: el libro ya no tiene su deuda, asi que la emision no la encuentra y se rechaza
 * con {@link NadaQueCobrar} — que es la misma barrera que tenia el monolito, movida de sitio.
 */
@Service
public class EmitirOrdenDeCobro {

    private final ConsultaDeDeudaPublica libro;
    private final OrdenesDeCobro caja;

    public EmitirOrdenDeCobro(ConsultaDeDeudaPublica libro, OrdenesDeCobro caja) {
        this.libro = libro;
        this.caja = caja;
    }

    /**
     * Compone y emite una orden por cada obligacion marcada.
     *
     * <p>Una orden por obligacion y no una por cobranza: es la granularidad con la que el libro
     * imputa, y agregarlas aqui obligaria a repartir el importe al volver — que es donde se pierden
     * cuotas y donde habria que decidir en que orden se aplica el pago, decision que es del Codigo
     * Tributario y no de una ventanilla.
     *
     * @param peticion lo que la ventanilla marco
     * @param observacion por que se emite (regla 10, RNF-052)
     * @throws NadaQueCobrar si ninguna de las obligaciones marcadas tiene deuda a la fecha
     * @throws OrdenesDeCobro.CajaInalcanzable si la caja no contesta
     */
    public Emision emitir(Peticion peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se emite una orden sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        List<ObligacionPublica> conDeuda =
                libro.deTodoElContribuyente(peticion.contribuyenteId(), peticion.aLaFecha());

        List<Emitida> emitidas = new ArrayList<>(peticion.obligaciones().size());
        List<SeleccionDeObligacion> sinDeuda = new ArrayList<>();
        for (SeleccionDeObligacion marcada : peticion.obligaciones()) {
            Optional<ObligacionPublica> enElLibro = buscar(conDeuda, marcada);
            if (enElLibro.isEmpty()) {
                sinDeuda.add(marcada);
                continue;
            }
            emitidas.add(emitirUna(peticion, enElLibro.get(), observacion));
        }

        if (emitidas.isEmpty()) {
            throw new NadaQueCobrar(peticion.contribuyenteId(), peticion.aLaFecha(), sinDeuda);
        }
        return new Emision(emitidas, List.copyOf(sinDeuda), peticion.aLaFecha());
    }

    private Emitida emitirUna(
            Peticion peticion, ObligacionPublica obligacion, Observacion observacion) {
        ReferenciaDeObligacion referencia =
                new ReferenciaDeObligacion(
                        obligacion.tributo(),
                        obligacion.ejercicio(),
                        obligacion.predioId(),
                        obligacion.vehiculoId(),
                        // La fecha de la referencia es la que el LIBRO devolvio, no la que se
                        // pidio. Son la misma hoy, y atarla a la respuesta es lo que impide que
                        // un lector que resolviera «hoy» por su cuenta dejara la orden diciendo
                        // una fecha y el importe siendo de otra.
                        obligacion.fecha());

        OrdenesDeCobro.Emitida enLaCaja =
                caja.emitir(
                        new OrdenesDeCobro.Peticion(
                                referencia,
                                conceptoDe(obligacion),
                                peticion.detalle(),
                                obligacion.total(),
                                obligacion.fecha(),
                                obligacion.fecha(),
                                observacion,
                                peticion.pagadorDocumento(),
                                peticion.pagadorNombre(),
                                peticion.contribuyenteId()));

        return new Emitida(
                enLaCaja.ordenId(),
                referencia,
                obligacion.total(),
                obligacion.fecha(),
                enLaCaja.nueva());
    }

    /**
     * Lo que el administrado lee en la linea del recibo.
     *
     * <p>Es texto, y la caja no lo interpreta: lo guarda y lo imprime. Que aqui diga «PREDIAL 2026»
     * no la hace saber que es un tributo — el dia que cobre un puesto de mercado dira «PUESTO 214,
     * MARZO» y la caja no notara la diferencia.
     */
    private static String conceptoDe(ObligacionPublica obligacion) {
        return obligacion.tributo() + " " + obligacion.ejercicio().valor();
    }

    private static Optional<ObligacionPublica> buscar(
            List<ObligacionPublica> conDeuda, SeleccionDeObligacion marcada) {
        return conDeuda.stream()
                .filter(
                        o ->
                                o.tributo().equals(marcada.tributo())
                                        && o.ejercicio().equals(marcada.ejercicio())
                                        && Objects.equals(o.predioId(), marcada.predioId())
                                        && Objects.equals(o.vehiculoId(), marcada.vehiculoId()))
                .findFirst();
    }

    // ------------------------------------------------------------------

    /**
     * Lo que la ventanilla marco.
     *
     * <p><b>No lleva importe</b>, y esa ausencia es la mitad de lo que este caso de uso protege.
     * Tampoco lleva campaña de beneficio, ni tipo de pago, ni forma de pago: el medio con el que se
     * paga es de la caja y se elige al cobrar, no al emitir.
     *
     * @param contribuyenteId de quien es la deuda; el pagador puede ser otro
     * @param obligaciones las filas marcadas, sin repetir
     * @param aLaFecha a que fecha se pide el importe (regla 9); no se resuelve con el reloj
     * @param detalle lo que se quiera anadir bajo la linea; <b>es la puerta de D-20</b>
     * @param pagadorDocumento quien paga, si se identifico
     * @param pagadorNombre como se llama quien paga, si se identifico
     */
    public record Peticion(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate aLaFecha,
            @Nullable String detalle,
            @Nullable String pagadorDocumento,
            @Nullable String pagadorNombre) {

        public Peticion {
            if (contribuyenteId <= 0) {
                throw new IllegalArgumentException("La orden es de un contribuyente del padron");
            }
            Objects.requireNonNull(obligaciones, "Se emite contra obligaciones marcadas");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (regla 9, RNF-075)");
            obligaciones = List.copyOf(obligaciones);
            if (obligaciones.isEmpty()) {
                throw new IllegalArgumentException(
                        "No se emite una orden sin marcar ninguna obligacion");
            }
            // La misma obligacion dos veces en la misma peticion se rechaza aqui y no en la caja:
            // alli las dos lineas comparten referencia, la segunda seria un reintento de la
            // primera y la cobranza saldria por la mitad de lo que la pantalla enseño, sin que
            // ninguna cifra pareciera mal.
            if (new LinkedHashSet<>(obligaciones).size() != obligaciones.size()) {
                throw new ObligacionRepetida(obligaciones);
            }
        }
    }

    /**
     * @param emitidas una por obligacion con deuda
     * @param sinDeuda las que se marcaron y el libro no debe; se dicen, no se callan
     * @param aLaFecha la fecha con la que se leyo el libro
     */
    public record Emision(
            List<Emitida> emitidas, List<SeleccionDeObligacion> sinDeuda, LocalDate aLaFecha) {

        public Emision {
            emitidas = List.copyOf(emitidas);
            sinDeuda = List.copyOf(sinDeuda);
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (regla 9, RNF-075)");
        }

        /** Lo que la ventanilla va a cobrar, sumado. No es una quinta cifra: es la suma. */
        public Dinero total() {
            return emitidas.stream().map(Emitida::importe).reduce(Dinero.CERO, Dinero::mas);
        }
    }

    /**
     * @param ordenId como la llama la caja; es lo que la ventanilla marca para cobrar
     * @param referencia como la llama este sistema
     * @param importe cuanto, segun el libro
     * @param actualizadoA a que fecha (regla 9)
     * @param nueva falso si la orden ya estaba: el mismo dia, la misma obligacion es la misma orden
     */
    public record Emitida(
            long ordenId,
            ReferenciaDeObligacion referencia,
            Dinero importe,
            LocalDate actualizadoA,
            boolean nueva) {}

    /** Ninguna de las obligaciones marcadas tiene deuda a la fecha. */
    public static final class NadaQueCobrar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NadaQueCobrar(
                long contribuyenteId, LocalDate aLaFecha, List<SeleccionDeObligacion> marcadas) {
            super(
                    "El contribuyente "
                            + contribuyenteId
                            + " no debe nada al "
                            + aLaFecha
                            + " de lo que se marco ("
                            + marcadas.size()
                            + " obligacion(es)). No se emite ninguna orden: una orden de cero"
                            + " soles se cobraria, imprimiria un recibo y no abonaria nada");
        }
    }

    /** La misma obligacion viene dos veces en la misma peticion. */
    public static final class ObligacionRepetida extends IllegalArgumentException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligacionRepetida(List<SeleccionDeObligacion> obligaciones) {
            super(
                    "La misma obligacion viene mas de una vez en la peticion ("
                            + obligaciones.size()
                            + " lineas marcadas y menos distintas). En la caja las dos lineas"
                            + " compartirian referencia, la segunda seria un reintento de la"
                            + " primera y se cobraria menos de lo que la pantalla enseño");
        }
    }
}
