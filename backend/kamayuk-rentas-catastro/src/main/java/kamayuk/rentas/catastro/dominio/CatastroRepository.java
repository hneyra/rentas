package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;

/**
 * Predios, catalogos territoriales y titularidad.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). Ninguno borra: los predios y los sectores se
 * dan de baja, las titularidades se cierran (regla 4, RNF-051).
 */
public interface CatastroRepository {

    // ---------- Sectores y manzanas ----------

    /**
     * El catalogo de sectores con sus conteos (#290).
     *
     * <p>Devuelve {@link SectorConConteos} y no {@link Sector} porque la pantalla de sectores del
     * manual muestra las tres cifras junto a cada fila, y contarlas es cosa de la base: hacerlo
     * arriba —traerse las manzanas y los predios de cada sector para llamar a {@code size()}— seria
     * traerse el padron entero para escribir un numero de dos digitos.
     *
     * <p>Los conteos son de <b>la pagina</b>, no del catalogo: se cuentan los sectores que la
     * pagina devuelve y ningun otro.
     */
    Pagina<SectorConConteos> sectores(Paginacion paginacion);

    Optional<Sector> sectorPorCodigo(String codigo);

    Optional<Sector> sectorPorId(long id);

    Sector guardar(Sector sector);

    List<Manzana> manzanasDe(long sectorId);

    /**
     * Las manzanas de un sector, paginadas y con sus conteos (#537).
     *
     * <p>Convive con {@link #manzanasDe(long)} y no lo sustituye: aquel devuelve la lista entera
     * porque lo llama la importacion, que necesita todas para resolver codigos; este es la lectura
     * de una pantalla, y un sector de una municipalidad grande pasa de mil manzanas.
     *
     * <p>Recibe el {@link Sector} entero y no su identificador porque necesita las dos cosas que
     * tiene: el {@code id} para acotar, y el {@code codigo} para que cada fila diga de que sector
     * cuelga sin que nadie lo vuelva a leer.
     *
     * <p>Los conteos son de <b>la pagina</b>, no del sector: se cuentan las manzanas que la pagina
     * devuelve y ninguna otra, igual que en {@link #sectores(Paginacion)}.
     */
    Pagina<ManzanaConConteos> manzanas(Sector sector, Paginacion paginacion);

    Manzana guardar(Manzana manzana);

    // ---------- Predios ----------

    Optional<Predio> predio(long id);

    Optional<Predio> predioPorCodigo(CodigoReferenciaCatastral codigo);

    /**
     * El padron de predios del catastro, con su ubicacion resuelta a codigos y si estan fichados.
     *
     * <p>Sustituye al listado sin filtros que habia hasta #400 y que no llamaba nadie: la pantalla
     * de saneamiento necesita acotar por sector, por prefijo de codigo y por estado, y sobre todo
     * necesita poder pedir <b>los que no tienen ficha</b>, que es lo que ninguna consulta del
     * sistema sabia responder.
     */
    Pagina<PredioDelCatastro> predios(FiltroDePredios filtro, Paginacion paginacion);

    /**
     * Los lotes con poligono que caen en el marco, hasta {@code tope} (ADR-0022, #536).
     *
     * <p><b>No pagina, y no es un olvido</b>: la paginacion de un plano no significa nada —no hay
     * un orden que convierta «la pagina 2» en una porcion del territorio— y un plano al que le
     * faltan lotes se lee como un plano donde no hay lotes. Las dos maneras legitimas de terminar
     * son «cabe» y «acercate», y de la segunda se encarga quien llama comparando contra {@link
     * #lotesEnElMarco(FiltroDelPlano)}.
     *
     * <p>Por eso {@code tope} es un limite de <b>seguridad</b> y no una pagina: quien llama pide
     * uno mas de los que admite, y si vuelven todos es que no cabe.
     *
     * @param tope cuantas filas como maximo se traen del motor
     */
    List<LoteDelPlano> lotesDelPlano(FiltroDelPlano filtro, int tope);

    /**
     * Cuantos lotes con poligono caen en el marco, sin traerlos.
     *
     * <p>Existe para poder decir la cifra al negarse: «hay 4 812 lotes en este marco y el tope es 2
     * 000» es una respuesta que se puede obedecer; «no caben» no lo es. Solo se pregunta cuando ya
     * se sabe que no cabe, asi que el camino normal no la paga.
     */
    long lotesEnElMarco(FiltroDelPlano filtro);

