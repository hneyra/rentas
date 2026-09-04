package kamayuk.rentas.indicadores.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.ExpedientesSinRec;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.PrediosSinConciliar;
import kamayuk.rentas.sanciones.PapeletasSinNotificar;
import kamayuk.rentas.valores.ValoresSinNotificar;

/**
 * Los cuatro modulos que el trabajo parado consulta, en memoria (#549).
 *
 * <p>Un solo doble para los cuatro puertos, por lo mismo que {@code LibroDeMentira} sirve las dos
 * APIs de {@code cuentacorriente}: lo que hay que poder afirmar es que el panel <b>pregunta</b> y
 * no cuenta, y para eso basta con que los puertos existan y anoten lo que se les pidio.
 *
 * <p>Anota {@link #preguntados}, y esa es la mitad util del doble: el AC 2.3 no dice solo que el
 * frente sin permiso no salga en la respuesta, dice que <b>no se consulte</b>. Con un doble que
 * solo devolviera cifras, «no sale» y «sale y se descarta» serian indistinguibles.
 *
 * <p>Anota tambien la fecha y el ejercicio con que se le pregunto: si el panel pidiera «ahora» en
 * vez de la fecha de la peticion, o el anio del reloj en vez del ejercicio, las cifras seguirian
 * siendo plausibles.
 */
public final class ModulosDeMentira
        implements PapeletasSinNotificar,
                ValoresSinNotificar,
                ExpedientesSinRec,
                PrediosSinConciliar {

    private final List<String> preguntados = new ArrayList<>();

    private long papeletas;
    private Dinero importeDeLasPapeletas = Dinero.CERO;
    private long valores;
    private long expedientes;
    private long predios;

    private LocalDate fechaDeLosValores;
    private LocalDate fechaDeLosPredios;
    private Ejercicio ejercicioDeLosPredios;

    public ModulosDeMentira conPapeletas(long cuantas, String importe) {
        this.papeletas = cuantas;
        this.importeDeLasPapeletas = Dinero.de(importe);
        return this;
    }

    public ModulosDeMentira conValores(long cuantos) {
        this.valores = cuantos;
        return this;
    }

    public ModulosDeMentira conExpedientes(long cuantos) {
        this.expedientes = cuantos;
        return this;
    }

    public ModulosDeMentira conPredios(long cuantos) {
        this.predios = cuantos;
        return this;
    }

    /** Que puertos se llegaron a preguntar, en orden. */
    public List<String> preguntados() {
        return List.copyOf(preguntados);
    }

    public LocalDate fechaDeLosValores() {
        return fechaDeLosValores;
    }

    public LocalDate fechaDeLosPredios() {
        return fechaDeLosPredios;
    }

    public Ejercicio ejercicioDeLosPredios() {
        return ejercicioDeLosPredios;
    }

    @Override
    public PapeletasImpuestas sinNotificar() {
        preguntados.add("TRANSITO");
        return new PapeletasImpuestas(papeletas, importeDeLasPapeletas);
    }

    @Override
    public long cuantosA(LocalDate aLaFecha) {
        preguntados.add("VALORES");
        this.fechaDeLosValores = aLaFecha;
        return valores;
    }

    @Override
    public long cuantosSinRec1() {
        preguntados.add("COACTIVA");
        return expedientes;
    }

    @Override
    public long cuantosA(Ejercicio ejercicio, LocalDate aLaFecha) {
        preguntados.add("CATASTRO");
        this.ejercicioDeLosPredios = ejercicio;
        this.fechaDeLosPredios = aLaFecha;
        return predios;
    }
}
