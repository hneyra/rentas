package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La declaracion jurada del contribuyente: el documento del que cuelga toda la determinacion
 * predial, y cuya fecha decide si hay multa tributaria (RF-023, #28).
 *
 * <p><b>No calcula nada.</b> {@code fueraDePlazo} es una comparacion de fechas, no un importe: el
 * de la multa es D-02 y no vive aqui (ver {@link PoliticaDeMultaPorPlazo}). {@code fechaLimite} no
 * la decide este tipo —vendria de un literal en el codigo, que la regla 5 prohibe—: la resuelve
 * quien construye el objeto, leyendola de los parametros sellados del ejercicio.
 *
 * <p><b>Nunca se edita en el sitio.</b> Corregir una DJ ya presentada es {@link #rectificadaPor}:
 * dos filas, la anterior {@code SUSTITUIDA} y la nueva {@code PRESENTADA}, igual que una ficha
 * catastral no se sobrescribe (#18) y un asiento equivocado se reversa (ADR-0006).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param numero el numero de la DJ, unico por ejercicio
 * @param ejercicio el ejercicio que declara
 * @param contribuyenteId el declarante
 * @param tipo el formulario
 * @param predioId el predio declarado, si el tipo es HR, PU o PR
 * @param vehiculoId el vehiculo declarado, si el tipo es VEHICULAR
 * @param fichaCatastralId la version de {@code ficha_catastral} vigente a {@code
 *     fechaPresentacion}; nulo si el predio no tiene ficha registrada todavia, o si el tipo no es
 *     predial
 * @param fechaPresentacion cuando se presento
 * @param fechaLimite el plazo parametrizado del ejercicio, ya resuelto por quien construye
 * @param estado en que situacion esta
 * @param djRectificaId la DJ que esta rectifica, si {@code tipo} es RECTIFICATORIA
 * @param usuarioRegistro quien la registro; nulo en una DJ que todavia no se guardo
 * @param observacion por que se registra (regla 10)
 */
public record DeclaracionJurada(
        @Nullable Long id,
        String numero,
        Ejercicio ejercicio,
        long contribuyenteId,
        TipoDeDeclaracion tipo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaCatastralId,
        LocalDate fechaPresentacion,
        LocalDate fechaLimite,
        EstadoDeDeclaracion estado,
        @Nullable Long djRectificaId,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int NUMERO_MAXIMO = 20;

    public DeclaracionJurada {
        Objects.requireNonNull(numero, "La declaracion jurada necesita su numero");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        Objects.requireNonNull(ejercicio, "La declaracion jurada necesita su ejercicio");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "La declaracion jurada tiene un declarante: el identificador de contribuyente"
                            + " debe ser positivo");
        }
        Objects.requireNonNull(tipo, "La declaracion jurada necesita su tipo (HR, PU, PR...)");
        if (tipo == TipoDeDeclaracion.VEHICULAR && predioId != null) {
            throw new IllegalArgumentException("Una declaracion VEHICULAR no lleva predio");
        }
        if (tipo != TipoDeDeclaracion.VEHICULAR && vehiculoId != null) {
            throw new IllegalArgumentException("Solo una declaracion VEHICULAR lleva vehiculo");
        }
        if (tipo == TipoDeDeclaracion.RECTIFICATORIA) {
            Objects.requireNonNull(
                    djRectificaId, "Una rectificatoria tiene que decir que DJ sustituye");
        } else if (djRectificaId != null) {
            throw new IllegalArgumentException(
                    "Solo una declaracion RECTIFICATORIA referencia otra DJ");
        }
        Objects.requireNonNull(fechaPresentacion, "La declaracion jurada necesita su fecha");
        Objects.requireNonNull(
                fechaLimite,
                "El plazo se resuelve de los parametros sellados, nunca de un literal (regla 5)");
        Objects.requireNonNull(estado, "La declaracion jurada necesita su estado");
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda una declaracion jurada (regla 10)");
    }

    /** Un formulario nuevo, todavia sin guardar. */
    public static DeclaracionJurada nueva(
            String numero,
            Ejercicio ejercicio,
            long contribuyenteId,
            TipoDeDeclaracion tipo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable Long fichaCatastralId,
            LocalDate fechaPresentacion,
            LocalDate fechaLimite,
            Observacion observacion) {
        return new DeclaracionJurada(
                null,
                numero,
                ejercicio,
                contribuyenteId,
                tipo,
                predioId,
                vehiculoId,
                fichaCatastralId,
                fechaPresentacion,
                fechaLimite,
                EstadoDeDeclaracion.PRESENTADA,
                null,
                null,
                observacion);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Si se presento despues del plazo parametrizado. Los dos extremos entran (regla 9). */
    public boolean fueraDePlazo() {
        return fechaPresentacion.isAfter(fechaLimite);
    }

    /**
     * La rectificatoria que sustituye a esta DJ, con los mismos datos de tipo y ejercicio salvo lo
     * que la rectificatoria trae.
     *
     * <p>Esta DJ <b>no cambia</b>: {@code RegistrarDeclaracionJurada} es quien la marca {@code
     * SUSTITUIDA} con un {@code UPDATE} de estado, nunca del contenido —numero, fecha, tipo—. Ese
     * cambio de estado tampoco lo hace este metodo: crea el objeto de la rectificatoria, y quien
     * orquesta las dos escrituras es el caso de uso.
     */
    public DeclaracionJurada rectificadaPor(
            String numero,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable Long fichaCatastralId,
            LocalDate fechaPresentacion,
            LocalDate fechaLimite,
            Observacion observacion) {
        Long idPropio = Objects.requireNonNull(id, "Solo se rectifica una DJ que ya esta guardada");
        // Solo se rectifica una declaracion en pie (#365). Una anulada no revive rectificandola, y
        // una sustituida ya tiene quien la sustituya: rectificarla otra vez dejaria dos
        // rectificatorias vivas sobre la misma DJ. La comprobacion va aqui y no en el caso de uso
        // porque es una propiedad de la declaracion: cualquier camino que la rectifique pasa por
        // este metodo.
        if (!estado.esVigente()) {
            throw new TransicionIlegal(this.numero, estado, EstadoDeDeclaracion.SUSTITUIDA);
        }
        return new DeclaracionJurada(
                null,
                numero,
                ejercicio,
                contribuyenteId,
                TipoDeDeclaracion.RECTIFICATORIA,
                predioId,
                vehiculoId,
                fichaCatastralId,
                fechaPresentacion,
                fechaLimite,
                EstadoDeDeclaracion.PRESENTADA,
                idPropio,
                null,
                observacion);
    }

    /**
     * Esta misma DJ, marcada como sustituida por una rectificatoria. Sus demas datos no cambian.
     */
    public DeclaracionJurada sustituida() {
        return conEstado(EstadoDeDeclaracion.SUSTITUIDA);
    }

    /**
     * Esta misma DJ, objetada por la administracion (#365).
     *
     * <p><b>Observarla no la retira.</b> Sigue conciliando el predio (ADR-0015 §1): la
     * administracion objeto el <b>contenido</b> de una declaracion que existe y fue presentada, y
     * negarle la conciliacion diria «este predio no genera deuda predial» de uno que si la genera.
     * Lo que la observacion abre es el camino de la rectificatoria.
     */
    public DeclaracionJurada observada() {
        return conEstado(EstadoDeDeclaracion.OBSERVADA);
    }

    /**
     * Esta misma DJ, anulada (#365).
     *
     * <p>Al reves que observarla, anularla si la retira: deja de sustentar nada y el predio deja de
     * conciliar por ella. Es terminal —{@link #anulada()} sobre una anulada no compila un estado
     * nuevo, lanza— y esa es la respuesta a «¿una anulada revive?»: no. Si el contribuyente declara
     * otra vez, se presenta otra declaracion, con su numero.
     */
    public DeclaracionJurada anulada() {
        return conEstado(EstadoDeDeclaracion.ANULADA);
    }

    /**
     * La maquina de estados, en un solo sitio.
     *
     * <p>Sale de un estado <b>vigente</b> —{@code PRESENTADA} u {@code OBSERVADA}— y nunca de uno
     * terminal, y nunca al mismo en que ya esta. Escrita aqui y no en el caso de uso porque es una
     * propiedad de la declaracion, no del tramite: cualquier acto que se agregue manana pasa por
     * esta puerta sin tener que acordarse.
     *
     * <p>La misma regla la sostiene V54 en la base, con {@code declaracion_jurada_estado_terminal}.
     * Este metodo produce el mensaje que se lee en ventanilla; el disparador es lo unico que ven
     * dos peticiones simultaneas.
     */
    private DeclaracionJurada conEstado(EstadoDeDeclaracion nuevo) {
        if (estado == nuevo) {
            throw new TransicionIlegal(numero, estado, nuevo);
        }
        if (estado.esTerminal()) {
            throw new TransicionIlegal(numero, estado, nuevo);
        }
        return new DeclaracionJurada(
                id,
                numero,
                ejercicio,
                contribuyenteId,
                tipo,
                predioId,
                vehiculoId,
                fichaCatastralId,
                fechaPresentacion,
                fechaLimite,
                nuevo,
                djRectificaId,
                usuarioRegistro,
                observacion);
    }

    /** El estado en que esta la declaracion no admite el acto que se pide (#365). */
    public static final class TransicionIlegal extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransicionIlegal(String numero, EstadoDeDeclaracion desde, EstadoDeDeclaracion hasta) {
            super(
                    "La declaracion jurada "
                            + numero
                            + " esta "
                            + desde
                            + " y no puede pasar a "
                            + hasta);
        }
    }
}
