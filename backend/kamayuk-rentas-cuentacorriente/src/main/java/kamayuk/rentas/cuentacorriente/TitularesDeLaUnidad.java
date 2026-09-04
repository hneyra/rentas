package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * De quien es la unidad sobre la que se mueve una obligacion (#635, #680).
 *
 * <h2>Por que este contexto declara la interfaz y no la llama a nadie</h2>
 *
 * <p>{@code cuentacorriente} <b>no conoce a nadie</b> (ARQ-01 §4, regla 2): no puede preguntarle a
 * catastro de quien es un predio ni a rentas de quien es un vehiculo. Pero una obligacion es de
 * <b>alguien SOBRE una unidad</b> —{@link kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo} es
 * (contribuyente, tributo, ejercicio, periodo, predio, vehiculo) y compara por igualdad exacta—, y
 * hasta #635 nadie comprobaba que la unidad fuera del contribuyente del movimiento.
 *
 * <p>Un alta con el {@code vehiculoId} de otra persona quedaba asentada sobre una clave que nadie
 * va a mirar: no sale en la ficha del vehiculo —que es la de su titular—, no se suma a la deuda sin
 * unidad de quien paga, y {@code GET /consultas/deuda} del obligado la publica como una fila mas,
 * indistinguible de una correcta. Es el defecto que #430 documento para la caja, por el lado del
 * cargo.
 *
 * <p>La salida es la misma que este contexto ya usa para la mora: <b>declarar el puerto y que lo
 * implemente quien sabe</b>. La flecha sigue apuntando hacia aqui —{@code rentas} depende de {@code
 * cuentacorriente}, no al reves—, y este modulo no gana ninguna dependencia.
 *
 * <h2>Lo que #680 corrigio: la lista vacia significaba dos cosas</h2>
 *
 * <p>Hasta #680 los dos metodos devolvian una {@code List} y el vacio cubria <b>«ese identificador
 * no apunta a nada»</b> y <b>«la unidad existe y a esa fecha no figura a nombre de nadie»</b>. Las
 * dos acababan en el mismo 422 con el mismo texto, y eso rompia dos cosas a la vez:
 *
 * <ul>
 *   <li><b>no se podia dar de alta deuda sobre un predio sin titularidad vigente</b>, que no es un
 *       borde raro sino <b>4 977 de los 14 422 predios de Catacaos, el 34,5 % del padron</b> (#586)
 *       — el mismo predio que la deteccion de omisos enseña y que la muestra sortea desde {@code
 *       V73}: detectado, sorteado, visitado, con su acta levantada, y con su deuda imposible de
 *       asentar por el circuito normal;
 *   <li>y el mensaje <b>afirmaba algo falso</b>: le decia a quien atiende que su identificador «no
 *       apunta a nada» cuando el predio estaba perfectamente en el padron. Es el patron
 *       plausible-y-equivocado de siempre — se arregla tecleando otro codigo, que es justo lo que
 *       no hay que hacer.
 * </ul>
 *
 * <p>Por eso los dos metodos contestan {@link TitularidadDeLaUnidad}, cuyo invariante impide volver
 * a confundirlas. <b>La distincion vive aqui y no en un {@code if} del llamador</b>: quien pregunta
 * recibe la respuesta ya separada, y no puede derivar mal una de la otra.
 *
 * <h2>Lo que la distincion NO abre</h2>
 *
 * <p>Bajo RLS una unidad de otra municipalidad <b>no esta en el padron</b>, igual que un
 * identificador inventado, asi que las dos contestan {@link TitularidadDeLaUnidad#fueraDelPadron()}
 * y esta lectura sigue sin ser un detector de predios ajenos — que es lo que {@code
 * catastro.TitularesDelPredio} decidio y aqui se respeta. Lo que se separa es «no existe aqui» de
 * «existe aqui y no lo reclama nadie», y eso no dice nada de ninguna otra municipalidad.
 */
public interface TitularesDeLaUnidad {

    /** Si el predio esta en el padron de esta municipalidad, y de quien es a esa fecha. */
    TitularidadDeLaUnidad delPredio(long predioId, LocalDate fecha);

    /**
     * Lo mismo para el vehiculo.
     *
     * <p><b>El padron vehicular no guarda historial de titularidad</b> —una transferencia cambia
     * {@code vehiculo.contribuyente_id}, no abre un tramo nuevo— asi que la fecha no cambia la
     * respuesta y quien la reciba no puede reconstruir de quien era en 2024. Lo que si se puede
     * hacer, y es lo que hace el movimiento, es dejar registrar la deuda de un titular anterior
     * <b>declarandolo</b>.
     *
     * <p>Y aqui «esta en el padron sin titular» <b>no puede darse</b>: {@code
     * vehiculo.contribuyente_id} es {@code NOT NULL} (V2), asi que un vehiculo que existe tiene
     * titular siempre. La respuesta lleva las dos cosas igual porque el tipo es el mismo para las
     * dos unidades, y porque que hoy sea imposible es un hecho del esquema y no de este puerto.
     */
    TitularidadDeLaUnidad delVehiculo(long vehiculoId, LocalDate fecha);

    /**
     * Lo que el padron contesta sobre una unidad: si esta, y a nombre de quien.
     *
     * <p>Las dos cosas van juntas y separadas a proposito, porque <b>significan cosas distintas y
     * se arreglan de forma distinta</b>:
     *
     * <ul>
     *   <li>{@code estaEnElPadron = false} es «ese identificador no apunta a nada en esta
     *       municipalidad» —un numero tecleado mal, o el de otra municipalidad, que bajo RLS es lo
     *       mismo—. No hay declaracion que lo arregle: se arregla tecleando el identificador que
     *       es;
     *   <li>{@code estaEnElPadron = true} con {@link #titulares} vacia es «la unidad existe y a esa
     *       fecha no figura a nombre de nadie». Es un estado corriente y legitimo de un padron real
     *       (DAT-01 §4.2, #586) y no se arregla con nada: es el padron diciendo la verdad.
     * </ul>
     *
     * <p><b>El invariante del compacto es lo que impide volver a confundirlas</b>: una unidad que
     * no esta en el padron no puede tener titulares, porque una cuota de titularidad referencia al
     * predio ({@code titularidad_predio_fk}, V1) y al vehiculo lo identifica su propia fila. Una
     * respuesta asi no describiria ningun estado posible del padron, y quien la recibiera tendria
     * que elegir a cual de las dos mitades creerle.
     *
     * @param estaEnElPadron si esa unidad es una fila del padron de esta municipalidad
     * @param titulares quienes la tienen a la fecha consultada; <b>son varios, no uno</b> —dos
     *     conyuges, una sucesion, un condominio— y sus porcentajes vigentes no tienen por que sumar
     *     100, asi que aqui no se publica ninguno: lo que este tipo contesta es <i>de quien es</i>,
     *     no <i>cuanto</i>
     */
    record TitularidadDeLaUnidad(boolean estaEnElPadron, List<TitularDeLaUnidad> titulares) {

        public TitularidadDeLaUnidad {
            Objects.requireNonNull(
                    titulares,
                    "La respuesta del padron lleva su lista de titulares: vacia, no nula");
            titulares = List.copyOf(titulares);
            if (!estaEnElPadron && !titulares.isEmpty()) {
                throw new IllegalArgumentException(
                        "Una unidad que no esta en el padron no puede tener titulares: una cuota de"
                                + " titularidad referencia a la unidad, asi que esa respuesta no"
                                + " describe ningun estado posible del padron");
            }
        }

        /**
         * Ese identificador no apunta a nada en esta municipalidad — tecleado mal, o de otra
         * municipalidad, que bajo RLS es lo mismo.
         */
        public static TitularidadDeLaUnidad fueraDelPadron() {
            return new TitularidadDeLaUnidad(false, List.of());
        }

        /**
         * La unidad esta en el padron y estos son sus titulares a la fecha consultada; la lista
         * puede ir vacia, y entonces significa que no la reclama nadie.
         */
        public static TitularidadDeLaUnidad de(List<TitularDeLaUnidad> titulares) {
            return new TitularidadDeLaUnidad(true, titulares);
        }

        /** La unidad esta en el padron y a esa fecha no figura a nombre de nadie (#586). */
        public static TitularidadDeLaUnidad sinTitular() {
            return new TitularidadDeLaUnidad(true, List.of());
        }

        /**
         * Si ese contribuyente figura entre los titulares.
         *
         * <p>Funcion pura y sin fecha propia: la fecha ya la aplico quien resolvio la titularidad,
         * y volver a mirarla aqui seria mirarla dos veces con dos criterios (regla 6).
         */
        public boolean esDe(long contribuyenteId) {
            for (TitularDeLaUnidad titular : titulares) {
                if (titular.contribuyenteId() == contribuyenteId) {
                    return true;
                }
            }
            return false;
        }

        /** Si la unidad esta en el padron y a esa fecha no la reclama nadie (#586). */
        public boolean sinTitularVigente() {
            return estaEnElPadron && titulares.isEmpty();
        }
    }

    /**
     * Una cuota de titularidad, con lo justo para poder decirlo en un mensaje.
     *
     * @param codigo el codigo del padron, que es lo que quien atiende teclea
     * @param nombre para que el rechazo diga de quien es la unidad y no solo que no es suya
     */
    record TitularDeLaUnidad(long contribuyenteId, String codigo, String nombre) {}
}
