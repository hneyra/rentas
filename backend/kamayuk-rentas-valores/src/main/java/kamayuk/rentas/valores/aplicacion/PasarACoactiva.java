package kamayuk.rentas.valores.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValorRepository;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.NotificacionRepository;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasa un valor al area de cobranza coactiva (#39, RF-095).
 *
 * <h2>Dos condiciones, y ninguna es opinable</h2>
 *
 * <ol>
 *   <li><b>Notificado.</b> Un valor sin diligencia que surta efecto no puede pasar: el art. 14 de
 *       la Ley 26979 exige que el acto que da origen a la deuda este debidamente notificado. Un
 *       expediente abierto sobre un valor no notificado es nulo, y anularlo despues cuesta la
 *       cobranza entera.
 *   <li><b>Con el plazo vencido.</b> La fecha del pase tiene que ser posterior a la exigibilidad
 *       que la diligencia fijo -y esa exigibilidad salio del plazo <b>parametrizado</b>, no de una
 *       constante-. Mientras el plazo corre, el deudor todavia puede reclamar.
 * </ol>
 *
 * <h2>Idempotente</h2>
 *
 * <p>Pasarlo dos veces no crea dos expedientes (AC de #39). La garantia esta en la base -el indice
 * unico parcial de V28 y su {@code ON CONFLICT}-, no en una comprobacion previa: dos peticiones
 * simultaneas pasarian las dos por cualquier {@code if} escrito en Java. La segunda llamada
 * devuelve el movimiento de la primera, con su fecha y su usuario originales.
 */
@Service
public class PasarACoactiva {

    private final ValorRepository valores;
    private final NotificacionRepository notificaciones;
    private final MovimientoDeValorRepository movimientos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public PasarACoactiva(
            ValorRepository valores,
            NotificacionRepository notificaciones,
            MovimientoDeValorRepository movimientos,
            Auditoria auditoria,
            Clock reloj) {
        this.valores = valores;
        this.notificaciones = notificaciones;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Pasa el valor a coactiva a la fecha de hoy. */
    @Transactional
    public MovimientoDeValor pasar(String numeroDeValor, Observacion observacion) {
        return pasar(numeroDeValor, LocalDate.now(reloj), observacion);
    }

    /**
     * Pasa el valor a coactiva a una fecha explicita.
     *
     * @param fechaDelMovimiento la fecha del pase; entra como argumento y no sale del reloj para
     *     que el pase de una cartera cargada un lunes se pueda registrar con la fecha en que la
     *     resolucion lo dispuso
     * @throws ValorInexistente si no hay ningun valor con ese numero
     * @throws ValorSinNotificar si ninguna diligencia del valor surtio efecto
     * @throws PlazoVigente si a esa fecha la deuda todavia no era exigible
     */
    @Transactional
    public MovimientoDeValor pasar(
            String numeroDeValor, LocalDate fechaDelMovimiento, Observacion observacion) {

        Valor valor =
                valores.porNumero(numeroDeValor.strip().toUpperCase(Locale.ROOT))
                        .orElseThrow(() -> new ValorInexistente(numeroDeValor));
        long valorId = requireId(valor);

        Notificacion notificacion =
                notificaciones
                        .queSurtioEfecto(valorId)
                        .orElseThrow(() -> new ValorSinNotificar(valor));
        LocalDate exigibleDesde =
                java.util.Objects.requireNonNull(
                        notificacion.exigibleDesde(),
                        "Una diligencia que surtio efecto siempre lleva su exigibilidad (V28)");

        if (fechaDelMovimiento.isBefore(exigibleDesde)) {
            throw new PlazoVigente(valor, exigibleDesde, fechaDelMovimiento);
        }

        MovimientoDeValor pase =
                movimientos.registrarPase(
                        new MovimientoDeValor(
                                null,
                                valorId,
                                TipoDeMovimiento.PCO,
                                fechaDelMovimiento,
                                requireId(notificacion),
                                exigibleDesde,
                                null,
                                observacion));

        if (valor.estado() == EstadoDeValor.NOTIFICADO) {
            valores.cambiarEstado(valorId, EstadoDeValor.COACTIVA);
        }

        auditar(valor, pase, observacion);
        return pase;
    }

    // ------------------------------------------------------------------

    private static long requireId(Valor valor) {
        Long id = valor.id();
        if (id == null) {
            throw new IllegalStateException("Un valor sin guardar no se puede pasar a coactiva");
        }
        return id;
    }

    private static long requireId(Notificacion notificacion) {
        Long id = notificacion.id();
        if (id == null) {
            throw new IllegalStateException("Una notificacion sin guardar no sustenta nada");
        }
        return id;
    }

    private void auditar(Valor valor, MovimientoDeValor pase, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                pase.fecha(),
                                "valor_movimiento",
                                String.valueOf(pase.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(valor, pase)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Valor valor, MovimientoDeValor pase) {
        return "{\"valor\":\""
                + valor.numero()
                + "\",\"tipo\":\""
                + pase.tipo()
                + "\",\"exigibleDesde\":\""
                + pase.exigibleDesde()
                + "\"}";
    }

    /** No hay ningun valor con ese numero. */
    public static final class ValorInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ValorInexistente(String numero) {
            super("No hay ningun valor con el numero '" + numero + "'");
        }
    }

    /** El valor no tiene ninguna diligencia que haya surtido efecto. */
    public static final class ValorSinNotificar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ValorSinNotificar(Valor valor) {
            super(
                    "El valor "
                            + valor.numero()
                            + " no esta notificado: sin notificacion, el expediente coactivo es"
                            + " nulo (Ley 26979, art. 14)");
        }
    }

    /** El plazo todavia corre: la deuda no es exigible a esa fecha. */
    public static final class PlazoVigente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PlazoVigente(Valor valor, LocalDate exigibleDesde, LocalDate fecha) {
            super(
                    "La deuda del valor "
                            + valor.numero()
                            + " no es exigible hasta el "
                            + exigibleDesde
                            + ": el "
                            + fecha
                            + " el plazo todavia corre y el deudor puede reclamar");
        }
    }
}
