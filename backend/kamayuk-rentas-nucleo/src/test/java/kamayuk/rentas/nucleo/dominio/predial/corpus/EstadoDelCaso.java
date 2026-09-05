package kamayuk.rentas.nucleo.dominio.predial.corpus;

import java.util.Locale;
import java.util.Objects;

/**
 * Como se puede comprobar un caso <b>hoy</b>, y —cuando no se puede— quien lo impide.
 *
 * <p>Sin esta columna el corpus seria una lista de deseos: cada fila diria lo que deberia pasar sin
 * decir si alguien lo comprueba. Con ella el corpus es un <b>libro mayor</b>, y las cuentas cuadran
 * en las dos direcciones: un caso que se declara sin regla y cuya regla si esta registrada pone la
 * prueba en rojo, igual que uno que se declara ejecutable y no lo es.
 *
 * @param clase que se puede hacer con el caso
 * @param detalle a quien se espera, o donde vive la regla. Vacio para {@link Clase#EJECUTABLE}
 */
public record EstadoDelCaso(Clase clase, String detalle) {

    public enum Clase {
        /** Sus reglas estan registradas en el motor: el corpus lo corre y comprueba sus aristas. */
        EJECUTABLE,

        /**
         * Correrlo <b>debe</b> fallar, y con una excepcion concreta. Es el caso borde que hoy se
         * resuelve fallando: sin arancel para la via no hay importe, y no hay cero.
         */
        FALLA_ESPERADA,

        /**
         * La regla todavia no se puede escribir. El detalle dice quien lo impide: {@code D-11} los
         * cuatro factores sin fuente, {@code D-02a} un dato normativo, {@code H-4} la dimension que
         * le falta a la tabla, {@code MOTOR} una forma que el motor no cubre.
         */
        SIN_REGLA,

        /**
         * <b>La regla existe; lo que no existe es el criterio de este caso.</b> Son los casos borde
         * que NEG-05 §2 deja con un {@code ‹VERIFICAR›}: dos vias con arancel distinto, el
         * pensionista con mas de un predio, la copropiedad. Correrlos hoy no comprobaria nada,
         * porque nadie ha decidido que deberia salir; escribirlos como si lo supieramos seria
         * inventar la decision.
         *
         * <p>Esta clase existe porque la prueba la exigio: declarar «sin regla» un caso de {@code
         * RT-001} —que si esta registrada— la pone en rojo, y con razon.
         */
        SIN_CRITERIO,

        /**
         * La regla existe, pero <b>fuera del motor</b>: es una funcion pura con su propia prueba,
         * porque transforma un valor ya agregado y el motor solo sabe de partidas y de sumas. El
         * detalle es la clase, y la prueba comprueba que exista.
         */
        FUERA_DEL_MOTOR
    }

    public EstadoDelCaso {
        Objects.requireNonNull(clase, "Todo caso declara como se comprueba");
        Objects.requireNonNull(detalle, "El detalle es vacio, no nulo");
        if (clase != Clase.EJECUTABLE && detalle.isBlank()) {
            throw new IllegalArgumentException(
                    "El estado " + clase + " exige decir quien lo impide o donde vive la regla");
        }
    }

    /** Lee {@code EJECUTABLE}, {@code SIN_REGLA:D-11}, {@code FUERA_DEL_MOTOR:‹clase›}. */
    public static EstadoDelCaso de(String texto) {
        Objects.requireNonNull(texto, "Todo caso declara su estado");
        String limpio = texto.strip();
        int corte = limpio.indexOf(':');
        String nombre = (corte < 0 ? limpio : limpio.substring(0, corte)).toUpperCase(Locale.ROOT);
        String detalle = corte < 0 ? "" : limpio.substring(corte + 1).strip();
        Clase clase;
        try {
            clase = Clase.valueOf(nombre);
        } catch (IllegalArgumentException noExiste) {
            throw new IllegalArgumentException(
                    "Estado de caso desconocido: '"
                            + texto
                            + "'. Los que hay son EJECUTABLE, FALLA_ESPERADA:‹excepcion›,"
                            + " SIN_REGLA:‹quien lo impide›, SIN_CRITERIO:‹fuente› y"
                            + " FUERA_DEL_MOTOR:‹clase›",
                    noExiste);
        }
        return new EstadoDelCaso(clase, detalle);
    }

    @Override
    public String toString() {
        return detalle.isEmpty() ? clase.name() : clase.name() + ":" + detalle;
    }
}
