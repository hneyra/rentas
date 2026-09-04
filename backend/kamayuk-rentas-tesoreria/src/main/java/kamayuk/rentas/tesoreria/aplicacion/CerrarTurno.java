package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.ArqueoDelTurno;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCajaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra el turno de un cajero con su arqueo, y reversa un cierre (#36, RF-087).
 *
 * <h2>Un cierre no se modifica ni se borra: se reversa con otro</h2>
 *
 * <p>Regla 4, y aqui es literal. {@link #cerrar} escribe una fila de {@code cierre_turno} con los
 * totales <b>congelados</b>; {@link #reversar} escribe otra que la deja sin efecto y <b>reabre</b>
 * el turno. El acta anterior no se toca: sigue diciendo lo que decia, con su fecha, su usuario y su
 * arqueo medio por medio. Corregir un cierre editandolo dejaria el papel firmado y la base diciendo
 * cosas distintas, y esa discusion la gana quien tenga el papel.
 *
 * <h2>Cobrar despues de cerrar exige reabrir, y reabrir es reversar</h2>
 *
 * <p>{@code AbrirCaja} rechaza cobrar contra un turno cerrado desde #33. Lo que #36 corrige es lo
 * que ese rechazo <b>prometia</b>: «hay que abrir otro turno». No existe tal cosa —{@code
 * cierre_uq} (V3) hace unico el turno por (caja, cajero, fecha)—, asi que la unica salida real es
 * reversar el cierre. El cierre siguiente vuelve a congelar sus totales, que ya incluiran lo
 * cobrado despues, y el historial conserva los dos arqueos y la reversion entre ellos.
 *
 * <h2>Dos cierres a la vez</h2>
 *
 * <p>Dos barreras, y las dos en la base:
 *
 * <ol>
 *   <li>el turno bloqueado con {@code FOR UPDATE} —el mismo candado con el que se serializa la
 *       ventanilla desde #33—: mientras una cobranza tenga el turno, el cierre espera, y al reves;
 *   <li>{@code cierre_turno_secuencia_uq}: dos cierres simultaneos calculan la misma secuencia y
 *       uno recibe {@code 23505}. Es lo que sigue valiendo si algun dia alguien escribe un camino
 *       que no bloquee.
 * </ol>
 *
 * <h2>El descuadre se guarda, no se rechaza</h2>
 *
 * <p>Que lo declarado no coincida con el neto del sistema <b>no impide cerrar</b>: es justo lo que
 * hay que dejar escrito. Lo que si impide cerrar es que el turno no cuadre <b>contra el libro</b>
 * ({@link ArqueoDeTurno#cuadrar}), que es otra cosa: ahi no falta dinero en el cajon, sino que los
 * asientos no dicen lo mismo que los recibos, y firmar sobre eso propagaria el defecto.
 */
@Service
public class CerrarTurno {

    private final CajaRepository cajas;
    private final TurnoDeCajaRepository turnos;
    private final CierreDeTurnoRepository cierres;
    private final ArqueoDeTurno arqueos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CerrarTurno(
            CajaRepository cajas,
            TurnoDeCajaRepository turnos,
            CierreDeTurnoRepository cierres,
            ArqueoDeTurno arqueos,
            Auditoria auditoria,
            Clock reloj) {
        this.cajas = cajas;
        this.turnos = turnos;
        this.cierres = cierres;
        this.arqueos = arqueos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cierra el turno con el arqueo que el cajero declaro.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Cierre}: la regla 10 exige que
     * se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @throws AbrirCaja.CajaInexistente si no hay ninguna caja con ese codigo
     * @throws TurnoSinAbrir si ese cajero no abrio turno ese dia: no hay nada que arquear
     * @throws TurnoYaCerrado si el turno ya esta cerrado
     * @throws ArqueoDeTurno.ElArqueoNoCuadraConElLibro si los asientos no dicen lo que los recibos
     */
    @Transactional
    public Cerrado cerrar(Cierre peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se cierra sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        // 1. La ventanilla, serializada: el mismo candado que toma una cobranza. Mientras
        //    el cierre lo tenga, ninguna cobranza de esta caja entra, y por eso el arqueo
        //    que se congela no puede quedarse corto entre leerlo y escribirlo.
        Bloqueado bloqueado =
                bloquear(peticion.codigoDeCaja(), peticion.cajero(), peticion.fecha());
        long turnoId = bloqueado.turno().idGuardado();
        List<CierreDeTurno> historia = cierres.deTurno(turnoId);
        if (CierreDeTurno.vigenteEn(historia) != null) {
            throw new TurnoYaCerrado(bloqueado.caja(), peticion.cajero(), peticion.fecha());
        }

        // 2. El arqueo, y su cuadre contra el libro. Si los asientos no dicen lo que los
        //    recibos, no se firma nada: la transaccion entera se va con la excepcion.
        ArqueoDelTurno arqueo = arqueos.del(turnoId, peticion.declarado(), peticion.fecha());
        ArqueoDeTurno.Cuadre cuadre = arqueos.cuadrar(turnoId, peticion.fecha());
        if (!cuadre.total().equals(arqueo.neto())) {
            throw new ElCuadreNoSumaElNeto(turnoId, arqueo.neto(), cuadre.total());
        }

        CierreDeTurno cerrado =
                cierres.registrar(
                        CierreDeTurno.cierre(
                                turnoId,
                                historia.size() + 1,
                                arqueo,
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fecha(),
                                "cierre_turno",
                                String.valueOf(cerrado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcionDelCierre(bloqueado.caja(), arqueo)));

        return new Cerrado(bloqueado.caja(), bloqueado.turno(), cerrado, cuadre);
    }

    /**
     * Reversa el cierre vigente del turno: lo deja sin efecto y vuelve a abrirlo.
     *
     * <p>El cierre que se reversa <b>no se toca</b>. Lo que se escribe es una fila nueva que lo
     * nombra, con su motivo y su observacion, y {@code cierre_turno_reversion_uq} impide reversarlo
     * dos veces —dos reversiones dejarian el historial contando una reapertura que no ocurrio—.
     *
     * @param motivo el sustento de reabrir una caja cuyo arqueo ya estaba firmado (RNF-052)
     * @throws TurnoSinAbrir si ese cajero no abrio turno ese dia
     * @throws TurnoSinCerrar si el turno no tiene ningun cierre vigente que reversar
     */
    @Transactional
    public Reversado reversar(
            String codigoDeCaja,
            String cajero,
            LocalDate fecha,
            String motivo,
            Observacion observacion) {

        Objects.requireNonNull(motivo, "Reversar un cierre exige su motivo (RNF-052)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Bloqueado bloqueado = bloquear(codigoDeCaja, cajero, fecha);
        long turnoId = bloqueado.turno().idGuardado();
        List<CierreDeTurno> historia = cierres.deTurno(turnoId);
        CierreDeTurno vigente = CierreDeTurno.vigenteEn(historia);
        if (vigente == null) {
            throw new TurnoSinCerrar(bloqueado.caja(), cajero, fecha);
        }

        CierreDeTurno reversion =
                cierres.registrar(
                        CierreDeTurno.reversion(
                                vigente,
                                historia.size() + 1,
                                fecha,
                                reloj.instant(),
                                motivo,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "cierre_turno",
                                String.valueOf(reversion.id()),
                                Operacion.ANULACION,
                                observacion)
                        .con(null, descripcionDeLaReversion(bloqueado.caja(), vigente, reversion)));

        return new Reversado(bloqueado.caja(), vigente, reversion);
    }

    // ------------------------------------------------------------------

    private Bloqueado bloquear(String codigoDeCaja, String cajero, LocalDate fecha) {
        Caja caja =
                cajas.porCodigo(codigoDeCaja)
                        .orElseThrow(() -> new AbrirCaja.CajaInexistente(codigoDeCaja));
        long cajaId =
                Objects.requireNonNull(
                        caja.id(), "Una caja leida del repositorio siempre trae su identificador");
        TurnoDeCaja turno =
                turnos.bloquear(cajaId, cajero, fecha)
                        .orElseThrow(() -> new TurnoSinAbrir(caja, cajero, fecha));
        return new Bloqueado(caja, turno);
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcionDelCierre(Caja caja, ArqueoDelTurno arqueo) {
        return "{\"caja\":\""
                + caja.codigo()
                + "\",\"fecha\":\""
                + arqueo.aLaFecha()
                + "\",\"cobrado\":"
                + arqueo.totalCobrado().valor().toPlainString()
                + ",\"anulado\":"
                + arqueo.totalAnulado().valor().toPlainString()
                + ",\"neto\":"
                + arqueo.neto().valor().toPlainString()
                + ",\"declarado\":"
                + arqueo.totalDeclarado().valor().toPlainString()
                + ",\"diferencia\":"
                + arqueo.diferencia().valor().toPlainString()
                + ",\"recibosEmitidos\":"
                + arqueo.recibosEmitidos()
                + ",\"recibosAnulados\":"
                + arqueo.recibosAnulados()
                + "}";
    }

    private static String descripcionDeLaReversion(
            Caja caja, CierreDeTurno vigente, CierreDeTurno reversion) {
        return "{\"caja\":\""
                + caja.codigo()
                + "\",\"fecha\":\""
                + reversion.fecha()
                + "\",\"reversaCierre\":"
                + vigente.idGuardado()
                + ",\"netoQueQuedaSinEfecto\":"
                + vigente.netoCongelado().valor().toPlainString()
                + ",\"motivo\":\""
                + reversion.motivoDeLaReversion()
                + "\"}";
    }

    // ------------------------------------------------------------------

    /** La caja y su turno, ya bloqueado. */
    private record Bloqueado(Caja caja, TurnoDeCaja turno) {}

    /**
     * Lo que el cajero declara al cerrar.
     *
     * @param codigoDeCaja la ventanilla
     * @param cajero quien cierra su turno
     * @param fecha el dia del turno; entra como argumento, no sale del reloj (regla 6)
     * @param declarado lo contado en el cajon, medio de pago por medio de pago. <b>Es el unico dato
     *     del arqueo que el sistema no puede recomponer</b>; los que falten cuentan como cero
     */
    public record Cierre(
            String codigoDeCaja,
            String cajero,
            LocalDate fecha,
            Map<FormaDePago, Dinero> declarado) {

        public Cierre {
            Objects.requireNonNull(codigoDeCaja, "El cierre es de una caja");
            Objects.requireNonNull(cajero, "El turno lo cierra el cajero que lo abrio");
            Objects.requireNonNull(fecha, "La fecha del turno entra como argumento (regla 6)");
            Objects.requireNonNull(declarado, "El mapa es vacio, no nulo");
            declarado = Map.copyOf(declarado);
            for (Map.Entry<FormaDePago, Dinero> entrada : declarado.entrySet()) {
                if (entrada.getValue().esNegativo()) {
                    throw new IllegalArgumentException(
                            "No se declara en negativo: " + entrada.getKey());
                }
            }
        }
    }

    /**
     * El turno cerrado con su acta.
     *
     * @param caja la ventanilla
     * @param turno la apertura que se cierra
     * @param cierre el acta, con su arqueo congelado
     * @param cuadre las dos mitades de lo recaudado, y contra que se comprobo cada una
     */
    public record Cerrado(
            Caja caja, TurnoDeCaja turno, CierreDeTurno cierre, ArqueoDeTurno.Cuadre cuadre) {}

    /**
     * El cierre que quedo sin efecto y la fila que lo dejo asi.
     *
     * @param caja la ventanilla
     * @param reversado el cierre anterior, <b>intacto</b>: sigue diciendo lo que decia
     * @param reversion la fila nueva
     */
    public record Reversado(Caja caja, CierreDeTurno reversado, CierreDeTurno reversion) {}

    /** Ese cajero no abrio turno ese dia en esa caja: no hay nada que arquear. */
    public static final class TurnoSinAbrir extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TurnoSinAbrir(Caja caja, String cajero, LocalDate fecha) {
            super(
                    "El cajero "
                            + cajero
                            + " no abrio turno en la caja "
                            + caja.codigo()
                            + " el "
                            + fecha
                            + ": no hay nada que arquear. El turno lo abre la primera cobranza del"
                            + " dia (#33)");
        }
    }

    /** El turno ya esta cerrado. Volver a cerrarlo dejaria dos arqueos sobre el mismo dinero. */
    public static final class TurnoYaCerrado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TurnoYaCerrado(Caja caja, String cajero, LocalDate fecha) {
            super(
                    "El turno de "
                            + cajero
                            + " en la caja "
                            + caja.codigo()
                            + " del "
                            + fecha
                            + " ya esta cerrado. Un cierre no se modifica ni se borra: si hay que"
                            + " rehacerlo, se reversa el que hay -y eso reabre el turno- y se"
                            + " cierra otra vez (regla 4)");
        }
    }

    /** El turno no tiene ningun cierre vigente: no hay nada que reversar. */
    public static final class TurnoSinCerrar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TurnoSinCerrar(Caja caja, String cajero, LocalDate fecha) {
            super(
                    "El turno de "
                            + cajero
                            + " en la caja "
                            + caja.codigo()
                            + " del "
                            + fecha
                            + " no esta cerrado: no hay ningun arqueo que dejar sin efecto");
        }
    }

    /**
     * Las dos mitades del cuadre no suman el neto del arqueo.
     *
     * <p>No puede pasar sin un defecto: las dos se calculan de los mismos recibos. Se comprueba
     * igual porque el precio es cero y lo que detecta es que alguien haya cambiado el reparto entre
     * «lo que abona en el libro» y «lo que no» sin darse cuenta de que el arqueo lo suma todo.
     */
    public static final class ElCuadreNoSumaElNeto extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ElCuadreNoSumaElNeto(long turnoId, Dinero neto, Dinero cuadre) {
            super(
                    "El arqueo del turno "
                            + turnoId
                            + " dice "
                            + neto
                            + " y las dos mitades del cuadre suman "
                            + cuadre
                            + ": alguien reparte los recibos entre el libro y el papel de una forma"
                            + " que se deja alguno fuera");
        }
    }
}
