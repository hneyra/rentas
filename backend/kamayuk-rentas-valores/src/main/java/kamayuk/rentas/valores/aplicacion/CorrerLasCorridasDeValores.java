package kamayuk.rentas.valores.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades.Municipalidad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * El invocador de la generacion masiva de valores: el proceso batch que {@link
 * GenerarCorridaMasiva} decia tener y no tenia (#400, RF-091).
 *
 * <h2>Lo que habia antes</h2>
 *
 * <p>{@code POST /api/v1/valores/masivo} registraba la etapa «criterio» y contestaba 201, y el
 * javadoc de la etapa siguiente decia «este servicio es el que invoca el proceso batch». Ninguno de
 * los ocho {@code ApplicationRunner} del arbol tocaba una corrida, ningun {@code @Scheduled} ni
 * {@code @EventListener} la disparaba y el descriptor no declaraba ningun {@code CronJob} para
 * ella: los candidatos se quedaban {@code PENDIENTE} para siempre, sin consumir un correlativo, y
 * quien operaba veia un exito.
 *
 * <h2>La forma: la de {@code CorrerElIngestor}</h2>
 *
 * <p>Un {@code ApplicationRunner} del perfil {@code batch} y no un {@code @Scheduled}, por lo que
 * {@code CorrerElIngestor} ya midio: en este sistema no hay {@code @EnableScheduling} y el perfil
 * {@code batch} termina el proceso. Lo lanza el {@code CronJob} {@code kamayuk-rentas-corridas} del
 * descriptor, en la ventana de lote, y solo el: {@code @ConditionalOnProperty} hace que el {@code
 * Job} de implantacion y los otros dos {@code CronJob} —que corren la misma imagen en el mismo
 * perfil— no generen ni una corrida.
 *
 * <h2>Municipalidad por municipalidad (ADR-0020)</h2>
 *
 * <p>Lee el registro con {@link RecorridoPorMunicipalidades#activas()} y fija el contexto de cada
 * una antes de preguntar por sus corridas. No usa {@link RecorridoPorMunicipalidades#recorrer}, y
 * es a proposito: ese metodo envuelve cada rama en <b>una</b> transaccion, y {@link
 * GenerarCorridaMasiva#generar} tiene que correr <b>fuera</b> de toda transaccion para que cada
 * candidato vaya en la suya. Lo que si se copia de el son sus reglas: el contexto se limpia entre
 * municipalidades pase lo que pase, y una municipalidad que falla no detiene a las demas.
 *
 * <h2>Sale distinto de cero si una corrida no avanza</h2>
 *
 * <p>Con {@link ExitCodeGenerator} y no lanzando: este runner comparte proceso con su gemelo de
 * {@code sanciones}, {@code CorrerLasCorridasDePapeletas}, y una excepcion aqui cortaria el
 * arranque antes de que el otro corriera — las papeletas se quedarian sin generar por culpa de una
 * orden de pago. Asi los dos terminan su pasada entera, y {@code KamayukAplicacion} sale con el
 * codigo que cualquiera de los dos devuelva ({@code SpringApplication.exit}).
 *
 * <p>«No avanza» es {@link GenerarCorridaMasiva.Informe#sinAvance()}: la pasada no resolvio a nadie
 * y alguien fallo, o {@code generar} lanzo. Un candidato que falla junto a otros que se resuelven
 * no pone el {@code CronJob} en rojo la primera vez; si en la ventana siguiente es lo unico que
 * queda y vuelve a fallar, si. Salir con 0 dejaria el {@code CronJob} en verde con la corrida
 * parada, que es la peor combinacion posible (#377).
 *
 * <h2>Quien firma lo que emite</h2>
 *
 * <p>{@value #USUARIO_DEL_PROCESO}, con {@link Origen#deProceso}: el proceso no atiende a nadie y
 * no puede atribuir el acto a una persona que no lo esta haciendo. Quien ordeno la emision consta
 * en la corrida ({@code valor_masivo.usuario_registro}), y la observacion que escribio viaja a cada
 * valor (regla 10).
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = CorrerLasCorridasDeValores.PROPIEDAD, havingValue = "true")
public class CorrerLasCorridasDeValores implements ApplicationRunner, ExitCodeGenerator {

    /** Lo que el {@code CronJob} de las corridas pone, y nadie mas. */
    public static final String PROPIEDAD = "kamayuk.rentas.corridas.generar";

    /** Con que nombre firma la auditoria lo que este proceso emite. */
    public static final String USUARIO_DEL_PROCESO = "corridas-de-valores";

    private static final Logger log = LoggerFactory.getLogger(CorrerLasCorridasDeValores.class);

    private final RecorridoPorMunicipalidades registro;
    private final ConsultaDeLaCorridaMasiva lectura;
    private final GenerarCorridaMasiva generar;

    /** Lo que la ultima pasada no pudo hacer avanzar. Vacio = el proceso sale con 0. */
    private List<String> sinAvance = List.of();

    public CorrerLasCorridasDeValores(
            RecorridoPorMunicipalidades registro,
            ConsultaDeLaCorridaMasiva lectura,
            GenerarCorridaMasiva generar) {
        this.registro = registro;
        this.lectura = lectura;
        this.generar = generar;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        List<String> atascadas = new ArrayList<>();
        OrigenContext.fijar(Origen.deProceso(USUARIO_DEL_PROCESO));
        try {
            for (Municipalidad municipalidad : registro.activas()) {
                atascadas.addAll(enLaMunicipalidad(municipalidad));
            }
        } finally {
            OrigenContext.limpiar();
        }
        sinAvance = List.copyOf(atascadas);
        if (!sinAvance.isEmpty()) {
            log.error(
                    "{} corrida(s) masiva(s) de valores no avanzaron en esta pasada; el proceso"
                            + " sale distinto de cero: {}",
                    sinAvance.size(),
                    sinAvance);
        }
    }

    /** Lo que la ultima pasada no pudo hacer avanzar, en el orden del recorrido. */
    public List<String> sinAvance() {
        return sinAvance;
    }

    @Override
    public int getExitCode() {
        return sinAvance.isEmpty() ? 0 : 1;
    }

    // `IllegalCatch` prohibe atrapar RuntimeException, y aqui es la decision de ADR-0020 §2
    // —la misma de `RecorridoPorMunicipalidades.recorrer`—: lo que se atrapa no es «un error»
    // sino UNA CORRIDA, o UNA MUNICIPALIDAD, que no avanzo. No se traga: se registra con su
    // tipo y pone el proceso en rojo. Estrecharlo a un tipo seria peor: `generar` puede lanzar
    // un fallo de persistencia, una `YaFormalizada` o una `EjercicioSinSellar`, y la que no
    // estuviera en la lista cortaria la pasada de todas las municipalidades de detras.
    @SuppressWarnings("checkstyle:IllegalCatch")
    private List<String> enLaMunicipalidad(Municipalidad municipalidad) {
        List<String> atascadas = new ArrayList<>();
        TenantContext.fijar(new MunicipalidadId(municipalidad.id()));
        try {
            for (long corridaId : lectura.corridasConPendientes()) {
                try {
                    GenerarCorridaMasiva.Informe informe = generar.generar(corridaId);
                    log.info("Municipalidad {}: {}", municipalidad.id(), informe);
                    if (informe.sinAvance()) {
                        atascadas.add(
                                describir(
                                        municipalidad, corridaId, "no resolvio ningun candidato"));
                    }
                } catch (RuntimeException fallo) {
                    log.warn(
                            "La corrida {} de la municipalidad {} no se pudo generar",
                            corridaId,
                            municipalidad.id(),
                            fallo);
                    atascadas.add(
                            describir(municipalidad, corridaId, fallo.getClass().getSimpleName()));
                }
            }
        } catch (RuntimeException fallo) {
            // No se pudieron ni leer sus corridas: la municipalidad entera se queda sin avanzar.
            log.warn(
                    "Las corridas de la municipalidad {} ({}) no se pudieron leer",
                    municipalidad.id(),
                    municipalidad.nombre(),
                    fallo);
            atascadas.add(
                    "municipalidad "
                            + municipalidad.id()
                            + ": sus corridas no se pudieron leer ("
                            + fallo.getClass().getSimpleName()
                            + ")");
        } finally {
            // SIEMPRE. Sin esto, la municipalidad siguiente leeria con el contexto de esta:
            // datos reales bajo otra etiqueta.
            TenantContext.limpiar();
        }
        return atascadas;
    }

    private static String describir(Municipalidad municipalidad, long corridaId, String motivo) {
        return "municipalidad "
                + municipalidad.id()
                + ", corrida "
                + corridaId
                + " ("
                + motivo
                + ")";
    }
}
