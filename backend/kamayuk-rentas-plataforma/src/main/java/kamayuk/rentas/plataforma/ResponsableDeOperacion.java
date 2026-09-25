package kamayuk.rentas.plataforma;

import java.util.Objects;

/**
 * A quien se le avisa cuando un consumidor de buzon deja su copia incompleta (ADR-0026 §4): el
 * responsable de la proyeccion del padron, en el ingestor de {@code catastro}, y el de la copia
 * local de la autorizacion, en el consumidor de {@code identidad}.
 *
 * <h2>Por que vive en {@code plataforma}, y de que coste viene (#377)</h2>
 *
 * <p>Hasta #377 eran dos clases —{@code ResponsableDeLaProyeccion} en {@code nucleo} y {@code
 * ResponsableDelConsumidor} en {@code seguridad}— que desde {@code public class} solo cambiaban el
 * nombre y el texto del mensaje. Y el coste de tener dos ya se pago una vez: la de {@code
 * seguridad} dejo de exigir un canal http en 5556137 (2026-09-09), la de {@code nucleo} lo siguio
 * exigiendo hasta 92dacb4 (#141, 2026-09-13), y esa exigencia fue una de las dos causas de que el
 * {@code CronJob} del ingestor no arrancara en ningun ambiente (#70). Es el mismo motivo por el que
 * {@link RespuestaAjena} y {@link CredencialDeServicio} viven aqui: son los mismos dos
 * consumidores.
 *
 * <h2>El nombre y el canal son OBLIGATORIOS, y se comprueban al construirlo</h2>
 *
 * <p>ADR-0026 §4 no pide «una alerta»: pide <b>«alerta a una persona con nombre»</b>. Una alerta
 * sin destinatario acaba en un panel que nadie mira, y lo que se pierde no es una linea de
 * registro: es que la copia sigue diciendo lo que el emisor ya no dice, y <b>ninguna cifra lo
 * delata</b>. Por eso quien lo construye dice, en {@code siFalta}, que propiedades faltan y que se
 * pierde sin ellas: son de cada consumidor, y el mensaje tiene que nombrar las suyas.
 *
 * <h2>Y el canal NO tiene que ser una direccion http(s) — medido</h2>
 *
 * <p>Los ambientes declaran un CORREO —{@code kamayuk:canalDeOperacion: operaciones@example.pe} en
 * los dos stacks, {@code KAMAYUK_CANAL_DE_OPERACION} en el {@code .env.ejemplo} de la plataforma—,
 * y exigir http tumbaba el contexto de Spring al arrancar: el {@code Job} de implantacion (etapa 4
 * de ADR-0039, medido en AC-5/AC-6 de {@code identidad}#4) y el {@code CronJob} del ingestor (#70,
 * medido el 2026-09-13). Asi que el aviso <b>siempre</b> se escribe en el registro con nivel ERROR,
 * con el responsable y su canal dentro, y <b>ademas</b> se entrega con un {@code POST} por {@link
 * CanalDeAvisos} cuando el canal es http(s). Lo que cuesta queda dicho: con un correo, la unica
 * constancia es esa linea, y lo que la convierte en aviso es que la observabilidad (INF-11) alerte
 * sobre ERROR.
 */
public final class ResponsableDeOperacion {

    private final String nombre;
    private final String canal;

    /**
     * @param siFalta el mensaje con que el proceso se niega a arrancar si falta el nombre o el
     *     canal: nombra las propiedades de ESE consumidor y lo que se pierde sin ellas
     */
    public ResponsableDeOperacion(String nombre, String canal, String siFalta) {
        this.nombre = nombre.strip();
        this.canal = canal.strip();
        if (this.nombre.isEmpty() || this.canal.isEmpty()) {
            throw new IllegalStateException(siFalta);
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
