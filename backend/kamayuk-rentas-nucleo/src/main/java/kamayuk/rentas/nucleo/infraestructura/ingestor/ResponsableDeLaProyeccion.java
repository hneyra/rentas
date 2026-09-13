package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.util.Objects;

/**
 * A quien se le avisa cuando la proyeccion del padron se queda incompleta (ADR-0026 §4).
 *
 * <h2>El nombre y el canal son OBLIGATORIOS, y se comprueban al construirlo</h2>
 *
 * <p>ADR-0026 §4 no pide «una alerta»: pide <b>«alerta a una persona con nombre»</b>. Una alerta
 * sin destinatario acaba en un panel que nadie mira, y aqui lo que se pierde no es una linea de
 * registro: es que la proyeccion del padron sigue diciendo lo que el padron ya no dice, y
 * <b>ninguna cifra lo delata</b>.
 *
 * <h2>Y el canal NO tiene que ser una direccion http(s) — medido, y alineado con los otros tres
 * </h2>
 *
 * <p>Esta clase exigio {@code http://} o {@code https://} para que la entrega se pudiera
 * <b>comprobar ejecutandola</b> (C-8), y eso dejo el {@code CronJob} del ingestor <b>sin arrancar
 * en ningun ambiente</b> (rentas#70): los dos stacks declaran {@code kamayuk:canalDeOperacion:
 * operaciones@example.pe}, un correo, y es lo que el descriptor le pone en {@code
 * KAMAYUK_RENTAS_INGESTOR_CANAL}. Medido el 2026-09-13 levantando el perfil {@code batch} con esa
 * configuracion: «kamayuk.rentas.ingestor.canal tiene que ser una direccion http(s) […] y llego
 * «operaciones@example.pe»».
 *
 * <p>Los otros tres consumidores ya lo habian medido y decidido igual —{@code
 * ResponsableDeLaCopiaLocal} de {@code normativa}, {@code ResponsableDelConsumidor} de {@code
 * catastro} y el aviso del consumidor de {@code identidad} de este mismo repositorio ({@code
 * ElAvisoAlResponsableTest})—, y {@code normativa} dejo escrito que este era «un defecto latente de
 * {@code rentas}».
 *
 * <p>Asi que el aviso <b>siempre</b> se escribe en el registro con nivel ERROR, con el responsable
 * y su canal dentro, y <b>ademas</b> se entrega con un {@code POST} cuando el canal es http(s). Lo
 * que cuesta queda dicho: con un correo la unica constancia es esa linea, y lo que la convierte en
 * aviso es que la observabilidad alerte sobre ERROR.
 */
public class ResponsableDeLaProyeccion {

    private final String nombre;
    private final String canal;

    public ResponsableDeLaProyeccion(String nombre, String canal) {
        this.nombre = nombre.strip();
        this.canal = canal.strip();
        if (this.nombre.isEmpty() || this.canal.isEmpty()) {
            throw new IllegalStateException(
                    "Faltan kamayuk.rentas.ingestor.responsable y/o .canal. No son opcionales:"
                            + " ADR-0026 §4 exige que un hecho que no se pudo aplicar avise A UNA"
                            + " PERSONA CON NOMBRE. Mientras ese hecho este sin aplicar, la"
                            + " proyeccion del padron dice algo que `catastro` ya no dice y ninguna"
                            + " cifra lo delata: el ingestor no arranca hasta que alguien diga quien"
                            + " lo recibe");
        }
    }

    public String nombre() {
        return nombre;
    }

    /**
     * Donde se avisa. Una direccion http(s) recibe ademas un POST; cualquier otra —un correo, un
     * telefono— solo se nombra en la linea de ERROR.
     */
    public String canal() {
        return canal;
    }

    /** Si al canal se le puede ENTREGAR el aviso, y no solo nombrarlo. */
    public boolean seLeEntrega() {
        return canal.startsWith("http://") || canal.startsWith("https://");
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(nombre) + " <" + canal + ">";
    }
}
