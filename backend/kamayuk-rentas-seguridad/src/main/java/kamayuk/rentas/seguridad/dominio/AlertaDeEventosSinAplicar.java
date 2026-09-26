package kamayuk.rentas.seguridad.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kamayuk.rentas.plataforma.EstadoDeLaCola;

/**
 * A quien se le dice que la copia local de la autorizacion se quedo incompleta (ADR-0026 §4).
 *
 * <p>Mientras haya un evento sin aplicar, alguien tiene en {@code identidad} un permiso, una cuenta
 * o una afiliacion que en esta copia no rige, y ninguna cifra lo delata: el guardia sigue
 * contestando con lo que la copia dice. Por eso se avisa a una persona con nombre, y no solo al
 * registro.
 *
 * <p><b>Son cuatro avisos y no uno, porque son cuatro hechos distintos</b>: uno que no se podra
 * aplicar nunca —se aparta a la cola de muertos, y hay que mirarlo—; uno que se podria aplicar en
 * cuanto llegue su dependencia, y que se aparto porque dejo de estar en camino; una cola cuya
 * cabeza entera esta esperando con mas eventos detras, que no se leen (#377); y una implantacion
 * que no pudo leer el buzon y deja la copia como estaba (#453). El segundo no es un fallo mientras
 * la dependencia este en camino; lo es cuando deja de estarlo, y eso solo se ve por el tiempo que
 * lleva esperando.
 */
public interface AlertaDeEventosSinAplicar {

    /**
     * Un evento que no se podra aplicar nunca: se aparto, se acuso, y alguien tiene que mirarlo.
     */
    void hayUnEventoSinAplicar(EventoDeIdentidadRecibido evento, String motivo, long apartados);

    /**
     * En esta corrida se APARTARON pospuestos que llevaban demasiado tiempo esperando (#377).
     *
     * <p>Hasta #377 se avisaba de ellos y se quedaban en el buzon; ahora la politica del consumidor
     * los aparta a la cola de muertos y los acusa, para que no paren lo de detras, y este aviso
     * dice cuales. <b>Uno por corrida y no uno por evento</b>: lo que hay que atender es que algo
     * no avanzaba, no cada uno de los eventos que lo componen.
     *
     * @param pospuestos los que se apartaron, en el orden en que el buzon los sirvio, con el motivo
     *     con que se apartaron
     * @param ahora el instante de la corrida
     * @param umbral desde cuando un pospuesto deja de ser «su dependencia esta en camino»
     */
    void hayPospuestosQueNoAvanzan(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral);

    /**
     * La cabeza del buzon entera esta esperando y hay mas eventos DETRAS que esta corrida no puede
     * leer (#377).
     *
     * <p>Es lo que el aviso de los pospuestos no decia: que detras de ellos todo esta parado —una
     * baja, una revocacion— y {@code GuardiaDeAcceso} sigue autorizando con la copia vieja. Con la
     * politica de produccion dura como mucho {@code umbral}: pasado eso los de la cabeza se
     * apartan.
     *
     * @param bloqueada la secuencia de la cabeza, cuantos la ocupan y cuantos esperan detras
     * @param enLaCabeza los que la ocupan, con lo que le falta a cada uno
     * @param umbral cuanto falta, como mucho, para que se aparten solos
     */
    void laColaEstaBloqueada(
            EstadoDeLaCola.Bloqueada bloqueada, List<EventoPospuesto> enLaCabeza, Duration umbral);

    /**
     * La implantacion no pudo leer el buzon y la copia local SE QUEDA COMO ESTABA, con cuentas
     * (#453).
     *
     * <p>No es un fallo del {@code Job} —la municipalidad ya tiene quien entre, y el {@code
     * Deployment} sigue autorizando con esa copia—, pero tampoco es nada: lo que {@code identidad}
     * haya cambiado desde la ultima pasada, una baja incluida, no rige aqui hasta que el {@code
     * CronJob} del consumidor la ponga al dia. Y si la causa es una credencial o una afiliacion, no
     * se cura sola. Por eso va a una persona y no solo al registro del {@code Job}.
     *
     * @param causa lo que el transporte dijo, con el estado dentro si lo hubo
     * @param cuentas las cuentas que la copia local tiene, que son las que siguen autorizando
     */
    void laCopiaSeQuedaComoEstaba(String causa, long cuentas);
}
