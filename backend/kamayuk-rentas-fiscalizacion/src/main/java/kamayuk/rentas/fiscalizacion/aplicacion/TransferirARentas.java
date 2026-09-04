package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfiere a rentas el resultado de una fiscalizacion: inscribe lo hallado en el padron, asienta
 * los cargos de la diferencia y emite la resolucion de determinacion (#52, RF-054, RF-057).
 *
 * <h2>Es la frontera delicada del sistema, y es el unico camino</h2>
 *
 * <p>Hasta aqui, todo lo que {@code fiscalizacion} registro vivio sobre <b>copias</b>: el acta
 * guarda el area medida en campo y la version de ficha que regia el dia de la visita, y la
 * liquidacion guarda el contraste hallado/declarado (ARQ-01 §3.5). Esta clase es el unico sitio del
 * sistema donde ese trabajo se convierte en dato oficial.
 *
 * <p>«El unico» no es una intencion: es una regla de arquitectura. {@code
 * SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION} exige que ninguna otra clase de este
 * contexto dependa de un puerto de escritura de {@code catastro}, {@code rentas} o {@code
 * cuentacorriente}, y que todo tipo ajeno que este contexto toque este clasificado como lectura o
 * como escritura, una linea por tipo. Tiene dos clases de muestra que la violan.
 *
 * <h2>Que escribe, en que orden y por que ese orden</h2>
 *
 * <ol>
 *   <li><b>La version nueva de la ficha</b>, por {@link TransferenciaDeFiscalizacion}. Su {@code
 *       documento_origen} es el <b>numero de la liquidacion</b>: es el acto que determino la
 *       diferencia, es lo que sustenta la version, y es lo unico que se conoce antes de emitir el
 *       papel. Va primero porque la resolucion imprime lo que la transferencia dejo inscrito, y un
 *       papel que anuncie una inscripcion que todavia no ocurrio puede acabar mintiendo.
 *   <li><b>La resolucion y su documento</b>, por {@link EmitirDocumento}, con la version real ya en
 *       la mano. El numero de la resolucion <b>es</b> el del documento, como en {@code
 *       ActoCoactivo} (#41) y {@code ResolucionDeGerencia} (#50): dos numeraciones para el mismo
 *       papel divergen.
 *   <li><b>Los cargos de la diferencia</b>, por {@link GeneradorDeCargos}, con el numero de la
 *       resolucion como documento de origen. El cargo apunta al papel notificado, que es lo que el
 *       contribuyente tiene en la mano cuando discute la cifra en ventanilla.
 *   <li><b>La fila de la transferencia</b>, que ata las dos versiones de ficha, el documento y la
 *       liquidacion.
 * </ol>
 *
 * <p>Los cuatro pasos van en <b>una transaccion</b> (AC 4, RF-133): ficha nueva, asientos y
 * resolucion, o nada. Media transferencia deja el padron cambiado sin papel que lo justifique, o un
 * papel notificado sin cargo que cobrar.
 *
 * <h2>Que NO escribe: nada en {@code rentas}</h2>
 *
 * <p>El nombre es el de RF-054 y el del manual, y conviene saber que la escritura no va ahi. Lo que
 * {@code rentas} guarda de un ejercicio es la <b>declaracion jurada</b>, que es el acto del
 * contribuyente: la administracion no la reescribe. Lo que la sustituye es la determinacion de
 * oficio, y su cifra espera a D-02a. Estructuralmente, entonces, «transferir a rentas» escribe en
 * dos sitios: la ficha —{@code catastro}, que es donde vive lo declarado sobre el predio— y el
 * libro —{@code cuentacorriente}, por {@link GeneradorDeCargos}, como todo cargo de otro contexto
 * (ARQ-01 §4 regla 2)—. Que {@code rentas} no tenga puerto de escritura no es un olvido: es lo que
 * la regla de arquitectura garantiza, y el dia que haga falta uno costara una linea visible en el
 * diff.
 *
 * <h2>Los cargos: la estructura se transfiere siempre, la cifra solo si la hay</h2>
 *
 * <p>Mientras D-02a siga abierta las lineas de la liquidacion salen sin importes (#198), asi que
 * una transferencia de hoy inscribe la estructura hallada y asienta <b>cero</b> cargos. Eso no es
 * una transferencia a medias: es la mitad que no depende de D-02, hecha entera. Cuando la
 * liquidacion traiga importes, cada linea con {@code insolutoOmitido} produce un cargo y cada una
 * con {@code multaTributaria} otro. Ninguna cifra se calcula aqui —se copia de la linea—, que es lo
 * que la regla 5 exige y lo que hace que esta clase no tenga ni un literal numerico.
 *
 * <p><b>El cargo va sin periodo</b>, y es una decision. El predial ordinario se asienta por cuotas;
 * la diferencia determinada de oficio es anual y vence con la resolucion, no con el calendario
 * trimestral. Con {@code periodo = null} la obligacion que crea en el libro es {@code
 * (contribuyente, tributo, ejercicio, 0, unidad)}, distinta de las cuatro cuotas ordinarias —que es
 * lo que corresponde: son deudas con vencimientos distintos—.
 */
@Service
public class TransferirARentas {

    private static final String TABLA_AUDITADA = "resolucion_determinacion";

    /** El tipo con que se registra el documento de la resolucion. */
    static final String TIPO_DE_DOCUMENTO = "RDF";

    /**
     * Los tributos del libro a los que se imputa lo determinado de oficio.
     *
     * <p>Son los mismos nombres con los que {@code rentas} asienta la determinacion ordinaria
     * —{@code PREDIAL}, {@code VEHICULAR}—, y tienen que serlo: la diferencia determinada de oficio
     * es <b>el mismo tributo del mismo ejercicio sobre la misma unidad</b>, y el libro identifica
     * una obligacion por esa combinacion. Inventar aqui un {@code PREDIAL_FISCALIZADO} crearia una
     * obligacion paralela que ninguna consulta de deuda sumaria con la ordinaria.
     */
    private static final String TRIBUTO_PREDIAL = TributoDelLibro.PREDIAL.texto();

    private static final String TRIBUTO_VEHICULAR = TributoDelLibro.VEHICULAR.texto();

    /** Y la multa del art. 176, que no es tributo y por eso se imputa aparte. */
    private static final String TRIBUTO_MULTA = TributoDelLibro.MULTA_TRIBUTARIA.texto();

    /** Los estados en los que el contraste de una liquidacion ya es definitivo (AC 3). */
    private static final Set<EstadoDeLiquidacion> CON_CONTRASTE_DEFINITIVO =
            Set.of(EstadoDeLiquidacion.LIQUIDADA, EstadoDeLiquidacion.NOTIFICADA);

    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;
    private final ActaFiscalizacionRepository actas;
    private final ResolucionDeDeterminacionRepository resoluciones;
    private final TransferenciaDeFiscalizacion padron;
    private final GeneradorDeCargos cargos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public TransferirARentas(
            LiquidacionRepository liquidaciones,
            MovimientoDeLiquidacionRepository movimientos,
            ActaFiscalizacionRepository actas,
            ResolucionDeDeterminacionRepository resoluciones,
            TransferenciaDeFiscalizacion padron,
            GeneradorDeCargos cargos,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
        this.actas = actas;
        this.resoluciones = resoluciones;
        this.padron = padron;
        this.cargos = cargos;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Transfiere el resultado al padron y emite su resolucion.
     *
     * @param peticion que liquidacion se transfiere, con que fecha y con que sustento
     * @param formato en que formato sale el papel
     * @param observacion por que se transfiere (regla 10, RNF-052)
     * @throws LiquidacionInexistente si no hay ninguna liquidacion con ese numero
     * @throws SinSustentoDocumental si el contraste todavia no es definitivo o falta el papel
     * @throws LiquidacionSustituida si una reliquidacion posterior la dejo sin efecto
     * @throws ResolucionDeDeterminacionRepository.LiquidacionYaTransferida si ya se transfirio
     * @throws TransferenciaDeFiscalizacion.SinFichaQueVersionar si el predio no tiene ficha vigente
     */
    @Transactional
    public Transferencia transferir(
            Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        Liquidacion liquidacion =
                liquidaciones
                        .porNumero(peticion.numeroDeLiquidacion())
                        .orElseThrow(
                                () -> new LiquidacionInexistente(peticion.numeroDeLiquidacion()));
        long liquidacionId = liquidacion.identificador();

        exigirSustento(liquidacion, peticion);

        // La comprobacion previa ahorra el trabajo y da un mensaje util; la que lo IMPIDE es
        // `resolucion_determinacion_liquidacion_uq` (V49), porque dos peticiones simultaneas
        // pasan las dos por este `if` (AC 6).
        resoluciones
                .deLiquidacion(liquidacionId)
                .ifPresent(
                        yaEsta -> {
                            throw new ResolucionDeDeterminacionRepository.LiquidacionYaTransferida(
                                    liquidacionId);
                        });

        ActaFiscalizacion acta =
                actas.findById(liquidacion.actaId())
                        .orElseThrow(
                                () ->
                                        new LiquidarFiscalizacion.ActaInexistente(
                                                liquidacion.actaId()));
        List<LineaDeLiquidacion> lineas = liquidaciones.lineasDe(liquidacionId);

        // 1. El padron. Unico camino de escritura hacia catastro, y va primero para que el papel
        //    imprima lo que quedo inscrito de verdad y no lo que se esperaba inscribir.
        VersionTransferida version =
                acta.esPredial()
                        ? padron.inscribirLoHallado(
                                Objects.requireNonNull(acta.predioId()),
                                peticion.fecha(),
                                liquidacion.numero(),
                                acta.areaHallada(),
                                usoHalladoDe(lineas),
                                observacion)
                        : null;

        // 2. La resolucion y su papel, con la version real ya en la mano.
        ResumenDeContribuyente obligado = obligadoDe(acta);
        ModeloDeDocumento modelo =
                ModeloDeLaResolucionDeDeterminacion.de(
                        liquidacion,
                        lineas,
                        obligado.nombre(),
                        obligado.codigo(),
                        obligado.documento(),
                        contribuyentes
                                .domicilioFiscalDe(acta.contribuyenteId(), peticion.fecha())
                                .orElse(null),
                        referenciaDeLaUnidad(acta),
                        version,
                        peticion.fecha(),
                        peticion.documentoSustento(),
                        peticion.sustento(),
                        peticion.baseLegal());

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(peticion.fecha()),
                        liquidacion.numero(),
                        modelo,
                        formato,
                        observacion);
        String numero = emision.registro().numero();

        // 3. Los cargos de la diferencia, apuntando al papel notificado.
        int asentados = asentarLaDiferencia(acta, lineas, numero, peticion.fecha(), observacion);

        // 4. Y la fila que ata las dos versiones, el documento y la liquidacion.
        ResolucionDeDeterminacion registrada =
                resoluciones.registrar(
                        acta.esPredial()
                                ? ResolucionDeDeterminacion.predial(
                                        numero,
                                        documentoDe(emision),
                                        liquidacionId,
                                        acta.contribuyenteId(),
                                        Objects.requireNonNull(acta.predioId()),
                                        Objects.requireNonNull(version).fichaAnteriorId(),
                                        version.fichaNuevaId(),
                                        peticion.fecha(),
                                        peticion.documentoSustento(),
                                        peticion.sustento(),
                                        peticion.baseLegal(),
                                        observacion)
                                : ResolucionDeDeterminacion.vehicular(
                                        numero,
                                        documentoDe(emision),
                                        liquidacionId,
                                        acta.contribuyenteId(),
                                        Objects.requireNonNull(acta.vehiculoId()),
                                        peticion.fecha(),
                                        peticion.documentoSustento(),
                                        peticion.sustento(),
                                        peticion.baseLegal(),
                                        observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                String.valueOf(registrada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(liquidacion, registrada, version, asentados)));

        return new Transferencia(registrada, emision, version, asentados, peticion.fecha());
    }

    // ------------------------------------------------------------------

    /**
     * AC 3: sin sustento documental no se transfiere.
     *
     * <p>Son tres condiciones y se comprueban por separado porque se arreglan de tres maneras
     * distintas: adjuntar el papel, cerrar la liquidacion, o transferir la version buena. Un unico
     * «no procede» dejaria a quien opera adivinando cual de las tres le falta.
     *
     * <p>El estado <b>se deriva del historial</b> y no de una columna: {@code
     * liquidacion_fiscalizacion} no tiene ninguna, precisamente porque no admite {@code UPDATE}
     * (V39 §3).
     */
    private void exigirSustento(Liquidacion liquidacion, Peticion peticion) {
        if (peticion.documentoSustento().isBlank()) {
            throw new SinSustentoDocumental(
                    liquidacion.numero(),
                    "no se indico el documento que la sustenta: lo hallado sobrescribe lo declarado,"
                            + " y el papel que lo respalda es lo unico que el contribuyente puede"
                            + " pedir");
        }

        EstadoDeLiquidacion estado =
                EstadoDeLiquidacion.delHistorial(
                        movimientos.deLiquidacion(liquidacion.identificador()));
        if (!CON_CONTRASTE_DEFINITIVO.contains(estado)) {
            throw new SinSustentoDocumental(
                    liquidacion.numero(),
                    "esta "
                            + estado.etiqueta()
                            + " y solo se transfiere lo LIQUIDADA o lo NOTIFICADA: hasta que el"
                            + " contraste sea definitivo, transferirlo inscribiria en el padron un"
                            + " hallazgo que todavia se esta revisando");
        }

        liquidaciones
                .ultimaVersionDeActa(liquidacion.actaId())
                .filter(ultima -> ultima.version() != liquidacion.version())
                .ifPresent(
                        ultima -> {
                            throw new LiquidacionSustituida(liquidacion.numero(), ultima.numero());
                        });
    }

    /**
     * Los cargos de la diferencia, uno por linea con cifra.
     *
     * <p>Devuelve cuantos asiento. Con D-02a abierta son cero, y eso es lo correcto: la liquidacion
     * no tiene importes que asentar (#198). Ninguna cifra se calcula aqui.
     */
    private int asentarLaDiferencia(
            ActaFiscalizacion acta,
            List<LineaDeLiquidacion> lineas,
            String numeroDeLaResolucion,
            LocalDate fecha,
            Observacion observacion) {

        String tributo = acta.esPredial() ? TRIBUTO_PREDIAL : TRIBUTO_VEHICULAR;
        int asentados = 0;
        for (LineaDeLiquidacion linea : lineas) {
            asentados +=
                    asentar(
                            linea,
                            tributo,
                            linea.insolutoOmitido(),
                            acta.contribuyenteId(),
                            numeroDeLaResolucion,
                            fecha,
                            observacion);
            asentados +=
                    asentar(
                            linea,
                            TRIBUTO_MULTA,
                            linea.multaTributaria(),
                            acta.contribuyenteId(),
                            numeroDeLaResolucion,
                            fecha,
                            observacion);
        }
        return asentados;
    }

    private int asentar(
            LineaDeLiquidacion linea,
            String tributo,
            @Nullable Dinero monto,
            long contribuyenteId,
            String numeroDeLaResolucion,
            LocalDate fecha,
            Observacion observacion) {
        if (monto == null || !monto.esPositivo()) {
            return 0;
        }
        cargos.generarCargo(
                linea.ejercicio(),
                contribuyenteId,
                tributo,
                null,
                linea.predioId(),
                linea.vehiculoId(),
                null,
                monto,
                fecha,
                numeroDeLaResolucion,
                observacion);
        return 1;
    }

    /**
     * El uso hallado que la liquidacion consigno, si alguna linea lo trae.
     *
     * <p>Sale de la liquidacion y no del acta porque {@code acta_fiscalizacion} (V4) guarda el area
     * medida en campo pero no el uso: el uso hallado entra al liquidar, y la liquidacion es la que
     * lo conserva. Nulo si ninguna linea lo consigno, y entonces la version nueva conserva el uso
     * que tenia.
     */
    private static @Nullable String usoHalladoDe(List<LineaDeLiquidacion> lineas) {
        for (LineaDeLiquidacion linea : lineas) {
            if (linea.usoHallado() != null) {
                return linea.usoHallado();
            }
        }
        return null;
    }

    private ResumenDeContribuyente obligadoDe(ActaFiscalizacion acta) {
        ResumenDeContribuyente enElPadron =
                contribuyentes.porIds(Set.of(acta.contribuyenteId())).get(acta.contribuyenteId());
        if (enElPadron == null) {
            throw new IllegalStateException(
                    "El acta fiscaliza a un contribuyente que el padron no tiene");
        }
        return enElPadron;
    }

    private static String referenciaDeLaUnidad(ActaFiscalizacion acta) {
        return acta.esPredial() ? "Predio " + acta.predioId() : "Vehiculo " + acta.vehiculoId();
    }

    private static long documentoDe(EmitirDocumento.Emision emision) {
        return Objects.requireNonNull(
                emision.registro().id(),
                "Un documento recien emitido siempre vuelve con su identificador");
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            Liquidacion liquidacion,
            ResolucionDeDeterminacion resolucion,
            @Nullable VersionTransferida version,
            int asentados) {
        return "{\"liquidacion\":\""
                + liquidacion.numero()
                + "\",\"resolucion\":\""
                + resolucion.numero()
                + "\",\"fichaAnterior\":"
                + (version == null ? "null" : version.fichaAnteriorId())
                + ",\"fichaNueva\":"
                + (version == null ? "null" : version.fichaNuevaId())
                + ",\"versionDeLaFicha\":"
                + (version == null ? "null" : version.version())
                + ",\"cargosAsentados\":"
                + asentados
                + "}";
    }

    /**
     * Lo que la pantalla manda para transferir.
     *
     * @param numeroDeLiquidacion la liquidacion que se transfiere
     * @param fecha el dia del acto; entra como argumento para que una transferencia dispuesta por
     *     otra resolucion se registre con la fecha que le corresponde (regla 6)
     * @param documentoSustento el papel que la sustenta —el acta, el expediente— (AC 3)
     * @param sustento el fundamento de la determinacion
     * @param baseLegal la norma que la ampara, tal como la cita quien resuelve
     */
    public record Peticion(
            String numeroDeLiquidacion,
            LocalDate fecha,
            String documentoSustento,
            String sustento,
            String baseLegal) {

        public Peticion {
            Objects.requireNonNull(numeroDeLiquidacion, "Falta el numero de liquidacion");
            Objects.requireNonNull(fecha, "Falta la fecha de la transferencia");
            Objects.requireNonNull(documentoSustento, "Falta el sustento documental");
            Objects.requireNonNull(sustento, "Falta el sustento de la resolucion");
            Objects.requireNonNull(baseLegal, "Falta la base legal");
        }
    }

    /**
     * Lo que la transferencia dejo hecho.
     *
     * @param resolucion la fila registrada
     * @param emision los bytes del documento y su registro
     * @param version lo que cambio en el padron; nulo en una transferencia vehicular
     * @param cargosAsentados cuantos asientos genero; cero mientras D-02a siga abierta
     * @param aLaFecha el dia del acto, al que estan las cifras del papel (regla 9)
     */
    public record Transferencia(
            ResolucionDeDeterminacion resolucion,
            EmitirDocumento.Emision emision,
            @Nullable VersionTransferida version,
            int cargosAsentados,
            LocalDate aLaFecha) {}

    /** No hay ninguna liquidacion con ese numero, o es de otra municipalidad. */
    public static final class LiquidacionInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionInexistente(String numero) {
            super("No hay ninguna liquidacion de fiscalizacion con el numero '" + numero + "'");
        }
    }

    /** Falta el papel que respalda el acto, o el contraste todavia no es definitivo (AC 3). */
    public static final class SinSustentoDocumental extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinSustentoDocumental(String numero, String motivo) {
            super("La liquidacion " + numero + " no se puede transferir: " + motivo);
        }
    }

    /** Una reliquidacion posterior la sustituyo: lo que se transfiere es la ultima version. */
    public static final class LiquidacionSustituida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionSustituida(String numero, String ultima) {
            super(
                    "La liquidacion "
                            + numero
                            + " esta sustituida por "
                            + ultima
                            + ": transferir una version corregida inscribiria en el padron un"
                            + " contraste que ya se rectifico");
        }
    }
}
