package kamayuk.rentas.sanciones.aplicacion;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kamayuk.rentas.sanciones.dominio.ActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.AcuseDelActo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import kamayuk.rentas.sanciones.dominio.MovimientoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todos los documentos emitidos por una papeleta, con su fecha y su acuse (#50, RF-065, AC 4).
 *
 * <p>Es lo que la pantalla {@code transito_documentos} lista: «registra los documentos emitidos por
 * papeleta y conserva la secuencia del trámite». La secuencia es una sola aunque los papeles salgan
 * de tres registros —{@code resolucion_gerencia}, {@code internamiento} y {@code
 * internamiento_movimiento}—, y componerla aquí es lo que evita que la pantalla tenga que
 * intercalar tres listas a mano y equivocarse de orden.
 *
 * <p><b>Con sus acuses, y todos.</b> Cada resolución trae sus diligencias, una fila por intento
 * (#39, V28). Quedarse con la última escondería que las dos anteriores no encontraron a nadie, que
 * es justamente lo que hay que poder mostrar cuando el administrado discute la notificación.
 *
 * <p>Las actas del depósito no llevan acuse y no es un olvido: se entregan en mano al conductor o a
 * quien retira, con su firma en el papel; no hay diligencia de notificación que registrar.
 */
@Service
public class ConsultaDeActosDeLaPapeleta {

    private final PapeletaRepository papeletas;
    private final ResolucionDeGerenciaRepository resoluciones;
    private final NotificacionDeResolucionRepository notificaciones;
    private final InternamientoRepository internamientos;
    private final DescargoRepository descargos;

    public ConsultaDeActosDeLaPapeleta(
            PapeletaRepository papeletas,
            ResolucionDeGerenciaRepository resoluciones,
            NotificacionDeResolucionRepository notificaciones,
            InternamientoRepository internamientos,
            DescargoRepository descargos) {
        this.papeletas = papeletas;
        this.resoluciones = resoluciones;
        this.notificaciones = notificaciones;
        this.internamientos = internamientos;
        this.descargos = descargos;
    }

    /**
     * El expediente de la papeleta: sus recursos y todos sus documentos, en orden.
     *
     * @param familia de qué familia es la papeleta
     * @param numero el número impreso
     * @throws RegistrarDescargo.PapeletaInexistente si no hay ninguna con ese número
     */
    @Transactional(readOnly = true)
    public Expediente de(Familia familia, String numero) {
        Papeleta papeleta =
                papeletas
                        .porNumero(familia, numero)
                        .orElseThrow(
                                () -> new RegistrarDescargo.PapeletaInexistente(familia, numero));
        long id = papeleta.identificador();

        List<ActoDeLaPapeleta> actos = new ArrayList<>();
        for (ResolucionDeGerencia resolucion : resoluciones.dePapeleta(id)) {
            actos.add(
                    new ActoDeLaPapeleta(
                            ActoDeLaPapeleta.CLASE_RESOLUCION,
                            resolucion.tipo().name(),
                            resolucion.numero(),
                            resolucion.fecha(),
                            resolucion.documentoId(),
                            resolucion.observacion(),
                            acusesDe(resolucion)));
        }
        for (Internamiento internamiento : internamientos.dePapeleta(id)) {
            actos.add(
                    new ActoDeLaPapeleta(
                            ActoDeLaPapeleta.CLASE_INTERNAMIENTO,
                            "INGRESO",
                            internamiento.acta(),
                            internamiento.fechaIngreso().atZone(ZoneOffset.UTC).toLocalDate(),
                            internamiento.documentoId(),
                            internamiento.observacion(),
                            List.of()));
        }
        for (MovimientoDeInternamiento movimiento : internamientos.movimientosDePapeleta(id)) {
            actos.add(
                    new ActoDeLaPapeleta(
                            ActoDeLaPapeleta.CLASE_INTERNAMIENTO,
                            movimiento.tipo().name(),
                            movimiento.acta(),
                            movimiento.fecha(),
                            movimiento.documentoId(),
                            movimiento.observacion(),
                            List.of()));
        }
        actos.sort(
                Comparator.comparing(ActoDeLaPapeleta::fecha)
                        .thenComparing(ActoDeLaPapeleta::numero));

        return new Expediente(papeleta, descargos.dePapeleta(id), List.copyOf(actos));
    }

    private List<AcuseDelActo> acusesDe(ResolucionDeGerencia resolucion) {
        List<AcuseDelActo> acuses = new ArrayList<>();
        for (NotificacionDeResolucion diligencia :
                notificaciones.deResolucion(resolucion.identificador())) {
            acuses.add(
                    new AcuseDelActo(
                            diligencia.intento(),
                            diligencia.fechaDeLaDiligencia(),
                            diligencia.modalidad(),
                            diligencia.resultado(),
                            diligencia.receptor(),
                            diligencia.acuse(),
                            diligencia.exigibleDesde()));
        }
        return acuses;
    }

    /**
     * El expediente de una papeleta.
     *
     * @param papeleta la multa
     * @param descargos los recursos presentados contra ella
     * @param actos todos los documentos emitidos, en orden de fecha
     */
    public record Expediente(
            Papeleta papeleta, List<Descargo> descargos, List<ActoDeLaPapeleta> actos) {}
}
