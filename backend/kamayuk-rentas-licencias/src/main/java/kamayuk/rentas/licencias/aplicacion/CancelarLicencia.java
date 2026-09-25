package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja una licencia sin efecto, con su resolucion (#44, RF-111).
 *
 * <h2>No se borra: se cancela</h2>
 *
 * <p>Es el criterio de aceptacion de #44 —«una licencia cancelada no se borra: cambia de estado con
 * su resolucion»— y la regla 4 del proyecto. Aqui eso significa tres cosas concretas:
 *
 * <ul>
 *   <li>La fila de {@code licencia_funcionamiento} <b>no se toca</b>. Ni siquiera se podria: V37 le
 *       retira el {@code UPDATE} y {@code DELETE} nunca lo tuvo.
 *   <li>Lo que se agrega es un {@link MovimientoDeLicencia} de cancelacion, con su fecha, su motivo
 *       y la resolucion que lo sustenta.
 *   <li>El estado <b>se deriva</b> de ese movimiento. No hay ninguna columna que actualizar, asi
 *       que no hay ninguna que se pueda quedar sin actualizar.
 * </ul>
 *
 * <h2>Una sola cancelacion, y lo decide la base</h2>
 *
 * <p>{@code licencia_movimiento_cancelacion_uq} es un indice unico parcial. Se comprueba tambien
 * aqui —para poder responder un mensaje util en el caso normal— pero la garantia no es esa
 * comprobacion: diez peticiones simultaneas pasan las diez por cualquier {@code if}, y el titular
 * acabaria con dos resoluciones de cancelacion de la misma licencia.
 *
 * <h2>Sin recibo</h2>
 *
 * <p>La cancelacion <b>no</b> exige recibo de derecho de tramite, al reves que la emision y el
 * duplicado. No es un olvido: RF-110 pide validar el recibo del tramite de la licencia, y ni el
 * manual ni la ley marco condicionan el cese a un pago. Exigirlo seria inventar un requisito que
 * ninguna norma pone, y dejaria establecimientos cerrados figurando como abiertos —que es peor para
 * la municipalidad que para el administrado, porque le sigue generando arbitrios—.
 */
@Service
public class CancelarLicencia {

    /** El {@code tipo} con que se guarda la resolucion en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "RES_CANCELACION_LICENCIA";

    private final LicenciaRepository licencias;
    private final MovimientoDeLicenciaRepository movimientos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CancelarLicencia(
            LicenciaRepository licencias,
            MovimientoDeLicenciaRepository movimientos,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.licencias = licencias;
        this.movimientos = movimientos;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cancela la licencia y emite la resolucion.
     *
     * @param numeroDeLicencia el numero impreso de la licencia
     * @param fecha el dia de la cancelacion; entra como argumento (regla 6)
     * @param motivo por que se cancela; obligatorio
     * @param formato en que formato sale la resolucion
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws LicenciaInexistente si no hay ninguna licencia con ese numero
     * @throws YaEstabaCancelada si la licencia ya estaba cancelada
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior al ultimo movimiento
     *     registrado o posterior a hoy (#402)
     */
    @Transactional
    public Cancelacion cancelar(
            String numeroDeLicencia,
            LocalDate fecha,
            String motivo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha de la cancelacion entra como argumento (regla 6)");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale la resolucion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new SinMotivo();
        }

        LicenciaDeFuncionamiento licencia =
                licencias
                        .porNumero(numeroDeLicencia)
                        .orElseThrow(() -> new LicenciaInexistente(numeroDeLicencia));

        List<MovimientoDeLicencia> historial = movimientos.deLicencia(licencia.identificador());
        exigirEnOrden(licencia, historial, fecha);

        EstadoDeLicencia actual =
                EstadoDeLicencia.derivarDe(historial, licencia.vigenciaHasta(), fecha);
        if (actual == EstadoDeLicencia.CANCELADA) {
            throw new YaEstabaCancelada(licencia.numero());
        }

        ResumenDeContribuyente titular = titularDe(licencia);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(fecha),
                        licencia.numero(),
                        ModeloDeLaLicencia.deLaCancelacion(
                                licencia, titular.nombre(), titular.codigo(), fecha, limpio),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        Instant ahora = reloj.instant();
        MovimientoDeLicencia registrado =
                movimientos.registrar(
                        MovimientoDeLicencia.cancelacion(
                                licencia.identificador(),
                                fecha,
                                limpio,
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "licencia_movimiento",
                                String.valueOf(registrado.identificador()),
                                Operacion.BAJA,
                                observacion)
                        .con(null, descripcion(licencia, registrado)));

        return new Cancelacion(licencia, registrado, emision);
    }

    /**
     * #402: la cancelacion no se fecha antes del <b>ultimo</b> movimiento registrado ni despues de
     * hoy. Es el mismo patron que el cese de un anuncio: el estado se deriva a la fecha del acto y
     * se salta lo posterior, asi que compararla solo con la emision —lo unico que se miraba hasta
     * #402, con una excepcion propia que se retira— deja pasar una resolucion fechada antes de un
     * acto que ya consta. La emision va tambien, por si una licencia migrada no trae su movimiento.
     */
    private void exigirEnOrden(
            LicenciaDeFuncionamiento licencia,
            List<MovimientoDeLicencia> historial,
            LocalDate fecha) {
        List<OrdenDeLosActos.ActoPrevio> previos = new ArrayList<>();
        previos.add(
                new OrdenDeLosActos.ActoPrevio(
                        "la emision de la licencia " + licencia.numero(), licencia.fechaEmision()));
        historial.stream()
                .max(Comparator.comparing(MovimientoDeLicencia::fecha))
                .ifPresent(
                        ultimo ->
                                previos.add(
                                        new OrdenDeLosActos.ActoPrevio(
                                                "el ultimo movimiento de "
                                                        + licencia.numero()
                                                        + " ("
                                                        + ultimo.tipo().titulo()
                                                        + ")",
                                                ultimo.fecha())));
        OrdenDeLosActos.exigir(
                "la cancelacion de " + licencia.numero(), fecha, LocalDate.now(reloj), previos);
    }

    private ResumenDeContribuyente titularDe(LicenciaDeFuncionamiento licencia) {
        Map<Long, ResumenDeContribuyente> padron =
                contribuyentes.porIds(Set.of(licencia.contribuyenteId()));
        ResumenDeContribuyente titular = padron.get(licencia.contribuyenteId());
        if (titular == null) {
            throw new IllegalStateException(
                    "La licencia "
                            + licencia.numero()
                            + " es de un contribuyente que el padron ya no tiene");
        }
        return titular;
    }

    private static String descripcion(
            LicenciaDeFuncionamiento licencia, MovimientoDeLicencia movimiento) {
        return "{\"licencia\":\""
                + licencia.numero()
                + "\",\"estado\":\"CANCELADA\",\"resolucion\":\""
                + movimiento.documentoNumero()
                + "\",\"fecha\":\""
                + movimiento.fecha()
                + "\"}";
    }

    // ------------------------------------------------------------------

    /** Lo que la cancelacion produjo. */
    public record Cancelacion(
            LicenciaDeFuncionamiento licencia,
            MovimientoDeLicencia movimiento,
            EmitirDocumento.Emision resolucion) {}

    /** No hay ninguna licencia con ese numero en esta municipalidad. */
    public static final class LicenciaInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LicenciaInexistente(String numero) {
            super("No hay ninguna licencia " + numero + " en esta municipalidad");
        }
    }

    /** La licencia ya estaba cancelada: el estado actual no admite cancelarla otra vez. */
    public static final class YaEstabaCancelada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        YaEstabaCancelada(String numero) {
            super(
                    "La licencia "
                            + numero
                            + " ya esta cancelada: una segunda resolucion de cancelacion sobre la"
                            + " misma licencia se contradice con la primera");
        }
    }

    /** Una cancelacion sin motivo no explica nada. */
    public static final class SinMotivo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinMotivo() {
            super(
                    "La resolucion de cancelacion lleva el motivo por el que la licencia queda sin"
                            + " efecto; sin el, el administrado no puede impugnarla");
        }
    }
}
