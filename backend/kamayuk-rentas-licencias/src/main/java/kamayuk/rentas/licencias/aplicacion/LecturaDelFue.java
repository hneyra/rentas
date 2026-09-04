package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue.FueEnConsulta;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstadoDelFue;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo lo que el FUE lee de <b>sus propias</b> tablas, y la transaccion en la que se lee (#48,
 * #569).
 *
 * <h2>Aqui si va la transaccion, y hace falta que vaya</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} es lo que abre la transaccion donde {@code
 * TenantTransactionManager} emite el {@code SET LOCAL app.municipalidad_id} que las politicas RLS
 * consultan. Una consulta fuera de transaccion no devuelve vacio: <b>falla</b>, con «invalid input
 * syntax for type bigint: ""», un mensaje que no se parece a su causa (#486, y antes {@code
 * ConsultaDeVias}).
 *
 * <h2>Por que esta clase existe: la separa {@link ConsultaDeFue}, que NO abre transaccion</h2>
 *
 * <p>El reporte y la ficha necesitan ademas el cuadro de valores unitarios, que vive en {@code
 * catastro} y <b>trae su propia transaccion</b>. Cuando el ejercicio no tiene conjunto sellado —lo
 * que ocurre hoy en todas las municipalidades, D-02a— ese lector lanza; si el anfitrion hubiera
 * abierto la transaccion, esa excepcion la dejaria marcada <i>rollback-only</i> y, aunque se
 * capture, el reporte entero fallaria al confirmarla (#54, #72, #247 §2, y #569, que es donde se
 * midio). Por eso lo que se lee de las tablas del FUE y lo que se le pide al cuadro no comparten
 * transaccion: esta clase abre la suya y la cierra, y el anfitrion valoriza despues.
 *
 * <h2>El estado se deriva, y en dos consultas</h2>
 *
 * <p>Una pagina de veinte expedientes necesita veinte estados, y cada estado necesita movimientos y
 * vigencias. Se leen los de los veinte de golpe y se derivan en memoria: con una lectura por fila
 * serian cuarenta y una consultas.
 */
@Service
public class LecturaDelFue {

    /** Cuantos contribuyentes se resuelven como mucho al filtrar por nombre. */
    private static final int TITULARES_MAXIMOS = 200;

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final DirectorioDeContribuyentes contribuyentes;

