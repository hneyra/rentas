package kamayuk.rentas.catastro.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ActualizarFichaCatastral;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeLaFichaVigente;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las cuatro fichas del manual, por codigo de referencia catastral (RF-001 a RF-004).
 *
 * <p>Un controlador y no cuatro, porque la pregunta es la misma —«dame la ficha de este predio»— y
 * lo unico que cambia es el tipo. Lo que si cambia por metodo es el {@code acceso}: cada una es una
 * opcion distinta del menu, con sus propios permisos.
 *
 * <p><b>Las rutas nombran el parametro de tres maneras</b> —{@code codRefCatastral}, {@code
 * codEdificacion}, {@code codUnidad}— y las tres reciben lo mismo: el codigo de referencia
 * catastral. La edificacion en propiedad exclusiva y comun y la unidad catastral rural son predios
 * del padron, con su propio codigo; no hacen falta dos numeraciones mas. Los nombres se respetan
 * porque son los del contrato, y el contrato salio de las pantallas del prototipo.
 *
 * <p>Se entra por el <b>codigo de referencia catastral</b>, no por el identificador interno del
 * predio: es lo que el tecnico tiene delante y lo que el contrato declara en la ruta.
 *
 * <p><b>Acepta una fecha.</b> Sin ella devuelve la ficha que rige hoy; con ella, la que regia
 * entonces. Es lo que permite responder «como estaba este predio cuando se emitio el valor de
 * 2027», que es exactamente la pregunta de una reclamacion. La respuesta lleva siempre la version y
 * su vigencia, para que ninguna cifra salga sin decir de cuando es (regla 9).
 *
 * <h2>Y el alta: el predio nace con su primera ficha</h2>
 *
 * <p>El {@code POST} de cada tipo inscribe la <b>primera version</b> de la ficha y, si el predio
 * todavia no existe, lo da de alta <b>en el mismo acto</b> (#290). No es una comodidad: {@code
 * ficha_catastral.predio_id} es {@code NOT NULL}, asi que sin el predio no hay ficha que registrar,
 * y hacerlo en dos peticiones dejaria predios sin ficha cada vez que la segunda falle. La
 * atomicidad la sostiene {@link InscribirFicha}, con una transaccion para las tres escrituras
 * —predio, ficha y titularidad— y <b>una sola observacion</b> para las tres filas de auditoria.
 *
 * <p>Si el predio ya existe con ese codigo se usa el que hay: un predio tiene una ficha de cada
 * tipo, y la segunda no crea un predio nuevo. Si ya tiene ficha <b>de ese tipo</b>, es {@code 409}
 * —lo que toca entonces es actualizarla, y eso es el {@code PUT} de {@link
 * ActualizacionController}—.
 *
 * <p><b>El alta escribe, asi que exige {@code REGISTRO}</b> sobre la opcion de su tipo, no sobre
 * una comun: cada ficha es una opcion distinta del menu, y quien levanta el catastro rural no tiene
 * por que poder abrir fichas economicas.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/fichas")
@RequiereAcceso(acceso = "ficha_urbana", privilegio = Privilegio.LECTURA)
public class FichaController {

    private final ActualizarFichaCatastral fichas;
    private final ConsultaDeFichas consulta;
    private final InscribirFicha inscribir;
    private final ConsultaDeLaFichaVigente fichaVigente;
    private final Clock reloj;

    public FichaController(
            ActualizarFichaCatastral fichas,
            ConsultaDeFichas consulta,
            InscribirFicha inscribir,
            ConsultaDeLaFichaVigente fichaVigente,
            Clock reloj) {
        this.fichas = fichas;
        this.consulta = consulta;
        this.inscribir = inscribir;
        this.fichaVigente = fichaVigente;
        this.reloj = reloj;
    }

    @GetMapping("/urbana/{codRefCatastral}")
    public FichaResource urbana(
            @PathVariable String codRefCatastral,
            @RequestParam(required = false) @Nullable String fecha,
            @RequestParam(required = false, defaultValue = "false") boolean historico) {
        return leer(codRefCatastral, TipoFicha.UNICA, fecha, "urbana", historico);
    }

