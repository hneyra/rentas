package kamayuk.rentas.tesoreria.infraestructura;

import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;
import org.springframework.stereotype.Component;

/**
 * Si un recibo esta anulado: sin ruta todavia (P5D).
 *
 * <h2>Por que lanza, y que se rompe mientras lo haga</h2>
 *
 * <p>{@code caja} publica hoy {@code GET /recibos/&#123;numeroImpreso&#125;} —por el numero
 * impreso— y {@code convenio_movimiento.recibo_id} guarda el <b>identificador interno</b>. No hay
 * ninguna ruta que traduzca uno en otro, asi que esta pregunta no tiene de donde salir.
 *
 * <p>Las dos alternativas a lanzar son peores, y conviene decirlo con las dos escritas:
 *
 * <ul>
 *   <li>Devolver {@code false} —«el recibo sigue vigente»— haria <b>imposible</b> anular cualquier
 *       convenio ya formalizado, en silencio y con un mensaje que habla de un recibo que nadie pudo
 *       mirar.
 *   <li>Devolver {@code true} —«ya esta anulado»— dejaria anular el convenio <b>con su cuota
 *       inicial cobrada y viva</b>: dinero recibido por un acto que ya no existe, y ningun arqueo
 *       lo detecta. Es exactamente el hecho que la guarda de {@code CerrarConvenio} existe para
 *       impedir, producido por la guarda misma.
 * </ul>
 *
 * <p>Lo que se rompe mientras esto lance esta acotado y hay que decirlo: <b>anular</b> un convenio
 * que ya se formalizo. Registrar el preconvenio, formalizarlo, consultarlo, quebrarlo y
 * reformularlo no pasan por aqui —quebrar no exige anular el recibo, porque ese dinero SI entro— y
 * anular un preconvenio que nunca se formalizo tampoco: {@code CerrarConvenio} ni llega a
 * preguntar.
 *
 * <p><b>Y esto no es una regresion que introduzca P5D: es la que P5D hace visible.</b> Mientras
 * `recibo_movimiento` seguia en esta base, el convenio leia la tabla de otro sistema y la frontera
 * era mentira. El dia que {@code caja} publique la ruta, esta clase se sustituye por un adaptador
 * HTTP como {@link RecibosDeTramiteHttp} y desaparece; es la lista de trabajo pendiente de esta
 * frontera, escrita donde se ejecuta, igual que {@code SinRutaTodavia} en P5C.
 */
@Component
public class AnulacionesDeReciboSinRuta implements AnulacionesDeRecibo {

    @Override
    public boolean estaAnulado(long reciboId) {
        throw new ClienteHttpDeCaja.SinRutaEnCaja(
                "si el recibo " + reciboId + " esta anulado",
                "GET caja/api/v1/recibos/por-id/{reciboId}");
    }
}
