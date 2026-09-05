package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La cabecera del Formulario Unico de Edificaciones: el <b>expediente</b> (#48, RF-113).
 *
 * <h2>No es la licencia</h2>
 *
 * <p>Un FUE nace al presentarse, se completa por partes y <b>recien entonces</b> se emite. Por eso
 * no tiene aqui ni numero de licencia, ni fecha de emision, ni recibo: los tres son atributos del
 * acto de emision y viven en {@link MovimientoDeEdificacion}. Ponerlos aqui obligaria a numerar
 * expedientes que pueden no llegar a ser licencia —un anteproyecto en consulta no lo es nunca— y a
 * quemar un correlativo por cada formulario presentado.
 *
 * <h2>No se edita</h2>
 *
 * <p>V43 le retira a {@code kamayuk_app} el privilegio de {@code UPDATE}, y el escaner de fuentes
 * rechaza cualquier {@code UPDATE licencia_edificacion SET} antes de que llegue a ejecutarse. Lo
 * que cambia mientras el expediente se tramita son sus <b>secciones</b>, que se versionan.
 *
 * <h2>Su estado no esta aqui</h2>
 *
 * <p>No hay ningun campo {@code estado}: se deriva de sus movimientos y sus vigencias, y de la
 * fecha a la que se pregunte ({@link EstadoDelFue#derivarDe}). Ver V43 §1.
 *
 * @param id nulo mientras no se haya guardado
 * @param expediente el numero de expediente con que se presento; identifica el tramite
 * @param fechaDeclaracion el dia de la declaracion del administrado
 * @param contribuyenteId el solicitante
 * @param predioId el predio donde se construye; opcional, porque hay terrenos sin empadronar
 * @param tipoTramite cual de los cinco tramites es
 * @param tipoObra que obra se autoriza
 * @param modalidad la modalidad de aprobacion declarada
 * @param revision quien revisa el proyecto; nulo mientras no se decida
 * @param expedienteAnterior el expediente previo, cuando el tramite se apoya en otro
 * @param licenciaOrigenId el FUE de la licencia original; obligatorio en ampliacion y revalidacion
 * @param solicitantePropietario si el solicitante es el propietario del terreno
 * @param representante el representante legal; opcional
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro; sale del origen de la sesion
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record FueDeEdificacion(
        @Nullable Long id,
        String expediente,
        LocalDate fechaDeclaracion,
        long contribuyenteId,
        @Nullable Long predioId,
        TipoDeTramiteDeEdificacion tipoTramite,
        TipoDeObra tipoObra,
        ModalidadDeAprobacion modalidad,
        @Nullable RevisionDelProyecto revision,
        @Nullable String expedienteAnterior,
        @Nullable Long licenciaOrigenId,
        boolean solicitantePropietario,
        @Nullable RepresentanteLegal representante,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code licencia_edificacion.expediente varchar(20)} (V43). */
    public static final int EXPEDIENTE_MAXIMO = 20;

    public FueDeEdificacion {
        Objects.requireNonNull(expediente, "Un FUE sin expediente no se puede seguir");
        Objects.requireNonNull(fechaDeclaracion, "La fecha entra como argumento (regla 6)");
        Objects.requireNonNull(tipoTramite, "El FUE dice de que tramite es");
        Objects.requireNonNull(tipoObra, "El FUE dice que obra se autoriza");
        Objects.requireNonNull(modalidad, "El FUE dice en que modalidad se aprueba");
        Objects.requireNonNull(registradoEn, "El FUE dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        expediente = expediente.strip().toUpperCase(java.util.Locale.ROOT);
        expedienteAnterior = vacioEsNulo(expedienteAnterior);

        if (expediente.isEmpty()) {
            throw new IllegalArgumentException("El numero de expediente no puede estar vacio");
        }
        if (expediente.length() > EXPEDIENTE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El expediente '"
                            + expediente
                            + "' excede los "
                            + EXPEDIENTE_MAXIMO
                            + " caracteres de licencia_edificacion.expediente");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("El FUE lo presenta un solicitante concreto");
        }
        if (tipoTramite.exigeLicenciaOriginal() && licenciaOrigenId == null) {
            throw new IllegalArgumentException(
                    "Un tramite de "
                            + tipoTramite.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + " tiene que decir sobre que licencia se apoya: sin ella no amplia ni"
                            + " prorroga nada, y nadie puede relacionarlo con la obra que ya estaba"
                            + " autorizada (AC 3, AC 4 de #48)");
        }
        if (!tipoTramite.exigeLicenciaOriginal() && licenciaOrigenId != null) {
            throw new IllegalArgumentException(
                    "Un tramite de "
                            + tipoTramite.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + " no se apoya en ninguna licencia anterior: nombrar una haria pensar"
                            + " que la sustituye");
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Un FUE sin guardar no tiene identificador");
        }
        return guardado;
    }

    /** El mismo FUE con su identificador, tal como vuelve de la base. */
    public FueDeEdificacion con(long identificador) {
        return new FueDeEdificacion(
                identificador,
                expediente,
                fechaDeclaracion,
                contribuyenteId,
                predioId,
                tipoTramite,
                tipoObra,
                modalidad,
                revision,
                expedienteAnterior,
                licenciaOrigenId,
                solicitantePropietario,
                representante,
                registradoEn,
                usuarioRegistro,
                observacion);
    }

    private static @Nullable String vacioEsNulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
