package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.seguridad.dominio.PermisoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los permisos efectivos del usuario en curso: lo que la interfaz necesita para saber que dibujar
 * (ADR-0013).
 *
 * <p>El usuario sale de {@link OrigenContext}, no de un argumento —es el mismo dato que la
 * auditoria y el guardia, y tenerlo en la firma invitaria a que dos sitios dijeran cosas distintas
 * sobre quien pregunta—. La fecha sale del reloj inyectado: la vigencia de un permiso se evalua
 * contra "hoy", y "hoy" tiene que ser reproducible en una prueba.
 *
 * <p>Lectura pura. La {@code @Transactional(readOnly = true)} no es cosmetica: la consulta lee
 * {@code acceso}, {@code permiso}, {@code grupo}, {@code miembro} y {@code usuario} —tablas de
 * tenant con RLS— y sin transaccion no hay {@code SET LOCAL app.municipalidad_id}.
 */
@Service
public class PermisosDeLaSesion {

    private final PermisoRepository permisos;
    private final Clock reloj;

    public PermisosDeLaSesion(PermisoRepository permisos, Clock reloj) {
        this.permisos = permisos;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public Map<String, Set<Privilegio>> efectivos() {
        return permisos.efectivosDe(OrigenContext.actual().usuario(), LocalDate.now(reloj));
    }
}
