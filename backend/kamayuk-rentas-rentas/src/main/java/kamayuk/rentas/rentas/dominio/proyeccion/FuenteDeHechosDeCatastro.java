package kamayuk.rentas.rentas.dominio.proyeccion;

import java.util.List;
import java.util.UUID;

/**
 * De donde salen los hechos de {@code catastro} (C-8, ADR-0026 §3).
 *
 * <h2>Se VIENE A BUSCAR, no se recibe empujado. Y lo decide un privilegio</h2>
 *
 * <p>`caja` publica sus pagos empujandolos a un endpoint de este sistema, y aqui es al reves. La
 * diferencia no es de gusto:
 *
 * <ul>
 *   <li>El receptor de un pago escribe {@code pago_recibido}, y `V8` le da a {@code sgtm_app}
 *       {@code INSERT, SELECT, UPDATE} sobre ella: el proceso que atiende HTTP <b>puede</b>
 *       recibir.
 *   <li>El receptor de estos hechos escribe {@code predio_ref}, {@code ficha_ref}, {@code
 *       valuacion_predio} y {@code valuacion_corrida}, y `V4` y `V5` le dan a {@code sgtm_app}
 *       <b>solo {@code SELECT}</b>. Quien las escribe es {@code rol_ingestor_catastro}, que no
 *       atiende peticiones. Un endpoint que recibiera empujones tendria que llevar esa credencial
 *       dentro del proceso web, y entonces «la proyeccion es de solo lectura para la aplicacion»
 *       dejaria de ser un privilegio y volveria a ser disciplina.
 * </ul>
 *
 * <h2>El acuse va DESPUES del COMMIT, y por eso la entrega es al menos una vez</h2>
 *
 * <p>Un acuse que se pierda hace que el emisor vuelva a servir lo mismo. No es un problema: quien
 * deduplica es este lado, por {@code evento_id}, y lo sostiene {@code catastro_evento_pk}. Lo que
 * <b>si</b> seria un problema es acusar antes de confirmar: el hecho dejaria de servirse y no
 * estaria aplicado, y nada lo diria.
 */
public interface FuenteDeHechosDeCatastro {

    /** Lo que el emisor tenga pendiente, en el orden en que lo produjo. */
    Lote pendientes(int limite);

    /** Le dice al emisor que estos ya no hacen falta. Se llama DESPUES de confirmar. */
    void acusar(List<UUID> eventoIds);

    /**
     * Un lote de hechos pendientes.
     *
     * @param quedan cuantos le quedan al emisor DESPUES de este lote. Es lo que permite decir
     *     «faltan 9 000» en vez de «faltan», y decidir si hay que dar otra vuelta
     */
    record Lote(List<HechoRecibido> hechos, long quedan) {}

    /**
     * No se pudo preguntar.
     *
     * <p>Es un fallo <b>transitorio</b> y se distingue a proposito de todo lo demas: no se acusa
     * nada, no se mata nada, y la vuelta siguiente lo reintenta. Confundirlo con un rechazo mataria
     * hechos por un motivo que iba a arreglarse levantando un despliegue.
     */
    final class CatastroNoContesta extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CatastroNoContesta(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }

        public CatastroNoContesta(String mensaje) {
            super(mensaje);
        }
    }
}
