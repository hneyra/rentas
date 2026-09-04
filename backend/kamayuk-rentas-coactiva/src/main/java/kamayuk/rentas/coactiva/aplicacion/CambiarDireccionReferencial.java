package kamayuk.rentas.coactiva.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reemplaza la direccion referencial de un expediente coactivo, dejando traza (#40, RF-106).
 *
 * <h2>Que es, y por que no es el domicilio fiscal</h2>
 *
 * <p>Es donde el ejecutor coactivo notifica al obligado cuando el domicilio fiscal no sirve —porque
 * esta cerrado, porque el obligado se mudo, porque la direccion declarada no existe—. Cambiar el
 * domicilio fiscal es otro acto, en otro contexto ({@code contribuyentes}), y tiene consecuencias
 * tributarias que este no tiene: aqui solo se decide a donde va el notificador de <b>este</b>
 * expediente.
 *
 * <p>Por eso el cambio no toca la cabecera del expediente —que ademas no admite {@code UPDATE}
 * desde V33— sino que <b>agrega</b> un movimiento con su motivo y su observacion. La direccion con
 * la que se abrio la carpeta se conserva, y es la que explica a donde fueron sus primeras
 * notificaciones.
 */
@Service
public class CambiarDireccionReferencial {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CambiarDireccionReferencial(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Cambia la direccion con fecha de hoy. */
    @Transactional
    public MovimientoDelExpediente cambiar(
            String numeroDeExpediente, String nueva, String motivo, Observacion observacion) {
        return cambiar(numeroDeExpediente, nueva, LocalDate.now(reloj), motivo, observacion);
    }

    /**
     * Cambia la direccion con una fecha explicita.
     *
     * @throws CambiarEstadoDelExpediente.ExpedienteInexistente si no hay ningun expediente con ese
     *     numero
     * @throws MismaDireccion si la nueva es la que ya estaba vigente
     */
    @Transactional
    public MovimientoDelExpediente cambiar(
            String numeroDeExpediente,
            String nueva,
            LocalDate fecha,
            String motivo,
            Observacion observacion) {

        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(numeroDeExpediente)
                        .orElseThrow(
                                () ->
                                        new CambiarEstadoDelExpediente.ExpedienteInexistente(
                                                numeroDeExpediente));

        String limpia = Objects.requireNonNull(nueva, "La direccion nueva es obligatoria").strip();
        if (limpia.isEmpty()) {
            throw new IllegalArgumentException(
                    "La direccion referencial nueva no puede ir en blanco: dejar al expediente sin"
                            + " a donde notificar no es un cambio, es una baja");
        }

        String vigente = vigenteDe(expediente);
        if (limpia.equalsIgnoreCase(vigente)) {
            throw new MismaDireccion(expediente.numero(), limpia);
        }

        MovimientoDelExpediente registrado =
                movimientos.registrar(
                        MovimientoDelExpediente.cambioDeDireccion(
                                expediente.identificador(),
                                limpia,
                                fecha,
                                motivo,
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "expediente_movimiento",
                                String.valueOf(registrado.id()),
                                Operacion.MODIFICACION,
                                observacion)
                        // La direccion NO viaja al JSON de la auditoria: es un dato personal, y la
                        // auditoria guarda que paso, no donde vive el obligado. La direccion queda
                        // en la fila del movimiento, que es donde el expediente la necesita.
                        .con(
                                "{\"expediente\":\""
                                        + expediente.numero()
                                        + "\",\"teniaDireccion\":"
                                        + (vigente != null)
                                        + "}",
                                "{\"expediente\":\""
                                        + expediente.numero()
                                        + "\",\"teniaDireccion\":true}"));

        return registrado;
    }

    /**
     * La direccion referencial vigente: la del ultimo cambio, o la de apertura si no hubo ninguno.
     *
     * <p>Es la que la pantalla {@code cambiar_direccion_ref} muestra como «Dirección referencial
     * actual (expediente)» antes de dejar escribir la nueva.
     */
    @Transactional(readOnly = true)
    public @Nullable String vigenteDe(ExpedienteCoactivo expediente) {
        Optional<MovimientoDelExpediente> ultimo =
                movimientos.ultimoCambioDeDireccion(expediente.identificador());
        return ultimo.map(MovimientoDelExpediente::direccionNueva)
                .orElseGet(expediente::direccionReferencial);
    }

    /** La direccion nueva es la que ya estaba vigente. */
    public static final class MismaDireccion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        MismaDireccion(String numero, String direccion) {
            super(
                    "El expediente "
                            + numero
                            + " ya notifica en esa direccion: registrar el mismo cambio dos veces"
                            + " llenaria la traza de movimientos que no cambian nada");
        }
    }
}
