package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * De las resoluciones de una papeleta, la que ordena su cobranza en esta familia, si alguna la
     * ordena (#384).
     *
     * <h2>Por qué es una política y no una consulta</h2>
     *
     * <p>Hasta #384 la respuesta estaba escondida en la cardinalidad de una lectura: se pedía al
     * repositorio «la» resolución del {@linkplain #resolucionQueOrdenaLaCobranza() tipo} y se
     * suponía que había una. El esquema lo garantiza para la ordinaria ({@code
     * resolucion_gerencia_ordinaria_uq}) y <b>no</b> para la administrativa: la RIS y la resolución
     * que resuelve una reconsideración, una apelación o una nulidad contra ella son del mismo tipo,
     * y un doble envío de «Emitir RIS» deja dos RIS. Con dos filas la lectura lanzaba {@code
     * IncorrectResultSizeDataAccessException}, la corrida lo contaba como un fallo pasajero y la
     * multa firme no se formalizaba nunca.
     *
     * <h2>La regla</h2>
     *
     * <ol>
     *   <li>Se miran solo las del tipo que ordena la cobranza en la familia, en el orden en que se
     *       dictaron: por {@code fecha} y, a igual fecha, por identificador —la que se registró
     *       después es la posterior—. El orden lo pone esta política y no quien le pasa la lista.
     *   <li>Si la última que resolvió un recurso <b>dejó la multa sin efecto</b>, ninguna ordena la
     *       cobranza: ver {@link #laQueDejoLaMultaSinEfecto}.
     *   <li>Si no, la ordena <b>la última</b>. Ninguna posterior la deja sin efecto, porque dejarla
     *       sin efecto exige resolver un recurso y la última que lo hizo no lo hizo.
     * </ol>
     *
     * <h2>Qué notificación abre el plazo después de resolverse un recurso</h2>
     *
     * <p>La de <b>la resolución que lo resuelve</b>, que es la última, y no la de la RIS. Resolver
     * una reconsideración con un «se mantiene» es un acto nuevo que se notifica y que a su vez se
     * puede impugnar en quince días hábiles (art. 218.2 del TUO de la Ley 27444, D.S.
     * 004-2019-JUS); solo vencido ese plazo sin recurso queda firme (art. 222), y solo una
     * obligación establecida por un acto firme es exigible en coactiva (art. 9.1 del TUO de la Ley
     * 26979, D.S. 018-2008-JUS: el acto «en el que hubiere recaído resolución firme confirmando la
     * obligación»). Formalizar con el plazo de la RIS emitiría el valor mientras lo resuelto
     * todavía se puede apelar.
     *
     * <p>Con el doble clic la última es la segunda RIS, y es a ella a la que se le pide la
     * notificación. Eso puede dejar el candidato en {@code NO_PROCEDE} —«no consta notificada»—
     * aunque la primera sí lo esté: es la respuesta segura, porque esta política no puede
     * distinguir un duplicado de una RIS que se volvió a dictar, y la salida es notificarla.
     *
     * <p>Es una función pura (regla 6): sin base de datos y sin reloj.
     *
     * @param deLaPapeleta todas las resoluciones de la papeleta, de cualquier tipo y en cualquier
     *     orden; todas ya guardadas
     */
    public Optional<ResolucionDeGerencia> laQueOrdenaLaCobranza(
            List<ResolucionDeGerencia> deLaPapeleta) {
        if (laQueDejoLaMultaSinEfecto(deLaPapeleta).isPresent()) {
            return Optional.empty();
        }
        List<ResolucionDeGerencia> delTipo = delTipoQueOrdenaLaCobranza(deLaPapeleta);
        return delTipo.isEmpty() ? Optional.empty() : Optional.of(delTipo.get(delTipo.size() - 1));
    }

    /**
     * La resolución que dejó la multa sin efecto, si la <b>última</b> de las del tipo que ordena la
     * cobranza que resolvió un recurso lo hizo así (#384).
     *
     * <p>Es la mitad de {@link #laQueOrdenaLaCobranza} que dice <b>por qué</b> no hay ninguna: el
     * candidato sale {@code NO_PROCEDE} nombrándola, y no como si nunca se hubiera dictado nada.
     * Una anterior que la dejó sin efecto, seguida de otra que resolvió manteniéndola, no cuenta:
     * decide la última que falló.
     *
     * @param deLaPapeleta todas las resoluciones de la papeleta, de cualquier tipo y en cualquier
     *     orden; todas ya guardadas
     */
    public Optional<ResolucionDeGerencia> laQueDejoLaMultaSinEfecto(
            List<ResolucionDeGerencia> deLaPapeleta) {
        ResolucionDeGerencia ultimaConFallo = null;
        for (ResolucionDeGerencia resolucion : delTipoQueOrdenaLaCobranza(deLaPapeleta)) {
            if (resolucion.efecto() != null) {
                ultimaConFallo = resolucion;
            }
        }
        return ultimaConFallo != null && ultimaConFallo.dejaLaMultaSinEfecto()
                ? Optional.of(ultimaConFallo)
                : Optional.empty();
    }

    /** Las del tipo que ordena la cobranza, en el orden en que se dictaron. */
    private List<ResolucionDeGerencia> delTipoQueOrdenaLaCobranza(
            List<ResolucionDeGerencia> deLaPapeleta) {
        Objects.requireNonNull(deLaPapeleta, "Hacen falta las resoluciones de la papeleta");
        TipoDeResolucionDeGerencia tipo = resolucionQueOrdenaLaCobranza();
        return deLaPapeleta.stream()
                .filter(resolucion -> resolucion.tipo() == tipo)
                .sorted(
                        Comparator.comparing(ResolucionDeGerencia::fecha)
                                .thenComparingLong(ResolucionDeGerencia::identificador))
                .toList();
    }
}
