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
import kamayuk.rentas.dominio.OrdenDeLosActos;
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
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la licencia de edificacion lee y escribe en esta base, sin preguntar a ningun vecino (#48,
 * #450).
 *
 * <h2>Por que existe</h2>
 *
 * <p>Hasta #450 todo esto era el cuerpo de {@link EmitirLicenciaDeEdificacion#emitir}, que era
 * {@code @Transactional} entero: {@code normativa} y {@code caja} se preguntaban con la conexion de
 * la peticion tomada, y la valorizacion —el cuadro de valores unitarios, que es {@code catastro}—
 * salia <b>despues</b> de {@code siguienteCorrelativo}, con la fila de {@code
 * edificacion_correlativo} bloqueada hasta el commit.
 *
 * <p>Ahora el orquestador pregunta sin transaccion y esta clase hace las dos cosas que si la
 * necesitan:
 *
 * <ul>
 *   <li>{@link #preparar} lee el expediente y comprueba que se puede emitir —antes de preguntar a
 *       nadie: si le faltan secciones, no hay recibo que comprobar—, en una transaccion de solo
 *       lectura.
 *   <li>{@link #registrar} <b>vuelve a leer y a comprobar</b> dentro de la transaccion que escribe,
 *       y despues numera, dibuja y guarda. Releer no es desconfianza: entre las dos transacciones
 *       cabe otra emision del mismo expediente, y la comprobacion que vale es la que se hace con el
 *       candado de la escritura.
 * </ul>
 */
@Service
public class RegistrarLicenciaDeEdificacion {

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final AplicacionDeRecibos aplicaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeEdificacion plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarLicenciaDeEdificacion(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            AplicacionDeRecibos aplicaciones,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeEdificacion plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.aplicaciones = aplicaciones;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * El expediente, comprobado: existe, otorga licencia, no la tiene ya, la fecha no se sale del
     * orden de los actos y estan las secciones obligatorias.
     *
     * @throws EmitirLicenciaDeEdificacion.ExpedienteInexistente si no hay ningun expediente con ese
     *     numero
     * @throws EmitirLicenciaDeEdificacion.TramiteQueNoOtorgaLicencia si el tramite no produce
     *     licencia
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior a la declaracion o
     *     posterior a hoy (#402)
     * @throws EmitirLicenciaDeEdificacion.SeccionesIncompletas si falta alguna seccion (AC 1)
     */
    @Transactional(readOnly = true)
    public ExpedienteListo preparar(String expediente, LocalDate fechaDeEmision) {
        return leerYComprobar(expediente, fechaDeEmision);
    }

    /**
     * Numera, dibuja y guarda la licencia con lo que los vecinos ya contestaron.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de la solicitud: la regla 10 exige que
     * se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional — que desde #450 es este y no el del orquestador.
     *
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya pago otro acto (#383)
     */
    @Transactional
    public EmitirLicenciaDeEdificacion.LicenciaEmitida registrar(
            String expediente,
            LocalDate fechaDeEmision,
            LocalDate vigenciaHasta,
            EmisionComprobada comprobada,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(comprobada, "No se escribe sin lo que contestaron los vecinos");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ExpedienteListo listo = leerYComprobar(expediente, fechaDeEmision);
        FueDeEdificacion fue = listo.fue();
        SeccionesDelExpediente secciones = listo.secciones();
        ResumenDeContribuyente solicitante = listo.solicitante();
        ReciboDeTramite recibo = comprobada.recibo();
        ValorizacionDelFue.Resultado valorizacion = comprobada.valorizacion();

        // El candado de `edificacion_correlativo` se toma AQUI y dura hasta el commit (#450). La
        // valorizacion ya llego: ningun vecino se pregunta con el tomado.
        Ejercicio ejercicio = Ejercicio.de(fechaDeEmision);
        String numero = plantilla.componer(ejercicio, expedientes.siguienteCorrelativo(ejercicio));

        VigenciaDeLaLicencia primerTramo =
                new VigenciaDeLaLicencia(
                        null, fue.identificador(), 0L, 1, fechaDeEmision, vigenciaHasta);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        EmitirLicenciaDeEdificacion.TIPO_DE_DOCUMENTO,
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

        // EL RECIBO SE GASTA AQUI (#383), con el movimiento de emision ya escrito.
        GastoDelDerecho.gastar(
                aplicaciones,
                recibo,
                comprobada.concepto(),
                "edificacion_movimiento",
                registrado.identificador());

        VigenciaDeLaLicencia vigencia =
                movimientos.conceder(fue.identificador(), registrado.identificador(), primerTramo);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "edificacion_movimiento",
                                String.valueOf(registrado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(fue, numero, recibo, valorizacion)));

        return new EmitirLicenciaDeEdificacion.LicenciaEmitida(
                fue, registrado, vigencia, emision, solicitante, valorizacion);
    }

    // ------------------------------------------------------------------

    private ExpedienteListo leerYComprobar(String expediente, LocalDate fechaDeEmision) {
        Objects.requireNonNull(
                fechaDeEmision, "La fecha de emision entra como argumento (regla 6)");

        FueDeEdificacion fue =
                expedientes
                        .porExpediente(expediente == null ? "" : expediente.strip())
                        .orElseThrow(
                                () ->
                                        new EmitirLicenciaDeEdificacion.ExpedienteInexistente(
                                                expediente));

        if (!fue.tipoTramite().emiteLicencia()) {
            throw new EmitirLicenciaDeEdificacion.TramiteQueNoOtorgaLicencia(fue);
        }
        if (movimientos.emisionDe(fue.identificador()).isPresent()) {
            throw new EmitirLicenciaDeEdificacion.YaEstabaEmitida(fue.expediente());
        }
        // #402: una de las cinco copias de la regla, con su excepcion propia y sin mirar hoy. La
        // regla es una sola y vive en `OrdenDeLosActos`.
        OrdenDeLosActos.exigir(
                "la emision de la licencia del expediente " + fue.expediente(),
                fechaDeEmision,
                LocalDate.now(reloj),
                new OrdenDeLosActos.ActoPrevio(
                        "la declaracion del expediente " + fue.expediente(),
                        fue.fechaDeclaracion()));

        SeccionesDelExpediente secciones = leerSecciones(fue);
        secciones.exigirCompletas(fue.expediente());

        return new ExpedienteListo(fue, secciones, solicitanteDe(fue));
    }

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
     * El expediente comprobado, con lo que el orquestador necesita para preguntar a los vecinos.
     *
     * @param fue el expediente
     * @param secciones las cinco secciones, ya comprobadas completas
     * @param solicitante el resumen del padron: su identificador es el que el recibo tiene que
     *     traer
     */
    public record ExpedienteListo(
            FueDeEdificacion fue,
            SeccionesDelExpediente secciones,
            ResumenDeContribuyente solicitante) {}

    /**
     * Lo que los vecinos contestaron, reunido fuera de toda transaccion por {@link
     * EmitirLicenciaDeEdificacion} (#450).
     *
     * @param concepto el concepto del TUPA que el conjunto sellado nombra (de {@code normativa})
     * @param recibo el recibo que respalda el derecho, ya comprobado (de {@code caja})
     * @param valorizacion la obra valorizada contra el cuadro de {@code catastro}, o el motivo por
     *     el que hoy no hay cifra
     */
    public record EmisionComprobada(
            String concepto, ReciboDeTramite recibo, ValorizacionDelFue.Resultado valorizacion) {

        public EmisionComprobada {
            Objects.requireNonNull(concepto, "concepto");
            Objects.requireNonNull(recibo, "recibo");
            Objects.requireNonNull(valorizacion, "valorizacion");
        }
    }

    /**
     * Las cinco secciones leidas de una vez, con la comprobacion del AC 1 dentro.
     *
     * <p>Se leen las cinco antes de comprobar ninguna, a proposito: comprobar sobre la marcha
     * dejaria el error diciendo solo la primera que falta.
     */
    public record SeccionesDelExpediente(
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
                throw new EmitirLicenciaDeEdificacion.SeccionesIncompletas(expediente, faltan);
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
}
