package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * La anti-entropia como trabajo programado (P6, punto 4).
 *
 * <h2>Por que un runner del perfil {@code batch} y no un {@code @Scheduled}</h2>
 *
 * <p>Se midio antes de elegir, y {@code @Scheduled} <b>no correria</b>: en los cuatro backends no
 * hay <b>ni un</b> {@code @EnableScheduling} —Spring Boot no lo activa por autoconfiguracion, asi
 * que el unico {@code @Scheduled} del sistema, el publicador del buzon de {@code caja}, tampoco se
 * registra—, y el perfil {@code batch} <b>termina el proceso</b> tras los runners ({@code
 * SgtmAplicacion} llama a {@code System.exit}) con {@code web-application-type: none}: un proceso
 * que sale no puede sostener un temporizador.
 *
 * <p>Asi que se hace como todo lo demas que corre por lotes en este sistema —la implantacion, la
 * publicacion de parametros, la carga del catalogo vial—: un {@code ApplicationRunner} del perfil
 * {@code batch}, que un {@code CronJob} invoca. «Diaria, y siempre antes de una emision» son dos
 * invocaciones del mismo proceso, y quien las ordena es el planificador del cluster, no una
 * anotacion dentro del que atiende la ventanilla.
 *
 * <p><b>El {@code CronJob} no esta desplegado</b>, y hay que decirlo: {@code infra/} despliega hoy
 * un solo sistema —el monolito— y ninguno de los cuatro del corte tiene manifiesto. Lo que queda
 * construido es el proceso y su forma de invocarse; su horario es de P7.
 *
 * <h2>Que hace, y que no</h2>
 *
 * <p>Compara y <b>escribe el informe en el registro</b>. No corrige nada, y eso es deliberado: una
 * anti-entropia que reparara sola escribiria la proyeccion desde una comparacion, y entonces la
 * fila dejaria de poder decir que evento la escribio —que es lo unico que `V9` existe para
 * garantizar (P5E §8)—. Lo que corrige la proyeccion es el ingestor, reprocesando; esto dice donde
 * mirar.
 *
 * <p>Y <b>no falla</b> cuando encuentra discrepancias: sale con codigo 0 y las registra en nivel de
 * aviso. Un proceso que fallara dejaria el {@code CronJob} en rojo todos los dias que hubiera una,
 * y un trabajo que siempre esta rojo deja de mirarse — que es exactamente el modo de fallo que esta
 * comprobacion existe para no tener.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.anti-entropia.municipalidad")
public class CorrerLaAntiEntropia implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorrerLaAntiEntropia.class);

    private final ConciliarConElPadron conciliar;
    private final Clock reloj;
    private final long municipalidadId;

    public CorrerLaAntiEntropia(
            ConciliarConElPadron conciliar,
            Clock reloj,
            @Value("${sgtm.anti-entropia.municipalidad}") long municipalidadId) {
        this.conciliar = conciliar;
        this.reloj = reloj;
        this.municipalidadId = municipalidadId;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        // La fecha sale del reloj INYECTADO y se pasa como argumento (regla 9): el informe tiene
        // que poder volver a leerse dentro de un mes y decir de que dia era.
        LocalDate aLaFecha = LocalDate.now(reloj);

        TenantContext.fijar(new MunicipalidadId(municipalidadId));
        try {
            AntiEntropia.Informe informe = conciliar.conciliar(aLaFecha);
            if (informe.cuadra()) {
                log.info("{}", informe.comoTexto());
            } else {
                // Nivel de aviso y no de error: no es un fallo del servidor, es un hallazgo. Lo
                // que separa uno de otro en este sistema es si deja incidencia (#486, #540).
                log.warn("{}", informe.comoTexto());
            }
        } finally {
            TenantContext.limpiar();
        }
    }
}
