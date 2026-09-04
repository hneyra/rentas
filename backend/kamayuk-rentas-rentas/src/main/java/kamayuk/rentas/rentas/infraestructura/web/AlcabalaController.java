package kamayuk.rentas.rentas.infraestructura.web;

import java.math.BigDecimal;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.aplicacion.RegistrarAlcabala;
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
 * Impuesto de alcabala: {@code POST /api/v1/rentas/alcabala} (RF-026, #32).
 *
 * <p>{@code autovaluoAjustado} llega en el cuerpo porque el ajuste por el IPM no está resuelto
 * todavía (D-11): quien complete esta pantalla lo trae ya calculado, igual que {@code
 * TransferenciaPredioController} recibe el valor de transferencia en vez de inventarlo.
 *
 * <h2>Se llamaba {@code autoavaluoAjustado}, con una «a» de más (#541)</h2>
 *
 * <p>El resto del sistema dice <b>autovalúo</b>: la columna, el rótulo del prototipo ({@code
 * autovaluoAjustadoS}), los otros dos controladores de determinación y 186 apariciones del backend
 * frente a las cinco de alcabala. Se renombró, y no había contrato que proteger: los cuerpos del
 * contrato se declaran {@code schema: { type: object }}, así que <b>ningún nombre de campo de
 * cuerpo está publicado</b>, y el único cliente que podría mandarlo no lo manda —{@code alcabala}
 * está en {@code ACTOS_SIN_CAMPO} desde #385: esa pantalla no escribe—.
 *
 * <p>Que la decisión no se deshaga por descuido lo sostiene una prueba que compara los componentes
 * del {@code record} con los esperados, y no un comentario: cambiar el nombre vuelve a ser una
 * decisión que alguien toma, con su prueba en rojo delante.
 *
 * <h2>Lo que falta publicar se dice, y no es un 500 (#540)</h2>
 *
 * <p>La determinación lee del conjunto sellado del ejercicio de la transferencia la {@code UIT} y
 * la {@code ALICUOTA_ALCABALA}. Que el ejercicio no tenga ningún conjunto sellado ({@code
 * EjercicioSinSellar}) o que el conjunto no traiga una de las dos ({@code ParametroAusente}) salía
 * como <b>500 {@code ERROR_INTERNO} con identificador de incidencia</b>: la operación se leía como
 * «el servidor está roto» cuando lo que pasa es que falta publicar una cifra, y cada intento dejaba
 * una incidencia ERROR en el registro por lo que hoy es el estado normal del sistema (D-02a
 * abierta). Ahora es <b>422 nombrando la llave</b>, como en {@code PredialController} (#395) y
 * {@code VehicularController} (#399), que ya lo hacían para {@code ParametroAusente}: esta pantalla
 * y la de espectáculos eran las dos de Rentas que se habían quedado fuera.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/alcabala")
@RequiereAcceso(acceso = "alcabala", privilegio = Privilegio.REGISTRO)
public class AlcabalaController {

    private final RegistrarAlcabala servicio;

    public AlcabalaController(RegistrarAlcabala servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeterminacionAlcabalaResource determinar(@RequestBody PeticionDeAlcabala peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        long transferenciaId = exigirId(peticion.transferenciaId(), "transferenciaId");
        Dinero autovaluoAjustado = dineroDe(peticion.autovaluoAjustado(), "autovaluoAjustado");

        try {
            return DeterminacionAlcabalaResource.de(
                    servicio.determinar(transferenciaId, autovaluoAjustado, observacion));
        } catch (RegistrarAlcabala.TransferenciaInexistente inexistente) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(inexistente));
        } catch (RegistrarAlcabala.NoGravaAlcabala noGrava) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(noGrava));
        } catch (ParametrosSellados.ParametroAusente
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
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

    private static Dinero dineroDe(@Nullable String texto, String campo) {
        try {
            return new Dinero(new BigDecimal(exigir(texto, campo)));
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El campo '" + campo + "' no es un importe valido");
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
     * El cuerpo de la determinación de alcabala. <b>Lista blanca</b>: lo que no está aquí no entra.
     */
    public record PeticionDeAlcabala(
            @Nullable String observacion,
            @Nullable Long transferenciaId,
            @Nullable String autovaluoAjustado) {}
}
