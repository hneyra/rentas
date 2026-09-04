package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La papeleta de infracción, con su desglose <b>guardado, no recalculado</b> (#46, DAT-01 §4.5,
 * RF-060). Cubre las dos familias de {@code papeleta} (V4): {@code TRANSITO} —placa, vehículo,
 * infractor, propietario— y {@code ADMINISTRATIVA} —contribuyente, predio, notificación previa—,
 * "mismo esqueleto, distinta base legal" (#47, ARQ-01 §3.6). Es <b>un solo tipo</b> para las dos,
 * igual que la tabla es una sola: no hay lógica que duplicar entre familias, solo qué grupo de
 * campos aplica.
 *
 * <p>Los seis importes —{@code baseImponible}, {@code porcentajeInfraccion}, {@code
 * importeInfraccion}, {@code porcentajeACobrar}, {@code importeAPagar} e {@code
 * importeConBeneficio}— no los calcula este tipo ni ningún caso de uso: se toman tal cual del acta
 * física al registrar, exactamente como {@link CodigoInfraccion#porcentajeUit} es un dato de la
 * fila y no una regla (regla 5). Es lo que garantiza que reimprimir la papeleta de hace tres años
 * devuelva los mismos seis importes, aunque el catálogo y la UIT hayan cambiado desde entonces.
 *
 * <p><b>Nunca se borra</b> (regla 4, RNF-051): se anula con acto, cambiando {@link #estado}, no
 * borrando la fila.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param familia {@code TRANSITO} o {@code ADMINISTRATIVA}
 * @param numero identifica la papeleta, único junto con la familia; puede corregirse con {@code
 *     CambiarNumeroDePapeleta} dejando traza (RF-067, solo tránsito)
 * @param codigoInfraccionId el código del catálogo vigente el día de la infracción, de la misma
 *     familia que la papeleta
 * @param fechaInfraccion cuándo ocurrió
 * @param horaInfraccion a qué hora, si el acta la trae
 * @param lugar dónde ocurrió
 * @param placa del vehículo infractor; nulo salvo en {@code TRANSITO}, donde {@code
 *     papeleta_familia_ck} lo exige
 * @param vehiculoId el vehículo del padrón, si está registrado (tránsito)
 * @param licenciaConducir del infractor, si el acta la trae (tránsito)
 * @param infractorId quien conducía, si se identificó (tránsito)
 * @param propietarioId el titular del vehículo según el padrón, si es distinto del infractor
 *     (tránsito)
 * @param contribuyenteId el administrado, si se identificó (administrativa)
 * @param predioId el predio inspeccionado, si aplica (administrativa)
 * @param notificacionPreviaId la notificación que la origina, si hubo una: el manual permite una
 *     papeleta administrativa sin notificación previa, y forzar el enlace inventaría un requisito
 *     (#47 AC1)
 * @param obligadoId el contribuyente contra el que se asentó el cargo de la multa. <b>No se
 *     deduce</b> de {@code infractorId}, {@code propietarioId} ni {@code contribuyenteId}: el
 *     manual permite cobrarle al propietario aunque condujera otro. Hasta #50 este dato entraba en
 *     {@code RegistrarPapeleta} y no se guardaba, y sin él nada puede encontrar después la
 *     obligación que un descargo fundado tiene que dar de baja (V41 §1)
 * @param baseImponible la UIT del ejercicio de la infracción, tal como se aplicó en el acta
 * @param porcentajeInfraccion el porcentaje que fija el código de infracción
 * @param importeInfraccion base por porcentaje, ya calculado en el acta
 * @param porcentajeACobrar el porcentaje que realmente se cobra (puede diferir del de la infracción
 *     por acumulación de puntos u otra regla del acta)
 * @param importeAPagar lo que corresponde pagar, sin beneficio
 * @param importeConBeneficio lo que corresponde pagar con el descuento vigente, si el acta trae uno
 * @param estado en qué punto está
 * @param usuarioRegistro quien la registró; nulo en una papeleta que todavía no se guardó
 * @param observacion por qué se registra (regla 10)
 */
public record Papeleta(
        @Nullable Long id,
        Familia familia,
        String numero,
        long codigoInfraccionId,
        LocalDate fechaInfraccion,
        @Nullable LocalTime horaInfraccion,
        String lugar,
        @Nullable String placa,
        @Nullable Long vehiculoId,
        @Nullable String licenciaConducir,
        @Nullable Long infractorId,
        @Nullable Long propietarioId,
        @Nullable Long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long notificacionPreviaId,
        long obligadoId,
        Dinero baseImponible,
        Alicuota porcentajeInfraccion,
        Dinero importeInfraccion,
        Alicuota porcentajeACobrar,
        Dinero importeAPagar,
        @Nullable Dinero importeConBeneficio,
        EstadoDePapeleta estado,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int NUMERO_MAXIMO = 20;
    private static final int LUGAR_MAXIMO = 300;
    private static final int PLACA_MAXIMA = 10;
    private static final int LICENCIA_MAXIMA = 20;

    public Papeleta {
        Objects.requireNonNull(familia, "La papeleta necesita su familia");
        Objects.requireNonNull(numero, "La papeleta necesita su numero");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        if (codigoInfraccionId <= 0) {
            throw new IllegalArgumentException("La papeleta necesita el codigo de infraccion");
        }
        Objects.requireNonNull(fechaInfraccion, "La papeleta necesita la fecha de la infraccion");
        Objects.requireNonNull(lugar, "La papeleta necesita el lugar de la infraccion");
        lugar = lugar.strip();
        if (lugar.isEmpty() || lugar.length() > LUGAR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El lugar va de 1 a " + LUGAR_MAXIMO + " caracteres");
        }
        if (familia == Familia.TRANSITO) {
            Objects.requireNonNull(
                    placa, "Una papeleta de transito necesita la placa (papeleta_familia_ck)");
            placa = placa.strip().toUpperCase(Locale.ROOT);
            if (placa.isEmpty() || placa.length() > PLACA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La placa va de 1 a " + PLACA_MAXIMA + " caracteres: '" + placa + "'");
            }
        } else {
            if (contribuyenteId == null && predioId == null) {
                throw new IllegalArgumentException(
                        "Una papeleta administrativa necesita contribuyente o predio"
                                + " (papeleta_familia_ck)");
            }
            if (placa != null) {
                placa = placa.strip().toUpperCase(Locale.ROOT);
                if (placa.isEmpty()) {
                    placa = null;
                } else if (placa.length() > PLACA_MAXIMA) {
                    throw new IllegalArgumentException(
                            "La placa va de 1 a " + PLACA_MAXIMA + " caracteres: '" + placa + "'");
                }
            }
        }
        if (licenciaConducir != null) {
            licenciaConducir = licenciaConducir.strip();
            if (licenciaConducir.isEmpty()) {
                licenciaConducir = null;
            } else if (licenciaConducir.length() > LICENCIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La licencia de conducir excede " + LICENCIA_MAXIMA + " caracteres");
            }
        }
        if (obligadoId <= 0) {
            throw new IllegalArgumentException(
                    "La papeleta dice a quien se le cobra la multa: sin el obligado no se puede"
                            + " asentar el cargo ni encontrarlo despues para darlo de baja (V41 §1)");
        }
        Objects.requireNonNull(baseImponible, "La papeleta necesita su base imponible");
        Objects.requireNonNull(
                porcentajeInfraccion, "La papeleta necesita el porcentaje de la infraccion");
        Objects.requireNonNull(
                importeInfraccion, "La papeleta necesita el importe de la infraccion");
        Objects.requireNonNull(porcentajeACobrar, "La papeleta necesita el porcentaje a cobrar");
        Objects.requireNonNull(importeAPagar, "La papeleta necesita el importe a pagar");
        Objects.requireNonNull(estado, "La papeleta necesita su estado");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda una papeleta (regla 10)");
    }

    /** Una papeleta de tránsito nueva, todavía sin guardar. */
    public static Papeleta nuevaTransito(
            String numero,
            long codigoInfraccionId,
            LocalDate fechaInfraccion,
            @Nullable LocalTime horaInfraccion,
            String lugar,
            String placa,
            @Nullable Long vehiculoId,
            @Nullable String licenciaConducir,
            @Nullable Long infractorId,
            @Nullable Long propietarioId,
            long obligadoId,
            Dinero baseImponible,
            Alicuota porcentajeInfraccion,
            Dinero importeInfraccion,
            Alicuota porcentajeACobrar,
            Dinero importeAPagar,
            @Nullable Dinero importeConBeneficio,
            Observacion observacion) {
        return new Papeleta(
                null,
                Familia.TRANSITO,
                numero,
                codigoInfraccionId,
                fechaInfraccion,
                horaInfraccion,
                lugar,
                placa,
                vehiculoId,
                licenciaConducir,
                infractorId,
                propietarioId,
                null,
                null,
                null,
                obligadoId,
                baseImponible,
                porcentajeInfraccion,
                importeInfraccion,
                porcentajeACobrar,
                importeAPagar,
                importeConBeneficio,
                EstadoDePapeleta.IMPUESTA,
                null,
                observacion);
    }

    /**
     * Una papeleta administrativa nueva, todavía sin guardar. Enlazada o no a una notificación
     * previa —el manual la permite sin ella (#47 AC1)—.
     */
    public static Papeleta nuevaAdministrativa(
            String numero,
            long codigoInfraccionId,
            LocalDate fechaInfraccion,
            @Nullable LocalTime horaInfraccion,
            String lugar,
            @Nullable Long contribuyenteId,
            @Nullable Long predioId,
            @Nullable Long notificacionPreviaId,
            long obligadoId,
            Dinero baseImponible,
            Alicuota porcentajeInfraccion,
            Dinero importeInfraccion,
            Alicuota porcentajeACobrar,
            Dinero importeAPagar,
            @Nullable Dinero importeConBeneficio,
            Observacion observacion) {
        return new Papeleta(
                null,
                Familia.ADMINISTRATIVA,
                numero,
                codigoInfraccionId,
                fechaInfraccion,
                horaInfraccion,
                lugar,
                null,
                null,
                null,
                null,
                null,
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                obligadoId,
                baseImponible,
                porcentajeInfraccion,
                importeInfraccion,
                porcentajeACobrar,
                importeAPagar,
                importeConBeneficio,
                EstadoDePapeleta.IMPUESTA,
                null,
                observacion);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "La papeleta todavia no se ha guardado");
    }

    /**
     * La misma papeleta con otro número, para {@code CambiarNumeroDePapeleta}. Sus demás datos,
     * incluido el desglose, no cambian: el cambio de número corrige un error del operador, nunca
     * recalcula nada (RF-067, solo tránsito).
     */
    public Papeleta conNumero(String otroNumero) {
        Objects.requireNonNull(id, "Solo se cambia el numero de una papeleta ya guardada");
        return new Papeleta(
                id,
                familia,
                otroNumero,
                codigoInfraccionId,
                fechaInfraccion,
                horaInfraccion,
                lugar,
                placa,
                vehiculoId,
                licenciaConducir,
                infractorId,
                propietarioId,
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                obligadoId,
                baseImponible,
                porcentajeInfraccion,
                importeInfraccion,
                porcentajeACobrar,
                importeAPagar,
                importeConBeneficio,
                estado,
                usuarioRegistro,
                observacion);
    }
}
