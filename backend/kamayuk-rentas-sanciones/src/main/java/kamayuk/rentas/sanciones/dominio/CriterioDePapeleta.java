package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que piden las pantallas de consulta de papeletas de las dos familias —{@code papeletas},
 * {@code transito_busqueda} y {@code transito_estado_cuenta} de #46; {@code infracciones_adm},
 * {@code adm_estado_cuenta} y {@code adm_notificaciones_contribuyente} de #47—, combinado en un
 * solo criterio: cada controlador traduce sus propios parámetros aquí. Todos los campos salvo
 * {@link #familia} son opcionales y se combinan con Y.
 *
 * @param familia distingue qué mitad de la tabla {@code papeleta} se consulta; nunca opcional, para
 *     que ningún criterio cruce por accidente tránsito con administrativa
 * @param numero de la papeleta
 * @param placa del vehículo infractor (tránsito)
 * @param documentoInfractor DNI o RUC del infractor, tal como lo escribe el operador (tránsito)
 * @param documentoAdministrado DNI o RUC del administrado —{@code contribuyente_id} de la papeleta
 *     administrativa— (administrativa)
 * @param codigoInfraccion el código del catálogo, tal como aparece en {@code codigo_infraccion}
 * @param desde fecha de infracción, límite inferior
 * @param hasta fecha de infracción, límite superior
 * @param estado de la papeleta
 * @param ingresadoPor el usuario que la registró
 * @param soloPendientes solo lo que todavía no está {@code PAGADA}, {@code ANULADA} ni {@code
 *     PRESCRITA} — lo que piden los estados de cuenta
 */
public record CriterioDePapeleta(
        Familia familia,
        @Nullable String numero,
        @Nullable String placa,
        @Nullable String documentoInfractor,
        @Nullable String documentoAdministrado,
        @Nullable String codigoInfraccion,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable EstadoDePapeleta estado,
        @Nullable String ingresadoPor,
        boolean soloPendientes) {

    public CriterioDePapeleta {
        Objects.requireNonNull(familia, "El criterio necesita su familia");
        numero = limpiar(numero);
        placa = limpiar(placa);
        documentoInfractor = limpiar(documentoInfractor);
        documentoAdministrado = limpiar(documentoAdministrado);
        codigoInfraccion = limpiar(codigoInfraccion);
        ingresadoPor = limpiar(ingresadoPor);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
