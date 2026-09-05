package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transferencias de predio y de vehiculo, con su historico (RF-026 parte de registro, RF-027,
 * RF-030, #29).
 *
 * <p>Sigue la plantilla de {@code RegistrarDeclaracionJurada}: resuelve lo que el dominio no puede
 * resolver por su cuenta antes de construirlo, y dos resoluciones son propias de este caso de uso:
 *
 * <ul>
 *   <li>{@link #transferirPredio}: pregunta a {@link GestorDeTitularidad#vigenteDe} cual es la
 *       titularidad del transferente antes de poder transferirla —{@code Transferencia} no conoce
 *       la titularidad, solo que hubo un acto—.
 *   <li>{@link #transferirVehiculo}: el transferente <b>no viene en la peticion</b>, se lee del
 *       propio vehiculo: es quien figura como titular ahora mismo, y pedirlo de otra fuente abriria
 *       la puerta a que no coincidan.
 * </ul>
 *
 * <p><b>Ningun asiento de cuenta corriente se genera aqui.</b> Trasladar la deuda del transferente
 * al adquiriente es una decision que exige su propio sustento y no se infiere de una transferencia
 * (ver javadoc de {@link Transferencia}); usa {@code POST /rentas/deuda/altas} y {@code .../bajas}
 * cuando corresponda.
 */
@Service
public class RegistrarTransferencia {

    private final TransferenciaRepository repositorio;
    private final GestorDeTitularidad titularidad;
    private final VehiculoRepository vehiculos;
    private final Auditoria auditoria;

    public RegistrarTransferencia(
            TransferenciaRepository repositorio,
            GestorDeTitularidad titularidad,
            VehiculoRepository vehiculos,
            Auditoria auditoria) {
        this.repositorio = repositorio;
        this.titularidad = titularidad;
        this.vehiculos = vehiculos;
        this.auditoria = auditoria;
    }

    /**
     * Transfiere una cuota de un predio: cierra la titularidad vigente del transferente y abre la
     * del adquiriente —y, si la transferencia es parcial, una tercera con el remanente del
     * transferente—, todo antes de dejar constancia del acto.
     */
    @Transactional
    public Transferencia transferirPredio(
            long predioId,
            long transferenteId,
            long adquirienteId,
            TipoTransferencia tipoTransferencia,
            LocalDate fecha,
            Dinero valorTransferencia,
            Porcentaje porcentajeTransferido,
            boolean afectaAlcabala,
            String documentoOrigen,
            Observacion observacion) {

        CuotaDeTitularidad vigente =
                titularidad
                        .vigenteDe(predioId, transferenteId, fecha)
                        .orElseThrow(
                                () -> new TransferenteSinTitularidad(predioId, transferenteId));

        titularidad.transferir(
                vigente.titularidadId(),
                adquirienteId,
                porcentajeTransferido,
                fecha,
                documentoOrigen,
                observacion);

        Transferencia guardada =
                repositorio.insertar(
                        Transferencia.dePredio(
                                predioId,
                                transferenteId,
                                adquirienteId,
                                tipoTransferencia,
                                fecha,
                                valorTransferencia,
                                porcentajeTransferido,
                                afectaAlcabala,
                                documentoOrigen,
                                observacion));
        auditar(guardada, observacion);
        return guardada;
    }

    /**
     * Transfiere un vehiculo: el transferente es quien figura hoy como titular, no un dato que
     * llegue en la peticion.
     */
    @Transactional
    public Transferencia transferirVehiculo(
            long vehiculoId,
            long adquirienteId,
            TipoTransferencia tipoTransferencia,
            LocalDate fecha,
            Dinero valorTransferencia,
            boolean afectaAlcabala,
            String documentoOrigen,
            Observacion observacion) {

        Vehiculo actual =
                vehiculos
                        .findById(vehiculoId)
                        .orElseThrow(() -> new VehiculoInexistente(vehiculoId));
        long transferenteId = actual.contribuyenteId();
        if (transferenteId == adquirienteId) {
            throw new IllegalArgumentException(
                    "El vehiculo " + vehiculoId + " ya es del contribuyente " + adquirienteId);
        }
        vehiculos.save(actual.conTitular(adquirienteId));

        Transferencia guardada =
                repositorio.insertar(
                        Transferencia.deVehiculo(
                                vehiculoId,
                                transferenteId,
                                adquirienteId,
                                tipoTransferencia,
                                fecha,
                                valorTransferencia,
                                afectaAlcabala,
                                documentoOrigen,
                                observacion));
        auditar(guardada, observacion);
        return guardada;
    }

    private void auditar(Transferencia guardada, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                guardada.fechaTransferencia(),
                                "transferencia",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));
    }

    private static String descripcion(Transferencia transferencia) {
        return "{\"objeto\":\""
                + transferencia.objeto()
                + "\",\"transferenteId\":"
                + transferencia.transferenteId()
                + ",\"adquirienteId\":"
                + transferencia.adquirienteId()
                + ",\"tipoTransferencia\":\""
                + transferencia.tipoTransferencia()
                + "\",\"porcentajeTransferido\":\""
                + transferencia.porcentajeTransferido()
                + "\"}";
    }

    /** El transferente no tiene ninguna titularidad vigente sobre ese predio a esa fecha. */
    public static final class TransferenteSinTitularidad extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransferenteSinTitularidad(long predioId, long transferenteId) {
            super(
                    "El contribuyente "
                            + transferenteId
                            + " no tiene ninguna titularidad vigente sobre el predio "
                            + predioId
                            + ": no hay que transferir");
        }
    }

    /** No hay ningun vehiculo con ese identificador, o es de otra municipalidad. */
    public static final class VehiculoInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoInexistente(long id) {
            super("No hay ningun vehiculo con identificador " + id + " en esta municipalidad");
        }
    }
}
