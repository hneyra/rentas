package kamayuk.rentas.seguridad.dominio;

import java.time.Instant;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la auditoria, tal como se lee.
 *
 * <p>Es un tipo distinto del {@code RegistroDeAuditoria} que se escribe, y a proposito: al escribir
 * hay que dar una {@code Observacion} valida y no hay identificador ni fecha —los pone la base—; al
 * leer hay identificador, fecha y origen, y la observacion es texto que ya paso su validacion. Un
 * solo tipo para las dos cosas tendria la mitad de sus campos nulos en cada uso.
 */
public record RegistroAuditado(
        long id,
        Ejercicio ejercicio,
        String tabla,
        String clave,
        String operacion,
        String usuario,
        @Nullable String origenEquipo,
        @Nullable String origenIp,
        Instant fecha,
        String observacion,
        @Nullable String datosAnteriores,
        @Nullable String datosNuevos) {}
