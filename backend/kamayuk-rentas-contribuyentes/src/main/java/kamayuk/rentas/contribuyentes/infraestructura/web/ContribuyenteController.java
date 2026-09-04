package kamayuk.rentas.contribuyentes.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDelPadron;
import kamayuk.rentas.contribuyentes.aplicacion.RegistrarContribuyente;
import kamayuk.rentas.contribuyentes.dominio.CondicionEspecial;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import kamayuk.rentas.contribuyentes.dominio.TipoPersona;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.TipoDocumento;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Padron de contribuyentes: {@code GET/POST /api/v1/rentas/contribuyentes} y {@code PUT
 * /api/v1/rentas/contribuyentes/{id}}.
 *
 * <p>Los cuatro filtros de la lectura son los que declara el contrato, con los nombres que trae de
 * la pantalla del manual: {@code codigo}, {@code nombreRazonSocial}, {@code dNI} y {@code rUC}. Que
 * los dos ultimos vengan asi —con la mayuscula corrida— no es un descuido: el contrato se derivo
 * del prototipo, y cambiarlo aqui rompe la pantalla. Se corrige en el contrato o no se corrige.
 *
 * <p><b>Hasta #488 esto era solo lectura</b>, y el javadoc lo decia sin rodeos: el alta vivia en
 * {@code RegistrarContribuyente} y no se publicaba. La consecuencia era que una municipalidad
 * recien implantada <b>no podia registrar a su primer contribuyente</b> desde la aplicacion: el
 * unico camino era el proceso de importacion por lotes, en perfil {@code batch}. Ahora el alta, la
 * correccion y la baja tienen verbo.
 *
 * <p><b>Por que las escrituras cuelgan de {@code /rentas/} y no estrenan {@code
 * /contribuyentes/}.</b> El prefijo de una ruta de este contrato nombra <b>el modulo del manual al
 * que pertenece la pantalla</b>, no el contexto acotado que la sirve: {@code GET
 * /catastro/contribuyentes/{codigo}/ficha.pdf} lo sirve catastro y {@code GET
 * /rentas/contribuyentes} lo sirve este contexto, y las dos rutas hablan de contribuyentes. Con esa
 * convencion ya fijada, {@code /contribuyentes/...} no seria «mas coherente»: seria una <b>segunda
 * convencion</b> conviviendo con la primera en el mismo archivo. Y todas las escrituras del
 * contrato siguen a su pantalla —{@code calles} lee en {@code /catastro/vias} y edita en {@code
 * /catastro/vias/{codigo}}; {@code declaracion_jurada} lee en {@code /rentas/declaraciones/{djNro}}
 * y presenta en {@code /rentas/declaraciones}—, asi que estrenar el prefijo haria de esta la unica
 * excepcion. El criterio de la casa es que el contrato esta derivado del prototipo (#312); la
 * pantalla del padron vive en «Rentas · Registro», y ahi se queda.
 *
 * <p><b>El {@code @RequiereAcceso} de la clase pide {@code LECTURA}, y eso importa.</b> Lo que #431
 * destapo es que declararlo con un privilegio de escritura hace que el {@code GET} lo herede: quien
 * solo puede consultar el padron dejaria de poder abrirlo. Cada escritura declara el suyo en el
 * metodo.
 *
 * <p><b>La observacion del usuario es obligatoria en toda escritura</b> (regla 10, RNF-052): viaja
 * en el cuerpo y se convierte en {@link Observacion} antes de tocar el caso de uso; si viene vacia,
 * la peticion es 422 y no se guarda nada.
 *
 * <p><b>Ningun metodo recibe la municipalidad</b>, ni como parametro ni como encabezado ni en el
 * cuerpo: sale del token (ADR-0005, regla 2).
 */
@RestController
@RequestMapping(ContribuyenteController.RUTA)
@RequiereAcceso(acceso = ContribuyenteController.ACCESO, privilegio = Privilegio.LECTURA)
public class ContribuyenteController {

    /** La raiz del padron. La comparte {@link FichaDelContribuyenteController}. */
    static final String RUTA = Api.RAIZ + "/rentas/contribuyentes";

    /** Id de esta opcion en el catalogo de pantallas (NEG-03) y en la tabla {@code acceso}. */
    static final String ACCESO = "contribuyentes";

    /** Por codigo: es como se lee un padron cuando no se busca nada en concreto. */
    private static final String ORDEN_POR_OMISION = "codigo_contribuyente";

    private final ConsultaDelPadron consulta;
    private final RegistrarContribuyente registrar;
    private final GuardaDeBaja guardaDeBaja;

    public ContribuyenteController(
            ConsultaDelPadron consulta,
            RegistrarContribuyente registrar,
            ComprobadorDeAcceso comprobador,
            Clock reloj) {
        this.consulta = consulta;
        this.registrar = registrar;
        this.guardaDeBaja = new GuardaDeBaja(comprobador, reloj);
    }

    @GetMapping
    public RespuestaPaginada<ContribuyenteResource> buscar(
            @RequestParam(required = false) @Nullable String codigo,
            @RequestParam(required = false) @Nullable String nombreRazonSocial,
            @RequestParam(name = "dNI", required = false) @Nullable String dni,
            @RequestParam(name = "rUC", required = false) @Nullable String ruc,
            ParametrosDePaginacion paginacion) {

        CriterioDeBusqueda criterio =
                new CriterioDeBusqueda(
                        codigo,
                        nombreRazonSocial,
                        tipoDe(dni, ruc),
                        dni != null && !dni.isBlank() ? dni : ruc,
                        false);

        return RespuestaPaginada.de(
                consulta.buscar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ContribuyenteResource::de);
    }

    /**
     * Alta de un contribuyente (RF-013).
     *
     * <p>{@code codigo}, {@code tipoDocumento}, {@code numeroDocumento}, {@code tipoPersona} y
     * {@code nombreRazonSocial} son obligatorios: no hay fila anterior de la que heredarlos.
     *
     * <p>El codigo o el documento repetidos salen como {@code 409}. La unicidad la exige la base
     * —es la unica que puede—, pero el caso de uso comprueba antes para poder decir <b>cual de los
     * dos</b> se repitio; y el mensaje del documento repetido no dice <b>con quien</b>, que seria
     * revelar que una persona esta en el padron a quien solo teclea documentos.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ContribuyenteResource registrar(@RequestBody PeticionDeContribuyente peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        Contribuyente nuevo =
                new Contribuyente(
                        null,
                        codigoDe(peticion.codigo()),
                        documentoDe(peticion.tipoDocumento(), peticion.numeroDocumento()),
                        tipoPersonaDe(peticion.tipoPersona()),
                        exigir(peticion.nombreRazonSocial(), "nombreRazonSocial"),
                        condicionDe(peticion.condicionEspecial()),
                        null,
                        null,
                        null,
                        true);
        try {
            return ContribuyenteResource.de(registrar.registrar(nuevo, observacion));
        } catch (RegistrarContribuyente.CodigoRepetido
                | RegistrarContribuyente.DocumentoRepetido repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        } catch (DuplicateKeyException choque) {
            // Ni tabla, ni restriccion, ni SQL: solo lo que el usuario escribio.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya hay otro contribuyente con ese codigo o ese documento en esta"
                            + " municipalidad");
        }
    }

    /**
     * Correccion de un contribuyente ya registrado, o su baja logica.
     *
     * <p><b>Lo que no viene, no cambia.</b> Todo campo ausente conserva el valor que la fila ya
     * tiene. Para <b>quitar</b> la condicion especial se manda la cadena vacia, que es una
     * instruccion y no una omision — la misma regla que {@code PUT /catastro/vias/{codigo}}.
     *
     * <p><b>El codigo y el documento no se corrigen por aqui.</b> Son la identidad del
     * contribuyente: el codigo enlaza sus predios, sus recibos y sus asientos, y el documento es
     * con lo que se le acredita. Cambiar cualquiera de los dos no es corregir una ficha sino
     * decidir que dos filas eran la misma persona, y eso es otro acto —con otro expediente— que
     * este endpoint no finge hacer.
     *
     * <h2>Lo que el expediente puede corregir aqui, y lo que no (#552)</h2>
     *
     * <p>El expediente del contribuyente dibuja del orden de cincuenta campos y esta escritura
     * admitia <b>tres</b>. Los que faltaban no eran todos del mismo tipo, y por eso no entran
     * todos:
     *
     * <ul>
     *   <li><b>Entran</b> {@code fechaNacimiento}, {@code estadoCivil} y {@code conyugeId}: son
     *       datos de la persona que el padron corrige a menudo —una fecha mal tecleada, una
     *       sociedad conyugal que se declara despues— y que esta operacion ya guardaba <b>sin poder
     *       cambiarlos</b>: los copiaba de la fila existente, asi que la pantalla los dibujaba
     *       editables y no viajaban.
     *   <li><b>No entran</b> el domicilio, los contactos ni los responsables solidarios: cada uno
     *       tiene su propia escritura en {@code FichaDelContribuyenteController} (#488), y con
     *       motivo — <b>una mudanza no es una edicion</b>: agrega un tramo y cierra el anterior el
     *       dia antes, de modo que la direccion vieja sigue explicando por que se notifico donde se
     *       notifico. Meterlos aqui como campos sueltos perderia esa historia.
     *   <li><b>No entran</b> los beneficios: son de {@code rentas} —tienen su vigencia, su
     *       resolucion y su propia pantalla ({@code GET /rentas/beneficios})—, y este contexto es
     *       la base del grafo de modulos (ARQ-01 §2): no puede depender de rentas.
     * </ul>
     *
     * <p>{@code activo = false} es la baja, y <b>no borra</b> (RNF-051): el codigo aparece en
     * recibos ya emitidos y en asientos del libro. Exige ademas el privilegio {@code ELIMINACION},
     * comprobado aqui dentro por el mismo puerto que usa el guardia, porque la anotacion declara lo
     * que exige la <i>ruta</i> y cual de las dos operaciones es depende del cuerpo, que el guardia
     * no lee.
     */
    @PutMapping("/{id}")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.MODIFICACION)
    public ContribuyenteResource modificar(
            @PathVariable long id, @RequestBody PeticionDeContribuyente peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        Contribuyente existente =
                consulta.porId(id)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun contribuyente con identificador "
                                                        + id
                                                        + " en esta municipalidad"));

        boolean baja = Boolean.FALSE.equals(peticion.activo());
        if (existente.activo() && baja) {
            guardaDeBaja.exigir(ACCESO);
            return ContribuyenteResource.de(registrar.darDeBaja(id, observacion));
        }

        Contribuyente cambiado =
                new Contribuyente(
                        existente.id(),
                        existente.codigo(),
                        existente.documento(),
                        existente.tipoPersona(),
                        peticion.nombreRazonSocial() == null
                                ? existente.nombreRazonSocial()
                                : exigir(peticion.nombreRazonSocial(), "nombreRazonSocial"),
                        peticion.condicionEspecial() == null
                                ? existente.condicionEspecial()
                                : condicionDe(peticion.condicionEspecial()),
                        peticion.fechaNacimiento() == null
                                ? existente.fechaNacimiento()
                                : fechaDeNacimientoDe(peticion.fechaNacimiento()),
                        peticion.estadoCivil() == null
                                ? existente.estadoCivil()
                                : vacioANulo(peticion.estadoCivil()),
                        peticion.conyugeId() == null
                                ? existente.conyugeId()
                                : conyugeDe(peticion.conyugeId(), id),
                        peticion.activo() == null ? existente.activo() : peticion.activo());

        return ContribuyenteResource.de(registrar.registrar(cambiado, observacion));
    }

    // ------------------------------------------------------------------

    /**
     * La fecha de nacimiento del cuerpo, o {@code null} si se manda vacia para borrarla.
     *
     * <p>La cadena vacia es una instruccion —«este contribuyente no tiene fecha»— y la ausencia del
     * campo es una omision, igual que en el resto de este PUT.
     */
    private static @Nullable LocalDate fechaDeNacimientoDe(String texto) {
        String limpio = texto.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(limpio);
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La fecha de nacimiento va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    /**
     * El conyuge, comprobando que no sea el propio contribuyente.
     *
     * <p>{@code 0} borra el enlace, como la cadena vacia en los campos de texto. Y nadie es su
     * propio conyuge: dejarlo pasar produciria una sociedad conyugal de una sola persona, que
     * ninguna consulta sabria deshacer.
     */
    private static @Nullable Long conyugeDe(long conyugeId, long propio) {
        if (conyugeId == 0L) {
            return null;
        }
        if (conyugeId == propio) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Nadie es su propio conyuge: el identificador del conyuge no puede ser el del"
                            + " contribuyente que se corrige");
        }
        return conyugeId;
    }

    private static @Nullable String vacioANulo(String texto) {
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    /**
     * El contrato trae el DNI y el RUC como dos filtros distintos, no como un tipo y un numero. Si
     * llegan los dos, gana el DNI: son criterios excluyentes —nadie tiene los dos en la misma fila—
     * y combinarlos con Y devolveria siempre vacio, que es la respuesta mas confusa posible.
     */
    private static @Nullable TipoDocumento tipoDe(@Nullable String dni, @Nullable String ruc) {
        if (dni != null && !dni.isBlank()) {
            return TipoDocumento.DNI;
        }
        if (ruc != null && !ruc.isBlank()) {
            return TipoDocumento.RUC;
        }
        return null;
    }

    static Observacion observacionDe(@Nullable String texto) {
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

    private static CodigoContribuyente codigoDe(@Nullable String texto) {
        try {
            return CodigoContribuyente.de(exigir(texto, "codigo"));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static DocumentoIdentidad documentoDe(@Nullable String tipo, @Nullable String numero) {
        String nombreDelTipo = normalizar(exigir(tipo, "tipoDocumento"));
        TipoDocumento tipoDocumento;
        try {
            tipoDocumento = TipoDocumento.valueOf(nombreDelTipo);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de documento desconocido: '" + tipo + "'");
        }
        try {
            return new DocumentoIdentidad(tipoDocumento, exigir(numero, "numeroDocumento"));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static TipoPersona tipoPersonaDe(@Nullable String texto) {
        try {
            return TipoPersona.valueOf(normalizar(exigir(texto, "tipoPersona")));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de persona desconocido: '" + texto + "'");
        }
    }

    /** Cadena vacia es «quitala»; nula, «no la toques». Lo resuelve quien llama. */
    private static @Nullable CondicionEspecial condicionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return CondicionEspecial.valueOf(normalizar(texto));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Condicion especial desconocida: '" + texto + "'");
        }
    }

    private static String normalizar(String texto) {
        return texto.strip().toUpperCase(Locale.ROOT);
    }

    static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo de un alta o una correccion. <b>Lista blanca</b>: lo que no esta aqui no entra,
     * aunque llegue en el JSON.
     *
     * <p><b>No estan la fecha de nacimiento, el estado civil ni el conyuge</b>, que la tabla si
     * guarda. {@code ContribuyenteResource} no los publica a proposito —son datos personales que la
     * busqueda no necesita, y lo que no se publica no se filtra—, y un campo que se puede escribir
     * y nunca se puede leer de vuelta es una trampa: quien lo teclea no tiene forma de comprobar
     * que entro bien. Entran cuando exista la lectura que los muestre.
     */
    public record PeticionDeContribuyente(
            @Nullable String observacion,
            @Nullable String codigo,
            @Nullable String tipoDocumento,
            @Nullable String numeroDocumento,
            @Nullable String tipoPersona,
            @Nullable String nombreRazonSocial,
            @Nullable String condicionEspecial,
            @Nullable String fechaNacimiento,
            @Nullable String estadoCivil,
            @Nullable Long conyugeId,
            @Nullable Boolean activo) {}
}
