package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import org.jspecify.annotations.Nullable;

/**
 * Un acto coactivo como lo ve la interfaz, con sus diligencias (#41, RF-102, RF-103).
 *
 * <p><b>Sin importes.</b> Un acto no lleva cifra propia: la deuda del expediente viaja una sola vez
 * en {@link ProcesoResource}, con la fecha a la que esta (regla 9, RNF-075). Repetirla en cada
 * actuacion invitaria a que una linea del proceso mostrara la deuda de marzo junto a la de agosto.
 *
 * @param tipo que acto es
 * @param titulo como se titula el documento que lo materializa
 * @param numero el numero impreso, que es el del documento emitido
 * @param fecha el dia del acto
 * @param descripcion la glosa
 * @param medida la forma de la medida cautelar; solo la REC-2 la lleva
 * @param exigibleDesde el dia desde el que la REC-2 se podia dictar; solo la REC-2 lo lleva
 * @param usuario quien lo registro
 * @param observaciones por que se registro (regla 10)
 * @param diligencias las notificaciones del acto, de la primera a la ultima
 */
public record ActoResource(
        String tipo,
        String titulo,
        String numero,
        LocalDate fecha,
        String descripcion,
        @Nullable String medida,
        @Nullable LocalDate exigibleDesde,
        @Nullable String usuario,
        String observaciones,
        List<DiligenciaResource> diligencias) {

    /** Un acto recien dictado: todavia sin ninguna diligencia. */
    public static ActoResource de(ActoCoactivo acto) {
        return construir(acto, List.of());
    }

    /** Un acto con las diligencias que intentaron notificarlo. */
    public static ActoResource de(ConsultaDelProcesoCoactivo.Actuacion actuacion) {
        return construir(actuacion.acto(), actuacion.diligencias());
    }

    private static ActoResource construir(
            ActoCoactivo acto, List<NotificacionCoactiva> diligencias) {
        List<DiligenciaResource> traza = new ArrayList<>();
        for (NotificacionCoactiva diligencia : diligencias) {
            traza.add(DiligenciaResource.de(diligencia));
        }
        return new ActoResource(
                acto.tipo().name(),
                acto.tipo().titulo(),
                acto.numero(),
                acto.fecha(),
                acto.descripcion(),
                acto.medida() == null ? null : acto.medida().etiqueta(),
                acto.rec1ExigibleDesde(),
                acto.usuarioRegistro(),
                acto.observacion().texto(),
                List.copyOf(traza));
    }

    /**
     * Una diligencia de notificacion, tal como la pinta {@code notificaciones_coactivas}.
     *
     * <p>{@code surtioEfecto} se <b>deriva</b> del resultado y no se guarda: es lo mismo que hace
     * el dominio, y tenerlo en dos sitios es como se llega a que la pantalla diga que se notifico y
     * el expediente no lo crea.
     *
     * @param intento que diligencia es, desde 1
     * @param fecha el dia en que se diligencio
     * @param modalidad como se diligencio (art. 104)
     * @param resultado con que resultado termino
     * @param surtioEfecto si abrio el plazo del art. 14.1
     * @param exigibleDesde desde cuando se puede dictar la medida cautelar; nulo si no surtio
     *     efecto
     * @param notificador quien la llevo
     * @param domicilio donde se diligencio
     * @param receptor quien recibio, si alguien recibio
     * @param documentoReceptor su documento
     * @param vinculo su vinculo con el obligado
     * @param acuse la constancia del cargo
     * @param usuario quien la registro
     * @param observaciones por que se registro (regla 10)
     */
    public record DiligenciaResource(
            int intento,
            LocalDate fecha,
            String modalidad,
            String resultado,
            boolean surtioEfecto,
            @Nullable LocalDate exigibleDesde,
            String notificador,
            String domicilio,
            @Nullable String receptor,
            @Nullable String documentoReceptor,
            @Nullable String vinculo,
            @Nullable String acuse,
            @Nullable String usuario,
            String observaciones) {

        static DiligenciaResource de(NotificacionCoactiva diligencia) {
            return new DiligenciaResource(
                    diligencia.intento(),
                    diligencia.fechaDeLaDiligencia(),
                    diligencia.modalidad().name(),
                    diligencia.resultado().name(),
                    diligencia.surtioEfecto(),
                    diligencia.exigibleDesde(),
                    diligencia.notificador(),
                    diligencia.direccion(),
                    diligencia.receptor(),
                    diligencia.documentoReceptor(),
                    diligencia.vinculo(),
                    diligencia.acuse(),
                    diligencia.usuarioRegistro(),
                    diligencia.observacion().texto());
        }
    }
}
