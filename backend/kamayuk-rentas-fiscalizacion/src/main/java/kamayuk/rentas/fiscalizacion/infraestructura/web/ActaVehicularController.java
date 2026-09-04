package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
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
 * Fiscalización vehicular: {@code POST /api/v1/fiscalizacion/vehicular} (RF-052, #45).
 *
 * <p>Trabaja sobre una copia: no toca ninguna fila de {@code rentas} (AC de #45). Nunca lleva ficha
 * ni área —esas dos son del acta predial—. El cuerpo es una <b>lista blanca</b>.
 *
 * <h2>Los tres filtros del cruce registral se retiran del contrato (#546, AC 9)</h2>
 *
 * <p>El contrato declaraba {@code placa}, {@code ejercicio} y {@code origenDelCruce} {@code in:
 * query} para esta operación, y <b>ningún método los lee</b>: son los filtros del cruce del padrón
 * vehicular contra SUNARP, SUNAT y MTC, y ese cruce <b>no existe</b> —no hay integración con ningún
 * registro, ni tabla donde apoyarla, ni operación que la haga—. Esta operación registra el acta de
 * una inspección, que es otra cosa.
 *
 * <p>Se retiran con {@code SUPRIMIDOS} del generador y no se dejan declarados: un parámetro
 * publicado es una promesa —el frontend lo manda creyendo que acota, y aquí acotaría <b>qué
 * vehículo se fiscaliza</b>—. Fingir la integración habría sido peor: la placa que el cruce «no
 * encontró» es indistinguible de la del cruce que nadie hizo. Vuelven el día que exista la
 * operación del cruce, y entonces serán suyos y no de esta acta.
 *
 * <p><b>{@code hallazgo} sí se lee, y no es del cruce</b> (#425). Es el filtro «Hallazgo» que la
 * pantalla dibuja y el contrato lo declara {@code in: query}. Aquí el desajuste no producía un 422
 * sino algo peor: {@code hallazgo} es opcional, así que la petición que la interfaz sabe construir
 * entraba con <b>201</b> y el acta quedaba guardada <b>sin hallazgo</b> —una inspección sin
 * conclusión, indistinguible de la que de verdad no encontró nada—. Se sigue aceptando en el
 * cuerpo, y ahí gana: ver {@link FiltroDeLaConsulta}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/vehicular")
@RequiereAcceso(acceso = "fisc_vehicular", privilegio = Privilegio.REGISTRO)
public class ActaVehicularController {

    private final RegistrarActaFiscalizacion actas;

    public ActaVehicularController(RegistrarActaFiscalizacion actas) {
        this.actas = actas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActaFiscalizacionResource registrar(
            @RequestParam(required = false) @Nullable String hallazgo,
            @RequestBody PeticionDeActaVehicular peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            return ActaFiscalizacionResource.de(
                    actas.registrarVehicular(
                            exigirId(peticion.programaId(), "programaId"),
                            exigirId(peticion.contribuyenteId(), "contribuyenteId"),
                            exigirId(peticion.vehiculoId(), "vehiculoId"),
                            fechaDe(peticion.fechaVisita()),
                            exigir(peticion.fiscalizador(), "fiscalizador"),
                            hallazgoDe(
                                    FiltroDeLaConsulta.primeroNoVacio(
                                            peticion.hallazgo(), hallazgo)),
                            peticion.detalle(),
                            observacion));
        } catch (RegistrarActaFiscalizacion.ProgramaInexistente
                | RegistrarActaFiscalizacion.ProgramaDeOtroTipo problema) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(problema));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static @Nullable Hallazgo hallazgoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Hallazgo.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Hallazgo desconocido: '" + texto + "'");
        }
    }

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

    private static LocalDate fechaDe(@Nullable String texto) {
        try {
            return LocalDate.parse(exigir(texto, "fechaVisita").strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static long exigirId(@Nullable Long valor, String campo) {
        if (valor == null || valor < 1) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor;
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

    /** El cuerpo de un acta vehicular. <b>Lista blanca</b>: lo que no está aquí no entra. */
    public record PeticionDeActaVehicular(
            @Nullable String observacion,
            @Nullable Long programaId,
            @Nullable Long contribuyenteId,
            @Nullable Long vehiculoId,
            @Nullable String fechaVisita,
            @Nullable String fiscalizador,
            @Nullable String hallazgo,
            @Nullable String detalle) {}
}
