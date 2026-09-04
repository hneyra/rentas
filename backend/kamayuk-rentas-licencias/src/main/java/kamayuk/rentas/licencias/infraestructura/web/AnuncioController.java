package kamayuk.rentas.licencias.infraestructura.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.CesarAnuncio;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeAnuncios;
import kamayuk.rentas.licencias.aplicacion.RegistrarAnuncio;
import kamayuk.rentas.licencias.aplicacion.RenovarAnuncio;
import kamayuk.rentas.licencias.aplicacion.TasaDeAnunciosParametrizada;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.licencias.dominio.CriterioDeAnuncios;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import kamayuk.rentas.licencias.dominio.TipoDeAnuncio;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los anuncios y la propaganda por HTTP: consulta, registro con su deuda, renovacion, cese, retiro
 * y padron (#51, RF-114).
 *
 * <h2>Dos opciones del catalogo, dos accesos</h2>
 *
 * <p>{@code anuncios} lee y escribe los cuatro actos; {@code anuncios_reportes} emite el padron.
 * Sin {@code @RequiereAcceso} el guardia <b>niega</b>, y la regla de arquitectura rompe el build;
 * las dos cosas juntas hacen que el olvido no se pueda convertir en una puerta abierta.
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Una autorizacion no se corrige: {@code anuncio} no admite {@code UPDATE} desde V45. Lo que le
 * pasa llega como un recurso nuevo —{@code /renovacion}, {@code /cese}, {@code /retiro}—, que es
 * ademas lo que el prototipo declara con su pestaña «Cancelacion».
 *
 * <h2>La cabecera {@code Idempotency-Key}, aqui si se lee</h2>
 *
 * <p>El frontend ya la manda en toda escritura ({@code nuevaClaveDeIdempotencia}) y hasta ahora
 * solo la caja la leia. Aqui tambien, y por el mismo motivo que alli: <b>este endpoint asienta
 * deuda</b>. Reenviar el mismo registro devuelve {@code 200} con la autorizacion de la primera vez
 * en lugar de {@code 201} con una segunda y su segundo cargo. La garantia no es esta lectura, es
 * {@code anuncio_idempotencia_uq} (V45).
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>La tasa de la clase de anuncio sale del <b>conjunto sellado</b> que rige a la fecha de la
 * autorizacion ({@link TasaDeAnunciosParametrizada}, regla 5). Que el conjunto exista y no traiga
 * la llave ({@code TasaSinParametrizar}) ya estaba traducido desde #51; que <b>no exista ningun
 * conjunto sellado</b> ({@code EjercicioSinSellar}) no lo estaba, y con D-02a abierta ese es el
 * estado <i>normal</i> de todas las municipalidades: salia como <b>500 {@code ERROR_INTERNO} con
 * identificador de incidencia</b>, y cada intento ensuciaba el registro de errores del servidor. El
 * razonamiento completo esta en la cabecera de {@link LicenciaController}.
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <p>{@code {id}} es el numero <b>impreso</b> de la autorizacion, tal como esta en el papel del
 * administrado. Ni el identificador interno de la fila —que ninguna pantalla conoce— ni el
 * ejercicio y el correlativo por separado.
 */
@RestController
@RequestMapping(Api.RAIZ + "/autorizaciones/anuncios")
public class AnuncioController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_ANUNCIOS = "anuncios";

    static final String ACCESO_REPORTES = "anuncios_reportes";

    private static final String ORDEN_POR_OMISION = "numero";

    private final ConsultaDeAnuncios consulta;
    private final RegistrarAnuncio registrar;
    private final RenovarAnuncio renovar;
    private final CesarAnuncio cesar;
    private final Clock reloj;

    public AnuncioController(
            ConsultaDeAnuncios consulta,
            RegistrarAnuncio registrar,
            RenovarAnuncio renovar,
            CesarAnuncio cesar,
            Clock reloj) {
        this.consulta = consulta;
        this.registrar = registrar;
        this.renovar = renovar;
        this.cesar = cesar;
        this.reloj = reloj;
    }

    /**
     * La grilla de autorizaciones, paginada, con el estado de cada una derivado a hoy (RF-114).
     *
     * <p>Con {@code nroAutorizacion}, la fila trae ademas su historial completo: es la ficha que la
     * pantalla dibuja al abrir una autorizacion. Sin el, la fila es la que la grilla pinta y nada
     * mas —una pagina de veinte no puede costar veinte lecturas de detalle—.
     */
    @GetMapping
    @RequiereAcceso(acceso = ACCESO_ANUNCIOS, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<AnuncioResource> listar(
            @RequestParam(required = false) @Nullable String nroAutorizacion,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String nExpediente,
            @RequestParam(required = false) @Nullable String direccion,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);

        if (nroAutorizacion != null && !nroAutorizacion.isBlank()) {
            return consulta.porNumero(nroAutorizacion.strip(), hoy)
                    .map(
                            ficha ->
                                    RespuestaPaginada.de(
                                            Pagina.de(
                                                    List.of(AnuncioResource.de(ficha)),
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION),
                                                    1)))
                    .orElseGet(
                            () ->
                                    RespuestaPaginada.de(
                                            Pagina.vacia(
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION))));
        }

        CriterioDeAnuncios criterio =
                new CriterioDeAnuncios(null, nExpediente, direccion, null, null, null, null);

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio, contribuyente, hoy, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                AnuncioResource::de);
    }

    /**
     * Registra una autorizacion de anuncio y genera su deuda por la tasa (RF-114).
     *
     * <p>Responde <b>201</b> cuando la autorizacion nace y <b>200</b> cuando la peticion era un
     * reintento ya atendido —misma clave de idempotencia, misma respuesta, ningun segundo cargo—.
     * El {@code 422} de una clase que la ordenanza sellada no tarifa lleva la llave que falta:
     * quien opera tiene que poder pedirla, en vez de recibir «error interno» y un identificador de
     * incidencia.
     */
    @PostMapping
    @RequiereAcceso(acceso = ACCESO_ANUNCIOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeAnuncioResource> registrar(
            @RequestBody PeticionDeAnuncio peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {

        Observacion observacion = observacionDe(peticion.observacion());

        RegistrarAnuncio.Solicitud solicitud =
                new RegistrarAnuncio.Solicitud(
                        exigido(peticion.codContribuyente(), "codContribuyente"),
                        vacioAnulo(peticion.nroLicencia()),
                        peticion.predioId(),
                        claseDe(peticion.claseAnuncio()),
                        tipoDe(peticion.tipoAnuncio()),
                        vacioAnulo(peticion.ubicacion()),
                        vacioAnulo(peticion.forma()),
                        vacioAnulo(peticion.denominacion()),
                        exigido(peticion.direccion(), "direccion"),
                        areaDe(peticion.area()),
                        peticion.nroLados() == null ? 1 : peticion.nroLados(),
                        peticion.cantidad() == null ? 1 : peticion.cantidad(),
                        fechaOhoy(peticion.fecInicio(), "fecInicio"),
                        fechaOpcional(peticion.fecVenc(), "fecVenc"),
                        vacioAnulo(peticion.nroDeExpediente()),
                        fechaOpcional(peticion.fechaExp(), "fechaExp"));

        RegistrarAnuncio.Registro registro;
        try {
            registro = registrar.registrar(solicitud, clave, observacion);
        } catch (RegistrarAnuncio.TitularDesconocido noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (RegistrarAnuncio.EstablecimientoDesconocido sinLocal) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinLocal));
        } catch (TasaDeAnunciosParametrizada.TasaSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinTarifa) {
            // 422 y no 500: la peticion esta bien y el sistema tampoco esta roto. Lo que falta es
            // un dato de configuracion —la ordenanza de D-02b, #199— y quien opera tiene que
            // enterarse de cual para poder pedirlo. `EjercicioSinSellar` —que no haya NINGUN
            // conjunto sellado— es el mismo caso y hasta #562 salia como 500 con incidencia.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinTarifa);
        } catch (AnuncioRepository.ClaveRepetida carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        } catch (AnuncioRepository.NumeroDuplicado repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (MovimientoDeAnuncioRepository.CargoYaAsentado dosVeces) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(dosVeces));
        } catch (MovimientoDeAnuncioRepository.ActoRepetido repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(registro.yaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ActoDeAnuncioResource.de(registro));
    }

    /** Renueva la autorizacion por otro ejercicio, con su tasa (RF-114). */
    @PostMapping("/{id}/renovacion")
    @RequiereAcceso(acceso = ACCESO_ANUNCIOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeAnuncioResource> renovacion(
            @PathVariable String id, @RequestBody PeticionDeActoDeAnuncio peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOhoy(peticion.fecha(), "fecha");

        RenovarAnuncio.Renovacion renovacion;
        try {
            renovacion =
                    renovar.renovar(
                            id, fecha, fechaOpcional(peticion.fecVenc(), "fecVenc"), observacion);
        } catch (RenovarAnuncio.AnuncioInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (RenovarAnuncio.NoSeRenueva cesado) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cesado));
        } catch (MovimientoDeAnuncioRepository.CargoYaAsentado dosVeces) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(dosVeces));
        } catch (TasaDeAnunciosParametrizada.TasaSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinTarifa) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinTarifa);
        } catch (RenovarAnuncio.AnteriorALaAutorizacion | RenovarAnuncio.VigenciaHaciaAtras mal) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(mal));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ActoDeAnuncioResource.de(renovacion));
    }

    /**
     * Cesa la autorizacion (RF-114).
     *
     * <p>No genera ningun cargo, no reversa ninguno y no borra nada: detiene la deuda futura —un
     * anuncio cesado no se renueva— y deja la pasada donde estaba (regla 4, RNF-051).
     */
    @PostMapping("/{id}/cese")
    @RequiereAcceso(acceso = ACCESO_ANUNCIOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeAnuncioResource> cese(
            @PathVariable String id, @RequestBody PeticionDeActoDeAnuncio peticion) {
        return actoSobreElAnuncio(id, peticion, /* esRetiro= */ false);
    }

    /** Registra que el elemento se retiro de la calle, comprobado en campo (RF-114). */
    @PostMapping("/{id}/retiro")
    @RequiereAcceso(acceso = ACCESO_ANUNCIOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeAnuncioResource> retiro(
            @PathVariable String id, @RequestBody PeticionDeActoDeAnuncio peticion) {
        return actoSobreElAnuncio(id, peticion, /* esRetiro= */ true);
    }

    /** El padron de autorizaciones, con su fecha de corte y su resumen (RF-114). */
    @PostMapping("/reportes")
    @RequiereAcceso(acceso = ACCESO_REPORTES, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<PadronDeAnunciosResource> reportes(
            @RequestBody PeticionDeReporteDeAnuncios peticion) {

        LocalDate corte = fechaOhoy(peticion.aLaFecha(), "aLaFecha");

        CriterioDeAnuncios criterio;
        try {
            criterio =
                    new CriterioDeAnuncios(
                            null,
                            null,
                            vacioAnulo(peticion.direccion()),
                            claseOpcional(peticion.claseAnuncio()),
                            fechaOpcional(peticion.desde(), "desde"),
                            fechaOpcional(peticion.hasta(), "hasta"),
                            null);
        } catch (IllegalArgumentException intervalo) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(intervalo));
        }

        ParametrosDePaginacion paginacion =
                new ParametrosDePaginacion(peticion.pagina(), peticion.tamano(), null, null);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        PadronDeAnunciosResource.de(
                                consulta.padron(
                                        criterio,
                                        peticion.contribuyente(),
                                        corte,
                                        paginacion.aPaginacion(ORDEN_POR_OMISION))));
    }

    // ------------------------------------------------------------------

    private ResponseEntity<ActoDeAnuncioResource> actoSobreElAnuncio(
            String numero, PeticionDeActoDeAnuncio peticion, boolean esRetiro) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOhoy(peticion.fecha(), "fecha");
        String motivo = peticion.motivo() == null ? "" : peticion.motivo();

        CesarAnuncio.Acto acto;
        try {
            acto =
                    esRetiro
                            ? cesar.retirar(numero, fecha, motivo, observacion)
                            : cesar.cesar(numero, fecha, motivo, observacion);
        } catch (RenovarAnuncio.AnuncioInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CesarAnuncio.YaEstabaCesado yaEstaba) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaEstaba));
        } catch (MovimientoDeAnuncioRepository.ActoRepetido carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        } catch (CesarAnuncio.SinCesePrevio
                | CesarAnuncio.SinMotivo
                | RenovarAnuncio.AnteriorALaAutorizacion invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ActoDeAnuncioResource.de(acto));
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

    private static ClaseDeAnuncio claseDe(@Nullable String clase) {
        ClaseDeAnuncio resuelta = claseOpcional(clase);
        if (resuelta == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de que clase es el anuncio: de ella sale la tasa que se le"
                            + " cobra");
        }
        return resuelta;
    }

    private static @Nullable ClaseDeAnuncio claseOpcional(@Nullable String clase) {
        String texto = clase == null ? "" : clase.strip();
        if (texto.isEmpty()) {
            return null;
        }
        try {
            return ClaseDeAnuncio.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La clase del anuncio va entre LETRERO, PANEL, TOLDO, BANDEROLA,"
                            + " PANTALLA_DIGITAL y GLOBO_AEROSTATICO: '"
                            + clase
                            + "'");
        }
    }

    private static TipoDeAnuncio tipoDe(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir que tipo de aviso es: AVISO_SIMPLE, AVISO_LUMINOSO,"
                            + " AVISO_ILUMINADO o AVISO_ELECTRONICO");
        }
        try {
            return TipoDeAnuncio.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de aviso va entre AVISO_SIMPLE, AVISO_LUMINOSO, AVISO_ILUMINADO y"
                            + " AVISO_ELECTRONICO: '"
                            + tipo
                            + "'");
        }
    }

    private static AreaM2 areaDe(@Nullable String area) {
        String texto = area == null ? "" : area.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El area del anuncio es obligatoria: es la medida que el acto consigna");
        }
        try {
            return new AreaM2(new BigDecimal(texto));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El area del anuncio va en metros cuadrados: '" + area + "'");
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
     * el cuerpo en blanco es peor que una con un texto generico, porque no dice ni siquiera que
     * clase de problema hubo.
     */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La peticion no se pudo completar" : mensaje;
    }
}
