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
     * Todas las obligaciones del contribuyente en el libro, a la fecha, sin paginar: <b>tambien las
     * saldadas</b>.
     *
     * <p>Una obligacion cobrada, dada de baja o prescrita sigue aqui con sus cuatro partes en 0,00:
     * {@code ConsultarDeuda} netea los cargos contra los abonos y no descarta el grupo. Es lo que
     * necesita quien tiene que distinguir «saldada» de «nunca asentada» —la composicion de un
     * expediente, la liquidacion de costas—, o quien solo suma. Quien lee la lista como «lo que
     * debe» pide {@link #pendientesDe}.
     *
     * <p><b>No dice de donde viene la deuda</b>: agrupa todos los periodos de un tributo, un
     * ejercicio y una unidad, y la deuda ordinaria y la determinada de oficio sobre la misma unidad
     * salen en una sola fila. Quien necesita solo lo que origino un acto suyo pregunta a {@link
     * ConsultaDeLoOriginado}; el estado de cuenta de fiscalizacion casaba estas filas por su clave
     * y se llevaba la deuda ordinaria (#342).
     *
     * <p>Hasta #401 se llamaba {@code deTodoElContribuyente} y su javadoc prometia «todas las
     * obligaciones con deuda»; devolvia estas, y cada consumidor lo creia o no por su cuenta. El
     * nombre nuevo obliga a elegir.
     *
     * <p>Sin filtro de tributo ni de unidad: quien consulta ya sabe que predio o vehiculo le
     * interesa y filtra sobre esta lista, que para un contribuyente nunca es larga. Vacia si el
     * contribuyente no tiene ninguna obligacion asentada.
     */
    List<ObligacionPublica> todasDe(long contribuyenteId, LocalDate fecha);

    /**
     * Las obligaciones del contribuyente que <b>deben algo</b> a la fecha: las de {@link #todasDe}
     * que {@link ObligacionPublica#estaPendiente()} (#401).
     *
     * <p>Es lo que se formaliza en un valor, lo que se manda a la caja y lo que una ficha cuenta
     * como «obligaciones con saldo». Metodo por omision y no una segunda consulta: la regla es una
     * sola y vive en el Value Object, y los dobles de prueba la heredan tal cual. Quien lo redefina
     * —la implementacion lo hace solo para abrir su transaccion— delega aqui, y no escribe otro
     * filtro.
     */
    default List<ObligacionPublica> pendientesDe(long contribuyenteId, LocalDate fecha) {
        return todasDe(contribuyenteId, fecha).stream()
                .filter(ObligacionPublica::estaPendiente)
                .toList();
    }
}
