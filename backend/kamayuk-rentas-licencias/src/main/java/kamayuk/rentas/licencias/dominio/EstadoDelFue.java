package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * En que situacion esta un FUE. <b>No es una columna: se deriva</b> (#48, V43 §1).
 *
 * <p>V4 le habia puesto a {@code licencia_edificacion} un {@code estado varchar(15) DEFAULT
 * 'VIGENTE'}, y V43 se lo retira por lo mismo que V37 se lo retiro a la licencia de funcionamiento,
 * V35 a las costas, V33 al expediente, V32 al turno, V31 al convenio y V30 al recibo. Aqui el
 * defecto era ademas mas visible que en ninguna otra: el FUE nace <b>en tramite</b>, y con la
 * columna de V4 un expediente recien presentado ya decia «VIGENTE».
 *
 * <h2>Por que la fecha entra como argumento (regla 6, regla 9)</h2>
 *
 * <p>«Vencida» no es un hecho de la licencia: es una relacion entre sus tramos de vigencia y un
 * dia. Una licencia que vencio ayer estaba vigente anteayer, y un reporte impreso con fecha de
 * corte de anteayer tiene que decir VIGENTE. Resolverlo con {@code LocalDate.now()} haria que
 * reimprimir ese reporte manana diera otra cosa.
 */
public enum EstadoDelFue {

    /** Presentado y todavia sin licencia. Es el estado con que nace todo expediente. */
    EN_TRAMITE,

    /** Con licencia otorgada y algun tramo de vigencia que cubre la fecha preguntada. */
    VIGENTE,

    /** Con licencia otorgada y ningun tramo que cubra la fecha preguntada. */
    VENCIDA,

    /** Dejada sin efecto por resolucion (regla 4: no se borra, se anula). */
    ANULADA;

    /**
     * El estado que dicen los movimientos y las vigencias a esa fecha.
     *
     * <p>La anulacion gana sobre todo lo demas: una licencia anulada el 3 de marzo lo sigue estando
     * en diciembre, aunque su vigencia hubiera terminado igual. El orden importa porque las
     * consecuencias son distintas —una vencida se revalida, una anulada no—.
     *
     * <p>Un tramo de vigencia <b>posterior</b> a la fecha preguntada no cuenta: la licencia
     * revalidada en junio no estaba vigente en abril por haberlo sido despues.
     *
     * @param movimientos los del expediente, en cualquier orden
     * @param vigencias los tramos de la licencia, en cualquier orden
     * @param aLaFecha el dia al que se pregunta
     */
    public static EstadoDelFue derivarDe(
            List<MovimientoDeEdificacion> movimientos,
            List<VigenciaDeLaLicencia> vigencias,
            LocalDate aLaFecha) {

        Objects.requireNonNull(movimientos, "La lista de movimientos es vacia, no nula");
        Objects.requireNonNull(vigencias, "La lista de vigencias es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "El estado se pregunta a una fecha (regla 6, regla 9)");

        boolean emitida = false;
        for (MovimientoDeEdificacion movimiento : movimientos) {
            if (movimiento.fecha().isAfter(aLaFecha)) {
                continue;
            }
            if (movimiento.tipo() == TipoDeMovimientoDeEdificacion.ANULACION) {
                return ANULADA;
            }
            if (movimiento.tipo() == TipoDeMovimientoDeEdificacion.EMISION) {
                emitida = true;
            }
        }
        if (!emitida) {
            return EN_TRAMITE;
        }
        for (VigenciaDeLaLicencia vigencia : vigencias) {
            if (vigencia.cubre(aLaFecha)) {
                return VIGENTE;
            }
        }
        return VENCIDA;
    }

    /** La letra con que la grilla la pinta en su columna de estado. */
    public String inicial() {
        return name().substring(0, 1);
    }
}
