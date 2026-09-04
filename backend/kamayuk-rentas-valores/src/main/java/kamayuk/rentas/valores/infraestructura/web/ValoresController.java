package kamayuk.rentas.valores.infraestructura.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores;
import kamayuk.rentas.valores.aplicacion.IniciarCorridaMasiva;
import kamayuk.rentas.valores.aplicacion.PasarACoactiva;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.aplicacion.RegistrarNotificacion;
import kamayuk.rentas.valores.aplicacion.RegistrarValor;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Todo lo que le pasa a un valor por HTTP: generacion individual y masiva, busqueda, notificacion y
 * pase a coactiva (RF-090 a RF-093 y RF-095).
 *
 * <p>Un valor emitido no se corrige, se anula (regla 4): este controlador no tiene ningun {@code
 * PUT} ni {@code PATCH}. Lo que cambia despues de emitirlo llega como un {@code POST} a un recurso
 * nuevo -una notificacion, un movimiento-, porque eso es lo que son: actos que se agregan.
 *
 * <p>{@link #generarMasivo} solo registra la etapa "criterio" (#38): la generacion en si -leer la
 * deuda de cada candidato y emitir su valor- corre en el perfil batch (ADR-0003), aparte de esta
 * peticion web, para que una corrida de miles de contribuyentes no compita con la caja por el mismo
 * proceso.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>La notificacion de un valor no se puede registrar sin el plazo de reclamacion que le
 * corresponde, y ese plazo sale del <b>conjunto sellado</b> que rige a la fecha de la diligencia
 * ({@link PlazosParametrizados}, regla 5). Que el conjunto exista y no traiga la llave ({@code
 * PlazoSinParametrizar}) ya estaba traducido desde #192; que <b>no exista ningun conjunto
 * sellado</b> ({@code EjercicioSinSellar}) no lo estaba, y con D-02a abierta ese es el estado
 * <i>normal</i> de todas las municipalidades: caia en el {@code @ExceptionHandler(Exception.class)}
 * de {@code ManejadorDeErrores} y salia como <b>500 {@code ERROR_INTERNO} con identificador de
 * incidencia</b>.
 *
 * <p>Son las dos consecuencias que #540 midio en Rentas y #547 en Tesoreria: la interfaz no puede
 * distinguir «falta publicar una cifra» —que se arregla publicandola— de «el servidor esta roto»
 * —que se arregla llamando a alguien—, y un cliente que reintenta un 500 reintenta para siempre; y
 * cada intento <b>escribia una incidencia de nivel ERROR en el registro</b>, o sea el registro de
 * errores del servidor lleno de lo que no es un error.
 *
 * <p>El mensaje es el de la propia excepcion: ya esta redactado en lenguaje del dominio y nombra la
 * llave —{@code PLAZO:NOTIFICACION_VALOR-OP}— o, cuando lo que falta es el conjunto entero y no hay
 * llave que nombrar, el <b>ejercicio</b>; sin tabla, sin restriccion y sin SQL (RNF-033).
 *
 * <p>Lo que <b>no</b> cambia: un fallo de verdad del servidor sigue siendo 500 con su incidencia.
 * Una traduccion demasiado ancha —capturar {@code RuntimeException}— es peor que el defecto que
 * arregla, y hay una prueba de contraste que lo mide con un plazo sellado ilegible.
 *
 * <p>La traduccion vive aqui y no en {@code ManejadorDeErrores} porque {@code kamayuk.rentas.web}
 * esta en {@code kamayuk-rentas-plataforma}, que no depende —ni debe— de {@code
 * kamayuk-rentas-parametros}, que es un contexto acotado; y porque la eleccion de codigo no es
 * uniforme en el sistema, asi que decidirla en un sitio unico decidiria tambien por catastro, que
 * traduce la misma excepcion a 404 a proposito.
 */
@RestController
@RequestMapping(Api.RAIZ + "/valores")
public class ValoresController {

    private static final String ORDEN_POR_OMISION = "fechaEmision";

    private final RegistrarValor registrar;
    private final ConsultaDeValores consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final IniciarCorridaMasiva iniciarMasivo;
    private final RegistrarNotificacion notificar;
    private final PasarACoactiva pasarACoactiva;

    public ValoresController(
            RegistrarValor registrar,
            ConsultaDeValores consulta,
            DirectorioDeContribuyentes contribuyentes,
            IniciarCorridaMasiva iniciarMasivo,
            RegistrarNotificacion notificar,
            PasarACoactiva pasarACoactiva) {
        this.registrar = registrar;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.iniciarMasivo = iniciarMasivo;
        this.notificar = notificar;
        this.pasarACoactiva = pasarACoactiva;
    }

    @PostMapping
    @RequiereAcceso(acceso = "valores_individual", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ValorResource> emitir(@RequestBody PeticionDeValor peticion) {
        TipoValor tipo = tipoDe(peticion.tipo());
        ResumenDeContribuyente contribuyente = contribuyenteDe(peticion.codContribuyente());
        List<SelectorDeObligacion> obligaciones = obligacionesDe(peticion.obligaciones());
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            Valor guardado = registrar.emitir(tipo, contribuyente.id(), obligaciones, observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ValorResource.de(guardado, contribuyente));
        } catch (RegistrarValor.SinObligaciones | RegistrarValor.ObligacionSinDeuda invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    @PostMapping("/masivo")
    @RequiereAcceso(acceso = "valores_masivo", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ValorMasivoResource> generarMasivo(
            @RequestBody PeticionDeValorMasivo peticion) {
        TipoValor tipo = tipoDe(peticion.tipo());
        Ejercicio ejercicioDesde =
                ejercicioRequeridoDe(peticion.ejercicioDesde(), "ejercicioDesde");
        Ejercicio ejercicioHasta =
                ejercicioRequeridoDe(peticion.ejercicioHasta(), "ejercicioHasta");
        LocalDate fechaCriterio = fechaOpcionalDe(peticion.fechaCriterio());
        String tributo = vacioAnulo(peticion.tributo());
        Observacion observacion = observacionDe(peticion.observacion());

        List<String> contribuyentes = peticion.contribuyentes();
        String archivoCsv = peticion.archivoCsv();
        boolean tieneSeleccion = contribuyentes != null && !contribuyentes.isEmpty();
        boolean tieneArchivo = archivoCsv != null && !archivoCsv.isBlank();
        if (tieneSeleccion == tieneArchivo) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Se necesita 'contribuyentes' (seleccion) o 'archivoCsv' (importacion), y solo"
                            + " uno de los dos");
        }

        try {
            ValorMasivo corrida =
                    tieneSeleccion
                            ? iniciarMasivo.porSeleccion(
                                    tipo,
                                    tributo,
                                    ejercicioDesde,
                                    ejercicioHasta,
                                    fechaCriterio,
                                    Objects.requireNonNull(contribuyentes),
                                    observacion)
                            : porImportacion(
                                    tipo,
                                    tributo,
                                    ejercicioDesde,
                                    ejercicioHasta,
                                    fechaCriterio,
                                    Objects.requireNonNull(archivoCsv),
                                    observacion);
            return ResponseEntity.status(HttpStatus.CREATED).body(ValorMasivoResource.de(corrida));
        } catch (IniciarCorridaMasiva.SinCandidatos vacio) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(vacio));
        } catch (IniciarCorridaMasiva.CandidatosInvalidos invalidos) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, mensajeDe(invalidos), invalidos.motivos());
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * Registra el acto de notificacion de un valor (RF-093).
     *
     * <p>La ruta lleva el numero del valor y el cuerpo, la diligencia. Desde cuando la deuda queda
     * exigible <b>no</b> viaja en el cuerpo: lo deriva el servidor del plazo parametrizado, porque
     * dejarlo entrar seria dejar que el cliente decidiera cuando puede empezar la cobranza
     * coactiva.
     *
     * <p><b>{@code notificador} y {@code resultado} tambien viajan por la consulta</b> (#425). Son
     * dos de los filtros que la pantalla dibuja y el contrato los declara {@code in: query};
     * leerlos solo del cuerpo dejaba la operacion publicada y sin ninguna pantalla que pudiera
     * llamarla. Se siguen aceptando en el cuerpo, y ahi ganan: ver {@link FiltroDeLaConsulta}.
     *
     * @param notificador quien llevo la diligencia
     * @param resultado NOTIFICADO, NO_UBICADO o RECHAZADO
     */
    @PostMapping("/{nro}/notificacion")
    @RequiereAcceso(acceso = "notificacion_valores", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<NotificacionResource> notificar(
            @PathVariable String nro,
            @RequestParam(required = false) @Nullable String notificador,
            @RequestParam(required = false) @Nullable String resultado,
            @RequestBody PeticionDeNotificacion peticion) {

        LocalDate fecha = fechaRequeridaDe(peticion.fechaDeNotificacion(), "fechaDeNotificacion");
        ModalidadDeNotificacion modalidad = modalidadDe(peticion.tipoDeNotificacion());
        ResultadoDeNotificacion resultadoDeLaDiligencia =
                resultadoDe(FiltroDeLaConsulta.primeroNoVacio(peticion.resultado(), resultado));
        String quienNotifico =
                exigir(
                        FiltroDeLaConsulta.primeroNoVacio(peticion.notificador(), notificador),
                        "notificador");
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            Notificacion guardada =
                    notificar.registrar(
                            exigir(nro, "nro"),
                            fecha,
                            modalidad,
                            resultadoDeLaDiligencia,
                            quienNotifico,
                            vacioAnulo(peticion.direccion()),
                            vacioAnulo(peticion.personaQueRecibe()),
                            vacioAnulo(peticion.documentoDeQuienRecibe()),
                            vacioAnulo(peticion.vinculo()),
                            vacioAnulo(peticion.acuse()),
                            observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(NotificacionResource.de(guardada, exigir(nro, "nro")));
        } catch (RegistrarNotificacion.ValorInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (PlazosParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // `EjercicioSinSellar` no es un fallo del servidor: es que nadie ha sellado todavia
            // el conjunto del ejercicio de la diligencia (D-02a). Ver la cabecera de la clase.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarNotificacion.DiligenciaAnteriorALaEmision
                | RegistrarNotificacion.SinDomicilio
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * Pasa un valor a coactiva (RF-095).
     *
     * <p>Idempotente: pedirlo dos veces devuelve el mismo movimiento, no dos. Lo garantiza la base
     * (V28), no una comprobacion previa —dos peticiones simultaneas pasarian las dos por cualquier
     * {@code if}—.
     */
    @PostMapping("/{numero}/movimientos")
    @RequiereAcceso(acceso = "pase_coactiva", privilegio = Privilegio.REGISTRO)
    public ResponseEntity<MovimientoResource> mover(
            @PathVariable String numero, @RequestBody PeticionDeMovimiento peticion) {

        TipoDeMovimiento tipo = tipoDeMovimientoDe(peticion.tipoDeMovimiento());
        if (tipo != TipoDeMovimiento.PCO) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "#39 registra el pase (PCO). "
                            + tipo.name()
                            + " es la respuesta de coactiva, y la escribe el modulo coactiva");
        }
        LocalDate fecha = fechaOpcionalDe(peticion.fechaDelMovimiento(), "fechaDelMovimiento");
        Observacion observacion = observacionDe(peticion.observacion());
        String valor = exigir(numero, "numero");

        try {
            MovimientoDeValor pase =
                    fecha == null
                            ? pasarACoactiva.pasar(valor, observacion)
                            : pasarACoactiva.pasar(valor, fecha, observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(MovimientoResource.de(pase, valor));
        } catch (PasarACoactiva.ValorInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (PasarACoactiva.ValorSinNotificar | PasarACoactiva.PlazoVigente invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    @GetMapping
    @RequiereAcceso(acceso = "valores_busqueda", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ValorResource> buscar(
            @RequestParam(required = false) @Nullable String nroDeValor,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String ejercicio,
            ParametrosDePaginacion paginacion) {

        Long contribuyenteId = null;
        if (codContribuyente != null && !codContribuyente.isBlank()) {
            Optional<ResumenDeContribuyente> encontrado =
                    contribuyentes.porCodigo(codContribuyente.strip());
            if (encontrado.isEmpty()) {
                // Un codigo que no existe no es una peticion mal formada: es un padron sin ese
                // contribuyente, igual que consulta_deuda (#25).
                return RespuestaPaginada.de(
                        Pagina.vacia(paginacion.aPaginacion(ORDEN_POR_OMISION)));
            }
            contribuyenteId = encontrado.get().id();
        }

        CriterioDeValor criterio =
                new CriterioDeValor(
                        vacioAnulo(nroDeValor),
                        contribuyenteId,
                        tipoOpcionalDe(tipo),
                        ejercicioDe(ejercicio));

        // La pagina y los nombres, en UNA transaccion: `valor` tiene RLS, y una consulta desde
        // el controlador corre sin el `SET LOCAL` que la politica necesita (#486).
        return RespuestaPaginada.de(
                consulta.emitidos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                fila -> ValorResource.de(fila.valor(), fila.contribuyente()));
    }

    // ------------------------------------------------------------------

    private static TipoValor tipoDe(@Nullable String texto) {
        String valor = exigir(texto, "tipo");
        try {
            return TipoValor.porCodigo(valor);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static @Nullable TipoValor tipoOpcionalDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return TipoValor.porCodigo(texto);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static @Nullable Integer ejercicioDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(texto.strip());
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'ejercicio' no es un numero: '" + texto + "'");
        }
    }

    private static Ejercicio ejercicioRequeridoDe(@Nullable Integer valor, String campo) {
        if (valor == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        try {
            return new Ejercicio(valor);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static @Nullable LocalDate fechaOpcionalDe(@Nullable String texto) {
        return fechaOpcionalDe(texto, "fechaCriterio");
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

    private static ModalidadDeNotificacion modalidadDe(@Nullable String texto) {
        String valor = exigir(texto, "tipoDeNotificacion");
        try {
            return ModalidadDeNotificacion.valueOf(valor.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Modalidad de notificacion desconocida: '"
                            + texto
                            + "'. Se admite PERSONAL, CEDULON, PUBLICACION, CORREO o NEGATIVA");
        }
    }

    private static ResultadoDeNotificacion resultadoDe(@Nullable String texto) {
        String valor = exigir(texto, "resultado");
        try {
            return ResultadoDeNotificacion.valueOf(valor.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Resultado desconocido: '"
                            + texto
                            + "'. Se admite NOTIFICADO, NO_UBICADO o RECHAZADO");
        }
    }

    private static TipoDeMovimiento tipoDeMovimientoDe(@Nullable String texto) {
        String valor = exigir(texto, "tipoDeMovimiento");
        try {
            return TipoDeMovimiento.porCodigo(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private ValorMasivo porImportacion(
            TipoValor tipo,
            @Nullable String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            @Nullable LocalDate fechaCriterio,
            String archivoCsvBase64,
            Observacion observacion) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(archivoCsvBase64.strip());
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El campo 'archivoCsv' no es base64 valido");
        }
        try (Reader lector =
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return iniciarMasivo.porImportacion(
                    tipo,
                    tributo,
                    ejercicioDesde,
                    ejercicioHasta,
                    fechaCriterio,
                    lector,
                    observacion);
        } catch (IOException fallo) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "No se pudo leer el archivo importado");
        }
    }

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

    private static List<SelectorDeObligacion> obligacionesDe(
            @Nullable List<PeticionDeValor.PeticionDeObligacion> obligaciones) {
        if (obligaciones == null || obligaciones.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El valor tiene que formalizar al menos una obligacion");
        }
        List<SelectorDeObligacion> selectores = new ArrayList<>(obligaciones.size());
        for (PeticionDeValor.PeticionDeObligacion obligacion : obligaciones) {
            String tributo = exigir(obligacion.tributo(), "obligaciones[].tributo");
            Integer ejercicio = obligacion.ejercicio();
            if (ejercicio == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION, "Falta el campo 'obligaciones[].ejercicio'");
            }
            try {
                selectores.add(
                        new SelectorDeObligacion(
                                tributo,
                                new Ejercicio(ejercicio),
                                obligacion.predioId(),
                                obligacion.vehiculoId()));
            } catch (IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }
        return selectores;
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda emision exige la observacion del usuario: sin ella no se guarda");
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

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
