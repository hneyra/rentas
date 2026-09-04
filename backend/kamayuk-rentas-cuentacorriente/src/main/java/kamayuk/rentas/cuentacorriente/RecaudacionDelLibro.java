package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Cuanto se ha cobrado de unos tributos en un rango de fechas, segun el libro (#53, RF-073,
 * RF-074).
 *
 * <p>Es la <b>octava</b> API publica de este modulo, despues de {@link ConsultaDeDeudaPublica},
 * {@link GeneradorDeCargos}, {@link MovimientoDeFase}, {@link RegistroDeAbonos}, {@link
 * AcogimientoAConvenio}, {@link ConciliacionDeCaja} y {@link MovimientosDelLibro}. Vive en el
 * paquete raiz por lo mismo que las otras siete: Spring Modulith trata como interno todo lo que
 * esta en un subpaquete, asi que esto es exactamente lo que otro contexto puede ver del libro. Sus
 * tablas, no.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para que el resumen de recaudacion de papeletas <b>cuadre con el libro</b> (AC 3 de #53): «lo
 * recaudado por papeletas es exactamente la suma de sus abonos». Ninguna de las siete anteriores lo
 * responde: {@link ConciliacionDeCaja} agrega por <b>documento de caja</b> —para cuadrar un turno—,
 * {@link MovimientosDelLibro} lista los pagos <b>de un contribuyente</b>, y {@code
 * tesoreria.ConsultaDeRecaudacion} suma sobre {@code recibo_detalle}, que es el papel y no el
 * libro. Lo que falta es agregar los abonos de un <b>tributo</b> en un rango de fechas, que es la
 * pregunta que hace un resumen de area.
 *
 * <h2>Por que asi el resumen no se puede recomponer</h2>
 *
 * <p>La alternativa —que {@code sanciones} sumara {@code papeleta.importe_a_pagar} de las papeletas
 * en estado {@code PAGADA}— daria una cifra <b>parecida y distinta</b>: no cuenta los intereses
 * cobrados, cuenta entero un pago parcial, y sigue contando un recibo anulado. Ese es exactamente
 * el defecto que la rotura de este criterio provoca, y el motivo por el que la cifra sale de aqui.
 *
 * <h2>Al reves de la regla 2</h2>
 *
 * <p>ARQ-01 §4: «cuentacorriente no conoce a nadie». Esta interfaz es la excepcion que la regla
 * preve —otro contexto depende de {@code cuentacorriente}, nunca al reves—, y por eso no recibe
 * ningun tipo de otro contexto: solo nombres de tributo y fechas.
 *
 * <h2>Solo lectura</h2>
 *
 * <p>No hay ningun metodo que escriba. Asentar es {@link GeneradorDeCargos} y abonar es {@link
 * RegistroDeAbonos}.
 */
public interface RecaudacionDelLibro {

    /**
     * Lo cobrado de esos tributos entre las dos fechas, desglosado por tributo, ejercicio, mes y
     * fase de cobranza.
     *
     * <p>Un cobro es un asiento {@code ABONO} de concepto {@code PAGO} <b>que nadie ha
     * reversado</b> —un recibo anulado conserva sus asientos, no se borran (V2)—, exactamente el
     * mismo criterio que {@link ConciliacionDeCaja#abonadoPor}. Los demas abonos —condonacion,
     * ajuste, fraccionamiento— mueven deuda pero no son dinero que entro, y contarlos inflaria la
     * recaudacion con bajas de deuda.
     *
     * <p>El desglose por <b>fase</b> es lo que la pantalla llama «tipo de cobranza»: ordinaria,
     * valor, coactiva o convenio. El desglose por <b>mes</b> es el de la {@code fecha_valor} del
     * abono, no el del ejercicio de la obligacion: un recibo de marzo de 2026 que cobra deuda de
     * 2025 cae en el mes 3 y en el ejercicio 2025, y las dos cosas son ciertas.
     *
     * @param tributos por cuales se pregunta; con la coleccion vacia, la respuesta va vacia
     * @param desde fecha valor minima, inclusive
     * @param hasta fecha valor maxima, inclusive
     * @param aLaFecha la fecha con la que se responde; viaja con la cifra (regla 9, RNF-075)
     */
    RecaudadoEnElLibro recaudadoPor(
            Collection<String> tributos, LocalDate desde, LocalDate hasta, LocalDate aLaFecha);

    /**
     * Lo cobrado de <b>todos</b> los tributos entre las dos fechas, con el mismo desglose y los
     * mismos criterios que {@link #recaudadoPor} (#56, RF-130).
     *
     * <p>Existe para el panel de recaudacion, que no puede enumerar los tributos: hacerlo obligaria
     * a compilar en el codigo una lista de nombres tributarios que el libro ya conoce, y esa lista
     * se quedaria corta el dia que apareciera un tributo nuevo —sin fallar, solo dejandolo fuera
     * del total—.
     *
     * <p><b>No es {@link #recaudadoPor} con la coleccion vacia</b>, y esa distincion es deliberada:
     * alli la coleccion vacia devuelve la respuesta vacia, que es lo correcto cuando un area
     * pregunta por sus tributos y resulta que no tiene ninguno. El mismo argumento no puede
     * significar «nada» y «todo».
     *
     * @param desde fecha valor minima, inclusive
     * @param hasta fecha valor maxima, inclusive
     * @param aLaFecha la fecha con la que se responde; viaja con la cifra (regla 9, RNF-075)
     */
    RecaudadoEnElLibro recaudadoDeTodos(LocalDate desde, LocalDate hasta, LocalDate aLaFecha);
}
