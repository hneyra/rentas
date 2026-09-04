package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.ModalidadDeAprobacion;
import kamayuk.rentas.licencias.dominio.RepresentanteLegal;
import kamayuk.rentas.licencias.dominio.RevisionDelProyecto;
import kamayuk.rentas.licencias.dominio.TipoDeObra;
import kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Presenta un Formulario Unico de Edificaciones: da de alta el expediente (#48 AC 1, RF-113).
 *
 * <h2>Solo la cabecera, y es el punto</h2>
 *
 * <p>Lo que se registra aqui son las tres secciones que existen desde el primer minuto —licencia,
 * solicitante y representante legal— y nada mas. El terreno, el proyecto, la valorizacion, los
 * profesionales y los documentos <b>se completan despues</b>, cada uno cuando el administrado lo
 * trae, y de eso se ocupa {@link CompletarSeccionDelFue}. Exigirlos aqui haria imposible el AC 1:
 * «las secciones del FUE se pueden completar por partes».
 *
 * <h2>Ni numero ni recibo</h2>
 *
 * <p>Presentar un FUE <b>no</b> otorga nada, asi que no numera ninguna licencia ni comprueba ningun
 * derecho de tramite. Las dos cosas pasan al emitir ({@link EmitirLicenciaDeEdificacion}), y
 * ponerlas aqui quemaria un correlativo por cada formulario presentado —incluidos los anteproyectos
 * en consulta, que no llegan a licencia nunca—.
 *
 * <h2>Una ampliacion referencia, no sustituye (AC 3)</h2>
 *
 * <p>Un FUE de ampliacion o de revalidacion nombra la licencia original y se comprueba que exista y
 * que este emitida. Lo que <b>no</b> se hace, y no se puede hacer, es tocar la original: V43 le
 * retira el {@code UPDATE} a {@code licencia_edificacion}.
 */
@Service
public class PresentarFue {

    private final FueRepository expedientes;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Auditoria auditoria;
    private final Clock reloj;

    public PresentarFue(
            FueRepository expedientes,
            DirectorioDeContribuyentes contribuyentes,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.contribuyentes = contribuyentes;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Presenta el formulario.
     *
     * @throws SolicitanteDesconocido si el codigo de contribuyente no esta en el padron
     * @throws LicenciaOriginalInexistente si la ampliacion o la revalidacion nombran una licencia
     *     que no existe, o un expediente que todavia no llego a licencia
     */
    @Transactional
    public FueDeEdificacion presentar(Solicitud solicitud, Observacion observacion) {
        Objects.requireNonNull(solicitud, "No se presenta sin solicitud");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ResumenDeContribuyente solicitante =
                contribuyentes
                        .porCodigo(solicitud.codigoContribuyente())
                        .orElseThrow(
                                () -> new SolicitanteDesconocido(solicitud.codigoContribuyente()));

        Long origenId = null;
        if (solicitud.tipoTramite().exigeLicenciaOriginal()) {
            FueDeEdificacion original =
                    expedientes
                            .porNumeroDeLicencia(textoDe(solicitud.numeroLicenciaAnterior()))
                            .orElseThrow(
                                    () ->
                                            new LicenciaOriginalInexistente(
                                                    solicitud.numeroLicenciaAnterior(),
                                                    solicitud.tipoTramite()));
            origenId = original.identificador();
        }

        FueDeEdificacion presentado =
                expedientes.presentar(
                        new FueDeEdificacion(
                                null,
                                solicitud.expediente(),
                                solicitud.fechaDeclaracion(),
                                solicitante.id(),
                                solicitud.predioId(),
                                solicitud.tipoTramite(),
                                solicitud.tipoObra(),
                                solicitud.modalidad(),
                                solicitud.revision(),
                                solicitud.expedienteAnterior(),
                                origenId,
                                solicitud.solicitantePropietario(),
                                solicitud.representante(),
                                reloj.instant(),
                                null,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                solicitud.fechaDeclaracion(),
                                "licencia_edificacion",
                                String.valueOf(presentado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(presentado)));

        return presentado;
    }

    // ------------------------------------------------------------------

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(FueDeEdificacion fue) {
        return "{\"expediente\":\""
                + fue.expediente()
                + "\",\"tramite\":\""
                + fue.tipoTramite()
                + "\",\"obra\":\""
                + fue.tipoObra()
                + "\",\"modalidad\":\""
                + fue.modalidad()
                + "\",\"origen\":"
                + (fue.licenciaOrigenId() == null ? "null" : fue.licenciaOrigenId())
                + "}";
    }

    private static String textoDe(@Nullable String numero) {
        return numero == null ? "" : numero.strip();
    }

    /**
     * Lo que se pide para presentar un FUE.
     *
     * @param expediente el numero de expediente
     * @param fechaDeclaracion el dia de la declaracion; entra como argumento (regla 6)
     * @param codigoContribuyente el solicitante, tal como lo teclea la pantalla
     * @param predioId el predio donde se construye; opcional
     * @param tipoTramite cual de los cinco tramites
     * @param tipoObra que obra
     * @param modalidad la modalidad de aprobacion declarada
     * @param revision quien revisa; opcional
     * @param expedienteAnterior el expediente previo; opcional
     * @param numeroLicenciaAnterior la licencia original; obligatorio en ampliacion y revalidacion
     * @param solicitantePropietario si el solicitante es el propietario del terreno
     * @param representante el representante legal; opcional
     */
    public record Solicitud(
            String expediente,
            LocalDate fechaDeclaracion,
            String codigoContribuyente,
            @Nullable Long predioId,
            TipoDeTramiteDeEdificacion tipoTramite,
            TipoDeObra tipoObra,
            ModalidadDeAprobacion modalidad,
            @Nullable RevisionDelProyecto revision,
            @Nullable String expedienteAnterior,
            @Nullable String numeroLicenciaAnterior,
            boolean solicitantePropietario,
            @Nullable RepresentanteLegal representante) {

        public Solicitud {
            Objects.requireNonNull(expediente, "El FUE se presenta con su expediente");
            Objects.requireNonNull(fechaDeclaracion, "La fecha entra como argumento (regla 6)");
            Objects.requireNonNull(codigoContribuyente, "El FUE lo presenta un solicitante");
            Objects.requireNonNull(tipoTramite, "Hay que decir de que tramite es");
            Objects.requireNonNull(tipoObra, "Hay que decir que obra se autoriza");
            Objects.requireNonNull(modalidad, "Hay que decir en que modalidad se aprueba");
        }
    }

    /** El codigo de contribuyente no esta en el padron de esta municipalidad. */
    public static final class SolicitanteDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SolicitanteDesconocido(String codigo) {
            super(
                    "No hay ningun contribuyente con codigo '"
                            + codigo
                            + "' en esta municipalidad: un FUE lo presenta un administrado del"
                            + " padron");
        }
    }

    /** La ampliacion o la revalidacion nombran una licencia que no existe. */
    public static final class LicenciaOriginalInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LicenciaOriginalInexistente(
                @Nullable String numero, TipoDeTramiteDeEdificacion tipoTramite) {
            super(
                    "No hay ninguna licencia de edificacion "
                            + (numero == null || numero.isBlank() ? "(sin numero)" : numero)
                            + " emitida en esta municipalidad, asi que este tramite de "
                            + tipoTramite.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + " no se apoya en nada. Una ampliacion referencia la licencia original"
                            + " sin sustituirla (AC 3 de #48), y para referenciarla tiene que"
                            + " existir");
        }
    }
}
