package kamayuk.rentas.coactiva;

/**
 * Cuantos expedientes coactivos estan abiertos y todavia sin REC-1 (#549).
 *
 * <p>Es la <b>API publica</b> de {@code coactiva} para el panel de trabajo parado, y devuelve un
 * <b>agregado</b>: un recuento, nunca la lista. Ver {@code PapeletasSinNotificar} para el porque.
 *
 * <h2>«Sin REC-1» es {@code INICIADO}, y es un derivado, no una columna</h2>
 *
 * <p>La cifra sale del <b>mismo</b> {@code WHERE} que sostiene la consulta de expedientes ({@code
 * coactiva_expedientes}), con {@code EstadoDelExpediente.INICIADO} — que el repositorio deriva del
 * ultimo movimiento y no guarda en ninguna columna (V33). Escribir aqui un {@code count} propio
 * significaria transcribir esa derivacion por segunda vez, y dos copias divergen: la que se lee en
 * la pantalla de aterrizaje seria la que nadie recalculo (AC 2.4 de #549, el precedente de #397).
 *
 * <p><b>Sin importe.</b> La deuda de un expediente se compone valor a valor con sus costas, asi que
 * cifrar este frente costaria una consulta por expediente en la pantalla que todo el mundo abre al
 * entrar (AC 4 de #56). El frente se publica con su recuento y sin cifrar.
 *
 * <p>Por que cuesta dinero: el expediente esta abierto y el procedimiento no ha empezado.
 */
public interface ExpedientesSinRec {

    /**
     * Cuantos expedientes siguen en {@code INICIADO}.
     *
     * <p>Sin fecha de corte: el estado se deriva del <b>ultimo</b> movimiento del expediente y el
     * repositorio no sabe reconstruirlo a una fecha pasada. Quien lo publica le pone la fecha de la
     * lectura, que es lo que esa cifra describe.
     */
    long cuantosSinRec1();
}
