package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.DuplicarLicencia;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve un acto sobre una licencia: la emision, la cancelacion y el duplicado (#44).
 *
 * <h2>Los bytes no viajan en el JSON</h2>
 *
 * <p>El contrato declara {@code application/json} para las tres opciones. Lo que la respuesta lleva
 * de cada papel es su numero, su formato, su resumen SHA-256 y su tamanio; la descarga es otra
 * peticion. Meter un PDF en base64 dentro de un JSON lo hincha un tercio.
 *
 * <h2>Dos papeles en el duplicado, y los dos se nombran</h2>
 *
 * <p>{@link #resolucion} es la resolucion que autoriza; {@link #licenciaReimpresa} es la licencia
 * vuelta a sacar, <b>con el numero de la original</b>. Que los dos numeros salgan en la respuesta
 * es lo que permite comprobar, desde fuera, que el duplicado conservo el numero: el criterio de
 * aceptacion de #44 se puede leer en el JSON.
 *
 * @param nroLicencia el numero de la licencia sobre la que se actuo
 * @param acto que paso: {@code EMISION}, {@code CANCELACION} o {@code DUPLICADO}
 * @param fecha el dia del acto
 * @param resolucion el papel principal del acto
 * @param licenciaReimpresa la licencia reimpresa; solo en el duplicado
 * @param numeroDeDuplicado el ordinal del duplicado; solo en el duplicado
 * @param estado en que queda la licencia
 */
public record ActoDeLicenciaResource(
        String nroLicencia,
        String acto,
        LocalDate fecha,
        DocumentoResource resolucion,
        @Nullable DocumentoResource licenciaReimpresa,
        @Nullable Integer numeroDeDuplicado,
        String estado) {

    /** La emision de una licencia nueva. */
    public static ActoDeLicenciaResource de(
            EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida) {
        return new ActoDeLicenciaResource(
                emitida.licencia().numero(),
                "EMISION",
                emitida.licencia().fechaEmision(),
                DocumentoResource.de(emitida.documento()),
                null,
                null,
                "VIGENTE");
    }

    /** La cancelacion. */
    public static ActoDeLicenciaResource de(CancelarLicencia.Cancelacion cancelacion) {
        return new ActoDeLicenciaResource(
                cancelacion.licencia().numero(),
                "CANCELACION",
                cancelacion.movimiento().fecha(),
                DocumentoResource.de(cancelacion.resolucion()),
                null,
                null,
                "CANCELADA");
    }

    /** El duplicado. */
    public static ActoDeLicenciaResource de(DuplicarLicencia.Duplicado duplicado) {
        return new ActoDeLicenciaResource(
                duplicado.licencia().numero(),
                "DUPLICADO",
                duplicado.duplicado().fecha(),
                DocumentoResource.de(duplicado.resolucion()),
                DocumentoResource.de(duplicado.reimpresion()),
                duplicado.duplicado().numero(),
                "VIGENTE");
    }

    /**
     * Un papel emitido, sin sus bytes.
     *
     * @param numero el numero del documento
     * @param formato en que formato salio
     * @param resumen el SHA-256 de los bytes entregados
     * @param bytes cuantos ocupa
     * @param reimpresiones cuantas veces se ha vuelto a sacar; 0 en el original
     */
    public record DocumentoResource(
            String numero, String formato, String resumen, int bytes, int reimpresiones) {

        /** Publico desde #54: el acto de certificado publica el mismo bloque de papel. */
        public static DocumentoResource de(EmitirDocumento.Emision emision) {
            return new DocumentoResource(
                    emision.registro().numero(),
                    emision.registro().formato().name(),
                    emision.registro().resumen(),
                    emision.contenido().length,
                    emision.registro().reimpresiones());
        }
    }
}
