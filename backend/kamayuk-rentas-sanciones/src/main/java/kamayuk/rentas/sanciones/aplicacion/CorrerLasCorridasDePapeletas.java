package kamayuk.rentas.sanciones.aplicacion;

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
 * El invocador de la generación masiva de valores por papeletas: el proceso batch que {@link
 * GenerarCorridaDeValores} decía tener y no tenía (#400, RF-066, RF-073).
 *
 * <h2>Lo que costaba no tenerlo</h2>
 *
 * <p>{@code POST /transito/valores/generacion-masiva} y su gemela administrativa contestaban 201
 * con la corrida y sus candidatos, y los candidatos se quedaban {@code PENDIENTE} para siempre. Y
 * este camino no es uno de varios: es el único que escribe {@code papeleta_masivo_item.valor_id},
 * así que ninguna papeleta podía recibir por aquí su resolución de multa, y las guardas que
 * dependen de él —la de {@code AnularPapeleta} de #267— no podían disparar nunca.
 *
 * <h2>La misma forma que su gemelo de {@code valores}</h2>
 *
 * <p>Es {@code valores.CorrerLasCorridasDeValores} con otra etapa detrás, y se replica en vez de
 * fusionarse: los dos módulos no comparten una abstracción de «corrida» y #400 no la inventa. Lo
 * que tienen en común está escrito dos veces a propósito, y cada uno tiene su prueba contra
 * PostgreSQL con la misma forma de siembra —dos municipalidades con candidatos que terminan
 * distinto y una tercera sin nada—:
 *
 * <ul>
 *   <li>un {@code ApplicationRunner} del perfil {@code batch}, que solo existe en el {@code
 *       CronJob} {@code kamayuk-rentas-corridas} ({@code @ConditionalOnProperty});
 *   <li>municipalidad por municipalidad (ADR-0020), con el registro de {@link
 *       RecorridoPorMunicipalidades#activas()} y <b>sin</b> {@link
 *       RecorridoPorMunicipalidades#recorrer}: ese metodo envuelve cada rama en una transacción, y
 *       {@link GenerarCorridaDeValores#generar} tiene que correr fuera de toda transacción para que
 *       cada papeleta vaya en la suya;
 *   <li>el contexto se limpia entre municipalidades pase lo que pase;
 *   <li>y sale distinto de cero, con {@link ExitCodeGenerator} y no lanzando, si una corrida no
 *       avanza ({@link GenerarCorridaDeValores.Informe#sinAvance()}): lanzar cortaría el arranque
 *       antes de que corriera su gemelo del mismo proceso.
 * </ul>
 *
 * <h2>Quién firma lo que emite</h2>
 *
 * <p>{@value #USUARIO_DEL_PROCESO}, con {@link Origen#deProceso}: quien ordenó la emisión consta en
 * la corrida, y su observación viaja a cada valor (regla 10).
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = CorrerLasCorridasDePapeletas.PROPIEDAD, havingValue = "true")
public class CorrerLasCorridasDePapeletas implements ApplicationRunner, ExitCodeGenerator {

    /**
     * Lo que el {@code CronJob} de las corridas pone, y nadie más.
     *
     * <p>La misma que la de {@code valores.CorrerLasCorridasDeValores}: los dos corren en el mismo
     * proceso, y una propiedad por módulo permitiría desplegar uno sin el otro sin que nada lo
     * dijera.
     */
    public static final String PROPIEDAD = "kamayuk.rentas.corridas.generar";

    /** Con qué nombre firma la auditoría lo que este proceso emite. */
    public static final String USUARIO_DEL_PROCESO = "corridas-de-papeletas";

    private static final Logger log = LoggerFactory.getLogger(CorrerLasCorridasDePapeletas.class);

    private final RecorridoPorMunicipalidades registro;
    private final ConsultaDeLaCorridaDeValores lectura;
    private final GenerarCorridaDeValores generar;

    /** Lo que la última pasada no pudo hacer avanzar. Vacío = el proceso sale con 0. */
    private List<String> sinAvance = List.of();

    public CorrerLasCorridasDePapeletas(
            RecorridoPorMunicipalidades registro,
            ConsultaDeLaCorridaDeValores lectura,
            GenerarCorridaDeValores generar) {
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
                    "{} corrida(s) masiva(s) de papeletas no avanzaron en esta pasada; el proceso"
                            + " sale distinto de cero: {}",
                    sinAvance.size(),
                    sinAvance);
        }
    }

    /** Lo que la última pasada no pudo hacer avanzar, en el orden del recorrido. */
    public List<String> sinAvance() {
        return sinAvance;
    }

    @Override
    public int getExitCode() {
        return sinAvance.isEmpty() ? 0 : 1;
    }

    // `IllegalCatch`: la misma decisión que su gemelo de `valores` y que
    // `RecorridoPorMunicipalidades.recorrer` (ADR-0020 §2). Lo que se atrapa es UNA CORRIDA, o
    // UNA MUNICIPALIDAD, que no avanzó: se registra con su tipo y pone el proceso en rojo, y las
    // de detrás siguen. `generar` puede lanzar un fallo de persistencia, una papeleta que no
    // existe o un plazo sin sellar, y la que no estuviera en la lista cortaría la pasada entera.
    @SuppressWarnings("checkstyle:IllegalCatch")
    private List<String> enLaMunicipalidad(Municipalidad municipalidad) {
        List<String> atascadas = new ArrayList<>();
        TenantContext.fijar(new MunicipalidadId(municipalidad.id()));
        try {
            for (long corridaId : lectura.corridasConPendientes()) {
                try {
                    GenerarCorridaDeValores.Informe informe = generar.generar(corridaId);
                    log.info("Municipalidad {}: {}", municipalidad.id(), informe);
                    if (informe.sinAvance()) {
                        atascadas.add(
                                describir(
                                        municipalidad, corridaId, "no resolvio ningun candidato"));
                    }
                } catch (RuntimeException fallo) {
                    log.warn(
                            "La corrida {} de papeletas de la municipalidad {} no se pudo generar",
                            corridaId,
                            municipalidad.id(),
                            fallo);
                    atascadas.add(
                            describir(municipalidad, corridaId, fallo.getClass().getSimpleName()));
                }
            }
        } catch (RuntimeException fallo) {
            log.warn(
                    "Las corridas de papeletas de la municipalidad {} ({}) no se pudieron leer",
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
            // SIEMPRE: sin esto la municipalidad siguiente leería con el contexto de esta.
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
