package kamayuk.rentas.coactiva.dominio;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * En que punto del procedimiento esta un expediente coactivo (#40, RF-100).
 *
 * <p><b>No es una columna.</b> V33 le retiro a {@code expediente_coactivo} la columna {@code
 * estado} que V3 le habia puesto, por lo mismo que V31 se la retiro al convenio y V30 al recibo: la
 * tabla no admite {@code UPDATE}, asi que la columna habria dicho {@code ABIERTO} para siempre
 * —tambien de un expediente concluido— y cualquier consulta ad hoc la habria leido como la verdad.
 *
 * <p>El estado se <b>deriva</b> de {@code expediente_movimiento}, y {@link #delHistorial} es el
 * unico sitio donde se deriva. Que sea uno solo es lo que impide que la grilla diga una cosa y la
 * pantalla de historial otra.
 *
 * <p>Funcion pura (regla 6): entran los movimientos y sale el estado. Sin base y sin reloj.
 *
 * <h2>El vocabulario es el del prototipo</h2>
 *
 * <p>Los seis codigos —{@code 011} a {@code 051}— son los que ofrece el desplegable «Nuevo estado»
 * de la pantalla {@code expediente_historial}. {@link #INICIADO} no esta ahi porque no se elige: es
 * con lo que nace el expediente al importar, antes de cualquier REC, y es lo que la pantalla {@code
 * coactiva_expedientes} filtra como «INICIADO».
 *
 * <p>El vocabulario de V3 —{@code ABIERTO}, {@code ARCHIVADO}— se retira con la columna: nadie
 * escribio nunca en ella, y mantener dos vocabularios para el mismo procedimiento es garantizar que
 * un dia digan cosas distintas.
 */
public enum EstadoDelExpediente {

    /** Recien abierto con sus valores importados; todavia sin REC. */
    INICIADO("000", "INICIADO"),

    /** REC 01 emitida. */
    REC1_EMITIDA("011", "REC 01 EMITIDO"),

    /** REC 01 notificada al obligado. */
    REC1_NOTIFICADA("012", "REC 01 NOTIFICADA"),

    /** REC 02 emitida. */
    REC2_EMITIDA("021", "REC 02 EMITIDA"),

    /** Con medida cautelar trabada. */
    MEDIDA_CAUTELAR("031", "MEDIDA CAUTELAR"),

    /** Suspendido por alguna de las causales del art. 16 de la Ley 26979. */
    SUSPENDIDO("041", "SUSPENDIDO"),

    /** Concluido. */
    CONCLUIDO("051", "CONCLUIDO");

    /** Como lo llama la pantalla {@code coactiva_expedientes} en su filtro «Estado». */
    private static final String CON_MEDIDA_CAUTELAR = "CON MEDIDA CAUTELAR";

    /**
     * La linea del procedimiento, en su orden (#409). Es una lista y no el orden de declaracion del
     * enumerado porque {@link #SUSPENDIDO} y {@link #CONCLUIDO} estan declarados despues de {@link
     * #MEDIDA_CAUTELAR} y no van despues de ella: estan fuera de la linea.
     */
    private static final List<EstadoDelExpediente> LINEA_DEL_PROCEDIMIENTO =
            List.of(INICIADO, REC1_EMITIDA, REC1_NOTIFICADA, REC2_EMITIDA, MEDIDA_CAUTELAR);

    private final String codigo;
    private final String etiqueta;

    EstadoDelExpediente(String codigo, String etiqueta) {
        this.codigo = codigo;
        this.etiqueta = etiqueta;
    }

    /** El codigo del manual: {@code 011}, {@code 012}, {@code 021}, {@code 031}, {@code 041}… */
    public String codigo() {
        return codigo;
    }

    /** Como lo escribe la pantalla. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * El estado que describe este historial.
     *
     * <p>Es el del <b>ultimo</b> movimiento que lleve estado. Los de direccion referencial no lo
     * mueven: cambiar donde se notifica no cambia en que punto esta el procedimiento.
     *
     * <p>Un expediente sin ningun movimiento esta {@link #INICIADO}. No deberia existir —la
     * importacion escribe su apertura en la misma transaccion—, pero derivar de la lista vacia el
     * estado inicial es mas honesto que lanzar: lo que la carpeta dice es que todavia no le paso
     * nada.
     */
    public static EstadoDelExpediente delHistorial(Iterable<MovimientoDelExpediente> movimientos) {
        Objects.requireNonNull(movimientos, "El estado se deriva del historial");
        EstadoDelExpediente estado = INICIADO;
        for (MovimientoDelExpediente movimiento : movimientos) {
            EstadoDelExpediente delMovimiento = movimiento.estado();
            if (delMovimiento != null) {
                estado = delMovimiento;
            }
        }
        return estado;
    }

    /**
     * El estado cuyo nombre, codigo o etiqueta coincide.
     *
     * <p>Acepta las tres formas porque las tres circulan: el nombre de la constante en el cuerpo
     * JSON, el codigo {@code 011} en el desplegable del manual, y la etiqueta «CON MEDIDA CAUTELAR»
     * en el filtro de la grilla. Traducirlas en el borde de cada endpoint seria tener tres
     * traductores que un dia difieren.
     *
     * @throws IllegalArgumentException si no es ninguna de las tres formas
     */
    public static EstadoDelExpediente porNombre(String nombre) {
        String limpio = Objects.requireNonNull(nombre, "Falta el estado").strip();
        String mayusculas = limpio.toUpperCase(Locale.ROOT);
        if (CON_MEDIDA_CAUTELAR.equals(mayusculas)) {
            return MEDIDA_CAUTELAR;
        }
        // «011 — REC 01 EMITIDO» tal como lo manda el desplegable: se queda con el codigo.
        String codigoSuelto = mayusculas.split("[^0-9]", 2)[0];
        for (EstadoDelExpediente estado : values()) {
            if (estado.name().equals(mayusculas)
                    || estado.etiqueta.equals(mayusculas)
                    || estado.codigo.equals(mayusculas)
                    || (!codigoSuelto.isEmpty() && estado.codigo.equals(codigoSuelto))) {
                return estado;
            }
        }
        throw new IllegalArgumentException(
                "Estado de expediente desconocido: '"
                        + nombre
                        + "'. Se admite el nombre, el codigo del manual (011, 012, 021, 031, 041,"
                        + " 051) o la etiqueta de la pantalla");
    }

    /** Si el procedimiento ya termino: sobre un expediente concluido no hay actos que registrar. */
    public boolean estaConcluido() {
        return this == CONCLUIDO;
    }

    /**
     * Si pasar de este estado a {@code destino} es <b>avanzar</b> por la linea del procedimiento
     * (#409).
     *
     * <p>La linea es la del procedimiento ordinario: {@code INICIADO < REC1_EMITIDA <
     * REC1_NOTIFICADA < REC2_EMITIDA < MEDIDA_CAUTELAR}. Es verdadero solo si <b>los dos</b> estan
     * en ella y el destino va despues. {@link #SUSPENDIDO} y {@link #CONCLUIDO} quedan fuera a
     * proposito: a los dos se llega por una resolucion, y de los dos se sale —si se sale— por otra.
     * Un evento automatico nunca los abandona.
     *
     * <p>Es la politica que consulta quien mueve el expediente <b>sin que el ejecutor dicte
     * nada</b> —hoy, la diligencia de la REC-1—, en vez de enumerar los estados en que no debe
     * hacerlo. Hasta #409 esa enumeracion decia «salvo que ya este en {@code REC1_NOTIFICADA} o
     * concluido», y el cargo de un intento que volvia dias despues de dictada la suspension o
     * trabada la medida devolvia el expediente a la REC-1: el estado es el del <b>ultimo</b>
     * movimiento ({@link #delHistorial}), y ese movimiento lo agregaba la diligencia.
     *
     * <p>No gobierna el cambio de estado manual: que el operador pueda retroceder entre los seis
     * estados del desplegable es una decision de negocio que #409 deja fuera.
     *
     * @param destino el estado al que se moveria
     */
    public boolean avanzaHacia(EstadoDelExpediente destino) {
        Objects.requireNonNull(destino, "Falta el estado de destino");
        int desde = LINEA_DEL_PROCEDIMIENTO.indexOf(this);
        int hasta = LINEA_DEL_PROCEDIMIENTO.indexOf(destino);
        return desde >= 0 && hasta >= 0 && hasta > desde;
    }
}
