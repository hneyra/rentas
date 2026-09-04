package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autoriza un duplicado de una licencia y vuelve a sacar el papel (#44, RF-111).
 *
 * <h2>El duplicado conserva el numero original</h2>
 *
 * <p>Es el criterio de aceptacion de #44, y no se cumple con una convencion: se cumple con {@code
 * EmitirDocumento.reimprimir}, que <b>vuelve a dibujar los datos guardados</b> del documento
 * original, comprueba que el SHA-256 sigue coincidiendo y le pone la marca {@code DUPLICADO N.o k}.
 *
 * <p>De ahi salen las dos mitades del criterio a la vez:
 *
 * <ul>
 *   <li><b>Conserva el numero</b> porque no se emite un documento nuevo: se reimprime el que ya
 *       existe, con su numero, su ejercicio y sus datos —incluido el numero de la licencia, que no
 *       cambia—.
 *   <li><b>Se identifica como duplicado</b> porque la marca la pone el propio generador, no el
 *       llamador. Un duplicado sin marcar circularia como si fuera el original, y en un local con
 *       dos licencias identicas eso es un papel de mas.
 * </ul>
 *
 * <p>Y si alguien cambiara el renderizador entre la emision y el duplicado, la reimpresion
 * <b>falla</b> en vez de entregar un papel distinto con el mismo numero.
 *
 * <h2>Dos papeles, y son dos actos distintos</h2>
 *
 * <p>Sale una <b>resolucion de duplicado</b> —documento nuevo, con su propio numero, que es lo que
 * la opcion {@code licencia_resolucion_duplicado} imprime— y ademas la <b>licencia reimpresa</b>
 * con su marca. La resolucion autoriza; la licencia reimpresa es lo que el titular cuelga. Emitir
 * solo una de las dos dejaria o un duplicado sin acto que lo autorice, o un acto sin el papel que
 * autoriza.
 *
 * <h2>El derecho de tramite tambien se paga</h2>
 *
 * <p>Con su propio concepto del TUPA, que sale del conjunto sellado igual que el de la emision. Un
 * duplicado es un procedimiento del TUPA como cualquier otro.
 */
@Service
public class DuplicarLicencia {

    /** El {@code tipo} con que se guarda la resolucion de duplicado. */
    public static final String TIPO_DE_DOCUMENTO = "RES_DUPLICADO_LICENCIA";

    private final LicenciaRepository licencias;
    private final MovimientoDeLicenciaRepository movimientos;
    private final DuplicadoDeLicenciaRepository duplicados;
    private final RecibosDeTramite recibos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final DerechosDeTramiteParametrizados derechos;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public DuplicarLicencia(
            LicenciaRepository licencias,
            MovimientoDeLicenciaRepository movimientos,
            DuplicadoDeLicenciaRepository duplicados,
            RecibosDeTramite recibos,
            DirectorioDeContribuyentes contribuyentes,
            DerechosDeTramiteParametrizados derechos,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.licencias = licencias;
        this.movimientos = movimientos;
        this.duplicados = duplicados;
        this.recibos = recibos;
        this.contribuyentes = contribuyentes;
        this.derechos = derechos;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Autoriza el duplicado, emite su resolucion y reimprime la licencia.
     *
     * @param numeroDeLicencia el numero impreso de la licencia original
     * @param fecha el dia de la autorizacion; entra como argumento (regla 6)
     * @param motivo por que se pide: extravio, deterioro, robo
     * @param numeroDeRecibo el recibo del derecho de tramite del duplicado
     * @param formato en que formato salen los dos papeles
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws CancelarLicencia.LicenciaInexistente si no hay licencia con ese numero
     * @throws LicenciaCancelada si la licencia esta cancelada
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho
     */
    @Transactional
    public Duplicado duplicar(
            String numeroDeLicencia,
            LocalDate fecha,
            String motivo,
            String numeroDeRecibo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha del duplicado entra como argumento (regla 6)");
        Objects.requireNonNull(formato, "Hay que decir en que formato salen los papeles");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        LicenciaDeFuncionamiento licencia =
                licencias
                        .porNumero(numeroDeLicencia)
                        .orElseThrow(
                                () -> new CancelarLicencia.LicenciaInexistente(numeroDeLicencia));

        List<MovimientoDeLicencia> historial = movimientos.deLicencia(licencia.identificador());
        if (EstadoDeLicencia.derivarDe(historial, licencia.vigenciaHasta(), fecha)
                == EstadoDeLicencia.CANCELADA) {
            throw new LicenciaCancelada(licencia.numero());
        }

        ResumenDeContribuyente titular = titularDe(licencia);

        String concepto = derechos.aLaFechaDe(fecha).paraElDuplicado();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        numeroDeRecibo,
                        licencia.contribuyenteId(),
                        concepto,
                        "duplicado de licencia de funcionamiento");

        // LA REIMPRESION VA PRIMERO, y el orden importa: si el papel original ya no se dibuja
        // igual, `reimprimir` lanza y no se autoriza ningun duplicado. Al reves —registrar el
        // duplicado y luego intentar el papel— dejaria un acto autorizado sin nada que entregar,
        // salvo que la transaccion lo deshaga; y depender de eso es depender de que nadie escriba
        // un `catch` en medio.
        EmitirDocumento.Emision reimpresion =
                documentos.reimprimir(
                        EmitirLicenciaDeFuncionamiento.TIPO_DE_DOCUMENTO,
                        Ejercicio.de(licencia.fechaEmision()),
                        numeroDelPapelDe(historial, licencia),
                        formato,
                        observacion);

        int ordinal = duplicados.cuantosDe(licencia.identificador()) + 1;
        Instant ahora = reloj.instant();

        DuplicadoDeLicencia sinGuardar =
                new DuplicadoDeLicencia(
                        null,
                        licencia.identificador(),
                        ordinal,
                        fecha,
                        motivo,
                        recibo.reciboId(),
                        // Se rellena abajo con la resolucion recien emitida, igual que la licencia
                        // con su papel: la resolucion se dibuja con los datos del duplicado.
                        0L,
                        reimpresion.registro().reimpresiones(),
                        ahora,
                        null,
                        observacion);

        EmitirDocumento.Emision resolucion =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(fecha),
                        licencia.numero(),
                        ModeloDeLaLicencia.delDuplicado(
                                licencia,
                                titular.nombre(),
                                titular.codigo(),
                                sinGuardar,
                                recibo.numero()),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        resolucion.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        DuplicadoDeLicencia registrado =
                duplicados.registrar(conDocumento(sinGuardar, documentoId));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "licencia_duplicado",
                                String.valueOf(registrado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(licencia, registrado, resolucion)));

