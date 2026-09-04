package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import org.jspecify.annotations.Nullable;

/**
 * Una licencia tal como sale por HTTP (#44, RF-110).
 *
 * <p>Los nombres de los campos son los de la pantalla {@code licencia_funcionamiento}: {@code
 * nroLicencia}, {@code denominacionComercial}, {@code nExpediente}. No son los de la tabla, ni los
 * del dominio: el contrato lo fija el prototipo, y traducir aqui es mas barato que traducir en cada
 * pantalla.
 *
 * <p><b>{@code estadoALaFecha} viaja siempre</b>, y no es un adorno: el estado de una licencia
 * temporal depende del dia, asi que una respuesta que dijera «VENCIDA» sin decir a que fecha seria
 * una respuesta que manana significa otra cosa (regla 9, RNF-075).
 *
 * <p><b>Ningun importe.</b> Una licencia no lleva cifras: el derecho de tramite se pago antes y su
 * importe esta en el recibo. Lo que viaja es el numero del recibo, que es lo que permite ir a
 * buscarlo.
 *
 * <p><b>El area viaja tipada</b> (#607). Se escribia a mano con {@code valor().toPlainString()}:
 * daba la cifra buena, pero era una segunda convencion para lo mismo, y de tener dos salio que
 * catastro compusiera con {@code toString()} y publicara «360.00 m2» del mismo predio que aqui sale
 * «360.00». Ahora la escribe el serializador que {@code ConfiguracionDeJson} registra para {@code
 * AreaM2}, que es un solo sitio; la unidad la sigue poniendo el nombre del campo, no el dato.
 */
public record LicenciaResource(
        String nroLicencia,
        String est,
        String estado,
        LocalDate estadoALaFecha,
        String contribuyente,
        String codContribuyente,
        String denominacionComercial,
        String direccion,
        String tipoDeLicencia,
        AreaM2 areaDelEstablecimiento,
        @Nullable String zonificacion,
        @Nullable Integer aforo,
        LocalDate fechaDeEmision,
        @Nullable LocalDate fechaDeVencimiento,
        @Nullable String nExpediente,
        @Nullable LocalDate fechaDeExpediente,
        @Nullable Long fichaEconomica,
        List<GiroResource> giros,
        List<MovimientoResource> historial,
        List<DuplicadoResource> duplicados) {

    /** La fila de la grilla y la ficha, con lo que cada una traiga. */
    public static LicenciaResource de(ConsultaDeLicencias.LicenciaEnConsulta fila) {
        LicenciaDeFuncionamiento licencia = fila.licencia();
        return new LicenciaResource(
                licencia.numero(),
                fila.estado().inicial(),
                fila.estado().name(),
                fila.aLaFecha(),
                fila.nombreDelTitular(),
                fila.codigoDelTitular(),
                licencia.nombreComercial(),
                licencia.direccion(),
                licencia.tipoLicencia().name(),
                licencia.areaSolicitada(),
                licencia.zonificacion(),
                licencia.aforo(),
                licencia.fechaEmision(),
                licencia.vigenciaHasta(),
                licencia.expediente(),
                licencia.fechaExpediente(),
                licencia.fichaId(),
                licencia.giros().stream().map(GiroResource::de).toList(),
                fila.historial().stream().map(MovimientoResource::de).toList(),
                fila.duplicados().stream().map(DuplicadoResource::de).toList());
    }

    /** Un giro autorizado. */
    public record GiroResource(
            String codigo, @Nullable String descripcion, boolean principal, boolean activo) {

        static GiroResource de(GiroDeLaLicencia giro) {
            return new GiroResource(
                    giro.codigo() == null ? "" : giro.codigo(),
                    giro.descripcion(),
                    giro.principal(),
                    giro.activo());
        }
    }

    /** Un movimiento del historial. */
    public record MovimientoResource(
            String tipo,
            LocalDate fecha,
            @Nullable String motivo,
            String resolucion,
            String observacion) {

        static MovimientoResource de(MovimientoDeLicencia movimiento) {
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.fecha(),
                    movimiento.motivo(),
                    movimiento.documentoNumero(),
                    movimiento.observacion().texto());
        }
    }

    /** Un duplicado autorizado. */
    public record DuplicadoResource(int numero, LocalDate fecha, String motivo, int reimpresion) {

        static DuplicadoResource de(DuplicadoDeLicencia duplicado) {
            return new DuplicadoResource(
                    duplicado.numero(),
                    duplicado.fecha(),
                    duplicado.motivo(),
                    duplicado.reimpresion());
        }
    }
}
