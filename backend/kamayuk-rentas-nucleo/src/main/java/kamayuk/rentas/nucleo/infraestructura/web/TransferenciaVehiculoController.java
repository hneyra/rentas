package kamayuk.rentas.nucleo.infraestructura.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.nucleo.aplicacion.RegistrarTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transferencia de vehiculo: {@code POST /api/v1/rentas/transferencias/vehiculo} (RF-026 parte de
 * registro, #29).
 *
 * <p>No lleva {@code codTransferente}: el transferente es quien figura hoy como titular del
 * vehiculo, y {@link RegistrarTransferencia#transferirVehiculo} lo lee de ahi. Pedirlo en el cuerpo
 * abriria la puerta a que el operador escriba un codigo distinto del que la base realmente tiene.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/transferencias/vehiculo")
@RequiereAcceso(acceso = "transferencia_vehiculo", privilegio = Privilegio.REGISTRO)
public class TransferenciaVehiculoController {

    private final RegistrarTransferencia transferencias;
    private final ConsultasDeRentas consultas;
    private final ConsultaDeVehiculos consultaDeVehiculos;

    public TransferenciaVehiculoController(
            RegistrarTransferencia transferencias,
            ConsultasDeRentas consultas,
            ConsultaDeVehiculos consultaDeVehiculos) {
        this.transferencias = transferencias;
        this.consultas = consultas;
        this.consultaDeVehiculos = consultaDeVehiculos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferenciaResource transferir(@RequestBody PeticionDeTransferenciaVehiculo peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        long vehiculoId = vehiculoDe(peticion.placa());
        long adquirienteId = contribuyenteDe(peticion.codAdquiriente());

        try {
            return TransferenciaResource.de(
                    transferencias.transferirVehiculo(
                            vehiculoId,
                            adquirienteId,
                            tipoDe(peticion.tipoTransferencia()),
                            fechaDe(peticion.fechaTransferencia()),
                            dineroDe(peticion.valorTransferencia()),
                            peticion.afectaAlcabala() != null && peticion.afectaAlcabala(),
                            exigir(peticion.documentoOrigen(), "documentoOrigen"),
                            observacion));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private long vehiculoDe(@Nullable String texto) {
        Placa placa;
        try {
            placa = Placa.de(exigir(texto, "placa"));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
        Vehiculo encontrado =
                consultaDeVehiculos
                        .vehiculoPorPlaca(placa)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun vehiculo con esa placa"));
        // Un vehiculo leido de la base siempre tiene id: lo asigna la base al guardarlo.
        return Objects.requireNonNull(encontrado.id());
    }

    private long contribuyenteDe(@Nullable String codigo) {
        return consultas
                .contribuyentePorCodigo(exigir(codigo, "codAdquiriente").toUpperCase(Locale.ROOT))
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con el codigo '"
                                                + codigo
                                                + "'"));
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

    /**
     * El tipo del acto, contra el vocabulario cerrado de {@link TipoTransferencia} (#542).
     *
     * <p>Mismo trato que {@code PredioController.tipoDe} le da a {@code TipoPredio}: <b>422
     * nombrando el valor</b>. Hasta #542 este campo era texto libre y {@code XXXX} entraba con un
     * 201, lo que dejaba un acto que ninguna consulta encuentra por su tipo.
     */
    private static TipoTransferencia tipoDe(@Nullable String texto) {
        try {
            return TipoTransferencia.de(exigir(texto, "tipoTransferencia"));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static Dinero dineroDe(@Nullable String texto) {
        try {
            return new Dinero(new BigDecimal(exigir(texto, "valorTransferencia")));
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El valor de transferencia no es un importe valido");
        }
    }

    private static LocalDate fechaDe(@Nullable String texto) {
        try {
            return LocalDate.parse(exigir(texto, "fechaTransferencia").strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
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
     * El cuerpo de una transferencia de vehiculo. <b>Lista blanca</b>: lo que no esta aqui no
     * entra. Sin {@code codTransferente}: ver el javadoc de la clase.
     */
    public record PeticionDeTransferenciaVehiculo(
            @Nullable String observacion,
            @Nullable String placa,
            @Nullable String codAdquiriente,
            @Nullable String tipoTransferencia,
            @Nullable String fechaTransferencia,
            @Nullable String valorTransferencia,
            @Nullable Boolean afectaAlcabala,
            @Nullable String documentoOrigen) {}
}
