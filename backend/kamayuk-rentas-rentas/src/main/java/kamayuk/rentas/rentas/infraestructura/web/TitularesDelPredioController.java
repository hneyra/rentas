package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeTitulares;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El titular del predio, resuelto al clic: {@code GET
 * /api/v1/catastro/predios/{predioId}/titulares} (#366, ADR-0015 §2.4).
 *
 * <p>Es la puerta que le faltaba a la fila de la consulta de fichas para poder <b>enlazar</b> con
 * la ficha de su titular. La grilla no cambia ni un byte: sigue publicando el nombre y no el
 * identificador, y quien quiera el codigo lo pide aqui, de un predio cada vez.
 *
 * <h2>El acceso es el del padron, no el de la pantalla</h2>
 *
 * <p>{@code contribuyentes} —la opcion del catalogo que abre el padron (NEG-03)—, y no {@code
 * consulta_fichas}, que es desde donde se hace clic. Es la excepcion deliberada al criterio de
 * {@code ConsultaPrediosController} y {@code ConciliacionController}, donde el acceso sigue a la
 * pantalla: aqui lo que se pide <b>no es catastro</b>, es el identificador de una persona en el
 * padron, y el publico de {@code consulta_fichas} —cualquiera que opere catastro— es mucho mas
 * amplio que el del padron. Exigir el acceso de la pantalla dejaria el cruce predio→persona al
 * alcance de todo el que pueda listar fichas, que es justo lo que ADR-0015 §2.4 decide que no.
 *
 * <p>La ruta, en cambio, si es la de la pantalla desde la que se hace clic: quien la sirve es un
 * detalle de donde vive el codigo (ADR-0015 §2.2), y este endpoint vive en {@code rentas} solo
 * porque es el unico modulo que puede depender de {@code catastro} y de {@code contribuyentes} a la
 * vez sin cerrar un ciclo.
 *
 * <h2>Uno a uno, y con rastro</h2>
 *
 * <p>No hay forma de pedir varios predios en una peticion, y es deliberado: un endpoint que acepte
 * una lista de identificadores vuelve a ser el extractor masivo que el listado no es. Cada
 * resolucion deja su fila de {@code ACCESO} en la bitacora, la escriba o no algun titular.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/predios/{predioId}/titulares")
@RequiereAcceso(acceso = TitularesDelPredioController.ACCESO, privilegio = Privilegio.LECTURA)
public class TitularesDelPredioController {

    /**
     * La opcion del catalogo (NEG-03) cuyo permiso cubre este cruce: el padron de contribuyentes.
     *
     * <p>Es {@code contribuyentes} y no «consulta_contribuyentes», que es como lo nombraba el
     * issue: esa opcion no existe en el catalogo de las 134, y un acceso inventado no lo tiene
     * nadie —el endpoint responderia 403 a todo el mundo y pareceria bien cerrado—.
     */
    static final String ACCESO = "contribuyentes";

    private final ConsultaDeTitulares consulta;
    private final Clock reloj;

    public TitularesDelPredioController(ConsultaDeTitulares consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public TitularesDelPredioResource resolver(
            @PathVariable long predioId,
            @RequestParam(required = false) @Nullable String vigenteA) {

        return TitularesDelPredioResource.de(consulta.resolver(predioId, fechaDe(vigenteA)));
    }

    /**
     * La fecha a la que se resuelve la titularidad; si no viene, hoy.
     *
     * <p>Del reloj inyectado y no de {@code LocalDate.now()} suelto: la respuesta la publica, asi
     * que una prueba puede comprobar cual se uso.
     */
    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException excepcion) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La fecha debe tener formato AAAA-MM-DD: '" + texto + "'");
        }
    }
}
