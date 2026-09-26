package kamayuk.rentas.seguridad.infraestructura;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kamayuk.rentas.plataforma.CanalDeAvisos;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.plataforma.ResponsableDeOperacion;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * El aviso de que la copia local de la autorizacion se quedo incompleta, ESCRITO SIEMPRE en el
 * registro con nivel ERROR y ADEMAS entregado por {@code POST} cuando el canal es una direccion
 * http(s) (ADR-0026 §4, ADR-0039 etapa 4).
 *
 * <p>El porque de esas dos mitades —y de que el canal no tenga que ser http— esta en {@link
 * ResponsableDeOperacion}, con la medida que lo decidio.
 *
 * <p><b>El texto y la linea de ERROR son de aqui; la entrega, de {@link CanalDeAvisos}</b> (#377),
 * que es la misma para los dos consumidores de buzon. Un canal que no contesta sigue sin tumbar la
 * corrida —el evento ya esta apartado y acusado, o sea que la cola sigue—: el porque esta alli.
 */
public class AlertaAlResponsableDeLaCopiaLocal implements AlertaDeEventosSinAplicar {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(AlertaAlResponsableDeLaCopiaLocal.class);

    private final ResponsableDeOperacion responsable;
    private final CanalDeAvisos canal;

    public AlertaAlResponsableDeLaCopiaLocal(JsonMapper json, ResponsableDeOperacion responsable) {
        this.responsable = responsable;
        this.canal = new CanalDeAvisos(json, responsable);
    }

    @Override
    public void hayUnEventoSinAplicar(
            EventoDeIdentidadRecibido evento, String motivo, long apartados) {
        String texto =
                "LA COPIA LOCAL DE LA AUTORIZACION ESTA INCOMPLETA: el evento "
                        + evento.eventoId()
                        + " ("
                        + evento.tipoPublicado()
                        + ", sujeto "
                        + evento.sujetoId()
                        + ", secuencia "
                        + evento.secuencia()
                        + ") de `identidad` no se pudo aplicar y se aparto. Motivo: "
                        + motivo
                        + ". Hay "
                        + apartados
                        + " evento(s) apartados en esta municipalidad. Mientras esten ahi, alguien"
                        + " tiene en `identidad` un permiso, una cuenta o una afiliacion que en"
                        + " `rentas` no rige, y ninguna cifra lo delata (ADR-0039 etapa 4,"
                        + " ADR-0026 §4).";
        avisar(new Aviso(responsable.nombre(), "APARTADO", motivo, apartados, texto));
    }

    @Override
    public void hayPospuestosQueNoAvanzan(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral) {
        String texto =
                "LA COPIA LOCAL DE LA AUTORIZACION NO AVANZA: en esta corrida se APARTARON a la"
                        + " cola de muertos "
                        + pospuestos.size()
                        + " evento(s) de `identidad` que llevaban mas de "
                        + enMinutos(umbral)
                        + " sin poder aplicarse porque esta copia no conoce aquello de lo que"
                        + " dependen. Un pospuesto no es un fallo mientras su dependencia este en"
                        + " camino; pasado ese tiempo ya no lo esta, y esperandolo se paraba todo lo"
                        + " que el buzon sirve detras (#377). Se acusaron: para aplicarlos hay que"
                        + " resolver en `identidad` o en el catalogo lo que les falta y volver a"
                        + " emitirlos (ADR-0039 etapa 4, ADR-0026 §4):"
                        + lista(pospuestos, ahora);
        avisar(new Aviso(responsable.nombre(), "NO_AVANZA", "no avanza", pospuestos.size(), texto));
    }

    @Override
    public void laColaEstaBloqueada(
            EstadoDeLaCola.Bloqueada bloqueada, List<EventoPospuesto> enLaCabeza, Duration umbral) {
        String texto =
                "COLA BLOQUEADA — LA COPIA LOCAL DE LA AUTORIZACION ESTA PARADA: el buzon de"
                        + " `identidad` sirve en su cabeza "
                        + bloqueada.enLaCabeza()
                        + " evento(s) que todavia no se pueden aplicar, desde la secuencia "
                        + bloqueada.secuenciaDeCabeza()
                        + ", y DETRAS esperan "
                        + bloqueada.detras()
                        + " evento(s) que esta corrida no puede leer. Si entre ellos hay una baja o"
                        + " una revocacion, NO rige aqui: el guardia sigue autorizando con la copia"
                        + " vieja. Los de la cabeza se apartaran solos cuando pasen de "
                        + enMinutos(umbral)
                        + " (#377, ADR-0026 §4):"
                        + lista(enLaCabeza, null);
        avisar(
                new Aviso(
                        responsable.nombre(),
                        "COLA_BLOQUEADA",
                        "cola bloqueada",
                        bloqueada.detras(),
                        texto));
    }

    @Override
    public void laCopiaSeQuedaComoEstaba(String causa, long cuentas) {
        String texto =
                "LA COPIA LOCAL DE LA AUTORIZACION NO SE PUSO AL DIA AL IMPLANTAR: no se pudo leer"
                        + " el buzon de `identidad` —"
                        + causa
                        + "—. La copia se queda como estaba, con "
                        + cuentas
                        + " cuenta(s), y `rentas` sigue autorizando con ella; lo que `identidad`"
                        + " haya cambiado desde la ultima pasada —un alta, una baja, una"
                        + " revocacion— NO rige aqui hasta que el CronJob del consumidor la ponga"
                        + " al dia, que lo intenta cada cinco minutos. Si la causa es la credencial"
                        + " o la afiliacion de la cuenta de servicio (un 401 o un 403), eso no se"
                        + " cura solo: se arregla en el despliegue (#453, ADR-0026 §4).";
        avisar(
                new Aviso(
                        responsable.nombre(),
                        "SIN_LEER_AL_IMPLANTAR",
                        "el buzon no contesto",
                        cuentas,
                        texto));
    }

    // ------------------------------------------------------------------

    /** Siempre al registro; y ademas al canal, si es de los que reciben. */
    private void avisar(Aviso aviso) {
        REGISTRO.error("{} Responsable: {}", aviso.texto(), responsable);
        canal.entregar(aviso);
    }

    private static String lista(List<EventoPospuesto> eventos, @Nullable Instant ahora) {
        StringBuilder lista = new StringBuilder();
        for (EventoPospuesto pospuesto : eventos) {
            lista.append("\n  - ")
                    .append(pospuesto.evento().tipoPublicado())
                    .append(" sujeto ")
                    .append(pospuesto.evento().sujetoId())
                    .append(", secuencia ")
                    .append(pospuesto.evento().secuencia());
            if (ahora != null) {
                lista.append(", esperando desde hace ").append(enMinutos(pospuesto.edad(ahora)));
            }
            lista.append(": ").append(pospuesto.motivo());
        }
        return lista.toString();
    }

    private static String enMinutos(Duration duracion) {
        return duracion.toMinutes() + " minuto(s)";
    }

    /** Lo que se manda al canal. */
    record Aviso(String responsable, String clase, String motivo, long cuantos, String texto) {}
}