    /**
     * Cuantos predios alcanzados por los mismos <b>filtros</b> no tienen poligono (ADR-0022 §3).
     *
     * <p>Los filtros, no el marco, y el motivo esta en {@link PlanoDelCatastro}: un predio sin
     * poligono no tiene sitio en el marco, y el unico que podria situarlo —el perimetro de su
     * manzana— no existe y no se deriva.
     */
    long prediosSinGeometria(FiltroDelPlano filtro);

    /**
     * El rectangulo que envuelve la geometria ya cargada, con los <b>mismos</b> filtros y sin marco
     * (#612).
     *
     * <p>Es lo unico que el sistema sabe decir sobre <b>donde esta</b> esta municipalidad, y sale
     * de un agregado sobre las cuatro columnas de {@code V65} —las que ya usa {@link
     * #lotesDelPlano(FiltroDelPlano, int)} para acotar—, no de {@code ST_Extent}: el agregado de
     * PostGIS trabaja sobre la geometria y estas columnas son las que el motor deriva de ella y
     * mantiene junto a cada fila.
     *
     * <p>Recibe {@link AcotacionDelPlano} y no {@link FiltroDelPlano} porque esta es la lectura que
     * <b>no puede</b> tener marco: existe para calcularlo. Que el tipo sea el mismo que compone
     * aquel es lo que garantiza que las dos respondan sobre el mismo conjunto de predios.
     *
     * <p>Nunca devuelve {@code null} ni {@link java.util.Optional}: la ausencia de marco es una
     * respuesta con su cuenta, no una fila que falta (ver {@link MarcoDeLoLevantado}).
     */
    MarcoDeLoLevantado marcoDeLoLevantado(AcotacionDelPlano acotacion);

    Predio guardar(Predio predio);

    /**
     * Guarda el poligono del lote, en WKT y en WGS84 (ADR-0021, V61).
     *
     * <p>Va aparte de {@link #guardar(Predio)} y no como un campo mas de {@link Predio}, y no es
     * comodidad: la geometria no entra por ninguna operacion del contrato —entra por la carga
     * cartografica— y meterla en el record obligaria a arrastrarla por los treinta sitios donde se
     * construye un predio, casi todos con un {@code null} que no significa nada.
     *
     * <p>Es un {@code UPDATE} sobre {@code predio}: la geometria se corrige cuando el plano se
     * corrige, y de eso no queda version. Lo que se versiona es la ficha, no el lote.
     *
     * @param wkt un {@code MULTIPOLYGON(...)}; el motor rechaza cualquier otra cosa por el tipo de
     *     la columna, asi que no hay una validacion aqui que pueda desincronizarse de aquella
     */
    void asignarGeometria(long predioId, String wkt);

    /** El poligono del predio en WKT, o vacio si no tiene: la mayoria no lo tendra nunca. */
    Optional<String> geometriaDe(long predioId);

    // ---------- Titularidad ----------

    /** Quien figura como titular del predio en esa fecha (regla 9). */
    List<Titularidad> titularesDe(long predioId, LocalDate fecha);

    /**
     * Lo mismo para un lote de predios, en <b>una</b> consulta (#545).
     *
     * <p>Existe porque quien recorre un padron pagina a pagina —la deteccion de omisos— necesita
     * los titulares de las veinte filas que ya trajo, y preguntarlos de uno en uno serian veinte
     * consultas por pagina. Es el mismo motivo por el que {@code DirectorioDeContribuyentes.porIds}
     * existe al lado de {@code porCodigo}.
     *
     * <p>Un predio <b>sin titular vigente a esa fecha no aparece en el mapa</b>, igual que un
     * contribuyente inexistente no aparece en el de {@code porIds}: devolver una entrada con lista
     * vacia obligaria a quien consulta a distinguir «no tiene titular» de «no pregunte por el», que
     * son la misma respuesta vista desde el mapa.
     */
    Map<Long, List<Titularidad>> titularesDeVarios(Collection<Long> predioIds, LocalDate fecha);

    /** Los predios de los que alguien es titular en esa fecha. */
    List<Titularidad> prediosDe(long contribuyenteId, LocalDate fecha);

    /** Una titularidad por su identificador, vigente o ya cerrada. */
    Optional<Titularidad> titularidad(long id);

    Titularidad guardar(Titularidad titularidad);

    // ---------- Inquilinos (#31) ----------

    /** Quien ocupa el predio en esa fecha. Puede haber mas de uno vigente a la vez. */
    List<Inquilino> inquilinosDe(long predioId, LocalDate fecha);

    /** Un registro de inquilino por su identificador, vigente o ya cerrado. */
    Optional<Inquilino> inquilino(long id);

    Inquilino guardar(Inquilino inquilino);
}
