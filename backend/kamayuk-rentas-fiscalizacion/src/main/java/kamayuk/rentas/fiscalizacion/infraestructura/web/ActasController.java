package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeActas;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las actas de inspección levantadas: {@code GET /api/v1/fiscalizacion/actas} (#599).
 *
 * <h2>Por qué no existía hasta ahora</h2>
 *
 * <p>Un acta se registraba —{@code POST /fiscalizacion/predial/actas} y {@code POST
 * /fiscalizacion/vehicular}— y no se podía volver a leer. #546 se negó a publicar esta lectura y
 * dejó escrito el motivo: el cuerpo del {@code POST} tenía <b>nueve</b> campos contra los
 * veintitrés controles y las siete filas de contraste declarado/verificado que la pantalla del
 * manual dibuja, así que un listado habría publicado esa misma foto incompleta. Lo que faltaba no
 * era por dónde leer, era <b>dónde guardar</b>, y en concreto el <b>uso hallado</b>: hoy lo anota
 * el acta ({@code acta_fiscalizacion.uso_hallado}, V76) y con él {@link
 * kamayuk.rentas.fiscalizacion.dominio.Hallazgo} tiene su quinto valor.
 *
 * <h2>Una lista para las dos familias, y una razon</h2>
 *
 * <p>El acta predial y la vehicular comparten tabla y ciclo de vida (V4), comparten tipo de dominio
 * y comparten {@link ActaFiscalizacionResource}; publicar dos listados sería mantener dos copias de
 * la misma consulta. Cuál es cuál lo dice cuál de {@code predioId} y {@code vehiculoId} trae valor.
 *
 * <p>De ahí el {@code oTambien}: la lectura la necesitan por igual las dos pantallas que escriben
 * actas, y exigir sólo {@code fisc_predial} dejaría a un perfil de fiscalización vehicular
 * <b>registrando actas que no puede volver a ver</b>. Está censado en {@code
 * AccesosCompartidosTest}, que es lo que impide que la lista crezca sin que el diff lo diga.
 *
 * <h2>Un solo filtro, y el motivo de que no haya más</h2>
 *
 * <p>{@code programa}, y está porque lo pide el <b>embudo del programa</b> (#546, AC 10): sus
 * cuatro etapas son «Programados», «Inspeccionados», «Con liquidación» y «Notificadas», y la única
 * que no tenía de dónde salir era la segunda —cuántas actas tiene el programa—. Se llena con el
 * {@code totalElementos} de esta operación acotada al programa, <b>no con una suma</b>: {@code
 * visitado} viaja fila a fila en la muestra y sumarlo en la interfaz recompondría una cifra
 * (RNF-083) sobre la página que se haya pedido.
 *
 * <p>Las dos pantallas del acta no dibujan <b>ningún</b> filtro —su catálogo no declara ni filtros
 * ni tabla—, así que no hay ninguno más que derivar del prototipo. Publicar el predio, el hallazgo
 * o el estado sería inventar promesas que ninguna pantalla hace, que es lo que #431, #432 y #544
 * tuvieron que retirar después.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/actas")
@RequiereAcceso(
        acceso = "fisc_predial",
        oTambien = "fisc_vehicular",
        privilegio = Privilegio.LECTURA)
public class ActasController {

    /** El orden por omisión: la fecha de la visita, que es como se recorre una jornada de campo. */
    private static final String ORDEN_POR_OMISION = "fechaVisita";

    private final ConsultaDeActas consulta;

    public ActasController(ConsultaDeActas consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<ActaFiscalizacionResource> actas(
            @RequestParam(required = false) @Nullable String programa,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                consulta.buscar(
                        new CriterioDeActas(programaOpcional(programa)),
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ActaFiscalizacionResource::de);
    }

    // ------------------------------------------------------------------

    private static @Nullable Long programaOpcional(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            long valor = Long.parseLong(texto.strip());
            if (valor < 1) {
                throw new NumberFormatException(texto);
            }
            return valor;
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El programa se identifica por su numero interno: '" + texto + "'");
        }
    }
}
