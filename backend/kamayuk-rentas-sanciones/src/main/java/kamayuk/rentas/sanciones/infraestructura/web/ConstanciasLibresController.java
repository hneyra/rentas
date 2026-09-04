package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.EmitirConstanciaLibre;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Constancia libre de infracciones: {@code POST /api/v1/transito/constancias-libres} (#53, RF-068).
 *
 * <h2>La negativa es un 409, y trae la lista</h2>
 *
 * <p>Cuando el vehículo debe papeletas a la fecha pedida, la respuesta es <b>409</b> —el estado
 * actual no admite la operación— con los números de las papeletas en {@code detalles}. No es un
 * 422: la petición está bien formada, lo que pasa es que la realidad dice que no. Y la lista viaja
 * porque quien vino a pedir la constancia lo que necesita saber es qué tiene que pagar; una
 * negativa sin ella lo manda a otra ventanilla a preguntar lo que este mismo endpoint ya sabe.
 *
 * <h2>Sale el documento, no un JSON</h2>
 *
 * <p>Una constancia es un papel que se entrega. La respuesta es el archivo, en el formato pedido
 * (PDF por omisión), con el número de la constancia en la cabecera {@code Content-Disposition} y en
 * {@code X-Sgtm-Numero} —para que la interfaz pueda enseñarlo sin abrir el PDF—.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/constancias-libres")
@RequiereAcceso(acceso = "transito_constancia_libre", privilegio = Privilegio.IMPRESION)
public class ConstanciasLibresController {

    /** Donde viaja el número de la constancia recién emitida. */
    static final String CABECERA_DEL_NUMERO = "X-Sgtm-Numero";

    private final EmitirConstanciaLibre emitir;
    private final Clock reloj;

    public ConstanciasLibresController(EmitirConstanciaLibre emitir, Clock reloj) {
        this.emitir = emitir;
        this.reloj = reloj;
    }

    @PostMapping
    public ResponseEntity<byte[]> emitir(@RequestBody PeticionDeConstanciaLibre peticion) {
        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        String placa = PeticionesDeSanciones.exigir(peticion.placa(), "placa");
        LocalDate verificadaAl =
                peticion.verificadaAl() == null || peticion.verificadaAl().isBlank()
                        ? LocalDate.now(reloj)
                        : PeticionesDeSanciones.fechaDe(peticion.verificadaAl(), "verificadaAl");
        FormatoDeDocumento formato =
                peticion.formato() == null || peticion.formato().isBlank()
                        ? FormatoDeDocumento.PDF
                        : ReportesDeSanciones.formatoDe(peticion.formato());

        EmitirConstanciaLibre.Emitida emitida;
        try {
            emitida =
                    emitir.emitir(
                            new EmitirConstanciaLibre.Peticion(
                                    placa,
                                    peticion.vehiculoId(),
                                    peticion.solicitanteId(),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.solicitante()),
                                    verificadaAl),
                            formato,
                            observacion);
        } catch (EmitirConstanciaLibre.HayPapeletasPendientes pendientes) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    PeticionesDeSanciones.mensajeDe(pendientes),
                    pendientes.numeros());
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.parseMediaType(formato.tipoDeMedio()))
                .header(CABECERA_DEL_NUMERO, emitida.constancia().numero())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(emitida.emision().nombreDeArchivo())
                                .build()
                                .toString())
                .body(emitida.emision().contenido());
    }
}
