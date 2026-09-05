package kamayuk.rentas.rentas.infraestructura.ingestor;

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
 * <h2>Y el canal tiene que ser una direccion a la que se pueda ENTREGAR</h2>
 *
 * <p>Esta es la diferencia con {@code ResponsableDeLaConciliacion} de `caja`, y esta puesta a
 * proposito. Alli el canal es texto libre —«un correo, un canal de mensajeria, un telefono»— y la
 * alerta se queda en el registro; P5D lo declaro como hueco con todas las letras: «esta construido
 * y no esta medido». Aqui se exige {@code http://} o {@code https://} para que la entrega se pueda
 * <b>comprobar ejecutandola</b>, que es lo que C-8 pide.
 *
 * <p>Lo que esta exigencia cuesta hay que decirlo: un municipio que solo tenga un correo tiene que
 * poner delante algo que reciba un {@code POST} y lo reenvie. A cambio, «avisa a una persona con
 * nombre» deja de ser una frase del javadoc.
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
        if (!this.canal.startsWith("http://") && !this.canal.startsWith("https://")) {
            throw new IllegalStateException(
                    "kamayuk.rentas.ingestor.canal tiene que ser una direccion http(s) a la que se"
                            + " pueda entregar el aviso, y llego «"
                            + this.canal
                            + "». No es una preferencia de formato: P5D dejo la alerta de `caja`"
                            + " declarada como «construida y no medida» precisamente porque su"
                            + " canal es texto libre y lo unico que se podia comprobar era que la"
                            + " linea existiera. Aqui la entrega se comprueba ejecutandola");
        }
    }

    public String nombre() {
        return nombre;
    }

    /** Donde se entrega el aviso. Una direccion http(s). */
    public String canal() {
        return canal;
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(nombre) + " <" + canal + ">";
    }
}
