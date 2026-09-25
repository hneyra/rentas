package kamayuk.rentas.nucleo.infraestructura.ingestor;

import kamayuk.rentas.nucleo.aplicacion.AlertaDeHechosSinAplicar;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.plataforma.CanalDeAvisos;
import kamayuk.rentas.plataforma.ResponsableDeOperacion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * La alerta, ENTREGADA al canal del responsable y ademas escrita con nivel ERROR (ADR-0026 §4).
 *
 * <h2>Las dos cosas, y las dos hacen falta</h2>
 *
 * <ul>
 *   <li><b>Se entrega</b> con un {@code POST} al canal configurado, cuando es http(s). Es lo que
 *       hace que «avisa a una persona con nombre» se pueda comprobar ejecutandolo, que es
 *       exactamente lo que P5D dejo sin poder comprobar: su alerta «escribe en el registro, no
 *       manda un correo… esta construido y no esta medido».
 *   <li><b>Y se registra</b> con nivel ERROR, con el responsable y su canal dentro. No es
 *       redundante: si el canal esta caido —o es un correo—, la unica constancia de que hubo un
 *       aviso es esa linea, y la observabilidad del proyecto (INF-11) alerta sobre ERROR con
 *       receptor ya comprobado.
 * </ul>
 *
 * <p><b>El texto y la linea de ERROR son de aqui; la entrega, de {@link CanalDeAvisos}</b> (#377),
 * que es la misma para los dos consumidores de buzon: hasta #377 este {@code entregar} y el de
 * {@code AlertaAlResponsableDeLaCopiaLocal} eran iguales byte a byte salvo el {@code record}, y un
 * canal que no contesta sigue sin tumbar la vuelta — el porque esta alli.
 */
public class AlertaAlCanalDelResponsable implements AlertaDeHechosSinAplicar {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(AlertaAlCanalDelResponsable.class);

    private final ResponsableDeOperacion responsable;
    private final CanalDeAvisos canal;

    public AlertaAlCanalDelResponsable(JsonMapper json, ResponsableDeOperacion responsable) {
        this.responsable = responsable;
        this.canal = new CanalDeAvisos(json, responsable);
    }

    @Override
    public void hayUnHechoSinAplicar(HechoRecibido hecho, String motivo, long muertosSinExplicar) {
        String texto =
                "LA PROYECCION DEL PADRON ESTA INCOMPLETA: el hecho "
                        + hecho.eventoId()
                        + " ("
                        + hecho.tipoPublicado()
                        + ", predio "
                        + hecho.predioId()
                        + ", ejercicio "
                        + hecho.ejercicio()
                        + ", secuencia "
                        + hecho.secuencia()
                        + ") no se pudo aplicar y se aparto. Motivo: "
                        + motivo
                        + ". Hay "
                        + muertosSinExplicar
                        + " hecho(s) apartados sin explicar. Mientras esten ahi, `rentas` dice del"
                        + " padron algo que `catastro` ya no dice, y ninguna cifra lo delata"
                        + " (ADR-0026 §4).";
        REGISTRO.error("{} Responsable: {}", texto, responsable);
        canal.entregar(
                new Aviso(
                        responsable.nombre(),
                        hecho.eventoId().toString(),
                        motivo,
                        muertosSinExplicar,
                        texto));
    }

    /** Lo que se manda al canal. */
    record Aviso(
            String responsable,
            String eventoId,
            String motivo,
            long muertosSinExplicar,
            String texto) {}
}
