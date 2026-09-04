package kamayuk.rentas.tesoreria.pagos;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Como {@code rentas} nombra una obligacion cuando se la manda a la caja (P5D, ADR-0026 §1).
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
 * <p>{@code TRIBUTO|EJERCICIO|PREDIO|VEHICULO}, con los dos ultimos vacios cuando no hay unidad. La
 * barra vertical y no la coma ni los dos puntos: un tributo no la lleva nunca, y el separador tiene
 * que ser algo que ningun componente pueda contener — es la misma decision que #428 tomo con el
 * numero de la notificacion administrativa.
 *
 * <p><b>No lleva el contribuyente</b>, y es deliberado: el pagador viaja aparte en la orden, y una
 * obligacion identificada por su deudor haria imposible que un tercero pague la deuda de otro, que
 * es legitimo y corriente en ventanilla.
 */
public record ReferenciaDeObligacion(
        String tributo, Ejercicio ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

    private static final String SEPARADOR = "|";

    public ReferenciaDeObligacion {
        Objects.requireNonNull(tributo, "La referencia necesita su tributo");
        Objects.requireNonNull(ejercicio, "La referencia necesita su ejercicio");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty() || tributo.contains(SEPARADOR)) {
            throw new IllegalArgumentException(
                    "El tributo no puede estar vacio ni contener el separador: '" + tributo + "'");
        }
        if (predioId != null && vehiculoId != null) {
            throw new IllegalArgumentException(
                    "Una obligacion es de un predio o de un vehiculo, no de los dos");
        }
    }

    public static ReferenciaDeObligacion de(SeleccionDeObligacion obligacion) {
        return new ReferenciaDeObligacion(
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId());
    }

    /** Lo que viaja a la caja. */
    public String texto() {
        return tributo
                + SEPARADOR
                + ejercicio.valor()
                + SEPARADOR
                + (predioId == null ? "" : predioId)
                + SEPARADOR
                + (vehiculoId == null ? "" : vehiculoId);
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
        if (partes.length != 4) {
            throw new ReferenciaIlegible(texto, "tiene " + partes.length + " partes y necesita 4");
        }
        try {
            return new ReferenciaDeObligacion(
                    partes[0],
                    new Ejercicio(Integer.parseInt(partes[1])),
                    partes[2].isEmpty() ? null : Long.parseLong(partes[2]),
                    partes[3].isEmpty() ? null : Long.parseLong(partes[3]));
        } catch (IllegalArgumentException malEscrita) {
            throw new ReferenciaIlegible(texto, malEscrita.getMessage());
        }
    }

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
