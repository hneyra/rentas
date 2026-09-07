package kamayuk.rentas.catastro;

import java.io.Serial;

/**
 * Se pregunto, {@code catastro} contesto, y lo que contesta es que ese hecho NO CONSTA (#43).
 *
 * <h2>Por que este tipo es publico y el del transporte no</h2>
 *
 * <p>Los modos de fallo de un puerto son parte de su API. Quien consume {@link
 * ZonificacionDelPredio} o {@link RiesgoYItseDelPredio} <b>tiene que</b> distinguir «no consta» de
 * «no se pudo preguntar» —de esa distincion cuelga si se abre un local—, asi que no puede depender
 * de una clase que vive en {@code infraestructura}, que es el transporte y no la API.
 *
 * <p>Lo destapo el primer consumidor de verdad: Spring Modulith puso rojo «Module 'licencias'
 * depends on non-exposed type …ClienteHttpDeCatastro$NoConstaEnCatastro within module 'catastro'».
 * Mientras estos puertos no tuvieron llamador, nadie lo podia ver — que es exactamente lo que #43
 * dice de los puertos muertos.
 *
 * <p><b>No es «catastro no responde»</b>: es una respuesta, y por eso es otra clase. Devolver vacio
 * aqui diria que el predio no tiene lo que se pregunta, que es una tercera cosa distinta.
 */
public abstract class HechoDelTerritorioQueNoConsta extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    protected HechoDelTerritorioQueNoConsta(String mensaje) {
        super(mensaje);
    }

    /**
     * El codigo del catalogo estable de {@code catastro} —{@code VALIDACION}, {@code
     * NO_ENCONTRADO}—.
     *
     * <p>Viaja como dato y no dentro de la frase por el motivo de siempre: quien decide mirando el
     * texto deja de decidir bien en cuanto alguien lo reescribe, y nada se pone rojo.
     */
    public abstract String codigo();
}
