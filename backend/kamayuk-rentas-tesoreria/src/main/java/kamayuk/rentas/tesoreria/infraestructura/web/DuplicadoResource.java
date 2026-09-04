package kamayuk.rentas.tesoreria.infraestructura.web;

import kamayuk.rentas.tesoreria.aplicacion.DuplicadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.EstadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import org.jspecify.annotations.Nullable;

/**
 * La vista previa de un recibo antes de reimprimirlo (RF-082).
 *
 * <p>Es lo que la pantalla pinta: el recibo con sus cifras congeladas —cada una con su fecha, en
 * {@link ReciboResource}— y lo que le paso despues.
 *
 * <p>{@code estado} se <b>deriva</b> del movimiento de anulacion: no hay ninguna columna que lo
 * guarde, y no la hay a proposito. V30 retiro la que V3 dejo puesta porque decia {@code EMITIDO}
 * para siempre —el recibo no se actualiza (V29)— y una columna que miente es peor que una columna
 * que falta.
 *
 * @param estado {@code EMITIDO} o {@code ANULADO}, derivado
 * @param duplicados cuantas veces se ha reimpreso ya; lo que la pantalla muestra en su columna
 * @param anulacion el acta, si la hubo
 * @param recibo el recibo con su desglose
 */
public record DuplicadoResource(
        String estado, long duplicados, @Nullable AnulacionBreve anulacion, ReciboResource recibo) {

    /** El estado de un recibo sin anulacion. Del enumerado, por lo mismo (#548). */
    public static final String EMITIDO = EstadoDeRecibo.EMITIDO.name();

    public static DuplicadoResource de(DuplicadoDeRecibo.Consultado consultado) {
        MovimientoDeRecibo anulacion = consultado.anulacion();
        return new DuplicadoResource(
                anulacion == null ? EMITIDO : AnulacionResource.ANULADO,
                consultado.duplicados(),
                anulacion == null ? null : AnulacionBreve.de(anulacion),
                ReciboResource.de(consultado.recibo()));
    }

    /**
     * Lo que hay que saber de la anulacion para pintarla en la pantalla.
     *
     * <p>Sin el importe: el del recibo ya viaja en {@link ReciboResource#total()} con su fecha, y
     * repetirlo aqui seria la segunda copia de la misma cifra, que es como acaban discrepando.
     *
     * @param fecha el dia de la anulacion
     * @param motivo el sustento del acto
     * @param usuario quien la registro
     */
    public record AnulacionBreve(String fecha, String motivo, @Nullable String usuario) {

        static AnulacionBreve de(MovimientoDeRecibo anulacion) {
            return new AnulacionBreve(
                    anulacion.fecha().toString(),
                    anulacion.motivoDeLaAnulacion(),
                    anulacion.usuarioRegistro());
        }
    }
}
