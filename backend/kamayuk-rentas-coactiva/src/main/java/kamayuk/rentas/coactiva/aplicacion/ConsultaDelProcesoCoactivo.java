package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactivaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El seguimiento del expediente coactivo: lo que la pantalla {@code proceso_coactivo} dibuja (#41,
 * RF-101).
 *
 * <p>Es la ficha que #40 ya componia —cabecera, estado derivado, direccion vigente, valores,
 * historial y deuda actualizada a la fecha— <b>mas</b> las actuaciones: cada acto con sus
 * diligencias de notificacion. La ficha no se vuelve a componer aqui: se pide a {@link
 * ConsultaDeExpedientes}, que es el unico sitio donde se deriva el estado y donde se pregunta la
 * deuda por los puertos publicos (regla 9).
 *
 * <p>Por {@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL}, y sin
 * el la politica RLS falla en vez de devolver filas.
 */
@Service
public class ConsultaDelProcesoCoactivo {

    private final ConsultaDeExpedientes expedientes;
    private final ActoCoactivoRepository actos;
    private final NotificacionCoactivaRepository notificaciones;

    public ConsultaDelProcesoCoactivo(
            ConsultaDeExpedientes expedientes,
            ActoCoactivoRepository actos,
            NotificacionCoactivaRepository notificaciones) {
        this.expedientes = expedientes;
        this.actos = actos;
        this.notificaciones = notificaciones;
    }

    /**
     * El proceso completo de un expediente, con su deuda actualizada a la fecha pedida.
     *
     * @param numero el numero impreso del expediente
     * @param aLaFecha a que dia se actualiza la deuda (regla 9). No afecta a las actuaciones: un
     *     acto dictado es un hecho, y no depende de cuando se mire
     */
    @Transactional(readOnly = true)
    public Optional<ProcesoCoactivo> porNumero(String numero, LocalDate aLaFecha) {
        return expedientes
                .porNumero(numero, aLaFecha)
                .map(
                        ficha -> {
                            List<Actuacion> actuaciones = new ArrayList<>();
                            for (ActoCoactivo acto :
                                    actos.deExpediente(ficha.expediente().identificador())) {
                                actuaciones.add(
                                        new Actuacion(
                                                acto, notificaciones.deActo(acto.identificador())));
                            }
                            return new ProcesoCoactivo(ficha, actuaciones);
                        });
    }

    /** Las actuaciones de un expediente, sin su ficha. */
    @Transactional(readOnly = true)
    public List<Actuacion> actuacionesDe(long expedienteId) {
        List<Actuacion> actuaciones = new ArrayList<>();
        for (ActoCoactivo acto : actos.deExpediente(expedienteId)) {
            actuaciones.add(new Actuacion(acto, notificaciones.deActo(acto.identificador())));
        }
        return actuaciones;
    }

    /**
     * Un acto con las diligencias que intentaron notificarlo.
     *
     * <p>Van <b>todas</b>, no solo la que surtio efecto: que se intento dos veces y no se hallo al
     * obligado es parte del expediente, y es lo que sostiene una notificacion por cedulon.
     *
     * @param acto el acto dictado
     * @param diligencias sus notificaciones, de la primera a la ultima
     */
    public record Actuacion(ActoCoactivo acto, List<NotificacionCoactiva> diligencias) {

        public Actuacion {
            Objects.requireNonNull(acto, "Una actuacion es la de un acto");
            diligencias = List.copyOf(diligencias);
        }

        /** La diligencia que abrio el plazo, si alguna lo hizo. */
        public Optional<NotificacionCoactiva> queSurtioEfecto() {
            return diligencias.stream().filter(NotificacionCoactiva::surtioEfecto).findFirst();
        }
    }

    /**
     * El expediente con todo lo que la pantalla de seguimiento necesita.
     *
     * @param ficha la cabecera, el estado, los valores, el historial y la deuda con su fecha
     * @param actuaciones los actos del procedimiento, cada uno con sus diligencias
     */
    public record ProcesoCoactivo(
            ConsultaDeExpedientes.FichaDelExpediente ficha, List<Actuacion> actuaciones) {

        public ProcesoCoactivo {
            Objects.requireNonNull(ficha, "El proceso es el de un expediente");
            actuaciones = List.copyOf(actuaciones);
        }
    }
}
