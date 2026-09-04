package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.EstadoDelAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cesa una autorizacion de anuncio y registra el retiro del elemento (#51, RF-114).
 *
 * <h2>El cese detiene la deuda futura y no borra la pasada</h2>
 *
 * <p>Es el tercer criterio de aceptacion de #51, y aqui se ve en lo que esta clase <b>no</b> hace:
 * no llama a {@code cuentacorriente}, no reversa ningun asiento y no borra ninguna fila. Lo unico
 * que hace es agregar un {@link MovimientoDeAnuncio} de cese. Sus dos consecuencias son
 * automaticas:
 *
 * <ul>
 *   <li><b>Deuda futura:</b> {@code RenovarAnuncio} deriva el estado antes de renovar y CESADO no
 *       admite renovacion, que es la unica via por la que un anuncio devenga otra tasa.
 *   <li><b>Deuda pasada:</b> sigue donde estaba. El libro es inmutable desde V2 y {@code anuncio} y
 *       {@code anuncio_movimiento} entran en las tablas protegidas del escaner de fuentes. Un
 *       anuncio que estuvo autorizado tres años debe tres tasas, lo hayan cesado o no; cesar no es
 *       condonar, y condonar es otro acto, de otro modulo y con otra firma.
 * </ul>
 *
 * <h2>Un cese y un retiro, y lo decide la base</h2>
 *
 * <p>{@code anuncio_movimiento_cese_uq} y {@code anuncio_movimiento_retiro_uq} son indices unicos
 * parciales. Se comprueba tambien aqui —para poder responder un mensaje util en el caso normal—
 * pero la garantia no es esa comprobacion: diez peticiones simultaneas pasan las diez por cualquier
 * {@code if}.
 *
 * <h2>El retiro va despues del cese, y no al reves</h2>
 *
 * <p>Cesar es el acto administrativo; retirar es el hecho fisico que lo sigue. Registrar un retiro
 * sin cese previo diria que se desmonto un anuncio que sigue autorizado, y el fiscalizador que lea
 * el padron no sabria si eso es una infraccion o un error de tecleo.
 */
@Service
public class CesarAnuncio {

    private static final String TABLA_AUDITADA = "anuncio_movimiento";

    private final AnuncioRepository anuncios;
    private final MovimientoDeAnuncioRepository movimientos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CesarAnuncio(
            AnuncioRepository anuncios,
            MovimientoDeAnuncioRepository movimientos,
            Auditoria auditoria,
            Clock reloj) {
        this.anuncios = anuncios;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cesa la autorizacion.
     *
     * @param numeroDeAutorizacion el numero impreso de la autorizacion
     * @param fecha el dia del cese; entra como argumento (regla 6)
     * @param motivo por que se cesa; obligatorio
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws RenovarAnuncio.AnuncioInexistente si no hay ninguna autorizacion con ese numero
     * @throws YaEstabaCesado si ya estaba cesada o retirada
     * @throws SinMotivo si no se dice por que
     */
    @Transactional
    public Acto cesar(
            String numeroDeAutorizacion, LocalDate fecha, String motivo, Observacion observacion) {
        return registrar(numeroDeAutorizacion, fecha, motivo, observacion, /* esRetiro= */ false);
    }

    /**
     * Registra que el elemento se retiro de la calle, comprobado en campo.
     *
     * @throws SinCesePrevio si la autorizacion no esta cesada a esa fecha
     */
    @Transactional
    public Acto retirar(
            String numeroDeAutorizacion, LocalDate fecha, String motivo, Observacion observacion) {
        return registrar(numeroDeAutorizacion, fecha, motivo, observacion, /* esRetiro= */ true);
    }

    // ------------------------------------------------------------------

    private Acto registrar(
            String numeroDeAutorizacion,
            LocalDate fecha,
            String motivo,
            Observacion observacion,
            boolean esRetiro) {

        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new SinMotivo(esRetiro);
        }

        Anuncio anuncio =
                anuncios.porNumero(numeroDeAutorizacion)
                        .orElseThrow(
                                () -> new RenovarAnuncio.AnuncioInexistente(numeroDeAutorizacion));

        List<MovimientoDeAnuncio> historial = movimientos.deAnuncio(anuncio.identificador());
        EstadoDelAnuncio actual =
                EstadoDelAnuncio.derivarDe(
                        historial, EstadoDelAnuncio.vigenciaSegun(historial, fecha), fecha);

        if (esRetiro) {
            if (actual == EstadoDelAnuncio.RETIRADO) {
                throw new YaEstabaCesado(anuncio.numero(), actual, true);
            }
            if (actual != EstadoDelAnuncio.CESADO) {
                throw new SinCesePrevio(anuncio.numero(), actual, fecha);
            }
        } else if (actual == EstadoDelAnuncio.CESADO || actual == EstadoDelAnuncio.RETIRADO) {
            throw new YaEstabaCesado(anuncio.numero(), actual, false);
        }

        if (fecha.isBefore(anuncio.fechaAutorizacion())) {
            throw new RenovarAnuncio.AnteriorALaAutorizacion(
                    anuncio.numero(), anuncio.fechaAutorizacion(), fecha);
        }

        Instant ahora = reloj.instant();
        MovimientoDeAnuncio registrado =
                movimientos.registrar(
                        esRetiro
                                ? MovimientoDeAnuncio.retiro(
                                        anuncio.identificador(), fecha, limpio, ahora, observacion)
                                : MovimientoDeAnuncio.cese(
                                        anuncio.identificador(),
                                        fecha,
                                        limpio,
                                        ahora,
                                        observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                TABLA_AUDITADA,
                                String.valueOf(registrado.identificador()),
                                Operacion.BAJA,
                                observacion)
                        .con(null, descripcion(anuncio, registrado)));

        return new Acto(anuncio, registrado);
    }

    private static String descripcion(Anuncio anuncio, MovimientoDeAnuncio movimiento) {
        return "{\"numero\":\""
                + anuncio.numero()
                + "\",\"acto\":\""
                + movimiento.tipo()
                + "\",\"fecha\":\""
                + movimiento.fecha()
                + "\"}";
    }

    // ------------------------------------------------------------------

    /** Lo que el cese o el retiro produjeron. */
    public record Acto(Anuncio anuncio, MovimientoDeAnuncio movimiento) {}

    /** La autorizacion ya estaba cesada: el estado actual no admite cesarla otra vez. */
    public static final class YaEstabaCesado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        YaEstabaCesado(String numero, EstadoDelAnuncio estado, boolean esRetiro) {
            super(
                    "La autorizacion "
                            + numero
                            + " ya esta "
                            + estado
                            + (esRetiro
                                    ? ": el elemento ya consta retirado"
                                    : ": un segundo cese sobre la misma autorizacion se contradice"
                                            + " con el primero"));
        }
    }

    /** No se puede retirar lo que sigue autorizado. */
    public static final class SinCesePrevio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCesePrevio(String numero, EstadoDelAnuncio estado, LocalDate fecha) {
            super(
                    "La autorizacion "
                            + numero
                            + " esta "
                            + estado
                            + " al "
                            + fecha
                            + ": primero se cesa la autorizacion y despues se constata el retiro"
                            + " del elemento. Al reves, el padron diria que se desmonto un anuncio"
                            + " que sigue autorizado");
        }
    }

    /** Un cese o un retiro sin motivo no explican nada. */
    public static final class SinMotivo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinMotivo(boolean esRetiro) {
            super(
                    (esRetiro ? "El retiro" : "El cese")
                            + " lleva el motivo por el que la autorizacion deja de regir; sin el,"
                            + " el administrado no puede impugnarlo y el padron no explica por que"
                            + " el anuncio dejo de devengar tasa");
        }
    }
}
