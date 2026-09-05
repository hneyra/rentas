package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La constancia con que la municipalidad acredita que un vehículo no registra papeletas de tránsito
 * pendientes (#53, RF-068; V47 §4).
 *
 * <h2>{@link #verificadaAl} no es la fecha de emisión, y no puede faltar</h2>
 *
 * <p>«No tiene papeletas pendientes» es cierto o falso <b>según el día</b> (regla 9, RNF-075). Una
 * constancia que no dijera a qué día acredita afirmaría algo sin fecha, y el vehículo podría tener
 * una papeleta impuesta el mismo día que se imprimió. Por eso la fecha entra como argumento a la
 * comprobación y se guarda con la constancia; nunca se resuelve con el reloj dentro de la consulta.
 *
 * <h2>Nunca se edita</h2>
 *
 * <p>Es un documento que se entrega al administrado (regla 4, RNF-051). V47 le concede a {@code
 * kamayuk_app} solo {@code SELECT} e {@code INSERT}, igual que V41 a la resolución de gerencia: una
 * constancia equivocada se deja sin efecto con otra, y las dos quedan.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param numero el número del documento emitido; no es un correlativo propio
 * @param documentoId la fila de {@code documento_emitido} que la dibujó
 * @param placa el vehículo sobre el que se acredita
 * @param vehiculoId el vehículo del padrón, si está registrado; una placa que no lo está también
 *     puede pedir la constancia
 * @param solicitanteId quién la pidió, si se identificó
 * @param verificadaAl el día al que se comprobó que no había papeleta pendiente
 * @param fechaEmision el día en que se imprimió
 * @param usuarioRegistro quién la emitió; nulo mientras no se ha guardado
 * @param registradoEn cuándo se registró; sale del reloj inyectado, no de un {@code DEFAULT now()}
 * @param observacion por qué se emite (regla 10, RNF-052)
 */
public record ConstanciaLibre(
        @Nullable Long id,
        String numero,
        long documentoId,
        String placa,
        @Nullable Long vehiculoId,
        @Nullable Long solicitanteId,
        LocalDate verificadaAl,
        LocalDate fechaEmision,
        @Nullable String usuarioRegistro,
        Instant registradoEn,
        Observacion observacion) {

    /** {@code constancia_libre.numero varchar(40)}. */
    public static final int NUMERO_MAXIMO = 40;

    /** {@code constancia_libre.placa varchar(10)}. */
    public static final int PLACA_MAXIMA = 10;

    public ConstanciaLibre {
        Objects.requireNonNull(numero, "La constancia necesita su numero");
        numero = numero.strip();
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "Una constancia se materializa en un documento emitido: sin el no hay nada que"
                            + " entregar ni que reimprimir (RF-132)");
        }
        Objects.requireNonNull(placa, "La constancia acredita sobre una placa");
        placa = placa.strip().toUpperCase(Locale.ROOT);
        if (placa.isEmpty() || placa.length() > PLACA_MAXIMA) {
            throw new IllegalArgumentException(
                    "La placa va de 1 a " + PLACA_MAXIMA + " caracteres: '" + placa + "'");
        }
        Objects.requireNonNull(
                verificadaAl,
                "La constancia dice a que dia acredita: «no tiene papeletas pendientes» es cierto o"
                        + " falso segun el dia (regla 9, RNF-075)");
        Objects.requireNonNull(fechaEmision, "La constancia necesita su fecha de emision");
        Objects.requireNonNull(registradoEn, "La constancia dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** Una constancia sin guardar. */
    public static ConstanciaLibre nueva(
            String numero,
            long documentoId,
            String placa,
            @Nullable Long vehiculoId,
            @Nullable Long solicitanteId,
            LocalDate verificadaAl,
            LocalDate fechaEmision,
            Instant registradoEn,
            Observacion observacion) {
        return new ConstanciaLibre(
                null,
                numero,
                documentoId,
                placa,
                vehiculoId,
                solicitanteId,
                verificadaAl,
                fechaEmision,
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
        return Objects.requireNonNull(id, "La constancia todavia no se ha guardado");
    }
}
