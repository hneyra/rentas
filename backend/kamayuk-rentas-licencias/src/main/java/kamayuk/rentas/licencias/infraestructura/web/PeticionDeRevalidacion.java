package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla manda para revalidar una licencia de edificacion (#48 AC 4).
 *
 * <p>La ruta identifica el expediente <b>de la revalidacion</b>, no la licencia original: la
 * revalidacion es su propio tramite, con su propio recibo y su propia resolucion, y la licencia que
 * prorroga la nombra el expediente al presentarse.
 *
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeRevalidacion(
        @Nullable String fecha,
        @Nullable String nuevaVigenciaHasta,
        @Nullable String nDeRecibo,
        @Nullable String formato,
        @Nullable String observacion) {}
