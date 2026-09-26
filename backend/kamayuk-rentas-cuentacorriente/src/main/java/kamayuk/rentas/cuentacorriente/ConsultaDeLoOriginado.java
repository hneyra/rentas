package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * La deuda del libro que <b>originaron</b> unos documentos concretos, publicada para otros
 * contextos acotados (#342).
 *
 * <p>Es la pregunta de quien necesita separar lo que nacio de un acto suyo de la deuda ordinaria de
 * la misma unidad y el mismo ejercicio. {@link ConsultaDeDeudaPublica#todasDe} no sirve para eso:
 * agrupa todos los periodos que comparten tributo, ejercicio y unidad, y no dice de donde viene
 * cada cargo. Casar sus filas por esa clave es lo que hacia el estado de cuenta de fiscalizacion, y
 * le atribuia a la fiscalizacion toda la deuda ordinaria del vehiculo o del predio. Lo unico que el
 * libro guarda del origen de una deuda es el {@code documento_origen} de su cargo: esa es la
 * pregunta.
 *
 * <h2>Por que un puerto aparte, y no un metodo mas de {@link ConsultaDeDeudaPublica}</h2>
 *
 * <p>El issue lo pedia en {@code ConsultaDeDeudaPublica}, y es la misma API publica del mismo
 * modulo. Pero aquel puerto tiene un solo metodo abstracto, y una veintena de pruebas de otros
 * cinco contextos lo implementan con una lambda: un segundo metodo abstracto las rompia todas por
 * una pregunta que solo {@code fiscalizacion} hace, y un metodo por omision que lanzara dejaria sin
 * compilar la garantia de que la implementacion de verdad lo contesta. Es el mismo criterio que
 * separa aqui {@link CarteraDelLibro}, {@link RecaudacionDelLibro} u {@link OrigenDeLaObligacion}:
 * una pregunta, un puerto. Lo implementa la misma clase que {@code ConsultaDeDeudaPublica}, sobre
 * el mismo servicio.
 *
 * <p>Vive en el paquete raiz por lo mismo que las demas APIs publicas de este modulo: Spring
 * Modulith trata como interno todo lo que esta en un subpaquete.
 */
public interface ConsultaDeLoOriginado {

    /**
     * La deuda que originaron esos documentos, a la fecha: una fila por clave de saldo —con su
     * periodo— que tiene un cargo de origen con alguno de ellos.
     *
     * <p>Se agrupa por clave de saldo y no por asiento para que cuenten tambien los abonos y las
     * imputaciones de esa deuda, aunque su documento sea un recibo: una deuda originada y cobrada
     * sale en 0,00, no desaparece (#401). Sin filtro de tributo: la multa que asento la misma
     * resolucion entra por su documento. Lo que el libro no puede separar —un cargo ordinario en la
     * misma clave de saldo, con el mismo periodo— se dice en {@code dominio.LoOriginadoPor}.
     *
     * @param contribuyenteId el obligado
     * @param documentosDeOrigen los documentos por los que se pregunta; se comparan sin espacios a
     *     los lados y sin distinguir mayusculas. Vacio da una lista vacia sin leer el libro
     * @param fecha la fecha de corte (regla 9, RNF-075)
     */
    List<ObligacionOriginada> deLoOriginadoPor(
            long contribuyenteId, Set<String> documentosDeOrigen, LocalDate fecha);
}