    @GetMapping("/economica/{codRefCatastral}")
    @RequiereAcceso(acceso = "ficha_economica", privilegio = Privilegio.LECTURA)
    public FichaResource economica(
            @PathVariable String codRefCatastral,
            @RequestParam(required = false) @Nullable String fecha,
            @RequestParam(required = false, defaultValue = "false") boolean historico) {
        return leer(codRefCatastral, TipoFicha.ECONOMICA, fecha, "economica", historico);
    }

    @GetMapping("/bienes-comunes/{codEdificacion}")
    @RequiereAcceso(acceso = "ficha_bienes", privilegio = Privilegio.LECTURA)
    public FichaResource bienesComunes(
            @PathVariable String codEdificacion,
            @RequestParam(required = false) @Nullable String fecha,
            @RequestParam(required = false, defaultValue = "false") boolean historico) {
        return leer(
                codEdificacion, TipoFicha.BIENES_COMUNES, fecha, "de bienes comunes", historico);
    }

    @GetMapping("/rural/{codUnidad}")
    @RequiereAcceso(acceso = "ficha_rural", privilegio = Privilegio.LECTURA)
    public FichaResource rural(
            @PathVariable String codUnidad,
            @RequestParam(required = false) @Nullable String fecha,
            @RequestParam(required = false, defaultValue = "false") boolean historico) {
        return leer(codUnidad, TipoFicha.RURAL, fecha, "rural", historico);
    }

    // ── Alta: la primera version, y el predio si no estaba ─────────────

