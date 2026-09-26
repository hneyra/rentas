package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.ComprobacionDelTerritorio;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.TerritorioDeLaLicencia;
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe la licencia de funcionamiento con lo que los vecinos ya contestaron (#44, #450).
 *
 * <h2>Solo escribe, y es todo el motivo de que exista</h2>
 *
 * <p>Hasta #450 esto era el cuerpo de {@link EmitirLicenciaDeFuncionamiento#emitir}, que era
 * {@code @Transactional} entero y dentro hacia seis viajes de red: {@code normativa}, {@code caja}
 * y cuatro a {@code catastro}. Cada uno esperaba con la conexion de la peticion tomada, y el ultimo
 * —la ficha economica— con la fila de {@code licencia_correlativo} bloqueada: dos emisiones de la
 * misma municipalidad y el mismo ano se ponian en fila detras de la latencia de {@code catastro}.
 *
 * <p>Ahora {@link EmitirLicenciaDeFuncionamiento} pregunta, sin transaccion, y reune lo que
 * contestaron en una {@link EmisionComprobada}; esta clase la recibe y escribe: el correlativo, el
 * papel, la fila, el gasto del recibo, el movimiento y la auditoria. <b>Aqui no entra ningun puerto
 * que vaya por la red</b>, y por eso el candado del correlativo dura lo que duran estas escrituras
 * y nada mas.
 *
 * <h2>La licencia y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transaccion</b>, con {@link EmitirDocumento}, y guarda
 * los datos con que se dibujo mas el SHA-256 de lo que salio. Es lo que hace que un duplicado
 * pedido en 2034 sea el <b>mismo</b> papel (RF-132) y no uno nuevo con el mismo numero. Una
 * licencia sin documento no se puede entregar; un documento sin licencia no tiene acto que lo
 * explique.
 */
@Service
public class RegistrarLicenciaDeFuncionamiento {

    private final LicenciaRepository licencias;
    private final MovimientoDeLicenciaRepository movimientos;
    private final AplicacionDeRecibos aplicaciones;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeLicencia plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarLicenciaDeFuncionamiento(
            LicenciaRepository licencias,
            MovimientoDeLicenciaRepository movimientos,
            AplicacionDeRecibos aplicaciones,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeLicencia plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.licencias = licencias;
        this.movimientos = movimientos;
        this.aplicaciones = aplicaciones;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Numera, dibuja y guarda la licencia.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de la solicitud: la regla 10 exige que
     * se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional — que desde #450 es este y no el del orquestador.
     *
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya pago otro acto (#383)
     */
    @Transactional
    public EmitirLicenciaDeFuncionamiento.LicenciaEmitida registrar(
            EmitirLicenciaDeFuncionamiento.Solicitud solicitud,
            EmisionComprobada comprobada,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(solicitud, "No se emite sin solicitud");
        Objects.requireNonNull(comprobada, "No se escribe sin lo que contestaron los vecinos");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ResumenDeContribuyente titular = comprobada.titular();
        ReciboDeTramite recibo = comprobada.recibo();
        List<GiroDeLaLicencia> giros = comprobada.giros();

        // El candado de `licencia_correlativo` se toma AQUI y dura hasta el commit (#450). Todo lo
        // que viene detras son escrituras de esta base: ningun vecino se pregunta con el tomado.
        Ejercicio ejercicio = Ejercicio.de(solicitud.fechaEmision());
        String numero = plantilla.componer(ejercicio, licencias.siguienteCorrelativo(ejercicio));

        Instant ahora = reloj.instant();
        LicenciaDeFuncionamiento sinGuardar =
                new LicenciaDeFuncionamiento(
                        null,
                        numero,
                        titular.id(),
                        solicitud.predioId(),
                        comprobada.fichaId(),
                        solicitud.nombreComercial(),
                        solicitud.direccion(),
                        solicitud.areaSolicitada(),
                        solicitud.tipoLicencia(),
                        solicitud.zonificacion(),
                        solicitud.aforo(),
                        solicitud.fechaEmision(),
                        solicitud.vigenciaHasta(),
                        recibo.reciboId(),
                        // Se rellena abajo con el documento recien emitido: el papel se dibuja con
                        // los datos de la licencia, asi que la licencia tiene que existir como
                        // objeto antes que el, y el identificador del documento antes que la fila.
                        0L,
                        solicitud.expediente(),
                        solicitud.fechaExpediente(),
                        ahora,
                        null,
                        observacion,
                        giros,
                        TerritorioDeLaLicencia.de(
                                comprobada.territorio(), solicitud.autorizacionDelTerritorio()));

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        EmitirLicenciaDeFuncionamiento.TIPO_DE_DOCUMENTO,
                        ejercicio,
                        numero,
                        ModeloDeLaLicencia.de(
                                sinGuardar,
                                titular.nombre(),
                                titular.codigo(),
                                titular.documento(),
                                recibo.numero(),
                                giros),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        LicenciaDeFuncionamiento guardada = licencias.emitir(conDocumento(sinGuardar, documentoId));

        // EL RECIBO SE GASTA AQUI (#383), en la misma transaccion y con la licencia ya escrita:
        // la constancia nombra el acto que pago. Hasta #383 el mismo papel respaldaba la
        // licencia del local A y la del local B, porque nadie anotaba que ya se habia usado.
        GastoDelDerecho.gastar(
                aplicaciones,
                recibo,
                comprobada.concepto(),
                "licencia_funcionamiento",
                guardada.identificador());

        MovimientoDeLicencia emisionRegistrada =
                movimientos.registrar(
                        MovimientoDeLicencia.emision(
                                guardada.identificador(),
                                solicitud.fechaEmision(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "licencia_funcionamiento",
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada, recibo, giros)));

        return new EmitirLicenciaDeFuncionamiento.LicenciaEmitida(
                guardada, emisionRegistrada, emision, titular);
    }

    // ------------------------------------------------------------------

    private static LicenciaDeFuncionamiento conDocumento(
            LicenciaDeFuncionamiento licencia, long documentoId) {
        return new LicenciaDeFuncionamiento(
                licencia.id(),
                licencia.numero(),
                licencia.contribuyenteId(),
                licencia.predioId(),
                licencia.fichaId(),
                licencia.nombreComercial(),
                licencia.direccion(),
                licencia.areaSolicitada(),
                licencia.tipoLicencia(),
                licencia.zonificacion(),
                licencia.aforo(),
                licencia.fechaEmision(),
                licencia.vigenciaHasta(),
                licencia.reciboId(),
                documentoId,
                licencia.expediente(),
                licencia.fechaExpediente(),
                licencia.registradoEn(),
                licencia.usuarioRegistro(),
                licencia.observacion(),
                licencia.giros(),
                licencia.territorio());
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            LicenciaDeFuncionamiento licencia,
            ReciboDeTramite recibo,
            List<GiroDeLaLicencia> giros) {
        return "{\"numero\":\""
                + licencia.numero()
                + "\",\"tipo\":\""
                + licencia.tipoLicencia()
                + "\",\"recibo\":\""
                + recibo.numero()
                + "\",\"giros\":"
                + giros.size()
                + ",\"fichaEconomica\":"
                + (licencia.fichaId() == null ? "null" : licencia.fichaId())
                // La marca y no el texto (#418): el texto de la autorizacion lo escribe una
                // persona y puede nombrar a otras, y esta columna va sin datos personales. El
                // texto esta en la licencia, que es donde se impugna.
                + ",\"porExcepcion\":"
                + licencia.territorio().porExcepcion()
                + "}";
    }

    /**
     * Lo que los vecinos contestaron —y lo que la base dijo antes de preguntarles—, reunido fuera
     * de toda transaccion por {@link EmitirLicenciaDeFuncionamiento} (#450).
     *
     * <p>Es un valor y no una lista de argumentos a proposito: dice <b>que</b> se comprobo antes de
     * escribir, y el escritor no puede recibir una emision a medio comprobar porque no hay forma de
     * construir esto sin las seis piezas.
     *
     * @param titular el titular del padron
     * @param giros los giros resueltos contra el catalogo, con el principal marcado
     * @param concepto el concepto del TUPA que el conjunto sellado nombra (de {@code normativa})
     * @param recibo el recibo que respalda el derecho, ya comprobado (de {@code caja})
     * @param territorio lo que {@code catastro} contesto de la zona, el riesgo y el ITSE, ya
     *     decidido: si esto llega aqui, el territorio permite emitir
     * @param fichaId la ficha economica vigente el dia de la emision (de {@code catastro}), o
     *     {@code null} si la solicitud no declara predio o el predio no la tiene
     */
    public record EmisionComprobada(
            ResumenDeContribuyente titular,
            List<GiroDeLaLicencia> giros,
            String concepto,
            ReciboDeTramite recibo,
            ComprobacionDelTerritorio territorio,
            @Nullable Long fichaId) {

        public EmisionComprobada {
            Objects.requireNonNull(titular, "titular");
            giros = List.copyOf(giros);
            Objects.requireNonNull(concepto, "concepto");
            Objects.requireNonNull(recibo, "recibo");
            Objects.requireNonNull(territorio, "territorio");
        }
    }
}
