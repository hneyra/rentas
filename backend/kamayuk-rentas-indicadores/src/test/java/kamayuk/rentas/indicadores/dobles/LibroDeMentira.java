package kamayuk.rentas.indicadores.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CargoDeUnTributo;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.PendienteDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudacionDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Un libro en memoria que sirve las dos APIs publicas de {@code cuentacorriente} que el panel usa.
 *
 * <p>Deja escritos los argumentos con que se le pregunto —{@link #desde}, {@link #hasta}, {@link
 * #ejercicioPedido}—, porque una de las cosas que hay que verificar del panel es <b>que periodo</b>
 * pide: si pidiera el ano del reloj en vez del ejercicio de la peticion, las cifras seguirian
 * siendo plausibles.
 *
 * <p>Que este doble implemente los <b>puertos publicos</b> y no los repositorios es lo que hace que
 * esta prueba se pueda escribir sin base de datos. Lo que ejercita el SQL de verdad vive en {@code
 * cuentacorriente}, contra PostgreSQL.
 */
public final class LibroDeMentira implements RecaudacionDelLibro, CarteraDelLibro {

    private final List<RecaudacionDeUnTributo> recaudado = new ArrayList<>();
    private final List<CargoDeUnTributo> cargado = new ArrayList<>();
    private final List<PendienteDeUnTributo> pendiente = new ArrayList<>();

    private LocalDate desde;
    private LocalDate hasta;
    private Ejercicio ejercicioPedido;
    private LocalDate fechaDeCorteDeLaCartera;

    public LibroDeMentira conRecaudado(
            String tributo, Ejercicio ejercicio, int mes, String importe, long abonos) {
        recaudado.add(
                new RecaudacionDeUnTributo(
                        tributo, ejercicio, mes, "ORDINARIA", Dinero.de(importe), abonos));
        return this;
    }

    public LibroDeMentira conCargado(String tributo, String importe, long cargos) {
        cargado.add(new CargoDeUnTributo(tributo, Dinero.de(importe), cargos));
        return this;
    }

    public LibroDeMentira conPendiente(String tributo, String importe, long obligaciones) {
        pendiente.add(new PendienteDeUnTributo(tributo, Dinero.de(importe), obligaciones));
        return this;
    }

    public LocalDate desde() {
        return desde;
    }

    public LocalDate hasta() {
        return hasta;
    }

    public Ejercicio ejercicioPedido() {
        return ejercicioPedido;
    }

    /**
     * La fecha de corte con que se pidio la cartera.
     *
     * <p>Se anota porque desde #639 esa fecha <b>decide la cifra</b>: la cartera es el insoluto
     * pendiente hasta ella. Si el panel pasara otra —el ano del reloj, o el 31 de diciembre—, el
     * total seguiria siendo plausible y estaria contando la cuota que aun no vence.
     */
    public LocalDate fechaDeCorteDeLaCartera() {
        return fechaDeCorteDeLaCartera;
    }

    @Override
    public RecaudadoEnElLibro recaudadoPor(
            Collection<String> tributos, LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
        throw new UnsupportedOperationException(
                "El panel pregunta por todos los tributos, no por una lista");
    }

    @Override
    public RecaudadoEnElLibro recaudadoDeTodos(
            LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
        this.desde = desde;
        this.hasta = hasta;
        return new RecaudadoEnElLibro(recaudado, desde, hasta, aLaFecha);
    }

    @Override
    public CargadoEnElLibro cargadoPorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
        this.ejercicioPedido = ejercicio;
        return new CargadoEnElLibro(cargado, ejercicio, aLaFecha);
    }

    @Override
    public CarteraPendiente pendientePorTributo(Ejercicio ejercicio, LocalDate aLaFecha) {
        this.ejercicioPedido = ejercicio;
        this.fechaDeCorteDeLaCartera = aLaFecha;
        return new CarteraPendiente(pendiente, ejercicio, aLaFecha);
    }
}
