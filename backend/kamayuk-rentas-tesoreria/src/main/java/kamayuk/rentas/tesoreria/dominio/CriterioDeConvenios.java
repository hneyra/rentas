package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Los filtros de la consulta de convenios (RF-084, pantalla {@code consulta_convenios}).
 *
 * <p>Todos opcionales y todos por igualdad o por rango: aqui no hay aproximacion —esa es cosa del
 * padron de {@code contribuyentes}— ni busqueda por prefijo. Si algun dia la hubiera, se escribe
 * como rango con {@code ~>=~} / {@code ~<~} y no con {@code LIKE}: bajo RLS un {@code LIKE
 * 'prefijo%'} no llega nunca al indice (DAT-01 §0, hallazgo 3).
 *
 * <p>El {@code estado} no es una columna —se deriva de los movimientos (V31)—, asi que filtrar por
 * el se resuelve en SQL contra {@code convenio_movimiento} y no contra {@code convenio}. Es el
 * precio de no tener una columna que mienta, y se paga una vez, aqui.
 *
 * @param numero el numero impreso del convenio
 * @param codigoContribuyente el codigo del titular, tal como lo teclea la pantalla
 * @param estado en que situacion esta; {@code null} es «todos»
 * @param desde el primer dia del rango de {@code convenio.fecha}, inclusive
 * @param hasta el ultimo dia del rango, inclusive
 * @param aLaFecha la fecha con la que se responde lo que depende de hoy —cuantas cuotas han vencido
 *     y cuanto queda por cobrar—. Entra como argumento y no sale de un {@code now()} de la base
 *     (regla 6, regla 9): dos filas de la misma pagina tienen que estar calculadas al mismo dia, y
 *     un reporte de ayer tiene que poder repetirse
 */
public record CriterioDeConvenios(
        @Nullable String numero,
        @Nullable String codigoContribuyente,
        @Nullable EstadoDeConvenio estado,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        LocalDate aLaFecha) {

    public CriterioDeConvenios {
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        numero = normalizar(numero);
        codigoContribuyente = normalizar(codigoContribuyente);
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new IllegalArgumentException(
                    "El rango de fechas esta al reves: desde " + desde + " hasta " + hasta);
        }
    }

    /** Sin ningun filtro: todos los convenios de la municipalidad, a esa fecha. */
    public static CriterioDeConvenios todos(LocalDate aLaFecha) {
        return new CriterioDeConvenios(null, null, null, null, null, aLaFecha);
    }

    private static @Nullable String normalizar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip().toUpperCase(Locale.ROOT);
        return limpio.isEmpty() ? null : limpio;
    }
}
