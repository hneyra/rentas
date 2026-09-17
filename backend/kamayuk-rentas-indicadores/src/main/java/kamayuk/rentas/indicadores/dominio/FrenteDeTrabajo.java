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
 *
 * <h2>Y por que NO publica una situacion, que es lo que #183 vino a pedir</h2>
 *
 * <p>La quinta columna de {@code ini-parado} es la de la insignia, y el artboard dibuja ahi un
 * <b>estado</b> —«Vencida», «Por vencer»—. Hoy la interfaz le pasa {@link #porQueCuestaDinero}, que
 * es una frase; desde #175 sale con el tono de «no se» y por tanto la insignia no enciende. La
 * salida honesta habria sido publicar aqui la situacion del frente, cerrada y anulable como ya lo
 * es el importe. <b>Se midio, y no se puede.</b> #183 se cerro sin implementarlo, y queda escrito
 * aqui porque este es el enumerado que alguien va a mirar cuando se lo vuelva a preguntar.
 *
 * <p><b>Primero: no hay con que juzgar.</b> Los cuatro puertos devuelven un <b>agregado</b> —{@code
 * PapeletasSinNotificar} un recuento y una suma; {@code ValoresSinNotificar}, {@code
 * ExpedientesSinRec} y {@code PrediosSinConciliar} un recuento a secas— y ninguno publica la
 * <b>antiguedad</b> de lo que esta parado: ni la fecha del mas viejo, ni cuantos pasan de un
 * umbral. Y no es un olvido que se arregle anadiendo un campo: lo que no pueden devolver es la
 * <b>lista</b>, porque la pantalla de aterrizaje la abre todo el mundo al entrar y recorrer el
 * padron entero es lo que el AC 4 de #56 prohibe y {@code PanelSinRecorrerElLibroTest} comprueba.
 *
 * <p><b>Segundo, y es el que cierra la puerta: no hay plazo publicado para ninguno de los
 * cuatro.</b> «Vencida» es «vencida respecto de que», y ese «que» es un valor normativo que la
 * regla 5 prohibe compilar. Medido el 2026-09-17 sobre el derivado publicable del corpus —el CSV de
 * {@code normativa}, nueve filas de tipo {@code PLAZO}—:
 *
 * <ul>
 *   <li>las tres de prescripcion del art. 43 del TUO del Codigo Tributario se distinguen por si el
 *       <b>deudor presento o no su declaracion</b>. Una papeleta de transito, un expediente
 *       coactivo y un predio sin conciliar no tienen declaracion que presentar, asi que elegir una
 *       de las tres seria inventar cual aplica;
 *   <li>{@code REC1_CUMPLIMIENTO} son siete dias habiles <b>de notificado el REC-1</b>, y el frente
 *       de coactiva es precisamente «expedientes <b>sin</b> REC-1»: el plazo arranca en un acto que
 *       todavia no ocurrio;
 *   <li>las tres de {@code NOTIFICACION_VALOR} son el plazo de <b>reclamacion</b> que corre desde
 *       la notificacion, y el frente de valores es «emitidos y <b>sin</b> notificar»: mismo caso;
 *   <li>las dos de {@code PRESCRIPCION_INICIO} fijan <b>cuando empieza</b> a contarse el plazo —un
 *       ano de desfase—, no cuanto puede estar algo parado;
 *   <li>y para el frente de catastro el corpus no publica ninguna, porque no hay norma que ponga
 *       plazo a conciliar una ficha con el padron.
 * </ul>
 *
 * <p>Los dos plazos que {@code sanciones} ya lee —{@code DESCARGO_PAPELETA} y el de cumplimiento de
 * la resolucion ordinaria— tampoco sirven, y ademas <b>tampoco estan publicados</b> en ese CSV: no
 * son «el plazo que tiene la administracion para emitir», que es el que este frente necesitaria.
 *
 * <p><b>Y el remate, que es por que no se publica el campo «por si acaso».</b> Si ningun frente se
 * puede juzgar, {@code situacion} saldria <b>nula en los cuatro, siempre</b>. Eso no es el caso que
 * el issue describia —un frente que no se puede cifrar entre otros que si— sino un campo que no
 * dice nada nunca; y peor, pondria roja a proposito la guarda {@code
 * la-insignia-no-se-pinta-verde-sin-regla} de la interfaz, que al verlo pide «dale a la columna su
 * regla de insignia sobre ESE campo». La regla no tendria sobre que decidir. Lo que sobra es la
 * <b>columna</b>, no la regla: se arregla en el artboard.
 *
 * <p>Cuando alguna de las dos cosas cambie —un puerto que pueda decir la antiguedad sin devolver la
 * lista, o un plazo que el corpus selle—, esto se reabre con el plazo delante y no antes. Un umbral
 * inventado aqui no cobra de mas: <b>declara vencido trabajo que todavia se puede hacer</b>, o
 * tranquiliza sobre el que ya no.
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
