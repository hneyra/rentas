package kamayuk.rentas.valores.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.valores.aplicacion.ConsultaDePrescripciones;
import kamayuk.rentas.valores.aplicacion.DeclararPrescripcion;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ClaseDeHecho;
import kamayuk.rentas.valores.dominio.CriterioDePrescripciones;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
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
 * Declaracion de prescripcion de la accion de cobro: {@code POST /api/v1/coactiva/prescripcion}
 * (RF-094).
 *
 * <p><b>Vive en {@code valores} aunque su ruta diga {@code coactiva}.</b> La ruta la fija el
 * contrato, que sale del menu del manual; el contexto acotado lo fija lo que el caso de uso toca, y
 * lo que este toca son valores: los marca {@code PRESCRITO}. No abre expedientes, no escribe actos
 * coactivos y no lee ninguno —eso es #40—.
 *
 * <p>Sin {@code PUT} ni {@code PATCH}: una resolucion no se edita.
 *
 * <h2>Y la relacion que las publica: {@code GET /api/v1/coactiva/prescripcion} (#674)</h2>
 *
 * <p>Misma ruta y otro verbo, como {@code permisos_de_grupo} sobre la suya. Es la lectura por la
 * que quien audita ve <b>que deuda quedo sin accion de cobro</b>, y la contrapartida de la decision
 * de #674: la prescripcion declarada no toca el libro, asi que la deuda sigue en la cartera y en
 * «lo cargado» del panel hasta que alguien la de de baja con RF-044. Si esa deuda no se puede
 * <i>ver</i> en ninguna parte, la decision se vuelve indistinguible de un descuido. El razonamiento
 * entero esta en {@link DeclararPrescripcion} y en {@code ActoDelLibro}.
 *
 * <p>Cuatro filtros y los cuatro se leen: {@code codContribuyente}, {@code tributo}, {@code
 * ejercicio} y {@code resultado}. Un codigo que no esta en el padron es <b>404 nombrandolo</b> y no
 * una pagina vacia —esa respuesta se lee como «esta persona no tiene ninguna declaracion», que es
 * lo contrario de lo que pasa—, mismo criterio que {@code ConsultaValoresController} desde #622. Un
 * {@code resultado} que no es ninguno de los tres es 422 diciendo cuales hay, y no un filtro
 * ignorado que devolveria la relacion entera (#544).
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El plazo del art. 43 y el desfase del inicio del computo del art. 44 salen del <b>conjunto
 * sellado</b> que rige a la fecha de la solicitud ({@link PlazosParametrizados}, regla 5). Que el
 * conjunto exista y no traiga la llave ({@code PlazoSinParametrizar}) ya estaba traducido desde
 * #192; que <b>no exista ningun conjunto sellado</b> ({@code EjercicioSinSellar}) no lo estaba, y
 * con D-02a abierta ese es el estado <i>normal</i> de todas las municipalidades: caia en el
 * {@code @ExceptionHandler(Exception.class)} de {@code ManejadorDeErrores} y salia como <b>500
 * {@code ERROR_INTERNO} con identificador de incidencia</b> —con lo que, ademas, cada intento
 * ensuciaba el registro de errores del servidor con lo que no es un error—.
 *
 * <p>El mensaje es el de la propia excepcion: nombra la llave —{@code
 * PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA}— o, cuando lo que falta es el conjunto entero y no hay
 * llave que nombrar, el <b>ejercicio</b>. Un fallo de verdad del servidor sigue siendo 500 con su
 * incidencia, y hay una prueba de contraste que lo mide. El razonamiento completo esta en la
 * cabecera de {@link ValoresController}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class PrescripcionController {

    /** Cronologico por presentacion, como se recorre un legajo de solicitudes. */
    private static final String ORDEN_POR_OMISION = "fechaPresentacion";

    private final DeclararPrescripcion declarar;
    private final ConsultaDePrescripciones consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public PrescripcionController(
            DeclararPrescripcion declarar,
            ConsultaDePrescripciones consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.declarar = declarar;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * La relacion de declaraciones (#674).
     *
     * <p>{@code @RequiereAcceso} va <b>en el metodo</b> y no se hereda de ninguna anotacion de
     * clase —esta clase no declara ninguna—, porque el privilegio no es el mismo: declarar una
     * prescripcion es {@link Privilegio#REGISTRO} y leerla es {@link Privilegio#LECTURA}. Que sean
     * la misma opcion del catalogo es lo correcto: es la misma pantalla.
     */
    @GetMapping("/prescripcion")
    @RequiereAcceso(acceso = "prescripcion", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PrescripcionEnListaResource> relacion(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String tributo,
            @RequestParam(required = false) @Nullable Integer ejercicio,
            @RequestParam(required = false) @Nullable String resultado,
            ParametrosDePaginacion parametros) {

        Paginacion paginacion = parametros.aPaginacion(ORDEN_POR_OMISION);

        Long contribuyenteId = null;
        String codigo = vacioAnulo(codContribuyente);
        if (codigo != null) {
            contribuyenteId =
                    consulta.contribuyentePorCodigo(codigo)
                            .orElseThrow(
                                    () ->
                                            new ProblemaDeNegocio(
                                                    CodigoDeError.NO_ENCONTRADO,
                                                    "No hay ningun contribuyente con el codigo '"
                                                            + codigo
                                                            + "'"))
                            .id();
        }

        CriterioDePrescripciones criterio =
                new CriterioDePrescripciones(
                        contribuyenteId,
                        vacioAnulo(tributo),
                        ejercicioFiltradoDe(ejercicio),
                        resultadoDe(resultado));

        return RespuestaPaginada.de(
                consulta.buscar(criterio, paginacion), PrescripcionEnListaResource::de);
    }

    @PostMapping("/prescripcion")
    @RequiereAcceso(acceso = "prescripcion", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<PrescripcionResource> declarar(
            @RequestBody PeticionDePrescripcion peticion) {

        ResumenDeContribuyente contribuyente = contribuyenteDe(peticion.codContribuyente());
        String tributo = exigir(peticion.tributo(), "tributo");
        Ejercicio desde = ejercicioDe(peticion.ejercicioDesde(), "ejercicioDesde");
        Ejercicio hasta = ejercicioDe(peticion.ejercicioHasta(), "ejercicioHasta");
        LocalDate presentacion =
                fechaOpcionalDe(peticion.fechaDePresentacion(), "fechaDePresentacion");
        CausalDePrescripcion causal = causalDe(peticion.plazoAplicable());
        List<HechoDelComputo> hechos = hechosDe(peticion.hechos());
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            Prescripcion guardada =
                    declarar.declarar(
                            contribuyente.id(),
                            tributo,
                            desde,
                            hasta,
                            presentacion == null ? LocalDate.now(reloj) : presentacion,
                            causal,
                            hechos,
                            vacioAnulo(peticion.nDeResolucion()),
                            observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(PrescripcionResource.de(guardada, contribuyente.codigo()));
        } catch (PlazosParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (DeclararPrescripcion.RangoInvertido | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private ResumenDeContribuyente contribuyenteDe(@Nullable String codigo) {
        String valor = exigir(codigo, "codContribuyente");
        return contribuyentes
                .porCodigo(valor)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con el codigo '"
                                                + valor
                                                + "'"));
    }

    private static Ejercicio ejercicioDe(@Nullable Integer valor, String campo) {
        if (valor == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        try {
            return new Ejercicio(valor);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static CausalDePrescripcion causalDe(@Nullable String texto) {
        String valor = exigir(texto, "plazoAplicable");
        try {
            return CausalDePrescripcion.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Causal de prescripcion desconocida: '"
                            + texto
                            + "'. Se admite DECLARACION_PRESENTADA, SIN_DECLARACION o"
                            + " AGENTE_RETENCION");
        }
    }

    private static List<HechoDelComputo> hechosDe(
            @Nullable List<PeticionDePrescripcion.PeticionDeHecho> peticiones) {
        if (peticiones == null || peticiones.isEmpty()) {
            return List.of();
        }
        List<HechoDelComputo> hechos = new ArrayList<>(peticiones.size());
        for (PeticionDePrescripcion.PeticionDeHecho hecho : peticiones) {
            ClaseDeHecho clase = claseDe(hecho.clase());
            LocalDate desde = fechaRequeridaDe(hecho.fechaDesde(), "hechos[].fechaDesde");
            LocalDate hasta = fechaOpcionalDe(hecho.fechaHasta(), "hechos[].fechaHasta");
            try {
                hechos.add(
                        new HechoDelComputo(
                                clase, exigir(hecho.causal(), "hechos[].causal"), desde, hasta));
            } catch (IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }
        return hechos;
    }

    private static ClaseDeHecho claseDe(@Nullable String texto) {
        String valor = exigir(texto, "hechos[].clase");
        try {
            return ClaseDeHecho.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Clase de hecho desconocida: '"
                            + texto
                            + "'. Se admite INTERRUPCION o SUSPENSION");
        }
    }

    private static @Nullable LocalDate fechaOpcionalDe(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no es una fecha ISO valida: '" + texto + "'");
        }
    }

    private static LocalDate fechaRequeridaDe(@Nullable String texto, String campo) {
        LocalDate fecha = fechaOpcionalDe(texto, campo);
        if (fecha == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return fecha;
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda declaracion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        return (texto == null || texto.isBlank()) ? null : texto.strip();
    }

    /**
     * El ejercicio del filtro, validado por {@link Ejercicio} antes de acotar nada.
     *
     * <p>Un ano fuera del rango del dominio devolveria la relacion vacia, y eso se lee como «no hay
     * ninguna declaracion de ese ano» cuando lo que pasa es que ese ano no existe para el sistema.
     */
    private static @Nullable Integer ejercicioFiltradoDe(@Nullable Integer valor) {
        if (valor == null) {
            return null;
        }
        try {
            return new Ejercicio(valor).valor();
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static @Nullable ResultadoDeLaSolicitud resultadoDe(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        try {
            return ResultadoDeLaSolicitud.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Resultado de solicitud desconocido: '"
                            + texto
                            + "'. Se admite PROCEDE, PROCEDE_EN_PARTE o NO_PROCEDE");
        }
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
