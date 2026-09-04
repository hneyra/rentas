package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarPrograma;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Programación de fiscalización: {@code GET} y {@code POST /api/v1/fiscalizacion/programas}
 * (RF-050, #45 y #431).
 *
 * <p>Reprogramar es registrar otro programa: no hay ruta de edición. El cuerpo es una <b>lista
 * blanca</b>, mismo patrón que {@code TransferenciaPredioController}.
 *
 * <p><b>{@code tipo} y {@code ejercicio} también viajan por la consulta</b> (#425). Son dos de los
 * cuatro filtros que la pantalla dibuja y el contrato los declara {@code in: query}; leerlos solo
 * del cuerpo dejaba la operación publicada y sin ninguna pantalla que pudiera llamarla —el 422
 * diría «Falta el campo 'tipo'» mientras la pantalla lo estaba mandando—. Se siguen aceptando en el
 * cuerpo, y ahí ganan: ver {@link FiltroDeLaConsulta}. {@code ejercicio} entra en ese reparto con
 * #481, que es cuando el programa empieza a guardarlo: hasta entonces el cuerpo no lo leía.
 *
 * <p><b>Y es por aquí por donde la detección programa</b> (#550, ADR-0023). «Omisos y
 * subvaluadores» no manda una lista de predios a ninguna parte: la muestra la <b>sortea</b> el
 * programa a partir de sus parámetros, y lo que esa pantalla aporta son sus dos filtros —sector y
 * condición—, que ya son dos de los cuatro. Por eso los lee {@link FiltroDeLaDeteccion} y no este
 * controlador por su cuenta: hasta #550 «Todos» del desplegable de sector se guardaba
 * <b>literal</b> en {@code sector_codigo} y el sorteo salía vacío en silencio, y «USO DISTINTO» se
 * aceptaba en la detección y se rechazaba aquí.
 *
 * <p><b>La lectura llegó después que la escritura</b> (#431), y hasta entonces un programa se podía
 * registrar y no se podía volver a encontrar: la pantalla {@code fisc_programa} declaraba el {@code
 * POST} como su único endpoint y las dos actas —que exigen el {@code programaId} de un programa ya
 * generado— no tenían ninguna fila real de la que sacarlo. Los dos verbos comparten ruta y opción
 * del catálogo, y el privilegio no: leer pide {@link Privilegio#LECTURA} y programar {@link
 * Privilegio#REGISTRO}, declarado en cada método —la anotación del método gana sobre la de la
 * clase—.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/programas")
@RequiereAcceso(acceso = "fisc_programa", privilegio = Privilegio.REGISTRO)
public class ProgramasController {

    /** El orden por omisión de la grilla: el «Nº de programa», que es como se buscan. */
    private static final String ORDEN_POR_OMISION = "codigo";

    private final RegistrarPrograma programas;
    private final ConsultaDeProgramas consulta;

    public ProgramasController(RegistrarPrograma programas, ConsultaDeProgramas consulta) {
        this.programas = programas;
        this.consulta = consulta;
    }

    /**
     * La grilla de programas (RF-050, #431).
     *
     * <p>Los dos filtros viajan <b>por la consulta</b> y no por el cuerpo: una búsqueda que no cabe
     * en la URL no se puede compartir ni recargar, y es lo que #399 dejó escrito. Los otros dos
     * desplegables de la pantalla —«Tipo» y «Estado»— no se declaran, y el motivo está en {@link
     * CriterioDeProgramas}: hablan un vocabulario que este dominio no tiene.
     */
    @GetMapping
    @RequiereAcceso(acceso = "fisc_programa", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ProgramaResource> programas(
            @RequestParam(required = false) @Nullable String nDePrograma,
            @RequestParam(required = false) @Nullable String ejercicio,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                consulta.buscar(
                        new CriterioDeProgramas(
                                vacioAnulo(nDePrograma), ejercicioOpcional(ejercicio)),
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ProgramaResource::de);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramaResource programar(
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestBody PeticionDePrograma peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            return ProgramaResource.de(
                    programas.registrar(
                            exigir(peticion.codigo(), "codigo"),
                            exigir(peticion.descripcion(), "descripcion"),
                            tipoDe(FiltroDeLaConsulta.primeroNoVacio(peticion.tipo(), tipo)),
                            fechaDe(peticion.fechaInicio(), "fechaInicio"),
                            fechaOpcionalDe(peticion.fechaFin()),
                            ejercicioDeLaMuestra(
                                    FiltroDeLaConsulta.primeroNoVacio(
                                            peticion.ejercicio(), ejercicio)),
                            FiltroDeLaDeteccion.sectorOpcional(peticion.sector()),
                            FiltroDeLaDeteccion.criterioDelPrograma(peticion.criterio()),
                            vacioAnulo(peticion.fiscalizador()),
                            observacion));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static @Nullable Integer ejercicioOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        try {
            return Integer.valueOf(valor);
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ejercicio va en cuatro digitos: '" + texto + "'");
        }
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static TipoDePrograma tipoDe(@Nullable String texto) {
        try {
            return TipoDePrograma.valueOf(exigir(texto, "tipo").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de programa desconocido: '" + texto + "'");
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

    private static LocalDate fechaDe(@Nullable String texto, String campo) {
        try {
            return LocalDate.parse(exigir(texto, campo).strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static @Nullable LocalDate fechaOpcionalDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return fechaDe(texto, "fechaFin");
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

    private static @Nullable Ejercicio ejercicioDeLaMuestra(@Nullable String texto) {
        Integer anio = ejercicioOpcional(texto);
        if (anio == null) {
            return null;
        }
        try {
            return new Ejercicio(anio);
        } catch (IllegalArgumentException fueraDeRango) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(fueraDeRango));
        }
    }

    /**
     * El cuerpo de una programación. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * <p>Los cuatro últimos son los parámetros con los que el programa sortea su muestra ({@code
     * V60}). Son opcionales en el cuerpo y no en el acto: un programa sin ellos se registra —los
     * anteriores a {@code V60} están así en la base— pero no puede generar muestra, y {@link
     * GenerarMuestra} falla nombrando el que falte.
     */
    public record PeticionDePrograma(
            @Nullable String observacion,
            @Nullable String codigo,
            @Nullable String descripcion,
            @Nullable String tipo,
            @Nullable String fechaInicio,
            @Nullable String fechaFin,
            @Nullable String ejercicio,
            @Nullable String sector,
            @Nullable String criterio,
            @Nullable String fiscalizador) {}
}
