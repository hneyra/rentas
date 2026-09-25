package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Service;

/**
 * Otorga la licencia de edificacion de un FUE ya completo (#48 AC 1 y AC 5, RF-113).
 *
 * <h2>Solo se emite cuando estan las secciones obligatorias (AC 1)</h2>
 *
 * <p>Es la otra mitad del AC 1, y la que importa: completar por partes solo sirve si alguien
 * comprueba que las partes estan. Se comprueban <b>todas de golpe</b> y el error dice <b>cuales
 * faltan</b>, no la primera: quien atiende en ventanilla tiene que poder decirle al administrado
 * todo lo que le falta en una sola frase, y no descubrirlo de una en una en cinco viajes.
 *
 * <h2>Sin el derecho pagado no se emite (AC 5)</h2>
 *
 * <p>Mismo mecanismo que la licencia de funcionamiento en #44: el <b>concepto</b> del TUPA sale del
 * conjunto sellado ({@link DerechosDeTramiteParametrizados}) y el recibo se comprueba contra la API
 * publica de {@code tesoreria} ({@link ComprobacionDelDerecho}) —que exista, que sea de caja de
 * tasas, que no este anulado, que sea del titular y que cubra ese concepto—. El <b>importe</b> no
 * esta aqui ni tiene por que: vive en la tabla {@code tasa} desde V3, con su ordenanza y su
 * vigencia.
 *
 * <h2>La valorizacion se calcula, no se guarda (AC 2)</h2>
 *
 * <p>El papel lleva el valor de obra, y ese valor se valoriza en el acto contra el cuadro de #17.
 * Si el cuadro sellado no lo permite —D-02a—, el papel imprime «—» con su motivo y la licencia se
 * emite igual: la estructura del FUE no espera a ninguna cifra, que es lo que #48 separa de #197.
 *
 * <h2>Nada de esto toca la licencia original de una ampliacion (AC 3)</h2>
 *
 * <p>Una ampliacion es <b>este</b> expediente: numera su propia licencia y recibe su propia
 * vigencia. La original ni se lee para escribirla ni se podria escribir —V43 le retira el {@code
 * UPDATE} a {@code licencia_edificacion}—.
 *
 * <h2>Esta clase pregunta; la que lee y escribe la base es otra (#450)</h2>
 *
 * <p>Hasta #450 este metodo era {@code @Transactional} entero: {@code normativa} y {@code caja} se
 * preguntaban con la conexion de la peticion tomada, y la valorizacion —que pide el cuadro a {@code
 * catastro}— salia <b>despues</b> de numerar, con el candado de {@code edificacion_correlativo}
 * tomado. Ahora esta clase <b>no abre transaccion</b>: pide a {@link
 * RegistrarLicenciaDeEdificacion} el expediente comprobado, pregunta a los tres vecinos, y le
 * devuelve lo que contestaron para que escriba. Es el reparto de {@link ConsultaDeFue}, que ya
 * pedia el cuadro fuera de la transaccion por el <i>rollback-only</i>; aqui el motivo es el pool.
 */
@Service
public class EmitirLicenciaDeEdificacion {

    /** El {@code tipo} con que se guarda el papel en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "LICENCIA_EDIFICACION";

    private final RegistrarLicenciaDeEdificacion registro;
    private final RecibosDeTramite recibos;
    private final DerechosDeTramiteParametrizados derechos;
    private final ValorizacionDelFue valorizaciones;

    public EmitirLicenciaDeEdificacion(
            RegistrarLicenciaDeEdificacion registro,
            RecibosDeTramite recibos,
            DerechosDeTramiteParametrizados derechos,
            ValorizacionDelFue valorizaciones) {
        this.registro = registro;
        this.recibos = recibos;
        this.derechos = derechos;
        this.valorizaciones = valorizaciones;
    }

    /**
     * Emite la licencia.
     *
     * <p><b>Sin {@code @Transactional}, y hace falta que no lo tenga</b> (#450): aqui se pregunta a
     * los vecinos. La {@link Observacion} viaja hasta {@link RegistrarLicenciaDeEdificacion}, que
     * es donde se escribe: la regla 10 la busca en los parametros del metodo transaccional.
     *
     * @param expediente el numero del expediente del FUE
     * @param fechaDeEmision el dia del acto; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando rige la licencia. <b>Entra como dato del acto y no se
     *     calcula</b>: el plazo lo fija la Ley 29090 con una cifra, y ninguna cifra normativa se
     *     compila (regla 5). Lo que el sistema si impone es que no termine antes de empezar.
     * @param numeroDeRecibo el recibo de caja de tasas del derecho, como esta en el papel
     * @throws ExpedienteInexistente si no hay ningun expediente con ese numero
     * @throws TramiteQueNoOtorgaLicencia si el tramite no produce licencia
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior a la declaracion o
     *     posterior a hoy (#402)
     * @throws SeccionesIncompletas si falta alguna seccion obligatoria (AC 1)
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho (AC 5)
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya pago otro acto (#383)
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto del TUPA cobra el derecho
     */
    public LicenciaEmitida emitir(
            String expediente,
            LocalDate fechaDeEmision,
            LocalDate vigenciaHasta,
            String numeroDeRecibo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(
                fechaDeEmision, "La fecha de emision entra como argumento (regla 6)");
        Objects.requireNonNull(vigenciaHasta, "La licencia dice hasta cuando rige");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        // Primero la base, en su transaccion de solo lectura: si al expediente le faltan
        // secciones, no hay recibo que comprobar ni obra que valorizar.
        RegistrarLicenciaDeEdificacion.ExpedienteListo listo =
                registro.preparar(expediente, fechaDeEmision);

        String concepto = derechos.aLaFechaDe(fechaDeEmision).paraLaEdificacion();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        numeroDeRecibo,
                        listo.solicitante().id(),
                        concepto,
                        "otorgamiento de licencia de edificacion");