    public LecturaDelFue(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            DirectorioDeContribuyentes contribuyentes) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.contribuyentes = contribuyentes;
    }

    /**
     * La grilla, paginada, con el estado de cada fila derivado a {@code aLaFecha}.
     *
     * <p><b>Sin valorizar.</b> Valorizar veinte expedientes por pagina seria pedir el cuadro de
     * valores unitarios veinte veces para una columna que la grilla ni siquiera pinta. La
     * valorizacion vive en la ficha y en el reporte, que son los dos sitios donde se lee.
     *
     * @param nombreDelSolicitante el filtro por nombre; se resuelve contra el padron
     * @param estado el filtro por estado; se aplica <b>despues</b> de derivarlo, porque no es una
     *     columna
     */
    @Transactional(readOnly = true)
    public Pagina<FueEnConsulta> buscar(
            CriterioDeFue criterio,
            @Nullable String nombreDelSolicitante,
            @Nullable EstadoDelFue estado,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        CriterioDeFue conTitulares = conTitularesResueltos(criterio, nombreDelSolicitante);
        if (conTitulares.sinTitularPosible()) {
            // Se filtro por solicitante y no hay ninguno que se parezca. Devolver la pagina entera
            // aqui convertiria un nombre inexistente en «todos los expedientes», que es el defecto
            // que la consulta de fichas ya cometio una vez.
            return Pagina.vacia(paginacion);
        }

        Pagina<FueDeEdificacion> pagina = expedientes.buscar(conTitulares, paginacion);
        if (pagina.estaVacia()) {
            return Pagina.vacia(paginacion);
        }

        Set<Long> ids = new HashSet<>();
        Set<Long> titulares = new HashSet<>();
        for (FueDeEdificacion fue : pagina.contenido()) {
            ids.add(fue.identificador());
            titulares.add(fue.contribuyenteId());
        }
        Map<Long, List<MovimientoDeEdificacion>> historiales = movimientos.deExpedientes(ids);
        Map<Long, List<VigenciaDeLaLicencia>> vigencias = movimientos.vigenciasDeVarias(ids);
        Map<Long, TerrenoDelFue> terrenos = expedientes.terrenosDe(ids);
        Map<Long, ResumenDeContribuyente> padron = contribuyentes.porIds(titulares);

        Pagina<FueEnConsulta> resuelta =
                pagina.mapear(
                        fue ->
                                filaDe(
                                        fue,
                                        historiales.getOrDefault(fue.identificador(), List.of()),
                                        vigencias.getOrDefault(fue.identificador(), List.of()),
                                        terrenos.get(fue.identificador()),
                                        padron.get(fue.contribuyenteId()),
                                        aLaFecha));

        if (estado == null) {
            return resuelta;
        }
        // El estado no es una columna y no se puede filtrar en el WHERE: se deriva y se filtra
        // aqui. El total de la pagina se recalcula sobre lo que queda, para que el reporte no
        // prometa mas filas de las que ensena.
        List<FueEnConsulta> filtradas =
                resuelta.contenido().stream().filter(fila -> fila.estado() == estado).toList();
        return Pagina.de(filtradas, paginacion, filtradas.size());
    }

    /**
     * Lo que el reporte general lee de las tablas del FUE: la pagina, los proyectos y las
     * estructuras declaradas de cada expediente.
     *
     * <p><b>Todo en una sola transaccion, y ninguna cifra.</b> Las estructuras se devuelven tal
     * como estan declaradas; quien las valoriza es {@link ConsultaDeFue}, ya fuera de aqui.
     */
    @Transactional(readOnly = true)
    public DatosDelReporte datosDelReporte(
            CriterioDeFue criterio,
            @Nullable String nombreDelSolicitante,
            @Nullable EstadoDelFue estado,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        Pagina<FueEnConsulta> filas =
                buscar(criterio, nombreDelSolicitante, estado, aLaFecha, paginacion);
        if (filas.estaVacia()) {
            return new DatosDelReporte(filas, Map.of(), Map.of());
        }

        Set<Long> ids = new HashSet<>();
        for (FueEnConsulta fila : filas.contenido()) {
            ids.add(fila.fue().identificador());
        }
        return new DatosDelReporte(
                filas, expedientes.proyectosDe(ids), expedientes.valorizacionesDe(ids));
    }

    /**
     * Los tramos de vigencia de una licencia de edificacion.
     *
     * <p>Los pide la respuesta de la revalidacion —las dos vigencias, la original y la nueva, que
     * es el AC 4 de #48 leible desde el JSON—.
     */
    @Transactional(readOnly = true)
    public List<VigenciaDeLaLicencia> vigenciasDe(long licenciaId) {
        return movimientos.vigenciasDe(licenciaId);
    }

    /** Lo que la ficha lee de las tablas del FUE, sin su cifra. */
    @Transactional(readOnly = true)
    public Optional<DatosDeLaFicha> porExpediente(String expediente, LocalDate aLaFecha) {
        return expedientes.porExpediente(expediente).map(fue -> datosDe(fue, aLaFecha));
    }

    /** Lo mismo, por el numero de la licencia otorgada. */
    @Transactional(readOnly = true)
    public Optional<DatosDeLaFicha> porNumeroDeLicencia(String numero, LocalDate aLaFecha) {
        return expedientes.porNumeroDeLicencia(numero).map(fue -> datosDe(fue, aLaFecha));
    }

    // ------------------------------------------------------------------

    private DatosDeLaFicha datosDe(FueDeEdificacion fue, LocalDate aLaFecha) {
        long id = fue.identificador();
        List<MovimientoDeEdificacion> historial = movimientos.deExpediente(id);
        List<VigenciaDeLaLicencia> vigencias = movimientos.vigenciasDe(id);
        Map<Long, ResumenDeContribuyente> padron =
                contribuyentes.porIds(Set.of(fue.contribuyenteId()));
        List<EstructuraDelProyecto> estructuras = expedientes.valorizacionVigente(id);

        List<SeccionDelFue> faltantes = new ArrayList<>();
        Optional<TerrenoDelFue> terreno = expedientes.terrenoVigente(id);
        Optional<ProyectoDelFue> proyecto = expedientes.proyectoVigente(id);
        List<ProfesionalDelFue> profesionales = expedientes.profesionalesVigentes(id);
        List<RequisitoDelFue> requisitos = expedientes.requisitosVigentes(id);
        if (terreno.isEmpty()) {
            faltantes.add(SeccionDelFue.TERRENO);
        }
        if (proyecto.isEmpty()) {
            faltantes.add(SeccionDelFue.PROYECTO);
        }
        if (estructuras.isEmpty()) {
            faltantes.add(SeccionDelFue.VALORIZACION);
        }
        if (profesionales.isEmpty()) {
            faltantes.add(SeccionDelFue.PROFESIONALES);
        }
        if (requisitos.isEmpty()) {
            faltantes.add(SeccionDelFue.DOCUMENTOS);
        }

        return new DatosDeLaFicha(
                filaDe(
                        fue,
                        historial,
                        vigencias,
                        terreno.orElse(null),
                        padron.get(fue.contribuyenteId()),
                        aLaFecha),
                terreno.orElse(null),
                proyecto.orElse(null),
                estructuras,
                profesionales,
                requisitos,
                historial,
                vigencias,
                List.copyOf(faltantes),
                fechaDelActo(fue, historial));
    }

    /**
     * El dia con el que se resuelve el conjunto sellado de la ficha: el de la emision si ya la
     * hubo, y el de la declaracion mientras no.
     *
     * <p>Resolverlo con «hoy» haria que la misma licencia consultada el anio que viene diera otra
     * cifra sin que nada avisara (ARQ-09 §3, regla 9).
     */
    private static LocalDate fechaDelActo(
            FueDeEdificacion fue, List<MovimientoDeEdificacion> historial) {
        return historial.stream()
                .filter(m -> m.tipo() == TipoDeMovimientoDeEdificacion.EMISION)
                .map(MovimientoDeEdificacion::fecha)
                .findFirst()
                .orElse(fue.fechaDeclaracion());
    }

    private static FueEnConsulta filaDe(
            FueDeEdificacion fue,
            List<MovimientoDeEdificacion> historial,
            List<VigenciaDeLaLicencia> vigencias,
            @Nullable TerrenoDelFue terreno,
            @Nullable ResumenDeContribuyente solicitante,
            LocalDate aLaFecha) {

        String numero =
                historial.stream()
                        .filter(m -> m.tipo() == TipoDeMovimientoDeEdificacion.EMISION)
                        .map(MovimientoDeEdificacion::numeroLicencia)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        return new FueEnConsulta(
                fue,
                EstadoDelFue.derivarDe(historial, vigencias, aLaFecha),
                aLaFecha,
                numero,
                terreno,
                solicitante);
    }

    private CriterioDeFue conTitularesResueltos(
            CriterioDeFue criterio, @Nullable String nombreDelSolicitante) {
        String buscado = nombreDelSolicitante == null ? "" : nombreDelSolicitante.strip();
        if (buscado.isEmpty()) {
            return criterio;
        }
        Set<Long> encontrados = new HashSet<>();
        for (ResumenDeContribuyente resumen : contribuyentes.buscar(buscado, TITULARES_MAXIMOS)) {
            encontrados.add(resumen.id());
        }
        return criterio.conTitulares(encontrados);
    }

    // ------------------------------------------------------------------

    /**
     * Lo que el reporte general necesita de la base, sin ninguna cifra todavia.
     *
     * @param filas la pagina con el estado de cada expediente ya derivado
     * @param proyectos la version vigente de las caracteristicas, por expediente
     * @param estructuras las partidas declaradas de cada expediente, por expediente
     */
    public record DatosDelReporte(
            Pagina<FueEnConsulta> filas,
            Map<Long, ProyectoDelFue> proyectos,
            Map<Long, List<EstructuraDelProyecto>> estructuras) {

        public DatosDelReporte {
            proyectos = Map.copyOf(proyectos);
            estructuras = Map.copyOf(estructuras);
        }
    }

    /**
     * La ficha del FUE tal como sale de la base: sus secciones, su historial y la fecha de su acto.
     *
     * <p>Le falta exactamente una cosa —la valorizacion—, y es la unica que no se puede pedir desde
     * dentro de esta transaccion.
     *
     * @param fechaDelActo el dia con el que la valorizacion resolvera el conjunto sellado
     */
    public record DatosDeLaFicha(
            FueEnConsulta fila,
            @Nullable TerrenoDelFue terreno,
            @Nullable ProyectoDelFue proyecto,
            List<EstructuraDelProyecto> estructuras,
            List<ProfesionalDelFue> profesionales,
            List<RequisitoDelFue> requisitos,
            List<MovimientoDeEdificacion> historial,
            List<VigenciaDeLaLicencia> vigencias,
            List<SeccionDelFue> seccionesFaltantes,
            LocalDate fechaDelActo) {

        public DatosDeLaFicha {
            estructuras = List.copyOf(estructuras);
            profesionales = List.copyOf(profesionales);
            requisitos = List.copyOf(requisitos);
            historial = List.copyOf(historial);
            vigencias = List.copyOf(vigencias);
            seccionesFaltantes = List.copyOf(seccionesFaltantes);
        }
    }
}
