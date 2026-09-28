package kamayuk.rentas.valores.infraestructura.web;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.valores.aplicacion.ImprimirCorridaMasiva;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La tercera etapa de la generacion masiva por HTTP: {@code GET
 * /api/v1/valores/masivo/{id}/impresion?formato=PDF|XLS|RTF} (RF-091, #631).
 *
 * <p>Hasta #631 {@link ImprimirCorridaMasiva} estaba escrita y probada y no la llamaba nadie: #400
 * cerro el <i>procesamiento</i> de la corrida, no su impresion, y una corrida generaba sus valores
 * sin manera de sacar el lote en papel desde la aplicacion.
 *
 * <h2>Un archivo con un documento por valor, y no un documento con todos dentro</h2>
 *
 * <p>La respuesta es un {@code .zip} con una entrada por valor {@code GENERADO}, en el formato
 * pedido y nombrada con su titulo —que lleva el numero del valor—. Es la forma que {@link
 * ImprimirCorridaMasiva} ya tenia escrita desde #38 («un archivo por valor nombrado con su
 * numero»), y no por comodidad:
 *
 * <ul>
 *   <li>cada valor es <b>su propio acto</b>, con su numero y su destinatario, y se notifica a un
 *       contribuyente distinto; un PDF con los cuatro mil dentro habria que partirlo para
 *       diligenciarlo;
 *   <li>los tres formatos de RF-132 no se pueden concatenar igual: dos hojas SpreadsheetML seguidas
 *       no son un libro, y dos RTF seguidos no son un RTF. Coser documentos exigiria un cuarto
 *       dialecto en cada renderizador, que es lo que {@code GeneradorDeDocumentos} existe para no
 *       tener;
 *   <li>y cada documento sale por {@code EmitirDocumento#emitirEnLote}, el mismo camino que ya
 *       dibuja el valor individual: el mismo pie, la misma marca de demostracion, los mismos bytes.
 * </ul>
 *
 * <h2>El archivo se arma en memoria, y es a proposito</h2>
 *
 * <p>Los modelos se siguen construyendo <b>de uno en uno</b> —el iterador de {@link
 * ImprimirCorridaMasiva} arma cada uno en su {@code next()}—, y lo que se acumula son los bytes ya
 * comprimidos, no los agregados. Transmitirlo mientras se genera —{@code StreamingResponseBody}— lo
 * sacaria del hilo de la peticion, y en el otro hilo no hay contexto de municipalidad: cada {@code
 * ConstruirModeloDeValor#de} abriria su transaccion sin {@code SET LOCAL} y la politica RLS de
 * {@code valor} la tumbaria a medio archivo, con las cabeceras ya enviadas y sin poder decir por
 * que. Moverle el contexto a ese hilo es justo lo que {@code
 * SOLO_EL_RECORRIDO_MUEVE_EL_TENANT_EN_WEB} prohibe. Asi, ademas, un fallo en el valor doscientos
 * sale como el 500 de siempre y no como un zip truncado que parece entero.
 *
 * <h2>{@code IMPRESION}, y no {@code LECTURA}</h2>
 *
 * <p>Por lo que {@code ConstanciaController} explica de los padrones de #53: esto saca del sistema,
 * de una vez, miles de documentos que en pantalla nadie llego a ver uno a uno. No escribe nada
 * —imprimir una corrida no consume correlativo ni mueve fase—, asi que es un {@code GET} y no pide
 * observacion (regla 10).
 */
@RestController
@RequestMapping(Api.RAIZ + "/valores/masivo")
public class ImpresionDeLaCorridaMasivaController {

    /**
     * La opcion del catalogo de la generacion masiva (RF-091): la impresion es su tercera etapa.
     */
    static final String ACCESO = "valores_masivo";

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ImprimirCorridaMasiva imprimir;

    public ImpresionDeLaCorridaMasivaController(ImprimirCorridaMasiva imprimir) {
        this.imprimir = imprimir;
    }

    /**
     * Los valores {@code GENERADO} de la corrida, uno por entrada del archivo.
     *
     * @param id el identificador de la corrida, el que devolvio {@code POST /valores/masivo}
     * @param formato {@code PDF}, {@code XLS} o {@code RTF}: el de todos los documentos del archivo
     */
    @GetMapping("/{id}/impresion")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> imprimir(@PathVariable long id, @RequestParam String formato) {
        FormatoDeDocumento elegido = formatoDe(formato);

        ByteArrayOutputStream archivo = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archivo)) {
            imprimir.imprimir(id, elegido, modelo -> entradaDe(zip, modelo, elegido));
        } catch (ImprimirCorridaMasiva.CorridaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (ImprimirCorridaMasiva.SinValoresGenerados sinPapel) {
            // 409 y no 404: la corrida existe; lo que no admite imprimirla es su estado —la
            // ventana de lote no la proceso todavia, o ninguno de sus candidatos tenia deuda—.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(sinPapel));
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }

        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("corrida-masiva-" + id + ".zip")
                                .build()
                                .toString())
                .body(archivo.toByteArray());
    }

    // ------------------------------------------------------------------

    /**
     * Abre la entrada de este valor y devuelve un flujo que escribe en ella.
     *
     * <p>El flujo <b>no cierra el archivo</b> aunque alguien lo cierre: cerrar la entrada de un
     * documento cerraria el zip entero, y el siguiente valor ya no tendria donde escribirse. La
     * entrada la cierra la siguiente {@code putNextEntry}, y la ultima el {@code close} del zip.
     */
    private static OutputStream entradaDe(
            ZipOutputStream zip, ModeloDeDocumento modelo, FormatoDeDocumento formato) {
        try {
            zip.putNextEntry(new ZipEntry(formato.nombreDeArchivo(nombreDe(modelo))));
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
        return new FilterOutputStream(zip) {
            @Override
            public void write(byte[] bytes, int desde, int cuantos) throws IOException {
                out.write(bytes, desde, cuantos);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    /**
     * El titulo del documento, con lo que no sea letra, cifra o guion convertido en un guion.
     *
     * <p>El titulo lleva el tipo y el numero —«ORDEN DE PAGO N.° OP-2026-000001»— y el numero es
     * unico por tipo y ejercicio, asi que dos entradas no pueden llamarse igual. Se limpia porque
     * un nombre de entrada con espacios y un «°» se descomprime distinto segun el sistema.
     */
    static String nombreDe(ModeloDeDocumento modelo) {
        String limpio = modelo.titulo().replaceAll("[^A-Za-z0-9-]+", "-");
        return limpio.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private static FormatoDeDocumento formatoDe(String formato) {
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La corrida no se pudo imprimir" : mensaje;
    }
}
