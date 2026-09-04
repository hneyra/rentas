package kamayuk.rentas.rentas.dominio;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide la consulta de vehiculos (RF-024, #25). Todos los criterios son opcionales y se
 * combinan con Y.
 *
 * <p>{@code contribuyente} es el codigo unico del padron, por igualdad —no por aproximacion, como
 * hace {@code catastro} con {@code kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes}—: es
 * el mismo alcance que ya tiene el filtro homonimo de {@link CriterioDeBeneficio}, y quien escribe
 * el codigo exacto de un vehiculo suele traer tambien el codigo exacto de su titular.
 *
 * @param placa sin distinguir el guion, igual que {@link VehiculoRepository#findByPlaca}
 * @param nroMotor por igualdad exacta
 * @param contribuyente el codigo del titular
 * @param estado el estado del vehiculo en el padron
 */
public record CriterioDeVehiculo(
        @Nullable String placa,
        @Nullable String nroMotor,
        @Nullable String contribuyente,
        @Nullable EstadoVehiculo estado) {

    public CriterioDeVehiculo {
        placa = limpiar(placa);
        nroMotor = limpiar(nroMotor);
        contribuyente = limpiar(contribuyente);
    }

    public static CriterioDeVehiculo todos() {
        return new CriterioDeVehiculo(null, null, null, null);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
