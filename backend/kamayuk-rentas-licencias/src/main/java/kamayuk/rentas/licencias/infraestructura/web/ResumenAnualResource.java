package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias;
import kamayuk.rentas.licencias.dominio.FilaDelResumenAnual;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El resumen de licencias por año, tal como sale de {@code licencia_resumen_anual} (#54, RF-115).
 *
 * <p>Las claves de cada fila son las que declara la tabla de la pantalla: {@code ano}, {@code
 * emitidas}, {@code canceladas}, {@code duplicados}, {@code vigentesAlCierre}, {@code
 * derechoDeTramiteS}.
 *
 * <p><b>El derecho de tramite va con su fecha o no va.</b> Es la unica cifra de la hoja, y cuando
 * el conjunto sellado de ese año no permite resolver el concepto del TUPA el campo llega nulo con
 * su motivo. El frontend imprime «—»; un cero se leeria como un año en el que no se cobro nada, y
 * esta hoja se usa para conciliar lo que la caja recaudo. Es exactamente el mismo reparto que
 * {@code ReporteDeEdificacionResource} hace con el valor de obra (#48).
 *
 * @param aLaFecha el dia de corte del reporte
 * @param filas un año por fila, del mas antiguo al mas reciente
 */
public record ResumenAnualResource(LocalDate aLaFecha, List<FilaResource> filas) {

    public static ResumenAnualResource de(ResumenAnualDeLicencias.Resumen resumen) {
        return new ResumenAnualResource(
                resumen.aLaFecha(), resumen.filas().stream().map(FilaResource::de).toList());
    }

    /**
     * Un año.
     *
     * @param alCierre el dia al que se derivo «vigentes al cierre»: el 31 de diciembre en un año
     *     cerrado, la fecha de corte en el año en curso
     * @param derechoDeTramiteS lo recaudado con su fecha; nulo cuando no se pudo resolver
     * @param derechoNoDisponible por que no se pudo, nombrando la llave que falta
     */
    public record FilaResource(
            int ano,
            long emitidas,
            long canceladas,
            long duplicados,
            long vigentesAlCierre,
            @Nullable ImporteActualizado derechoDeTramiteS,
            @Nullable String derechoNoDisponible,
            LocalDate alCierre) {

        static FilaResource de(FilaDelResumenAnual fila) {
            return new FilaResource(
                    fila.ejercicio().valor(),
                    fila.emitidas(),
                    fila.canceladas(),
                    fila.duplicados(),
                    fila.vigentesAlCierre(),
                    fila.derechoDeTramite() == null
                            ? null
                            : new ImporteActualizado(fila.derechoDeTramite(), fila.alCierre()),
                    fila.derechoNoDisponible(),
                    fila.alCierre());
        }
    }
}
