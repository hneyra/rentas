package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.Exigibilidad;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.NotificacionRepository;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el acto de notificacion de un valor, con su acuse (#39, RF-093).
 *
 * <h2>La fecha de notificacion es la que hace exigible la deuda</h2>
 *
 * <p>De aqui sale {@link Notificacion#exigibleDesde()}, y de ella depende que el expediente
 * coactivo sea valido: sin notificacion, es nulo. La fecha no se calcula con una constante -eso
 * seria la regla 5 rota en el sitio donde mas duele- sino con el plazo que {@link
 * PlazosParametrizados} lee del conjunto sellado <b>vigente a la fecha de la diligencia</b>, y el
 * identificador de ese conjunto queda en la fila.
 *
 * <h2>Un intento no hallado no se corrige: se vuelve a diligenciar</h2>
 *
 * <p>Cada diligencia es una fila con su numero de intento. Registrar la segunda no toca la primera
 * -no hay {@code UPDATE} en este camino, ni privilegio para hacerlo (V28)-, de modo que el
 * expediente conserva la constancia de que se intento antes y no se hallo (AC de #39). El valor
 * solo pasa a {@link EstadoDeValor#NOTIFICADO} cuando una diligencia surte efecto.
 *
 * <h2>Donde se notifica</h2>
 *
 * <p>En el domicilio fiscal <b>vigente a la fecha de la diligencia</b>, que es lo que {@code
 * contribuyentes} publica desde #15: quien mudo en setiembre no cambia la direccion a la que se le
 * notifico en marzo. Quien registra puede dar otra direccion -una notificacion se diligencia a
 * veces en un domicilio procesal-, y entonces se guarda la que dio.
 */
@Service
public class RegistrarNotificacion {

    private final ValorRepository valores;
    private final NotificacionRepository notificaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final PlazosParametrizados plazos;
    private final Auditoria auditoria;

    public RegistrarNotificacion(
            ValorRepository valores,
            NotificacionRepository notificaciones,
            DirectorioDeContribuyentes contribuyentes,
            PlazosParametrizados plazos,
            Auditoria auditoria) {
        this.valores = valores;
        this.notificaciones = notificaciones;
        this.contribuyentes = contribuyentes;
        this.plazos = plazos;
        this.auditoria = auditoria;
    }

    /**
     * Registra una diligencia sobre el valor identificado por su numero.
     *
     * @param numeroDeValor el numero del valor notificado
     * @param fechaDeLaDiligencia cuando se diligencio; es la fecha del hecho, y de ella sale que
     *     conjunto de parametros rige
     * @param modalidad como se diligencio (art. 104)
     * @param resultado con que resultado termino
     * @param notificador quien la llevo
     * @param direccion donde se diligencio; si es {@code null}, el domicilio fiscal vigente a esa
     *     fecha
     * @param receptor quien recibio, si alguien recibio
     * @param documentoReceptor su documento
     * @param vinculo su vinculo con el titular
     * @param acuse la constancia del cargo
     * @param observacion por que se registra (regla 10)
     * @throws ValorInexistente si no hay ningun valor con ese numero
     * @throws SinDomicilio si no se dio direccion y el contribuyente no tiene domicilio a esa fecha
     */
    @Transactional
    public Notificacion registrar(
            String numeroDeValor,
            LocalDate fechaDeLaDiligencia,
            ModalidadDeNotificacion modalidad,
            ResultadoDeNotificacion resultado,
            String notificador,
            @Nullable String direccion,
            @Nullable String receptor,
            @Nullable String documentoReceptor,
            @Nullable String vinculo,
            @Nullable String acuse,
            Observacion observacion) {

        Valor valor = valorDe(numeroDeValor);
        if (fechaDeLaDiligencia.isBefore(valor.fechaEmision())) {
            throw new DiligenciaAnteriorALaEmision(valor, fechaDeLaDiligencia);
        }

        int intento = notificaciones.intentosDe(requireId(valor)) + 1;

        LocalDate exigibleDesde = null;
        Long conjuntoId = null;
        if (resultado.surteEfecto()) {
            PlazosParametrizados.Vigentes vigentes = plazos.aLaFechaDe(fechaDeLaDiligencia);
            Plazo plazo = vigentes.paraNotificar(valor.tipo());
            Exigibilidad exigibilidad =
                    Exigibilidad.derivarDe(fechaDeLaDiligencia, plazo, vigentes.calendario());
            exigibleDesde = exigibilidad.exigibleDesde();
            conjuntoId = vigentes.conjuntoId();
        }

        Notificacion guardada =
                notificaciones.insertar(
                        new Notificacion(
                                null,
                                requireId(valor),
                                numeroDeLaDiligencia(valor, intento),
                                intento,
                                fechaDeLaDiligencia,
                                modalidad,
                                resultado,
                                notificador,
                                direccionDe(valor, direccion, fechaDeLaDiligencia),
                                receptor,
                                documentoReceptor,
                                vinculo,
                                acuse,
                                exigibleDesde,
                                conjuntoId,
                                null,
                                observacion));

        // El valor pasa a NOTIFICADO solo cuando la diligencia surte efecto, y solo si todavia
        // estaba EMITIDO: notificar de nuevo un valor que ya paso a coactiva no lo retrocede.
        if (guardada.surtioEfecto() && valor.estado() == EstadoDeValor.EMITIDO) {
            valores.cambiarEstado(requireId(valor), EstadoDeValor.NOTIFICADO);
        }

        auditar(valor, guardada, observacion);
        return guardada;
    }

    // ------------------------------------------------------------------

    private Valor valorDe(String numero) {
        return valores.porNumero(numero.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ValorInexistente(numero));
    }

    private String direccionDe(Valor valor, @Nullable String dada, LocalDate fecha) {
        if (dada != null && !dada.isBlank()) {
            return dada.strip();
        }
        return contribuyentes
                .domicilioFiscalDe(valor.contribuyenteId(), fecha)
                .orElseThrow(() -> new SinDomicilio(valor, fecha));
    }

    /**
     * El numero de la diligencia, derivado del valor y del intento.
     *
     * <p>Provisional hasta D-09, igual que el correlativo del propio valor: lo unico que se le
     * exige es que no se repita, y eso lo garantiza {@code notificacion_intento_uq} (V28), no este
     * formateo.
     */
    private static String numeroDeLaDiligencia(Valor valor, int intento) {
        return valor.numero() + "/" + intento;
    }

    private static long requireId(Valor valor) {
        Long id = valor.id();
        if (id == null) {
            throw new IllegalStateException("Un valor sin guardar no se puede notificar");
        }
        return id;
    }

    private void auditar(Valor valor, Notificacion notificacion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                notificacion.fechaDeLaDiligencia(),
                                "notificacion",
                                String.valueOf(notificacion.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(valor, notificacion)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Valor valor, Notificacion notificacion) {
        return "{\"valor\":\""
                + valor.numero()
                + "\",\"intento\":"
                + notificacion.intento()
                + ",\"modalidad\":\""
                + notificacion.modalidad()
                + "\",\"resultado\":\""
                + notificacion.resultado()
                + "\",\"exigibleDesde\":"
                + (notificacion.exigibleDesde() == null
                        ? "null"
                        : "\"" + notificacion.exigibleDesde() + "\"")
                + "}";
    }

    /** No hay ningun valor con ese numero. */
    public static final class ValorInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ValorInexistente(String numero) {
            super("No hay ningun valor con el numero '" + numero + "'");
        }
    }

    /** Se diligencio antes de emitir el valor: no puede ser. */
    public static final class DiligenciaAnteriorALaEmision extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DiligenciaAnteriorALaEmision(Valor valor, LocalDate fecha) {
            super(
                    "El valor "
                            + valor.numero()
                            + " se emitio el "
                            + valor.fechaEmision()
                            + ": no se pudo notificar el "
                            + fecha);
        }
    }

    /** No se dio direccion y el contribuyente no tenia domicilio vigente a esa fecha. */
    public static final class SinDomicilio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinDomicilio(Valor valor, LocalDate fecha) {
            super(
                    "El contribuyente del valor "
                            + valor.numero()
                            + " no tenia domicilio fiscal vigente al "
                            + fecha
                            + ", y no se dio uno: no hay donde notificar");
        }
    }
}
