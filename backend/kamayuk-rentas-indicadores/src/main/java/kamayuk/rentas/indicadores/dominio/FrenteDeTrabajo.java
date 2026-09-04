package kamayuk.rentas.indicadores.dominio;

/**
 * Cada frente de trabajo parado que la pantalla de aterrizaje enumera (#549, RF-130).
 *
 * <h2>Que es un frente</h2>
 *
 * <p>Un cuello de botella <b>contable</b>: un conjunto de expedientes, papeletas o predios que ya
 * existen, que estan esperando un acto de la administracion y que mientras esperan no cobran. No es
 * una alerta ni un indicador de gestion: es un recuento de trabajo pendiente, con el modulo donde
 * se desatasca.
 *
 * <p>El enumerado es cerrado a proposito. Cada frente cuesta una consulta en la pantalla que todo
 * el mundo abre al entrar, y anadir uno obliga a escribir aqui —y en {@code build.gradle.kts} del
 * modulo— por que el panel necesita mirar ese contexto.
 *
 * <h2>{@link #acceso} es lo que decide quien lo ve</h2>
 *
 * <p>Es el id de la opcion del catalogo (NEG-03) de la pantalla del modulo donde ese trabajo se
 * desatasca, y el mismo que su controlador exige. Quien no puede abrir esa pantalla <b>no recibe el
 * frente</b> —ni siquiera vacio—: una fila vacia ya dice que ahi hay algo que mirar, que es la
 * lectura que ADR-0016 §2 prohibe (#297).
 *
 * <h2>Los dos que faltan, y por que</h2>
 *
 * <p>El issue enumera <b>seis</b> y aqui hay <b>cuatro</b>. Los dos que faltan no se dejaron para
 * despues por comodidad:
 *
 * <ul>
 *   <li><b>Licencias — solicitudes con el plazo agotado.</b> Necesita el plazo del silencio
 *       positivo, que es un valor normativo: la regla 5 prohibe compilarlo y el corpus no publica
 *       ninguna llave {@code PLAZO} de licencias. Un plazo inventado no cobra de mas, <b>autoriza
 *       de mas</b> —el mismo perjuicio que #54 midio con la vigencia del certificado—, y ademas
 *       aqui produciria la lista de las que van a quedar otorgadas sin que nadie las resuelva.
 *   <li><b>Fiscalizacion — actas con diferencia sin liquidar.</b> Ninguna pantalla del modulo lista
 *       actas: {@code fisc_resultados} lista <b>liquidaciones</b>, y {@code
 *       ActaFiscalizacionRepository} no tiene una sola consulta paginada. No hay, por tanto,
 *       ninguna consulta que reutilizar, y escribir aqui la primera definicion de «acta con
 *       diferencia» seria darle a la pantalla de aterrizaje una cifra que ninguna pantalla del
 *       modulo puede confirmar (AC 2.4 leido al derecho).
 * </ul>
 */
public enum FrenteDeTrabajo {

    /**
     * Papeletas vivas a las que nadie ha emitido su resolucion de multa.
     *
     * <p>El issue lo llama «papeletas levantadas y nunca notificadas», y el rotulo que sale por
     * HTTP dice lo que el sistema <b>puede</b> afirmar: sin valor emitido. El motivo esta medido en
     * el javadoc de {@code PapeletasSinNotificar} — ningun codigo de produccion escribe {@code
     * EstadoDePapeleta.NOTIFICADA}, asi que ese estado no distingue lo que su nombre promete, y
     * publicar «sin notificar» contando por el seria una cifra plausible y equivocada.
     */
    TRANSITO(
            "Transito",
            "papeletas sin resolucion de multa emitida",
            "sin emitir no se pueden notificar ni cobrar, y prescriben",
            "transito_padron"),

    /** Valores emitidos y sin notificar: existen, no cobran, y el plazo les corre igual. */
    VALORES(
            "Valores",
            "valores emitidos y sin notificar",
            "existen, no cobran, y el plazo de prescripcion les corre igual",
            "consulta_valores"),

    /** Expedientes importados sin REC-1: el expediente esta abierto y no ha empezado. */
    COACTIVA(
            "Coactiva",
            "expedientes importados sin REC-1",
            "el expediente esta abierto y el procedimiento no ha empezado",
            "coactiva_expedientes"),

    /** Predios con ficha y sin conciliar con rentas: tienen ficha y no generan deuda. */
    CATASTRO(
            "Catastro",
            "predios con ficha y sin conciliar con rentas",
            "tienen ficha catastral y no generan deuda predial",
            "consulta_fichas");

    private final String modulo;
    private final String queEstaParado;
    private final String porQueCuestaDinero;
    private final String acceso;

    FrenteDeTrabajo(String modulo, String queEstaParado, String porQueCuestaDinero, String acceso) {
        this.modulo = modulo;
        this.queEstaParado = queEstaParado;
        this.porQueCuestaDinero = porQueCuestaDinero;
        this.acceso = acceso;
    }

    /** El modulo del manual donde se desatasca. */
    public String modulo() {
        return modulo;
    }

    /** Que es lo que esta parado, en las palabras del propio issue. */
    public String queEstaParado() {
        return queEstaParado;
    }

    /** Por que cuesta dinero tenerlo parado. */
    public String porQueCuestaDinero() {
        return porQueCuestaDinero;
    }

    /** El id de la opcion del catalogo cuyo permiso de lectura hace falta para verlo. */
    public String acceso() {
        return acceso;
    }
}
