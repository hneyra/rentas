package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;

/**
 * Un catastro en memoria para las pruebas de las cargas desde archivo, que no necesitan base de
 * datos: lo que se verifica es que el proceso lee el archivo, respeta la guarda de demostracion y
 * rechaza por fila, no como persiste PostgreSQL —eso ya lo prueban los tests {@code ...JdbcTest}
 * contra el motor de verdad—.
 *
 * <p>Implementa los tres puertos que {@link InscribirFicha} y los importadores necesitan, y un
 * directorio de contribuyentes de mentira. Lo que no usan lanza {@link
 * UnsupportedOperationException} en vez de devolver vacio: una prueba que pase porque un doble
 * respondio «nada» a algo que nadie penso no verifica lo que dice verificar.
 */
final class CatastroEnMemoria
        implements CatastroRepository,
                ViaRepository,
                FichaCatastralRepository,
                DirectorioDeContribuyentes {

    @Override
    public java.util.Optional<FichaCatastral> porId(long fichaId) {
        throw new UnsupportedOperationException("esta prueba no lee una version por id");
    }

    private final Map<Long, Sector> sectores = new LinkedHashMap<>();
    private final Map<Long, Manzana> manzanas = new LinkedHashMap<>();
    private final Map<Long, Via> vias = new LinkedHashMap<>();
    private final Map<Long, Predio> predios = new LinkedHashMap<>();
    private final Map<Long, Titularidad> titularidades = new LinkedHashMap<>();
    private final Map<Long, FichaCatastral> fichas = new LinkedHashMap<>();
    private final Map<String, ResumenDeContribuyente> padron = new LinkedHashMap<>();

    private long siguienteId = 1;

    // ------------------------------------------------------------------
    // Siembra de lo que ya tiene que existir antes de una carga de fichas
    // ------------------------------------------------------------------

    void sembrarSector(String codigo, String nombre) {
        long id = siguienteId++;
        sectores.put(id, new Sector(id, codigo, nombre, null, true));
    }

    void sembrarManzana(String codigoDeSector, String codigo) {
        Sector sector =
                sectorPorCodigo(codigoDeSector)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Falta el sector " + codigoDeSector));
        long id = siguienteId++;
        manzanas.put(id, new Manzana(id, sector.id(), codigo));
    }

    void sembrarVia(Via via) {
        long id = siguienteId++;
        vias.put(
                id,
                new Via(id, via.codigo(), via.tipo(), via.nombre(), via.ubigeo(), via.activa()));
    }

    void sembrarContribuyente(String codigo, String nombre) {
        long id = siguienteId++;
        padron.put(codigo, new ResumenDeContribuyente(id, codigo, nombre, "DNI 00000000"));
    }

    // ------------------------------------------------------------------
    // Lo que las pruebas preguntan despues
    // ------------------------------------------------------------------

    List<Predio> prediosRegistrados() {
        return List.copyOf(predios.values());
    }

    List<FichaCatastral> fichasRegistradas() {
        return List.copyOf(fichas.values());
    }

    List<Titularidad> titularidadesRegistradas() {
        return List.copyOf(titularidades.values());
    }

    // ------------------------------------------------------------------
    // CatastroRepository
    // ------------------------------------------------------------------

    @Override
    public Pagina<SectorConConteos> sectores(Paginacion paginacion) {
        throw new UnsupportedOperationException("La carga desde archivo no pagina sectores");
    }

    @Override
    public Optional<Sector> sectorPorCodigo(String codigo) {
        return sectores.values().stream().filter(s -> s.codigo().equals(codigo)).findFirst();
    }

    @Override
    public Optional<Sector> sectorPorId(long id) {
        return Optional.ofNullable(sectores.get(id));
    }

    @Override
    public Sector guardar(Sector sector) {
        long id = sector.esNuevo() ? siguienteId++ : sector.id();
        Sector guardado =
                new Sector(id, sector.codigo(), sector.nombre(), sector.zona(), sector.activo());
        sectores.put(id, guardado);
        return guardado;
    }

    @Override
    public List<Manzana> manzanasDe(long sectorId) {
        return manzanas.values().stream().filter(m -> m.sectorId() == sectorId).toList();
    }

    @Override
    public Pagina<ManzanaConConteos> manzanas(Sector sector, Paginacion paginacion) {
        Long sectorId = sector.id();
        List<ManzanaConConteos> todas =
                manzanas.values().stream()
                        .filter(m -> sectorId != null && m.sectorId() == sectorId)
                        .sorted(java.util.Comparator.comparing(Manzana::codigo))
                        .map(m -> conConteos(m, sector.codigo()))
                        .toList();
        List<ManzanaConConteos> pagina =
                todas.stream()
                        .skip((long) paginacion.pagina() * paginacion.tamano())
                        .limit(paginacion.tamano())
                        .toList();
        return Pagina.de(pagina, paginacion, todas.size());
    }

    /** Cuenta lo mismo que la consulta de la base: predios activos de la manzana, y sus lotes. */
    private ManzanaConConteos conConteos(Manzana manzana, String sectorCodigo) {
        Long id = manzana.id();
        List<Predio> suyos =
                predios.values().stream()
                        .filter(p -> id != null && java.util.Objects.equals(p.manzanaId(), id))
                        .filter(p -> p.estado() == EstadoPredio.ACTIVO)
                        .toList();
        long lotes =
                suyos.stream()
                        .map(Predio::lote)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .count();
        return new ManzanaConConteos(manzana, sectorCodigo, suyos.size(), lotes);
    }

    @Override
    public Manzana guardar(Manzana manzana) {
        long id = manzana.esNueva() ? siguienteId++ : manzana.id();
        Manzana guardada = new Manzana(id, manzana.sectorId(), manzana.codigo());
        manzanas.put(id, guardada);
        return guardada;
    }

    @Override
    public Optional<Predio> predio(long id) {
        return Optional.ofNullable(predios.get(id));
    }

    @Override
    public Optional<Predio> predioPorCodigo(CodigoReferenciaCatastral codigo) {
        return predios.values().stream()
                .filter(p -> p.codigo().valor().equals(codigo.valor()))
                .findFirst();
    }

    @Override
    public Pagina<PredioDelCatastro> predios(FiltroDePredios filtro, Paginacion paginacion) {
        throw new UnsupportedOperationException("La carga desde archivo no pagina predios");
    }

    @Override
    public void asignarGeometria(long predioId, String wkt) {
        throw new UnsupportedOperationException("Esta carga no trae geometria");
    }

    @Override
    public java.util.List<kamayuk.rentas.catastro.dominio.LoteDelPlano> lotesDelPlano(
            kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro, int tope) {
        throw new UnsupportedOperationException("Esta carga no dibuja planos");
    }

    @Override
    public long lotesEnElMarco(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
        throw new UnsupportedOperationException("Esta carga no dibuja planos");
    }

    @Override
    public long prediosSinGeometria(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
        throw new UnsupportedOperationException("Esta carga no dibuja planos");
    }

    @Override
    public kamayuk.rentas.catastro.dominio.MarcoDeLoLevantado marcoDeLoLevantado(
            kamayuk.rentas.catastro.dominio.AcotacionDelPlano acotacion) {
        throw new UnsupportedOperationException("Esta carga no dibuja planos");
    }

    @Override
    public Optional<String> geometriaDe(long predioId) {
        return Optional.empty();
    }

    @Override
    public Predio guardar(Predio predio) {
        long id = predio.id() == null ? siguienteId++ : predio.id();
        Predio guardado =
                new Predio(
                        id,
                        predio.codigo(),
                        predio.tipo(),
                        predio.viaId(),
                        predio.numeroMunicipal(),
                        predio.direccion(),
                        predio.sectorId(),
                        predio.manzanaId(),
                        predio.lote(),
                        predio.ubigeo(),
                        predio.estado());
        predios.put(id, guardado);
        return guardado;
    }

    @Override
    public List<Titularidad> titularesDe(long predioId, LocalDate fecha) {
        return titularidades.values().stream().filter(t -> t.predioId() == predioId).toList();
    }

    @Override
    public java.util.Map<Long, List<Titularidad>> titularesDeVarios(
            java.util.Collection<Long> predioIds, LocalDate fecha) {
        java.util.Map<Long, List<Titularidad>> porPredio = new LinkedHashMap<>();
        for (Long predioId : predioIds) {
            List<Titularidad> cuotas = titularesDe(predioId, fecha);
            if (!cuotas.isEmpty()) {
                porPredio.put(predioId, cuotas);
            }
        }
        return porPredio;
    }

    @Override
    public List<Titularidad> prediosDe(long contribuyenteId, LocalDate fecha) {
        throw new UnsupportedOperationException("La carga desde archivo no consulta por titular");
    }

    @Override
    public Optional<Titularidad> titularidad(long id) {
        return Optional.ofNullable(titularidades.get(id));
    }

    @Override
    public Titularidad guardar(Titularidad titularidad) {
        long id = titularidad.id() == null ? siguienteId++ : titularidad.id();
        Titularidad guardada =
                new Titularidad(
                        id,
                        titularidad.predioId(),
                        titularidad.contribuyenteId(),
                        titularidad.condicion(),
                        titularidad.porcentaje(),
                        titularidad.vigenciaDesde(),
                        titularidad.vigenciaHasta(),
                        titularidad.documentoOrigen());
        titularidades.put(id, guardada);
        return guardada;
    }

    @Override
    public List<Inquilino> inquilinosDe(long predioId, LocalDate fecha) {
        throw new UnsupportedOperationException("La carga desde archivo no toca inquilinos");
    }

    @Override
    public Optional<Inquilino> inquilino(long id) {
        throw new UnsupportedOperationException("La carga desde archivo no toca inquilinos");
    }

    @Override
    public Inquilino guardar(Inquilino inquilino) {
        throw new UnsupportedOperationException("La carga desde archivo no toca inquilinos");
    }

    // ------------------------------------------------------------------
    // ViaRepository
    // ------------------------------------------------------------------

    @Override
    public Optional<Via> findById(long id) {
        return Optional.ofNullable(vias.get(id));
    }

    @Override
    public Optional<Via> findByCodigo(String codigo) {
        return vias.values().stream().filter(v -> v.codigo().equals(codigo)).findFirst();
    }

    @Override
    public Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion) {
        throw new UnsupportedOperationException("La carga desde archivo no pagina vias");
    }

    @Override
    public Via save(Via via) {
        long id = via.id() == null ? siguienteId++ : via.id();
        Via guardada =
                new Via(id, via.codigo(), via.tipo(), via.nombre(), via.ubigeo(), via.activa());
        vias.put(id, guardada);
        return guardada;
    }

    // ------------------------------------------------------------------
    // FichaCatastralRepository
    // ------------------------------------------------------------------

    @Override
    public Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha) {
        return ultimaVersion(predioId, tipo);
    }

    @Override
    public List<FichaCatastral> historial(long predioId, TipoFicha tipo) {
        return deEsePredio(predioId, tipo);
    }

    @Override
    public Optional<FichaCatastral> ultimaVersion(long predioId, TipoFicha tipo) {
        return deEsePredio(predioId, tipo).stream()
                .max(java.util.Comparator.comparingInt(FichaCatastral::version));
    }

    @Override
    public FichaCatastral insertar(FichaCatastral ficha) {
        long id = siguienteId++;
        FichaCatastral guardada =
                new FichaCatastral(
                        id,
                        ficha.predioId(),
                        ficha.tipo(),
                        ficha.version(),
                        ficha.areaTerreno(),
                        ficha.uso(),
                        ficha.frontis(),
                        ficha.condicionPropiedad(),
                        ficha.tipoEdificacion(),
                        ficha.denominacion(),
                        ficha.vigenciaDesde(),
                        ficha.vigenciaHasta(),
                        ficha.origen(),
                        ficha.documentoOrigen(),
                        ficha.observacion(),
                        ficha.construcciones(),
                        ficha.instalaciones(),
                        ficha.detalle());
        fichas.put(id, guardada);
        return guardada;
    }

    @Override
    public FichaCatastral cerrar(FichaCatastral ficha) {
        fichas.put(ficha.id(), ficha);
        return ficha;
    }

    @Override
    public List<Construccion> construccionesDe(long fichaId) {
        return List.of();
    }

    @Override
    public List<OtraInstalacion> instalacionesDe(long fichaId) {
        return List.of();
    }

    @Override
    public Optional<DetalleDeLaFicha> detalleDe(long fichaId, TipoFicha tipo) {
        return Optional.empty();
    }

    @Override
    public Pagina<FichaEncontrada> consultar(
            FiltroDeFichas filtro, List<Long> titulares, LocalDate fecha, Paginacion paginacion) {
        throw new UnsupportedOperationException("La carga desde archivo no consulta la grilla");
    }

    @Override
    public List<VersionDeLaFicha> versionesDe(long predioId, TipoFicha tipo) {
        throw new UnsupportedOperationException("La carga desde archivo no lee el historico");
    }

    // ------------------------------------------------------------------
    // DirectorioDeContribuyentes
    // ------------------------------------------------------------------

    @Override
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        throw new UnsupportedOperationException("La carga desde archivo no busca por aproximacion");
    }

    @Override
    public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
        return Optional.ofNullable(padron.get(codigo));
    }

    @Override
    public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
        throw new UnsupportedOperationException("La carga desde archivo no resuelve titulares");
    }

    @Override
    public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
        throw new UnsupportedOperationException("La carga desde archivo no lee domicilios");
    }

    // ------------------------------------------------------------------

    private List<FichaCatastral> deEsePredio(long predioId, TipoFicha tipo) {
        List<FichaCatastral> encontradas = new ArrayList<>();
        for (FichaCatastral ficha : fichas.values()) {
            if (ficha.predioId() == predioId && ficha.tipo() == tipo) {
                encontradas.add(ficha);
            }
        }
        return encontradas;
    }
}
