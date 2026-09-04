package kamayuk.rentas.sanciones.infraestructura.web;

import java.util.Locale;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Lo que los once reportes de #53 comparten para salir en los tres formatos de RF-132.
 *
 * <h2>Se reutiliza la infraestructura de documentos, no se escribe otro exportador</h2>
 *
 * <p>El PDF, la hoja de cálculo y el texto enriquecido los dibujan los tres renderizadores de
 * {@code kamayuk.rentas.documentos} a partir del mismo {@link ModeloDeDocumento}. Un exportador
 * propio de sanciones sería un cuarto dialecto: el día que alguien añadiera una columna, la hoja de
 * cálculo de tránsito la tendría y la de catastro no, y nadie sabría cuál de las dos está bien. Es
 * además lo que hace que el RTF escape lo no-ASCII —«PEÑA GARCÍA» y no «PE?A GARC?A»—, que se
 * verificó al escribirlo y no se vuelve a resolver aquí.
 *
 * <h2>Un parámetro, no una ruta nueva</h2>
 *
 * <p>{@code ?formato=PDF|XLS|RTF} sobre la misma ruta, como en {@code catastro.ReporteController} y
 * en {@code tesoreria.ReciboController}. El contrato no tiene rutas para los formatos, y publicar
 * rutas que ninguna pantalla llama las deja sin dueño.
 */
final class ReportesDeSanciones {

    private ReportesDeSanciones() {}

    /** Si la petición pidió el documento en vez del JSON. */
    static boolean pideDocumento(@Nullable String formato) {
        return formato != null && !formato.isBlank();
    }

    static FormatoDeDocumento formatoDe(String formato) {
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    /** Dibuja el modelo y lo devuelve como descarga. */
    static ResponseEntity<byte[]> documento(
            GeneradorDeDocumentos generador,
            ModeloDeDocumento modelo,
            String formato,
            String nombreBase) {

        FormatoDeDocumento elegido = formatoDe(formato);
        byte[] archivo = generador.generar(modelo, elegido);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(elegido.tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(elegido.nombreDeArchivo(nombreBase))
                                .build()
                                .toString())
                .body(archivo);
    }
}
