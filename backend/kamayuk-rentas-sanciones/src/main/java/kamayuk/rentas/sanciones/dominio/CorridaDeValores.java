package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El criterio de una generación masiva de valores por papeletas, congelado (#53, RF-066, RF-073;
 * V47 §1).
 *
 * <h2>Por qué {@link #fechaCriterio} se guarda</h2>
 *
 * <p>Es la fecha a la que se mira la deuda de cada papeleta candidata <b>y</b> a la que se
 * comprueba que el plazo de su resolución venció. Se fija una vez, al registrar la corrida, para
 * que reanudar la generación tres días después seleccione y emita exactamente lo mismo que si
 * hubiera terminado el primer día. Con «hoy» no lo haría: una papeleta cuyo plazo vence mañana
 * entraría en la segunda ejecución y no en la primera, y las dos corridas serían la misma corrida
 * (regla 9, RNF-075).
 *
 * <h2>Una familia, nunca dos</h2>
 *
 * <p>{@code transito_valores} y {@code adm_valores} son dos opciones del menú con dos permisos
 * distintos. Una corrida que cruzara las dos familias emitiría valores de tránsito a quien solo
 * puede emitir los administrativos.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param familia qué mitad de {@code papeleta} recorre
 * @param desde primer día de infracción que entra, inclusive
 * @param hasta último día, inclusive
 * @param fechaCriterio a qué fecha se evalúa la deuda y la exigibilidad
 * @param origen si los candidatos se eligieron a mano o por rango
 * @param totalCandidatos cuántas papeletas entraron
 * @param usuarioRegistro quién la registró; nulo mientras no se ha guardado
 * @param registradoEn cuándo se registró; sale del reloj inyectado, no de un {@code DEFAULT now()}
 * @param observacion por qué se registra (regla 10, RNF-052)
 */
public record CorridaDeValores(
        @Nullable Long id,
        Familia familia,
        LocalDate desde,
        LocalDate hasta,
        LocalDate fechaCriterio,
        OrigenDeLaCorrida origen,
        int totalCandidatos,
        @Nullable String usuarioRegistro,
        Instant registradoEn,
        Observacion observacion) {

    public CorridaDeValores {
        Objects.requireNonNull(familia, "La corrida necesita su familia");
        Objects.requireNonNull(desde, "La corrida necesita su fecha inicial");
        Objects.requireNonNull(hasta, "La corrida necesita su fecha final");
        Objects.requireNonNull(
                fechaCriterio,
                "La corrida congela a que fecha evalua la deuda y el plazo (regla 9, RNF-075)");
        Objects.requireNonNull(origen, "La corrida necesita su origen");
        Objects.requireNonNull(registradoEn, "La corrida dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
        if (totalCandidatos < 0) {
            throw new IllegalArgumentException("El total de candidatos no puede ser negativo");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** Una corrida sin guardar. */
    public static CorridaDeValores nueva(
            Familia familia,
            LocalDate desde,
            LocalDate hasta,
            LocalDate fechaCriterio,
            OrigenDeLaCorrida origen,
            int totalCandidatos,
            Instant registradoEn,
            Observacion observacion) {
        return new CorridaDeValores(
                null,
                familia,
                desde,
                hasta,
                fechaCriterio,
                origen,
                totalCandidatos,
                null,
                registradoEn,
                observacion);
    }

    /** Si todavía no se ha guardado. */
    public boolean esNueva() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "La corrida todavia no se ha guardado");
    }

    /**
     * La resolución de gerencia que ordena la cobranza en esta familia.
     *
     * <p>En tránsito es la {@link TipoDeResolucionDeGerencia#ORDINARIA} —«la que ordena la cobranza
     * de la papeleta de tránsito y abre el plazo de pago»—; en administrativa, la del procedimiento
     * sancionador municipal. No hay una tercera respuesta, y por eso vive aquí y no repartida por
     * los casos de uso.
     */
    public TipoDeResolucionDeGerencia resolucionQueOrdenaLaCobranza() {
        return switch (familia) {
            case TRANSITO -> TipoDeResolucionDeGerencia.ORDINARIA;
            case ADMINISTRATIVA -> TipoDeResolucionDeGerencia.ADMINISTRATIVA;
        };
    }
}
