package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;

/**
 * Los expedientes del FUE en memoria, para la prueba del borde HTTP (#48).
 *
 * <p>Impone lo que la base impone y ninguna comprobacion de Java debe repetir: el expediente unico
 * ({@code edificacion_expediente_uq}) y el versionado de cada seccion. Sin esa unicidad, el 409 del
 * controlador no tendria nada que traducir.
 *
 * <p>Lo que <b>no</b> impone es la concurrencia: eso no se puede fingir, y por eso vive en {@code
 * LicenciaDeEdificacionJdbcTest} contra PostgreSQL de verdad.
 */
public final class FuesEnMemoria implements FueRepository {

    private final AtomicLong secuencia = new AtomicLong();
    private final AtomicLong correlativo = new AtomicLong();
    private final Map<Long, FueDeEdificacion> expedientes = new LinkedHashMap<>();
    private final Map<Long, List<TerrenoDelFue>> terrenos = new LinkedHashMap<>();
    private final Map<Long, List<ProyectoDelFue>> proyectos = new LinkedHashMap<>();
    private final Map<Long, List<EstructuraDelProyecto>> estructuras = new LinkedHashMap<>();
    private final Map<Long, List<ProfesionalDelFue>> profesionales = new LinkedHashMap<>();
    private final Map<Long, List<RequisitoDelFue>> requisitos = new LinkedHashMap<>();

    /** Los movimientos, para poder resolver el numero de licencia sin cruzar contextos. */
    private MovimientosDeEdificacionEnMemoria movimientos = new MovimientosDeEdificacionEnMemoria();

    public FuesEnMemoria con(MovimientosDeEdificacionEnMemoria movimientos) {
        this.movimientos = movimientos;
        return this;
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        return correlativo.incrementAndGet();
    }

    @Override
    public FueDeEdificacion presentar(FueDeEdificacion fue) {
        if (porExpediente(fue.expediente()).isPresent()) {
            throw new ExpedienteDuplicado(
                    "Ya existe el expediente de edificacion "
                            + fue.expediente()
                            + " en esta municipalidad",
                    new IllegalStateException("expediente repetido"));
        }
        FueDeEdificacion guardado = fue.con(secuencia.incrementAndGet());
        expedientes.put(guardado.identificador(), guardado);
        return guardado;
    }

    @Override
    public Optional<FueDeEdificacion> porExpediente(String expediente) {
        String buscado = expediente == null ? "" : expediente.strip().toUpperCase(Locale.ROOT);
        return expedientes.values().stream()
                .filter(fue -> fue.expediente().equals(buscado))
                .findFirst();
    }

    @Override
    public Optional<FueDeEdificacion> porId(long fueId) {
        return Optional.ofNullable(expedientes.get(fueId));
    }

    @Override
    public Optional<FueDeEdificacion> porNumeroDeLicencia(String numeroDeLicencia) {
        String buscado = numeroDeLicencia == null ? "" : numeroDeLicencia.strip();
        return expedientes.values().stream()
                .filter(
                        fue ->
                                movimientos
                                        .emisionDe(fue.identificador())
                                        .map(emision -> buscado.equals(emision.numeroLicencia()))
                                        .orElse(false))
                .findFirst();
    }

    @Override
    public Pagina<FueDeEdificacion> buscar(CriterioDeFue criterio, Paginacion paginacion) {
        List<FueDeEdificacion> encontrados =
                expedientes.values().stream()
                        .filter(
                                fue ->
                                        criterio.expediente() == null
                                                || fue.expediente()
                                                        .equalsIgnoreCase(criterio.expediente()))
                        .filter(
                                fue ->
                                        criterio.tipoTramite() == null
                                                || fue.tipoTramite() == criterio.tipoTramite())
                        .filter(
                                fue ->
                                        criterio.modalidad() == null
                                                || fue.modalidad() == criterio.modalidad())
                        .filter(
                                fue ->
                                        criterio.desde() == null
                                                || !fue.fechaDeclaracion()
                                                        .isBefore(criterio.desde()))
                        .filter(
                                fue ->
                                        criterio.hasta() == null
                                                || !fue.fechaDeclaracion()
                                                        .isAfter(criterio.hasta()))
                        .filter(
                                fue ->
                                        criterio.contribuyentes() == null
                                                || criterio.contribuyentes()
                                                        .contains(fue.contribuyenteId()))
                        .sorted(Comparator.comparing(FueDeEdificacion::expediente))
                        .toList();
        return Pagina.de(encontrados, paginacion, encontrados.size());
    }

    @Override
    public TerrenoDelFue guardarTerreno(TerrenoDelFue terreno) {
        List<TerrenoDelFue> versiones =
                terrenos.computeIfAbsent(terreno.fueId(), clave -> new ArrayList<>());
        TerrenoDelFue guardado =
                new TerrenoDelFue(
                        secuencia.incrementAndGet(),
                        terreno.fueId(),
                        versiones.size() + 1,
                        terreno.codigoCatastral(),
                        terreno.direccion(),
                        terreno.manzana(),
                        terreno.lote(),
                        terreno.areaTerreno(),
                        terreno.zonificacion(),
                        terreno.partidaRegistral(),
                        terreno.frente(),
                        terreno.fondo(),
                        terreno.registradoEn(),
                        terreno.usuarioRegistro(),
                        terreno.observacion());
        versiones.add(guardado);
        return guardado;
    }

