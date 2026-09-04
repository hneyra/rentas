package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Exigibilidad;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra la diligencia de notificación de una resolución de gerencia, con su acuse (#50, RF-065).
 *
 * <h2>De aquí sale el derecho a la sancionadora</h2>
 *
 * <p>La resolución ordinaria concede un plazo de pago; solo vencido ese plazo cabe la sancionadora
 * (pantalla {@code transito_rg_sancionadora}: «segunda resolución, emitida luego de la ordinaria»).
 * Esa cuenta se resuelve aquí, una vez, y su resultado queda escrito en {@code
 * notificacion.exigible_desde} junto con el conjunto sellado del que salió el plazo. La
 * sancionadora lo <b>copia</b> de ahí; no lo recalcula.
 *
 * <p>El plazo entra por {@link PlazosDeSancionesParametrizados}, nunca como constante: un «7»
 * compilado obligaría a desplegar para seguir a la norma, y recalcularía con la cifra de hoy los
 * expedientes de ayer (regla 5). Es el camino que #39 abrió y #41 repitió.
 *
 * <h2>Un intento no hallado no se corrige: se vuelve a diligenciar</h2>
 *
 * <p>Cada diligencia es una fila con su número de intento, y registrar la segunda <b>no toca la
 * primera</b>: no hay {@code UPDATE} en este camino ni privilegio para hacerlo (V28). La garantía
 * no está en este código sino en {@code notificacion_intento_uq}: reintentar «el intento 2» dos
 * veces choca contra el índice en vez de sobrescribir la traza sin que se note.
 *
 * <p>Y solo {@link ResultadoDeNotificacion#NO_UBICADO} deja el plazo sin abrir. Que la negativa a
 * recibir <b>sí</b> surta efecto no es un descuido: el art. 104 a) del TUO del Código Tributario
 * admite la certificación de la negativa como notificación válida, y si no lo hiciera bastaría con
 * cerrar la puerta para que ninguna resolución llegara a producir efecto nunca.
 */
@Service
public class NotificarResolucionDeGerencia {

    private static final String TABLA_AUDITADA = "notificacion";

    private final ResolucionDeGerenciaRepository resoluciones;
    private final NotificacionDeResolucionRepository notificaciones;
    private final PapeletaRepository papeletas;
    private final DirectorioDeContribuyentes contribuyentes;
    private final PlazosDeSancionesParametrizados plazos;
    private final Auditoria auditoria;

    public NotificarResolucionDeGerencia(
            ResolucionDeGerenciaRepository resoluciones,
            NotificacionDeResolucionRepository notificaciones,
            PapeletaRepository papeletas,
            DirectorioDeContribuyentes contribuyentes,
            PlazosDeSancionesParametrizados plazos,
            Auditoria auditoria) {
        this.resoluciones = resoluciones;
        this.notificaciones = notificaciones;
        this.papeletas = papeletas;
        this.contribuyentes = contribuyentes;
        this.plazos = plazos;
        this.auditoria = auditoria;
    }

    /**
     * Registra una diligencia sobre la resolución identificada por el número de su documento.
     *
     * @param numeroDeResolucion el número impreso de la resolución notificada
     * @param peticion los datos de la diligencia
     * @param observacion por qué se registra (regla 10, RNF-052)
     * @throws ResolucionInexistente si no hay ninguna resolución con ese número
     * @throws DiligenciaAnteriorALaResolucion si la diligencia es anterior a la resolución
     * @throws SinDireccion si ni el padrón ni la petición dicen dónde notificar
     */
    @Transactional
    public Diligencia registrar(
            String numeroDeResolucion, Peticion peticion, Observacion observacion) {

        ResolucionDeGerencia resolucion =
                resoluciones
                        .porNumero(numeroDeResolucion.strip())
                        .orElseThrow(() -> new ResolucionInexistente(numeroDeResolucion));

        if (peticion.fechaDeLaDiligencia().isBefore(resolucion.fecha())) {
            throw new DiligenciaAnteriorALaResolucion(resolucion, peticion.fechaDeLaDiligencia());
        }

        int intento = notificaciones.intentosDe(resolucion.identificador()) + 1;

        LocalDate exigibleDesde = null;
        Long conjuntoId = null;
        if (peticion.resultado().surteEfecto()) {
            PlazosDeSancionesParametrizados.Vigentes vigentes =
                    plazos.aLaFechaDe(peticion.fechaDeLaDiligencia());
            Plazo plazo = vigentes.paraCumplirLaOrdinaria();
            CalendarioHabil calendario = vigentes.calendario();
            exigibleDesde =
                    Exigibilidad.derivarDe(peticion.fechaDeLaDiligencia(), plazo, calendario)
                            .exigibleDesde();
            conjuntoId = vigentes.conjuntoId();
        }

        NotificacionDeResolucion guardada =
                notificaciones.insertar(
                        new NotificacionDeResolucion(
                                null,
                                resolucion.identificador(),
                                resolucion.numero() + "/" + intento,
                                intento,
                                peticion.fechaDeLaDiligencia(),
                                peticion.modalidad(),
                                peticion.resultado(),
                                peticion.notificador(),
                                direccionDe(resolucion, peticion),
                                peticion.receptor(),
                                peticion.documentoReceptor(),
                                peticion.vinculo(),
                                peticion.acuse(),
                                exigibleDesde,
                                conjuntoId,
                                null,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fechaDeLaDiligencia(),
                                TABLA_AUDITADA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(resolucion, guardada)));

        return new Diligencia(guardada, resolucion);
    }

    // ------------------------------------------------------------------

    /**
     * Dónde se diligenció.
     *
     * <p>Por omisión, el domicilio fiscal <b>vigente a la fecha de la diligencia</b> del obligado
     * —{@code DirectorioDeContribuyentes.domicilioFiscalDe}, que resuelve la vigencia y no «el
     * último» (#28)—. Quien registra puede dar otro: una cédula se practica a veces en el
     * establecimiento, no en el domicilio fiscal.
     */
    private String direccionDe(ResolucionDeGerencia resolucion, Peticion peticion) {
        String dada = peticion.direccion();
        if (dada != null && !dada.isBlank()) {
            return dada.strip();
        }
        Papeleta papeleta =
                papeletas
                        .porId(resolucion.papeletaId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La resolucion "
                                                        + resolucion.numero()
                                                        + " apunta a una papeleta que no existe"));
        return contribuyentes
                .domicilioFiscalDe(papeleta.obligadoId(), peticion.fechaDeLaDiligencia())
                .filter(direccion -> !direccion.isBlank())
                .orElseThrow(() -> new SinDireccion(resolucion.numero()));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(
            ResolucionDeGerencia resolucion, NotificacionDeResolucion diligencia) {
        return "{\"resolucion\":\""
                + resolucion.numero()
                + "\",\"intento\":"
                + diligencia.intento()
                + ",\"modalidad\":\""
                + diligencia.modalidad()
                + "\",\"resultado\":\""
                + diligencia.resultado()
                + "\",\"exigibleDesde\":"
                + (diligencia.exigibleDesde() == null
                        ? "null"
                        : "\"" + diligencia.exigibleDesde() + "\"")
                + "}";
    }

    /**
     * Lo que la pantalla manda para registrar una diligencia.
     *
     * @param fechaDeLaDiligencia cuándo se diligenció; de ella sale qué conjunto de parámetros rige
     * @param modalidad cómo se diligenció (art. 104)
     * @param resultado con qué resultado terminó
     * @param notificador quién la llevó
     * @param direccion dónde; nulo significa el domicilio fiscal vigente del obligado
     * @param receptor quién recibió, si alguien recibió
     * @param documentoReceptor su documento
     * @param vinculo su vínculo con el administrado
     * @param acuse la constancia del cargo
     */
    public record Peticion(
            LocalDate fechaDeLaDiligencia,
            ModalidadDeNotificacion modalidad,
            ResultadoDeNotificacion resultado,
            String notificador,
            @Nullable String direccion,
            @Nullable String receptor,
            @Nullable String documentoReceptor,
            @Nullable String vinculo,
            @Nullable String acuse) {

        public Peticion {
            Objects.requireNonNull(fechaDeLaDiligencia, "Falta la fecha de la diligencia");
            Objects.requireNonNull(modalidad, "Falta la modalidad de la notificacion");
            Objects.requireNonNull(resultado, "Falta el resultado de la diligencia");
            Objects.requireNonNull(notificador, "Falta el notificador");
        }
    }

    /**
     * La diligencia registrada, con la resolución que notificó.
     *
     * @param notificacion la fila guardada
     * @param resolucion la resolución notificada
     */
    public record Diligencia(
            NotificacionDeResolucion notificacion, ResolucionDeGerencia resolucion) {

        /** Si lo notificado fue la ordinaria y abrió el plazo que da derecho a la sancionadora. */
        public boolean abreElPlazoDeLaSancionadora() {
            return resolucion.tipo() == TipoDeResolucionDeGerencia.ORDINARIA
                    && notificacion.surtioEfecto();
        }
    }

    /** No hay ninguna resolución de gerencia con ese número. */
    public static final class ResolucionInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ResolucionInexistente(String numero) {
            super("No hay ninguna resolucion de gerencia con el numero '" + numero + "'");
        }
    }

    /** Se diligenció antes de dictar la resolución: no puede ser. */
    public static final class DiligenciaAnteriorALaResolucion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DiligenciaAnteriorALaResolucion(ResolucionDeGerencia resolucion, LocalDate fecha) {
            super(
                    "La resolucion "
                            + resolucion.numero()
                            + " se dicto el "
                            + resolucion.fecha()
                            + ": no se pudo notificar el "
                            + fecha);
        }
    }

    /** Ni el padrón ni la petición dicen dónde notificar. */
    public static final class SinDireccion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinDireccion(String resolucion) {
            super(
                    "El obligado no tiene domicilio fiscal vigente y no se dio una direccion: no"
                            + " hay donde notificar la resolucion "
                            + resolucion);
        }
    }
}
