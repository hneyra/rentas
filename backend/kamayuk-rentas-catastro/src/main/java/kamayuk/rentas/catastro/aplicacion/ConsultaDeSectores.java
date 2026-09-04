package kamayuk.rentas.catastro.aplicacion;

import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura del catalogo de sectores: la opcion {@code sectores} del contrato.
 *
 * <p>Existe por lo mismo que {@link ConsultaDeVias}, y llega con el mismo defecto ya cometido una
 * vez: {@code SectorController} llamaba al repositorio <b>directamente</b>, y una lectura fuera de
 * transaccion no emite el {@code SET LOCAL app.municipalidad_id} que la politica RLS de {@code
 * sector} consulta. Sin el, la consulta no devuelve vacio —falla con «invalid input syntax for type
 * bigint: ""»—. En {@code GET /catastro/vias} el sintoma tardo en aparecer porque nadie con permiso
 * habia llegado nunca al endpoint; aqui pasaba exactamente igual.
 *
 * <p>El {@code @Transactional(readOnly = true)} de cada metodo es el que abre la transaccion donde
 * {@code TenantTransactionManager} fija el tenant.
 */
@Service
public class ConsultaDeSectores {

    private final CatastroRepository repositorio;

    public ConsultaDeSectores(CatastroRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * El catalogo con los conteos de cada sector: manzanas, predios activos y lotes (#290).
     *
     * <p>Los cuenta la base, en la misma transaccion y sobre la pagina ya limitada. Contarlos aqui
     * —pidiendo las manzanas y los predios de cada sector para llamar a {@code size()}— serian dos
     * consultas por fila y un padron entero en memoria para pintar veinte numeros.
     */
    @Transactional(readOnly = true)
    public Pagina<SectorConConteos> listar(Paginacion paginacion) {
        return repositorio.sectores(paginacion);
    }

    /**
     * El sector de un codigo, para que la edicion sepa que id esta tocando y para que el alta de
     * una manzana pueda decir «ese sector no existe» antes de intentar escribir.
     *
     * <p>Lleva su propia transaccion por lo mismo que {@link #listar}: sin ella no hay {@code SET
     * LOCAL} y la politica RLS de {@code sector} no tiene contexto de tenant.
     */
    @Transactional(readOnly = true)
    public Optional<Sector> buscarPorCodigo(String codigo) {
        return repositorio.sectorPorCodigo(codigo);
    }

    /**
     * Las manzanas de un sector, paginadas y con sus conteos (#537).
     *
     * <p><b>El vacio y el «no existe» son dos respuestas distintas, y por eso devuelve un {@link
     * Optional}.</b> {@code Optional.empty()} es «no hay ningun sector con ese codigo» —404—;
     * {@code Optional.of(paginaVacia)} es «ese sector existe y todavia no tiene ninguna manzana»
     * —200 con cero filas—. Escrito como una sola pagina, las dos saldrian iguales, y una pantalla
     * que dibuja un arbol no puede distinguir un sector recien creado de un codigo mal tecleado.
     * Que la diferencia la lleve el <b>tipo</b> es lo que impide perderla al pasar por la capa web.
     *
     * <p><b>Las dos consultas van en la misma transaccion</b>, y ahi esta el motivo de que esto sea
     * un metodo y no dos llamadas desde el controlador: entre leer el sector y leer sus manzanas no
     * cabe nadie, y —sobre todo— cada una necesita el {@code SET LOCAL app.municipalidad_id} que
     * abre el {@code @Transactional}. Resolver el sector desde el controlador es exactamente el
     * defecto de #486: sin contexto de tenant, la politica RLS no devuelve vacio, <b>revienta</b>
     * con «invalid input syntax for type bigint: ""».
     */
    @Transactional(readOnly = true)
    public Optional<Pagina<ManzanaConConteos>> manzanasDelSector(
            String codigoDeSector, Paginacion paginacion) {
        return repositorio
                .sectorPorCodigo(codigoDeSector)
                .map(sector -> repositorio.manzanas(sector, paginacion));
    }
}
