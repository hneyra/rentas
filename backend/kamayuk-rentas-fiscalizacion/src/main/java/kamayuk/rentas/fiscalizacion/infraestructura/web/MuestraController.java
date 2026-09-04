package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.fiscalizacion.dominio.ResultadoDelSorteo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La muestra de un programa: {@code GET} y {@code POST
 * /api/v1/fiscalizacion/programas/{id}/muestra} (#481, AC 2 de #431).
 *
 * <p><b>Es la pieza que le faltaba a las dos mitades del AC 2 a la vez.</b> La lectura es la grilla
 * «Predios seleccionados» de {@code fisc_programa}, y es también de donde {@code fisc_predial} —que
 * no declara ni filtros ni tabla, y dibuja sus tres identificadores de solo lectura— resuelve su
 * fila: sin ella no hay dónde buscarlos, y con ella salen los tres de golpe.
 *
 * <p><b>El privilegio se declara en cada método</b> y no sólo en la clase, que es la lección de
 * #431: leer la muestra pide {@link Privilegio#LECTURA} y sortearla {@link Privilegio#REGISTRO}, y
 * con una sola anotación de clase quien tuviera únicamente lectura sobre {@code fisc_programa} no
 * podría abrir su pantalla — sin que nada lo dijera hasta integrar.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/programas")
@RequiereAcceso(acceso = "fisc_programa", privilegio = Privilegio.REGISTRO)
public class MuestraController {

    /** El orden por omisión: el código del predio, que es como se lee la grilla. */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    private final GenerarMuestra sorteo;
    private final ConsultaDeMuestra muestra;
    private final DirectorioDeContribuyentes contribuyentes;

    public MuestraController(
            GenerarMuestra sorteo,
            ConsultaDeMuestra muestra,
            DirectorioDeContribuyentes contribuyentes) {
        this.sorteo = sorteo;
        this.muestra = muestra;
        this.contribuyentes = contribuyentes;
    }

    /**
     * La muestra sorteada de un programa.
     *
     * <p>{@code predio} viaja <b>por la consulta</b> y no por el cuerpo (#425): es como el acta
     * pide su propia fila, y una búsqueda que no cabe en la URL no se puede compartir ni recargar.
     *
     * <p><b>Un {@code id} que no existe es 404 nombrándolo</b> (#546). Cero filas significa «este
     * programa todavía no ha sorteado su muestra», que es lo contrario de «ese programa no está»; y
     * hasta este issue las dos respuestas eran idénticas byte a byte.
     */
    @GetMapping("/{id}/muestra")
    @RequiereAcceso(acceso = "fisc_programa", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<MuestraResource> muestra(
            @PathVariable long id,
            @RequestParam(required = false) @Nullable String predio,
            ParametrosDePaginacion paginacion) {

        ConsultaDeMuestra.Resultado resultado =
                muestra.buscar(
                                id,
                                predioOpcional(predio),
                                paginacion.aPaginacion(ORDEN_POR_OMISION))
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No existe el programa de fiscalizacion " + id));

        Map<Long, ResumenDeContribuyente> padron = padronDe(resultado.pagina());
        return RespuestaPaginada.de(
                resultado.pagina(),
                fila ->
                        MuestraResource.de(
                                fila,
                                codigoDe(padron, fila.contribuyenteId()),
                                nombreDe(padron, fila.contribuyenteId()),
                                resultado.visitado(fila)));
    }

    /**
     * Sortea la muestra: el acto que dispara la acción «Generar muestra» de la pantalla.
     *
     * <p>Responde <b>409</b> si el programa ya la sorteó. Una muestra es un acto y no se regenera:
     * hay actas levantadas sobre ella, y volver a sortear cambiaría la foto bajo sus pies. Para
     * otra muestra, otro programa.
     *
     * <p><b>Y la respuesta dice sobre qué padrón se sorteó</b> (#586). Hasta este issue devolvía
     * {@code {"programaId":N,"predios":M}} y nada más, así que una muestra de 100 sobre un padrón
     * donde 4 977 predios no podían entrar era indistinguible de una muestra de 100 sobre un padrón
     * de 100.
     */
    @PostMapping("/{id}/muestra")
    @ResponseStatus(HttpStatus.CREATED)
    public MuestraSorteadaResource generar(
            @PathVariable long id, @RequestBody PeticionDeMuestra peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            return MuestraSorteadaResource.de(id, sorteo.generar(id, observacion));
        } catch (GenerarMuestra.ProgramaInexistente inexistente) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(inexistente));
        } catch (GenerarMuestra.MuestraYaSorteada repetida) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetida));
        } catch (GenerarMuestra.ProgramaSinParametros incompleto) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(incompleto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static @Nullable Long predioOpcional(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(texto.strip());
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El predio de la muestra es un identificador: '" + texto + "'");
        }
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
     * Una sola lectura del padrón por página, no una por fila.
     *
     * <p>Las filas <b>sin titular</b> no piden nada: desde #586 la muestra las admite y su columna
     * es nula, así que no hay identificador que resolver.
     */
    private Map<Long, ResumenDeContribuyente> padronDe(Pagina<MuestraDelPrograma> pagina) {
        Set<Long> ids = new HashSet<>();
        for (MuestraDelPrograma fila : pagina.contenido()) {
            if (fila.contribuyenteId() != null) {
                ids.add(fila.contribuyenteId());
            }
        }
        return ids.isEmpty() ? Map.of() : contribuyentes.porIds(ids);
    }

    // Sin titular en la fila, nulo: es el predio que nadie reclama (#586). Con titular pero sin
    // fila en el padron se cae al identificador en vez de ocultar el predio: uno cuyo titular se
    // dio de baja es justamente el que hay que revisar. Mismo criterio que OmisosController.
    private static @Nullable String codigoDe(
            Map<Long, ResumenDeContribuyente> padron, @Nullable Long contribuyenteId) {
        if (contribuyenteId == null) {
            return null;
        }
        ResumenDeContribuyente enElMapa = padron.get(contribuyenteId);
        return enElMapa == null ? String.valueOf(contribuyenteId) : enElMapa.codigo();
    }

    private static @Nullable String nombreDe(
            Map<Long, ResumenDeContribuyente> padron, @Nullable Long contribuyenteId) {
        if (contribuyenteId == null) {
            return null;
        }
        ResumenDeContribuyente enElMapa = padron.get(contribuyenteId);
        return enElMapa == null ? String.valueOf(contribuyenteId) : enElMapa.nombre();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo del sorteo. <b>Sólo la observación</b>: a quién se fiscaliza lo deciden los
     * parámetros del programa, no esta petición — si viajaran aquí, la muestra dejaría de ser del
     * programa y dos sorteos del mismo podrían dar cosas distintas.
     *
     * <p><b>Y eso ya no es sólo un javadoc</b> (#550): ADR-0023 lo decidió por escrito frente a la
     * alternativa —que «Programar fiscalización» mandara los predios marcados en la grilla de
     * «Omisos y subvaluadores»— y {@code LaMuestraSeSorteaTest} exige que este {@code record} tenga
     * <b>exactamente un componente</b>. Un campo más aquí pone esa prueba en rojo, que es la forma
     * de que quien lo añada relea el ADR antes.
     */
    public record PeticionDeMuestra(@Nullable String observacion) {}

    /**
     * Lo que el sorteo devuelve: sobre cuántos predios va a actuar el programa, y <b>sobre qué
     * padrón se sorteó</b> (#586).
     *
     * <p>Con {@code predios} solo no se podía distinguir «el padrón tiene dos candidatos» de «tenía
     * cien y noventa y ocho no podían entrar», que es exactamente el silencio que este issue cierra
     * — y el criterio con el que se cerraron #538, #539 y #545.
     *
     * <p>{@code detectados} = {@code predios} + {@code excluidosPorOtroPrograma} + {@code
     * excluidosPorActaDelEjercicio}, siempre: el dominio no deja construir un reparto que no
     * cuadre, así que quien lea esta respuesta puede reconstruir el padrón examinado sin fiarse de
     * nadie.
     *
     * <p>Los excluidos van <b>por motivo</b> y no en un número solo: uno suelto no distingue «otro
     * programa abierto se lo llevó» de «ya se fiscalizó este ejercicio», que se arreglan de maneras
     * distintas.
     *
     * @param sinTitular cuántos de los sorteados no tienen titular vigente. Desde {@code V73}
     *     entran, y salen contados: quien visita va sabiendo que ahí tiene que averiguar quién
     *     ocupa
     * @param fechaSorteo el día al que se resolvió el padrón. La muestra es una foto y estas cifras
     *     son las de ese día (regla 9, RNF-075)
     */
    public record MuestraSorteadaResource(
            long programaId,
            int predios,
            int sinTitular,
            int detectados,
            int excluidosPorOtroPrograma,
            int excluidosPorActaDelEjercicio,
            String fechaSorteo) {

        static MuestraSorteadaResource de(long programaId, ResultadoDelSorteo resultado) {
            return new MuestraSorteadaResource(
                    programaId,
                    resultado.sorteados(),
                    resultado.sorteadosSinTitular(),
                    resultado.detectados(),
                    resultado.excluidosPorOtroPrograma(),
                    resultado.excluidosPorActaDelEjercicio(),
                    resultado.fechaSorteo().toString());
        }
    }
}
