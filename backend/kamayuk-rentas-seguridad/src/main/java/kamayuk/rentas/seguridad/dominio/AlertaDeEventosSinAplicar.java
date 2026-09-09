package kamayuk.rentas.seguridad.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A quien se le dice que la copia local de la autorizacion se quedo incompleta (ADR-0026 §4).
 *
 * <p>Mientras haya un evento sin aplicar, alguien tiene en {@code identidad} un permiso, una cuenta
 * o una afiliacion que en esta copia no rige, y ninguna cifra lo delata: el guardia sigue
 * contestando con lo que la copia dice. Por eso se avisa a una persona con nombre, y no solo al
 * registro.
 *
 * <p><b>Son dos avisos y no uno, porque son dos hechos distintos</b>: uno que no se podra aplicar
 * nunca —se aparta a la cola de muertos, y hay que mirarlo— y uno que se podria aplicar en cuanto
 * llegue su dependencia. El segundo no es un fallo mientras la dependencia este en camino; lo es
 * cuando deja de estarlo, y eso solo se ve por el tiempo que lleva esperando.
 */
public interface AlertaDeEventosSinAplicar {

    /**
     * Un evento que no se podra aplicar nunca: se aparto, se acuso, y alguien tiene que mirarlo.
     */
    void hayUnEventoSinAplicar(EventoDeIdentidadRecibido evento, String motivo, long apartados);

    /**
     * Al terminar la corrida quedan pospuestos que llevan demasiado tiempo esperando.
     *
     * <p><b>Uno por corrida y no uno por evento</b>: lo que hay que atender es que la cola no
     * avanza, no cada uno de los eventos que la componen.
     *
     * @param pospuestos los que superan el umbral, en el orden en que el buzon los sirvio
     * @param ahora el instante contra el que se midio su edad
     * @param umbral desde cuando un pospuesto deja de ser «su dependencia esta en camino»
     */
    void hayPospuestosQueNoAvanzan(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral);
}
