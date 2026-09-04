package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.tesoreria.pagos.EmitirOrdenDeCobro;
import org.jspecify.annotations.Nullable;

/**
 * Las ordenes emitidas, tal como la ventanilla las necesita para cobrar (P5D).
 *
 * <p>Los importes van como <b>cadena</b> (RNF-055): un numero de coma flotante puede volver con
 * otro valor, y esto es el camino del dinero.
 *
 * <p><b>Toda cifra indica su fecha</b> (regla 9): {@code actualizadoA} no acompaña al importe, lo
 * decide — el mismo predial a dos fechas distintas son dos cifras distintas.
 *
 * @param aLaFecha la fecha con la que se leyo el libro
 * @param total la suma de las ordenes emitidas; no es una quinta cifra, es la suma
 * @param ordenes una por obligacion con deuda
 * @param sinDeuda las que se marcaron y el libro no debe a esa fecha; <b>se dicen, no se
 *     callan</b>, porque una fila marcada que desaparece del total se lee como un error de la
 *     pantalla
 */
public record EmisionResource(
        String aLaFecha,
        String total,
        List<OrdenResource> ordenes,
        List<MarcadaSinDeuda> sinDeuda) {

    public static EmisionResource de(EmitirOrdenDeCobro.Emision emision) {
        return new EmisionResource(
                emision.aLaFecha().toString(),
                emision.total().valor().toPlainString(),
                emision.emitidas().stream().map(OrdenResource::de).toList(),
                emision.sinDeuda().stream().map(MarcadaSinDeuda::de).toList());
    }

    /**
     * @param ordenId como la llama la caja; es lo que se manda a {@code POST /caja/api/v1/cobros}
     * @param referenciaExterna como la llama este sistema; opaca para la caja
     * @param nueva falso si la orden ya estaba: el mismo dia, la misma obligacion es la misma orden
     */
    public record OrdenResource(
            long ordenId,
            String referenciaExterna,
            String tributo,
            int ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String importe,
            String actualizadoA,
            boolean nueva) {

        public static OrdenResource de(EmitirOrdenDeCobro.Emitida emitida) {
            return new OrdenResource(
                    emitida.ordenId(),
                    emitida.referencia().texto(),
                    emitida.referencia().tributo(),
                    emitida.referencia().ejercicio().valor(),
                    emitida.referencia().predioId(),
                    emitida.referencia().vehiculoId(),
                    emitida.importe().valor().toPlainString(),
                    emitida.actualizadoA().toString(),
                    emitida.nueva());
        }
    }

    /** Una fila marcada que no llego a ser orden. Sin importe: no hay ninguno que decir. */
    public record MarcadaSinDeuda(
            String tributo, int ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

        public static MarcadaSinDeuda de(SeleccionDeObligacion marcada) {
            return new MarcadaSinDeuda(
                    marcada.tributo(),
                    marcada.ejercicio().valor(),
                    marcada.predioId(),
                    marcada.vehiculoId());
        }
    }
}
