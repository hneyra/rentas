package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El avance de recaudacion y su distribucion por area, por HTTP (RF-088, RF-089).
 *
 * <h2>Dos lecturas que no hacen esperar a la ventanilla</h2>
 *
 * <p>Las dos son {@code GET} y ninguna bloquea nada. El avance de un turno se consulta
 * <b>mientras</b> el cajero cobra —es lo que la pantalla de cierre llama «Cuadrar»—, y una lectura
 * que tomara el turno con {@code FOR UPDATE}, que es lo que hace la cobranza, pondria la cola a
 * esperar por un informe. Ver {@link ConsultaDeRecaudacion}.
 *
 * <h2>Sin paginar, y a proposito</h2>
 *
 * <p>El contrato declara {@code pagina} y {@code tamano} porque el generador se los pone a toda
 * pantalla con tabla. Aqui no se usan: las dos respuestas son <b>agregados</b> —una fila por
 * tributo, una por (area, partida, tributo)—, no un padron. Paginar un agregado dejaria al cliente
 * con una pagina de sumas y sin el total, que es la cifra que el reporte existe para dar; y el
 * total de una pagina no es el total del periodo.
 *
 * <h2>El rango</h2>
 *
 * <p>{@code desde} y {@code hasta} en ISO. Si no vienen, se derivan del {@code ejercicio}: el ano
 * entero. Si tampoco viene, el ejercicio del reloj. Ninguna de las tres opciones inventa una cifra:
 * lo que cambia es que periodo se suma, y la respuesta lo dice siempre.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria/recaudacion")
public class RecaudacionController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_AVANCE = "avance_recaudacion";

    static final String ACCESO_POR_AREA = "recaudacion_area";

    private final ConsultaDeRecaudacion recaudacion;
    private final Clock reloj;

    public RecaudacionController(ConsultaDeRecaudacion recaudacion, Clock reloj) {
        this.recaudacion = recaudacion;
        this.reloj = reloj;
    }

    /**
     * El avance de recaudacion del periodo, por tributo (RF-088).
     *
     * <p>Con {@code caja} y {@code cajero} responde en cambio el <b>avance en vivo del turno</b>:
     * lo que ese cajero lleva cobrado y anulado ese dia, que es el mismo arqueo que el cierre
     * congelara. Los dos parametros no salen de la pantalla —el prototipo dibuja ejercicio, rango y
     * tributo— sino del backend, como {@code ejercicio} en la bitacora: sin ellos, la pantalla de
     * cierre no podria cuadrar antes de firmar.
     *
     * <p>El dia del turno es el <b>ultimo del rango</b>: con el rango de un dia —que es como la
     * pantalla de cierre lo pide— ese dia, y con un rango largo el mas reciente. Se elige asi y no
     * «hoy» para que se pueda cuadrar el turno de ayer que se quedo sin sistema, igual que {@code
     * POST /caja/cierre} admite su fecha.
     *
     * <p>Si el cajero no ha abierto turno ese dia, 404: no hay nada que arquear, y devolver un
     * arqueo en ceros haria pensar que abrio y no cobro.
     */
    @GetMapping("/avance")
    @RequiereAcceso(acceso = ACCESO_AVANCE, privilegio = Privilegio.LECTURA)
    public RecaudacionResource.Avance avance(
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String tributo,
            @RequestParam(required = false) @Nullable String caja,
            @RequestParam(required = false) @Nullable String cajero) {

        LocalDate hoy = LocalDate.now(reloj);
        String laCaja = vacioAnulo(caja);
        String elCajero = vacioAnulo(cajero);
        CriterioDeRecaudacion criterio =
                criterio(ejercicio, desde, hasta, tributo, null, hoy).enLaCajaDe(laCaja, elCajero);

        // Una sola forma de respuesta, con el turno dentro cuando se pide. Devolver dos
        // tipos distintos segun los parametros obligaria al cliente a mirar que llego
        // antes de saber que dibujar, y ese es el camino a dos renderizadores.
        RecaudacionResource.AvanceDelTurno delTurno = null;
        if (laCaja != null && elCajero != null) {
            LocalDate dia = criterio.hasta();
            delTurno =
                    recaudacion
                            .delTurno(laCaja, elCajero, dia, hoy)
                            .map(avance -> RecaudacionResource.AvanceDelTurno.de(laCaja, avance))
                            .orElseThrow(
                                    () ->
                                            new ProblemaDeNegocio(
                                                    CodigoDeError.NO_ENCONTRADO,
                                                    "El cajero '"
                                                            + elCajero
                                                            + "' no abrio turno en la caja '"
                                                            + laCaja
                                                            + "' el "
                                                            + dia));
        }
        return RecaudacionResource.Avance.de(recaudacion.avance(criterio, hoy), delTurno);
    }

    /** La recaudacion por area generadora, partida presupuestal y tributo (RF-089). */
    @GetMapping("/por-area")
    @RequiereAcceso(acceso = ACCESO_POR_AREA, privilegio = Privilegio.LECTURA)
    public RecaudacionResource.Distribucion porArea(
            @RequestParam(required = false) @Nullable String area,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String ejercicio) {

        LocalDate hoy = LocalDate.now(reloj);
        return RecaudacionResource.Distribucion.de(
                recaudacion.porPartida(
                        criterio(ejercicio, desde, hasta, null, codigoDeArea(area), hoy), hoy));
    }

    // ------------------------------------------------------------------

    private CriterioDeRecaudacion criterio(
            @Nullable String ejercicio,
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String tributo,
            @Nullable String area,
            LocalDate hoy) {

        Ejercicio delAno = ejercicioDe(ejercicio, hoy);
        LocalDate inicio = fechaDe(desde, "desde", LocalDate.of(delAno.valor(), 1, 1));
        LocalDate fin = fechaDe(hasta, "hasta", LocalDate.of(delAno.valor(), 12, 31));
        try {
            return new CriterioDeRecaudacion(inicio, fin, vacioAnulo(tributo), area, null, null);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * El area, tal como la dibuja la pantalla: {@code «113300 — SUBGERENCIA DE …»}.
     *
     * <p>Se queda con el codigo, que es lo que {@code area.codigo} guarda. Mandar la etiqueta
     * entera es lo que hara el desplegable del prototipo mientras no se conecte al catalogo de
     * areas, y rechazarla obligaria a la interfaz a partir la cadena por su cuenta —que es
     * exactamente donde acaban dos formas distintas de hacerlo—.
     */
    private static @Nullable String codigoDeArea(@Nullable String area) {
        String limpio = vacioAnulo(area);
        if (limpio == null) {
            return null;
        }
        int separador = limpio.indexOf('—');
        return separador < 0 ? limpio : limpio.substring(0, separador).strip();
    }

    private Ejercicio ejercicioDe(@Nullable String texto, LocalDate hoy) {
        if (texto == null || texto.isBlank()) {
            return Ejercicio.de(hoy);
        }
        try {
            return new Ejercicio(Integer.parseInt(texto.strip()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'ejercicio' no es un ano valido: '" + texto + "'");
        }
    }

    private static LocalDate fechaDe(@Nullable String texto, String campo, LocalDate porOmision) {
        if (texto == null || texto.isBlank()) {
            return porOmision;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no es una fecha ISO valida: '" + texto + "'");
        }
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        return (texto == null || texto.isBlank()) ? null : texto.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
