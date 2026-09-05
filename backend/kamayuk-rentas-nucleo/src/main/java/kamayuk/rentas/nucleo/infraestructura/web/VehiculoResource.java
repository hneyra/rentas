package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.OffsetDateTime;
import java.util.List;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import org.jspecify.annotations.Nullable;

/**
 * La ficha del vehiculo tal como sale por HTTP: campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>No lleva el identificador de municipalidad, ni entra ni sale (ADR-0005, regla 2).
 *
 * <p><b>No lleva ningun importe.</b> El valor referencial y el impuesto no salen de aqui: el
 * primero es una consulta aparte —depende del ejercicio, y toda cifra tiene que decir de cuando es—
 * y el segundo sigue bloqueado por D-02. Poner un campo vacio esperandolos invitaria a rellenarlo.
 */
public record VehiculoResource(
        long id,
        String placa,
        long contribuyenteId,
        String marca,
        String modelo,
        @Nullable String categoria,
        int anioFabricacion,
        int anioInscripcion,
        @Nullable String numeroMotor,
        @Nullable String numeroSerie,
        String estado,
        List<CambioDePlacaResource> historialDePlacas) {

    static VehiculoResource de(Vehiculo vehiculo, List<CambioDePlaca> historial) {
        return new VehiculoResource(
                java.util.Objects.requireNonNull(vehiculo.id(), "Un vehiculo leido tiene id"),
                vehiculo.placa().valor(),
                vehiculo.contribuyenteId(),
                vehiculo.marca(),
                vehiculo.modelo(),
                vehiculo.categoria(),
                vehiculo.anioFabricacion().valor(),
                vehiculo.anioInscripcion().valor(),
                vehiculo.numeroMotor(),
                vehiculo.numeroSerie(),
                vehiculo.estado().name(),
                historial.stream().map(CambioDePlacaResource::de).toList());
    }

    /** Un cambio de placa, con quien lo hizo y por que. */
    public record CambioDePlacaResource(
            String anterior, String nueva, String usuario, String fecha, String observacion) {

        static CambioDePlacaResource de(CambioDePlaca cambio) {
            OffsetDateTime fecha = cambio.fecha();
            return new CambioDePlacaResource(
                    cambio.anterior().valor(),
                    cambio.nueva().valor(),
                    cambio.usuario(),
                    fecha.toString(),
                    cambio.observacion());
        }
    }
}
