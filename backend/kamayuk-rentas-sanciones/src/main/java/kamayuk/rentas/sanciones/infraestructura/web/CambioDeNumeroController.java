package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.CambiarNumeroDePapeleta;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cambio de número de papeleta: {@code PATCH /api/v1/transito/papeletas/{numero}/codigo} (RF-067).
 *
 * <p>"Corrige el número de papeleta... cuando hubo error del operador al momento del registro"
 * (contrato). No toca el desglose ni el cargo ya asentado —ver {@code CambiarNumeroDePapeleta}—.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/papeletas")
@RequiereAcceso(acceso = "transito_cambio_numero", privilegio = Privilegio.MODIFICACION)
public class CambioDeNumeroController {

    private final CambiarNumeroDePapeleta servicio;

    public CambioDeNumeroController(CambiarNumeroDePapeleta servicio) {
        this.servicio = servicio;
    }

    @PatchMapping("/{numero}/codigo")
    public PapeletaResource cambiar(
            @PathVariable String numero, @RequestBody PeticionDeCambioDeNumero peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        String numeroNuevo = exigir(peticion.numeroNuevo(), "numeroNuevo");

        try {
            return PapeletaResource.de(servicio.cambiar(numero, numeroNuevo, observacion));
        } catch (CambiarNumeroDePapeleta.PapeletaInexistente inexistente) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(inexistente));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /** El cuerpo de un cambio de número. <b>Lista blanca</b>: lo que no está aquí no entra. */
    public record PeticionDeCambioDeNumero(
            @Nullable String observacion, @Nullable String numeroNuevo) {}
}
