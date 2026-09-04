package kamayuk.rentas.coactiva.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla {@code coactiva_expedientes} (RF-100).
 *
 * <p>Es una proyeccion, no el expediente con su historial: lleva la cabecera y los tres datos que
 * la grilla necesita y la cabecera <b>no guarda</b> —el estado derivado, la direccion referencial
 * vigente y cuantos valores agrupa—. Traer los movimientos de veinte expedientes para pintar veinte
 * lineas seria traer cientos de filas hijas que nadie va a mirar.
 *
 * <p><b>Sin la deuda.</b> La deuda actualizada no esta aqui a proposito: depende de una fecha
 * (regla 9) y sale de {@code cuentacorriente}, no de este contexto. La compone {@code
 * ConsultaDeExpedientes} y viaja pegada a su fecha en {@link DeudaDelExpediente}.
 *
 * @param expediente la cabecera, tal como esta guardada
 * @param estado el estado derivado del historial ({@link EstadoDelExpediente#delHistorial})
 * @param direccionReferencialVigente la ultima direccion declarada, o la de apertura si no cambio
 * @param valores cuantos valores agrupa
 */
public record ExpedienteEnConsulta(
        ExpedienteCoactivo expediente,
        EstadoDelExpediente estado,
        @Nullable String direccionReferencialVigente,
        int valores) {

    public ExpedienteEnConsulta {
        Objects.requireNonNull(expediente, "La fila de la grilla es la de un expediente");
        Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
        if (valores < 0) {
            throw new IllegalArgumentException("Un expediente no agrupa un numero negativo");
        }
    }
}
