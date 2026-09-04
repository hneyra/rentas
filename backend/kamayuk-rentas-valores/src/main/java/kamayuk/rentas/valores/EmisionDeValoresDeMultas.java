package kamayuk.rentas.valores;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Emitir la resolucion de multa de una papeleta, y saber cual de ellas ya paso a coactiva (#53,
 * RF-066, RF-073).
 *
 * <p>Es la <b>tercera</b> API publica de {@code valores}, despues de {@link
 * ValoresDelContribuyente} y {@link ValoresEnCoactiva}. Vive en el paquete raiz, no en {@code
 * .aplicacion} ni en {@code .dominio}: Spring Modulith trata como interno todo lo que esta en un
 * subpaquete, asi que un {@code import} desde {@code sanciones} de {@code
 * valores.aplicacion.RegistrarValor} no pasa la verificacion. <b>Esto es exactamente lo que
 * sanciones puede ver de los valores. Sus tablas, no.</b>
 *
 * <h2>Existe para que la generacion masiva NO numere</h2>
 *
 * <p>Es el primer criterio de aceptacion de #53: «la generacion masiva reutiliza la numeracion de
 * #37; no inventa un correlativo propio». Este puerto es lo que lo hace posible sin abrir el
 * modulo: {@code sanciones} pide «emiteme la resolucion de multa de esta obligacion» y recibe el
 * numero ya puesto. El correlativo sale de {@code valor_correlativo} (V26) por el mismo {@code
 * UPDATE} atomico que usa la emision individual, y no hay ningun camino por el que {@code
 * sanciones} pueda escribir uno.
 *
 * <p>Por eso el metodo <b>devuelve</b> el numero y no lo recibe. Si lo recibiera, el dia que
 * alguien quisiera «una serie propia para las multas» le bastaria con pasar otro texto, y las dos
 * numeraciones divergirian sin que nada lo dijera.
 *
 * <h2>Lo que este puerto NO decide</h2>
 *
 * <p>Si la papeleta <b>procede</b> —si su resolucion de gerencia esta dictada, notificada y con el
 * plazo vencido— no se comprueba aqui: eso es de {@code sanciones}, que es quien tiene las
 * resoluciones. Lo que si comprueba, porque es de este modulo, es que la obligacion tenga deuda a
 * la fecha; sin ella no hay nada que formalizar.
 */
public interface EmisionDeValoresDeMultas {

    /**
     * Emite la resolucion de multa que formaliza esa obligacion, a esa fecha.
     *
     * <p>El tipo es siempre {@code RM} —resolucion de multa—: es lo que formaliza una sancion, y
     * dejarlo elegir desde fuera abriria la puerta a emitir una orden de pago por una papeleta.
     *
     * @param contribuyenteId el obligado de la papeleta; ya resuelto por quien llama
     * @param tributo el tributo con que la multa se asento en el libro
     * @param ejercicio el ejercicio de la obligacion
     * @param predioId la unidad, en una multa administrativa que cuelga de un predio
     * @param vehiculoId la unidad, en una multa de transito de un vehiculo del padron
     * @param fecha a que fecha se evalua la deuda y con la que nace el valor (regla 9); es la
     *     {@code fecha_criterio} congelada de la corrida, nunca «hoy»
     * @param observacion por que se emite (regla 10)
     * @throws SinDeudaQueFormalizar si esa obligacion no debe nada a esa fecha
     */
    ValorDeMulta emitirPorMulta(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            LocalDate fecha,
            Observacion observacion);

    /**
     * De entre esos valores, cuales ya tienen su pase a coactiva ({@code PCO} de {@code
     * valor_movimiento}, V28).
     *
     * <p>Es lo que el padron de papeletas enviadas a coactiva necesita, y se pregunta <b>en
     * bloque</b>: un padron de doscientas filas que preguntara una por una haria doscientas
     * consultas por pagina.
     *
     * @return los identificadores, de entre los preguntados, con pase; vacio si ninguno
     */
    Set<Long> conPaseACoactiva(Collection<Long> valorIds);

    /** La obligacion no debe nada a la fecha del criterio: no hay valor que emitir. */
    final class SinDeudaQueFormalizar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinDeudaQueFormalizar(String mensaje) {
            super(mensaje);
        }
    }
}
