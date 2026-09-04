package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
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
 * Las dos consultas de fiscalización que no son de liquidación: omisos y subvaluadores ({@code
 * fisc_omisos}, RF-055) y el estado de cuenta ({@code fisc_estado_cuenta}, RF-056).
 *
 * <p>Las dos son de <b>solo lectura</b>, y la detección lo es <b>del todo</b>: no escribe nada, ni
 * siquiera una marca en el padrón. Hasta #550 este javadoc decía que convertir la lista en un
 * programa «es la acción de la pantalla, que ya existe desde #45», y no era cierto: {@code POST
 * /fiscalizacion/programas} registra un programa con su código, su descripción, su tipo y su fecha
 * de inicio, y <b>no recibe predios</b>. ADR-0023 decidió que siga así —la muestra se sortea, no se
 * manda—: lo que esta pantalla aporta al programa son sus <b>filtros</b>, y por eso los dos que
 * también son parámetros del sorteo se leen en un solo sitio, {@link FiltroDeLaDeteccion}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion")
public class OmisosController {

    /** Las dos opciones del catálogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_OMISOS = "fisc_omisos";

    static final String ACCESO_ESTADO_DE_CUENTA = "fisc_estado_cuenta";

    /**
     * El orden por omision, con el nombre que la fila <b>publica</b> (#546).
     *
     * <p>Hasta este issue era {@code codigoRefCatastral} —el {@code camelCase} de la columna— y la
     * fila publica {@code codRefCatastral}: dos nombres para el mismo dato en la misma operacion, y
     * pedir por el que la fila ensena daba {@code 422 ORDEN_NO_ADMITIDO}.
     */
    private static final String ORDEN_POR_OMISION = "codRefCatastral";

    private final DeteccionDeOmisos deteccion;
    private final EstadoDeCuentaDeFiscalizacion estadoDeCuenta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public OmisosController(
            DeteccionDeOmisos deteccion,
            EstadoDeCuentaDeFiscalizacion estadoDeCuenta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.deteccion = deteccion;
        this.estadoDeCuenta = estadoDeCuenta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /** Omisos y subvaluadores del ejercicio (RF-055). */
    @GetMapping("/omisos")
    @RequiereAcceso(acceso = ACCESO_OMISOS, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<OmisoResource> omisos(
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String sector,
            @RequestParam(required = false) @Nullable String condicion,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);
        Pagina<FilaDeOmisos> pagina =
                deteccion.detectar(
                        ejercicioDe(ejercicio, hoy),
                        FiltroDeLaDeteccion.sectorOpcional(sector),
                        FiltroDeLaDeteccion.condicionOpcional(condicion),
                        hoy,
                        paginacion.aPaginacion(ORDEN_POR_OMISION));

        Map<Long, ResumenDeContribuyente> padron = padronDe(pagina);
        return RespuestaPaginada.de(pagina, fila -> OmisoResource.de(fila, padron));
    }

    /** El estado de cuenta de fiscalización de un contribuyente (RF-056). */
    @GetMapping("/estado-cuenta")
    @RequiereAcceso(acceso = ACCESO_ESTADO_DE_CUENTA, privilegio = Privilegio.LECTURA)
    public EstadoDeCuentaResource estadoDeCuenta(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String fechaDeConsulta) {

        String codigo = exigir(contribuyente, "contribuyente");
        ResumenDeContribuyente titular =
                contribuyentes
                        .porCodigo(codigo)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun contribuyente con el codigo '"
                                                        + codigo
                                                        + "'"));

        LocalDate aLaFecha =
                fechaOpcional(fechaDeConsulta, "fechaDeConsulta", LocalDate.now(reloj));
        return EstadoDeCuentaResource.de(
                estadoDeCuenta.de(titular.id(), aLaFecha), titular.codigo());
    }

    // ------------------------------------------------------------------

    /**
     * Los titulares de la pagina, resueltos a codigo y nombre en <b>una</b> consulta (#545).
     *
     * <p>Una fila puede tener varios y otra ninguno: los que no esten en el padron simplemente no
     * salen del mapa, y {@code OmisoResource} los publica con codigo y nombre nulos en vez de
     * ocultar la fila —un predio cuyo titular se dio de baja es justamente el que hay que revisar—.
     */
    private Map<Long, ResumenDeContribuyente> padronDe(Pagina<FilaDeOmisos> pagina) {
        Set<Long> ids = new HashSet<>();
        for (FilaDeOmisos fila : pagina.contenido()) {
            ids.addAll(fila.titulares());
        }
        return ids.isEmpty() ? Map.of() : contribuyentes.porIds(ids);
    }

    private static Ejercicio ejercicioDe(@Nullable String texto, LocalDate hoy) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return Ejercicio.de(hoy);
        }
        try {
            return new Ejercicio(Integer.parseInt(valor));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El ejercicio va en cuatro digitos: '" + texto + "'");
        }
    }

    private static LocalDate fechaOpcional(
            @Nullable String texto, String campo, LocalDate porOmision) {
        if (texto == null || texto.isBlank()) {
            return porOmision;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato ISO (2026-03-16): '" + texto + "'");
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
