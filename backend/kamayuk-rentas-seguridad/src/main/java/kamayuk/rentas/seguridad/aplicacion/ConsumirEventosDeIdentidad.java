package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.plataforma.PoliticaDeLoQueNoAvanza;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Una vuelta del consumidor del buzon de {@code identidad}: traer un lote, aplicar cada evento en
 * su transaccion, y acusar DESPUES lo que quedo resuelto (etapa 4 de ADR-0039, ADR-0028 §3).
 *
 * <p>Es la forma de {@code IngestarHechosDeCatastro}, con la diferencia que marca la etapa: aqui lo
 * que se consume es la autorizacion, asi que un evento que no se pueda aplicar nunca no se ignora
 * —se aparta, se acusa y se avisa— y uno que no se pueda aplicar todavia no se acusa.
 *
 * <p><b>El acuse va al final y solo con lo resuelto.</b> Acusar antes de confirmar la transaccion
 * perderia el evento cuyo commit falle (AC-7 #1): el buzon dejaria de servirlo y la copia local no
 * lo tendria. Acusar solo lo que se aplico, se ignoro por ajeno o se aparto es lo que hace que un
 * evento pendiente por su dependencia vuelva en la vuelta siguiente.
 *
 * <h2>Un pendiente no puede ocupar la cabeza PARA SIEMPRE (#377)</h2>
 *
 * <p>Hasta #377 un {@code TodaviaNo} no se acusaba nunca, y eso no tenia tope. {@code identidad}
 * sirve lo no acusado por orden y como mucho {@value #POR_VUELTA}: con 200 {@code PERMISO_FIJADO}
 * pospuestos en la cabeza —dos guardados de la matriz sobre un sujeto cuya alta se aparto, o un
 * acceso que el catalogo local no tiene— la pagina entera eran ellos, la pasada terminaba «sin
 * progreso» en verde y <b>el evento de detras no se leia nunca</b>. Medido: la baja de un empleado
 * cesado se quedaba sin aplicar y {@code GuardiaDeAcceso} lo seguia autorizando con la copia vieja.
 * Y {@code TodaviaNo} sale siempre que un {@code INSERT … SELECT} escribe 0 filas, que en esos dos
 * casos esperar no arregla.
 *
 * <p>Asi que un {@code TodaviaNo} le pregunta a la {@link PoliticaDeLoQueNoAvanza}: la de
 * produccion espera mientras el evento tenga como mucho {@code
 * PasadaDelConsumidorDeIdentidad.MINUTOS_QUE_SE_TOLERAN} y pasado eso lo <b>aparta a la cola de
 * muertos y lo acusa</b>. No se avisa aqui, evento a evento: los apartados por no avanzar vuelven
 * en la {@link Vuelta} y la pasada los junta en UN aviso.
 */
public class ConsumirEventosDeIdentidad {

    private static final Logger log = LoggerFactory.getLogger(ConsumirEventosDeIdentidad.class);

    public static final int POR_VUELTA = 200;

    private final FuenteDeEventosDeIdentidad fuente;
    private final AplicarUnEventoDeIdentidad aplicador;
    private final AlertaDeEventosSinAplicar alerta;
    private final PoliticaDeLoQueNoAvanza politica;
    private final Clock reloj;

    public ConsumirEventosDeIdentidad(
            FuenteDeEventosDeIdentidad fuente,
            AplicarUnEventoDeIdentidad aplicador,
            AlertaDeEventosSinAplicar alerta,
            PoliticaDeLoQueNoAvanza politica,
            Clock reloj) {
        this.fuente = fuente;
        this.aplicador = aplicador;
        this.alerta = alerta;
        this.politica = politica;
        this.reloj = reloj;
    }

    public Vuelta consumir() {
        Instant ahora = reloj.instant();
        FuenteDeEventosDeIdentidad.Lote lote = fuente.pendientes(POR_VUELTA);
        List<UUID> resueltos = new ArrayList<>();
        int aplicados = 0;
        int yaEstaban = 0;
        int ajenos = 0;
        int apartados = 0;
        List<EventoPospuesto> pospuestos = new ArrayList<>();
        List<EventoPospuesto> apartadosPorNoAvanzar = new ArrayList<>();

        for (EventoDeIdentidadRecibido evento : lote.eventos()) {
            try {
                AplicarUnEventoDeIdentidad.Aplicacion resultado = aplicador.aplicar(evento);
                switch (resultado) {
                    case APLICADO -> aplicados++;
                    case YA_APLICADO -> yaEstaban++;
                    // Se cuentan y se resumen al final de la vuelta, en UNA linea. Uno por
                    // evento era ruido medido: una implantacion entera son 161 permisos y 28 de
                    // ellos son de otros sistemas en `rentas` (494 lineas entre los cuatro
                    // consumidores), y un registro que grita en lo normal es un registro que
                    // nadie lee el dia que dice algo.
                    case IGNORADO_AJENO -> ajenos++;
                    // Checkstyle exige el `default` aunque el enumerado este cubierto: si algun
                    // dia gana un cuarto resultado, mejor que se note aqui que en el acuse.
                    default ->
                            throw new IllegalStateException(
                                    "Resultado de aplicacion sin contar: " + resultado);
                }
                resueltos.add(evento.eventoId());
            } catch (AplicarUnEventoDeIdentidad.NoSePuedeAplicar nunca) {
                String motivo = motivoDe(nunca);
                aplicador.apartar(evento, motivo);
                apartados++;
                // Se acusa igual: apartado y acusado deja de servirse, que es lo que impide que
                // bloquee la cola detras de el. Y se avisa a una persona: mientras este ahi,
                // alguien puede en `identidad` lo que aqui no puede.
                resueltos.add(evento.eventoId());
                alerta.hayUnEventoSinAplicar(evento, motivo, aplicador.apartados());
            } catch (AplicarUnEventoDeIdentidad.TodaviaNo todaviaNo) {
                PoliticaDeLoQueNoAvanza.Decision decision =
                        politica.decidir(
                                new PoliticaDeLoQueNoAvanza.LoQueNoAvanza(
                                        evento.tipoPublicado(),
                                        evento.creadoEn(),
                                        motivoDe(todaviaNo)),
                                ahora);
                if (decision.aparta()) {
                    // Lleva demasiado esperando: su dependencia ya no esta en camino, y
                    // esperandolo paraba todo lo de detras (#377). A la cola de muertos y SE
                    // ACUSA. El aviso lo da la pasada, UNO por corrida con todos juntos.
                    aplicador.apartar(evento, decision.motivo());
                    apartadosPorNoAvanzar.add(new EventoPospuesto(evento, decision.motivo()));
                    resueltos.add(evento.eventoId());
                    continue;
                }
                // NO se acusa y NO se aparta: su dependencia esta en camino. Un fallo que se
                // arregla solo no puede matar un evento (AC-7 #3), y la politica dice cuanto se
                // le consiente antes de dejar de creerlo.
                pospuestos.add(new EventoPospuesto(evento, motivoDe(todaviaNo)));
                log.warn(
                        "Evento {} ({}, secuencia {}) TODAVIA no se puede aplicar y se deja"
                                + " pendiente en el buzon de `identidad`: {}",
                        evento.eventoId(),
                        evento.tipoPublicado(),
                        evento.secuencia(),
                        todaviaNo.getMessage());
            }
        }

        if (ajenos > 0) {
            // UNA linea por vuelta, no una por evento: lo que hay que poder leer es cuantos
            // permisos de otros sistemas trajo este lote, no cual fue cada uno.
            log.warn(
                    "{} permiso(s) de OTROS sistemas ignorados y acusados en esta vuelta: no rigen"
                            + " en esta copia. No es un fallo: `identidad` publica los cinco"
                            + " catalogos por un solo buzon, y a `rentas` solo le tocan los suyos",
                    ajenos);
        }

        // Lo que queda en el buzon se cuenta DESPUES del acuse, y por eso sale de la respuesta
        // del acuse y no del lote: `lote.quedan()` es lo que habia cuando se sirvio, o sea ANTES
        // de que estos se acusaran — decia «174 acusados; quedan 174», que es exactamente lo
        // contrario de lo que la linea promete.
        long quedan = lote.quedan();
        boolean acuseRechazado = false;
        if (!resueltos.isEmpty()) {
            try {
                quedan = fuente.acusar(List.copyOf(resueltos)).quedan();
            } catch (FuenteDeEventosDeIdentidad.AcuseRechazado rechazo) {
                acuseRechazado = true;
                // Un 4xx de negocio al acusar: se registra y la corrida termina. Los eventos SI
                // estan resueltos aqui —se descartaran por deduplicacion cuando vuelvan—; lo que
                // no cuadra es lo que este consumidor y el buzon entienden por un acuse.
                log.error(
                        "`identidad` RECHAZO el acuse de {} evento(s) con {}: {}. Los eventos SI"
                                + " estan resueltos en esta copia; lo que no cuadra es lo que este"
                                + " consumidor y el buzon entienden por un acuse, y eso no cambia"
                                + " reintentando. La corrida termina aqui",
                        resueltos.size(),
                        rechazo.estado(),
                        rechazo.getMessage());
            }
        }
        return new Vuelta(
                lote.eventos().size(),
                aplicados,
                yaEstaban,
                ajenos,
                apartados,
                List.copyOf(pospuestos),
                List.copyOf(apartadosPorNoAvanzar),
                quedan,
                acuseRechazado);
    }

    private static String motivoDe(RuntimeException noSePudo) {
        String mensaje = noSePudo.getMessage();
        return mensaje == null ? noSePudo.getClass().getSimpleName() : mensaje;
    }

    /**
     * Lo que una vuelta hizo, para el registro y para decidir si se sigue.
     *
     * @param pospuestos los que la politica decidio seguir esperando: sin acusar
     * @param apartadosPorNoAvanzar los que la politica aparto por llevar demasiado esperando: en la
     *     cola de muertos y acusados (#377)
     * @param quedan lo pendiente en el buzon CONTANDO lo que esta vuelta no acuso, que es lo que
     *     {@code identidad} contesta
     */
    public record Vuelta(
            int leidos,
            int aplicados,
            int yaEstaban,
            int ajenos,
            int apartados,
            List<EventoPospuesto> pospuestos,
            List<EventoPospuesto> apartadosPorNoAvanzar,
            long quedan,
            boolean acuseRechazado) {

        /** Cuantos se dejaron para la vuelta siguiente. */
        public int pendientes() {
            return pospuestos.size();
        }

        /**
         * Vacia, al dia, o BLOQUEADA en su cabeza con lo que espera detras (#377).
         *
         * <p>{@code quedan} cuenta tambien los pospuestos de esta pagina —siguen pendientes en el
         * buzon—, asi que lo que hay DETRAS de ella es lo que queda menos ellos.
         */
        public EstadoDeLaCola estado() {
            long cabeza = pospuestos.isEmpty() ? -1 : pospuestos.getFirst().evento().secuencia();
            return EstadoDeLaCola.alTerminarLaVuelta(
                    leidos, leidos - pendientes(), Math.max(0, quedan - pendientes()), cabeza);
        }

        /**
         * Sin progreso, no «vacia»: un evento pendiente por su dependencia no se acusa, asi que el
         * emisor lo vuelve a servir, y dar vueltas hasta que el lote llegue vacio seria darlas
         * todas sobre los mismos eventos (la leccion de #54).
         */
        public boolean sinProgreso() {
            return leidos == 0 || leidos == pendientes() || acuseRechazado;
        }

        @Override
        public String toString() {
            return leidos
                    + " evento(s) leidos: "
                    + aplicados
                    + " aplicados, "
                    + yaEstaban
                    + " ya estaban, "
                    + ajenos
                    + " de otro sistema, "
                    + apartados
                    + " apartados sin poder aplicarse, "
                    + pendientes()
                    + " pendientes por su dependencia, "
                    + apartadosPorNoAvanzar.size()
                    + " apartados por llevar demasiado esperando; quedan "
                    + quedan
                    + " en el buzon de `identidad` DESPUES de acusar"
                    + (acuseRechazado ? "; y el acuse fue RECHAZADO" : "");
        }
    }
}
