package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.pagos.EmitirOrdenDeCobro;
import kamayuk.rentas.tesoreria.pagos.OrdenesDeCobro;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La ventanilla pide que se cobre una deuda tributaria (P5D, ADR-0026 §1).
 *
 * <h2>Por que este endpoint existe</h2>
 *
 * <p>Es la primera mitad de lo que hasta P5D era un solo {@code POST /tesoreria/caja/cobranza}:
 * quien atiende marca las filas de la consulta de deuda y este endpoint compone las ordenes contra
 * el libro. La segunda mitad la sirve {@code caja} —{@code POST /caja/api/v1/cobros}—, y entre las
 * dos ya no hay una transaccion sino una conciliacion diaria.
 *
 * <h2>Lo que el cuerpo NO trae</h2>
 *
 * <p><b>Ningun importe.</b> Cuanto se debe lo dice el libro releyendose (ARQ-01 §3.8), y por eso el
 * {@code record} de la peticion no tiene donde ponerlo: si lo tuviera, la pantalla podria mandar el
 * que leyo hace cinco minutos y la caja lo imprimiria sin discutir. Tampoco trae la campaña de
 * beneficio —su descuento sigue bloqueado por D-02b— ni el medio de pago, que es de la caja y se
 * elige al cobrar.
 *
 * <h2>El contribuyente llega por su codigo del padron, no por su identificador</h2>
 *
 * <p>Como en el resto del contrato (#15): el codigo es lo que quien atiende tiene delante. Uno que
 * no este en el padron es <b>404 nombrandolo</b> y no una respuesta vacia — «esa persona no tiene
 * deuda» y «esa persona no existe» se leen igual y significan lo contrario (#622).
 */
@RestController
@RequestMapping(Api.RAIZ + "/ordenes-de-cobro")
public class OrdenDeCobroController {

    /**
     * El acceso con el que se emite.
     *
     * <p>Es {@code caja_tributaria} con {@code REGISTRO}, el mismo con el que se recibe el pago:
     * emitir la orden es exactamente lo que la ventanilla hacia al pulsar «Cobrar» cuando el cobro
     * era una sola transaccion, y darle un permiso propio crearia una opcion de menu que nadie abre
     * y que nadie administra.
     */
    private static final String ACCESO = "caja_tributaria";

    private final EmitirOrdenDeCobro emitir;
    private final DirectorioDeContribuyentes padron;

    public OrdenDeCobroController(EmitirOrdenDeCobro emitir, DirectorioDeContribuyentes padron) {
        this.emitir = emitir;
        this.padron = padron;
    }

    /**
     * Emite una orden por cada obligacion marcada que tenga deuda.
     *
     * <p><b>201</b> siempre que se emitiera al menos una, aunque alguna ya estuviera —una orden que
     * ya estaba es un reintento del mismo dia, y el cuerpo lo dice fila a fila con {@code nueva}—.
     */
    @PostMapping
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<EmisionResource> emitir(@RequestBody PeticionDeOrdenDeCobro peticion) {
        String codigo =
                exigir(peticion.codContribuyente(), "codContribuyente").strip().toUpperCase();
        ResumenDeContribuyente contribuyente =
                padron.porCodigo(codigo)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "El codigo '"
                                                        + codigo
                                                        + "' no esta en el padron de"
                                                        + " contribuyentes"));

        LocalDate aLaFecha = fecha(exigir(peticion.aLaFecha(), "aLaFecha"));
        Observacion observacion = Observacion.de(exigir(peticion.observacion(), "observacion"));

        EmitirOrdenDeCobro.Emision emision;
        try {
            emision =
                    emitir.emitir(
                            new EmitirOrdenDeCobro.Peticion(
                                    contribuyente.id(),
                                    obligacionesDe(peticion),
                                    aLaFecha,
                                    peticion.detalle(),
                                    peticion.pagadorDocumento(),
                                    peticion.pagadorNombre()),
                            observacion);
        } catch (EmitirOrdenDeCobro.NadaQueCobrar nada) {
            // 422 y no 404: el contribuyente existe y las obligaciones se entienden; lo que pasa es
            // que no hay nada que cobrar a esa fecha. Reintentar no cambia nada, y por eso no es un
            // error del servidor.
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(nada));
        } catch (EmitirOrdenDeCobro.ObligacionRepetida repetida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(repetida));
        } catch (OrdenesDeCobro.CajaInalcanzable noContesta) {
            // 503 y no 500: no es un defecto de este servidor, es que el otro no esta. Y no es 422:
            // reintentar SI puede cambiar el resultado, que es justo lo contrario.
            throw new ProblemaDeNegocio(
                    CodigoDeError.SERVICIO_NO_DISPONIBLE,
                    "La caja no contesta, asi que no se pudo emitir ninguna orden: "
                            + mensajeDe(noContesta));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(EmisionResource.de(emision));
    }

    // ------------------------------------------------------------------

    private static List<SeleccionDeObligacion> obligacionesDe(PeticionDeOrdenDeCobro peticion) {
        List<PeticionDeOrdenDeCobro.LineaMarcada> marcadas =
                Objects.requireNonNullElse(
                        peticion.obligaciones(), List.<PeticionDeOrdenDeCobro.LineaMarcada>of());
        if (marcadas.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "No se emite una orden sin marcar ninguna obligacion: falta 'obligaciones'");
        }
        List<SeleccionDeObligacion> seleccion = new ArrayList<>(marcadas.size());
        for (PeticionDeOrdenDeCobro.LineaMarcada linea : marcadas) {
            Integer ejercicio = linea.ejercicio();
            if (ejercicio == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION, "Falta 'obligaciones[].ejercicio'");
            }
            try {
                seleccion.add(
                        new SeleccionDeObligacion(
                                exigir(linea.tributo(), "obligaciones[].tributo"),
                                new Ejercicio(ejercicio),
                                linea.predioId(),
                                linea.vehiculoId()));
            } catch (IllegalArgumentException malFormada) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(malFormada));
            }
        }
        return seleccion;
    }

    private static LocalDate fecha(String texto) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malEscrita) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El campo 'aLaFecha' no es una fecha ISO: " + texto);
        }
    }

    /** El mensaje de una excepcion, que en Java es anulable y aqui nunca lo es. */
    private static String mensajeDe(Exception fallo) {
        String mensaje = fallo.getMessage();
        return mensaje == null ? fallo.getClass().getSimpleName() : mensaje;
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El cuerpo no trae '" + campo + "'");
        }
        return valor;
    }
}
