package kamayuk.rentas.seguridad.infraestructura;

import java.util.Objects;

/**
 * A quien se le avisa cuando la copia local de la autorizacion se queda incompleta (ADR-0026 §4,
 * aplicado a ADR-0039).
 *
 * <h2>El nombre y el canal son OBLIGATORIOS, y se comprueban al construirlo</h2>
 *
 * <p>ADR-0026 §4 no pide «una alerta»: pide <b>«alerta a una persona con nombre»</b>. Un evento que
 * no se pudo aplicar deja esta copia diciendo algo que {@code identidad} ya no dice, y lo que se ve
 * es un 403 a quien tiene el permiso —o una revocacion que aqui no llego—.
 *
 * <h2>Y el canal NO tiene que ser una direccion http(s), y eso es una decision MEDIDA</h2>
 *
 * <p>Es la diferencia con {@code ResponsableDeLaProyeccion} del ingestor del padron, que exige
 * {@code http(s)://} para poder comprobar la entrega ejecutandola. Aqui la etapa 4 se levanto de
 * verdad y lo pago: el canal que los ambientes declaran es un CORREO —{@code
 * KAMAYUK_CANAL_DE_OPERACION=operaciones@example.pe} en el {@code .env.ejemplo} de la plataforma, y
 * {@code kamayuk:canalDeOperacion} en los dos stacks—, y es lo que el descriptor pone en {@code
 * KAMAYUK_IDENTIDAD_CANAL} desde {@code e.operacion.canal}. Con la exigencia de http, el contexto
 * de Spring se cae al arrancar —{@code IllegalStateException: kamayuk.identidad.canal tiene que ser
 * una direccion http(s) … y llego «operaciones@example.pe»}— y como la implantacion TERMINA con una
 * pasada del consumidor, el {@code Job} de implantacion de `rentas` no arranca en ningun ambiente
 * (C-7). Medido en AC-5/AC-6 de `identidad`#4; `catastro` ya lo habia medido y `normativa` lo
 * adopto.
 *
 * <p>Asi que el aviso <b>siempre</b> se registra con nivel ERROR con el responsable y su canal
 * dentro, y <b>ademas</b> se entrega con un {@code POST} cuando el canal es una direccion http(s).
 * Lo que cuesta queda dicho: con un correo, la unica constancia es esa linea, y la observabilidad
 * del proyecto (INF-11) alerta sobre ERROR con receptor ya comprobado.
 */
public class ResponsableDelConsumidor {

    private final String nombre;
    private final String canal;

    public ResponsableDelConsumidor(String nombre, String canal) {
        this.nombre = nombre.strip();
        this.canal = canal.strip();
        if (this.nombre.isEmpty() || this.canal.isEmpty()) {
            throw new IllegalStateException(
                    "Faltan kamayuk.identidad.responsable y/o kamayuk.identidad.canal. No son"
                            + " opcionales: ADR-0026 §4 exige que un evento que no se pudo aplicar"
                            + " avise A UNA PERSONA CON NOMBRE. Mientras ese evento este sin"
                            + " aplicar, la copia local de la autorizacion dice algo que"
                            + " `identidad` ya no dice y ninguna pantalla lo delata: el consumidor"
                            + " no arranca hasta que alguien diga quien lo recibe");
        }
    }

    public String nombre() {
        return nombre;
    }

    /**
     * Donde se avisa. Una direccion http(s) recibe ademas un POST; cualquier otra, solo se nombra.
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
