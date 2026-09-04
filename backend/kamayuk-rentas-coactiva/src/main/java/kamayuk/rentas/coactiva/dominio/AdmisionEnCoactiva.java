package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Que valores admite un expediente coactivo, y por que rechaza los demas (#40, RF-100).
 *
 * <h2>El criterio, y por que este</h2>
 *
 * <p>El issue lo enuncia como «notificados y con plazo vencido, y con el movimiento de pase a
 * coactiva». Las dos primeras condiciones <b>ya las exige el pase</b>: {@code
 * valores.PasarACoactiva} rechaza un valor sin diligencia que surta efecto y rechaza un pase
 * anterior a la exigibilidad, y ademas lo rechaza la base ({@code valor_movimiento_exigible_ck},
 * V28). Asi que el criterio efectivo es <b>uno solo y verificable</b>:
 *
 * <blockquote>
 * el valor tiene su movimiento {@code PCO}, y no esta ya en un expediente.
 * </blockquote>
 *
 * <p>Comprobar ademas «notificado» y «plazo vencido» por separado aqui seria escribir una segunda
 * vez la regla que #39 ya escribio, y dos escrituras de la misma regla es como se llega a que la
 * importacion admita lo que el pase rechaza —o al reves—. Lo que si se hace es <b>distinguir el
 * motivo</b>: un valor sin pase se rechaza diciendo si le falta la notificacion, si el plazo aun
 * corre o si solo le falta el pase, porque las tres se arreglan de maneras distintas.
 *
 * <p>Funcion pura (regla 6): entran la situacion mirada a una fecha, si hay pase y si ya esta en un
 * expediente, y sale el motivo. Sin base, sin reloj y sin configuracion.
 *
 * <h2>La situacion entra como texto</h2>
 *
 * <p>Y no como {@code SituacionDelValor}: ese tipo vive en {@code valores.dominio} y no cruza la
 * frontera del modulo (ARQ-01 §4). Lo que cruza es su nombre, que es lo que {@code
 * ValorParaCoactiva} publica.
 */
public final class AdmisionEnCoactiva {

    /** Lo que {@code SituacionDelValor} llama a un valor sin notificar. */
    private static final String EMITIDO = "EMITIDO";

    /** Notificado, con el plazo todavia corriendo. */
    private static final String NOTIFICADO = "NOTIFICADO";

    /** El plazo vencio; lo que el prototipo llama «FIRME». */
    private static final String EXIGIBLE = "EXIGIBLE";

    /** Ya pasado a coactiva. */
    private static final String COACTIVA = "COACTIVA";

    private AdmisionEnCoactiva() {}

    /**
     * El motivo por el que ese valor no entra, o vacio si entra.
     *
     * @param situacion el nombre de la situacion del valor, mirada a la fecha de la importacion
     * @param conPaseACoactiva si el valor tiene su movimiento {@code PCO} (#39, V28)
     * @param yaEnUnExpediente si el valor ya vive en un expediente coactivo
     */
    public static Optional<MotivoDeRechazo> rechazo(
            String situacion, boolean conPaseACoactiva, boolean yaEnUnExpediente) {

        String normalizada =
                Objects.requireNonNull(situacion, "La situacion es obligatoria")
                        .strip()
                        .toUpperCase(Locale.ROOT);

        // Primero lo que ya esta resuelto: reintentar no duplica, y decirlo antes que cualquier
        // otra cosa es lo que hace que el segundo intento se lea como «ya estaba», no como un
        // error nuevo.
        if (yaEnUnExpediente) {
            return Optional.of(MotivoDeRechazo.YA_EN_UN_EXPEDIENTE);
        }

        return switch (normalizada) {
            case EMITIDO -> Optional.of(MotivoDeRechazo.SIN_NOTIFICAR);
            case NOTIFICADO -> Optional.of(MotivoDeRechazo.PLAZO_VIGENTE);
            case EXIGIBLE -> Optional.of(MotivoDeRechazo.SIN_PASE_A_COACTIVA);
            case COACTIVA ->
                    // La situacion dice COACTIVA tambien cuando la columna `valor.estado` lo dice
                    // y el movimiento falta. No deberia pasar -#39 escribe los dos juntos-, pero
                    // el expediente se sustenta en el MOVIMIENTO, que es el que copia la
                    // diligencia y la exigibilidad. Sin el, no hay de donde sacar el sustento.
                    conPaseACoactiva
                            ? Optional.empty()
                            : Optional.of(MotivoDeRechazo.SIN_PASE_A_COACTIVA);
            default -> Optional.of(MotivoDeRechazo.NO_COBRABLE);
        };
    }

    /** Si ese valor entra en el expediente. */
    public static boolean admite(
            String situacion, boolean conPaseACoactiva, boolean yaEnUnExpediente) {
        return rechazo(situacion, conPaseACoactiva, yaEnUnExpediente).isEmpty();
    }
}
