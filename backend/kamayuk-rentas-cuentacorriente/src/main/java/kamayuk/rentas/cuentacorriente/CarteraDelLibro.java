package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Cuanto se puso a cobrar y cuanto sigue pendiente, por tributo (#56, RF-130).
 *
 * <p>Es la <b>novena</b> API publica de este modulo, despues de {@link ConsultaDeDeudaPublica},
 * {@link GeneradorDeCargos}, {@link MovimientoDeFase}, {@link RegistroDeAbonos}, {@link
 * AcogimientoAConvenio}, {@link ConciliacionDeCaja}, {@link MovimientosDelLibro} y {@link
 * RecaudacionDelLibro}. Vive en el paquete raiz por lo mismo que las otras ocho: Spring Modulith
 * trata como interno todo lo que esta en un subpaquete, asi que esto es exactamente lo que otro
 * contexto puede ver del libro. Sus tablas, no.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para el panel de recaudacion (#56): {@link RecaudacionDelLibro} contesta cuanto <b>entro</b>,
 * y un avance necesita ademas contra que se compara. {@code tesoreria.ConsultaDeRecaudacion} ya
 * nombro este hueco por su nombre —«lo emitido son los cargos del libro, y este contexto no lee las
 * tablas de cuentacorriente»—, y lo que faltaba era justamente esta interfaz.
 *
 * <h2>Que se cuenta como «cargado»</h2>
 *
 * <p>La pregunta tenia tres respuestas posibles y las tres dan cifras distintas: la determinacion
 * anual de {@code rentas}, el valor notificado de {@code valores}, o el cargo asentado en el libro.
 * Se elige el <b>cargo asentado</b>, y por un motivo que no es de comodidad: es la unica de las
 * tres que <b>cuadra</b> con lo recaudado, porque las dos cifras salen de la misma tabla y del
 * mismo criterio de reversion. Un panel cuyo numerador viniera del libro y cuyo denominador viniera
 * de {@code determinacion} podria pasar del 100 % sin que nada estuviera mal, y nadie sabria decir
 * por que.
 *
 * <h2>Al reves de la regla 2</h2>
 *
 * <p>ARQ-01 §4: «cuentacorriente no conoce a nadie». Esta interfaz es la excepcion que la regla
 * preve —otro modulo depende de {@code cuentacorriente}, nunca al reves—, y por eso no recibe
 * ningun tipo de otro contexto: solo un ejercicio y una fecha.
 *
 * <h2>Solo lectura, y sin recorrer nada</h2>
 *
 * <p>Ningun metodo escribe, ninguno bloquea y ninguno devuelve una fila por obligacion: las dos
 * respuestas son agregados que calcula PostgreSQL, con una linea por tributo. Es el AC 4 de #56, y
 * no es una preferencia de estilo: la cartera de un padron son decenas de miles de filas, y
 * traerlas para escribir doce cifras pondria la caja a esperar por el panel de inicio.
 */
public interface CarteraDelLibro {

    /**
     * Lo cargado en el ejercicio, desglosado por tributo.
     *
     * <p>Un cargo cuenta si es de tipo {@code CARGO}, de concepto {@code INSOLUTO} y <b>nadie lo ha
     * reversado</b>. Los otros conceptos —reajuste, interes, gasto— no son el tributo puesto a
     * cobrar sino lo que se le fue anadiendo, y meterlos aqui haria que el avance de cobranza
     * bajara cada vez que corre el interes.
     *
     * @param ejercicio de que ejercicio son las obligaciones
     * @param aLaFecha la fecha con la que se responde; viaja con la cifra (regla 9, RNF-075)
     */
    CargadoEnElLibro cargadoPorTributo(Ejercicio ejercicio, LocalDate aLaFecha);

    /**
     * Lo que sigue pendiente en el ejercicio <b>a la fecha de corte</b>, desglosado por tributo.
     *
     * <p>Es <b>insoluto</b>, no deuda: ver {@link PendienteDeUnTributo}. Se cuentan solo las
     * obligaciones con insoluto <b>positivo</b>; una en cero esta cancelada y una negativa es un
     * pago en exceso, que es un hecho del libro pero no es cartera por cobrar y restarlo taparia
     * deuda ajena.
     *
     * <p><b>{@code aLaFecha} es una fecha de corte, no un sello.</b> Hasta #639 esta cifra salia de
     * la proyeccion del saldo (#23), que netea la obligacion entera sin fecha —no tiene ninguna
     * columna con la que aplicarla—, asi que la cartera incluia la cuota que todavia no vence y
     * daba lo mismo preguntando por enero que por diciembre. Medido en la municipalidad de
     * demostracion, PREDIAL 2026 al 2026-09-01: 10 662,60 sin corte, <b>8 221,05</b> con el, y los
     * 2 441,55 de diferencia eran las siete cuotas con fecha valor 2026-11-30. La cifra que la
     * ventanilla lee delante del contribuyente —{@code GET /consultas/deuda}— es la segunda, y esta
     * es su suma sobre el padron.
     *
     * @param ejercicio de que ejercicio son las obligaciones
     * @param aLaFecha la fecha de corte; ningun asiento posterior entra (regla 9, RNF-075)
     */
    CarteraPendiente pendientePorTributo(Ejercicio ejercicio, LocalDate aLaFecha);
}