    @PostMapping("/urbana")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "ficha_urbana", privilegio = Privilegio.REGISTRO)
    public FichaResource registrarUrbana(@RequestBody PeticionDeAlta peticion) {
        return inscribir(peticion, TipoFicha.UNICA);
    }

    @PostMapping("/economica")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "ficha_economica", privilegio = Privilegio.REGISTRO)
    public FichaResource registrarEconomica(@RequestBody PeticionDeAlta peticion) {
        return inscribir(peticion, TipoFicha.ECONOMICA);
    }

    @PostMapping("/bienes-comunes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "ficha_bienes", privilegio = Privilegio.REGISTRO)
    public FichaResource registrarBienesComunes(@RequestBody PeticionDeAlta peticion) {
        return inscribir(peticion, TipoFicha.BIENES_COMUNES);
    }

    @PostMapping("/rural")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "ficha_rural", privilegio = Privilegio.REGISTRO)
    public FichaResource registrarRural(@RequestBody PeticionDeAlta peticion) {
        return inscribir(peticion, TipoFicha.RURAL);
    }

    /**
     * Un solo camino para las cuatro altas, con el tipo como unica diferencia.
     *
     * <p>Todo lo que puede fallar se traduce aqui, y cada cosa a lo que es: lo que no existe
     * —sector, manzana, via, contribuyente— a {@code 404}; lo que el estado no admite —el predio ya
     * tiene ficha de ese tipo, o esta dado de baja— a {@code 409}; y el codigo repetido que se
     * cuela entre la lectura y el {@code INSERT}, tambien a {@code 409}, sin nombrar la
     * restriccion.
     */
    private FichaResource inscribir(PeticionDeAlta peticion, TipoFicha tipo) {
        var observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());

        InscribirFicha.DatosDelPredio predio = predioDeclarado(peticion);
        InscribirFicha.DatosDeLaFicha ficha = fichaDeclarada(peticion, tipo);

        try {
            return FichaResource.de(
                    inscribir.inscribir(
                            predio,
                            ficha,
                            DeclaracionDeFicha.titularDe(peticion.titular()),
                            observacion));
        } catch (ActualizarFichaCatastral.YaTieneFicha yaTiene) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "El predio "
                            + predio.codigo().valor()
                            + " ya tiene ficha "
                            + tipo
                            + "; modificarla es crear su version siguiente, no otra primera");
        } catch (InscribirFicha.PredioDadoDeBaja dadoDeBaja) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, DeclaracionDeFicha.mensajeDe(dadoDeBaja));
        } catch (InscribirFicha.ReferenciaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, DeclaracionDeFicha.mensajeDe(noExiste));
        } catch (DuplicateKeyException repetido) {
            // Ni tabla, ni restriccion, ni SQL: solo el dato que el usuario escribio.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya existe un predio con el codigo de referencia catastral '"
                            + predio.codigo().valor()
                            + "' en esta municipalidad");
        }
    }

    /** Los datos del predio. Solo se usan si hay que darlo de alta; el codigo, siempre. */
    private InscribirFicha.DatosDelPredio predioDeclarado(PeticionDeAlta peticion) {
        return new InscribirFicha.DatosDelPredio(
                DeclaracionDeFicha.referenciaDe(
                        DeclaracionDeFicha.exigir(peticion.codRefCatastral(), "codRefCatastral")),
                tipoDePredio(peticion.tipoPredio()),
                DeclaracionDeFicha.exigir(peticion.direccion(), "direccion"),
                DeclaracionDeFicha.vacioANulo(peticion.codigoDeVia()),
                DeclaracionDeFicha.vacioANulo(peticion.numeroMunicipal()),
                DeclaracionDeFicha.vacioANulo(peticion.codigoDeSector()),
                DeclaracionDeFicha.vacioANulo(peticion.codigoDeManzana()),
                DeclaracionDeFicha.vacioANulo(peticion.lote()),
                DeclaracionDeFicha.vacioANulo(peticion.ubigeo()));
    }

    /**
     * La primera version de la ficha.
     *
     * <p>Las listas ausentes son <b>vacias</b>, no «lo que tenia»: no hay version anterior de la
     * que copiar. Esa es la unica diferencia de semantica con el {@code PUT}, y esta aqui para que
     * se vea.
     */
    private InscribirFicha.DatosDeLaFicha fichaDeclarada(PeticionDeAlta peticion, TipoFicha tipo) {
        List<Construccion> construcciones =
                DeclaracionDeFicha.construccionesDe(peticion.construcciones());
        List<OtraInstalacion> instalaciones =
                DeclaracionDeFicha.instalacionesDe(peticion.instalaciones());

        return new InscribirFicha.DatosDeLaFicha(
                tipo,
                DeclaracionDeFicha.areaDe(peticion.areaTerreno(), "areaTerreno"),
                DeclaracionDeFicha.exigir(peticion.uso(), "uso"),
                DeclaracionDeFicha.vacioANulo(peticion.denominacion()),
                peticion.vigenciaDesde() == null
                        ? LocalDate.now(reloj)
                        : DeclaracionDeFicha.fechaDe(peticion.vigenciaDesde(), "vigenciaDesde"),
                DeclaracionDeFicha.origenDe(peticion.origen()),
                DeclaracionDeFicha.exigir(peticion.documentoOrigen(), "documentoOrigen"),
                construcciones == null ? List.of() : construcciones,
                instalaciones == null ? List.of() : instalaciones,
                DeclaracionDeFicha.detalleDe(
                        tipo, peticion.economico(), peticion.bienesComunes(), peticion.rural()));
    }

    /** Sin tipo declarado, urbano: es el caso de la inmensa mayoria del padron. */
    private static TipoPredio tipoDePredio(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return TipoPredio.URBANO;
        }
        try {
            return TipoPredio.valueOf(texto.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Tipo de predio desconocido: '" + texto + "'");
        }
    }

    // ── Lectura ────────────────────────────────────────────────────────

    /**
     * Un solo camino para los cuatro tipos.
     *
     * <p>Que la fecha se resuelva aqui y no en cada metodo es lo que impide que uno de los cuatro
     * acabe respondiendo «la ultima» en vez de «la vigente a la fecha». Es el mismo defecto que ya
     * aparecio en el domicilio del contribuyente, y se corrige una vez.
     */
    private FichaResource leer(
            String codigo,
            TipoFicha tipo,
            @Nullable String fecha,
            String comoSeLlama,
            boolean historico) {

        LocalDate cuando = fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : parsear(fecha);

        // Las tres consultas —el predio, su ficha y su historial— van en **una** transaccion, que
        // es lo que emite el `SET LOCAL` que RLS exige y lo que hace la respuesta coherente consigo
        // misma. Resolver el predio aqui costaba un 500 en las cuatro rutas (#486).
        ConsultaDeLaFichaVigente.Encontrada encontrada =
                fichaVigente
                        .porCodigo(DeclaracionDeFicha.referenciaDe(codigo), tipo, cuando, historico)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun predio con ese codigo de referencia"
                                                        + " catastral"));

        FichaCatastral ficha = encontrada.ficha();
        if (ficha == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO,
                    "El predio no tiene ficha " + comoSeLlama + " vigente al " + cuando);
        }

        // El historico no viaja siempre: son todas las versiones de la ficha, y la pantalla que
        // solo pinta la vigente no tiene por que pagarlas. Cuando no se pide, el campo sale nulo
        // —no una lista vacia—, que es la diferencia entre «no lo pediste» y «no hay ninguna».
        List<VersionDeLaFicha> historial = encontrada.historial();
        return historial == null ? FichaResource.de(ficha) : FichaResource.con(ficha, historial);
    }

    private static LocalDate parsear(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + fecha + "'");
        }
    }

    /**
     * El mensaje de una excepcion es {@code @Nullable} para el verificador. Aqui nunca lo es —los
     * objetos de valor siempre explican por que rechazan—, pero decirlo con un texto de reserva
     * cuesta menos que discutirlo.
     */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo de un alta de ficha, el mismo para los cuatro tipos. <b>Lista blanca</b>: lo que no
     * esta aqui no entra, aunque llegue en el JSON.
     *
     * <p>Lleva tres cosas: <b>el predio</b> —su codigo de referencia catastral y donde esta—, <b>la
     * primera version de la ficha</b> y, opcional, <b>su titular</b>. Los tres bloques de detalle
     * conviven y solo entra el del tipo que la ruta declara; mandar el de otro es {@code 422} y no
     * un campo ignorado en silencio.
     *
     * <p>El titular es opcional porque en un levantamiento catastral se ficha el predio antes de
     * identificar a su propietario, y exigirlo obligaria al tecnico a inventarse uno (DAT-01 §4.2).
     *
     * <p>Ni un importe: categorias, areas, superficies y porcentajes (regla 5, D-02a). El autovaluo
     * lo calcula rentas, y esta bloqueado.
     */
    public record PeticionDeAlta(
            @Nullable String observacion,
            // El predio
            @Nullable String codRefCatastral,
            @Nullable String tipoPredio,
            @Nullable String direccion,
            @Nullable String codigoDeVia,
            @Nullable String numeroMunicipal,
            @Nullable String codigoDeSector,
            @Nullable String codigoDeManzana,
            @Nullable String lote,
            @Nullable String ubigeo,
            // La primera version de la ficha
            @Nullable String areaTerreno,
            @Nullable String uso,
            @Nullable String denominacion,
            @Nullable String vigenciaDesde,
            @Nullable String origen,
            @Nullable String documentoOrigen,
            @Nullable List<DeclaracionDeFicha.ConstruccionDeclarada> construcciones,
            @Nullable List<DeclaracionDeFicha.InstalacionDeclarada> instalaciones,
            DeclaracionDeFicha.@Nullable EconomicoDeclarado economico,
            DeclaracionDeFicha.@Nullable BienesComunesDeclarados bienesComunes,
            DeclaracionDeFicha.@Nullable RuralDeclarado rural,
            // Su titular, si ya se conoce
            DeclaracionDeFicha.@Nullable TitularDeclarado titular) {}
}
