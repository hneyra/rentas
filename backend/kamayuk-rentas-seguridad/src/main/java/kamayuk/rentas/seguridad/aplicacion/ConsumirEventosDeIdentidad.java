package kamayuk.rentas.seguridad.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
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
 */
public class ConsumirEventosDeIdentidad {

    private static final Logger log = LoggerFactory.getLogger(ConsumirEventosDeIdentidad.class);

    public static final int POR_VUELTA = 200;

    private final FuenteDeEventosDeIdentidad fuente;
    private final AplicarUnEventoDeIdentidad aplicador;
    private final AlertaDeEventosSinAplicar alerta;

    public ConsumirEventosDeIdentidad(
            FuenteDeEventosDeIdentidad fuente,
            AplicarUnEventoDeIdentidad aplicador,
            AlertaDeEventosSinAplicar alerta) {
        this.fuente = fuente;
        this.aplicador = aplicador;
        this.alerta = alerta;
    }

    public Vuelta consumir() {
        FuenteDeEventosDeIdentidad.Lote lote = fuente.pendientes(POR_VUELTA);
        List<UUID> resueltos = new ArrayList<>();
        int aplicados = 0;
        int yaEstaban = 0;
        int ajenos = 0;
        int apartados = 0;
        int pendientes = 0;

        for (EventoDeIdentidadRecibido evento : lote.eventos()) {
            try {
                AplicarUnEventoDeIdentidad.Aplicacion resultado = aplicador.aplicar(evento);
                switch (resultado) {
                    case APLICADO -> aplicados++;
                    case YA_APLICADO -> yaEstaban++;
                    case IGNORADO_AJENO -> {
                        ajenos++;
                        // WARN y no en silencio: un permiso que se ignora sin rastro es
                        // indistinguible de uno que se perdio.
                        log.warn(
                                "Evento {} ({}, secuencia {}) es un permiso de OTRO sistema y no"
                                        + " rige en esta copia: se acusa y no se aplica. No es un"
                                        + " fallo: `identidad` publica los cinco catalogos por un"
                                        + " solo buzon, y a `rentas` solo le tocan los suyos",
                                evento.eventoId(),
                                evento.tipoPublicado(),
                                evento.secuencia());
                    }
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
                pendientes++;
                // NO se acusa y NO se aparta: su dependencia esta en camino. Un fallo que se
                // arregla solo no puede matar un evento (AC-7 #3).
                log.warn(
                        "Evento {} ({}, secuencia {}) TODAVIA no se puede aplicar y se deja"
                                + " pendiente en el buzon de `identidad`: {}",
                        evento.eventoId(),
                        evento.tipoPublicado(),
                        evento.secuencia(),
                        todaviaNo.getMessage());
            }
        }

        boolean acuseRechazado = false;
        if (!resueltos.isEmpty()) {
            try {
                fuente.acusar(List.copyOf(resueltos));
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
                pendientes,
                lote.quedan(),
                acuseRechazado);
    }

    private static String motivoDe(RuntimeException noSePudo) {
        String mensaje = noSePudo.getMessage();
        return mensaje == null ? noSePudo.getClass().getSimpleName() : mensaje;
    }

    /** Lo que una vuelta hizo, para el registro y para decidir si se sigue. */
    public record Vuelta(
            int leidos,
            int aplicados,
            int yaEstaban,
            int ajenos,
            int apartados,
            int pendientes,
            long quedan,
            boolean acuseRechazado) {

        /**
         * Sin progreso, no «vacia»: un evento pendiente por su dependencia no se acusa, asi que el
         * emisor lo vuelve a servir, y dar vueltas hasta que el lote llegue vacio seria darlas
         * todas sobre los mismos eventos (la leccion de #54).
         */
        public boolean sinProgreso() {
            return leidos == 0 || leidos == pendientes || acuseRechazado;
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
                    + pendientes
                    + " pendientes por su dependencia; quedan "
                    + quedan
                    + " en el buzon de `identidad`"
                    + (acuseRechazado ? "; y el acuse fue RECHAZADO" : "");
        }
    }
}
