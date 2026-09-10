package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Una pasada entera del consumidor del buzon de {@code identidad}: vueltas hasta que una no
 * progrese, y UN aviso al final de lo que lleva demasiado tiempo pendiente.
 *
 * <h2>Por que esta aparte del runner (ADR-0039, etapa 5)</h2>
 *
 * <p>Hasta la etapa 4 este bucle vivia dentro de {@link CorrerElConsumidorDeIdentidad}, que ademas
 * resuelve de que municipalidad es el buzon y fija el contexto de tenant. Con la etapa 5 la pasada
 * deja de ser un adorno del final de la implantacion y pasa a ser <b>la unica fuente</b> del
 * administrador —el sembrador ya no lo escribe—, asi que {@link ImplantarMunicipalidad} tiene que
 * poder llamarla <b>en linea</b>: encadenarla por {@code @Order} no vale, porque sin {@code
 * KAMAYUK_IDENTIDAD_URL} el runner <b>no existe</b> y la implantacion saldria {@code Complete} con
 * la copia vacia — el {@code Job} roto de C-18 con otra cara.
 *
 * <p>Lo que esta clase NO hace es fijar el contexto de tenant: lo pone quien la llama, porque los
 * dos llamadores lo saben por caminos distintos —el runner lo resuelve de su cuenta de servicio y
 * la implantacion acaba de crear la municipalidad—.
 *
 * <h2>Acotada</h2>
 *
 * <p>Como mucho {@value #VUELTAS_MAXIMAS} paginas por pasada, y se para antes en cuanto una vuelta
 * no progresa. Un proceso que no acaba no es un consumidor: es un pod que nadie mira.
 *
 * <h2>Un pospuesto que no avanza SI se avisa, y NO tumba la pasada</h2>
 *
 * <p>Un evento que todavia no se puede aplicar no se acusa, asi que el buzon lo vuelve a servir y
 * la pasada siguiente lo intenta otra vez. Mientras su dependencia este en camino eso es lo
 * correcto y no es un fallo. Medido con las cinco aplicaciones levantadas (AC-5/AC-6 de
 * `identidad`#4), lo que pasa cuando NO esta en camino es que no le llega a nadie: cuatro permisos
 * quedaron pospuestos corrida tras corrida, con su WARN por vuelta y <b>cero avisos al
 * responsable</b>. Asi que los que llevan mas de {@value #MINUTOS_QUE_SE_TOLERAN} minutos esperando
 * se juntan en <b>un</b> aviso y la pasada <b>termina bien</b>: salir con codigo 1 cada cinco
 * minutos convertiria el {@code CronJob} en un `Job` que falla siempre y al que nadie mira.
 *
 * <p><b>Y por eso un pospuesto tampoco hace fallar a la implantacion</b>, que llama a esta misma
 * pasada: lo que la implantacion comprueba no son los pospuestos sino el <b>resultado</b> —que la
 * copia local haya quedado con alguna cuenta—, y esa separacion esta escrita donde se aplica, en
 * {@link ImplantarMunicipalidad}.
 */
public class PasadaDelConsumidorDeIdentidad {

    private static final Logger log = LoggerFactory.getLogger(PasadaDelConsumidorDeIdentidad.class);

    private static final int VUELTAS_MAXIMAS = 50;

    /**
     * Tres ticks del {@code CronJob}, que corre cada cinco minutos.
     *
     * <p>Un pospuesto normal —la afiliacion que llega en el mismo lote que su grupo, o en el
     * siguiente— se resuelve en la corrida siguiente: medido, en cuanto la dependencia llega los
     * cuatro consumidores se ponen al dia en UNA corrida. Tres ticks deja pasar el caso normal y un
     * reintento con `backoffLimit: 1`, y no deja pasar el caso que hay que atender: el que se
     * repite igual corrida tras corrida.
     */
    static final int MINUTOS_QUE_SE_TOLERAN = 15;

    static final Duration ANTIGUEDAD_QUE_SE_AVISA = Duration.ofMinutes(MINUTOS_QUE_SE_TOLERAN);

    private final ConsumirEventosDeIdentidad consumidor;
    private final AlertaDeEventosSinAplicar alerta;
    private final Clock reloj;

    public PasadaDelConsumidorDeIdentidad(
            ConsumirEventosDeIdentidad consumidor, AlertaDeEventosSinAplicar alerta, Clock reloj) {
        this.consumidor = consumidor;
        this.alerta = alerta;
        this.reloj = reloj;
    }

    /**
     * Da vueltas hasta que una no progrese, y avisa al final de lo que no avanza.
     *
     * @return cuantos eventos se aplicaron de verdad en toda la pasada
     */
    public int hastaAgotar() {
        // Por `eventoId` y no una lista: el buzon vuelve a servir el mismo evento en cada vuelta
        // mientras no se acuse, y avisar del mismo tres veces seria contar tres problemas donde
        // hay uno. Se queda el ultimo motivo, que es el de la vuelta mas reciente.
        Map<UUID, EventoPospuesto> pospuestos = new LinkedHashMap<>();
        int aplicados = 0;
        try {
            for (int vuelta = 1; vuelta <= VUELTAS_MAXIMAS; vuelta++) {
                ConsumirEventosDeIdentidad.Vuelta resultado = consumidor.consumir();
                aplicados += resultado.aplicados();
                for (EventoPospuesto pospuesto : resultado.pospuestos()) {
                    pospuestos.put(pospuesto.evento().eventoId(), pospuesto);
                }
                log.info("Vuelta {} del consumidor de identidad: {}", vuelta, resultado);
                if (resultado.sinProgreso()) {
                    return aplicados;
                }
            }
            log.warn(
                    "Se agotaron las {} vueltas y el buzon de `identidad` sigue teniendo eventos."
                            + " No es un fallo: la pasada acaba a proposito en vez de no acabar,"
                            + " y la siguiente sigue por donde esta se quedo",
                    VUELTAS_MAXIMAS);
            return aplicados;
        } finally {
            avisarSiNoAvanzan(pospuestos.values());
        }
    }

    /**
     * Avisa UNA vez, y solo de los que llevan esperando mas de {@link #ANTIGUEDAD_QUE_SE_AVISA}.
     *
     * <p>Va en el {@code finally} a proposito: un buzon que deja de contestar a mitad de pasada es
     * transitorio y sube —la corrida acaba en rojo—, pero lo que ya se sabia de los pospuestos se
     * sabe igual, y callarlo por eso seria perder el aviso justo el dia que hay dos cosas mal.
     */
    private void avisarSiNoAvanzan(Collection<EventoPospuesto> pospuestos) {
        Instant ahora = reloj.instant();
        List<EventoPospuesto> viejos = new ArrayList<>();
        for (EventoPospuesto pospuesto : pospuestos) {
            if (pospuesto.edad(ahora).compareTo(ANTIGUEDAD_QUE_SE_AVISA) > 0) {
                viejos.add(pospuesto);
            }
        }
        if (!viejos.isEmpty()) {
            alerta.hayPospuestosQueNoAvanzan(List.copyOf(viejos), ahora, ANTIGUEDAD_QUE_SE_AVISA);
        }
    }
}
