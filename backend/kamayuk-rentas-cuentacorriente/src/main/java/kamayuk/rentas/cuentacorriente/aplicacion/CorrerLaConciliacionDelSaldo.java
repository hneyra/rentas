package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.Divergencia;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades.Municipalidad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * La red de seguridad de ADR-0006, corriendo: concilia el saldo proyectado de cada contribuyente de
 * cada municipalidad contra su libro (#630).
 *
 * <h2>Lo que habia antes</h2>
 *
 * <p>{@link ReconstruirSaldo#conciliar} y {@link ReconstruirPadron#reconstruir} existian desde #23
 * —«el saldo es cache, no verdad; si discrepa del libro, el libro gana»— y su javadoc decia que los
 * correria «un proceso automatico de madrugada». Ese proceso no existia: ni una ruta, ni un {@code
 * ApplicationRunner}, ni un {@code CronJob}. Un saldo que se desviara del libro —una escritura que
 * tocara uno y no el otro, una migracion— se quedaba desviado para siempre, y el estado de cuenta y
 * el panel decian una cifra que el libro no respalda.
 *
 * <h2>La forma: la de {@code CorrerLasCorridasDeValores}</h2>
 *
 * <p>Un {@code ApplicationRunner} del perfil {@code batch} y no un {@code @Scheduled}, por lo que
 * {@code CorrerLaAntiEntropia} midio: no hay {@code @EnableScheduling} y el perfil {@code batch}
 * termina el proceso. Lo lanza el {@code CronJob} {@code kamayuk-rentas-conciliacion-del-saldo}, y
 * solo el: sin {@value #PROPIEDAD} no se registra, de modo que la implantacion y los otros {@code
 * CronJob} —la misma imagen en el mismo perfil— no concilian nada.
 *
 * <p>Recorre <b>todas</b> las municipalidades activas del registro (ADR-0020), fijando el contexto
 * de cada una antes de tocar su padron y limpiandolo despues pase lo que pase. Y no usa {@link
 * RecorridoPorMunicipalidades#recorrer}, por lo mismo que las corridas: ese metodo envuelve cada
 * rama en <b>una</b> transaccion, y aqui cada contribuyente abre la suya.
 *
 * <h2>Concilia y NO repara, salvo que se le pida</h2>
 *
 * <p>La pasada de cada noche <b>solo informa</b>: una linea de nivel ERROR por contribuyente que no
 * cuadra, con cada obligacion, lo que dice el libro y lo que dice la proyeccion. Es la separacion
 * que {@link Divergencia} defiende: una proyeccion que se autocorrige en silencio esconde el
 * defecto que la desalineo, y ese defecto la va a volver a desalinear. Quien lee el informe busca
 * al escritor que la desvio y <b>despues</b> repara, relanzando el mismo proceso con {@value
 * #RECONSTRUIR}{@code =true}: entonces reconstruye el padron de las municipalidades que no
 * cuadraron y vuelve a conciliarlo, y lo que siga sin cuadrar lo dice.
 *
 * <h2>Sale distinto de cero si algo queda sin cuadrar, y al reves que la anti-entropia</h2>
 *
 * <p>{@code CorrerLaAntiEntropia} sale con 0 aunque encuentre discrepancias, porque las suyas se
 * curan solas —las corrige el ingestor reprocesando— y un {@code CronJob} rojo cada dia que haya
 * una acabaria sin mirarse. Aqui no: una divergencia del saldo <b>no se cura sola</b>, porque la
 * proyeccion se mantiene en la misma transaccion que el asiento (ADR-0006) y nada la vuelve a
 * escribir hasta el proximo asiento de esa obligacion. Un {@code CronJob} en verde con el estado de
 * cuenta diciendo una cifra que el libro no respalda es la peor combinacion posible (#377). Asi que
 * sale con 1 mientras quede algo sin cuadrar, o una municipalidad sin conciliar, y vuelve a 0
 * cuando se repara. Con {@link ExitCodeGenerator} y no lanzando, como las corridas: una excepcion
 * aqui cortaria el arranque de cualquier otro runner que compartiera el proceso.
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = CorrerLaConciliacionDelSaldo.PROPIEDAD, havingValue = "true")
public class CorrerLaConciliacionDelSaldo implements ApplicationRunner, ExitCodeGenerator {

    /** Lo que el {@code CronJob} de la conciliacion pone, y nadie mas. */
    public static final String PROPIEDAD = "kamayuk.rentas.saldos.conciliar";

    /** Lo que se pone a mano, despues de leer el informe, para que ademas repare. */
    public static final String RECONSTRUIR = "kamayuk.rentas.saldos.reconstruir";

    private static final Logger log = LoggerFactory.getLogger(CorrerLaConciliacionDelSaldo.class);

    private final RecorridoPorMunicipalidades registro;
    private final ReconstruirPadron padron;
    private final boolean reconstruirLoQueDiverge;

    /** Una linea por contribuyente que no cuadraba al conciliar, en el orden del recorrido. */
    private List<String> informe = List.of();

    /** Lo que al terminar la pasada sigue sin cuadrar, o sin conciliar. Vacio = sale con 0. */
    private List<String> sinCuadrar = List.of();

    public CorrerLaConciliacionDelSaldo(
            RecorridoPorMunicipalidades registro,
            ReconstruirPadron padron,
            @Value("${" + RECONSTRUIR + ":false}") boolean reconstruirLoQueDiverge) {
        this.registro = registro;
        this.padron = padron;
        this.reconstruirLoQueDiverge = reconstruirLoQueDiverge;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        List<String> divergentes = new ArrayList<>();
        List<String> pendientes = new ArrayList<>();
        for (Municipalidad municipalidad : registro.activas()) {
            enLaMunicipalidad(municipalidad, divergentes, pendientes);
        }
        informe = List.copyOf(divergentes);
        sinCuadrar = List.copyOf(pendientes);
        if (!sinCuadrar.isEmpty()) {
            log.error(
                    "{} saldo(s) proyectado(s) siguen sin cuadrar con el libro, o sin conciliar;"
                            + " el proceso sale distinto de cero{}",
                    sinCuadrar.size(),
                    reconstruirLoQueDiverge
                            ? ""
                            : ". La conciliacion no repara: se repara relanzandolo con "
                                    + RECONSTRUIR
                                    + "=true, despues de buscar que escritura los desvio");
        }
    }

    /** Una linea por contribuyente que no cuadraba al conciliar, en el orden del recorrido. */
    public List<String> informe() {
        return informe;
    }

    /** Lo que la ultima pasada dejo sin cuadrar o sin conciliar. */
    public List<String> sinCuadrar() {
        return sinCuadrar;
    }

    @Override
    public int getExitCode() {
        return sinCuadrar.isEmpty() ? 0 : 1;
    }

    // `IllegalCatch` prohibe atrapar RuntimeException, y aqui es la decision de ADR-0020 §2 —la
    // de `RecorridoPorMunicipalidades.recorrer` y la de las corridas—: lo que se atrapa no es «un
    // error» sino UNA MUNICIPALIDAD que no se pudo conciliar. No se traga: se registra con su tipo
    // y pone el proceso en rojo, y las municipalidades de detras se concilian igual.
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void enLaMunicipalidad(
            Municipalidad municipalidad, List<String> divergentes, List<String> pendientes) {
        TenantContext.fijar(new MunicipalidadId(municipalidad.id()));
        try {
            ReconstruirPadron.Conciliacion antes = padron.conciliar();
            if (antes.cuadra()) {
                log.info(
                        "Municipalidad {}: {} contribuyente(s) conciliado(s); el saldo proyectado"
                                + " cuadra con el libro",
                        municipalidad.id(),
                        antes.contribuyentes());
                return;
            }
            List<String> lineas = lineasDe(municipalidad, antes);
            lineas.forEach(linea -> log.error("{}", linea));
            divergentes.addAll(lineas);

            if (!reconstruirLoQueDiverge) {
                pendientes.addAll(lineas);
                return;
            }
            long ultimo = padron.reconstruir(0L);
            ReconstruirPadron.Conciliacion despues = padron.conciliar();
            log.warn(
                    "Municipalidad {}: padron reconstruido desde el libro hasta el contribuyente {};"
                            + " quedan {} contribuyente(s) sin cuadrar",
                    municipalidad.id(),
                    ultimo,
                    despues.divergencias().size());
            for (String linea : lineasDe(municipalidad, despues)) {
                // Lo que ni reconstruyendo cuadra es otra cosa —una fila de una obligacion que el
                // libro no tiene, que la reconstruccion no reescribe—, y se dice aparte.
                String sigue = "sigue sin cuadrar tras reconstruir: " + linea;
                log.error("{}", sigue);
                pendientes.add(sigue);
            }
        } catch (RuntimeException fallo) {
            log.warn(
                    "El saldo proyectado de la municipalidad {} ({}) no se pudo conciliar",
                    municipalidad.id(),
                    municipalidad.nombre(),
                    fallo);
            pendientes.add(
                    "municipalidad "
                            + municipalidad.id()
                            + ": no se pudo conciliar ("
                            + fallo.getClass().getSimpleName()
                            + ")");
        } finally {
            // SIEMPRE. Sin esto, la municipalidad siguiente leeria con el contexto de esta:
            // datos reales bajo otra etiqueta.
            TenantContext.limpiar();
        }
    }

    private static List<String> lineasDe(
            Municipalidad municipalidad, ReconstruirPadron.Conciliacion conciliacion) {
        List<String> lineas = new ArrayList<>();
        for (Map.Entry<Long, List<Divergencia>> suyas : conciliacion.divergencias().entrySet()) {
            lineas.add(
                    "municipalidad "
                            + municipalidad.id()
                            + ", contribuyente "
                            + suyas.getKey()
                            + ": el saldo proyectado no cuadra con el libro: "
                            + suyas.getValue());
        }
        return lineas;
    }
}
