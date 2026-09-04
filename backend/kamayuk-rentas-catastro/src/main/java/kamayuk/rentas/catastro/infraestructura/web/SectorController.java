package kamayuk.rentas.catastro.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeSectores;
import kamayuk.rentas.catastro.aplicacion.RegistrarManzana;
import kamayuk.rentas.catastro.aplicacion.RegistrarSector;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.dominio.Observacion;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogo territorial: {@code GET/POST /api/v1/catastro/sectores}, {@code PUT
 * /api/v1/catastro/sectores/{codigo}} y {@code GET/POST
 * /api/v1/catastro/sectores/{codigo}/manzanas}.
 *
 * <p>Es la operacion {@code sectores} del contrato —«Sectores, manzanas y lotes»—. Se publicaba
 * solo el listado (#16); con #290 gana el lado de escritura que el manual pide en su pantalla de
 * mantenimiento. Sigue paso por paso lo que {@link ViaController} dejo escrito para {@code calles}:
 * el alta en {@code POST}, la edicion y la baja logica en un solo {@code PUT}, la observacion
 * obligatoria en el cuerpo, y el codigo repetido traducido a {@code 409}.
 *
 * <p><b>Las manzanas cuelgan del sector y no tienen ruta propia</b>: se dan de alta bajo el codigo
 * del sector al que pertenecen, que es como se identifican —la manzana 001 del sector 01 y la 001
 * del sector 02 son manzanas distintas—. Comparten esta opcion del catalogo, {@code sectores}, y
 * por tanto su acceso: no hay una pantalla de manzanas separada en el manual de la que sacar otro.
 *
 * <p><b>Declara su acceso</b>: {@code sectores} es el id de esta opcion en el catalogo de pantallas
 * (NEG-03), el mismo que la siembra pone en la tabla {@code acceso}. Una regla de ArchUnit rompe el
 * build si un endpoint no lo declara. La lectura exige {@code LECTURA} (de la clase); las altas
 * —sector y manzana—, {@code REGISTRO}; la edicion y la baja logica, {@code MODIFICACION}.
 *
 * <p><b>Y la baja exige ademas {@code ELIMINACION}</b>, comprobado aqui dentro por lo mismo que en
 * {@link ViaController}: la anotacion declara lo que exige la <i>ruta</i>, y la ruta es una sola
 * para editar y para retirar del catalogo; cual de las dos es depende del cuerpo, que el guardia no
 * lee. La comprobacion usa el mismo puerto que el guardia —{@link ComprobadorDeAcceso}, con el
 * usuario de {@link OrigenContext} y la fecha del reloj inyectado— y lanza el mismo {@code
 * ProblemaDeNegocio} con {@code SIN_PRIVILEGIO}, para que negar por esta via no se distinga de
 * negar por aquella.
 *
 * <p><b>La lectura pasa por {@link ConsultaDeSectores}</b>, no por el repositorio directamente: es
 * esa capa la que lleva el {@code @Transactional(readOnly = true)} donde se fija el tenant. Sin
 * ella la consulta corre sin {@code SET LOCAL} y la politica RLS de {@code sector} falla —el mismo
 * defecto que la marcha blanca destapo en {@code GET /catastro/vias}, y que aqui seguia vivo—. La
 * escritura pasa por {@link RegistrarSector} y {@link RegistrarManzana}, que llevan su
 * {@code @Transactional} y asientan la auditoria en la misma transaccion.
 *
 * <p><b>La observacion del usuario es obligatoria en toda escritura</b> (regla 10, RNF-052): viaja
 * en el cuerpo y se convierte en {@link Observacion} antes de tocar el caso de uso; si viene vacia,
 * la peticion es 422 y no se guarda nada.
 *
 * <p><b>Ningun metodo recibe la municipalidad</b>, ni como parametro ni como encabezado ni en el
 * cuerpo: sale del token (ADR-0005, regla 2).
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/sectores")
@RequiereAcceso(acceso = SectorController.ACCESO, privilegio = Privilegio.LECTURA)
public class SectorController {

    /** Id de esta opcion en el catalogo de pantallas (NEG-03) y en la tabla {@code acceso}. */
    static final String ACCESO = "sectores";

    /** Por codigo: el codigo del sector es un tramo del codigo catastral, y se lee en ese orden. */
    private static final String ORDEN_POR_OMISION = "codigo";

    /**
     * Y las manzanas, tambien por codigo: es otro tramo del mismo codigo, y es como se recorre una
     * manzana tras otra al sanear una zona. Ademas es unico dentro del sector, asi que ordena de
     * forma total y dos paginas consecutivas no pueden repetir una fila ni saltarse otra.
     */
    private static final String ORDEN_DE_MANZANAS = "codigo";

    private final ConsultaDeSectores consulta;
    private final RegistrarSector registrarSector;
    private final RegistrarManzana registrarManzana;
    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public SectorController(
            ConsultaDeSectores consulta,
            RegistrarSector registrarSector,
            RegistrarManzana registrarManzana,
            ComprobadorDeAcceso comprobador,
            Clock reloj) {
        this.consulta = consulta;
        this.registrarSector = registrarSector;
        this.registrarManzana = registrarManzana;
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    /**
     * El catalogo de sectores, con <b>los conteos de cada uno</b>: manzanas, predios activos y
     * lotes (#290). Los cuenta la base sobre la pagina ya limitada; que significa exactamente cada
     * cifra esta en {@link SectorResource}.
     */
    @GetMapping
    public RespuestaPaginada<SectorResource> listar(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                consulta.listar(paginacion.aPaginacion(ORDEN_POR_OMISION)), SectorResource::de);
    }

    /**
     * Alta de un sector del catastro.
     *
     * <p>{@code codigo} y {@code nombre} son obligatorios: no hay sector anterior del que
     * heredarlos. {@code zona} es opcional, igual que la columna.
     *
     * <p><b>Un sector nace activo</b>, y el {@code activo} del cuerpo se ignora: darlo de alta ya
     * retirado del catalogo seria un alta y una baja en un solo acto, y dejaria la auditoria con un
     * {@code ALTA} donde hubo dos cosas. Para retirarlo esta el {@code PUT}, que ademas exige
     * {@code ELIMINACION}.
     *
     * <p>Un codigo que ya existe sale como {@code 409} y no como incidencia: la unicidad la exige
     * la base —es la unica que puede—, pero su mensaje nombra la tabla y la restriccion, asi que se
     * traduce aqui. Lo que el cliente recibe dice que el codigo esta tomado y nada mas.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public SectorResource registrar(@RequestBody PeticionDeSector peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        String codigo = exigir(peticion.codigo(), "codigo");
        Sector nuevo =
                new Sector(
                        null,
                        codigo,
                        exigir(peticion.nombre(), "nombre"),
                        vacioANulo(peticion.zona()),
                        true);
        try {
            return SectorResource.de(registrarSector.registrar(nuevo, observacion));
        } catch (DuplicateKeyException repetido) {
            // Ni tabla, ni restriccion, ni SQL: solo el dato que el usuario escribio.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya existe un sector con el codigo '" + codigo + "' en esta municipalidad");
        }
    }

    /**
     * Edicion de un sector ya existente, o su baja logica.
     *
     * <p><b>Lo que no viene, no cambia.</b> Todo campo ausente —{@code null}— conserva el valor que
     * el sector ya tiene: {@code nombre}, {@code zona} y {@code activo}. Para <b>borrar</b> la zona
     * se manda la cadena vacia, que es una instruccion y no una omision; la columna la admite nula.
     *
     * <p>La {@code observacion} sigue siendo obligatoria (regla 10, RNF-052): sin ella, 422.
     *
     * <p>El {@code codigo} de la ruta identifica el sector y <b>no se cambia</b> por esta
     * operacion: el {@code codigo} del cuerpo, si viene, se ignora. No es una comodidad: el codigo
     * del sector es uno de los tramos del codigo de referencia catastral, y cambiarlo desalinearia
     * el codigo de todos los predios del sector. {@code activo = false} es la baja, y exige ademas
     * el privilegio {@code ELIMINACION} —ver el javadoc de la clase—.
     */
    @PutMapping("/{codigo}")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.MODIFICACION)
    public SectorResource modificar(
            @PathVariable String codigo, @RequestBody PeticionDeSector peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        Sector existente = exigirSector(codigo);
        Sector cambiado =
                new Sector(
                        existente.id(),
                        existente.codigo(),
                        peticion.nombre() == null
                                ? existente.nombre()
                                : exigir(peticion.nombre(), "nombre"),
                        peticion.zona() == null ? existente.zona() : vacioANulo(peticion.zona()),
                        peticion.activo() == null ? existente.activo() : peticion.activo());

        if (existente.activo() && !cambiado.activo()) {
            exigirPrivilegioDeBaja();
        }
        return SectorResource.de(registrarSector.editar(existente, cambiado, observacion));
    }

    /**
     * Las manzanas de un sector, con <b>los conteos de cada una</b>: predios activos y lotes
     * (#537).
     *
     * <p><b>Pagina como el resto de listados</b>, y no es una formalidad: un sector de una
     * municipalidad grande pasa de mil manzanas, y devolverlas todas obligaria a la interfaz a
     * dibujarlas todas para poder ensenar las diez primeras del arbol.
     *
     * <p><b>Un sector que no existe es {@code 404}, no una pagina vacia.</b> Las dos cosas se
     * parecen mucho —cero filas— y significan lo contrario: «ese codigo no esta en el catalogo» y
     * «ese sector todavia no tiene manzanas». La diferencia la lleva el {@link java.util.Optional}
     * que devuelve {@link ConsultaDeSectores#manzanasDelSector}, para que no se pueda perder por el
     * camino.
     *
     * <p><b>Exige {@code LECTURA} sobre {@code sectores}</b>, el mismo acceso que el listado de
     * sectores y por la misma razon que el alta de manzana: las manzanas no tienen pantalla propia
     * en el manual —cuelgan del arbol territorial de «Sectores, manzanas y lotes»—, asi que no hay
     * de donde sacar otro id de opcion. Lo hereda de la clase, que declara ese acceso con ese
     * privilegio; no se repite aqui porque repetirlo no anadiria nada y la regla de ArchUnit admite
     * la anotacion «en la clase o en cada endpoint».
     */
    @GetMapping("/{codigo}/manzanas")
    public RespuestaPaginada<ManzanaResource> listarManzanas(
            @PathVariable String codigo, ParametrosDePaginacion paginacion) {
        Pagina<ManzanaConConteos> pagina =
                consulta.manzanasDelSector(codigo, paginacion.aPaginacion(ORDEN_DE_MANZANAS))
                        .orElseThrow(() -> sectorInexistente(codigo));
        return RespuestaPaginada.de(pagina, ManzanaResource::de);
    }

    /**
     * Alta de una manzana dentro de un sector.
     *
     * <p><b>No hay {@code PUT}: una manzana no se edita.</b> Es la misma razon que {@link
     * RegistrarManzana} da y que aqui se repite porque es la que explica el hueco: su codigo es un
     * tramo del codigo catastral de sus predios, asi que cambiarlo desalinearia el codigo de todos
     * ellos. Lo que se hace con una manzana equivocada es dar de alta la correcta y mover los
     * predios, no reescribir la que ya esta en los valores emitidos.
     *
     * <p>El sector se identifica por su <b>codigo</b>, el de la ruta —es lo que teclearia una
     * persona y lo que trae un archivo de importacion—. Si no existe, {@code 404}, y se decide
     * <b>aqui</b>, leyendolo antes: el caso de uso lo rechaza dentro de su transaccion con un
     * {@link IllegalArgumentException}, que el manejador global traduce a 422 —«la peticion no
     * cumple una regla de validacion»— y eso no es lo que paso. Un recurso que no esta es un 404.
     *
     * <p>Un codigo de manzana ya usado <b>dentro de ese sector</b> es {@code 409}; el mismo codigo
     * en otro sector es una manzana distinta y entra con normalidad.
     */
    @PostMapping("/{codigo}/manzanas")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public ManzanaResource registrarManzana(
            @PathVariable String codigo, @RequestBody PeticionDeManzana peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        String codigoDeManzana = exigir(peticion.codigo(), "codigo");
        // El 404 se decide con el sector leido, y no traduciendo la excepcion del caso de uso:
        // esa misma excepcion la lanza tambien un codigo de manzana fuera de rango, que si es un
        // 422. Distinguirlas por el texto del mensaje seria atarse a como esta redactado.
        Sector sector = exigirSector(codigo);
        try {
            return ManzanaResource.de(
                    registrarManzana.registrarPorCodigoDeSector(
                            codigo, codigoDeManzana, observacion),
                    sector.codigo());
        } catch (DuplicateKeyException repetido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO,
                    "Ya existe una manzana con el codigo '"
                            + codigoDeManzana
                            + "' en el sector '"
                            + codigo
                            + "'");
        }
    }

    // ------------------------------------------------------------------

    private Sector exigirSector(String codigo) {
        return consulta.buscarPorCodigo(codigo).orElseThrow(() -> sectorInexistente(codigo));
    }

    /** Un solo texto para el 404 del sector, lo pida el listado de manzanas o el alta de una. */
    private static ProblemaDeNegocio sectorInexistente(String codigo) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "No hay ningun sector con codigo '" + codigo + "' en esta municipalidad");
    }

    /**
     * Retirar un sector del catalogo exige {@code ELIMINACION}, y se pregunta por el mismo puerto
     * que usa el guardia.
     *
     * <p>No se toca {@link kamayuk.rentas.autorizacion.GuardiaDeAcceso}: la anotacion no puede
     * expresar «segun lo que traiga el cuerpo» y el interceptor corre antes de que el cuerpo se
     * lea. Lo que si se conserva es la respuesta: el mismo {@code CodigoDeError.SIN_PRIVILEGIO} y
     * un mensaje de la misma forma, para que negar por esta via no se distinga de negar por
     * aquella.
     */
    private void exigirPrivilegioDeBaja() {
        String usuario = OrigenContext.actual().usuario();
        if (!comprobador.autoriza(usuario, ACCESO, Privilegio.ELIMINACION, LocalDate.now(reloj))) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "No tiene el privilegio " + Privilegio.ELIMINACION + " sobre " + ACCESO);
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
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

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static @Nullable String vacioANulo(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo de un alta o una edicion de sector. <b>Lista blanca</b>: lo que no esta aqui no
     * entra, aunque llegue en el JSON. Las manzanas del sector no estan porque tienen su propia
     * ruta: un alta anidada las dejaria sin su propia observacion.
     */
    public record PeticionDeSector(
            @Nullable String observacion,
            @Nullable String codigo,
            @Nullable String nombre,
            @Nullable String zona,
            @Nullable Boolean activo) {}

    /**
     * El cuerpo de un alta de manzana. <b>Lista blanca</b>: el {@code sectorId} no entra —el sector
     * lo dice la ruta, por su codigo— y no hay nada mas que una manzana lleve.
     */
    public record PeticionDeManzana(@Nullable String observacion, @Nullable String codigo) {}
}
