package kamayuk.rentas.tesoreria.infraestructura.web;

import kamayuk.rentas.tesoreria.aplicacion.ArqueoDeTurno;
import kamayuk.rentas.tesoreria.aplicacion.CerrarTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El acta de un cierre de turno —o de su reversion— tal como sale por HTTP (#36, RF-087).
 *
 * <p>{@code estadoDelTurno} sale <b>derivado</b> del tipo del movimiento y no de ninguna columna:
 * un cierre deja el turno CERRADO y una reversion lo deja ABIERTO. Es la misma forma en que un
 * recibo responde si esta anulado desde #34.
 *
 * <p>{@code cuadraConElLibro} solo aparece en un cierre, y no es decorativo: el cierre <b>no se
 * firma</b> si lo recaudado en deuda tributaria no coincide con lo que el libro asento. Que salga
 * en la respuesta es lo que deja constancia de contra que se comprobo, y {@code
 * recaudadoSinAsiento} dice cuanto quedo deliberadamente fuera de esa comparacion —tasas y cuotas
 * iniciales, que no tocan el libro—.
 *
 * @param id el identificador del acta
 * @param turnoId el turno
 * @param caja el codigo de la ventanilla
 * @param cajero de quien es el turno
 * @param tipo CIERRE o REVERSION
 * @param secuencia el orden dentro del turno
 * @param fecha el dia del acto, en ISO
 * @param registradoEn el instante exacto, en ISO
 * @param estadoDelTurno como queda el turno tras este acto
 * @param arqueo el arqueo congelado; nulo en una reversion
 * @param reversaCierreId el acta que se deja sin efecto; nulo en un cierre
 * @param motivo por que se reversa; nulo en un cierre
 * @param recaudadoConAsiento lo que el libro confirmo; nulo en una reversion
 * @param recaudadoSinAsiento lo que se cobro sin tocar el libro; nulo en una reversion
 * @param observacion por que se hizo (regla 10)
 */
public record CierreResource(
        long id,
        long turnoId,
        String caja,
        String cajero,
        String tipo,
        int secuencia,
        String fecha,
        String registradoEn,
        String estadoDelTurno,
        @Nullable ArqueoResource arqueo,
        @Nullable Long reversaCierreId,
        @Nullable String motivo,
        @Nullable ImporteActualizado recaudadoConAsiento,
        @Nullable ImporteActualizado recaudadoSinAsiento,
        String observacion) {

    /** El acta de un cierre, con su arqueo y contra que cuadro. */
    public static CierreResource de(CerrarTurno.Cerrado cerrado) {
        CierreDeTurno acta = cerrado.cierre();
        ArqueoDeTurno.Cuadre cuadre = cerrado.cuadre();
        return new CierreResource(
                acta.idGuardado(),
                acta.turnoId(),
                cerrado.caja().codigo(),
                cerrado.turno().cajero(),
                acta.tipo().name(),
                acta.secuencia(),
                acta.fecha().toString(),
                acta.registradoEn().toString(),
                "CERRADO",
                ArqueoResource.de(acta.arqueoCongelado()),
                null,
                null,
                new ImporteActualizado(cuadre.conAsientos(), cuadre.aLaFecha()),
                new ImporteActualizado(cuadre.sinAsientos(), cuadre.aLaFecha()),
                acta.observacion().texto());
    }

    /** El acta de una reversion. El cierre que deja sin efecto sigue donde estaba. */
    public static CierreResource de(CerrarTurno.Reversado reversado, String cajero) {
        CierreDeTurno acta = reversado.reversion();
        return new CierreResource(
                acta.idGuardado(),
                acta.turnoId(),
                reversado.caja().codigo(),
                cajero,
                acta.tipo().name(),
                acta.secuencia(),
                acta.fecha().toString(),
                acta.registradoEn().toString(),
                "ABIERTO",
                null,
                reversado.reversado().idGuardado(),
                acta.motivoDeLaReversion(),
                null,
                null,
                acta.observacion().texto());
    }
}
