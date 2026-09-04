package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
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
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeProfesional;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class EmitirLicenciaDeEdificacion {

    /** El {@code tipo} con que se guarda el papel en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "LICENCIA_EDIFICACION";

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final RecibosDeTramite recibos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final DerechosDeTramiteParametrizados derechos;
    private final ValorizacionDelFue valorizaciones;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeEdificacion plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirLicenciaDeEdificacion(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            RecibosDeTramite recibos,
            DirectorioDeContribuyentes contribuyentes,
            DerechosDeTramiteParametrizados derechos,
            ValorizacionDelFue valorizaciones,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeEdificacion plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.recibos = recibos;
        this.contribuyentes = contribuyentes;
        this.derechos = derechos;
        this.valorizaciones = valorizaciones;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite la licencia.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de la solicitud: la regla 10 exige que
     * se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @param expediente el numero del expediente del FUE
     * @param fechaDeEmision el dia del acto; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando rige la licencia. <b>Entra como dato del acto y no se
     *     calcula</b>: el plazo lo fija la Ley 29090 con una cifra, y ninguna cifra normativa se
     *     compila (regla 5). Lo que el sistema si impone es que no termine antes de empezar.
     * @param numeroDeRecibo el recibo de caja de tasas del derecho, como esta en el papel
     * @throws ExpedienteInexistente si no hay ningun expediente con ese numero
     * @throws TramiteQueNoOtorgaLicencia si el tramite no produce licencia
     * @throws SeccionesIncompletas si falta alguna seccion obligatoria (AC 1)
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho (AC 5)
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto del TUPA cobra el derecho
     */
    @Transactional
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

        FueDeEdificacion fue =
                expedientes
                        .porExpediente(expediente == null ? "" : expediente.strip())
                        .orElseThrow(() -> new ExpedienteInexistente(expediente));

        if (!fue.tipoTramite().emiteLicencia()) {
            throw new TramiteQueNoOtorgaLicencia(fue);
        }
        if (movimientos.emisionDe(fue.identificador()).isPresent()) {
            throw new YaEstabaEmitida(fue.expediente());
        }
        if (fechaDeEmision.isBefore(fue.fechaDeclaracion())) {
            throw new AnteriorALaDeclaracion(
                    fue.expediente(), fue.fechaDeclaracion(), fechaDeEmision);
        }

        SeccionesDelExpediente secciones = leerSecciones(fue);
        secciones.exigirCompletas(fue.expediente());

        ResumenDeContribuyente solicitante = solicitanteDe(fue);

        String concepto = derechos.aLaFechaDe(fechaDeEmision).paraLaEdificacion();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        numeroDeRecibo,
                        solicitante.id(),
                        concepto,
                        "otorgamiento de licencia de edificacion");

        Ejercicio ejercicio = Ejercicio.de(fechaDeEmision);
        String numero = plantilla.componer(ejercicio, expedientes.siguienteCorrelativo(ejercicio));

        // La valorizacion se calcula AQUI, con la fecha del acto, y se imprime en el papel. Si el
        // cuadro sellado no la permite, el resultado trae el motivo y el papel imprime «—»: la
        // licencia se emite igual, porque su estructura no depende de ninguna cifra (#48 vs #197).
        ValorizacionDelFue.Resultado valorizacion =
                valorizaciones.valorizar(secciones.estructuras(), fechaDeEmision);

        VigenciaDeLaLicencia primerTramo =
                new VigenciaDeLaLicencia(
                        null, fue.identificador(), 0L, 1, fechaDeEmision, vigenciaHasta);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        ejercicio,
                        numero,
                        ModeloDelFue.deLaLicencia(
                                fue,
                                numero,
                                fechaDeEmision,
                                primerTramo,
                                solicitante.nombre(),
                                solicitante.codigo(),
                                secciones.terreno(),
                                secciones.proyecto(),
                                secciones.profesionales(),
                                secciones.estructuras(),
                                valorizacion,
                                recibo.numero()),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        Instant ahora = reloj.instant();
        MovimientoDeEdificacion registrado =
                movimientos.registrar(
                        MovimientoDeEdificacion.emision(
                                fue.identificador(),
                                fechaDeEmision,
                                numero,
                                recibo.reciboId(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        VigenciaDeLaLicencia vigencia =
                movimientos.conceder(fue.identificador(), registrado.identificador(), primerTramo);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fechaDeEmision,
                                "edificacion_movimiento",
                                String.valueOf(registrado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(fue, numero, recibo, valorizacion)));

        return new LicenciaEmitida(fue, registrado, vigencia, emision, solicitante, valorizacion);
    }

    // ------------------------------------------------------------------

    private SeccionesDelExpediente leerSecciones(FueDeEdificacion fue) {
        long id = fue.identificador();
        return new SeccionesDelExpediente(
                expedientes.terrenoVigente(id),
                expedientes.proyectoVigente(id),
                expedientes.valorizacionVigente(id),
                expedientes.profesionalesVigentes(id),
                expedientes.requisitosVigentes(id));
    }

    private ResumenDeContribuyente solicitanteDe(FueDeEdificacion fue) {
        Map<Long, ResumenDeContribuyente> padron =
                contribuyentes.porIds(Set.of(fue.contribuyenteId()));
        ResumenDeContribuyente solicitante = padron.get(fue.contribuyenteId());
        if (solicitante == null) {
            throw new IllegalStateException(
                    "El expediente "
                            + fue.expediente()
                            + " es de un contribuyente que el padron ya no tiene");
        }
        return solicitante;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            FueDeEdificacion fue,
            String numero,
            ReciboDeTramite recibo,
            ValorizacionDelFue.Resultado valorizacion) {
        return "{\"expediente\":\""
                + fue.expediente()
                + "\",\"licencia\":\""
                + numero
                + "\",\"recibo\":\""
                + recibo.numero()
                + "\",\"valorizada\":"
                + valorizacion.estaDisponible()
                + "}";
    }

    // ------------------------------------------------------------------

    /**
     * Las cinco secciones leidas de una vez, con la comprobacion del AC 1 dentro.
     *
     * <p>Se leen las cinco antes de comprobar ninguna, a proposito: comprobar sobre la marcha
     * dejaria el error diciendo solo la primera que falta.
     */
    record SeccionesDelExpediente(
            Optional<TerrenoDelFue> terrenoOpcional,
            Optional<ProyectoDelFue> proyectoOpcional,
            List<EstructuraDelProyecto> estructuras,
            List<ProfesionalDelFue> profesionales,
            List<RequisitoDelFue> requisitos) {

        void exigirCompletas(String expediente) {
            List<SeccionDelFue> faltan = new ArrayList<>();
            if (terrenoOpcional.isEmpty()) {
                faltan.add(SeccionDelFue.TERRENO);
            }
            if (proyectoOpcional.isEmpty()) {
                faltan.add(SeccionDelFue.PROYECTO);
            }
            if (estructuras.isEmpty()) {
                faltan.add(SeccionDelFue.VALORIZACION);
            }
            if (!tieneLosProfesionalesQueFirman()) {
                faltan.add(SeccionDelFue.PROFESIONALES);
            }
            if (requisitos.stream().noneMatch(RequisitoDelFue::presentado)) {
                faltan.add(SeccionDelFue.DOCUMENTOS);
            }
            if (!faltan.isEmpty()) {
                throw new SeccionesIncompletas(expediente, faltan);
            }
        }

        /**
         * Que esten el proyectista de arquitectura y el responsable de obra.
         *
         * <p>Son los dos que el issue nombra como secciones propias del FUE, y los dos que
         * responden por la obra: sin proyectista no hay quien responda por el proyecto, y sin
         * responsable de obra no hay a quien reclamar durante la ejecucion. Los otros dos
         * proyectistas —estructuras e instalaciones— no se exigen aqui: cuando hacen falta lo dice
         * el reglamento segun la modalidad, y eso son cifras y supuestos que este repositorio no
         * tiene verificados.
         */
        private boolean tieneLosProfesionalesQueFirman() {
            Set<TipoDeProfesional> presentes = EnumSet.noneOf(TipoDeProfesional.class);
            for (ProfesionalDelFue profesional : profesionales) {
                presentes.add(profesional.tipo());
            }
            return presentes.contains(TipoDeProfesional.PROYECTISTA_ARQUITECTURA)
                    && presentes.contains(TipoDeProfesional.RESPONSABLE_OBRA);
        }

        TerrenoDelFue terreno() {
            return terrenoOpcional.orElseThrow();
        }

        ProyectoDelFue proyecto() {
            return proyectoOpcional.orElseThrow();
        }
    }

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

    /** La emision no puede ser anterior a la declaracion que la sustenta. */
    public static final class AnteriorALaDeclaracion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AnteriorALaDeclaracion(String expediente, LocalDate declaracion, LocalDate emision) {
            super(
                    "El expediente "
                            + expediente
                            + " se declaro el "
                            + declaracion
                            + " y no se puede emitir el "
                            + emision
                            + ": un acto no autoriza lo que todavia no se habia solicitado");
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
