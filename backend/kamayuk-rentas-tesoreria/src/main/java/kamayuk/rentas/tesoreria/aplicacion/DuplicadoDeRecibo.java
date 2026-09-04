package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeReciboRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reimprime un recibo ya emitido, marcado como duplicado (#34, RF-082).
 *
 * <h2>Identico al original, meses despues</h2>
 *
 * <p>No se recalcula nada: cada cifra sale del desglose que la cobranza congelo en {@code
 * recibo_detalle}, y la fecha del papel es {@link Recibo#actualizadoA}, la fecha a la que estaban
 * actualizados los importes cobrados —nunca el dia en que se pide la reimpresion (regla 9)—. Volver
 * a preguntarle al libro daria un papel distinto cada vez.
 *
 * <p>Y no se afirma: se <b>comprueba</b>. El primer duplicado guarda el SHA-256 de lo congelado; el
 * segundo lo vuelve a calcular y, si no coincide, <b>falla</b> en vez de entregar un papel distinto
 * al original con el mismo numero. Es la misma garantia que {@code EmitirDocumento} le da a un
 * valor (V15), aplicada aqui sobre {@code recibo_movimiento}.
 *
 * <h2>Por que el recibo no pasa por {@code documento_emitido}</h2>
 *
 * <p>Porque su duplicado tiene que decir si el recibo esta <b>anulado</b>, y eso ocurre despues de
 * emitirlo. {@code documento_emitido} archiva un modelo y un disparador impide cambiarlo, asi que
 * un recibo archivado no podria anunciar su propia anulacion. Y porque el recibo ya tiene
 * numeracion correlativa propia ({@code recibo_correlativo}, V29): archivarlo daria un segundo
 * numero para el mismo papel.
 *
 * <h2>El duplicado deja rastro</h2>
 *
 * <p>Cada reimpresion agrega su fila —«queda registrado en la bitacora con el usuario que lo
 * genero», dice la pantalla— y de contarlas sale el {@code DUPLICADO N.° 3} que el papel lleva
 * impreso. Un recibo de caja reimpreso sin marca y sin rastro circula como si fuera el original.
 *
 * <p>Por eso {@link #imprimir} escribe, aunque el verbo de la ruta sea {@code GET}: el verbo lo
 * fija el prototipo, y el manual exige el registro. La <b>vista previa</b> —{@link #consultar}— es
 * la que no escribe: mira, no emite.
 */
@Service
public class DuplicadoDeRecibo {

    /** El formato con el que se calcula el resumen, sea cual sea el que se pida imprimir. */
    private static final FormatoDeDocumento FORMATO_DEL_RESUMEN = FormatoDeDocumento.PDF;

    private final ReciboRepository recibos;
    private final MovimientoDeReciboRepository movimientos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final GeneradorDeDocumentos generador;
    private final Auditoria auditoria;
    private final Clock reloj;

    public DuplicadoDeRecibo(
            ReciboRepository recibos,
            MovimientoDeReciboRepository movimientos,
            DirectorioDeContribuyentes contribuyentes,
            GeneradorDeDocumentos generador,
            Auditoria auditoria,
            Clock reloj) {
        this.recibos = recibos;
        this.movimientos = movimientos;
        this.contribuyentes = contribuyentes;
        this.generador = generador;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * El recibo tal como quedo, sin emitir nada.
     *
     * <p>Es la «vista previa» de la pantalla. No escribe: mirar un recibo no es reimprimirlo, y
     * numerar un duplicado cada vez que alguien abre la pantalla llenaria la bitacora de
     * reimpresiones que nunca salieron por la impresora.
     */
    @Transactional(readOnly = true)
    public Optional<Consultado> consultar(NumeroDeRecibo numero) {
        return recibos.porNumero(numero)
                .map(
                        recibo -> {
                            long id = Objects.requireNonNull(recibo.id());
                            return new Consultado(
                                    recibo,
                                    movimientos.anulacionDe(id).orElse(null),
                                    movimientos.duplicadosDe(id));
                        });
    }

    /**
     * Dibuja el duplicado y lo registra.
     *
     * <p>La {@link Observacion} va en la firma: esto escribe, y la regla 10 no tiene una excepcion
     * para las escrituras pequenas. Quien pide el duplicado dice por que lo pide.
     *
     * @param formato en cual de los tres formatos se quiere el papel (RF-132)
     * @throws ReciboInexistente si no hay ningun recibo con ese numero en esta municipalidad
     * @throws LaReimpresionNoCoincide si dibujar lo congelado ya no da los mismos bytes
     */
    @Transactional
    public Duplicado imprimir(
            NumeroDeRecibo numero, FormatoDeDocumento formato, Observacion observacion) {
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el duplicado");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Recibo recibo = recibos.porNumero(numero).orElseThrow(() -> new ReciboInexistente(numero));
        long reciboId =
                Objects.requireNonNull(recibo.id(), "Un recibo leido trae su identificador");

        // El resumen se calcula sobre LO CONGELADO -sin el nombre del contribuyente, que
        // viene del padron de hoy y no del recibo-, para que cubra exactamente lo que
        // tiene que salir identico: todas las cifras y todo el desglose.
        String resumen =
                generador.resumenDe(ModeloDelRecibo.de(recibo, null, null), FORMATO_DEL_RESUMEN);
        exigirQueSalgaIgual(numero, reciboId, resumen);

        @Nullable MovimientoDeRecibo anulacion = movimientos.anulacionDe(reciboId).orElse(null);
        int cual = (int) movimientos.duplicadosDe(reciboId) + 1;

        ModeloDeDocumento impreso =
                ModeloDelRecibo.de(recibo, titularDe(recibo), anulacion).comoDuplicado(cual);
        byte[] documento = generador.generar(impreso, formato);

        MovimientoDeRecibo registrado =
                movimientos.registrar(
                        MovimientoDeRecibo.duplicado(
                                recibo, LocalDate.now(reloj), resumen, observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "recibo_movimiento",
                                String.valueOf(registrado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(recibo, cual, formato)));

        return new Duplicado(recibo, anulacion, cual, formato, documento);
    }

    // ------------------------------------------------------------------

    /**
     * Comprueba que dibujar lo congelado sigue dando los mismos bytes que la primera vez.
     *
     * <p>Es lo unico que convierte «la reimpresion sale identica al original» en una afirmacion
     * comprobable. Si alguien cambia el renderizador —una fuente, un margen— o hace que el modelo
     * lea algo que no esta congelado, esto salta en el segundo duplicado en vez de entregar en
     * silencio un papel distinto al original con el mismo numero.
     *
     * <p>Se compara con el <b>primer</b> duplicado y no con el ultimo: si algo se movio entre el
     * primero y el segundo, el que hay que reproducir es el primero.
     */
    private void exigirQueSalgaIgual(NumeroDeRecibo numero, long reciboId, String ahora) {
        List<MovimientoDeRecibo> anteriores = movimientos.deRecibo(reciboId);
        for (MovimientoDeRecibo movimiento : anteriores) {
            String antes = movimiento.resumen();
            if (antes != null && !antes.equals(ahora)) {
                throw new LaReimpresionNoCoincide(numero, antes, ahora);
            }
        }
    }

    private @Nullable ResumenDeContribuyente titularDe(Recibo recibo) {
        Map<Long, ResumenDeContribuyente> encontrados =
                contribuyentes.porIds(Set.of(recibo.contribuyenteId()));
        return encontrados.get(recibo.contribuyenteId());
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Recibo recibo, int cual, FormatoDeDocumento formato) {
        return "{\"numero\":\""
                + recibo.numero().impreso()
                + "\",\"duplicado\":"
                + cual
                + ",\"formato\":\""
                + formato
                + "\",\"actualizadoA\":\""
                + recibo.actualizadoA()
                + "\"}";
    }

    /**
     * Un recibo y su estado, sin emitir nada.
     *
     * @param anulacion la anulacion, si la hubo; es de donde sale el estado efectivo
     * @param duplicados cuantas veces se ha reimpreso ya
     */
    public record Consultado(
            Recibo recibo, @Nullable MovimientoDeRecibo anulacion, long duplicados) {

        public boolean estaAnulado() {
            return anulacion != null;
        }
    }

    /**
     * El duplicado dibujado.
     *
     * @param cual que numero de duplicado es; el primero es 1
     * @param contenido los bytes del documento
     */
    public record Duplicado(
            Recibo recibo,
            @Nullable MovimientoDeRecibo anulacion,
            int cual,
            FormatoDeDocumento formato,
            byte[] contenido) {

        public boolean estaAnulado() {
            return anulacion != null;
        }

        public String nombreDeArchivo() {
            return formato.nombreDeArchivo("recibo-" + recibo.numero().impreso());
        }
    }

    /** No hay ningun recibo con ese numero en esta municipalidad. */
    public static final class ReciboInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ReciboInexistente(NumeroDeRecibo numero) {
            super("No hay ningun recibo " + numero.impreso() + " en esta municipalidad");
        }
    }

    /** Dibujar lo congelado ya no da los mismos bytes que la primera reimpresion. */
    public static final class LaReimpresionNoCoincide extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LaReimpresionNoCoincide(NumeroDeRecibo numero, String antes, String ahora) {
            super(
                    "El recibo "
                            + numero.impreso()
                            + " ya no se dibuja igual que en su primer duplicado: el resumen era "
                            + antes.substring(0, 12)
                            + "… y ahora es "
                            + ahora.substring(0, 12)
                            + "…. Entregar esto seria dar un papel distinto al original con el"
                            + " mismo numero");
        }
    }
}
