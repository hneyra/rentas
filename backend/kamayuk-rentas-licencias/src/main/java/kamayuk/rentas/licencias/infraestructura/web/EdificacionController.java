package kamayuk.rentas.licencias.infraestructura.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.CompletarSeccionDelFue;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.PresentarFue;
import kamayuk.rentas.licencias.aplicacion.RevalidarLicenciaDeEdificacion;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstadoDelFue;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.ModalidadDeAprobacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.PartidaDeEdificacion;
import kamayuk.rentas.licencias.dominio.RepresentanteLegal;
import kamayuk.rentas.licencias.dominio.RevisionDelProyecto;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeObra;
import kamayuk.rentas.licencias.dominio.TipoDeProfesional;
import kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
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
 * El Formulario Unico de Edificaciones por HTTP: presentacion, secciones, emision, revalidacion y
 * el reporte general (#48, RF-113, RF-115).
 *
 * <h2>Dos opciones del catalogo, dos accesos</h2>
 *
 * <p>{@code fue_edificacion} lee y registra; {@code edificacion_reporte} imprime. Sin
 * {@code @RequiereAcceso} el guardia <b>niega</b>, y la regla de arquitectura rompe el build; las
 * dos cosas juntas hacen que el olvido no se pueda convertir en una puerta abierta.
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Un FUE no se corrige: sus secciones se <b>versionan</b> —cada {@code POST} sobre {@code
 * /secciones} guarda la siguiente, y la anterior queda entera— y la cabecera no admite {@code
 * UPDATE} desde V43. Lo que le pasa al expediente llega como recurso nuevo: {@code /licencia},
 * {@code /revalidacion}.
 *
 * <h2>El expediente, en la ruta</h2>
 *
 * <p>{@code {expediente}} es el numero de expediente con que el FUE se presento, no el
 * identificador interno de la fila —que ninguna pantalla conoce— ni el numero de la licencia, que
 * puede no existir todavia.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El concepto del TUPA con que se comprueba el derecho de la licencia y el de la revalidacion
 * salen del <b>conjunto sellado</b> que rige a la fecha del acto ({@link
 * DerechosDeTramiteParametrizados}, regla 5). La llave que falta dentro del conjunto ya estaba
 * traducida desde #48; que <b>no exista ningun conjunto sellado</b> ({@code EjercicioSinSellar})
 * salia como <b>500 {@code ERROR_INTERNO} con identificador de incidencia</b>, y con D-02a abierta
 * ese es el estado <i>normal</i> de todas las municipalidades. El razonamiento completo esta en la
 * cabecera de {@link LicenciaController}.
 *
 * <p><b>La valorizacion de la obra no entra en esto</b>, y por diseño: {@link
 * kamayuk.rentas.licencias.aplicacion.ValorizacionDelFue} captura sus dos excepciones dentro y
 * devuelve «—» con la llave que falta (#48), asi que el cuadro de valores unitarios sin cargar no
 * impide emitir la licencia ni asoma por el borde.
 */
@RestController
@RequestMapping(Api.RAIZ + "/licencias/edificacion")
public class EdificacionController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_FUE = "fue_edificacion";

    static final String ACCESO_REPORTE = "edificacion_reporte";

    private static final String ORDEN_POR_OMISION = "expediente";

    private final ConsultaDeFue consulta;
    private final PresentarFue presentar;
    private final CompletarSeccionDelFue completar;
    private final EmitirLicenciaDeEdificacion emitir;
    private final RevalidarLicenciaDeEdificacion revalidar;
    private final Clock reloj;

    public EdificacionController(
            ConsultaDeFue consulta,
            PresentarFue presentar,
            CompletarSeccionDelFue completar,
            EmitirLicenciaDeEdificacion emitir,
            RevalidarLicenciaDeEdificacion revalidar,
            Clock reloj) {
        this.consulta = consulta;
        this.presentar = presentar;
        this.completar = completar;
        this.emitir = emitir;
        this.revalidar = revalidar;
        this.reloj = reloj;
    }

    /**
     * La grilla del FUE, paginada, con el estado de cada fila derivado a hoy (RF-113).
     *
     * <p>Con {@code nroExpediente} o {@code nroLicencia}, la fila trae ademas sus cinco secciones,
     * su historial, sus vigencias y su valorizacion: es la ficha que la pantalla dibuja al abrir un
     * expediente. Sin ellos, la fila es la que la grilla pinta y nada mas —una pagina de veinte no
     * puede costar veinte lecturas de detalle, y menos veinte valorizaciones—.
     */
    @GetMapping
    @RequiereAcceso(acceso = ACCESO_FUE, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<FueResource> listar(
            @RequestParam(required = false) @Nullable String nroExpediente,
            @RequestParam(required = false) @Nullable String nroLicencia,
            @RequestParam(required = false) @Nullable String nombreContribuyente,
            @RequestParam(required = false) @Nullable String lugarMz,
            @RequestParam(required = false) @Nullable String lugarLt,
            @RequestParam(required = false) @Nullable String tipoTramite,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);

        String expedienteBuscado = vacioAnulo(nroExpediente);
        if (expedienteBuscado != null) {
            return unaFicha(consulta.porExpediente(expedienteBuscado, hoy), paginacion);
        }
        String licenciaBuscada = vacioAnulo(nroLicencia);
        if (licenciaBuscada != null) {
            return unaFicha(consulta.porNumeroDeLicencia(licenciaBuscada, hoy), paginacion);
        }

        CriterioDeFue criterio =
                new CriterioDeFue(
                        null,
                        null,
                        lugarMz,
                        lugarLt,
                        tramiteOpcional(tipoTramite),
                        null,
                        null,
                        null,
                        null);

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio,
                        nombreContribuyente,
                        null,
                        hoy,
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                FueResource::de);
    }

    /**
     * El reporte general de licencias de edificacion (RF-115).
     *
     * <p>Es la unica salida con importes del modulo, y por eso cada fila lleva su fecha: el valor
     * de obra sale del cuadro de valores unitarios que rigio la fecha de corte, y sin ella la misma
     * hoja impresa el anio que viene podria decir otra cosa (regla 9, RNF-075).
     */
    @GetMapping("/reportes/general")
    @RequiereAcceso(acceso = ACCESO_REPORTE, privilegio = Privilegio.IMPRESION)
    public RespuestaPaginada<ReporteDeEdificacionResource> reporte(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String modalidad,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        LocalDate hastaFecha = fechaOpcional(hasta, "hasta");
        // La fecha de corte es el extremo del rango si lo hay: un reporte «hasta el 31 de marzo»
        // tiene que derivar el estado de cada licencia a ese dia, no al de hoy.
        LocalDate corte = hastaFecha == null ? LocalDate.now(reloj) : hastaFecha;

        CriterioDeFue criterio;
        try {
            criterio =
                    new CriterioDeFue(
                            null,
                            null,
                            null,
                            null,
                            null,
                            modalidadOpcional(modalidad),
                            fechaOpcional(desde, "desde"),
                            hastaFecha,
                            null);
        } catch (IllegalArgumentException rangoInvalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(rangoInvalido));
        }

        return RespuestaPaginada.de(
                consulta.reporte(
                        criterio,
                        null,
                        estadoOpcional(estado),
                        corte,
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ReporteDeEdificacionResource::de);
    }

    /** Presenta un FUE: da de alta el expediente (AC 1). */
    @PostMapping
    @RequiereAcceso(acceso = ACCESO_FUE, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<FueResource> presentar(@RequestBody PeticionDeFue peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate declaracion = fechaOhoy(peticion.fechaDeclaracion(), "fechaDeclaracion");

        PresentarFue.Solicitud solicitud;
        try {
            solicitud =
                    new PresentarFue.Solicitud(
                            exigido(peticion.nroExpediente(), "nroExpediente"),
                            declaracion,
                            exigido(peticion.codContribuyente(), "codContribuyente"),
                            peticion.predioId(),
                            tramiteDe(peticion.tipoTramite()),
                            obraDe(peticion.obra()),
                            modalidadDe(peticion.modalidadAprobacion()),
                            revisionOpcional(peticion.revision()),
                            vacioAnulo(peticion.nroExpedienteAnterior()),
                            vacioAnulo(peticion.nroLicenciaAnterior()),
                            Boolean.TRUE.equals(peticion.solicitanteEsPropietario()),
                            representanteDe(peticion));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        try {
            presentar.presentar(solicitud, observacion);
        } catch (PresentarFue.SolicitanteDesconocido noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (PresentarFue.LicenciaOriginalInexistente sinOriginal) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinOriginal));
        } catch (FueRepository.ExpedienteDuplicado repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        FueResource.de(
                                consulta.porExpediente(solicitud.expediente(), declaracion)
                                        .orElseThrow()));
    }

    /**
     * Completa una seccion del FUE (AC 1).
     *
     * <p>Una ruta para las cinco, discriminada por {@code seccion}. La respuesta es la ficha
     * completa, que es lo que la pantalla necesita para saber que le sigue faltando: devolver solo
     * la seccion guardada obligaria a una segunda peticion en cada visita del administrado.
     */
    @PostMapping("/{expediente}/secciones")
    @RequiereAcceso(acceso = ACCESO_FUE, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<FueResource> completarSeccion(
            @PathVariable String expediente, @RequestBody PeticionDeSeccionDelFue peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        SeccionDelFue seccion = seccionDe(peticion.seccion());

        try {
            switch (seccion) {
                case TERRENO ->
                        completar.completarTerreno(expediente, terrenoDe(peticion), observacion);
                case PROYECTO ->
                        completar.completarProyecto(expediente, proyectoDe(peticion), observacion);
                case VALORIZACION ->
                        completar.completarValorizacion(
                                expediente, valorizacionDe(peticion), observacion);
                case PROFESIONALES ->
                        completar.completarProfesionales(
                                expediente, profesionalesDe(peticion), observacion);
                case DOCUMENTOS ->
                        completar.completarDocumentos(
                                expediente, documentosDe(peticion), observacion);
                // Inalcanzable hoy —las cinco ramas cubren la enumeracion entera— y aqui
                // porque Checkstyle lo exige. Vale la pena: el dia que se agregue una seccion
                // sexta y alguien olvide su rama, esto responde «no se sabe que hacer con
                // ella» en vez de guardar nada y devolver 201.
                default ->
                        throw new ProblemaDeNegocio(
                                CodigoDeError.VALIDACION,
                                "La seccion «"
                                        + seccion.etiqueta()
                                        + "» no se sabe completar todavia");
            }
        } catch (CompletarSeccionDelFue.ExpedienteInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (CompletarSeccionDelFue.ExpedienteYaEmitido yaEmitido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaEmitido));
        } catch (CompletarSeccionDelFue.SeccionVacia
                | CompletarSeccionDelFue.ProfesionalRepetido
                | IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        FueResource.de(
                                consulta.porExpediente(expediente, LocalDate.now(reloj))
                                        .orElseThrow()));
    }

    /** Emite la licencia de edificacion del expediente (AC 1, AC 5). */
    @PostMapping("/{expediente}/licencia")
    @RequiereAcceso(acceso = ACCESO_FUE, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeEdificacionResource> emitir(
            @PathVariable String expediente,
            @RequestBody PeticionDeLicenciaDeEdificacion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        LocalDate emisionEl = fechaOhoy(peticion.fechaDeEmision(), "fechaDeEmision");
        LocalDate vigenciaHasta = exigidaLaFecha(peticion.vigenciaHasta(), "vigenciaHasta");

        EmitirLicenciaDeEdificacion.LicenciaEmitida emitida;
        try {
            emitida =
                    emitir.emitir(
                            expediente,
                            emisionEl,
                            vigenciaHasta,
                            exigido(peticion.nDeRecibo(), "nDeRecibo"),
                            formato,
                            observacion);
        } catch (EmitirLicenciaDeEdificacion.ExpedienteInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (EmitirLicenciaDeEdificacion.YaEstabaEmitida
                | MovimientoDeEdificacionRepository.YaEstabaEmitida
                | MovimientoDeEdificacionRepository.NumeroDeLicenciaDuplicado carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        } catch (EmitirLicenciaDeEdificacion.SeccionesIncompletas
                | EmitirLicenciaDeEdificacion.TramiteQueNoOtorgaLicencia
                | EmitirLicenciaDeEdificacion.AnteriorALaDeclaracion invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        } catch (ComprobacionDelDerecho.DerechoNoPagado sinPagar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinPagar));
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinParametro) {
            // 422 y no 500: la peticion esta bien y el sistema tampoco esta roto. Lo que falta es
            // un dato de configuracion, y quien opera tiene que enterarse de cual.
            // `EjercicioSinSellar` es el mismo caso y hasta #562 salia como 500 con incidencia.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinParametro);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ActoDeEdificacionResource.de(emitida));
    }

    /** Revalida la licencia que este expediente de revalidacion nombra (AC 4). */
    @PostMapping("/{expediente}/revalidacion")
    @RequiereAcceso(acceso = ACCESO_FUE, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeEdificacionResource> revalidar(
            @PathVariable String expediente, @RequestBody PeticionDeRevalidacion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        LocalDate fecha = fechaOhoy(peticion.fecha(), "fecha");
        LocalDate hasta = exigidaLaFecha(peticion.nuevaVigenciaHasta(), "nuevaVigenciaHasta");

        RevalidarLicenciaDeEdificacion.Revalidacion revalidacion;
        try {
            revalidacion =
                    revalidar.revalidar(
                            expediente,
                            fecha,
                            hasta,
                            exigido(peticion.nDeRecibo(), "nDeRecibo"),
                            formato,
                            observacion);
        } catch (EmitirLicenciaDeEdificacion.ExpedienteInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (RevalidarLicenciaDeEdificacion.NoEsUnaRevalidacion
                | RevalidarLicenciaDeEdificacion.OriginalSinLicencia
                | RevalidarLicenciaDeEdificacion.ProrrogaQueNoProrroga invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        } catch (ComprobacionDelDerecho.DerechoNoPagado sinPagar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinPagar));
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinParametro) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinParametro);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        // Las dos vigencias, la original y la nueva: es el AC 4 leible desde el JSON.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ActoDeEdificacionResource.de(
                                revalidacion,
                                consulta.vigenciasDe(revalidacion.original().identificador())));
    }

    // ------------------------------------------------------------------

    private RespuestaPaginada<FueResource> unaFicha(
            java.util.Optional<ConsultaDeFue.FichaDelFue> ficha, ParametrosDePaginacion pedida) {
        return ficha.map(
                        encontrada ->
                                RespuestaPaginada.de(
                                        Pagina.de(
                                                List.of(FueResource.de(encontrada)),
                                                pedida.aPaginacion(ORDEN_POR_OMISION),
                                                1)))
                .orElseGet(
                        () ->
                                RespuestaPaginada.de(
                                        Pagina.vacia(pedida.aPaginacion(ORDEN_POR_OMISION))));
    }

    private static CompletarSeccionDelFue.Terreno terrenoDe(PeticionDeSeccionDelFue peticion) {
        return new CompletarSeccionDelFue.Terreno(
                vacioAnulo(peticion.codCatastral()),
                exigido(peticion.direccion(), "direccion"),
                vacioAnulo(peticion.mz()),
                vacioAnulo(peticion.lt()),
                areaDe(peticion.areaDelTerrenoM(), "areaDelTerrenoM"),
                vacioAnulo(peticion.zonificacion()),
                vacioAnulo(peticion.partidaRegistral()),
                medidaOpcional(peticion.frenteM(), "frenteM"),
                medidaOpcional(peticion.fondoM(), "fondoM"));
    }

    private static CompletarSeccionDelFue.Proyecto proyectoDe(PeticionDeSeccionDelFue peticion) {
        Integer pisos = peticion.nDePisos();
        if (pisos == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio 'nDePisos'");
        }
        return new CompletarSeccionDelFue.Proyecto(
                exigido(peticion.usoDeLaEdificacion(), "usoDeLaEdificacion"),
                pisos,
                areaDe(peticion.areaTechadaTotalM(), "areaTechadaTotalM"),
                areaOpcional(peticion.areaLibreM(), "areaLibreM"),
                peticion.nDeEstacionamientos(),
                peticion.plazoDeEjecucionMeses());
    }

    private static List<CompletarSeccionDelFue.Estructura> valorizacionDe(
            PeticionDeSeccionDelFue peticion) {
        List<PeticionDeSeccionDelFue.LineaDeValorizacion> lineas = peticion.valorizacion();
        if (lineas == null || lineas.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La seccion de valorizacion llega sin ninguna linea: hay que decir que partida,"
                            + " en que categoria y cuantos metros tiene cada piso");
        }
        List<CompletarSeccionDelFue.Estructura> estructuras = new ArrayList<>(lineas.size());
        for (PeticionDeSeccionDelFue.LineaDeValorizacion linea : lineas) {
            Integer piso = linea.piso();
            if (piso == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Cada linea de la valorizacion dice de que piso es");
            }
            estructuras.add(
                    new CompletarSeccionDelFue.Estructura(
                            piso,
                            partidaDe(linea.partida()),
                            categoriaDe(linea.categoria()),
                            areaDe(linea.areaM(), "areaM")));
        }
        return estructuras;
    }

    private static List<CompletarSeccionDelFue.Profesional> profesionalesDe(
            PeticionDeSeccionDelFue peticion) {
        List<PeticionDeSeccionDelFue.ProfesionalDeclarado> declarados = peticion.profesionales();
        if (declarados == null || declarados.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La seccion de profesionales llega sin ninguno: el FUE lo firman los"
                            + " proyectistas y el responsable de obra");
        }
        List<CompletarSeccionDelFue.Profesional> firmantes = new ArrayList<>(declarados.size());
        for (PeticionDeSeccionDelFue.ProfesionalDeclarado declarado : declarados) {
            firmantes.add(
                    new CompletarSeccionDelFue.Profesional(
                            profesionalDe(declarado.tipo()),
                            exigido(declarado.nombre(), "nombre"),
                            vacioAnulo(declarado.colegio()),
                            vacioAnulo(declarado.colegiatura())));
        }
        return firmantes;
    }

    private static List<CompletarSeccionDelFue.Requisito> documentosDe(
            PeticionDeSeccionDelFue peticion) {
        List<PeticionDeSeccionDelFue.DocumentoDeclarado> declarados = peticion.documentos();
        if (declarados == null || declarados.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La seccion de documentos llega sin ninguno: hay que decir que requisitos del"
                            + " TUPA se adjuntaron");
        }
        List<CompletarSeccionDelFue.Requisito> documentos = new ArrayList<>(declarados.size());
        for (PeticionDeSeccionDelFue.DocumentoDeclarado declarado : declarados) {
            documentos.add(
                    new CompletarSeccionDelFue.Requisito(
                            exigido(declarado.requisito(), "requisito"),
                            Boolean.TRUE.equals(declarado.presentado()),
                            declarado.folios()));
        }
        return documentos;
    }

    private static @Nullable RepresentanteLegal representanteDe(PeticionDeFue peticion) {
        String nombre = vacioAnulo(peticion.representanteNombre());
        String documento = vacioAnulo(peticion.representanteDni());
        String partida = vacioAnulo(peticion.representantePartidaRegistral());
        if (nombre == null && documento == null && partida == null) {
            return null;
        }
        if (nombre == null || documento == null || partida == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El representante legal va entero —documento, nombre y partida registral del"
                            + " poder— o no va: un nombre sin partida no acredita representacion");
        }
        return new RepresentanteLegal(
                documento,
                nombre,
                partida,
                fechaOpcional(peticion.representanteVigenciaPoder(), "representanteVigenciaPoder"));
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

    private static FormatoDeDocumento formatoDe(@Nullable String formato) {
        if (formato == null || formato.isBlank()) {
            return FormatoDeDocumento.PDF;
        }
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    private static SeccionDelFue seccionDe(@Nullable String seccion) {
        String texto = seccion == null ? "" : seccion.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir que seccion del FUE se completa: TERRENO, PROYECTO,"
                            + " VALORIZACION, PROFESIONALES o DOCUMENTOS");
        }
        try {
            return SeccionDelFue.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La seccion va entre TERRENO, PROYECTO, VALORIZACION, PROFESIONALES y"
                            + " DOCUMENTOS: '"
                            + seccion
                            + "'");
        }
    }

    private static TipoDeTramiteDeEdificacion tramiteDe(@Nullable String tramite) {
        String texto = tramite == null ? "" : tramite.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio 'tipoTramite'");
        }
        try {
            return TipoDeTramiteDeEdificacion.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de tramite va entre ANTEPROYECTO_EN_CONSULTA, LICENCIA_DE_OBRA,"
                            + " AMPLIACION_DE_LICENCIA, REVALIDACION_DE_LICENCIA y"
                            + " REGULARIZACION_DE_LICENCIA: '"
                            + tramite
                            + "'");
        }
    }

    private static @Nullable TipoDeTramiteDeEdificacion tramiteOpcional(@Nullable String tramite) {
        if (tramite == null || tramite.isBlank() || "Todos".equalsIgnoreCase(tramite.strip())) {
            return null;
        }
        return tramiteDe(tramite);
    }

    private static TipoDeObra obraDe(@Nullable String obra) {
        String texto = obra == null ? "" : obra.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio 'obra'");
        }
        try {
            return TipoDeObra.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La obra va entre EDIFICACION_NUEVA, AMPLIACION, REMODELACION, DEMOLICION,"
                            + " CERCO y PUESTA_EN_VALOR: '"
                            + obra
                            + "'");
        }
    }

    private static ModalidadDeAprobacion modalidadDe(@Nullable String modalidad) {
        String texto = modalidad == null ? "" : modalidad.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio 'modalidadAprobacion'");
        }
        try {
            return ModalidadDeAprobacion.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La modalidad de aprobacion va entre A, B, C y D: '" + modalidad + "'");
        }
    }

    private static @Nullable ModalidadDeAprobacion modalidadOpcional(@Nullable String modalidad) {
        if (modalidad == null
                || modalidad.isBlank()
                || "Todas".equalsIgnoreCase(modalidad.strip())) {
            return null;
        }
        return modalidadDe(modalidad);
    }

    private static @Nullable RevisionDelProyecto revisionOpcional(@Nullable String revision) {
        if (revision == null || revision.isBlank()) {
            return null;
        }
        try {
            return RevisionDelProyecto.porNombre(revision);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La revision va entre REVISORES_URBANOS y COMISION_TECNICA: '"
                            + revision
                            + "'");
        }
    }

    private static @Nullable EstadoDelFue estadoOpcional(@Nullable String estado) {
        if (estado == null || estado.isBlank() || "Todos".equalsIgnoreCase(estado.strip())) {
            return null;
        }
        try {
            return EstadoDelFue.valueOf(estado.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El estado va entre EN_TRAMITE, VIGENTE, VENCIDA y ANULADA: '" + estado + "'");
        }
    }

    private static PartidaDeEdificacion partidaDe(@Nullable String partida) {
        String texto = partida == null ? "" : partida.strip();
        try {
            return PartidaDeEdificacion.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La partida va entre MUROS, TECHOS y PUERTAS, que son las tres del cuadro de"
                            + " valores unitarios: '"
                            + partida
                            + "'");
        }
    }

    private static char categoriaDe(@Nullable String categoria) {
        String texto = categoria == null ? "" : categoria.strip().toUpperCase(Locale.ROOT);
        if (texto.length() != 1) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La categoria es una sola letra, de A a J: '" + categoria + "'");
        }
        return texto.charAt(0);
    }

    private static TipoDeProfesional profesionalDe(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        try {
            return TipoDeProfesional.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de profesional va entre PROYECTISTA_ARQUITECTURA,"
                            + " PROYECTISTA_ESTRUCTURAS, PROYECTISTA_INSTALACIONES y"
                            + " RESPONSABLE_OBRA: '"
                            + tipo
                            + "'");
        }
    }

    private static AreaM2 areaDe(@Nullable String area, String campo) {
        String texto = area == null ? "" : area.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio '" + campo + "'");
        }
        try {
            return new AreaM2(new BigDecimal(texto));
        } catch (IllegalArgumentException | ArithmeticException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en metros cuadrados: '" + area + "'");
        }
    }

    private static @Nullable AreaM2 areaOpcional(@Nullable String area, String campo) {
        return area == null || area.isBlank() ? null : areaDe(area, campo);
    }

    private static @Nullable Medida medidaOpcional(@Nullable String magnitud, String campo) {
        if (magnitud == null || magnitud.isBlank()) {
            return null;
        }
        try {
            return Medida.enMetrosLineales(magnitud.strip());
        } catch (IllegalArgumentException | ArithmeticException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en metros lineales: '" + magnitud + "'");
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

    private static @Nullable LocalDate fechaOpcional(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato ISO (2026-03-15): '" + texto + "'");
        }
    }

    private static LocalDate exigidaLaFecha(@Nullable String texto, String campo) {
        LocalDate fecha = fechaOpcional(texto, campo);
        if (fecha == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio '" + campo + "'");
        }
        return fecha;
    }

    /**
     * La fecha del acto, o la de hoy.
     *
     * <p>El reloj es el <b>inyectado</b>, no {@code LocalDate.now()} suelto: una prueba que no
     * pueda congelar el dia no puede comprobar nada que dependa de el, y una fila de auditoria
     * fechada con el reloj de la maquina cae en la particion que no es.
     */
    private LocalDate fechaOhoy(@Nullable String texto, String campo) {
        LocalDate fecha = fechaOpcional(texto, campo);
        return fecha == null ? LocalDate.now(reloj) : fecha;
    }

    /**
     * El texto de una excepcion, sin poder ser nulo.
     *
     * <p>{@code getMessage()} es {@code @Nullable} y NullAway lo exige: una respuesta de error con
     * el cuerpo en blanco es peor que una con un texto generico.
     */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La peticion no se pudo completar" : mensaje;
    }
}
