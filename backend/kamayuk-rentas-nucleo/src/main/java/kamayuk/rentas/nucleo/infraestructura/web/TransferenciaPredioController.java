package kamayuk.rentas.nucleo.infraestructura.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.nucleo.aplicacion.RegistrarTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
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
 * Transferencia de predio: {@code POST /api/v1/rentas/transferencias/predio} (RF-026 parte de
 * registro, RF-027, #29).
 *
 * <p>El cuerpo es una <b>lista blanca</b>, igual que en {@code MovimientosDeDeudaController}: un
 * campo que la opcion no declara no entra. {@code predioId} llega como identificador y no como
 * codigo catastral porque quien completa esta pantalla ya vino de la busqueda de predio (RF-004),
 * que es quien resuelve el codigo; {@code codTransferente} y {@code codAdquiriente} si son codigos,
 * porque son los que el operador escribe en esta misma pantalla.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/transferencias/predio")
@RequiereAcceso(acceso = "transferencia_predio", privilegio = Privilegio.REGISTRO)
public class TransferenciaPredioController {

    private final RegistrarTransferencia transferencias;
    private final ConsultasDeRentas consultas;

    public TransferenciaPredioController(
            RegistrarTransferencia transferencias, ConsultasDeRentas consultas) {
        this.transferencias = transferencias;
        this.consultas = consultas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferenciaResource transferir(@RequestBody PeticionDeTransferenciaPredio peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        long predioId = exigirId(peticion.predioId(), "predioId");
        long transferenteId = contribuyenteDe(peticion.codTransferente(), "codTransferente");
        long adquirienteId = contribuyenteDe(peticion.codAdquiriente(), "codAdquiriente");

        try {
            return TransferenciaResource.de(
                    transferencias.transferirPredio(
                            predioId,
                            transferenteId,
                            adquirienteId,
                            tipoDe(peticion.tipoTransferencia()),
                            fechaDe(peticion.fechaTransferencia()),
                            dineroDe(peticion.valorTransferencia()),
                            porcentajeDe(peticion.porcentajeTransferido()),
                            peticion.afectaAlcabala() != null && peticion.afectaAlcabala(),
                            exigir(peticion.documentoOrigen(), "documentoOrigen"),
                            observacion));
        } catch (RegistrarTransferencia.TransferenteSinTitularidad sinTitularidad) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinTitularidad));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private long contribuyenteDe(@Nullable String codigo, String campo) {
        return consultas
                .contribuyentePorCodigo(exigir(codigo, campo).toUpperCase(java.util.Locale.ROOT))
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

    private static Porcentaje porcentajeDe(@Nullable String texto) {
        try {
            return Porcentaje.de(exigir(texto, "porcentajeTransferido"));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
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

    /**
     * El cuerpo de una transferencia de predio. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>El importe y el porcentaje viajan como texto, no como numero (regla 1): un {@code double}
     * en el JSON pierde centimos antes de llegar.
     */
    public record PeticionDeTransferenciaPredio(
            @Nullable String observacion,
            @Nullable Long predioId,
            @Nullable String codTransferente,
            @Nullable String codAdquiriente,
            @Nullable String tipoTransferencia,
            @Nullable String fechaTransferencia,
            @Nullable String valorTransferencia,
            @Nullable String porcentajeTransferido,
            @Nullable Boolean afectaAlcabala,
            @Nullable String documentoOrigen) {}
}
