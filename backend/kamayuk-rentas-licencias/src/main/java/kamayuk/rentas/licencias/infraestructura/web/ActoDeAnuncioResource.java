package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.licencias.aplicacion.CesarAnuncio;
import kamayuk.rentas.licencias.aplicacion.RegistrarAnuncio;
import kamayuk.rentas.licencias.aplicacion.RenovarAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve un acto sobre una autorizacion de anuncio: el registro, la renovacion, el cese y
 * el retiro (#51, RF-114).
 *
 * <h2>La referencia del cargo sale en la respuesta, y no es un detalle interno</h2>
 *
 * <p>{@link #referenciaDelCargo} es la cadena con la que la tasa entro en el libro. Que viaje
 * permite dos cosas que ninguna otra hace: comprobar <b>desde fuera</b> que un reintento no genero
 * un segundo cargo —los dos responden la misma referencia— e ir del anuncio al asiento sin
 * adivinar. Es el primer criterio de aceptacion de #51 leyendose en el JSON.
 *
 * <h2>{@link #yaExistia} distingue el alta del reenvio</h2>
 *
 * <p>El borde responde {@code 201} cuando la autorizacion nace y {@code 200} cuando la peticion era
 * un reintento ya atendido. Son cosas distintas, y quien reintenta merece saber cual le paso en vez
 * de tener que deducirlo.
 *
 * @param nroAutorizacion el numero de la autorizacion sobre la que se actuo
 * @param acto que paso: {@code AUTORIZACION}, {@code RENOVACION}, {@code CESE} o {@code RETIRO}
 * @param fecha el dia del acto
 * @param ejercicio el ejercicio al que se imputo la tasa; nulo si el acto no devenga
 * @param referenciaDelCargo con que referencia entro el cargo en el libro; nula si no devenga
 * @param tasa el importe asentado, con su fecha; nulo si el acto no devenga
 * @param fecVenc hasta cuando queda vigente tras el acto
 * @param yaExistia si esta respuesta es el reenvio de una peticion ya atendida
 */
public record ActoDeAnuncioResource(
        String nroAutorizacion,
        String acto,
        LocalDate fecha,
        @Nullable Integer ejercicio,
        @Nullable String referenciaDelCargo,
        @Nullable ImporteActualizado tasa,
        @Nullable LocalDate fecVenc,
        boolean yaExistia) {

    /** El registro de una autorizacion nueva, o el reenvio de uno ya atendido. */
    public static ActoDeAnuncioResource de(RegistrarAnuncio.Registro registro) {
        return componer(registro.anuncio().numero(), registro.autorizacion(), registro.yaExistia());
    }

    /** La renovacion. */
    public static ActoDeAnuncioResource de(RenovarAnuncio.Renovacion renovacion) {
        return componer(renovacion.anuncio().numero(), renovacion.movimiento(), false);
    }

    /** El cese o el retiro. */
    public static ActoDeAnuncioResource de(CesarAnuncio.Acto acto) {
        return componer(acto.anuncio().numero(), acto.movimiento(), false);
    }

    private static ActoDeAnuncioResource componer(
            String numero, MovimientoDeAnuncio movimiento, boolean yaExistia) {
        Dinero tasa = movimiento.tasa();
        var ejercicio = movimiento.ejercicio();
        return new ActoDeAnuncioResource(
                numero,
                movimiento.tipo().name(),
                movimiento.fecha(),
                ejercicio == null ? null : ejercicio.valor(),
                movimiento.referenciaCargo(),
                tasa == null ? null : new ImporteActualizado(tasa, movimiento.fecha()),
                movimiento.vigenciaHasta(),
                yaExistia);
    }
}
