package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dicta una resolución de gerencia sobre una papeleta, emite su documento y —si deja la multa sin
 * efecto— asienta la baja de la deuda (#50, RF-065, RF-074).
 *
 * <h2>Un solo camino de escritura para las tres resoluciones</h2>
 *
 * <p>La ordinaria, la sancionadora y la administrativa comparten todas las guardas —la papeleta
 * existe y sigue viva, el recurso que resuelven es suyo y no está resuelto, el documento se emite
 * en la misma transacción— y las tres pantallas que las dictan ({@code transito_rg_ordinaria},
 * {@code transito_rg_sancionadora}, {@code adm_resolucion_gerencia}) son vistas del mismo acto
 * administrativo. Escribirlas por separado sería tener tres sitios donde olvidar una guarda, y el
 * olvido no se vería: la resolución entraría y el papel saldría.
 *
 * <h2>Lo que la base decide, y lo que decide este código</h2>
 *
 * <ul>
 *   <li><b>La base</b>: que no haya dos ordinarias ni dos sancionadoras de la misma papeleta
 *       ({@code resolucion_gerencia_ordinaria_uq}, {@code ..._sancionadora_uq}), que un descargo no
 *       se resuelva dos veces ({@code ..._descargo_uq}), que la sancionadora lleve su sustento
 *       entero ({@code ..._sustento_ck}) y que no sea anterior al día en que vence el plazo ({@code
 *       ..._plazo_ck}). Son las que un {@code CHECK} o un índice pueden expresar, y por eso van
 *       ahí: dos peticiones simultáneas pasan las dos por cualquier {@code if}.
 *   <li><b>Este código</b>: que la diligencia que sustenta la sancionadora sea la de la ordinaria
 *       <b>de esta papeleta</b> y que haya surtido efecto —eso exige un {@code JOIN}, y un {@code
 *       CHECK} no puede hacerlo—, y que el descargo que se resuelve sea de esta papeleta.
 * </ul>
 *
 * <h2>Fundado no borra: asienta (AC 1 de #50)</h2>
 *
 * <p>Cuando el efecto es {@link EfectoSobreLaMulta#SE_DEJA_SIN_EFECTO}, la papeleta <b>no se borra
 * ni se edita</b> (regla 4, RNF-051): lo que se hace es pedirle a {@code cuentacorriente} que dé de
 * baja lo que esa obligación deba a la fecha de la resolución, por {@link ExtincionDeDeuda} y con
 * el número de la resolución como documento de origen. El motivo de cada asiento es la observación
 * de quien resuelve, así que el estado de cuenta explica por qué se dejó de cobrar sin salir de él.
 *
 * <p>Y <b>el estado de la papeleta no se toca</b>. Su situación se deriva de las resoluciones que
 * tiene y del libro: escribirla en la columna dejaría dos verdades, y la de la columna es la que
 * nadie vuelve a mirar.
 *
 * <h2>La resolución y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transacción</b>, con {@link EmitirDocumento}: el número
 * de la resolución <b>es</b> el del documento, y el documento guarda los datos con que se dibujó
 * más el SHA-256 de lo que salió. Es el patrón de {@code RegistrarActoCoactivo} (#41), y por el
 * mismo motivo: una resolución sin documento no se puede notificar; un documento sin resolución no
 * tiene procedimiento que lo explique.
 */
@Service
public class ResolverConResolucionDeGerencia {

    private static final String TABLA_AUDITADA = "resolucion_gerencia";

    private final PapeletaRepository papeletas;
    private final DescargoRepository descargos;
    private final ResolucionDeGerenciaRepository resoluciones;
    private final NotificacionDeResolucionRepository notificaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final ConsultaDeDeudaPublica deudas;
    private final ExtincionDeDeuda extincion;
    private final PlazosDeSancionesParametrizados plazos;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public ResolverConResolucionDeGerencia(
            PapeletaRepository papeletas,
            DescargoRepository descargos,
            ResolucionDeGerenciaRepository resoluciones,
            NotificacionDeResolucionRepository notificaciones,
            DirectorioDeContribuyentes contribuyentes,
            ConsultaDeDeudaPublica deudas,
            ExtincionDeDeuda extincion,
            PlazosDeSancionesParametrizados plazos,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.papeletas = papeletas;
        this.descargos = descargos;
        this.resoluciones = resoluciones;
        this.notificaciones = notificaciones;
        this.contribuyentes = contribuyentes;
        this.deudas = deudas;
        this.extincion = extincion;
        this.plazos = plazos;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Dicta la resolución, emite su documento y —si corresponde— asienta la baja.
     *
     * @param peticion qué papeleta, qué resolución, con qué fecha y con qué fallo
     * @param formato en qué formato sale el papel
     * @param observacion por qué se dicta (regla 10, RNF-052)
     * @throws RegistrarDescargo.PapeletaInexistente si no hay ninguna papeleta con ese número
     * @throws RegistrarDescargo.PapeletaSinNadaQueImpugnar si la papeleta está anulada o prescrita
     * @throws DescargoInexistente si se pide resolver un recurso que no existe
     * @throws DescargoDeOtraPapeleta si el recurso es de otra papeleta
     * @throws OrdinariaSinDictar si se pide la sancionadora y no hay ordinaria
     * @throws OrdinariaSinNotificar si la ordinaria no está notificada
     * @throws PlazoDeLaOrdinariaEnCurso si el plazo de la ordinaria todavía corre
     */
    @Transactional
    public ResolucionDictada dictar(
            Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        Papeleta papeleta =
                papeletas
                        .porNumero(peticion.familia(), peticion.numeroDePapeleta())
                        .orElseThrow(
                                () ->
                                        new RegistrarDescargo.PapeletaInexistente(
                                                peticion.familia(), peticion.numeroDePapeleta()));
        if (papeleta.estado() == EstadoDePapeleta.ANULADA
                || papeleta.estado() == EstadoDePapeleta.PRESCRITA) {
            throw new RegistrarDescargo.PapeletaSinNadaQueImpugnar(papeleta);
        }

        Descargo recurso = recursoDe(papeleta, peticion);
        Sustento sustento = sustentoDe(papeleta, peticion.tipo(), peticion.fecha());

        LocalDate proyeccion =
                peticion.proyectarDeudaAl() == null
                        ? peticion.fecha()
                        : peticion.proyectarDeudaAl();
        SeleccionDeObligacion obligacion = ObligacionDeLaPapeleta.de(papeleta);
        ObligacionPublica deuda = deudaDe(papeleta, obligacion, proyeccion);

        ResumenDeContribuyente obligado = obligadoDe(papeleta);
        Plazo plazo =
                peticion.tipo() == TipoDeResolucionDeGerencia.ORDINARIA
                        ? plazos.aLaFechaDe(peticion.fecha()).paraCumplirLaOrdinaria()
                        : null;

        ModeloDeDocumento modelo =
                ModeloDeLaResolucionDeGerencia.de(
                        papeleta,
                        peticion.tipo(),
                        obligado.nombre(),
                        obligado.codigo(),
                        obligado.documento(),
                        contribuyentes
                                .domicilioFiscalDe(papeleta.obligadoId(), peticion.fecha())
                                .orElse(null),
                        recurso,
                        peticion.sentido(),
                        peticion.efecto(),
                        peticion.sancionAccesoria(),
                        plazo,
                        deuda,
                        proyeccion,
                        peticion.sustento());

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        peticion.tipo().tipoDeDocumento(),
                        Ejercicio.de(papeleta.fechaInfraccion()),
                        papeleta.numero(),
                        modelo,
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");
        Instant ahora = reloj.instant();

        ResolucionDeGerencia registrada =
                resoluciones.registrar(
                        peticion.tipo().exigeOrdinariaVencida()
                                ? ResolucionDeGerencia.sancionadora(
                                        papeleta.identificador(),
                                        emision.registro().numero(),
                                        documentoId,
                                        peticion.fecha(),
                                        recurso == null ? null : recurso.identificador(),
                                        peticion.sentido(),
                                        peticion.efecto(),
                                        sustento.exigirNotificacion(),
                                        sustento.exigirDesde(),
                                        peticion.sancionAccesoria(),
                                        peticion.sustento(),
                                        ahora,
                                        observacion)
                                : ResolucionDeGerencia.nueva(
                                        papeleta.identificador(),
                                        peticion.tipo(),
                                        emision.registro().numero(),
                                        documentoId,
                                        peticion.fecha(),
                                        recurso == null ? null : recurso.identificador(),
                                        peticion.sentido(),
                                        peticion.efecto(),
                                        peticion.sancionAccesoria(),
                                        peticion.sustento(),
                                        ahora,
                                        observacion));

        MovimientoAsentado baja = null;
        if (registrada.dejaLaMultaSinEfecto()) {
            baja =
                    extincion.extinguir(
                            papeleta.obligadoId(),
                            obligacion,
                            peticion.fecha(),
                            "RESOLUCION " + registrada.numero(),
                            ObligacionDeLaPapeleta.referenciaDe(papeleta),
                            // La causal la declara quien dicta la resolucion, que es quien
                            // acaba de comprobar `dejaLaMultaSinEfecto()` en la linea de
                            // arriba (#684). Es una de las seis del desplegable de RF-044, y
                            // no una traduccion aproximada: la resolucion de gerencia que
                            // deja la multa sin efecto ES «RESOLUCIÓN QUE DEJA SIN EFECTO».
                            CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO,
                            observacion);
        }

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fecha(),
                                TABLA_AUDITADA,
                                String.valueOf(registrada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(papeleta, registrada, baja)));

        return new ResolucionDictada(registrada, emision, deuda, proyeccion, baja);
    }

    // ------------------------------------------------------------------

    /** El recurso que la resolución resuelve, comprobando que es de esta papeleta. */
    private @Nullable Descargo recursoDe(Papeleta papeleta, Peticion peticion) {
        String expediente = peticion.expedienteDelDescargo();
        if (expediente == null) {
            return null;
        }
        Descargo descargo =
                descargos
                        .porNumeroDeExpediente(expediente)
                        .orElseThrow(() -> new DescargoInexistente(expediente));
        if (descargo.papeletaId() != papeleta.identificador()) {
            throw new DescargoDeOtraPapeleta(expediente, papeleta.numero());
        }
        return descargo;
    }

    /**
     * El sustento de la sancionadora: la diligencia que notificó la ordinaria y el día desde el que
     * se puede sancionar.
     *
     * <p>Las tres condiciones se comprueban por separado porque las tres se arreglan de maneras
     * distintas: dictar la ordinaria, notificarla, o esperar. Un único «no procede» dejaría a quien
     * opera adivinando cuál de las tres le falta.
     */
    private Sustento sustentoDe(
            Papeleta papeleta, TipoDeResolucionDeGerencia tipo, LocalDate fecha) {
        if (!tipo.exigeOrdinariaVencida()) {
            return Sustento.NINGUNO;
        }
        ResolucionDeGerencia ordinaria =
                resoluciones
                        .dePapeleta(papeleta.identificador(), TipoDeResolucionDeGerencia.ORDINARIA)
                        .orElseThrow(() -> new OrdinariaSinDictar(papeleta.numero()));
        NotificacionDeResolucion diligencia =
                notificaciones
                        .queSurtioEfecto(ordinaria.identificador())
                        .orElseThrow(
                                () ->
                                        new OrdinariaSinNotificar(
                                                papeleta.numero(), ordinaria.numero()));
        LocalDate desde =
                Objects.requireNonNull(
                        diligencia.exigibleDesde(),
                        "Una diligencia que surtio efecto siempre trae su exigibilidad (V28)");
        if (fecha.isBefore(desde)) {
            throw new PlazoDeLaOrdinariaEnCurso(
                    papeleta.numero(), ordinaria.numero(), desde, fecha);
        }
        return new Sustento(diligencia.identificador(), desde);
    }

    /**
     * Cuánto debe esa obligación a la fecha, preguntándoselo al libro por su API pública.
     *
     * <p>Nunca a los importes congelados de la papeleta: el desglose que {@code papeleta} guarda es
     * el del acta y no se mueve, mientras que lo que hay que imprimir en la resolución es lo que se
     * debe <b>hoy</b>, con su fecha (regla 9). Devolver {@code null} cuando ya no debe nada es la
     * respuesta correcta: la resolución sale igual, con el cuadro en cero.
     */
    private @Nullable ObligacionPublica deudaDe(
            Papeleta papeleta, SeleccionDeObligacion obligacion, LocalDate fecha) {
        for (ObligacionPublica publica :
                deudas.deTodoElContribuyente(papeleta.obligadoId(), fecha)) {
            if (publica.tributo().equals(obligacion.tributo())
                    && publica.ejercicio().equals(obligacion.ejercicio())
                    && Objects.equals(publica.predioId(), obligacion.predioId())
                    && Objects.equals(publica.vehiculoId(), obligacion.vehiculoId())) {
                return publica;
            }
        }
        return null;
    }

    private ResumenDeContribuyente obligadoDe(Papeleta papeleta) {
        ResumenDeContribuyente enElPadron =
                contribuyentes.porIds(Set.of(papeleta.obligadoId())).get(papeleta.obligadoId());
        if (enElPadron == null) {
            throw new IllegalStateException(
                    "La papeleta "
                            + papeleta.numero()
                            + " se cobra a un contribuyente que el padron no tiene");
        }
        return enElPadron;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(
            Papeleta papeleta, ResolucionDeGerencia resolucion, @Nullable MovimientoAsentado baja) {
        return "{\"papeleta\":\""
                + papeleta.numero()
                + "\",\"tipo\":\""
                + resolucion.tipo().name()
                + "\",\"numero\":\""
                + resolucion.numero()
                + "\",\"sentido\":"
                + (resolucion.sentido() == null ? "null" : "\"" + resolucion.sentido() + "\"")
                + ",\"asientosDeBaja\":"
                + (baja == null ? 0 : baja.asientos())
                + "}";
    }

    /** La diligencia que sustenta la sancionadora y el día desde el que se puede dictar. */
    private record Sustento(@Nullable Long notificacionId, @Nullable LocalDate exigibleDesde) {

        static final Sustento NINGUNO = new Sustento(null, null);

        long exigirNotificacion() {
            return Objects.requireNonNull(
                    notificacionId, "Solo la sancionadora pide su sustento, y ahi nunca falta");
        }

        LocalDate exigirDesde() {
            return Objects.requireNonNull(
                    exigibleDesde, "Solo la sancionadora pide su sustento, y ahi nunca falta");
        }
    }

    /**
     * Lo que la pantalla manda para dictar una resolución.
     *
     * @param familia de qué familia es la papeleta
     * @param numeroDePapeleta el número impreso de la papeleta
     * @param tipo cuál de las tres resoluciones se dicta
     * @param fecha el día de la resolución; entra como argumento para que una resolución dispuesta
     *     por otra se registre con la fecha que le corresponde
     * @param expedienteDelDescargo el recurso que resuelve, si resuelve alguno
     * @param sentido con qué sentido; obligatorio si hay recurso
     * @param efecto qué le pasa a la multa; obligatorio si hay recurso
     * @param sancionAccesoria la sanción no pecuniaria que se deriva, si la hay
     * @param sustento el fundamento de la resolución
     * @param proyectarDeudaAl a qué día se proyecta la deuda que se imprime; nulo significa el día
     *     de la resolución
     */
    public record Peticion(
            Familia familia,
            String numeroDePapeleta,
            TipoDeResolucionDeGerencia tipo,
            LocalDate fecha,
            @Nullable String expedienteDelDescargo,
            @Nullable SentidoDelFallo sentido,
            @Nullable EfectoSobreLaMulta efecto,
            @Nullable String sancionAccesoria,
            String sustento,
            @Nullable LocalDate proyectarDeudaAl) {

        public Peticion {
            Objects.requireNonNull(familia, "Falta la familia de la papeleta");
            Objects.requireNonNull(numeroDePapeleta, "Falta el numero de papeleta");
            Objects.requireNonNull(tipo, "Falta el tipo de resolucion");
            Objects.requireNonNull(fecha, "Falta la fecha de la resolucion");
            Objects.requireNonNull(sustento, "Falta el sustento de la resolucion");
        }
    }

    /**
     * La resolución dictada, con el papel que salió y lo que movió el libro.
     *
     * @param resolucion la fila registrada
     * @param emision los bytes del documento y su registro
     * @param deuda cuánto se debía el día que dice {@link #aLaFecha}; nulo si ya no debía nada
     * @param aLaFecha el día al que se leyó la deuda (regla 9, RNF-075)
     * @param baja lo que se dio de baja; nulo si la resolución no dejó la multa sin efecto
     */
    public record ResolucionDictada(
            ResolucionDeGerencia resolucion,
            EmitirDocumento.Emision emision,
            @Nullable ObligacionPublica deuda,
            LocalDate aLaFecha,
            @Nullable MovimientoAsentado baja) {}

    /** No hay ningún descargo con ese número de expediente. */
    public static final class DescargoInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DescargoInexistente(String expediente) {
            super("No hay ningun descargo con el expediente '" + expediente + "'");
        }
    }

    /** El descargo existe pero impugna otra papeleta. */
    public static final class DescargoDeOtraPapeleta extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DescargoDeOtraPapeleta(String expediente, String papeleta) {
            super(
                    "El expediente "
                            + expediente
                            + " no impugna la papeleta "
                            + papeleta
                            + ": resolverlo aqui declararia fundado un recurso contra otra multa");
        }
    }

    /** Se pidió la sancionadora y la papeleta todavía no tiene ordinaria. */
    public static final class OrdinariaSinDictar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        OrdinariaSinDictar(String papeleta) {
            super(
                    "La papeleta "
                            + papeleta
                            + " no tiene resolucion de gerencia ordinaria: la sancionadora es la"
                            + " segunda, «emitida luego de la ordinaria», no la primera");
        }
    }

    /** La ordinaria existe pero ninguna diligencia surtió efecto. */
    public static final class OrdinariaSinNotificar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        OrdinariaSinNotificar(String papeleta, String ordinaria) {
            super(
                    "La resolucion ordinaria "
                            + ordinaria
                            + " de la papeleta "
                            + papeleta
                            + " no esta notificada: el plazo que da derecho a sancionar se cuenta"
                            + " desde la notificacion, y sin ella no ha empezado a correr");
        }
    }

    /** La ordinaria está notificada pero el plazo todavía corre. */
    public static final class PlazoDeLaOrdinariaEnCurso extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PlazoDeLaOrdinariaEnCurso(
                String papeleta, String ordinaria, LocalDate exigibleDesde, LocalDate pedida) {
            super(
                    "El plazo de la resolucion ordinaria "
                            + ordinaria
                            + " de la papeleta "
                            + papeleta
                            + " vence el "
                            + exigibleDesde.minusDays(1)
                            + ": la sancionadora no se puede dictar el "
                            + pedida
                            + ", sino desde el "
                            + exigibleDesde);
        }
    }
}
