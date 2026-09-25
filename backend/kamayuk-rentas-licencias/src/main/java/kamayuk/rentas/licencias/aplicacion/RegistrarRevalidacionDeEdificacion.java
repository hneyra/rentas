package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.TramosDeVigencia;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la revalidacion de una licencia de edificacion lee y escribe en esta base, sin preguntar a
 * ningun vecino (#48 AC 4, #450).
 *
 * <p>Hasta #450 todo esto era el cuerpo de {@link RevalidarLicenciaDeEdificacion#revalidar}, que
 * era {@code @Transactional} entero y preguntaba a {@code normativa} y a {@code caja} con la
 * conexion de la peticion tomada. Ahora el orquestador pregunta sin transaccion, y esta clase hace
 * las dos cosas que si la necesitan: {@link #preparar} lee y comprueba antes de preguntar, y {@link
 * #registrar} <b>vuelve a leer y a comprobar</b> dentro de la transaccion que escribe —entre las
 * dos cabe otra revalidacion de la misma licencia, y el tramo que vale es el que se calcula con la
 * escritura en curso— y despues escribe.
 */
@Service
public class RegistrarRevalidacionDeEdificacion {

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final AplicacionDeRecibos aplicaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarRevalidacionDeEdificacion(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            AplicacionDeRecibos aplicaciones,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.aplicaciones = aplicaciones;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * La revalidacion, comprobada: es una revalidacion, su licencia original existe y esta
     * otorgada, la fecha no se sale del orden de los actos, el tramo nuevo prorroga algo y llega al
     * dia del acto que lo concede (#451).
     *
     * @throws EmitirLicenciaDeEdificacion.ExpedienteInexistente si no hay ningun expediente con ese
     *     numero
     * @throws RevalidarLicenciaDeEdificacion.NoEsUnaRevalidacion si el tramite es otro
     * @throws RevalidarLicenciaDeEdificacion.OriginalSinLicencia si la original no se otorgo
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior a la declaracion o a
     *     la emision de la licencia original, o posterior a hoy (#402)
     * @throws RevalidarLicenciaDeEdificacion.ProrrogaQueNoProrroga si el tramo no pasa del anterior
     * @throws TramosDeVigencia.ProrrogaQueNoLlegaAlActo si el tramo termina antes del dia en que
     *     empezaria, que con la licencia vencida es el del acto (#451)
     */
    @Transactional(readOnly = true)
    public RevalidacionLista preparar(
            String expedienteDeRevalidacion, LocalDate fecha, LocalDate nuevaVigenciaHasta) {
        return leerYComprobar(expedienteDeRevalidacion, fecha, nuevaVigenciaHasta);
    }

    /**
     * Concede el tramo nuevo, con su resolucion, su movimiento y el recibo gastado.
     *
     * <p>La {@link Observacion} va en la firma: la regla 10 la busca en los parametros del metodo
     * transaccional, que desde #450 es este y no el del orquestador.
     *
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya pago otro acto (#383)
     */
    @Transactional
    public RevalidarLicenciaDeEdificacion.Revalidacion registrar(
            String expedienteDeRevalidacion,
            LocalDate fecha,
            LocalDate nuevaVigenciaHasta,
            DerechoComprobado derecho,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(derecho, "No se escribe sin el derecho comprobado");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale la resolucion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        RevalidacionLista lista =
                leerYComprobar(expedienteDeRevalidacion, fecha, nuevaVigenciaHasta);
        FueDeEdificacion revalidacion = lista.revalidacion();
        FueDeEdificacion original = lista.original();
        long originalId = original.identificador();
        String numeroDeLicencia = lista.numeroDeLicencia();
        ReciboDeTramite recibo = derecho.recibo();

        // Donde empieza el tramo nuevo lo decide el dominio (#451): hasta ese issue se calculaba
        // aqui en linea, siempre el dia siguiente al ultimo tramo, y con la licencia vencida eso
        // la volvia vigente hacia atras. Se calculo —y se comprobo— en `leerYComprobar`, con los
        // tramos releidos dentro de esta transaccion.
        TramosDeVigencia.TramoSiguiente tramo = lista.tramoNuevo();

        List<VigenciaDeLaLicencia> conLaNueva = new ArrayList<>(lista.anteriores());
        conLaNueva.add(tramo.concedidoPor(originalId, 0L));

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        RevalidarLicenciaDeEdificacion.TIPO_DE_DOCUMENTO,
                        Ejercicio.de(fecha),
                        numeroDeLicencia,
                        ModeloDelFue.deLaRevalidacion(
                                original,
                                numeroDeLicencia,
                                lista.solicitante().nombre(),
                                conLaNueva,
                                fecha,
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
                        MovimientoDeEdificacion.revalidacion(
                                revalidacion.identificador(),
                                fecha,
                                recibo.reciboId(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        // EL RECIBO SE GASTA AQUI (#383). Hasta ese issue, con un solo recibo se prorrogaba la
        // vigencia de la misma obra una y otra vez, con un FUE de revalidacion nuevo en cada
        // tramo: las cinco comprobaciones del derecho pasaban todas las veces.
        GastoDelDerecho.gastar(
                aplicaciones,
                recibo,
                derecho.concepto(),
                "edificacion_movimiento",
                registrado.identificador());

        VigenciaDeLaLicencia concedida =
                movimientos.conceder(
                        originalId,
                        registrado.identificador(),
                        tramo.concedidoPor(originalId, registrado.identificador()));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "edificacion_vigencia",
                                String.valueOf(concedida.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(
                                null,
                                "{\"licencia\":\""
                                        + numeroDeLicencia
                                        + "\",\"expediente\":\""
                                        + revalidacion.expediente()
                                        + "\",\"tramo\":"
                                        + concedida.orden()
                                        + ",\"hasta\":\""
                                        + concedida.hasta()
                                        + "\"}"));

        return new RevalidarLicenciaDeEdificacion.Revalidacion(
                original, revalidacion, numeroDeLicencia, registrado, concedida, emision);
    }

    // ------------------------------------------------------------------

    private RevalidacionLista leerYComprobar(
            String expedienteDeRevalidacion, LocalDate fecha, LocalDate nuevaVigenciaHasta) {

        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(nuevaVigenciaHasta, "La revalidacion dice hasta cuando prorroga");

        FueDeEdificacion revalidacion =
                expedientes
                        .porExpediente(
                                expedienteDeRevalidacion == null
                                        ? ""
                                        : expedienteDeRevalidacion.strip())
                        .orElseThrow(
                                () ->
                                        new EmitirLicenciaDeEdificacion.ExpedienteInexistente(
                                                expedienteDeRevalidacion));

        if (revalidacion.tipoTramite()
                != kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion
                        .REVALIDACION_DE_LICENCIA) {
            throw new RevalidarLicenciaDeEdificacion.NoEsUnaRevalidacion(revalidacion);
        }

        long originalId =
                Objects.requireNonNull(
                        revalidacion.licenciaOrigenId(),
                        "Una revalidacion siempre nombra su licencia original");
        FueDeEdificacion original =
                expedientes
                        .porId(originalId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La licencia original del expediente "
                                                        + revalidacion.expediente()
                                                        + " ya no esta"));

        MovimientoDeEdificacion emisionOriginal =
                movimientos
                        .emisionDe(originalId)
                        .orElseThrow(
                                () ->
                                        new RevalidarLicenciaDeEdificacion.OriginalSinLicencia(
                                                original.expediente()));
        String numeroDeLicencia =
                Objects.requireNonNull(
                        emisionOriginal.numeroLicencia(), "Una emision siempre numera la licencia");

        // #402: de los cinco actos que resuelven sobre uno previo, era el unico que no comparaba
        // la fecha con nada, y la fecha se imprime en la resolucion («Fecha de la revalidacion»)
        // y queda en un movimiento de solo insercion. No se fecha antes de la declaracion que la
        // pide ni de la licencia que prorroga, ni despues de hoy.
        OrdenDeLosActos.exigir(
                "la revalidacion del expediente " + revalidacion.expediente(),
                fecha,
                LocalDate.now(reloj),
                new OrdenDeLosActos.ActoPrevio(
                        "la declaracion del expediente " + revalidacion.expediente(),
                        revalidacion.fechaDeclaracion()),
                new OrdenDeLosActos.ActoPrevio(
                        "la emision de la licencia " + numeroDeLicencia, emisionOriginal.fecha()));

        List<VigenciaDeLaLicencia> anteriores = movimientos.vigenciasDe(originalId);
        for (VigenciaDeLaLicencia tramo : anteriores) {
            if (!nuevaVigenciaHasta.isAfter(tramo.hasta())) {
                throw new RevalidarLicenciaDeEdificacion.ProrrogaQueNoProrroga(
                        numeroDeLicencia, tramo.hasta(), nuevaVigenciaHasta);
            }
        }

        // #451: y ademas tiene que llegar al dia del acto. Se comprueba aqui, en `preparar`, antes
        // de preguntarle nada a `caja`: una revalidacion que solo autoriza el pasado no se cobra.
        TramosDeVigencia.TramoSiguiente tramoNuevo =
                TramosDeVigencia.siguienteTramo(anteriores, fecha, nuevaVigenciaHasta);

        return new RevalidacionLista(
                revalidacion,
                original,
                numeroDeLicencia,
                anteriores,
                tramoNuevo,
                solicitanteDe(revalidacion));
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

    // ------------------------------------------------------------------

    /**
     * La revalidacion comprobada, con lo que hace falta para preguntar a los vecinos y escribir.
     *
     * @param revalidacion el expediente del tramite
     * @param original el expediente de la licencia que se prorroga
     * @param numeroDeLicencia el numero de la licencia; no cambia
     * @param anteriores los tramos que ya tenia, en orden
     * @param tramoNuevo el que la revalidacion concede, con el dia en que empieza (#451)
     * @param solicitante el resumen del padron: su identificador es el que el recibo tiene que
     *     traer
     */
    public record RevalidacionLista(
            FueDeEdificacion revalidacion,
            FueDeEdificacion original,
            String numeroDeLicencia,
            List<VigenciaDeLaLicencia> anteriores,
            TramosDeVigencia.TramoSiguiente tramoNuevo,
            ResumenDeContribuyente solicitante) {

        public RevalidacionLista {
            anteriores = List.copyOf(anteriores);
        }
    }

    /**
     * Lo que {@code normativa} y {@code caja} contestaron, reunido fuera de toda transaccion por
     * {@link RevalidarLicenciaDeEdificacion} (#450).
     *
     * @param concepto el concepto del TUPA de la revalidacion
     * @param recibo el recibo que lo respalda, ya comprobado
     */
    public record DerechoComprobado(String concepto, ReciboDeTramite recibo) {

        public DerechoComprobado {
            Objects.requireNonNull(concepto, "concepto");
            Objects.requireNonNull(recibo, "recibo");
        }
    }
}