        // La valorizacion se calcula AQUI, con la fecha del acto, y se imprime en el papel. Si el
        // cuadro sellado no la permite, el resultado trae el motivo y el papel imprime «—»: la
        // licencia se emite igual, porque su estructura no depende de ninguna cifra (#48 vs #197).
        //
        // Y AQUI quiere decir antes de numerar (#450): hasta #450 se pedia el cuadro a `catastro`
        // con el candado de `edificacion_correlativo` ya tomado.
        ValorizacionDelFue.Resultado valorizacion =
                valorizaciones.valorizar(listo.secciones().estructuras(), fechaDeEmision);

        return registro.registrar(
                expediente,
                fechaDeEmision,
                vigenciaHasta,
                new RegistrarLicenciaDeEdificacion.EmisionComprobada(
                        concepto, recibo, valorizacion),
                formato,
                observacion);
    }

    // ------------------------------------------------------------------

    /**
     * La licencia recien otorgada.
     *
     * @param fue el expediente
     * @param emision el movimiento que la otorgo, con su numero
     * @param vigencia el primer tramo de vigencia
     * @param documento los bytes del papel y el registro que los respalda
     * @param solicitante el resumen del padron
     * @param valorizacion la obra valorizada, o el motivo por el que hoy no hay cifra
     */
    public record LicenciaEmitida(
            FueDeEdificacion fue,
            MovimientoDeEdificacion emision,
            VigenciaDeLaLicencia vigencia,
            EmitirDocumento.Emision documento,
            ResumenDeContribuyente solicitante,
            ValorizacionDelFue.Resultado valorizacion) {

        /** El numero de la licencia otorgada. */
        public String numeroDeLicencia() {
            return Objects.requireNonNull(
                    emision.numeroLicencia(), "Una emision siempre numera la licencia");
        }
    }

    /** No hay ningun expediente con ese numero en esta municipalidad. */
    public static final class ExpedienteInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ExpedienteInexistente(@org.jspecify.annotations.Nullable String expediente) {
            super(
                    "No hay ningun expediente de edificacion "
                            + (expediente == null || expediente.isBlank()
                                    ? "(sin numero)"
                                    : expediente)
                            + " en esta municipalidad");
        }
    }

    /** El tramite no otorga licencia: un anteproyecto en consulta se resuelve con conformidad. */
    public static final class TramiteQueNoOtorgaLicencia extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TramiteQueNoOtorgaLicencia(FueDeEdificacion fue) {
            super(
                    "El expediente "
                            + fue.expediente()
                            + " es un tramite de "
                            + fue.tipoTramite().etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + ", y de el no sale ninguna licencia: se resuelve con una conformidad."
                            + " Emitir una aqui numeraria un acto que no existe");
        }
    }

    /** El expediente ya tenia su licencia. */
    public static final class YaEstabaEmitida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        YaEstabaEmitida(String expediente) {
            super(
                    "El expediente "
                            + expediente
                            + " ya tiene su licencia otorgada: una segunda emision le daria dos"
                            + " numeros a la misma obra");
        }
    }

    /** Faltan secciones obligatorias del FUE (AC 1). El mensaje dice cuales, todas. */
    public static final class SeccionesIncompletas extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        @SuppressWarnings("serial")
        private final List<SeccionDelFue> faltantes;

        SeccionesIncompletas(String expediente, List<SeccionDelFue> faltantes) {
            super(
                    "El expediente "
                            + expediente
                            + " no se puede emitir todavia: le faltan las secciones "
                            + faltantes.stream().map(SeccionDelFue::etiqueta).toList()
                            + ". El FUE se completa por partes, y la licencia solo sale cuando"
                            + " estan las obligatorias (AC 1 de #48)");
            this.faltantes = List.copyOf(faltantes);
        }

        /** Las secciones que faltan, legibles por programa. */
        public List<SeccionDelFue> faltantes() {
            return faltantes;
        }
    }
}
