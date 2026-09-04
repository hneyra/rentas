package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeCertificados;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Un certificado tal como sale por HTTP (#54, RF-115).
 *
 * <p>Los nombres de los campos son los de la pantalla {@code certificados}: {@code nCertificado},
 * {@code tipo}, {@code predio}, {@code solicitante}, {@code fecha}, {@code derechoS}, {@code
 * estado}. No son los de la tabla ni los del dominio: el contrato lo fija el prototipo, y traducir
 * aqui es mas barato que traducir en cada pantalla.
 *
 * <p><b>{@code estadoALaFecha} viaja siempre</b>, y no es un adorno: un certificado caduca, asi que
 * una respuesta que dijera «CADUCADO» sin decir a que fecha seria una respuesta que ayer
 * significaba otra cosa (regla 9, RNF-075).
 *
 * <p><b>El derecho viaja con su fecha</b>, en un {@link ImporteActualizado}. Es la unica cifra del
 * recurso, y la fecha es la del cobro que la acredita: sin ella, «S/ 35,00» no se puede defender el
 * dia que el TUPA suba la tarifa.
 */
public record CertificadoResource(
        String nCertificado,
        String tipo,
        String tipoEtiqueta,
        String predio,
        String direccion,
        String solicitante,
        String codContribuyente,
        LocalDate fecha,
        LocalDate vigenciaHasta,
        ImporteActualizado derechoS,
        String estado,
        LocalDate estadoALaFecha,
        @Nullable String nExpediente,
        String documento,
        @Nullable String zonificacion,
        @Nullable String alturaMaximaPermitida,
        @Nullable String areaLibreMinima,
        @Nullable String retiroMunicipal,
        @Nullable String coeficienteDeEdificacion) {

    public static CertificadoResource de(ConsultaDeCertificados.CertificadoEnConsulta fila) {
        Certificado certificado = fila.certificado();
        ParametrosUrbanisticos parametros = certificado.parametros();
        return new CertificadoResource(
                certificado.numero(),
                certificado.tipo().name(),
                certificado.tipo().etiqueta(),
                certificado.codigoPredial(),
                certificado.direccion(),
                fila.nombreDelSolicitante(),
                fila.codigoDelSolicitante(),
                certificado.fechaEmision(),
                certificado.vigenciaHasta(),
                new ImporteActualizado(certificado.derecho(), certificado.derechoA()),
                fila.estado(),
                fila.aLaFecha(),
                certificado.expediente(),
                certificado.documentoNumero(),
                parametros.zonificacion(),
                parametros.alturaMaxima(),
                parametros.areaLibreMinima(),
                parametros.retiroMunicipal(),
                parametros.coeficienteEdificacion());
    }
}
