package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDelEmbudo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El embudo de un programa de fiscalizacion: {@code GET
 * /api/v1/fiscalizacion/programas/&#123;id&#125;/embudo} (#196).
 *
 * <h2>Por que existe</h2>
 *
 * <p>{@code fis-panel} es la unica de las cuatro hojas de Fiscalizacion que #179 no conecto, y no
 * por falta de tiempo: <b>no habia nada que pedirle</b>. Lo que declaraba era {@code GET
 * /fiscalizacion/estado-cuenta}, que publica otra cosa —la deuda de fiscalizacion de UN
 * contribuyente, con {@code ?contribuyente=} obligatorio— y ni una de las cuatro cifras del embudo.
 *
 * <h2>Una operacion y no cuatro</h2>
 *
 * <p>Las cuatro etapas se podian componer en el navegador con el {@code totalElementos} de cuatro
 * operaciones. No se hace, y esta medido por que: serian cuatro peticiones para cuatro numeros que
 * ninguna operacion afirma que signifiquen eso, tres de ellas acotadas a mano al programa —y sin
 * acotar cuentan el distrito entero mientras el rotulo dice «del programa»—, y un embudo compuesto
 * en el navegador <b>se lee igual</b> que uno publicado, con la diferencia de que solo uno de los
 * dos se puede cuadrar.
 *
 * <h2>Por que cuelga de {@code /programas/&#123;id&#125;} y no de una ruta propia</h2>
 *
 * <p>Porque el embudo es <b>de un programa</b>: sus cuatro cifras no existen sin uno. Es la misma
 * forma que {@code /programas/&#123;id&#125;/muestra}, y el {@code id} sale de {@code GET
 * /fiscalizacion/programas}, que es la unica operacion que lo publica.
 *
 * <h2>Dos opciones del catalogo, y el motivo</h2>
 *
 * <p>La pantalla que dibuja el embudo es {@code fisc_estado_cuenta} —el panel de Fiscalizacion— y
 * lo que el embudo cuenta es un programa, que es {@code fisc_programa}. Exigir solo la segunda
 * dejaria el panel contestando 403 a quien puede abrirlo; exigir solo la primera negaria la cifra a
 * quien administra los programas. Mismo mecanismo y mismo motivo que la grilla de actas (#599,
 * #548), y censado en {@code AccesosCompartidosTest}, que es lo que impide que la lista crezca sin
 * que el diff lo diga.
 *
 * <h2>Nada que escribir</h2>
 *
 * <p>Solo {@code LECTURA}. El embudo es un derivado de cuatro tablas y no se guarda en ninguna:
 * guardarlo dejaria dos verdades sobre lo mismo y la que se lee en pantalla seria la que nadie
 * recalculo (#397, #481).
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/programas")
public class EmbudoController {

    private final ConsultaDelEmbudo embudo;

    public EmbudoController(ConsultaDelEmbudo embudo) {
        this.embudo = embudo;
    }

    @GetMapping("/{id}/embudo")
    @RequiereAcceso(
            acceso = "fisc_estado_cuenta",
            oTambien = "fisc_programa",
            privilegio = Privilegio.LECTURA)
    public EmbudoResource embudo(@PathVariable long id) {
        try {
            return EmbudoResource.de(embudo.de(id));
        } catch (ConsultaDelEmbudo.ProgramaInexistente noExiste) {
            // 404 y no una respuesta en ceros: cuatro ceros se leen como «este programa no ha
            // detectado nada», que es lo contrario de «este programa no existe».
            String mensaje = noExiste.getMessage();
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO,
                    mensaje == null ? "No existe el programa de fiscalizacion " + id : mensaje);
        }
    }
}
