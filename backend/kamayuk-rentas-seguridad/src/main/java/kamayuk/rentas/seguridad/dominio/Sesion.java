package kamayuk.rentas.seguridad.dominio;

import java.time.Instant;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una sesion abierta: «el sistema cuenta con un registro de entradas que permite determinar
 * mediante sistema quienes estan conectados» (manual, cap. 1).
 *
 * <h2>El ejercicio de trabajo</h2>
 *
 * <p>El manual permite trabajar sobre un ejercicio distinto del corriente —emitir valores de 2025
 * en 2026, por ejemplo—, y ese ejercicio es <b>dato de sesion</b>.
 *
 * <p>Lo que <b>no</b> es, y conviene que quede escrito aqui porque es donde se buscara: no es la
 * fecha de calculo. Ninguna regla tributaria lo lee (regla 6): la fecha entra como argumento del
 * calculo, siempre. Si el ejercicio de trabajo pudiera sustituirla, recalcular un padron con la
 * sesion mal puesta produciria cifras equivocadas sin ningun error de por medio, que es la peor
 * clase de defecto que puede tener un sistema tributario.
 *
 * <p>Tampoco es el contexto de municipalidad. Ese sale del token (ADR-0005) y no de la sesion.
 */
public record Sesion(
        long id,
        long usuarioId,
        Instant inicio,
        @Nullable Instant fin,
        @Nullable String origenEquipo,
        @Nullable String origenIp,
        @Nullable Ejercicio ejercicioDeTrabajo) {

    public boolean estaAbierta() {
        return fin == null;
    }
}
