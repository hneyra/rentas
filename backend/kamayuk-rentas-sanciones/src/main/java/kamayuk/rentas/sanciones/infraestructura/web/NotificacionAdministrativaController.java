package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.RegistrarNotificacionAdministrativa;
import kamayuk.rentas.sanciones.aplicacion.SubsanarNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final SubsanarNotificacion subsanar;
    private final Clock reloj;

    public NotificacionAdministrativaController(
            RegistrarNotificacionAdministrativa servicio,
            SubsanarNotificacion subsanar,
            Clock reloj) {
        this.servicio = servicio;
        this.subsanar = subsanar;
        this.reloj = reloj;
    }

    /**
     * Cierra la notificacion por subsanacion (#47 AC2, #611): sin papeleta ni deuda.
     *
     * <p>Hasta #611 {@link SubsanarNotificacion} no tenia ruta, y {@code SUBSANADA} —que {@code
     * notificacion_administrativa} admite desde la baseline— no la escribia nada en produccion. La
     * fecha es la del acto, y sin ella la de hoy; una fecha futura no se admite.
     */
    @PostMapping("/{numero}/subsanacion")
    public NotificacionAdministrativaResource subsanar(
            @PathVariable String numero, @RequestBody PeticionDeSubsanacion peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate hoy = LocalDate.now(reloj);
        LocalDate fecha =
                peticion.fechaSubsanacion() == null
                        ? hoy
                        : fechaDe(peticion.fechaSubsanacion(), "fechaSubsanacion");
        if (fecha.isAfter(hoy)) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La subsanacion no se fecha despues de hoy: "
                            + fecha
                            + " todavia no ha llegado");
        }
        try {
            return NotificacionAdministrativaResource.de(
                    subsanar.subsanar(numero, fecha, observacion));
        } catch (SubsanarNotificacion.NotificacionInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (SubsanarNotificacion.EstadoInvalido | SubsanarNotificacion.FueraDePlazo cerrada) {
            // 409: la peticion esta bien escrita; lo que no la admite es que la notificacion ya no
            // este EMITIDA, o que su plazo haya vencido a esa fecha.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cerrada));
        }
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
        } catch (RegistrarNotificacionAdministrativa.ContribuyenteInexistente noEsta) {
            // #422: hasta aqui era el 500 de notif_adm_contribuyente_fk.
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (NotificacionAdministrativaRepository.NotificacionRepetida repetida) {
            // #422: 409 y no 422 —la peticion esta bien escrita, lo que no la admite es que ese
            // numero ya lo tiene otra notificacion—. Hasta aqui era el 500 del indice unico.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetida));
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

    /**
     * El plazo, comprobado ANTES de convertirlo a {@code smallint} (#454).
     *
     * <p>{@code Integer.shortValue()} es un estrechamiento: se queda con los 16 bits bajos y nunca
     * lanza, asi que el {@code catch} que habia aqui era codigo muerto y 65 566 dias se guardaban
     * como 30, con un 201. La cota es la de la columna, {@code plazo_dias smallint}.
     */
    private static @Nullable Short plazoDe(@Nullable Integer plazoDias) {
        if (plazoDias == null) {
            return null;
        }
        if (plazoDias < 1 || plazoDias > Short.MAX_VALUE) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El plazo en dias va de 1 a "
                            + Short.MAX_VALUE
                            + ": "
                            + plazoDias
                            + " no cabe, y guardarlo recortado daria otro plazo");
        }
        return plazoDias.shortValue();
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
     * El cuerpo de una subsanacion: su fecha —sin ella, hoy— y por que (regla 10).
     *
     * @param fechaSubsanacion {@code AAAA-MM-DD}
     */
    public record PeticionDeSubsanacion(
            @Nullable String fechaSubsanacion, @Nullable String observacion) {}

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
