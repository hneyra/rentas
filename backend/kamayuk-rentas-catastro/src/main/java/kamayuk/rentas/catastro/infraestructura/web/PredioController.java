package kamayuk.rentas.catastro.infraestructura.web;

import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ActualizarCatastro;
import kamayuk.rentas.catastro.aplicacion.ConsultaDePredios;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.TitularidadDelPredio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
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
 * El predio como recurso propio: {@code GET/POST /api/v1/catastro/predios}, {@code POST
 * /api/v1/catastro/predios/{predioId}/baja} y {@code POST
 * /api/v1/catastro/predios/{predioId}/reactivacion}.
 *
 * <h2>El alta, que es el orden natural de ventanilla</h2>
 *
 * <p>Hasta #489 un predio solo podia nacer como <b>efecto secundario</b> de inscribir su ficha, o
 * por la carga cartografica del perfil {@code batch}. El orden de ventanilla es el contrario:
 * primero se identifica el predio —su codigo de referencia catastral, su ubicacion, su tipo— y
 * despues se le levanta la ficha; y hasta aqui habia que inventarle una ficha para que existiera.
 *
 * <p>El alta <b>no resuelve nada aqui dentro</b>: el codigo se valida sin tocar la base y sector,
 * manzana y via los resuelve {@link InscribirFicha#inscribirPredio} dentro de su transaccion, que
 * es donde se emite el {@code SET LOCAL} que RLS exige (#486).
 *
 * <h2>Por que la baja tiene ruta y no es un campo de la actualizacion</h2>
 *
 * <p>Retirar un predio del padron no es versionar su ficha: la ficha sigue entera y lo que cambia
 * es el estado del predio. Meterlo como un campo del {@code PUT} de la actualizacion obligaria a
 * crear una version de ficha que no declara nada nuevo cada vez que se retira un predio, y dejaria
 * el acto mas grave del catastro escondido dentro del mas corriente.
 *
 * <p>Se identifica por {@code predioId} y no por codigo de referencia catastral, por lo mismo que
 * {@code /catastro/predios/{predioId}/titulares}: es el identificador que cada fila de la consulta
 * de fichas ya publica, y no hay dos convenciones para el mismo tramo de ruta.
 *
 * <h2>Las dos son irreversibles a medias, y a proposito</h2>
 *
 * <p>La baja no borra nada (regla 4, RNF-051): el predio deja de estar activo y sus fichas, su
 * titularidad y las determinaciones que se apoyaron en el quedan como estaban. Y tiene vuelta,
 * {@code reactivacion}, porque sin ella seria una puerta de un solo sentido —{@link
 * ActualizarCatastro#reactivar} lo explica—.
 *
 * <p><b>La baja exige {@code ELIMINACION} y la reactivacion {@code MODIFICACION}.</b> No son el
 * mismo privilegio porque no son el mismo riesgo: retirar un predio del padron lo saca de toda
 * emision futura, y devolverlo solo lo restituye. Es el reparto que {@link ViaController} y {@link
 * SectorController} ya hacen con la baja logica de su catalogo, aqui mas facil de declarar porque
 * cada acto tiene su ruta y el guardia no necesita leer el cuerpo.
 *
 * <p>La observacion viene en el cuerpo y es obligatoria en las dos (regla 10, RNF-052).
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/predios")
@RequiereAcceso(acceso = "actualizacion_catastro", privilegio = Privilegio.MODIFICACION)
public class PredioController {

    /** El codigo ordena por omision: es como se recorre un sector al sanearlo. */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    private final ActualizarCatastro catastro;
    private final ConsultaDePredios consulta;
    private final InscribirFicha inscribir;

    public PredioController(
            ActualizarCatastro catastro, ConsultaDePredios consulta, InscribirFicha inscribir) {
        this.catastro = catastro;
        this.consulta = consulta;
        this.inscribir = inscribir;
    }

    /**
     * El padron de predios, con sus filtros.
     *
     * <p>Exige {@code LECTURA} y no {@code MODIFICACION}: encontrar el predio es el paso previo de
     * los dos actos de esta pantalla, y pedir para leer el permiso de escribir dejaria sin poder
     * mirar a quien solo puede mirar.
     */
    @GetMapping
    @RequiereAcceso(acceso = "actualizacion_catastro", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PredioDelCatastroResource> listar(
            @RequestParam(required = false) @Nullable String codRefCatastral,
            @RequestParam(required = false) @Nullable String codigoDeSector,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam(required = false) @Nullable String fichado,
            @RequestParam(required = false) @Nullable String titularidad,
            ParametrosDePaginacion paginacion) {

        FiltroDePredios filtro =
                new FiltroDePredios(
                        codRefCatastral,
                        codigoDeSector,
                        estadoDe(estado),
                        fichadoDe(fichado),
                        titularidadDe(titularidad));

        return RespuestaPaginada.de(
                consulta.buscar(filtro, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PredioDelCatastroResource::de);
    }

    /**
     * Da de alta un predio, <b>sin ficha</b> (RF-001).
     *
     * <p>{@code codRefCatastral} y {@code direccion} son obligatorios; {@code tipoPredio} sin
     * declarar es {@code URBANO}, que es el caso de la inmensa mayoria del padron. Via, sector y
     * manzana entran por <b>codigo</b> —lo que el tecnico tiene delante—, y una referencia que no
     * existe es 404 nombrandola, no un predio guardado a medias.
     *
     * <p>Un codigo ya inscrito sale como {@code 409}. La unicidad la sostiene {@code
     * predio_codigo_uq} —es la unica que puede—, pero su mensaje nombra la tabla y la restriccion,
     * asi que se traduce: lo que el cliente recibe dice que el codigo esta tomado y nada mas.
     *
     * <p>Exige {@code REGISTRO}. La anotacion de la clase pide {@code MODIFICACION} y el {@code
     * GET} declara la suya, {@code LECTURA}: cada verbo dice lo que exige, que es lo que #431 dejo
     * claro que no se puede dar por heredado.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "actualizacion_catastro", privilegio = Privilegio.REGISTRO)
    public PredioResource inscribir(@RequestBody PeticionDeInscripcionDePredio peticion) {
        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        InscribirFicha.DatosDelPredio datos =
                new InscribirFicha.DatosDelPredio(
                        DeclaracionDeFicha.referenciaDe(
                                DeclaracionDeFicha.exigir(
                                        peticion.codRefCatastral(), "codRefCatastral")),
                        tipoDe(peticion.tipoPredio()),
                        DeclaracionDeFicha.exigir(peticion.direccion(), "direccion"),
                        DeclaracionDeFicha.vacioANulo(peticion.codigoDeVia()),
                        DeclaracionDeFicha.vacioANulo(peticion.numeroMunicipal()),
                        DeclaracionDeFicha.vacioANulo(peticion.codigoDeSector()),
                        DeclaracionDeFicha.vacioANulo(peticion.codigoDeManzana()),
                        DeclaracionDeFicha.vacioANulo(peticion.lote()),
                        DeclaracionDeFicha.vacioANulo(peticion.ubigeo()));
        try {
            return PredioResource.de(inscribir.inscribirPredio(datos, observacion));
        } catch (InscribirFicha.PredioYaInscrito repetido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, DeclaracionDeFicha.mensajeDe(repetido));
        } catch (InscribirFicha.ReferenciaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, DeclaracionDeFicha.mensajeDe(noExiste));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, DeclaracionDeFicha.mensajeDe(invalido));
        } catch (DuplicateKeyException choque) {
            // Ni tabla, ni restriccion, ni SQL: solo que el codigo esta tomado.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya hay un predio con ese codigo de referencia catastral en esta"
                            + " municipalidad");
        }
    }

    /** Retira el predio del padron. No lo borra. */
    @PostMapping("/{predioId}/baja")
    @RequiereAcceso(acceso = "actualizacion_catastro", privilegio = Privilegio.ELIMINACION)
    public PredioResource darDeBaja(
            @PathVariable long predioId, @RequestBody PeticionDeCambioDeEstado peticion) {
        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        try {
            return PredioResource.de(catastro.darDeBaja(predioId, observacion));
        } catch (ActualizarCatastro.PredioInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, DeclaracionDeFicha.mensajeDe(noExiste));
        } catch (ActualizarCatastro.EstadoQueYaTiene yaEsta) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, DeclaracionDeFicha.mensajeDe(yaEsta));
        }
    }

    /** Devuelve al padron un predio retirado. */
    @PostMapping("/{predioId}/reactivacion")
    public PredioResource reactivar(
            @PathVariable long predioId, @RequestBody PeticionDeCambioDeEstado peticion) {
        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        try {
            return PredioResource.de(catastro.reactivar(predioId, observacion));
        } catch (ActualizarCatastro.PredioInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, DeclaracionDeFicha.mensajeDe(noExiste));
        } catch (ActualizarCatastro.EstadoQueYaTiene yaEsta) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, DeclaracionDeFicha.mensajeDe(yaEsta));
        }
    }

    /**
     * El cuerpo de los dos actos. <b>Lista blanca</b>: solo la observacion.
     *
     * <p>No lleva el estado al que se va —eso lo dice la ruta— ni el predio —lo dice la ruta—. Un
     * cuerpo con el estado dentro admitiria una peticion que dice {@code baja} en la ruta y {@code
     * ACTIVO} en el cuerpo, y habria que decidir cual gana; asi no hay nada que decidir.
     */
    public record PeticionDeCambioDeEstado(@Nullable String observacion) {}

    /**
     * El cuerpo de un alta de predio. <b>Lista blanca</b>: lo que no esta aqui no entra, aunque
     * llegue en el JSON.
     *
     * <p>Es {@link InscribirFicha.DatosDelPredio} en la forma que llega por HTTP, y con los
     * <b>mismos nombres</b> que el bloque del predio de {@code PeticionDeAlta}: la pantalla que
     * ficha y la que inscribe el predio mandan el mismo campo con el mismo nombre, o una de las dos
     * acabaria mandando el que la otra ignora.
     *
     * <p>Ni {@code estado} ni {@code predioId}: el predio nace activo, y su identificador lo pone
     * la base. Ni un dato de ficha: para eso esta {@code POST /catastro/fichas/…}.
     */
    public record PeticionDeInscripcionDePredio(
            @Nullable String observacion,
            @Nullable String codRefCatastral,
            @Nullable String tipoPredio,
            @Nullable String direccion,
            @Nullable String codigoDeVia,
            @Nullable String numeroMunicipal,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            @Nullable String ubigeo) {}

    // ------------------------------------------------------------------

    /** Sin tipo declarado, urbano: es el caso de la inmensa mayoria del padron. */
    private static TipoPredio tipoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return TipoPredio.URBANO;
        }
        try {
            return TipoPredio.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de predio desconocido: '" + texto + "'");
        }
    }

    private static @Nullable EstadoPredio estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return EstadoPredio.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Estado de predio desconocido: '" + texto + "'");
        }
    }

    /**
     * {@code true}/{@code false}, y nada mas.
     *
     * <p>No se usa {@code Boolean.parseBoolean}, que lee cualquier cosa que no sea «true» como
     * {@code false}: con el, un {@code fichado=si} devolveria la cola de saneamiento entera cuando
     * lo que se pedia era lo contrario, sin un solo mensaje.
     */
    /**
     * El censo de saneamiento de titularidad (#690).
     *
     * <p>Se rechaza con 422 lo que no sea uno de los tres valores, en vez de ignorarlo: un filtro
     * ignorado devuelve el padron entero, y quien lo pidio leeria esos 14 422 predios como «todos
     * tienen la titularidad incompleta», que es la respuesta plausible y equivocada por la que
     * {@code ConsultaController} rechaza {@code conciliadaConRentas}.
     */
    private static @Nullable TitularidadDelPredio titularidadDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String valor = texto.strip().toUpperCase(Locale.ROOT);
        for (TitularidadDelPredio candidato : TitularidadDelPredio.values()) {
            if (candidato.name().equals(valor)) {
                return candidato;
            }
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "«titularidad» admite SIN_TITULAR, INCOMPLETA o COMPLETA, y llego '"
                        + texto
                        + "'. Sin filtro, el listado es el padron entero");
    }

    private static @Nullable Boolean fichadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String valor = texto.strip().toLowerCase(Locale.ROOT);
        if (valor.equals("true")) {
            return Boolean.TRUE;
        }
        if (valor.equals("false")) {
            return Boolean.FALSE;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "El filtro 'fichado' admite 'true' o 'false': llego '" + texto + "'");
    }
}
