package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cambia la placa de un vehiculo, dejando traza.
 *
 * <h2>Lo que este caso de uso NO hace, y es lo importante</h2>
 *
 * <p><b>No toca las papeletas.</b> La tabla {@code papeleta} guarda dos cosas: {@code vehiculo_id},
 * que es el enlace, y {@code placa}, que es <b>el texto que el inspector escribio en el acta</b>.
 * Sincronizar la segunda al cambiar la placa es lo que uno escribe sin pensar —«que quede
 * coherente»— y es reescribir un acta: el documento pasaria a decir una placa que ese dia no
 * existia, y con eso se cae la imputacion en un descargo.
 *
 * <p>Por eso el enlace es el identificador y no la placa. El vehiculo sigue siendo el mismo, sus
 * papeletas siguen colgando de el, y cada una conserva el texto de su acta.
 *
 * <h2>El historial</h2>
 *
 * <p>La auditoria se llavea por el <b>identificador</b> del vehiculo. Si se llavease por la placa,
 * el primer cambio partiria el historial en dos trozos sin nada que los una, y reconstruirlo
 * exigiria encadenar los {@code datos_nuevos} de uno con los {@code datos_anteriores} del otro
 * —adivinando—.
 */
@Service
public class CambiarPlaca {

    private final VehiculoRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CambiarPlaca(VehiculoRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    @Transactional
    public Vehiculo cambiar(long vehiculoId, Placa nueva, Observacion observacion) {
        Vehiculo actual =
                repositorio
                        .findById(vehiculoId)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No existe el vehiculo " + vehiculoId));

        if (actual.placa().equals(nueva)) {
            // `Placa` compara sin el guion, asi que 'ABC-123' -> 'ABC123' entra por
            // aqui: es reescribir el mismo dato, y dejaria en la auditoria un cambio
            // que no cambia nada.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "El vehiculo ya tiene la placa " + nueva + ": no hay nada que cambiar");
        }

        Vehiculo cambiado = repositorio.save(actual.conPlaca(nueva));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "vehiculo",
                                String.valueOf(vehiculoId),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(FichaEnJson.soloLaPlaca(actual), FichaEnJson.soloLaPlaca(cambiado)));

        return cambiado;
    }
}
