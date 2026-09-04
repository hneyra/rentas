package kamayuk.rentas.catastro.infraestructura.web;

import java.util.List;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.Reporte;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.UnidadAfecta;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * El contenido de la ficha del contribuyente, tal como sale por HTTP.
 *
 * <p>{@code aLaFecha} va en primera linea a proposito: es un documento que se imprime y se archiva,
 * y sin la fecha nadie puede decir si el que tiene en la mano describe la situacion de la que se
 * esta discutiendo (regla 9).
 *
 * <p>Ni un importe. El autovaluo de cada unidad es una regla de calculo bloqueada por D-02a; lo que
 * sale es superficie, uso y porcentaje.
 *
 * <p><b>Las areas viajan tipadas</b> (#607). Las escribe el serializador que {@code
 * ConfiguracionDeJson} registra para {@code AreaM2}, o sea la cifra sola —{@code "360.00"}—, y la
 * unidad la pone la cabecera de la columna. Metida dentro del dato obliga a cada consumidor a
 * recortarla antes de comparar, y era la diferencia por la que el mismo predio decia «360.00 m2»
 * aqui y «360.00» en fiscalizacion.
 */
public record ReporteResource(
        String aLaFecha,
        String codigo,
        String nombre,
        String documento,
        @Nullable String domicilioFiscal,
        List<UnidadResource> unidades) {

    public static ReporteResource de(Reporte reporte) {
        return new ReporteResource(
                reporte.aLaFecha().toString(),
                reporte.contribuyente().codigo(),
                reporte.contribuyente().nombre(),
                reporte.contribuyente().documento(),
                reporte.domicilioFiscal(),
                reporte.unidades().stream().map(UnidadResource::de).toList());
    }

    /**
     * Una unidad afecta.
     *
     * <p>{@code areaTerreno}, {@code uso} y {@code version} nulos significan «predio registrado y
     * todavia sin ficha». Salen nulos y no en cero: un cero se leeria como un terreno de cero
     * metros, que es una cifra, y esto es la ausencia de una.
     */
    public record UnidadResource(
            String codRefCatastral,
            String direccion,
            String condicion,
            String porcentaje,
            @Nullable AreaM2 areaTerreno,
            @Nullable String uso,
            @Nullable Integer version) {

        public static UnidadResource de(UnidadAfecta unidad) {
            return new UnidadResource(
                    unidad.codigo(),
                    unidad.direccion(),
                    unidad.condicion(),
                    unidad.porcentaje().toString(),
                    unidad.area(),
                    unidad.uso(),
                    unidad.version());
        }
    }
}
