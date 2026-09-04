package kamayuk.rentas.cuentacorriente.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ModeloDeLaConstanciaDeNoAdeudo;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code constancia}: {@code GET /api/v1/consultas/constancias/no-adeudo} (RF-049, RNF-084, #25).
 *
 * <p>Vista previa del documento que se entrega al contribuyente. {@link
 * ConsultarDeuda#constanciaDeNoAdeudo} entrega los datos y la decision de si se niega, mirando
 * todas las obligaciones del contribuyente en cualquier fase, sin que este contexto necesite
 * conocer a coactiva ni a un contexto de convenios (regla 2, ARQ-01 §4).
 *
 * <h2>Con {@code ?formato=PDF|XLS|RTF} devuelve el documento (RF-132, RNF-081)</h2>
 *
 * <p>Sin el parametro, el JSON de siempre: es lo que el contrato declara y lo que la pantalla
 * pinta. Con el, el mismo contenido dibujado por el generador comun ({@link
 * ModeloDeLaConstanciaDeNoAdeudo}). Se distinguen por el parametro y no por rutas nuevas, como en
 * {@code catastro.ReporteController} y en los once reportes de #53: el contrato no tiene rutas para
 * los formatos, y publicar rutas que ninguna pantalla llama las deja sin dueno.
 *
 * <p><b>La hoja la exportaba el frontend, y no podia.</b> El javadoc anterior daba la exportacion
 * por hecha «del renderizador comun de reportes del frontend»; ese renderizador dibuja la hoja A4 y
 * la manda a imprimir, y no produce {@code .xls} ni {@code .rtf}. El criterio de #72 se cierra aqui
 * porque es aqui donde estan los tres renderizadores que #55 escribio.
 *
 * <h2>Basta {@code LECTURA}, y no es un descuido</h2>
 *
 * <p>Los padrones de #53 exigen {@link Privilegio#IMPRESION} porque sacan del sistema un listado
 * que en pantalla nadie llego a ver entero. Aqui no: el documento es <b>la misma hoja</b> que la
 * pantalla ya dibuja con {@code lectura}, y que el navegador ya imprime con Ctrl+P. Pedir un
 * segundo privilegio para el PDF del servidor negaria el archivo a quien tiene el contenido delante
 * —y la interfaz no tiene con que apagar el boton (no hay {@code puedeImprimir}), asi que seria una
 * descarga prometida que responde 403 (#332)—.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/constancias/no-adeudo")
@RequiereAcceso(acceso = "constancia", privilegio = Privilegio.LECTURA)
public class ConstanciaController {

    private final ConsultarDeuda consulta;
    private final GeneradorDeDocumentos documentos;

    public ConstanciaController(ConsultarDeuda consulta, GeneradorDeDocumentos documentos) {
        this.consulta = consulta;
        this.documentos = documentos;
    }

    @GetMapping
    public ConstanciaResource constancia(
            @RequestParam String codContribuyente,
            @RequestParam(required = false) @Nullable String fecha) {
        return ConstanciaResource.de(buscar(codContribuyente, fecha));
    }

    /**
     * La misma constancia, como archivo descargable (RF-132, RNF-081).
     *
     * <p>No se registra como documento emitido. Es una consulta: se mira, se guarda y se imprime,
     * pero no se numera. Numerar cada vez que alguien abre la constancia de un contribuyente
     * llenaria el correlativo de ruido, y es la misma decision que ya tomaron {@code
     * catastro.ReporteController} y los reportes de #53.
     */
    @GetMapping(params = "formato")
    public ResponseEntity<byte[]> documento(
            @RequestParam String codContribuyente,
            @RequestParam(required = false) @Nullable String fecha,
            @RequestParam String formato) {

        FormatoDeDocumento elegido = formatoDe(formato);
        ConstanciaDeNoAdeudo constancia = buscar(codContribuyente, fecha);
        byte[] archivo = documentos.generar(ModeloDeLaConstanciaDeNoAdeudo.de(constancia), elegido);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(elegido.tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        elegido.nombreDeArchivo(
                                                ModeloDeLaConstanciaDeNoAdeudo.nombreDeArchivo(
                                                        constancia)))
                                .build()
                                .toString())
                .body(archivo);
    }

    private ConstanciaDeNoAdeudo buscar(String codContribuyente, @Nullable String fecha) {
        if (codContribuyente.isBlank()) {
            throw new IllegalArgumentException(
                    "codContribuyente es obligatorio para emitir la constancia de no adeudo");
        }
        return consulta.constanciaDeNoAdeudo(codContribuyente, fechaDe(fecha));
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

    /**
     * La fecha de corte pedida, o hoy si no viene ninguna. Ver {@link
     * ConsultaDeudaController#fechaDe} para por que {@code DateTimeParseException} necesita su
     * propio {@code catch}.
     */
    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return consulta.hoy();
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException excepcion) {
            throw new IllegalArgumentException(
                    "La fecha de corte debe tener formato AAAA-MM-DD: '" + texto + "'", excepcion);
        }
    }
}
