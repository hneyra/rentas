package kamayuk.rentas.tesoreria.infraestructura.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.ArqueoDeTurno;
import kamayuk.rentas.tesoreria.aplicacion.CerrarTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
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
 * El cierre y arqueo de caja por HTTP (RF-087).
 *
 * <p>Solo {@code POST}, y por lo mismo que {@link CajaController}: un cierre no se corrige (regla
 * 4, V32). Lo que le pasa despues llega como un recurso nuevo —su reversion—, que es lo que es: un
 * acto que se agrega.
 *
 * <p><b>Una ruta, dos actos.</b> La pantalla «Cierre y arqueo de caja» declara un solo endpoint, y
 * publicar aqui un segundo verbo que ninguna pantalla llama lo rechazaria el contrato ({@code
 * ContratoDeApiTest}). Cual de los dos es lo dice el cuerpo: con {@code motivoDeReversion} se
 * reversa, sin el se cierra. Es la misma solucion que {@code SectorController} usa para editar y
 * dar de baja con un solo {@code PUT}.
 *
 * <p>Cerrar exige {@link Privilegio#REGISTRO}; <b>reversar exige ademas {@link
 * Privilegio#ELIMINACION}</b>, comprobado en el metodo con el mismo puerto que usa el guardia.
 * Reversar reabre una caja cuyo arqueo ya estaba firmado, que es una operacion de otra categoria
 * que cerrarla: la anotacion declara lo que exige la ruta, y la ruta es una sola.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria/caja")
public class CierreController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO = "cierre_caja";

    private final CerrarTurno cerrarTurno;
    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public CierreController(CerrarTurno cerrarTurno, ComprobadorDeAcceso comprobador, Clock reloj) {
        this.cerrarTurno = cerrarTurno;
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    /**
     * Cierra el turno con su arqueo, o reversa el cierre vigente (RF-087).
     *
     * <p>Devuelve 201 en los dos casos: los dos <b>crean</b> un acta. Reversar no borra el cierre
     * anterior —sigue donde estaba, con su arqueo—, escribe otra fila que lo deja sin efecto.
     */
    @PostMapping("/cierre")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<CierreResource> cierre(@RequestBody PeticionDeCierre peticion) {
        String caja = exigir(peticion.caja(), "caja");
        String cajero = exigir(peticion.cajero(), "cajero");
        LocalDate fecha = fechaDe(peticion.fecha());
        Observacion observacion = observacionDe(peticion.observacion());
        String motivo = vacioAnulo(peticion.motivoDeReversion());

        try {
            if (motivo != null) {
                exigirPrivilegioDeReversion();
                CerrarTurno.Reversado reversado =
                        cerrarTurno.reversar(caja, cajero, fecha, motivo, observacion);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(CierreResource.de(reversado, cajero));
            }
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre(
                                    caja, cajero, fecha, declaradoDe(peticion.declarado())),
                            observacion);
            return ResponseEntity.status(HttpStatus.CREATED).body(CierreResource.de(cerrado));
        } catch (AbrirCaja.CajaInexistente | CerrarTurno.TurnoSinAbrir noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CerrarTurno.TurnoYaCerrado
                | CerrarTurno.TurnoSinCerrar
                | CierreDeTurnoRepository.TurnoYaTieneEseMovimiento yaEstaba) {
            // 409 y no 422: la peticion esta bien formada. Lo que no admite la operacion es
            // el estado del turno.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaEstaba));
        } catch (ArqueoDeTurno.ElArqueoNoCuadraConElLibro noCuadra) {
            // 409 tambien: no es que el cliente mandara mal la peticion, es que el estado
            // del sistema no permite firmar este cierre. Se responde el mensaje entero
            // porque nombra las dos cifras y ninguna tabla ni restriccion.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(noCuadra));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    /**
     * Reversar exige ademas {@link Privilegio#ELIMINACION}.
     *
     * <p>Se comprueba aqui y no con la anotacion porque la anotacion declara lo que exige la
     * <b>ruta</b>, y la ruta es una sola para cerrar y para reversar: cual de las dos es depende
     * del cuerpo, que el interceptor no lee. Mismo camino que la baja de un sector (#290).
     */
    private void exigirPrivilegioDeReversion() {
        String usuario = OrigenContext.actual().usuario();
        if (!comprobador.autoriza(usuario, ACCESO, Privilegio.ELIMINACION, LocalDate.now(reloj))) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "No tiene el privilegio " + Privilegio.ELIMINACION + " sobre " + ACCESO);
        }
    }

    /**
     * Lo declarado, por forma de pago.
     *
     * <p>Las cifras llegan como texto y se convierten con {@code new BigDecimal(texto)}: un numero
     * JSON pasa por un {@code double} en el analizador y ya perdio precision antes de que nadie
     * pueda comprobarlo (regla 1, RNF-055).
     */
    private static Map<FormaDePago, Dinero> declaradoDe(@Nullable Map<String, String> declarado) {
        Map<FormaDePago, Dinero> porForma = new EnumMap<>(FormaDePago.class);
        if (declarado == null) {
            return porForma;
        }
        for (Map.Entry<String, String> entrada : declarado.entrySet()) {
            FormaDePago forma;
            try {
                forma = FormaDePago.porNombre(entrada.getKey());
            } catch (IllegalArgumentException desconocida) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocida));
            }
            String texto = entrada.getValue();
            if (texto == null || texto.isBlank()) {
                continue;
            }
            try {
                porForma.put(forma, new Dinero(new BigDecimal(texto.strip())));
            } catch (NumberFormatException noEsUnImporte) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Lo declarado en "
                                + forma
                                + " no es un importe decimal valido: '"
                                + texto
                                + "'");
            }
        }
        return porForma;
    }

    /**
     * La fecha del turno. Si no viene, hoy.
     *
     * <p>Admitirla explicita es lo que permite cerrar el turno de ayer que se quedo sin sistema.
     * Quien puede hacerlo tiene el privilegio de REGISTRO sobre la opcion, y todo queda en la
     * auditoria con su observacion.
     */
    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'fecha' no es una fecha ISO valida: '" + texto + "'");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Cerrar o reversar un turno exige la observacion del usuario: sin ella no se"
                            + " guarda");
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
