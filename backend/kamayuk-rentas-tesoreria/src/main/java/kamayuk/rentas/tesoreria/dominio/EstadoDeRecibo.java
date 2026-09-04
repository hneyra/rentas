package kamayuk.rentas.tesoreria.dominio;

/**
 * En que situacion esta un recibo. <b>Se deriva</b>, no se guarda (V30).
 *
 * <p>V3 le habia puesto a {@code recibo} una columna {@code estado} con {@code DEFAULT 'EMITIDO'};
 * V30 la retiro porque decia {@code EMITIDO} para siempre —el recibo no se actualiza (V29), asi que
 * ninguna sentencia podia moverla— y una columna que miente es peor que una columna que falta. Lo
 * que hay es una fila de {@code recibo_movimiento} con {@code tipo = 'ANULACION'}, o no la hay.
 *
 * <p>Existe como enumerado y no como dos cadenas sueltas porque desde #548 lo miran cuatro sitios:
 * la vista previa del duplicado, el acta de anulacion, el filtro del listado y la fila que ese
 * listado devuelve —los dos primeros publicaban su propio literal desde #34 y ahora lo derivan de
 * aqui—. Con literales, el dia que uno de los cuatro escribiera {@code "Anulado"} el filtro dejaria
 * de encontrar los anulados y la grilla saldria entera, que es exactamente la lectura que quien
 * filtra cree haber descartado.
 */
public enum EstadoDeRecibo {

    /** Sin anulacion: el recibo vale lo que dice. */
    EMITIDO,

    /** Tiene su acta de anulacion, y la deuda que cobro volvio al libro. */
    ANULADO;

    /** El estado que corresponde a un recibo segun tenga o no su fila de anulacion. */
    public static EstadoDeRecibo deLaAnulacion(boolean anulado) {
        return anulado ? ANULADO : EMITIDO;
    }
}
