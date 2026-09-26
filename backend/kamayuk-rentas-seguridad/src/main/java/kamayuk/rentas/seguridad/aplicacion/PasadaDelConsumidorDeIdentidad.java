package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.plataforma.PoliticaDeLoQueNoAvanza;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
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
 * <h2>Un pospuesto que no avanza SE APARTA y se avisa, y NO tumba la pasada</h2>
 *
 * <p>Un evento que todavia no se puede aplicar no se acusa, asi que el buzon lo vuelve a servir y
 * la pasada siguiente lo intenta otra vez. Mientras su dependencia este en camino eso es lo
 * correcto y no es un fallo. Medido con las cinco aplicaciones levantadas (AC-5/AC-6 de
 * `identidad`#4), lo que pasa cuando NO esta en camino es que no le llega a nadie: cuatro permisos
 * quedaron pospuestos corrida tras corrida, con su WARN por vuelta y <b>cero avisos al
 * responsable</b>. Desde entonces los que llevaban mas de {@value #MINUTOS_QUE_SE_TOLERAN} minutos
 * esperando se juntaban en <b>un</b> aviso — <b>pero se quedaban en el buzon</b>, y #377 midio lo
 * que eso costaba: con 200 de ellos en la cabeza, la pagina entera eran ellos y lo de detras no se
 * leia nunca, una baja incluida. Asi que ahora {@link #POLITICA} los <b>aparta a la cola de muertos
 * y los acusa</b> en la vuelta misma, y el aviso de siempre dice cuales se apartaron.
 *
 * <p>La pasada <b>termina bien</b> igual: salir con codigo 1 cada cinco minutos convertiria el
 * {@code CronJob} en un `Job` que falla siempre y al que nadie mira. Y si la cabeza llega a estar
 * {@link EstadoDeLaCola.Bloqueada BLOQUEADA} —la pagina entera esperando, con mas detras, antes de
 * que pasen los {@value #MINUTOS_QUE_SE_TOLERAN} minutos— se avisa «COLA BLOQUEADA» con cuantos
 * esperan detras: es lo que el aviso de los pospuestos no decia.
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

    /** Pasada esta antiguedad, un pospuesto se aparta y se avisa (#377). */
    static final Duration ANTIGUEDAD_QUE_SE_AVISA = Duration.ofMinutes(MINUTOS_QUE_SE_TOLERAN);

    /**
     * La politica de produccion del consumidor: se espera un {@code TodaviaNo} hasta {@link
     * #ANTIGUEDAD_QUE_SE_AVISA} y pasado eso se aparta (#377).
     */
    public static final PoliticaDeLoQueNoAvanza POLITICA =
            PoliticaDeLoQueNoAvanza.apartarPasadoDe(ANTIGUEDAD_QUE_SE_AVISA);

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
     * Da vueltas hasta que una no progrese, y avisa al final de lo que no avanzaba.
     *
     * @return cuantos eventos se aplicaron de verdad en toda la pasada
     */
    public int hastaAgotar() {
        // Por `eventoId` y no una lista: si un acuse se pierde, el buzon vuelve a servir el mismo
        // evento, y avisar del mismo dos veces seria contar dos problemas donde hay uno.
        Map<UUID, EventoPospuesto> apartados = new LinkedHashMap<>();
        int aplicados = 0;
        try {
            for (int vuelta = 1; vuelta <= VUELTAS_MAXIMAS; vuelta++) {
                ConsumirEventosDeIdentidad.Vuelta resultado = consumidor.consumir();
                aplicados += resultado.aplicados();
                for (EventoPospuesto apartado : resultado.apartadosPorNoAvanzar()) {
                    apartados.put(apartado.evento().eventoId(), apartado);
                }
                log.info("Vuelta {} del consumidor de identidad: {}", vuelta, resultado);
                if (resultado.sinProgreso()) {
                    // SIN PROGRESO no es «nada que hacer» (#377): si lo que espera llena la pagina
                    // y hay mas detras, lo de detras —una baja, una revocacion— no se lee en esta
                    // corrida. Se dice, a una persona y con cuantos esperan.
                    if (resultado.estado() instanceof EstadoDeLaCola.Bloqueada bloqueada) {
                        alerta.laColaEstaBloqueada(
                                bloqueada, resultado.pospuestos(), ANTIGUEDAD_QUE_SE_AVISA);
                    }
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
            avisarDeLosApartados(apartados.values());
        }
    }

    /**
     * La pasada no pudo leer el buzon y quien la llamo decidio que la copia se queda como estaba
     * (#453): se le dice al responsable por el mismo canal que los apartados.
     *
     * <p>Lo decide {@link ImplantarMunicipalidad} y no esta clase, porque la decision depende de
     * algo que la pasada no mira —si la copia tiene cuentas—; lo que es de aqui es el canal, que es
     * el mismo por el que ya salen los otros avisos de la copia local.
     */
    public void avisarQueLaCopiaSeQuedaComoEstaba(
            FuenteDeEventosDeIdentidad.IdentidadNoContesta causa, long cuentas) {
        alerta.laCopiaSeQuedaComoEstaba(String.valueOf(causa.getMessage()), cuentas);
    }

    /**
     * Avisa UNA vez de los que se apartaron por llevar mas de {@link #ANTIGUEDAD_QUE_SE_AVISA}
     * esperando.
     *
     * <p>Va en el {@code finally} a proposito: un buzon que deja de contestar a mitad de pasada es
     * transitorio y sube —la corrida acaba en rojo—, pero lo que ya se aparto esta apartado igual,
     * y callarlo por eso seria perder el aviso justo el dia que hay dos cosas mal.
     */
    private void avisarDeLosApartados(Collection<EventoPospuesto> apartados) {
        if (!apartados.isEmpty()) {
            alerta.hayPospuestosQueNoAvanzan(
                    List.copyOf(apartados), reloj.instant(), ANTIGUEDAD_QUE_SE_AVISA);
        }
    }
}
