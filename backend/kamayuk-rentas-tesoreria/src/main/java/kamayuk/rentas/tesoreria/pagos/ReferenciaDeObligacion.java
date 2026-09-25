package kamayuk.rentas.tesoreria.pagos;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Como {@code rentas} nombra lo que le manda a cobrar a la caja (P5D, ADR-0026 §1).
 *
 * <h2>Por que existe, y por que la caja no la entiende</h2>
 *
 * <p>La caja recibe ordenes con una {@code referenciaExterna} <b>opaca</b>: no la analiza, no la
 * compara por partes y no la ordena. Lo unico que hace es guardarla y devolverla dentro del evento
 * del pago. Eso es lo que la hace reutilizable — el dia que cobre un puesto de mercado, la
 * referencia sera la del contrato de ese puesto y la caja no notara la diferencia.
 *
 * <p>Esta clase es la otra mitad: <b>el formato es de `rentas`</b> y vive aqui. Se compone al
 * emitir la orden y se lee al recibir el pago, y por eso las dos operaciones estan en el mismo
 * tipo: si estuvieran en dos, un cambio de formato dejaria los pagos en vuelo sin poder leerse.
 *
 * <h2>El formato</h2>
 *
 * <p>{@code TRIBUTO|EJERCICIO|CONTRIBUYENTE|PREDIO|VEHICULO|FECHA}, con los dos de la unidad vacios
 * cuando no la hay. La barra vertical y no la coma ni los dos puntos: un tributo no la lleva nunca,
 * y el separador tiene que ser algo que ningun componente pueda contener — es la misma decision que
 * #428 tomo con el numero de la notificacion administrativa.
 *
 * <p>{@link #leer} acepta tambien la forma de <b>cinco</b> partes, la de antes de #431 —{@code
 * TRIBUTO|EJERCICIO|PREDIO|VEHICULO|FECHA}—, y no por nostalgia: es lo que el parrafo de arriba
 * sobre los pagos en vuelo pide. Una orden emitida la vispera del despliegue vuelve con esa forma,
 * y su deudor sigue saliendo de donde salia, del {@code pagador.idExterno} del pago.
 *
 * <h2>Por que la fecha esta DENTRO de la referencia</h2>
 *
 * <p>Porque es la regla 9 aplicada a la identidad de la orden: <b>no existe «la deuda» de una
 * obligacion</b>, existe {@code deudaActualizadaA(fecha)}. Una orden por «el predial 2026 del
 * predio 123» no es una cosa; una orden por «el predial 2026 del predio 123, al 16/03/2026, 340,00»
 * si lo es.
 *
 * <p>Y eso es exactamente lo que la idempotencia de la caja necesita, porque su clave es {@code
 * (sistemaOrigen, referenciaExterna)}: dos emisiones del <b>mismo dia</b> son un reintento y
 * devuelven la orden que ya estaba; dos emisiones de dias distintos son dos importes distintos y
 * son dos ordenes. Sin la fecha dentro, la primera emision congelaria el importe para siempre y el
 * interes devengado despues no se podria cobrar por ninguna via — y nada lo diria, porque una orden
 * con el importe de la semana pasada se ve igual que una correcta.
 *
 * <p>La fecha <b>no forma parte de la obligacion</b>, y por eso {@link #comoSeleccion()} no la
 * mira: al imputar el pago lo que importa es contra que se abona, y cuanto lo dice el importe
 * cobrado.
 *
 * <h2>Por que lleva al DEUDOR (#431)</h2>
 *
 * <p>Hasta #431 esta cabecera decia lo contrario: «no lleva el contribuyente, y es deliberado: una
 * obligacion identificada por su deudor haria imposible que un tercero pague la deuda de otro». La
 * premisa confundia al <b>deudor</b> con el <b>pagador</b>, y fallaba por dos caminos:
 *
 * <ul>
 *   <li><b>Un recibo con ordenes de dos deudores se rechazaba.</b> La caja junta ordenes en un
 *       recibo y publica un solo pagador —el de la primera orden—; sin el deudor en la referencia,
 *       el libro buscaba todas las lineas bajo ese pagador, la del otro deudor no tenia saldo y el
 *       pago quedaba {@code RECHAZADO}. Una hija que paga su predial y el de su madre en la misma
 *       cola dejaba 500,00 en caja y a las dos debiendolo todo.
 *   <li><b>Dos condominos se cobraban la misma orden.</b> La idempotencia de la caja es por
 *       referencia, el libro admite la misma obligacion para dos titulares ({@code saldo_uq} lleva
 *       {@code contribuyente_id}), y el segundo que emitia el mismo dia recibia la orden del
 *       primero: su importe, y el primero de pagador. Pagarla extinguia la deuda del otro.
 * </ul>
 *
 * <p>En el libro, la identidad de una obligacion <b>incluye a su deudor</b> ({@code
 * ClaveDeObligacion}), y esta referencia es el nombre de esa obligacion al otro lado de la
 * frontera. Llevarlo no impide que pague un tercero: el pagador viaja aparte ({@code
 * pagadorDocumento}, {@code pagadorNombre}), y es precisamente el deudor en la referencia lo que
 * permite que el pago de un tercero se impute a quien debe y no a quien pago.
 *
 * <p>{@code contribuyenteId} es nulo <b>solo</b> en una referencia de cinco partes, leida de un
 * pago en vuelo; toda orden que este sistema emite desde #431 lo lleva.
 */
public record ReferenciaDeObligacion(
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        LocalDate actualizadoA) {

    private static final String SEPARADOR = "|";

    public ReferenciaDeObligacion {
        Objects.requireNonNull(tributo, "La referencia necesita su tributo");
        Objects.requireNonNull(ejercicio, "La referencia necesita su ejercicio");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty() || tributo.contains(SEPARADOR)) {
            throw new IllegalArgumentException(
                    "El tributo no puede estar vacio ni contener el separador: '" + tributo + "'");
        }
        if (contribuyenteId != null && contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "El deudor es un contribuyente del padron: " + contribuyenteId);
        }
        if (predioId != null && vehiculoId != null) {
            throw new IllegalArgumentException(
                    "Una obligacion es de un predio o de un vehiculo, no de los dos");
        }
        Objects.requireNonNull(actualizadoA, "Toda cifra indica su fecha (regla 9, RNF-075)");
    }

    /** La orden de un deudor: la obligacion que se cobra y de quien es (#431). */
    public static ReferenciaDeObligacion de(
            long contribuyenteId, SeleccionDeObligacion obligacion, LocalDate aLaFecha) {
        return new ReferenciaDeObligacion(
                obligacion.tributo(),
                obligacion.ejercicio(),
                contribuyenteId,
                obligacion.predioId(),
                obligacion.vehiculoId(),
                aLaFecha);
    }

    /**
     * Lo que viaja a la caja.
     *
     * <p>Con el deudor dentro (#431). Sin el —solo en una referencia de cinco partes que se leyo de
     * un pago en vuelo— devuelve esa misma forma, de modo que leer y volver a escribir no cambia el
     * texto.
     */
    public String texto() {
        return tributo
                + SEPARADOR
                + ejercicio.valor()
                + SEPARADOR
                + (contribuyenteId == null ? "" : contribuyenteId + SEPARADOR)
                + (predioId == null ? "" : predioId)
                + SEPARADOR
                + (vehiculoId == null ? "" : vehiculoId)
                + SEPARADOR
                + actualizadoA;
    }

    /**
     * Lee lo que vuelve dentro del evento del pago.
     *
     * @throws ReferenciaIlegible si el texto no tiene esta forma. <b>No se ignora la linea</b>: un
     *     pago cuya obligacion no se puede leer es dinero cobrado que no se sabe contra que
     *     imputar, y saltarselo dejaria el recibo cobrando mas de lo que el libro abona sin que
     *     ninguna cifra lo dijera
     */
    public static ReferenciaDeObligacion leer(String texto) {
        Objects.requireNonNull(texto, "No hay referencia que leer");
        String[] partes = texto.split("\\|", -1);
        if (partes.length != 5 && partes.length != 6) {
            throw new ReferenciaIlegible(
                    texto,
                    "tiene "
                            + partes.length
                            + " partes y necesita 6 —o 5, la forma de antes de #431, sin deudor—");
        }
        // Las dos formas se distinguen por la cuenta, y solo por ella: la de cinco es la de una
        // orden emitida antes de #431, y su deudor lo pone quien imputa (el pagador del pago).
        boolean conDeudor = partes.length == 6;
        int unidad = conDeudor ? 3 : 2;
        try {
            if (conDeudor && partes[2].isEmpty()) {
                throw new IllegalArgumentException(
                        "la forma de seis partes lleva siempre al deudor en la tercera");
            }
            return new ReferenciaDeObligacion(
                    partes[0],
                    new Ejercicio(Integer.parseInt(partes[1])),
                    conDeudor ? Long.parseLong(partes[2]) : null,
                    partes[unidad].isEmpty() ? null : Long.parseLong(partes[unidad]),
                    partes[unidad + 1].isEmpty() ? null : Long.parseLong(partes[unidad + 1]),
                    LocalDate.parse(partes[unidad + 2]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malEscrita) {
            throw new ReferenciaIlegible(texto, malEscrita.getMessage());
        }
    }

    /**
     * La obligacion, sin la fecha ni el deudor: contra esto se imputa el pago.
     *
     * <p>Sin el deudor porque {@link SeleccionDeObligacion} es lo que la ventanilla marca y no lo
     * lleva; quien imputa lo empareja con el deudor de esta referencia —o con el pagador, si es de
     * cinco partes— antes de ir al libro (#431).
     */
    public SeleccionDeObligacion comoSeleccion() {
        return new SeleccionDeObligacion(tributo, ejercicio, predioId, vehiculoId);
    }

    /** El texto que vino en el pago no tiene la forma que este sistema compone. */
    public static final class ReferenciaIlegible extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ReferenciaIlegible(String texto, @Nullable String porQue) {
            super(
                    "La referencia '"
                            + texto
                            + "' no la compuso este sistema: "
                            + porQue
                            + ". Un pago cuya obligacion no se puede leer es dinero cobrado que no"
                            + " se sabe contra que imputar, asi que NO se ignora la linea: el pago"
                            + " entero se rechaza y alguien tiene que mirarlo");
        }
    }
}
