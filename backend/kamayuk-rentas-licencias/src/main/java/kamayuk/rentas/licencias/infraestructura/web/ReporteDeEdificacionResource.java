package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue;
import kamayuk.rentas.licencias.aplicacion.ValorizacionDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.ValorizacionDeObra;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del reporte general de licencias de edificacion (#48, RF-115).
 *
 * <p>Las claves son las que declara la tabla de la pantalla {@code edificacion_reporte}: {@code
 * nLicencia}, {@code expediente}, {@code administrado}, {@code areaAConstruirM}, {@code
 * valorDeObraS}.
 *
 * <p><b>El valor de obra va con su fecha o no va.</b> Es la unica cifra de la hoja, y cuando el
 * cuadro de valores unitarios sellado no permite calcularla el campo llega nulo con su motivo. El
 * frontend imprime «—»; un cero se leeria como una obra que no vale nada, y esa hoja se usa para
 * conciliar lo que se cobro por derechos de tramite.
 *
 * <p><b>El area viaja tipada</b> (#607). Se escribia a mano con {@code valor().toPlainString()}:
 * daba la cifra buena, pero era una segunda convencion para lo mismo, y de tener dos salio que
 * catastro compusiera con {@code toString()} y publicara «360.00 m2» del mismo predio que aqui sale
 * «360.00». Ahora la escribe el serializador que {@code ConfiguracionDeJson} registra para {@code
 * AreaM2}, que es un solo sitio; la unidad la sigue poniendo el nombre del campo, no el dato.
 */
public record ReporteDeEdificacionResource(
        @Nullable String nLicencia,
        String expediente,
        LocalDate fecha,
        String administrado,
        @Nullable String predio,
        String modalidad,
        @Nullable AreaM2 areaAConstruirM,
        @Nullable ImporteActualizado valorDeObraS,
        @Nullable String valorDeObraNoDisponible,
        String estado,
        LocalDate estadoALaFecha) {

    public static ReporteDeEdificacionResource de(ConsultaDeFue.FilaDelReporte fila) {
        ConsultaDeFue.FueEnConsulta consulta = fila.fila();
        ProyectoDelFue proyecto = fila.proyecto();
        ValorizacionDelFue.Resultado valorizacion = fila.valorizacion();
        ValorizacionDeObra.Valorizacion obra =
                valorizacion == null ? null : valorizacion.valorizacion();

        return new ReporteDeEdificacionResource(
                consulta.numeroDeLicencia(),
                consulta.fue().expediente(),
                consulta.fue().fechaDeclaracion(),
                consulta.nombreDelSolicitante(),
                consulta.terreno() == null ? null : consulta.terreno().direccion(),
                consulta.fue().modalidad().name(),
                proyecto == null ? null : proyecto.areaTechada(),
                obra == null ? null : new ImporteActualizado(obra.total(), consulta.aLaFecha()),
                valorizacion == null ? null : valorizacion.motivo(),
                consulta.estado().name(),
                consulta.aLaFecha());
    }
}
