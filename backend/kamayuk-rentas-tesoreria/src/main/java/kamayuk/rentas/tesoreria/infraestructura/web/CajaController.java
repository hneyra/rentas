package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.CobrarDeuda;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las dos ventanillas por HTTP: caja tributaria y caja de tasas (RF-080, RF-081).
 *
 * <p>Solo {@code POST}. No hay ningun {@code PUT} ni {@code PATCH}, y no por estilo: un recibo no
 * se corrige (regla 4, V29). Lo que le pasa despues llega como un recurso nuevo —una anulacion, un
 * duplicado—, que es lo que #34 escribira.
 *
 * <p><b>La cabecera {@code idempotency-key}.</b> El frontend ya la manda en toda escritura ({@code
 * nuevaClaveDeIdempotencia}) y hasta ahora ningun endpoint la leia. Aqui si: reenviar el mismo
 * intento —el doble clic, el reintento del navegador tras un tiempo de espera— devuelve el recibo
 * que se emitio la primera vez, con su mismo numero, en vez de cobrar otra vez. La garantia ultima
 * es {@code recibo_idempotencia_uq} (V29), no esta lectura.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria/caja")
public class CajaController {

    private final CobrarDeuda cobrarDeuda;
    private final CobrarTasa cobrarTasa;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public CajaController(
            CobrarDeuda cobrarDeuda,
            CobrarTasa cobrarTasa,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.cobrarDeuda = cobrarDeuda;
        this.cobrarTasa = cobrarTasa;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Caja tributaria: cobra la deuda marcada y emite el recibo (RF-080).
     *
     * <p>Con {@code tipoDePago = PRECONVENIO} cobra en cambio la <b>cuota inicial</b> de un
     * convenio de fraccionamiento y lo formaliza (#35, RF-084). Es la misma ruta y el mismo turno a
     * proposito: el dinero entra por la misma ventanilla, la numeracion del recibo es la misma y la
     * atomicidad —recibo mas acogimiento— sale gratis de estar dentro de la misma transaccion.
     */
    @PostMapping("/cobranza")
    @RequiereAcceso(acceso = "caja_tributaria", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ReciboResource> cobranza(
            @RequestBody PeticionDeCobranza peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {

        ResumenDeContribuyente contribuyente = contribuyenteDe(peticion.codContribuyente());
        LocalDate fechaDePago = fechaDe(peticion.fechaDePago(), "fechaDePago");
        Observacion observacion = observacionDe(peticion.observacion());

        CobrarDeuda.Cobranza cobranza;
        try {
            cobranza =
                    new CobrarDeuda.Cobranza(
                            exigir(peticion.caja(), "caja"),
                            exigir(peticion.cajero(), "cajero"),
                            contribuyente.id(),
                            obligacionesDe(peticion.obligaciones()),
                            FormaDePago.porNombre(exigir(peticion.formaDePago(), "formaDePago")),
                            tipoDePagoDe(peticion.tipoDePago()),
                            vacioAnulo(peticion.beneficioAplicable()),
                            fechaDePago,
                            vacioAnulo(clave),
                            vacioAnulo(peticion.numeroDeConvenio()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        try {
            Recibo emitido = cobrarDeuda.cobrar(cobranza, observacion);
            return ResponseEntity.status(HttpStatus.CREATED).body(ReciboResource.de(emitido));
        } catch (CobrarDeuda.NadaQueCobrar yaPagado) {
            // 409 y no 422: la peticion esta bien formada. Lo que pasa es que el estado
            // actual no admite la operacion, porque esa deuda ya se cobro.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaPagado));
        } catch (AbrirCaja.TurnoCerrado cerrado) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cerrado));
        } catch (AbrirCaja.CajaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (FormalizarConvenio.ConvenioInexistente noExisteConvenio) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExisteConvenio));
        } catch (FormalizarConvenio.ConvenioNoEsPreconvenio
                | MovimientoDeConvenioRepository.ConvenioYaFormalizado yaEstaba) {
            // 409: la peticion esta bien formada, lo que no admite la operacion es el
            // estado del convenio. Formalizar dos veces acogeria su deuda dos veces.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaEstaba));
        } catch (AbrirCaja.CajaDeBaja
                | CobrarDeuda.TipoDePagoNoImplementado
                | CobrarDeuda.SinCuotaInicialQueCobrar
                | FormalizarConvenio.LaInicialNoCuadra
                | FormalizarConvenio.SinDeudaQueAcoger
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * Caja de tasas: cobra derechos del TUPA y emite el recibo (RF-081).
     *
     * <p><b>{@code codContribuyente} tambien viaja por la consulta</b> (#425). Es el filtro «Cod.
     * Contribuyente» que la pantalla dibuja y el contrato lo declara {@code in: query}; leerlo solo
     * del cuerpo dejaba a la ventanilla de tasas publicada y sin poder cobrarle a nadie —el 422
     * decia «Falta el campo 'codContribuyente'» mientras la pantalla lo estaba mandando—. La
     * cobranza tributaria de al lado <b>no</b> lo declara {@code in: query} en el contrato y por
     * eso no cambia. Se sigue aceptando en el cuerpo, y ahi gana: ver {@link FiltroDeLaConsulta}.
     */
    @PostMapping("/tasas")
    @RequiereAcceso(acceso = "caja_tasas", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ReciboResource> tasas(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestBody PeticionDeCobroDeTasas peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {

        ResumenDeContribuyente contribuyente =
                contribuyenteDe(
                        FiltroDeLaConsulta.primeroNoVacio(
                                peticion.codContribuyente(), codContribuyente));
        LocalDate fechaDeCobro = fechaDe(peticion.fechaDeCobro(), "fechaDeCobro");
        Observacion observacion = observacionDe(peticion.observacion());

        CobrarTasa.CobroDeTasas cobro;
        try {
            cobro =
                    new CobrarTasa.CobroDeTasas(
                            exigir(peticion.caja(), "caja"),
                            exigir(peticion.cajero(), "cajero"),
                            contribuyente.id(),
                            conceptosDe(peticion.conceptos()),
                            FormaDePago.porNombre(exigir(peticion.formaDePago(), "formaDePago")),
                            fechaDeCobro,
                            vacioAnulo(clave));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        try {
            Recibo emitido = cobrarTasa.cobrar(cobro, observacion);
            return ResponseEntity.status(HttpStatus.CREATED).body(ReciboResource.de(emitido));
        } catch (AbrirCaja.TurnoCerrado cerrado) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cerrado));
        } catch (AbrirCaja.CajaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CobrarTasa.TasaSinTarifaVigente sinTarifa) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinTarifa));
        } catch (AbrirCaja.CajaDeBaja
                | CobrarTasa.TarifaEnCero
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private ResumenDeContribuyente contribuyenteDe(@Nullable String codigo) {
        String valor = exigir(codigo, "codContribuyente");
        return contribuyentes
                .porCodigo(valor)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con el codigo '"
                                                + valor
                                                + "'"));
    }

    /**
     * Las obligaciones marcadas.
     *
     * <p>Una lista vacia se deja pasar y la rechaza {@code CobrarDeuda.Cobranza}: el cobro de una
     * cuota inicial de convenio no marca ninguna —las marco el preconvenio—, y decidirlo aqui
     * obligaria a este metodo a conocer el tipo de pago para saber si vacio es un error.
     */
    private static List<SeleccionDeObligacion> obligacionesDe(
            @Nullable List<PeticionDeCobranza.PeticionDeObligacion> marcadas) {
        if (marcadas == null || marcadas.isEmpty()) {
            return List.of();
        }
        List<SeleccionDeObligacion> seleccion = new ArrayList<>(marcadas.size());
        for (PeticionDeCobranza.PeticionDeObligacion marcada : marcadas) {
            Integer ejercicio = marcada.ejercicio();
            if (ejercicio == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION, "Falta el campo 'obligaciones[].ejercicio'");
            }
            try {
                seleccion.add(
                        new SeleccionDeObligacion(
                                exigir(marcada.tributo(), "obligaciones[].tributo"),
                                new Ejercicio(ejercicio),
                                marcada.predioId(),
                                marcada.vehiculoId()));
            } catch (IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }
        return seleccion;
    }

    private static List<LineaDeTasaPedida> conceptosDe(
            @Nullable List<PeticionDeCobroDeTasas.PeticionDeConcepto> marcados) {
        if (marcados == null || marcados.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Hay que marcar al menos un concepto del TUPA");
        }
        List<LineaDeTasaPedida> conceptos = new ArrayList<>(marcados.size());
        for (PeticionDeCobroDeTasas.PeticionDeConcepto marcado : marcados) {
            Integer cantidad = marcado.cantidad();
            try {
                conceptos.add(
                        new LineaDeTasaPedida(
                                exigir(marcado.conceptoTupa(), "conceptos[].conceptoTupa"),
                                cantidad == null ? 1 : cantidad));
            } catch (IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }
        return conceptos;
    }

    /**
     * La fecha del cobro. Si no viene, hoy.
     *
     * <p>Admitirla explicita no es un descuido de seguridad sino lo que exige registrar una
     * cobranza de ayer que se quedo sin sistema: quien puede hacerlo tiene el privilegio de
     * REGISTRO sobre la opcion, y todo queda en la auditoria con su observacion.
     */
    private LocalDate fechaDe(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no es una fecha ISO valida: '" + texto + "'");
        }
    }

    private static TipoDePago tipoDePagoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return TipoDePago.NORMAL;
        }
        try {
            return TipoDePago.valueOf(texto.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Tipo de pago desconocido: '"
                            + texto
                            + "'. Se admite NORMAL, A_CUENTA, PRECONVENIO, CUOTA_CONVENIO o TASA");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda cobranza exige la observacion del usuario: sin ella no se guarda");
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

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        return (texto == null || texto.isBlank()) ? null : texto.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
