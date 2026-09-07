package kamayuk.rentas.dominio;

import org.jspecify.annotations.Nullable;

/**
 * El «por que» de una escritura, escrito por quien la hace.
 *
 * <p>Regla 10 y ADR-0008. El manual del sistema original lo dice sin rodeos: se registra «una
 * observacion que debe escribir el usuario, de lo contrario no le permite guardar la modificacion».
 * El <i>que cambio</i> lo reconstruye cualquier sistema; el <i>por que</i> solo lo sabe quien lo
 * cambio, en el momento de cambiarlo.
 *
 * <p><b>Por que es un tipo y no un {@code String}.</b> Un parametro {@code String observacion} se
 * cumple pasando {@code ""}, y se cumple asi el dia que corre prisa. Un tipo que no se puede
 * construir vacio convierte la regla en algo que el compilador y el constructor sostienen: quien
 * quiera saltarsela tiene que escribir cinco caracteres a proposito, y eso ya deja rastro.
 *
 * <p>Los limites son los de la base, para que el rechazo ocurra en el dominio y no en un {@code
 * INSERT} a medio camino: al menos 5 caracteres una vez recortada —la restriccion {@code
 * auditoria_observacion_ck}— y como mucho 500, que es el ancho de las columnas {@code observacion
 * NOT NULL} del esquema.
 */
public record Observacion(String texto) {

    /**
     * Lo que se contesta cuando la observacion no viene, y NOMBRA EL CAMPO (#30).
     *
     * <p>Antes era un {@code Objects.requireNonNull}, y esa eleccion decidia el estado HTTP sin que
     * nadie lo hubiera decidido: un {@code NullPointerException} no lo caza ningun manejador del
     * borde, cae en el {@code @ExceptionHandler(Exception.class)} y sale <b>500 con un
     * identificador de incidencia</b>. Medido contra la instalacion: {@code PUT
     * /seguridad/sesion/ejercicio} sin observacion contestaba {@code 500 ERROR_INTERNO}, y el texto
     * que el cliente necesita —este— se quedaba en el registro del servidor.
     *
     * <p>Tres cosas estaban mal a la vez, y la tercera es la peor: el estado <b>miente</b> sobre de
     * quien es la culpa —la clase 5xx dice «el servidor fallo» y aqui el servidor funciono—; el
     * cliente no puede distinguir «me falta un campo» de «se rompio»; y un cliente que reintenta
     * ante 5xx reintentaria para siempre una peticion que nunca va a funcionar.
     *
     * <p>Es {@link IllegalArgumentException} y no un tipo del borde porque este modulo no conoce la
     * web —regla 7—: el manejador ya traduce esa excepcion a {@code 422 VALIDACION} con su mensaje,
     * que es como contesta desde siempre la observacion demasiado corta. Un nulo y una de cuatro
     * caracteres son la misma clase de error y no habia motivo para que fueran dos clases de
     * respuesta.
     *
     * <p>Y el arreglo va AQUI y no en cada operacion: la regla 10 dice que <i>toda</i> modificacion
     * exige observacion, asi que este constructor es el sitio comun por el que pasan las 91
     * escrituras del borde, cualquiera que sea el ayudante que las traiga.
     */
    private static final String FALTA =
            "Falta el campo 'observacion': toda escritura exige una observacion"
                    + " (regla 10, ADR-0008)";

    /** {@code CHECK (length(btrim(observacion)) >= 5)} en la tabla de auditoria. */
    private static final int LARGO_MINIMO = 5;

    /** El ancho de {@code observacion varchar(500) NOT NULL} de las tablas de negocio. */
    private static final int LARGO_MAXIMO = 500;

    /**
     * El constructor canonico se escribe entero —y no como compacto— para poder declarar su
     * parametro {@link Nullable} (#30).
     *
     * <p>No es un detalle de estilo: <b>es lo que hacia falta para que el arreglo llegue a los
     * llamadores</b>. Con el parametro no-nulo, NullAway rechaza en compilacion todo {@code
     * Observacion.de(peticion.observacion())} cuyo campo sea nulable, y de ahi salieron los cinco
     * {@code texto == null ? "" : texto} de {@code licencias}: no eran descuido, eran la unica
     * forma de compilar. Tapaban el nulo, y con el la diferencia entre «falta» y «no vale». El
     * componente <b>si</b> es no-nulo: pasada la validacion nunca lo es, y {@code texto()} no
     * devuelve nulo a nadie.
     */
    public Observacion(@Nullable String texto) {
        if (texto == null) {
            throw new IllegalArgumentException(FALTA);
        }
        String limpio = texto.strip();
        if (limpio.length() < LARGO_MINIMO) {
            throw new IllegalArgumentException(
                    "La observacion debe explicar el cambio: al menos "
                            + LARGO_MINIMO
                            + " caracteres, y no espacios en blanco (ADR-0008)");
        }
        if (limpio.length() > LARGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "La observacion excede "
                            + LARGO_MAXIMO
                            + " caracteres, que es lo que admite la columna: "
                            + limpio.length());
        }
        this.texto = limpio;
    }

    public static Observacion de(@Nullable String texto) {
        return new Observacion(texto);
    }

    @Override
    public String toString() {
        return texto;
    }
}
