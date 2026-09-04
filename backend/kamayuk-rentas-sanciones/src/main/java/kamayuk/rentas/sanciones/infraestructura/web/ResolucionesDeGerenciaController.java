package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las resoluciones de gerencia por HTTP: la ordinaria, la sancionadora, la administrativa y sus
 * notificaciones (#50, RF-065, RF-074).
 *
 * <h2>Cinco rutas, cuatro accesos</h2>
 *
 * <p>Cada endpoint declara el suyo: {@code transito_rg_ordinaria}, {@code
 * transito_rg_sancionadora}, {@code adm_resolucion_gerencia} y {@code adm_notificacion_resolucion}.
 * Sin {@code @RequiereAcceso} el guardia <b>niega</b>, y la regla de arquitectura rompe el build;
 * las dos cosas juntas hacen que el olvido no se pueda convertir en una puerta abierta.
 *
 * <p>La notificación de tránsito comparte acceso con la ordinaria: notificar la resolución es parte
 * de la misma opción del menú, y el manual no le da pantalla propia. La ruta sí es propia, porque
 * sin ella la sancionadora no se podría dictar nunca —su plazo se cuenta desde que la ordinaria
 * surte efecto—.
 *
 * <h2>Los bytes no viajan en el JSON</h2>
 *
 * <p>Lo que la respuesta lleva del documento es su número, su formato, su resumen SHA-256 y el
 * nombre del archivo; la descarga es otra petición. Meter un PDF en base64 dentro de un JSON lo
 * hincha un tercio.
 *
 * <h2>Qué devuelve 422, y por qué no 500 (#562)</h2>
 *
 * <p>El plazo de cumplimiento de la resolución ordinaria sale del <b>conjunto sellado</b> que rige
 * a la fecha del acto —o de la diligencia— ({@link PlazosDeSancionesParametrizados}, regla 5). Ni
 * que falte el conjunto entero ({@code EjercicioSinSellar}) ni que falte la llave dentro de él
 * ({@code PlazoSinParametrizar}) estaban traducidas: las dos salían como <b>500 {@code
 * ERROR_INTERNO} con identificador de incidencia</b>, y con D-02a abierta ese es el estado
 * <i>normal</i> de todas las municipalidades — con lo que dictar la ordinaria y notificar cualquier
 * resolución eran inalcanzables, y cada intento ensuciaba el registro de errores del servidor.
 *
 * <p><b>La sancionadora y la administrativa no leen el plazo</b> —{@code
 * ResolverConResolucionDeGerencia} solo lo resuelve cuando el tipo es {@code ORDINARIA}— y la
 * diligencia solo lo lee cuando el resultado <b>surte efecto</b>: por esas ramas no se alcanzaba
 * ninguna de las dos, y siguen igual.
 *
 * <p>El mensaje es el de la propia excepción: nombra la llave —{@code
 * PLAZO:RG_ORDINARIA_CUMPLIMIENTO}— o, cuando lo que falta es el conjunto entero y no hay llave que
 * nombrar, el <b>ejercicio</b>. Un fallo de verdad del servidor sigue siendo 500 con su incidencia.
 */
@RestController
@RequestMapping(Api.RAIZ)
public class ResolucionesDeGerenciaController {

    static final String ACCESO_ORDINARIA = "transito_rg_ordinaria";

    static final String ACCESO_SANCIONADORA = "transito_rg_sancionadora";

    static final String ACCESO_ADMINISTRATIVA = "adm_resolucion_gerencia";

    static final String ACCESO_NOTIFICACION = "adm_notificacion_resolucion";

    private final ResolverConResolucionDeGerencia resolver;
    private final NotificarResolucionDeGerencia notificar;

    public ResolucionesDeGerenciaController(
            ResolverConResolucionDeGerencia resolver, NotificarResolucionDeGerencia notificar) {
        this.resolver = resolver;
        this.notificar = notificar;
    }

    /** La resolución que ordena la cobranza de una papeleta de tránsito. */
    @PostMapping("/transito/resoluciones/ordinaria")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO_ORDINARIA, privilegio = Privilegio.REGISTRO)
    public ResolucionResource ordinaria(@RequestBody PeticionDeResolucion peticion) {
        return dictar(Familia.TRANSITO, TipoDeResolucionDeGerencia.ORDINARIA, peticion);
    }

    /** La segunda resolución, con carácter sancionador. */
    @PostMapping("/transito/resoluciones/sancionadora")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO_SANCIONADORA, privilegio = Privilegio.REGISTRO)
    public ResolucionResource sancionadora(@RequestBody PeticionDeResolucion peticion) {
        return dictar(Familia.TRANSITO, TipoDeResolucionDeGerencia.SANCIONADORA, peticion);
    }

    /** La resolución del procedimiento administrativo sancionador. */
    @PostMapping("/infracciones/administrativas/resoluciones")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO_ADMINISTRATIVA, privilegio = Privilegio.REGISTRO)
    public ResolucionResource administrativa(@RequestBody PeticionDeResolucion peticion) {
        return dictar(Familia.ADMINISTRATIVA, TipoDeResolucionDeGerencia.ADMINISTRATIVA, peticion);
    }

    /** La cédula de notificación de una resolución de tránsito. */
    @PostMapping("/transito/resoluciones/{numero}/notificacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO_ORDINARIA, privilegio = Privilegio.REGISTRO)
    public DiligenciaResource notificarDeTransito(
            @PathVariable String numero, @RequestBody PeticionDeNotificacionDeResolucion peticion) {
        return diligenciar(numero, peticion);
    }

    /** La cédula de notificación de una resolución administrativa. */
    @PostMapping("/infracciones/administrativas/resoluciones/{id}/notificacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO_NOTIFICACION, privilegio = Privilegio.REGISTRO)
    public DiligenciaResource notificarAdministrativa(
            @PathVariable String id, @RequestBody PeticionDeNotificacionDeResolucion peticion) {
        return diligenciar(id, peticion);
    }

    // ------------------------------------------------------------------

    private ResolucionResource dictar(
            Familia familia, TipoDeResolucionDeGerencia tipo, PeticionDeResolucion peticion) {

        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        try {
            ResolverConResolucionDeGerencia.ResolucionDictada dictada =
                    resolver.dictar(
                            new ResolverConResolucionDeGerencia.Peticion(
                                    familia,
                                    PeticionesDeSanciones.exigir(peticion.papeleta(), "papeleta"),
                                    tipo,
                                    PeticionesDeSanciones.fechaDe(peticion.fecha(), "fecha"),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.nDeExpediente()),
                                    PeticionesDeSanciones.enumeradoSiViene(
                                            SentidoDelFallo.class,
                                            peticion.sentidoDelFallo(),
                                            "sentidoDelFallo"),
                                    PeticionesDeSanciones.enumeradoSiViene(
                                            EfectoSobreLaMulta.class,
                                            peticion.efectoSobreLaMulta(),
                                            "efectoSobreLaMulta"),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.sancionAccesoria()),
                                    PeticionesDeSanciones.exigir(peticion.sustento(), "sustento"),
                                    PeticionesDeSanciones.fechaSiViene(
                                            peticion.proyectarDeudaAl(), "proyectarDeudaAl")),
                            formatoDe(peticion.formato()),
                            observacion);
            return ResolucionResource.de(dictada);
        } catch (RegistrarDescargo.PapeletaInexistente
                | ResolverConResolucionDeGerencia.DescargoInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        } catch (ResolucionDeGerenciaRepository.ResolucionDuplicada
                | ResolverConResolucionDeGerencia.OrdinariaSinDictar
                | ResolverConResolucionDeGerencia.OrdinariaSinNotificar
                | ResolverConResolucionDeGerencia.PlazoDeLaOrdinariaEnCurso conflicto) {
            // 409 y no 422: la peticion esta bien formada; lo que no se cumple es un requisito del
            // estado del procedimiento, y quien opera lo arregla notificando o esperando.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, PeticionesDeSanciones.mensajeDe(conflicto));
        } catch (PlazosDeSancionesParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Las dos de parametros no son un fallo del servidor: es una cifra que todavia nadie
            // ha publicado, y con D-02a abierta es el estado normal. Ver la cabecera de la clase.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarDescargo.PapeletaSinNadaQueImpugnar
                | ResolverConResolucionDeGerencia.DescargoDeOtraPapeleta
                | IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    private DiligenciaResource diligenciar(
            String numero, PeticionDeNotificacionDeResolucion peticion) {

        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        try {
            NotificarResolucionDeGerencia.Diligencia diligencia =
                    notificar.registrar(
                            numero,
                            new NotificarResolucionDeGerencia.Peticion(
                                    PeticionesDeSanciones.fechaDe(
                                            peticion.fechaDeNotificacion(), "fechaDeNotificacion"),
                                    PeticionesDeSanciones.enumeradoDe(
                                            ModalidadDeNotificacion.class,
                                            peticion.modalidad(),
                                            "modalidad"),
                                    PeticionesDeSanciones.enumeradoDe(
                                            ResultadoDeNotificacion.class,
                                            peticion.resultado(),
                                            "resultado"),
                                    PeticionesDeSanciones.exigir(
                                            peticion.notificador(), "notificador"),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.direccion()),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.recibidoPor()),
                                    PeticionesDeSanciones.vacioEsNulo(
                                            peticion.documentoDelReceptor()),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.vinculo()),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.acuse())),
                            observacion);
            return DiligenciaResource.de(diligencia);
        } catch (NotificarResolucionDeGerencia.ResolucionInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        } catch (PlazosDeSancionesParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Igual que en `dictar`: el plazo de cumplimiento de la ordinaria sale del conjunto
            // sellado, y que falte no es un fallo del servidor. Ver la cabecera de la clase.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (NotificarResolucionDeGerencia.DiligenciaAnteriorALaResolucion
                | NotificarResolucionDeGerencia.SinDireccion
                | IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    private static FormatoDeDocumento formatoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return FormatoDeDocumento.PDF;
        }
        return PeticionesDeSanciones.enumeradoDe(FormatoDeDocumento.class, texto, "formato");
    }

    /**
     * El cuerpo de una resolución. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se dicta (regla 10, RNF-052)
     * @param papeleta el número de la papeleta
     * @param fecha el día de la resolución
     * @param nDeExpediente el descargo que resuelve, si resuelve alguno
     * @param sentidoDelFallo con qué sentido; obligatorio si hay recurso
     * @param efectoSobreLaMulta qué le pasa a la multa; obligatorio si hay recurso
     * @param sancionAccesoria la sanción no pecuniaria que se deriva, si la hay
     * @param sustento el fundamento de la resolución
     * @param proyectarDeudaAl a qué día se proyecta la deuda que se imprime
     * @param formato en qué formato sale el papel; por omisión PDF
     */
    public record PeticionDeResolucion(
            @Nullable String observacion,
            @Nullable String papeleta,
            @Nullable String fecha,
            @Nullable String nDeExpediente,
            @Nullable String sentidoDelFallo,
            @Nullable String efectoSobreLaMulta,
            @Nullable String sancionAccesoria,
            @Nullable String sustento,
            @Nullable String proyectarDeudaAl,
            @Nullable String formato) {}

    /**
     * El cuerpo de una diligencia. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se registra (regla 10, RNF-052)
     * @param fechaDeNotificacion cuándo se diligenció
     * @param modalidad cómo (art. 104 del TUO del Código Tributario)
     * @param resultado con qué resultado terminó
     * @param notificador quién la llevó
     * @param direccion dónde; sin ella, el domicilio fiscal vigente del obligado
     * @param recibidoPor quién recibió
     * @param documentoDelReceptor su documento
     * @param vinculo su vínculo con el administrado
     * @param acuse la constancia del cargo
     */
    public record PeticionDeNotificacionDeResolucion(
            @Nullable String observacion,
            @Nullable String fechaDeNotificacion,
            @Nullable String modalidad,
            @Nullable String resultado,
            @Nullable String notificador,
            @Nullable String direccion,
            @Nullable String recibidoPor,
            @Nullable String documentoDelReceptor,
            @Nullable String vinculo,
            @Nullable String acuse) {}

    /**
     * La resolución dictada.
     *
     * @param deuda lo que se debía, con el día al que está (regla 9, RNF-075); nulo si nada
     * @param dadoDeBaja lo que la resolución extinguió, con su fecha; nulo si no extinguió nada
     */
    public record ResolucionResource(
            long id,
            String numero,
            String tipo,
            String papeleta,
            LocalDate fecha,
            @Nullable String nDeExpediente,
            @Nullable String sentidoDelFallo,
            @Nullable String efectoSobreLaMulta,
            @Nullable String sancionAccesoria,
            @Nullable ImporteActualizado deuda,
            @Nullable ImporteActualizado dadoDeBaja,
            int asientosDeBaja,
            String formato,
            String resumen,
            String nombreDeArchivo) {

        static ResolucionResource de(ResolverConResolucionDeGerencia.ResolucionDictada dictada) {
            return new ResolucionResource(
                    dictada.resolucion().identificador(),
                    dictada.resolucion().numero(),
                    dictada.resolucion().tipo().name(),
                    dictada.emision().registro().referencia(),
                    dictada.resolucion().fecha(),
                    dictada.resolucion().descargoId() == null
                            ? null
                            : String.valueOf(dictada.resolucion().descargoId()),
                    dictada.resolucion().sentido() == null
                            ? null
                            : dictada.resolucion().sentido().name(),
                    dictada.resolucion().efecto() == null
                            ? null
                            : dictada.resolucion().efecto().name(),
                    dictada.resolucion().sancionAccesoria(),
                    dictada.deuda() == null
                            ? null
                            : new ImporteActualizado(dictada.deuda().total(), dictada.aLaFecha()),
                    dictada.baja() == null
                            ? null
                            : new ImporteActualizado(
                                    dictada.baja().importe(), dictada.baja().fecha()),
                    dictada.baja() == null ? 0 : dictada.baja().asientos(),
                    dictada.emision().registro().formato().name(),
                    dictada.emision().registro().resumen(),
                    dictada.emision().nombreDeArchivo());
        }
    }

    /** La diligencia registrada, con su acuse. */
    public record DiligenciaResource(
            long id,
            String resolucion,
            String numero,
            int intento,
            LocalDate fechaDeNotificacion,
            String modalidad,
            String resultado,
            String notificador,
            String direccion,
            @Nullable String recibidoPor,
            @Nullable String acuse,
            @Nullable LocalDate exigibleDesde,
            boolean abreElPlazoDeLaSancionadora) {

        static DiligenciaResource de(NotificarResolucionDeGerencia.Diligencia diligencia) {
            return new DiligenciaResource(
                    diligencia.notificacion().identificador(),
                    diligencia.resolucion().numero(),
                    diligencia.notificacion().numero(),
                    diligencia.notificacion().intento(),
                    diligencia.notificacion().fechaDeLaDiligencia(),
                    diligencia.notificacion().modalidad().name(),
                    diligencia.notificacion().resultado().name(),
                    diligencia.notificacion().notificador(),
                    diligencia.notificacion().direccion(),
                    diligencia.notificacion().receptor(),
                    diligencia.notificacion().acuse(),
                    diligencia.notificacion().exigibleDesde(),
                    diligencia.abreElPlazoDeLaSancionadora());
        }
    }
}
