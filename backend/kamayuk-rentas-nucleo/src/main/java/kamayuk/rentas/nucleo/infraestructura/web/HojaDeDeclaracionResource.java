package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeLaHojaDeDeclaracion;
import org.jspecify.annotations.Nullable;

/**
 * La hoja resumen de una declaracion jurada, tal como sale por HTTP (#563). Campos en español
 * {@code camelCase} (ARQ-04 §3).
 *
 * <p><b>Los importes viajan como texto</b>, igual que en {@code DeterminacionPredialResource}: son
 * las cifras fijas con que se determino, no un saldo que cambie con el tiempo, y la fecha a la que
 * se leyeron es <b>una sola</b> para toda la hoja — {@code aLaFecha} (regla 9).
 *
 * <p><b>Un campo nulo es un campo que no hay, y por eso {@code faltan} viene lleno.</b> Sin
 * determinacion del ejercicio no hay autovaluo, ni valuo afecto, ni impuesto: publicar cero seria
 * escribir «no debe nada» en un papel que alguien firma. Y ni siquiera con determinacion viajan el
 * derecho de emision y el total a pagar, porque el derecho es una cifra de ordenanza local que
 * todavia no se carga (D-02b).
 *
 * <p>{@code faltan} es una lista de motivos y no un booleano: «no se puede imprimir» sin decir por
 * que es lo que hace que alguien lo imprima igual desde otro sitio.
 */
public record HojaDeDeclaracionResource(
        DeclaracionJuradaResource declaracion,
        LocalDate aLaFecha,
        @Nullable DeclaranteResource declarante,
        List<PredioDeLaHojaResource> predios,
        @Nullable String valuoAfectoTotal,
        @Nullable String impuestoInsoluto,
        List<String> faltan) {

    public static HojaDeDeclaracionResource de(ConsultaDeLaHojaDeDeclaracion.Hoja hoja) {
        return new HojaDeDeclaracionResource(
                DeclaracionJuradaResource.de(hoja.declaracion()),
                hoja.aLaFecha(),
                hoja.declarante() == null
                        ? null
                        : new DeclaranteResource(
                                hoja.declarante().codigo(),
                                hoja.declarante().nombre(),
                                hoja.declarante().documento(),
                                hoja.domicilioFiscal()),
                hoja.predios().stream().map(PredioDeLaHojaResource::de).toList(),
                hoja.valuoAfectoTotal() == null ? null : hoja.valuoAfectoTotal().toString(),
                hoja.impuestoInsoluto() == null ? null : hoja.impuestoInsoluto().toString(),
                hoja.faltan());
    }

    /**
     * Quien declara.
     *
     * <p>{@code documento} viene ya formateado —«DNI 03593174»—, como lo publica el padron: la hoja
     * lo imprime tal cual y componerlo aqui seria una segunda forma de escribir el mismo dato.
     */
    public record DeclaranteResource(
            String codigo, String nombre, String documento, @Nullable String domicilioFiscal) {}

    /** Una linea de la tabla de predios de la hoja. */
    public record PredioDeLaHojaResource(
            long predioId,
            String codRefCatastral,
            String direccion,
            String tipo,
            String porcentajePropiedad,
            @Nullable String autovaluo,
            @Nullable String valuoExonerado,
            @Nullable String valuoAfecto) {

        static PredioDeLaHojaResource de(ConsultaDeLaHojaDeDeclaracion.FilaDePredio fila) {
            return new PredioDeLaHojaResource(
                    fila.predioId(),
                    fila.codigoReferenciaCatastral(),
                    fila.direccion(),
                    fila.tipo(),
                    fila.porcentajePropiedad().toString(),
                    fila.autovaluo() == null ? null : fila.autovaluo().toString(),
                    fila.valuoExonerado() == null ? null : fila.valuoExonerado().toString(),
                    fila.valuoAfecto() == null ? null : fila.valuoAfecto().toString());
        }
    }
}
