package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Los valores emitidos.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). No hay ningun metodo que actualice ni que
 * borre una fila de {@code valor} o {@code valor_detalle}: ni siquiera existe el privilegio en la
 * base (V26). "Se anula, se da de baja o se reversa" (regla 4) es un acto distinto, todavia sin
 * issue propio.
 */
public interface ValorRepository {

    /**
     * Guarda la cabecera y su detalle en una sola operacion.
     *
     * @param valor el valor a guardar; {@link Valor#esNuevo()} tiene que ser verdadero
     * @param detalle las obligaciones que formaliza; ninguna puede estar ya guardada
     * @return el mismo valor, con su {@code id} asignado
     */
    Valor insertar(Valor valor, List<ValorDetalle> detalle);

    Optional<Valor> porNumero(TipoValor tipo, Ejercicio ejercicio, String numero);

    /**
     * El valor por su numero, sin decir de que tipo es.
     *
     * <p>Existe porque las tres opciones de #39 identifican el valor solo por su numero -asi lo
     * declaran sus rutas: {@code /valores/{nro}/notificacion} y {@code /valores/{numero}/
     * movimientos}-, y pedirle el tipo a quien ya escribio "OP-2026-000001" seria pedirle que
     * repita lo que el numero ya dice.
     *
     * <p>La unicidad real es {@code (municipalidad_id, tipo, numero)} (V3), asi que en teoria dos
     * tipos podrian compartir numero. Si eso llegara a pasar, esto falla en vez de elegir uno: un
     * valor notificado por error es un acto administrativo sobre la deuda equivocada.
     */
    Optional<Valor> porNumero(String numero);

    /** El mismo valor por su identificador, para quien ya lo resolvio antes (p. ej. #38). */
    Optional<Valor> porId(long id);

    /** El detalle de un valor ya guardado, en el orden en que se congelo. */
    List<ValorDetalle> detalleDe(long valorId);

    Pagina<Valor> buscar(CriterioDeValor criterio, Paginacion paginacion);

    /**
     * La grilla de {@code consulta_valores} (RF-041, #25): la cabecera de cada valor con lo que la
     * pantalla muestra y la cabecera no guarda.
     *
     * <p>Va aparte de {@link #buscar} porque no consulta lo mismo. {@link #buscar} lee {@code
     * valor} y nada mas; esto cruza ademas {@code valor_detalle} —que tributo y que ejercicios
     * formaliza—, {@code notificacion} —cuando surtio efecto— y {@code valor_movimiento} —si ya hay
     * pase—, y filtra por {@link SituacionDelValor}, que no es ninguna columna.
     *
     * <p><b>El filtro por situacion se resuelve en SQL, no despues de paginar.</b> Filtrar en Java
     * las veinte filas que la base devolvio daria un total equivocado —«1 de 47» sobre 47 sin
     * filtrar— y paginas con menos de veinte lineas sin motivo visible.
     */
    Pagina<ValorEnConsulta> consultar(CriterioDeConsultaDeValores criterio, Paginacion paginacion);

    /**
     * Cuantos valores cumplen ese criterio, <b>sin traerse ninguno</b> (#549).
     *
     * <p>Es el {@code count(*)} que {@link #consultar} ya ejecuta para poder paginar, con el mismo
     * {@code FROM} y el mismo {@code WHERE} —incluida la condicion de {@link SituacionDelValor},
     * que no es una columna sino una expresion—. Existe para que el panel de trabajo parado pida la
     * cifra sin componer ademas una pagina de valores que nadie va a dibujar, y sobre todo para que
     * esa cifra no sea una <b>segunda</b> transcripcion de «sin notificar»: si divergieran, la de
     * la pantalla de aterrizaje es la que se lee primero (AC 2.4 de #549).
     */
    long contar(CriterioDeConsultaDeValores criterio);

    /**
     * Los valores del contribuyente que formalizan ese tributo y ese ejercicio, y que todavia se
     * pueden cobrar.
     *
     * <p>Existe para la prescripcion (#39): la solicitud se presenta por contribuyente, tributo y
     * rango de ejercicios, y hay que saber que valores alcanza. Los que ya estan {@code PAGADO},
     * {@code ANULADO} o {@code PRESCRITO} no se devuelven: sobre ellos no hay accion de cobro que
     * prescriba.
     */
    List<Valor> cobrablesDe(long contribuyenteId, String tributo, Ejercicio ejercicio);

    /**
     * Mueve el estado de un valor ya emitido, sin tocar su desglose congelado.
     *
     * <p>Es el unico {@code UPDATE} que {@code valor} admite, y solo sobre {@code estado}: lo que
     * cambia despues de emitir es en que punto de la cobranza esta el valor, nunca cuanto dice.
     * Reimprimirlo dos anios despues sigue devolviendo el mismo desglose (AC de #37).
     *
     * @return el valor releido, con su estado nuevo
     * @throws IllegalArgumentException si el valor no existe
     */
    Valor cambiarEstado(long valorId, EstadoDeValor nuevo);

    /**
     * El siguiente correlativo para ese tipo y ejercicio, unico y sin huecos bajo concurrencia real
     * (AC de #37).
     *
     * <p>D-09 decide el formato del numero final —con que ceros, si se reinicia—; lo que aqui se
     * garantiza es que la secuencia no se repita ni salte, y lo garantiza un {@code UPDATE} atomico
     * contra {@code valor_correlativo}, no una lectura seguida de una escritura.
     */
    long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio);
}
