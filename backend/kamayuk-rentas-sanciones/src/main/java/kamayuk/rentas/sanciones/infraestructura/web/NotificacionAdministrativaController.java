package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.RegistrarNotificacionAdministrativa;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notificación administrativa previa: {@code POST
 * /api/v1/infracciones/administrativas/notificaciones} (RF-070, #47).
 *
 * <p>"Un paso previo a la generación de la multa administrativa" —no exige contribuyente ni predio
 * identificados, ni un plazo: sin uno, la notificación nunca vence (#47 AC3).
 *
 * <p><b>{@code numero} también viaja por la consulta</b> (#425). Es el filtro «Número» que la
 * pantalla dibuja y el contrato lo declara {@code in: query}; leerlo solo del cuerpo dejaba la
 * operación publicada y sin ninguna pantalla que pudiera llamarla. Se sigue aceptando en el cuerpo,
 * y ahí gana: ver {@link FiltroDeLaConsulta}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/administrativas/notificaciones")
@RequiereAcceso(acceso = "adm_notificacion", privilegio = Privilegio.REGISTRO)
public class NotificacionAdministrativaController {

    private final RegistrarNotificacionAdministrativa servicio;

    public NotificacionAdministrativaController(RegistrarNotificacionAdministrativa servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificacionAdministrativaResource registrar(
            @RequestParam(required = false) @Nullable String numero,
            @RequestBody PeticionDeNotificacion peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            return NotificacionAdministrativaResource.de(
                    servicio.registrar(
                            exigir(
                                    FiltroDeLaConsulta.primeroNoVacio(peticion.numero(), numero),
                                    "numero"),
                            fechaDe(peticion.fecha(), "fecha"),
                            peticion.contribuyenteId(),
                            peticion.predioId(),
                            exigir(peticion.direccion(), "direccion"),
                            exigir(peticion.motivo(), "motivo"),
                            plazoDe(peticion.plazoDias()),
                            observacion));
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

    private static LocalDate fechaDe(@Nullable String texto, String campo) {
        try {
            return LocalDate.parse(exigir(texto, campo).strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static @Nullable Short plazoDe(@Nullable Integer plazoDias) {
        if (plazoDias == null) {
            return null;
        }
        try {
            return plazoDias.shortValue();
        } catch (ArithmeticException fueraDeRango) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El plazo en dias es demasiado grande: " + plazoDias);
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

    /**
     * El cuerpo de un registro de notificación. <b>Lista blanca</b>: lo que no está aquí no entra.
     */
    public record PeticionDeNotificacion(
            @Nullable String observacion,
            @Nullable String numero,
            @Nullable String fecha,
            @Nullable Long contribuyenteId,
            @Nullable Long predioId,
            @Nullable String direccion,
            @Nullable String motivo,
            @Nullable Integer plazoDias) {}
}