    @Override
    public ProyectoDelFue guardarProyecto(ProyectoDelFue proyecto) {
        List<ProyectoDelFue> versiones =
                proyectos.computeIfAbsent(proyecto.fueId(), clave -> new ArrayList<>());
        ProyectoDelFue guardado =
                new ProyectoDelFue(
                        secuencia.incrementAndGet(),
                        proyecto.fueId(),
                        versiones.size() + 1,
                        proyecto.uso(),
                        proyecto.numeroPisos(),
                        proyecto.areaTechada(),
                        proyecto.areaLibre(),
                        proyecto.estacionamientos(),
                        proyecto.plazoEnMeses(),
                        proyecto.registradoEn(),
                        proyecto.usuarioRegistro(),
                        proyecto.observacion());
        versiones.add(guardado);
        return guardado;
    }

    @Override
    public List<EstructuraDelProyecto> guardarValorizacion(
            long fueId, List<EstructuraDelProyecto> lineas) {
        List<EstructuraDelProyecto> guardadas = new ArrayList<>();
        int version = version(estructuras.get(fueId), EstructuraDelProyecto::version);
        for (EstructuraDelProyecto linea : lineas) {
            guardadas.add(
                    new EstructuraDelProyecto(
                            secuencia.incrementAndGet(),
                            fueId,
                            version,
                            linea.piso(),
                            linea.partida(),
                            linea.categoria(),
                            linea.area()));
        }
        estructuras.computeIfAbsent(fueId, clave -> new ArrayList<>()).addAll(guardadas);
        return guardadas;
    }

    @Override
    public List<ProfesionalDelFue> guardarProfesionales(
            long fueId, List<ProfesionalDelFue> firmantes) {
        List<ProfesionalDelFue> guardados = new ArrayList<>();
        int version = version(profesionales.get(fueId), ProfesionalDelFue::version);
        for (ProfesionalDelFue firmante : firmantes) {
            guardados.add(
                    new ProfesionalDelFue(
                            secuencia.incrementAndGet(),
                            fueId,
                            version,
                            firmante.tipo(),
                            firmante.nombre(),
                            firmante.colegio(),
                            firmante.colegiatura()));
        }
        profesionales.computeIfAbsent(fueId, clave -> new ArrayList<>()).addAll(guardados);
        return guardados;
    }

    @Override
    public List<RequisitoDelFue> guardarRequisitos(long fueId, List<RequisitoDelFue> documentos) {
        List<RequisitoDelFue> guardados = new ArrayList<>();
        int version = version(requisitos.get(fueId), RequisitoDelFue::version);
        for (RequisitoDelFue documento : documentos) {
            guardados.add(
                    new RequisitoDelFue(
                            secuencia.incrementAndGet(),
                            fueId,
                            version,
                            documento.requisito(),
                            documento.presentado(),
                            documento.folios()));
        }
        requisitos.computeIfAbsent(fueId, clave -> new ArrayList<>()).addAll(guardados);
        return guardados;
    }

    @Override
    public Optional<TerrenoDelFue> terrenoVigente(long fueId) {
        return ultima(terrenos.get(fueId), TerrenoDelFue::version);
    }

    @Override
    public Optional<ProyectoDelFue> proyectoVigente(long fueId) {
        return ultima(proyectos.get(fueId), ProyectoDelFue::version);
    }

    @Override
    public List<EstructuraDelProyecto> valorizacionVigente(long fueId) {
        return vigentes(estructuras.get(fueId), EstructuraDelProyecto::version);
    }

    @Override
    public List<ProfesionalDelFue> profesionalesVigentes(long fueId) {
        return vigentes(profesionales.get(fueId), ProfesionalDelFue::version);
    }

    @Override
    public List<RequisitoDelFue> requisitosVigentes(long fueId) {
        return vigentes(requisitos.get(fueId), RequisitoDelFue::version);
    }

    @Override
    public Map<Long, TerrenoDelFue> terrenosDe(Set<Long> fueIds) {
        Map<Long, TerrenoDelFue> encontrados = new LinkedHashMap<>();
        for (Long fueId : fueIds) {
            terrenoVigente(fueId).ifPresent(terreno -> encontrados.put(fueId, terreno));
        }
        return encontrados;
    }

    @Override
    public Map<Long, ProyectoDelFue> proyectosDe(Set<Long> fueIds) {
        Map<Long, ProyectoDelFue> encontrados = new LinkedHashMap<>();
        for (Long fueId : fueIds) {
            proyectoVigente(fueId).ifPresent(proyecto -> encontrados.put(fueId, proyecto));
        }
        return encontrados;
    }

    @Override
    public Map<Long, List<EstructuraDelProyecto>> valorizacionesDe(Set<Long> fueIds) {
        Map<Long, List<EstructuraDelProyecto>> encontradas = new LinkedHashMap<>();
        for (Long fueId : fueIds) {
            encontradas.put(fueId, valorizacionVigente(fueId));
        }
        return encontradas;
    }

    // ------------------------------------------------------------------

    private static <T> int version(
            java.util.@org.jspecify.annotations.Nullable List<T> filas,
            java.util.function.ToIntFunction<T> version) {
        if (filas == null || filas.isEmpty()) {
            return 1;
        }
        return filas.stream().mapToInt(version).max().orElse(0) + 1;
    }

    private static <T> Optional<T> ultima(
            java.util.@org.jspecify.annotations.Nullable List<T> filas,
            java.util.function.ToIntFunction<T> version) {
        if (filas == null || filas.isEmpty()) {
            return Optional.empty();
        }
        return filas.stream().max(Comparator.comparingInt(version));
    }

    private static <T> List<T> vigentes(
            java.util.@org.jspecify.annotations.Nullable List<T> filas,
            java.util.function.ToIntFunction<T> version) {
        if (filas == null || filas.isEmpty()) {
            return List.of();
        }
        int ultima = filas.stream().mapToInt(version).max().orElse(0);
        return filas.stream().filter(fila -> version.applyAsInt(fila) == ultima).toList();
    }
}
