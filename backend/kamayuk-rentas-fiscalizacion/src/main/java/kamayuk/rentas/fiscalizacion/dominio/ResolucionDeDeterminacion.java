package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La transferencia a rentas de un resultado de fiscalizacion, y la resolucion de determinacion que
 * la materializa (#52, RF-054, RF-057).
 *
 * <h2>Una sola cosa, no dos</h2>
 *
 * <p>El acto administrativo que determina de oficio y el efecto de ese acto sobre el padron son la
 * misma cosa vista desde dos sitios. Por eso hay una fila y no dos: separarlas en «transferencia» y
 * «resolucion» habria producido dos registros 1:1 que nadie puede desincronizar sin que el otro
 * mienta, y una pregunta sin respuesta —cual de los dos se notifica—. Es el patron de {@code
 * ResolucionDeGerencia} (#50) y de {@code ActoCoactivo} (#41).
 *
 * <h2>Por que no es un {@code valor} de tipo RD</h2>
 *
 * <p>Porque un valor <b>formaliza</b> una deuda que ya esta asentada —{@code RegistrarValor} la lee
 * del libro y le mueve la fase— y esta resolucion es el acto que la <b>asienta</b>. Emitirla como
 * valor exigiria que la deuda existiera antes del acto que la determina. Una vez asentado el cargo,
 * {@code valores} puede formalizarlo por el camino ordinario de #37: la resolucion de fiscalizacion
 * determina, el valor formaliza.
 *
 * <h2>Deja constancia de que version cerro y cual abrio</h2>
 *
 * <p>{@link #fichaAnteriorId} y {@link #fichaNuevaId} son lo que ata la version nueva del padron al
 * acto que la justifica (AC 2), y lo que permite responder «como estaba antes» sin recorrer fechas
 * (AC 5). Una transferencia vehicular no versiona ficha alguna y los lleva nulos; una predial los
 * lleva los dos, y lo exige {@code resolucion_determinacion_version_ck} (V49).
 *
 * <h2>Ni un importe</h2>
 *
 * <p>Como la liquidacion de la que sale. Lo que se asienta lo dicen las lineas de la liquidacion, y
 * lo que se cobra lo dice el libro: guardar aqui un total seria una tercera cifra que puede
 * discrepar de las otras dos, y la que se cobra en ventanilla es la del libro (la leccion de {@code
 * costa_procesal}, #42).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param numero el numero del documento emitido, que es el de la resolucion
 * @param documentoId el papel que la materializa
 * @param liquidacionId el resultado que se transfiere; unico por resolucion (AC 6)
 * @param contribuyenteId a quien se le determina
 * @param predioId la unidad, si la fiscalizacion es predial
 * @param vehiculoId la unidad, si es vehicular
 * @param fichaAnteriorId la version de ficha que este acto cerro; nula en una vehicular
 * @param fichaNuevaId la version de ficha que este acto abrio; nula en una vehicular
 * @param fecha el dia de la resolucion, no el de su registro
 * @param documentoSustento el papel que sustenta el acto (AC 3); sin el no se transfiere
 * @param sustento el fundamento de la determinacion
 * @param baseLegal la norma que la ampara, tal como la cita quien resuelve
 * @param usuarioRegistro quien la registro; nulo mientras no se ha guardado
 * @param observacion por que se registra (regla 10)
 */
public record ResolucionDeDeterminacion(
        @Nullable Long id,
        String numero,
        long documentoId,
        long liquidacionId,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaAnteriorId,
        @Nullable Long fichaNuevaId,
        LocalDate fecha,
        String documentoSustento,
        String sustento,
        String baseLegal,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int NUMERO_MAXIMO = 40;
    private static final int SUSTENTO_DOCUMENTAL_MAXIMO = 80;
    private static final int SUSTENTO_MAXIMO = 1000;
    private static final int BASE_LEGAL_MAXIMA = 200;

    public ResolucionDeDeterminacion {
        Objects.requireNonNull(numero, "La resolucion necesita su numero");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "Una resolucion sin documento no se puede notificar: el papel y el acto nacen"
                            + " juntos");
        }
        if (liquidacionId <= 0) {
            throw new IllegalArgumentException(
                    "La resolucion necesita la liquidacion que transfiere");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("La resolucion necesita a quien se le determina");
        }
        if ((predioId == null) == (vehiculoId == null)) {
            throw new IllegalArgumentException(
                    "Una transferencia es de un predio o de un vehiculo, nunca de los dos ni de"
                            + " ninguno");
        }
        if ((fichaAnteriorId == null) != (fichaNuevaId == null)) {
            throw new IllegalArgumentException(
                    "Una transferencia deja las dos versiones o ninguna: media es un padron que"
                            + " cambio sin decir desde que version");
        }
        if ((predioId != null) != (fichaNuevaId != null)) {
            throw new IllegalArgumentException(
                    "Una transferencia predial SIEMPRE versiona la ficha, y una vehicular no"
                            + " versiona ninguna: registrarla sin haber tocado el padron seria"
                            + " registrar el acto sin su efecto");
        }
        if (fichaAnteriorId != null && fichaAnteriorId.equals(fichaNuevaId)) {
            throw new IllegalArgumentException(
                    "La version que se abre no puede ser la que se cierra: eso es sobrescribir");
        }
        Objects.requireNonNull(fecha, "La resolucion necesita su fecha");

        documentoSustento =
                exigir(
                        documentoSustento,
                        SUSTENTO_DOCUMENTAL_MAXIMO,
                        "Sin sustento documental no se transfiere: lo hallado sobrescribe lo"
                                + " declarado, y el papel que lo respalda es lo unico que el"
                                + " contribuyente puede pedir");
        sustento = exigir(sustento, SUSTENTO_MAXIMO, "La resolucion necesita su fundamento");
        baseLegal = exigir(baseLegal, BASE_LEGAL_MAXIMA, "La resolucion necesita su base legal");

        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda una resolucion (regla 10, RNF-052)");
    }

    /** Una resolucion de una fiscalizacion predial, todavia sin guardar. */
    public static ResolucionDeDeterminacion predial(
            String numero,
            long documentoId,
            long liquidacionId,
            long contribuyenteId,
            long predioId,
            long fichaAnteriorId,
            long fichaNuevaId,
            LocalDate fecha,
            String documentoSustento,
            String sustento,
            String baseLegal,
            Observacion observacion) {
        return new ResolucionDeDeterminacion(
                null,
                numero,
                documentoId,
                liquidacionId,
                contribuyenteId,
                predioId,
                null,
                fichaAnteriorId,
                fichaNuevaId,
                fecha,
                documentoSustento,
                sustento,
                baseLegal,
                null,
                observacion);
    }

    /** Una resolucion de una fiscalizacion vehicular: no versiona ninguna ficha. */
    public static ResolucionDeDeterminacion vehicular(
            String numero,
            long documentoId,
            long liquidacionId,
            long contribuyenteId,
            long vehiculoId,
            LocalDate fecha,
            String documentoSustento,
            String sustento,
            String baseLegal,
            Observacion observacion) {
        return new ResolucionDeDeterminacion(
                null,
                numero,
                documentoId,
                liquidacionId,
                contribuyenteId,
                null,
                vehiculoId,
                null,
                null,
                fecha,
                documentoSustento,
                sustento,
                baseLegal,
                null,
                observacion);
    }

    public boolean esPredial() {
        return predioId != null;
    }

    /** El identificador ya asignado. Falla si todavia no se guardo. */
    public long identificador() {
        return Objects.requireNonNull(id, "La resolucion todavia no se ha guardado");
    }

    private static String exigir(String texto, int maximo, String motivo) {
        Objects.requireNonNull(texto, motivo);
        String limpio = texto.strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(motivo);
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException("No puede superar " + maximo + " caracteres");
        }
        return limpio;
    }
}
