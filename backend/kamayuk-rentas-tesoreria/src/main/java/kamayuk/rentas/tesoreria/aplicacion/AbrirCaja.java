package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCajaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abre el turno de un cajero en una ventanilla (#33, RF-080).
 *
 * <p>No tiene endpoint propio: el prototipo no declara una pantalla de apertura —de las diez de
 * Tesoreria, la que toca el turno es «Cierre y arqueo de caja»—, y publicar una ruta que ninguna
 * pantalla llama seria inventar contrato. La apertura ocurre donde tiene que ocurrir: la primera
 * cobranza del dia la hace, y el resto del turno cobra contra ella.
 *
 * <p>Es idempotente porque lo es la base: {@code cierre_uq} hace unico el turno por (caja, cajero,
 * fecha) y {@link TurnoDeCajaRepository#abrir} resuelve el conflicto devolviendo el que ya estaba.
 * Abrir dos veces no crea dos turnos, ni falla.
 */
@Service
public class AbrirCaja {

    private final CajaRepository cajas;
    private final TurnoDeCajaRepository turnos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AbrirCaja(
            CajaRepository cajas, TurnoDeCajaRepository turnos, Auditoria auditoria, Clock reloj) {
        this.cajas = cajas;
        this.turnos = turnos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Deja abierto el turno de ese cajero en esa caja y ese dia, y lo devuelve <b>bloqueado</b>.
     *
     * <p>El bloqueo es lo importante y por eso no se puede separar de la apertura: quien va a
     * cobrar necesita que la ventanilla este serializada desde antes de mirar nada, y devolver aqui
     * un turno sin bloquear obligaria a quien llama a acordarse de bloquearlo despues —que es
     * exactamente la clase de paso que se olvida—.
     *
     * @param fechaDeTrabajo el dia del turno; entra como argumento y no sale del reloj, para que
     *     una cobranza registrada con fecha de ayer no abra un turno de hoy
     * @param observacion por que se abre (regla 10)
     * @throws CajaInexistente si no hay ninguna caja con ese codigo en esta municipalidad
     * @throws CajaDeBaja si la caja existe pero esta dada de baja
     * @throws TurnoCerrado si el turno de ese cajero en ese dia ya se cerro
     */
    @Transactional
    public Abierta enLaCaja(
            String codigoDeCaja, String cajero, LocalDate fechaDeTrabajo, Observacion observacion) {

        Caja caja =
                cajas.porCodigo(codigoDeCaja).orElseThrow(() -> new CajaInexistente(codigoDeCaja));
        if (!caja.activa()) {
            throw new CajaDeBaja(caja);
        }
        long cajaId =
                java.util.Objects.requireNonNull(
                        caja.id(), "Una caja leida del repositorio siempre trae su identificador");

        java.util.Optional<TurnoDeCaja> existente = turnos.bloquear(cajaId, cajero, fechaDeTrabajo);
        TurnoDeCaja turno =
                existente.orElseGet(
                        () ->
                                turnos.abrir(
                                        cajaId,
                                        cajero,
                                        fechaDeTrabajo,
                                        reloj.instant(),
                                        observacion));
        if (!turno.estaAbierto()) {
            throw new TurnoCerrado(caja, cajero, fechaDeTrabajo);
        }

        if (existente.isEmpty()) {
            // Solo la apertura de verdad se audita. Auditar cada cobranza como si abriera
            // la caja llenaria la bitacora de altas que no ocurrieron.
            auditoria.registrar(
                    RegistroDeAuditoria.enLaFechaDe(
                                    fechaDeTrabajo,
                                    "cierre_caja",
                                    String.valueOf(turno.idGuardado()),
                                    Operacion.ALTA,
                                    observacion)
                            .con(null, descripcion(caja, turno)));
        }
        return new Abierta(caja, turno);
    }

    /** Una caja y su turno abierto: lo que la cobranza necesita para emitir. */
    public record Abierta(Caja caja, TurnoDeCaja turno) {}

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Caja caja, TurnoDeCaja turno) {
        return "{\"caja\":\""
                + caja.codigo()
                + "\",\"serie\":\""
                + caja.serie()
                + "\",\"fecha\":\""
                + turno.fecha()
                + "\"}";
    }

    /** No hay ninguna caja con ese codigo en esta municipalidad. */
    public static final class CajaInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CajaInexistente(String codigo) {
            super("No hay ninguna caja con el codigo '" + codigo + "' en esta municipalidad");
        }
    }

    /** La caja existe pero se dio de baja: no se borra, se desactiva (RNF-051). */
    public static final class CajaDeBaja extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CajaDeBaja(Caja caja) {
            super("La caja " + caja.codigo() + " esta dada de baja y no puede cobrar");
        }
    }

    /**
     * El turno de ese cajero ya se cerro: su arqueo esta firmado.
     *
     * <p><b>El mensaje cambio en #36</b>, y el cambio es la correccion de algo que #33 prometia sin
     * poder cumplir. Decia «hay que abrir otro turno, no reabrir este»: no existe tal cosa. {@code
     * cierre_uq} (V3) hace unico el turno por (caja, cajero, fecha), asi que un segundo turno del
     * mismo cajero en el mismo dia y la misma caja es una fila que la base rechaza. La unica salida
     * real es <b>reversar el cierre</b>, que es lo que lo reabre (V32, regla 4).
     */
    public static final class TurnoCerrado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TurnoCerrado(Caja caja, String cajero, LocalDate fecha) {
            super(
                    "El turno de "
                            + cajero
                            + " en la caja "
                            + caja.codigo()
                            + " del "
                            + fecha
                            + " ya se cerro: su arqueo esta firmado y ese dinero no estaria en el."
                            + " Para volver a cobrar hoy hay que reversar ese cierre -eso reabre el"
                            + " turno-, porque un cajero tiene un solo turno al dia por"
                            + " ventanilla");
        }
    }
}
