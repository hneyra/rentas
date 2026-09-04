package kamayuk.rentas.tesoreria.infraestructura.web;

import kamayuk.rentas.tesoreria.aplicacion.AnularRecibo;
import kamayuk.rentas.tesoreria.dominio.EstadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El acta de anulacion, tal como sale por HTTP (RF-083).
 *
 * <p>Lleva el recibo entero —intacto: su numero y su desglose siguen donde estaban— porque anular
 * no lo cambia, y quien anula necesita ver que anulo. Lo que cambia es que ahora hay un movimiento,
 * y de el sale el {@code estado}.
 *
 * <p>{@code importe} es lo que deja de estar cobrado, con su fecha (regla 9, RNF-075). Es tambien
 * lo que el arqueo del turno resta del cajon.
 *
 * @param numero el numero impreso del recibo anulado
 * @param estado siempre {@code ANULADO}; se publica porque el recibo ya no lo dice por si mismo
 * @param fecha el dia de la anulacion
 * @param motivo el sustento del acto
 * @param autorizadoPor quien lo autorizo, si consta
 * @param documentoAutorizacion el memorando o la resolucion, si consta
 * @param usuario quien la registro
 * @param importe lo que deja de estar cobrado, con su fecha
 * @param asientosReversados cuantas filas se escribieron en el libro; cero en caja de tasas
 * @param recibo el recibo, tal como quedo
 */
public record AnulacionResource(
        String numero,
        String estado,
        String fecha,
        String motivo,
        @Nullable String autorizadoPor,
        @Nullable String documentoAutorizacion,
        @Nullable String usuario,
        ImporteActualizado importe,
        int asientosReversados,
        ReciboResource recibo) {

    /**
     * El estado efectivo de un recibo con anulacion. Se deriva del movimiento, no de una columna.
     *
     * <p>Sale del enumerado y no de un literal (#548): el mismo vocabulario lo publica el listado
     * de recibos y lo acepta su filtro, y tres copias de dos palabras acaban discrepando en una.
     */
    public static final String ANULADO = EstadoDeRecibo.ANULADO.name();

    public static AnulacionResource de(AnularRecibo.Anulado anulado) {
        MovimientoDeRecibo anulacion = anulado.anulacion();
        return new AnulacionResource(
                anulado.recibo().numero().impreso(),
                ANULADO,
                anulacion.fecha().toString(),
                anulacion.motivoDeLaAnulacion(),
                anulacion.autorizadoPor(),
                anulacion.documentoAutorizacion(),
                anulacion.usuarioRegistro(),
                // La fecha del importe es la del recibo, no la de la anulacion: lo que se
                // devuelve es exactamente lo que se cobro, actualizado al dia en que se
                // cobro. Poner aqui la fecha de hoy sugeriria un recalculo que no hubo.
                new ImporteActualizado(
                        anulacion.importeReversado(), anulado.recibo().actualizadoA()),
                anulado.asientosReversados(),
                ReciboResource.de(anulado.recibo()));
    }
}
