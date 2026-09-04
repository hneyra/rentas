package kamayuk.rentas.indicadores.infraestructura.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.indicadores.aplicacion.ConsultaDeTrabajoParado;
import kamayuk.rentas.indicadores.aplicacion.PanelDeRecaudacion;
import kamayuk.rentas.indicadores.dominio.FrenteDeTrabajo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El panel de recaudacion, por HTTP: la operacion de la pantalla {@code inicio} (RF-130).
 *
 * <h2>Una sola lectura del reloj</h2>
 *
 * <p>El controlador consulta el reloj <b>una vez</b> y pasa el dia y el instante al caso de uso.
 * Las cifras del panel describen entonces la misma lectura, y una prueba puede pedir el panel de un
 * dia concreto y obtener siempre lo mismo (regla 6).
 *
 * <h2>Sin paginar, y a proposito</h2>
 *
 * <p>El contrato declara {@code ejercicio} y nada mas, que es lo que la pantalla manda. No hay
 * {@code pagina} ni {@code tamano} porque la respuesta es un <b>agregado</b> —una fila por tributo,
 * una por mes—, no un padron: paginar un agregado deja al cliente con una pagina de sumas y sin el
 * total, que es la cifra que un panel existe para dar.
 *
 * <h2>El ejercicio</h2>
 *
 * <p>Si no viene, el del reloj. No se rechaza la peticion sin el —la pantalla de inicio se abre sin
 * parametros— y no se inventa nada: lo que cambia es que ejercicio se resume, y la respuesta lo
 * dice en {@code ejercicio} y en la nota de cada bloque.
 */
@RestController
@RequestMapping(Api.RAIZ + "/indicadores")
public class IndicadoresController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO = "inicio";

    private final PanelDeRecaudacion panel;
    private final ConsultaDeTrabajoParado trabajoParado;
    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public IndicadoresController(
            PanelDeRecaudacion panel,
            ConsultaDeTrabajoParado trabajoParado,
            ComprobadorDeAcceso comprobador,
            Clock reloj) {
        this.panel = panel;
        this.trabajoParado = trabajoParado;
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    /** El avance de la recaudacion del ejercicio, con su cartera pendiente (RF-130). */
    @GetMapping("/recaudacion")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.LECTURA)
    public PanelResource recaudacion(@RequestParam(required = false) @Nullable String ejercicio) {
        Instant ahora = reloj.instant();
        LocalDate hoy = LocalDate.now(reloj);
        return PanelResource.de(panel.del(ejercicioDe(ejercicio, hoy), hoy, ahora));
    }

    /**
     * El trabajo parado por modulo: que espera un acto y no cobra mientras espera (#549).
     *
     * <h2>El permiso de cada frente se comprueba aqui, uno por uno</h2>
     *
     * <p>{@code @RequiereAcceso} abre la pantalla de inicio, y eso es lo unico que ese guardia
     * puede decir: el endpoint es uno y los frentes son de cuatro modulos distintos. Asi que el
     * controlador —que es quien conoce al usuario en curso, por {@code OrigenContext}— pregunta al
     * {@link ComprobadorDeAcceso} por el acceso de <b>cada</b> frente y le pasa al caso de uso solo
     * los que salen que si. Es el mismo reparto que {@code ConsultaDeConciliacion} eligio para el
     * permiso de fiscalizacion de {@code conciliadaConRentas=No}.
     *
     * <p>Un frente que el perfil no puede ver <b>no se consulta y no se publica</b>. No sale vacio
     * ni con un guion: una fila vacia ya dice que ahi hay algo que mirar (#297).
     */
    @GetMapping("/trabajo-parado")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.LECTURA)
    public TrabajoParadoResource trabajoParado(
            @RequestParam(required = false) @Nullable String ejercicio) {

        Instant ahora = reloj.instant();
        LocalDate hoy = LocalDate.now(reloj);
        String usuario = OrigenContext.actual().usuario();

        Set<FrenteDeTrabajo> visibles = EnumSet.noneOf(FrenteDeTrabajo.class);
        for (FrenteDeTrabajo frente : FrenteDeTrabajo.values()) {
            if (comprobador.autoriza(usuario, frente.acceso(), Privilegio.LECTURA, hoy)) {
                visibles.add(frente);
            }
        }

        return TrabajoParadoResource.de(
                trabajoParado.del(ejercicioDe(ejercicio, hoy), hoy, ahora, visibles));
    }

    private static Ejercicio ejercicioDe(@Nullable String texto, LocalDate hoy) {
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
}
