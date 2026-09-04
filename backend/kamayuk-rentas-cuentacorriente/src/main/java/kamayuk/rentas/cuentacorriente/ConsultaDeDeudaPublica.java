package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;

/**
 * Cuanto debe un contribuyente, publicado para otros contextos acotados (ARQ-01 §4, #25).
 *
 * <p>Es la API publica de este modulo: vive en el paquete raiz, no en {@code .aplicacion} ni en
 * {@code .dominio}, porque Spring Modulith trata como interno todo lo que esta en un subpaquete
 * (mismo patron que {@code catastro.LectorDeFichas} y {@code parametros.LectorDeParametros}).
 *
 * <p><b>Al reves de la regla 2</b> (ARQ-01 §4: «cuentacorriente no conoce a nadie»): esta interfaz
 * es justo la excepcion que la regla preve —otro contexto puede depender de {@code
 * cuentacorriente}, nunca al reves—, y por eso quien la implementa no recibe nunca un tipo de otro
 * contexto: solo un identificador de contribuyente, que ya resolvio quien llama.
 *
 * <p>Devuelve {@link ObligacionPublica}, no {@code ObligacionConDeuda}: ese tipo vive en {@code
 * .dominio} y cruzar la frontera del modulo con el filtrar el detalle a lo que un consumidor
 * externo necesita —el mismo motivo por el que {@code DirectorioDeContribuyentes} devuelve {@code
 * ResumenDeContribuyente} y no el contribuyente entero.
 */
public interface ConsultaDeDeudaPublica {

    /**
     * Todas las obligaciones con deuda del contribuyente, a la fecha, sin paginar.
     *
     * <p>Sin filtro de tributo ni de unidad: quien consulta —{@code rentas}, hoy— ya sabe que
     * predio o vehiculo le interesa y filtra sobre esta lista, que para un contribuyente nunca es
     * larga. Vacia si el contribuyente no tiene ninguna obligacion asentada.
     */
    List<ObligacionPublica> deTodoElContribuyente(long contribuyenteId, LocalDate fecha);
}
