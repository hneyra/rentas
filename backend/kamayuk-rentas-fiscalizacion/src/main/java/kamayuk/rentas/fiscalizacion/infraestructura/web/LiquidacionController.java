package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.CambiarEstadoDeLaLiquidacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.aplicacion.LiquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ReliquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La liquidación de fiscalización por HTTP: emisión, reliquidación, cambio de estado, la grilla de
 * resultados y el histórico del proceso (#49, RF-053, RF-056).
 *
 * <h2>Por qué aquí hay {@code PATCH} y no es una excepción a la regla 4</h2>
 *
 * <p>La ruta de estados <b>no actualiza ninguna fila</b>: <b>inserta</b> un movimiento en {@code
 * liquidacion_movimiento} —la cabecera ni siquiera admite {@code UPDATE} desde V39— y lo que cambia
 * es lo que se <b>deriva</b> de ese historial. Es exactamente el mismo caso que {@code
 * ExpedienteController} en coactiva (#40), y el verbo describe bien lo que el cliente ve.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion")
public class LiquidacionController {

    /** Las dos opciones del catálogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_RESULTADOS = "fisc_resultados";

    static final String ACCESO_HISTORICO = "fisc_historico";

    private static final String ORDEN_POR_OMISION = "numero";

    private final LiquidarFiscalizacion liquidar;
    private final ReliquidarFiscalizacion reliquidar;
    private final CambiarEstadoDeLaLiquidacion cambiarEstado;
    private final ConsultaDeLiquidaciones consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public LiquidacionController(
            LiquidarFiscalizacion liquidar,
            ReliquidarFiscalizacion reliquidar,
            CambiarEstadoDeLaLiquidacion cambiarEstado,
            ConsultaDeLiquidaciones consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.liquidar = liquidar;
        this.reliquidar = reliquidar;
        this.cambiarEstado = cambiarEstado;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * La grilla «Resultados y determinaciones» (RF-053).
     *
     * <p>Solo la <b>última</b> versión de cada acta: una reliquidación sustituye a la anterior, y
     * pintar las dos duplicaría la deuda que la pantalla suma. El histórico es el que las enseña
     * todas.
     */
    @GetMapping("/resultados")
    @RequiereAcceso(acceso = ACCESO_RESULTADOS, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<LiquidacionResource> resultados(
            @RequestParam(required = false) @Nullable String programa,
            @RequestParam(required = false) @Nullable String hallazgo,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDeLiquidaciones criterio =
                new CriterioDeLiquidaciones(
                        null,
                        enteroOpcional(programa, "programa"),
                        null,
                        null,
                        condicionOpcional(hallazgo),
                        estadoOpcional(estado),
                        true);

        Pagina<ConsultaDeLiquidaciones.LiquidacionConsultada> pagina =
                consulta.buscar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION));
        return RespuestaPaginada.de(pagina, LiquidacionResource::de);
    }

    /**
     * El «Histórico de fiscalización predial» (RF-056, AC 5 de #49).
     *
     * <p>Con {@code nLiquidacion}, devuelve el proceso <b>completo</b> del acta a la que pertenece
     * esa liquidación: todas sus versiones en orden, cada una con lo que cambió respecto de la
     * anterior. Sin él, la grilla paginada de todas las versiones.
     */
    @GetMapping("/predial/historico")
    @RequiereAcceso(acceso = ACCESO_HISTORICO, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<LiquidacionResource.VersionResource> historico(
            @RequestParam(required = false) @Nullable String nLiquidacion,
            @RequestParam(required = false) @Nullable String codCont,
            @RequestParam(required = false) @Nullable String nNotificacion,
            @RequestParam(required = false) @Nullable String contribuyente,
            ParametrosDePaginacion paginacion) {

        String numeroPedido = vacioAnulo(nLiquidacion);
        if (numeroPedido != null) {
            Liquidacion pedida =
                    consulta.porNumero(numeroPedido)
                            .orElseThrow(
                                    () ->
                                            new ProblemaDeNegocio(
                                                    CodigoDeError.NO_ENCONTRADO,
                                                    "No hay ninguna liquidacion con el numero '"
                                                            + numeroPedido
                                                            + "'"))
                            .liquidacion();
            List<LiquidacionResource.VersionResource> proceso = new ArrayList<>();
            for (ConsultaDeLiquidaciones.VersionDelProceso version :
                    consulta.historicoDeActa(pedida.actaId())) {
                proceso.add(LiquidacionResource.VersionResource.de(version));
            }
            return RespuestaPaginada.de(
                    new Pagina<>(proceso, 0, Math.max(proceso.size(), 1), proceso.size()));
        }

        CriterioDeLiquidaciones criterio =
                new CriterioDeLiquidaciones(
                        null,
                        null,
                        contribuyenteOpcional(codCont == null ? contribuyente : codCont),
                        vacioAnulo(nNotificacion),
                        null,
                        null,
                        false);

        Pagina<ConsultaDeLiquidaciones.LiquidacionConsultada> pagina =
                consulta.buscar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION));
        // Sin numero de liquidacion no hay una cadena que recorrer: cada fila es una version
        // suelta y su diferencia se pide abriendo el proceso. Devolverla aqui obligaria a leer el
        // historial completo de cada una de las veinte filas de la pagina.
        return RespuestaPaginada.de(
                pagina,
                fila ->
                        new LiquidacionResource.VersionResource(
                                LiquidacionResource.de(fila), List.of(), List.of()));
    }

    /** Emite la liquidación de un acta (RF-053). */
    @PostMapping("/liquidaciones")
    @RequiereAcceso(acceso = ACCESO_RESULTADOS, privilegio = Privilegio.REGISTRO)
    @ResponseStatus(HttpStatus.CREATED)
    public LiquidacionResource liquidar(@RequestBody PeticionDeLiquidacion peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));

        Liquidacion emitida;
        try {
            emitida =
                    liquidar.liquidar(
                            exigirEntero(peticion.actaId(), "actaId"),
                            ejercicioDe(peticion.periodoDesde(), "periodoDesde"),
                            ejercicioDe(peticion.periodoHasta(), "periodoHasta"),
                            tipoDe(peticion.tipoDeFiscalizacion()),
                            exigir(peticion.motivoDeterminante(), "motivoDeterminante"),
                            fecha,
                            observacion);
        } catch (LiquidarFiscalizacion.ActaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (LiquidarFiscalizacion.ActaYaLiquidada enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            // 422 y no 500: la peticion esta bien formada; lo que falta es que alguien selle el
            // conjunto de ese ejercicio, y el mensaje lo nombra.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinSellar);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return fichaDe(emitida.numero());
    }

    /** Reliquida: emite otra versión que referencia la anterior y explica la diferencia (AC 2). */
    @PostMapping("/liquidaciones/{numero}/reliquidaciones")
    @RequiereAcceso(acceso = ACCESO_RESULTADOS, privilegio = Privilegio.REGISTRO)
    @ResponseStatus(HttpStatus.CREATED)
    public LiquidacionResource.VersionResource reliquidar(
            @PathVariable String numero, @RequestBody PeticionDeReliquidacion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));

        List<ReliquidarFiscalizacion.CorreccionDeLinea> correcciones = new ArrayList<>();
        for (CorreccionEnLaPeticion correccion :
                peticion.correcciones() == null
                        ? List.<CorreccionEnLaPeticion>of()
                        : peticion.correcciones()) {
            correcciones.add(
                    new ReliquidarFiscalizacion.CorreccionDeLinea(
                            ejercicioDe(correccion.ejercicio(), "ejercicio"),
                            areaOpcional(correccion.areaDeclarada(), "areaDeclarada"),
                            areaOpcional(correccion.areaHallada(), "areaHallada"),
                            vacioAnulo(correccion.usoDeclarado()),
                            vacioAnulo(correccion.usoHallado())));
        }

        ReliquidarFiscalizacion.Resultado resultado;
        try {
            resultado =
                    reliquidar.reliquidar(
                            numero,
                            ejercicioDe(peticion.periodoDesde(), "periodoDesde"),
                            ejercicioDe(peticion.periodoHasta(), "periodoHasta"),
                            tipoDe(peticion.tipoDeFiscalizacion()),
                            exigir(peticion.motivoDeterminante(), "motivoDeterminante"),
                            correcciones,
                            fecha,
                            observacion);
        } catch (ReliquidarFiscalizacion.LiquidacionInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (ReliquidarFiscalizacion.NoEsLaUltimaVersion enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (ReliquidarFiscalizacion.EjercicioSinLineaAnterior
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return new LiquidacionResource.VersionResource(
                fichaDe(resultado.liquidacion().numero()),
                resultado.diferencia().cambios().stream()
                        .map(
                                cambio ->
                                        new LiquidacionResource.CambioResource(
                                                cambio.concepto(),
                                                cambio.antes(),
                                                cambio.despues()))
                        .toList(),
                resultado.diferencia().importesSinCifra());
    }

    /** Mueve la liquidación de estado agregando un movimiento (RF-056). */
    @PatchMapping("/liquidaciones/{numero}/estados")
    @RequiereAcceso(acceso = ACCESO_HISTORICO, privilegio = Privilegio.MODIFICACION)
    public LiquidacionResource cambiarEstado(
            @PathVariable String numero, @RequestBody PeticionDeEstadoDeLiquidacion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        try {
            cambiarEstado.cambiar(
                    numero,
                    estadoDe(peticion.nuevoEstado()),
                    fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj)),
                    exigir(peticion.motivo(), "motivo"),
                    observacion);
        } catch (CambiarEstadoDeLaLiquidacion.LiquidacionInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CambiarEstadoDeLaLiquidacion.LiquidacionAnulada
                | CambiarEstadoDeLaLiquidacion.SinCambio enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
        return fichaDe(numero);
    }

    // ------------------------------------------------------------------

    private LiquidacionResource fichaDe(String numero) {
        return LiquidacionResource.de(
                consulta.porNumero(numero)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ninguna liquidacion con el numero '"
                                                        + numero
                                                        + "'")));
    }

    private @Nullable Long contribuyenteOpcional(@Nullable String codigo) {
        String valor = vacioAnulo(codigo);
        if (valor == null) {
            return null;
        }
        // Un codigo que no existe deja el filtro sin candidatos, y eso es una lista vacia, no un
        // 404: la grilla admite que se teclee cualquier cosa en su caja de filtro.
        return contribuyentes.porCodigo(valor).map(ResumenDeContribuyente::id).orElse(-1L);
    }

    private static @Nullable Long enteroOpcional(@Nullable String texto, String campo) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor)) {
            return null;
        }
        try {
            return Long.valueOf(valor);
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va como numero entero: '" + texto + "'");
        }
    }

    private static long exigirEntero(@Nullable String texto, String campo) {
        Long valor = enteroOpcional(exigir(texto, campo), campo);
        if (valor == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor;
    }

    private static @Nullable CondicionFiscalizada condicionOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor) || "TODAS".equalsIgnoreCase(valor)) {
            return null;
        }
        try {
            return CondicionFiscalizada.porNombre(valor);
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocida));
        }
    }

    private static @Nullable EstadoDeLiquidacion estadoOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor) || "TODAS".equalsIgnoreCase(valor)) {
            return null;
        }
        try {
            return EstadoDeLiquidacion.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static EstadoDeLiquidacion estadoDe(@Nullable String texto) {
        try {
            return EstadoDeLiquidacion.porNombre(exigir(texto, "nuevoEstado"));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static TipoDeFiscalizacion tipoDe(@Nullable String texto) {
        try {
            return TipoDeFiscalizacion.porNombre(exigir(texto, "tipoDeFiscalizacion"));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static Ejercicio ejercicioDe(@Nullable String texto, String campo) {
        String valor = exigir(texto, campo);
        try {
            return new Ejercicio(Integer.parseInt(valor));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' es un ejercicio de cuatro digitos: '" + texto + "'");
        }
    }

    private static @Nullable AreaM2 areaOpcional(@Nullable String texto, String campo) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        try {
            return AreaM2.de(valor);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' es un area en metros cuadrados: '" + texto + "'");
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

    private static Observacion observacionDe(@Nullable String texto) {
        try {
            return Observacion.de(exigir(texto, "observacion"));
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
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La operacion no se pudo completar" : mensaje;
    }

    /** El cuerpo de una liquidación. <b>Lista blanca</b>: lo que no está aquí no entra. */
    public record PeticionDeLiquidacion(
            @Nullable String observacion,
            @Nullable String actaId,
            @Nullable String periodoDesde,
            @Nullable String periodoHasta,
            @Nullable String tipoDeFiscalizacion,
            @Nullable String motivoDeterminante,
            @Nullable String usoHallado,
            @Nullable String fecha) {}

    /** El cuerpo de una reliquidación. <b>Lista blanca</b>. */
    public record PeticionDeReliquidacion(
            @Nullable String observacion,
            @Nullable String periodoDesde,
            @Nullable String periodoHasta,
            @Nullable String tipoDeFiscalizacion,
            @Nullable String motivoDeterminante,
            @Nullable List<CorreccionEnLaPeticion> correcciones,
            @Nullable String fecha) {}

    /** Lo que se corrige de una línea. Lo que no llega se conserva de la versión anterior. */
    public record CorreccionEnLaPeticion(
            @Nullable String ejercicio,
            @Nullable String areaDeclarada,
            @Nullable String areaHallada,
            @Nullable String usoDeclarado,
            @Nullable String usoHallado) {}

    /** El cuerpo de un cambio de estado. <b>Lista blanca</b>. */
    public record PeticionDeEstadoDeLiquidacion(
            @Nullable String observacion,
            @Nullable String nuevoEstado,
            @Nullable String motivo,
            @Nullable String fecha) {}
}
