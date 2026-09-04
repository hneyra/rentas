package kamayuk.rentas.licencias.infraestructura.web;

import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.MantenerCatalogoCiiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCiiu;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo CIIU de giros por HTTP: consulta y extension (RF-112).
 *
 * <p>Una sola opcion del catalogo, {@code ciiu}, y sus dos acciones de pantalla —«Nuevo» y
 * «Guardar»— que en HTTP son un {@code GET} y un {@code POST}. No hay {@code PUT}: en esta oleada
 * el catalogo se extiende, no se corrige. Editar un giro ya citado por licencias emitidas cambia lo
 * que dice el papel de esas licencias sin dejar traza, y decidir que se puede tocar y que no —el
 * codigo nunca, la descripcion quiza, el riesgo de la ITSE con que efecto sobre las licencias
 * vigentes— es una decision que #44 no toma. La columna {@code activo} y el privilegio de {@code
 * UPDATE} que V37 conserva estan para cuando se tome.
 */
@RestController
@RequestMapping(Api.RAIZ + "/licencias")
public class CiiuController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_CIIU = "ciiu";

    private static final String ORDEN_POR_OMISION = "codigo";

    private final MantenerCatalogoCiiu catalogo;

    public CiiuController(MantenerCatalogoCiiu catalogo) {
        this.catalogo = catalogo;
    }

    /** El catalogo, paginado (RF-112). */
    @GetMapping("/ciiu")
    @RequiereAcceso(acceso = ACCESO_CIIU, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<CiiuResource> listar(
            @RequestParam(required = false) @Nullable String codigoCiiu,
            @RequestParam(required = false) @Nullable String descripcion,
            @RequestParam(required = false) @Nullable String seccion,
            ParametrosDePaginacion paginacion) {

        CriterioDeCiiu criterio = new CriterioDeCiiu(codigoCiiu, descripcion, seccionDe(seccion));

        return RespuestaPaginada.de(
                catalogo.listar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CiiuResource::de);
    }

    /** Agrega un giro al catalogo (RF-112). */
    @PostMapping("/ciiu")
    @RequiereAcceso(acceso = ACCESO_CIIU, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<CiiuResource> registrar(@RequestBody PeticionDeCiiu peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        MantenerCatalogoCiiu.Alta alta;
        try {
            alta =
                    new MantenerCatalogoCiiu.Alta(
                            exigido(peticion.codigo(), "codigo"),
                            exigido(peticion.descripcion(), "descripcion"),
                            vacioAnulo(peticion.seccion()),
                            riesgoDe(peticion.riesgoItse()),
                            vacioAnulo(peticion.zonificacionCompatible()),
                            Boolean.TRUE.equals(peticion.requiereSectorial()));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(CiiuResource.de(catalogo.registrar(alta, observacion)));
        } catch (CiiuRepository.CodigoDuplicado repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    // ------------------------------------------------------------------

    /**
     * La seccion, admitiendo lo que el desplegable de la pantalla manda.
     *
     * <p>La pantalla ofrece {@code "G — COMERCIO"} y {@code "Todas"}. Lo primero se recorta a su
     * letra; lo segundo es «sin filtro» y no una seccion llamada «Todas», que es lo que un filtro
     * literal buscaria —devolviendo cero filas sobre un catalogo que si las tiene—.
     */
    private static @Nullable String seccionDe(@Nullable String seccion) {
        String texto = seccion == null ? "" : seccion.strip();
        if (texto.isEmpty() || texto.equalsIgnoreCase("Todas")) {
            return null;
        }
        return texto.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static @Nullable RiesgoItse riesgoDe(@Nullable String riesgo) {
        String texto = riesgo == null ? "" : riesgo.strip();
        if (texto.isEmpty()) {
            return null;
        }
        // El desplegable de la pantalla manda «RIESGO BAJO»; la enumeracion es BAJO.
        String limpio = texto.toUpperCase(Locale.ROOT).replaceFirst("^RIESGO\\s+", "");
        try {
            return RiesgoItse.porNombre(limpio);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El nivel de riesgo va entre BAJO, MEDIO, ALTO y MUY_ALTO: '" + riesgo + "'");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        try {
            return Observacion.de(texto == null ? "" : texto);
        } catch (IllegalArgumentException sinObservacion) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion de datos exige la observacion del usuario (regla 10,"
                            + " RNF-052): "
                            + mensajeDe(sinObservacion));
        }
    }

    private static String exigido(@Nullable String valor, String campo) {
        String texto = valor == null ? "" : valor.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio '" + campo + "'");
        }
        return texto;
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    /**
     * El texto de una excepcion, sin poder ser nulo.
     *
     * <p>{@code getMessage()} es {@code @Nullable} y NullAway lo exige: una respuesta de error con
     * el cuerpo en blanco es peor que una con un texto generico, porque no dice ni siquiera que
     * clase de problema hubo.
     */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La peticion no se pudo completar" : mensaje;
    }
}