        return new Duplicado(licencia, registrado, resolucion, reimpresion);
    }

    /**
     * El numero del papel de la licencia, para poder reimprimirlo.
     *
     * <p>Sale del movimiento de EMISION, que lo copio al registrarse. Es exactamente para lo que
     * {@code licencia_movimiento.documento_numero} existe: {@code licencia_funcionamiento} guarda
     * el <b>identificador</b> del documento —que es lo que sostiene la integridad— y el numero
     * impreso vive en el movimiento, de modo que reimprimir no necesita cruzar a {@code
     * documento_emitido} para preguntar como se llamaba su propio papel.
     */
    private static String numeroDelPapelDe(
            List<MovimientoDeLicencia> historial, LicenciaDeFuncionamiento licencia) {
        return historial.stream()
                .filter(
                        movimiento ->
                                movimiento.tipo()
                                        == kamayuk.rentas.licencias.dominio
                                                .TipoDeMovimientoDeLicencia.EMISION)
                .map(MovimientoDeLicencia::documentoNumero)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La licencia "
                                                + licencia.numero()
                                                + " no tiene movimiento de emision: no se puede"
                                                + " reimprimir un papel que nadie registro"));
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

    private static DuplicadoDeLicencia conDocumento(
            DuplicadoDeLicencia duplicado, long documentoId) {
        return new DuplicadoDeLicencia(
                duplicado.id(),
                duplicado.licenciaId(),
                duplicado.numero(),
                duplicado.fecha(),
                duplicado.motivo(),
                duplicado.reciboId(),
                documentoId,
                duplicado.reimpresion(),
                duplicado.registradoEn(),
                duplicado.usuarioRegistro(),
                duplicado.observacion());
    }

    private static String descripcion(
            LicenciaDeFuncionamiento licencia,
            DuplicadoDeLicencia duplicado,
            EmitirDocumento.Emision resolucion) {
        return "{\"licencia\":\""
                + licencia.numero()
                + "\",\"duplicado\":"
                + duplicado.numero()
                + ",\"resolucion\":\""
                + resolucion.registro().numero()
                + "\",\"reimpresion\":"
                + duplicado.reimpresion()
                + "}";
    }

    // ------------------------------------------------------------------

    /**
     * Lo que el duplicado produjo.
     *
     * @param licencia la licencia original, con su numero intacto
     * @param duplicado la fila registrada
     * @param resolucion la resolucion que lo autoriza
     * @param reimpresion la licencia vuelta a dibujar, con su marca de duplicado
     */
    public record Duplicado(
            LicenciaDeFuncionamiento licencia,
            DuplicadoDeLicencia duplicado,
            EmitirDocumento.Emision resolucion,
            EmitirDocumento.Emision reimpresion) {

        /**
         * El numero de la licencia reimpresa, que <b>es</b> el de la original.
         *
         * <p>Se expone como metodo para que la prueba del criterio de aceptacion pueda compararlo
         * sin conocer la estructura del documento.
         */
        public String numeroDelPapel() {
            return reimpresion.registro().numero();
        }
    }

    /** La licencia esta cancelada: no hay original vigente del que sacar copia. */
    public static final class LicenciaCancelada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LicenciaCancelada(String numero) {
            super(
                    "La licencia "
                            + numero
                            + " esta cancelada: un duplicado de una licencia sin efecto acreditaria"
                            + " una autorizacion que ya no existe");
        }
    }
}
