package kamayuk.rentas.contribuyentes.infraestructura.web;

import static kamayuk.rentas.contribuyentes.infraestructura.web.ContribuyenteController.exigir;
import static kamayuk.rentas.contribuyentes.infraestructura.web.ContribuyenteController.mensajeDe;
import static kamayuk.rentas.contribuyentes.infraestructura.web.ContribuyenteController.observacionDe;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.aplicacion.ActualizarFicha;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDeLaFichaDelContribuyente;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import kamayuk.rentas.contribuyentes.dominio.TipoContacto;
import kamayuk.rentas.contribuyentes.dominio.TipoDomicilio;
import kamayuk.rentas.contribuyentes.dominio.Vinculo;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
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
 * Lo que cuelga del contribuyente: {@code /api/v1/rentas/contribuyentes/{id}/…}.
 *
 * <p>Donde esta —sus domicilios—, como se le ubica —sus contactos— y quien responde con el —sus
 * responsables solidarios—. Publica {@link ActualizarFicha}, que existia desde #15 sin que ningun
 * endpoint la llamara: la ficha se mantenia fuera del sistema.
 *
 * <p><b>Va aparte de {@link ContribuyenteController} a proposito.</b> Son dos casos de uso
 * distintos —el padron identifica al sujeto; la ficha describe donde encontrarlo— y separarlos deja
 * el {@code GET} del padron en una clase cuya anotacion de clase sigue siendo {@code LECTURA}. Eso
 * es lo que #431 destapo: una anotacion de clase con privilegio de escritura se la come tambien el
 * {@code GET}.
 *
 * <p><b>La mudanza no es una edicion.</b> No hay {@code PUT} de domicilio, y es la decision que mas
 * dice de esta clase: cambiar de domicilio fiscal es <b>cerrar el anterior y abrir el nuevo</b> en
 * la misma transaccion, no reescribir una direccion. Si fuera una edicion, la direccion vieja
 * desapareceria y con ella la unica prueba de por que se notifico donde se notifico. {@link
 * ActualizarFicha#mudar} lo hace en un acto; aqui solo se publica.
 *
 * <p><b>Nada se borra</b> (regla 4, RNF-051): un contacto se da de baja con {@code vigente = false}
 * y un vinculo se cierra con su fecha. Las dos son bajas, asi que exigen ademas {@code ELIMINACION}
 * — ver {@link GuardaDeBaja}.
 *
 * <p><b>Toda escritura exige la observacion del usuario</b> (regla 10, RNF-052), y <b>ninguna
 * recibe la municipalidad</b>: sale del token (regla 2).
 */
@RestController
@RequestMapping(ContribuyenteController.RUTA + "/{id}")
@RequiereAcceso(acceso = ContribuyenteController.ACCESO, privilegio = Privilegio.LECTURA)
public class FichaDelContribuyenteController {

    private static final String ACCESO = ContribuyenteController.ACCESO;

    private final ConsultaDeLaFichaDelContribuyente consulta;
    private final ActualizarFicha actualizar;
    private final GuardaDeBaja guardaDeBaja;
    private final Clock reloj;

    public FichaDelContribuyenteController(
            ConsultaDeLaFichaDelContribuyente consulta,
            ActualizarFicha actualizar,
            ComprobadorDeAcceso comprobador,
            Clock reloj) {
        this.consulta = consulta;
        this.actualizar = actualizar;
        this.guardaDeBaja = new GuardaDeBaja(comprobador, reloj);
        this.reloj = reloj;
    }

    /**
     * La ficha entera a una fecha: domicilio vigente, historial, contactos y responsables.
     *
     * <p>Existe porque las escrituras de aqui abajo necesitan identificadores que ninguna lectura
     * publicaba: dar de baja un contacto exige decir cual. Y porque las cuatro consultas van en
     * <b>una</b> transaccion (#486): cuatro por separado dejarian sitio entre medias a una mudanza,
     * y la ficha saldria diciendo que el contribuyente vive en dos sitios.
     *
     * <p>{@code fecha} ausente es hoy, con el reloj inyectado. <b>Lo vigente se resuelve a esa
     * fecha</b>, no «lo ultimo» (regla 9).
     */
    @GetMapping("/ficha")
    public FichaDelContribuyenteResource ficha(
            @PathVariable long id, @RequestParam(required = false) @Nullable String fecha) {
        LocalDate cuando = fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : parsear(fecha);
        return FichaDelContribuyenteResource.de(
                consulta.de(id, cuando).orElseThrow(() -> noExiste(id)));
    }

    /**
     * Muda al contribuyente (RF-014): cierra el domicilio vigente del mismo tipo y abre el nuevo.
     *
     * <p>Es un {@code POST} y no un {@code PUT} porque <b>no reemplaza nada</b>: agrega un tramo de
     * vigencia y cierra el anterior el dia antes. El domicilio que habia sigue ahi, y por eso una
     * notificacion de marzo se puede seguir explicando en 2029.
     *
     * <p>{@code vigenciaDesde} ausente es hoy. El indice parcial {@code
     * domicilio_fiscal_vigente_uq} impide que queden dos vigentes aunque el codigo se equivoque; lo
     * que no puede exigir es que quede uno, y de eso se encarga la transaccion del caso de uso.
     */
    @PostMapping("/domicilios")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.MODIFICACION)
    public FichaDelContribuyenteResource.DomicilioResource mudar(
            @PathVariable long id, @RequestBody PeticionDeDomicilio peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        exigirQueExista(id);

        Domicilio nuevo =
                deValor(
                        () ->
                                new Domicilio(
                                        null,
                                        id,
                                        tipoDomicilioDe(peticion.tipo()),
                                        exigir(peticion.direccion(), "direccion"),
                                        vacioANulo(peticion.referencia()),
                                        vacioANulo(peticion.ubigeo()),
                                        peticion.vigenciaDesde() == null
                                                ? LocalDate.now(reloj)
                                                : parsear(peticion.vigenciaDesde()),
                                        null,
                                        exigir(peticion.documentoOrigen(), "documentoOrigen")));

        return FichaDelContribuyenteResource.DomicilioResource.de(
                actualizar.mudar(nuevo, observacion));
    }

    /** Alta de un telefono, un correo o un gestor (RF-015). */
    @PostMapping("/contactos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public FichaDelContribuyenteResource.ContactoResource registrarContacto(
            @PathVariable long id, @RequestBody PeticionDeContacto peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        exigirQueExista(id);

        Contacto nuevo =
                deValor(
                        () ->
                                new Contacto(
                                        null,
                                        id,
                                        tipoContactoDe(peticion.tipo()),
                                        exigir(peticion.valor(), "valor"),
                                        vacioANulo(peticion.nombre()),
                                        vacioANulo(peticion.documento()),
                                        vacioANulo(peticion.nota()),
                                        true));

        return FichaDelContribuyenteResource.ContactoResource.de(
                conflictoSiChoca(() -> actualizar.registrarContacto(nuevo, observacion)));
    }

    /**
     * Correccion de un contacto, o su baja logica.
     *
     * <p><b>Lo que no viene, no cambia.</b> {@code vigente = false} es la baja y no borra: un
     * gestor que ya no lo es aparece en notificaciones anteriores, y explicar por que se le
     * notifico exige que su ficha siga ahi. La baja exige ademas {@code ELIMINACION}.
     */
    @PutMapping("/contactos/{contactoId}")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.MODIFICACION)
    public FichaDelContribuyenteResource.ContactoResource modificarContacto(
            @PathVariable long id,
            @PathVariable long contactoId,
            @RequestBody PeticionDeContacto peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        Contacto existente =
                consulta
                        .de(id, LocalDate.now(reloj))
                        .orElseThrow(() -> noExiste(id))
                        .contactos()
                        .stream()
                        .filter(contacto -> Long.valueOf(contactoId).equals(contacto.id()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "El contribuyente "
                                                        + id
                                                        + " no tiene ningun contacto con"
                                                        + " identificador "
                                                        + contactoId));

        if (existente.vigente() && Boolean.FALSE.equals(peticion.vigente())) {
            guardaDeBaja.exigir(ACCESO);
            return FichaDelContribuyenteResource.ContactoResource.de(
                    actualizar.darDeBajaContacto(existente, observacion));
        }

        Contacto cambiado =
                deValor(
                        () ->
                                new Contacto(
                                        existente.id(),
                                        existente.contribuyenteId(),
                                        peticion.tipo() == null
                                                ? existente.tipo()
                                                : tipoContactoDe(peticion.tipo()),
                                        peticion.valor() == null
                                                ? existente.valor()
                                                : exigir(peticion.valor(), "valor"),
                                        peticion.nombre() == null
                                                ? existente.nombre()
                                                : vacioANulo(peticion.nombre()),
                                        peticion.documento() == null
                                                ? existente.documento()
                                                : vacioANulo(peticion.documento()),
                                        peticion.nota() == null
                                                ? existente.observacion()
                                                : vacioANulo(peticion.nota()),
                                        peticion.vigente() == null
                                                ? existente.vigente()
                                                : peticion.vigente()));

        return FichaDelContribuyenteResource.ContactoResource.de(
                conflictoSiChoca(() -> actualizar.registrarContacto(cambiado, observacion)));
    }

    /**
     * Alta de un responsable solidario (RF-016).
     *
     * <p>{@code responsableId} es <b>otro contribuyente del mismo padron</b>, no un nombre suelto:
     * para notificarle hace falta su domicilio, y el domicilio cuelga del padron.
     *
     * <p>{@code porcentaje} solo lo admiten los vinculos que reparten; en los demas, mandarlo es
     * 422 y no un campo ignorado en silencio.
     */
    @PostMapping("/responsables")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public FichaDelContribuyenteResource.ResponsableResource registrarResponsable(
            @PathVariable long id, @RequestBody PeticionDeResponsable peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        exigirQueExista(id);
        Long responsableId = peticion.responsableId();
        if (responsableId == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo 'responsableId'");
        }
        exigirQueExista(responsableId);

        ResponsableSolidario nuevo =
                deValor(
                        () ->
                                new ResponsableSolidario(
                                        null,
                                        id,
                                        responsableId,
                                        vinculoDe(peticion.vinculo()),
                                        porcentajeDe(peticion.porcentaje()),
                                        peticion.vigenciaDesde() == null
                                                ? LocalDate.now(reloj)
                                                : parsear(peticion.vigenciaDesde()),
                                        null,
                                        exigir(peticion.documentoOrigen(), "documentoOrigen")));

        return FichaDelContribuyenteResource.ResponsableResource.de(
                conflictoSiChoca(() -> actualizar.registrarResponsable(nuevo, observacion)));
    }

    /**
     * Cierra el vinculo en una fecha. No lo borra: la deuda anterior sigue siendo suya, y una
     * notificacion de entonces se defiende ensenando que el vinculo regia.
     *
     * <p>Solo se puede cerrar uno que este vigente a la fecha de hoy; uno ya cerrado sale como
     * {@code 404}, que es lo que es —no hay tal vinculo abierto que cerrar—.
     */
    @PutMapping("/responsables/{responsableId}")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.MODIFICACION)
    public FichaDelContribuyenteResource.ResponsableResource cerrarResponsable(
            @PathVariable long id,
            @PathVariable long responsableId,
            @RequestBody PeticionDeCierre peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate hoy = LocalDate.now(reloj);
        ResponsableSolidario existente =
                consulta.de(id, hoy).orElseThrow(() -> noExiste(id)).responsables().stream()
                        .filter(responsable -> Long.valueOf(responsableId).equals(responsable.id()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "El contribuyente "
                                                        + id
                                                        + " no tiene ningun vinculo abierto con"
                                                        + " identificador "
                                                        + responsableId));

        guardaDeBaja.exigir(ACCESO);
        LocalDate cierre =
                peticion.vigenciaHasta() == null ? hoy : parsear(peticion.vigenciaHasta());

        return FichaDelContribuyenteResource.ResponsableResource.de(
                deValor(() -> actualizar.cerrarResponsable(existente, cierre, observacion)));
    }

    // ------------------------------------------------------------------

    /**
     * Que el contribuyente exista se pregunta <b>antes</b> de componer nada.
     *
     * <p>Sin esto, colgar un domicilio de un identificador inexistente llegaria a la base y saldria
     * como un choque de clave foranea: un 500 con el nombre de la restriccion dentro. Preguntarlo
     * primero cuesta una consulta y devuelve el 404 que es.
     */
    private void exigirQueExista(long id) {
        consulta.de(id, LocalDate.now(reloj)).orElseThrow(() -> noExiste(id));
    }

    private static ProblemaDeNegocio noExiste(long id) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "No hay ningun contribuyente con identificador " + id + " en esta municipalidad");
    }

    /**
     * Un objeto de valor que rechaza lo recibido es {@code 422}, no {@code 500}.
     *
     * <p>Los constructores del dominio validan y lanzan {@link IllegalArgumentException}; su
     * mensaje explica que esta mal y no nombra ninguna tabla, asi que se puede devolver tal cual.
     */
    private static <T> T deValor(java.util.function.Supplier<T> construir) {
        try {
            return construir.get();
        } catch (IllegalArgumentException | IllegalStateException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /** Ni tabla, ni restriccion, ni SQL: solo que ya existe. */
    private static <T> T conflictoSiChoca(java.util.function.Supplier<T> escribir) {
        try {
            return escribir.get();
        } catch (DuplicateKeyException repetido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, "Ese dato ya esta registrado para este contribuyente");
        }
    }

    private static LocalDate parsear(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + fecha + "'");
        }
    }

    private static TipoDomicilio tipoDomicilioDe(@Nullable String texto) {
        try {
            return TipoDomicilio.valueOf(normalizar(exigir(texto, "tipo")));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de domicilio desconocido: '" + texto + "'");
        }
    }

    private static TipoContacto tipoContactoDe(@Nullable String texto) {
        try {
            return TipoContacto.valueOf(normalizar(exigir(texto, "tipo")));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de contacto desconocido: '" + texto + "'");
        }
    }

    private static Vinculo vinculoDe(@Nullable String texto) {
        try {
            return Vinculo.valueOf(normalizar(exigir(texto, "vinculo")));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Vinculo desconocido: '" + texto + "'");
        }
    }

    /**
     * El porcentaje llega como <b>texto</b>, no como numero: un {@code double} en el JSON perderia
     * escala antes de que nadie lo mire (regla 1).
     */
    private static @Nullable Porcentaje porcentajeDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Porcentaje.de(texto.strip());
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Porcentaje no valido: '" + texto + "'");
        }
    }

    private static String normalizar(String texto) {
        return texto.strip().toUpperCase(Locale.ROOT);
    }

    private static @Nullable String vacioANulo(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    /**
     * El cuerpo de una mudanza. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>No lleva {@code vigenciaHasta}: el domicilio que se abre esta abierto, y cerrarlo es lo
     * que hace la mudanza siguiente. Un domicilio que naciera ya cerrado no serviria para notificar
     * a nadie.
     */
    public record PeticionDeDomicilio(
            @Nullable String observacion,
            @Nullable String tipo,
            @Nullable String direccion,
            @Nullable String referencia,
            @Nullable String ubigeo,
            @Nullable String vigenciaDesde,
            @Nullable String documentoOrigen) {}

    /**
     * El cuerpo de un contacto. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>{@code nota} es la observacion <b>del contacto</b> —«llamar despues de las 6»—, y {@code
     * observacion} es la del usuario que guarda (regla 10). Se llaman distinto a proposito: la
     * tabla las tiene con el mismo nombre, y un cuerpo con dos {@code observacion} acabaria
     * escribiendo una en el sitio de la otra sin que nada lo dijera.
     */
    public record PeticionDeContacto(
            @Nullable String observacion,
            @Nullable String tipo,
            @Nullable String valor,
            @Nullable String nombre,
            @Nullable String documento,
            @Nullable String nota,
            @Nullable Boolean vigente) {}

    /** El cuerpo de un responsable solidario. <b>Lista blanca</b>. */
    public record PeticionDeResponsable(
            @Nullable String observacion,
            @Nullable Long responsableId,
            @Nullable String vinculo,
            @Nullable String porcentaje,
            @Nullable String vigenciaDesde,
            @Nullable String documentoOrigen) {}

    /** El cuerpo de un cierre de vinculo: la fecha y la observacion, nada mas. */
    public record PeticionDeCierre(@Nullable String observacion, @Nullable String vigenciaHasta) {}
}
