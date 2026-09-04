package kamayuk.rentas.coactiva.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mueve el estado de un expediente coactivo dejando su historial (#40, RF-100).
 *
 * <h2>El estado no se escribe: se agrega un movimiento</h2>
 *
 * <p>V33 le retiro a {@code expediente_coactivo} la columna {@code estado} y le revoco el {@code
 * UPDATE}. Lo que este caso de uso hace es <b>insertar</b> una fila en {@code
 * expediente_movimiento}; el estado sale de ahi con {@link EstadoDelExpediente#delHistorial}. Un
 * cambio registrado por error no se corrige editando: se registra otro, y los dos quedan.
 *
 * <p>Por eso la pantalla puede mostrar el historial completo con su documento de respaldo, su
 * motivo y su observacion: no es un registro paralelo que alguien recuerda alimentar, es el
 * <b>unico</b> sitio donde el estado existe.
 */
@Service
public class CambiarEstadoDelExpediente {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CambiarEstadoDelExpediente(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Cambia el estado con fecha de hoy. */
    @Transactional
    public MovimientoDelExpediente cambiar(
            String numeroDeExpediente,
            EstadoDelExpediente nuevo,
            String motivo,
            @Nullable LocalDate documentoFecha,
            @Nullable String documentoNumero,
            Observacion observacion) {
        return cambiar(
                numeroDeExpediente,
                nuevo,
                LocalDate.now(reloj),
                motivo,
                documentoFecha,
                documentoNumero,
                observacion);
    }

    /**
     * Cambia el estado con una fecha explicita.
     *
     * @param fecha el dia del acto; entra como argumento para que un cambio dispuesto por una
     *     resolucion se registre con la fecha de la resolucion
     * @throws ExpedienteInexistente si no hay ningun expediente con ese numero
     * @throws ExpedienteConcluido si el expediente ya estaba concluido
     * @throws SinCambio si el expediente ya estaba en ese estado
     */
    @Transactional
    public MovimientoDelExpediente cambiar(
            String numeroDeExpediente,
            EstadoDelExpediente nuevo,
            LocalDate fecha,
            String motivo,
            @Nullable LocalDate documentoFecha,
            @Nullable String documentoNumero,
            Observacion observacion) {

        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(numeroDeExpediente)
                        .orElseThrow(() -> new ExpedienteInexistente(numeroDeExpediente));

        List<MovimientoDelExpediente> historial =
                movimientos.deExpediente(expediente.identificador());
        EstadoDelExpediente actual = EstadoDelExpediente.delHistorial(historial);

        if (actual.estaConcluido()) {
            throw new ExpedienteConcluido(expediente.numero());
        }
        if (actual == nuevo) {
            throw new SinCambio(expediente.numero(), actual);
        }

        MovimientoDelExpediente registrado =
                movimientos.registrar(
                        MovimientoDelExpediente.cambioDeEstado(
                                expediente.identificador(),
                                nuevo,
                                fecha,
                                motivo,
                                documentoFecha,
                                documentoNumero,
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "expediente_movimiento",
                                String.valueOf(registrado.id()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(json(expediente, actual), json(expediente, nuevo)));

        return registrado;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String json(ExpedienteCoactivo expediente, EstadoDelExpediente estado) {
        return "{\"expediente\":\""
                + expediente.numero()
                + "\",\"estado\":\""
                + estado.name()
                + "\"}";
    }

    /** No hay ningun expediente con ese numero. */
    public static final class ExpedienteInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ExpedienteInexistente(String numero) {
            super("No hay ningun expediente coactivo con el numero '" + numero + "'");
        }
    }

    /**
     * El expediente ya estaba concluido.
     *
     * <p>Reabrirlo cambiando su estado seria continuar un procedimiento que termino, sin la
     * resolucion que lo dispusiera. Lo que corresponde es otro expediente.
     */
    public static final class ExpedienteConcluido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ExpedienteConcluido(String numero) {
            super(
                    "El expediente "
                            + numero
                            + " esta concluido: sobre un procedimiento terminado no hay actos que"
                            + " registrar, y reabrirlo cambiando su estado lo haria sin"
                            + " resolucion que lo disponga");
        }
    }

    /** El expediente ya estaba en ese estado. */
    public static final class SinCambio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCambio(String numero, EstadoDelExpediente estado) {
            super(
                    "El expediente "
                            + numero
                            + " ya esta en "
                            + estado.etiqueta()
                            + ": registrar el mismo estado dos veces llenaria el historial de"
                            + " filas que no dicen nada");
        }
    }
}
