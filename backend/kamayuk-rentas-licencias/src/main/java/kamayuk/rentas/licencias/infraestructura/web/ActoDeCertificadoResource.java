package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.licencias.aplicacion.EmitirCertificado;
import kamayuk.rentas.licencias.dominio.Certificado;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve emitir un certificado (#54, RF-115).
 *
 * <h2>Los bytes no viajan en el JSON</h2>
 *
 * <p>Mismo criterio que {@code ActoDeLicenciaResource} (#44): el contrato declara {@code
 * application/json}, y lo que la respuesta lleva del papel es su numero, su formato, su resumen
 * SHA-256 y su tamanio. La descarga es otra peticion —{@code POST
 * /licencias/certificados/{numero}/impresion}—, que ademas es la que RF-132 necesita para poder
 * pedir el mismo certificado en los tres formatos. Meter un PDF en base64 dentro de un JSON lo
 * hincha un tercio.
 *
 * <h2>{@link #documento} puede faltar, y significa algo concreto</h2>
 *
 * <p>La peticion fue un reintento con la misma clave de idempotencia y el certificado ya estaba
 * emitido. No se dibuja nada nuevo —hacerlo lo marcaria «DUPLICADO N.o 1» sin que nadie lo
 * pidiera—, y quien haya perdido el papel lo pide por la ruta de impresion, que si lo marca. {@link
 * #yaExistia} lo dice explicitamente, para que la pantalla no tenga que deducirlo de un nulo.
 *
 * @param nCertificado el numero del certificado
 * @param yaExistia si no se emitio ahora: el reintento idempotente devolvio el de la primera vez
 * @param documento el papel recien emitido; nulo en el reintento idempotente
 */
public record ActoDeCertificadoResource(
        String nCertificado,
        String tipo,
        String predio,
        String direccion,
        String solicitante,
        LocalDate fecha,
        LocalDate vigenciaHasta,
        ImporteActualizado derechoS,
        boolean yaExistia,
        ActoDeLicenciaResource.@Nullable DocumentoResource documento) {

    public static ActoDeCertificadoResource de(EmitirCertificado.Emision emision) {
        Certificado certificado = emision.certificado();
        EmitirDocumento.Emision papel = emision.documento();
        return new ActoDeCertificadoResource(
                certificado.numero(),
                certificado.tipo().name(),
                certificado.codigoPredial(),
                certificado.direccion(),
                emision.solicitante().nombre(),
                certificado.fechaEmision(),
                certificado.vigenciaHasta(),
                new ImporteActualizado(certificado.derecho(), certificado.derechoA()),
                emision.yaExistia(),
                papel == null ? null : ActoDeLicenciaResource.DocumentoResource.de(papel));
    }
}
