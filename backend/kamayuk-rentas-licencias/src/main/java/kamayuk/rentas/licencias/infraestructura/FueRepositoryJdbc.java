package kamayuk.rentas.licencias.infraestructura;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.CriterioDeFue;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.ModalidadDeAprobacion;
import kamayuk.rentas.licencias.dominio.PartidaDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.RepresentanteLegal;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.RevisionDelProyecto;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.TipoDeObra;
import kamayuk.rentas.licencias.dominio.TipoDeProfesional;
import kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El expediente del FUE y sus cinco secciones contra PostgreSQL (V4, V43).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE licencia_edificacion} ni un {@code
 * DELETE}: V43 le retira a {@code sgtm_app} el privilegio de {@code UPDATE} sobre la cabecera y no
 * se lo concede a ninguna tabla de seccion; {@code DELETE} nunca lo tuvo (V7). El unico {@code
 * UPDATE} de esta clase es el del contador de {@code edificacion_correlativo}, que es
 * infraestructura de numeracion y no un acto administrativo.
 *
 * <h2>La version de una seccion la calcula el SQL, no Java</h2>
 *
 * <p>{@code (SELECT coalesce(max(version), 0) + 1 ...)} dentro del propio {@code INSERT}.
 * Calcularla con un {@code SELECT} previo dejaria que dos peticiones simultaneas eligieran la misma
 * y las dos chocarian contra {@code edificacion_*_uq} con un error que no dice que paso; dentro del
 * {@code INSERT}, la segunda ve la fila de la primera o choca contra el indice, que es lo que ese
 * indice existe para hacer.
 */
@Repository
public class FueRepositoryJdbc extends RepositorioJdbc implements FueRepository {

    private static final String COLUMNAS =
            "id, expediente, fecha_declaracion, contribuyente_id, predio_id, tipo_tramite,"
                    + " tipo_obra, modalidad, revision, expediente_anterior, licencia_origen_id,"
                    + " solicitante_propietario, representante_documento, representante_nombre,"
                    + " representante_partida, representante_vigencia_poder, usuario_registro,"
                    + " fecha_registro, observacion";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("expediente", "fecha_declaracion", "tipo_tramite", "modalidad");

    public FueRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos emisiones concurrentes del mismo ejercicio se serializan en el motor y salen con
        // numeros consecutivos.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO edificacion_correlativo (municipalidad_id, ejercicio,"
                                        + " ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = edificacion_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public FueDeEdificacion presentar(FueDeEdificacion fue) {
        if (!fue.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un expediente ya presentado no se vuelve a insertar ni se corrige: sus"
                            + " secciones se versionan y el expediente se anula con un movimiento");
        }
        RepresentanteLegal representante = fue.representante();
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO licencia_edificacion"
                                            + " (municipalidad_id, expediente, fecha_declaracion,"
                                            + "  contribuyente_id, predio_id, tipo_tramite, tipo_obra,"
                                            + "  modalidad, revision, expediente_anterior,"
                                            + "  licencia_origen_id, solicitante_propietario,"
                                            + "  representante_documento, representante_nombre,"
                                            + "  representante_partida, representante_vigencia_poder,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :expediente, :declaracion, :contribuyente,"
                                            + "  :predio, :tramite, :obra, :modalidad, :revision,"
                                            + "  :anterior, :origen, :propietario, :repDocumento,"
                                            + "  :repNombre, :repPartida, :repVigencia, :usuario,"
                                            + "  :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("expediente", fue.expediente())
                            .param("declaracion", fue.fechaDeclaracion())
                            .param("contribuyente", fue.contribuyenteId())
                            .param("predio", fue.predioId())
                            .param("tramite", fue.tipoTramite().name())
                            .param("obra", fue.tipoObra().name())
                            .param("modalidad", fue.modalidad().name())
                            .param(
                                    "revision",
                                    fue.revision() == null ? null : fue.revision().name())
                            .param("anterior", fue.expedienteAnterior())
                            .param("origen", fue.licenciaOrigenId())
                            .param("propietario", fue.solicitantePropietario())
                            .param(
                                    "repDocumento",
                                    representante == null ? null : representante.documento())
                            .param(
                                    "repNombre",
                                    representante == null ? null : representante.nombre())
                            .param(
                                    "repPartida",
                                    representante == null ? null : representante.partidaRegistral())
                            .param(
                                    "repVigencia",
                                    representante == null ? null : representante.vigenciaDelPoder())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(fue.registradoEn()))
                            .param("observacion", fue.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new ExpedienteDuplicado(
                    "Ya existe el expediente de edificacion "
                            + fue.expediente()
                            + " en esta municipalidad: dos tramites con el mismo numero no se"
                            + " pueden distinguir en la mesa de partes",
                    yaEstaba);
        }
        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El expediente recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<FueDeEdificacion> porExpediente(String expediente) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM licencia_edificacion WHERE expediente = :expediente")
                .param(
                        "expediente",
                        expediente == null ? "" : expediente.strip().toUpperCase(Locale.ROOT))
                .query(FueRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<FueDeEdificacion> porId(long fueId) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM licencia_edificacion WHERE id = :id")
                .param("id", fueId)
                .query(FueRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<FueDeEdificacion> porNumeroDeLicencia(String numeroDeLicencia) {
        // El numero vive en el movimiento de emision (V43 §5): el FUE existe antes de que haya
        // licencia, asi que la cabecera no lo lleva.
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM licencia_edificacion e"
                                + " WHERE EXISTS (SELECT 1 FROM edificacion_movimiento m"
                                + "                WHERE m.municipalidad_id = e.municipalidad_id"
                                + "                  AND m.fue_id = e.id"
                                + "                  AND m.numero_licencia = :numero)")
                .param("numero", numeroDeLicencia == null ? "" : numeroDeLicencia.strip())
                .query(FueRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<FueDeEdificacion> buscar(CriterioDeFue criterio, Paginacion paginacion) {
        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.expediente() != null) {
            donde.append(" AND expediente = :expediente");
            parametros.put("expediente", criterio.expediente().toUpperCase(Locale.ROOT));
        }
        if (criterio.numeroLicencia() != null) {
            donde.append(
                    " AND EXISTS (SELECT 1 FROM edificacion_movimiento m"
                            + " WHERE m.municipalidad_id = e.municipalidad_id AND m.fue_id = e.id"
                            + " AND m.numero_licencia = :numeroLicencia)");
            parametros.put("numeroLicencia", criterio.numeroLicencia());
        }
        if (criterio.tipoTramite() != null) {
            donde.append(" AND tipo_tramite = :tramite");
            parametros.put("tramite", criterio.tipoTramite().name());
        }
        if (criterio.modalidad() != null) {
            donde.append(" AND modalidad = :modalidad");
            parametros.put("modalidad", criterio.modalidad().name());
        }
        if (criterio.desde() != null) {
            donde.append(" AND fecha_declaracion >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND fecha_declaracion <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        agregarFiltroDeTerreno(donde, parametros, "manzana", criterio.manzana(), "mz");
        agregarFiltroDeTerreno(donde, parametros, "lote", criterio.lote(), "lt");

        Set<Long> titulares = criterio.contribuyentes();
        if (titulares != null) {
            // Vacio no llega aqui: `ConsultaDeFue` devuelve la pagina vacia antes, porque un
            // `IN ()` no es SQL valido y un filtro ignorado devolveria el padron entero.
            donde.append(" AND contribuyente_id IN (:titulares)");
            parametros.put("titulares", titulares);
        }

        return paginar(
                "SELECT " + COLUMNAS + " FROM licencia_edificacion e" + donde,
                "SELECT count(*) FROM licencia_edificacion e" + donde,
                parametros,
                paginacion,
                ORDEN,
                FueRepositoryJdbc::mapear);
    }

    /**
     * Filtra por una columna del terreno vigente, por prefijo y como rango.
     *
     * <p>El {@code EXISTS} apunta a la <b>ultima version</b> de la seccion: buscar en todas haria
     * que un expediente cuyo terreno se corrigio de la manzana A a la B siguiera apareciendo al
     * filtrar por A, que es exactamente lo contrario de lo que quien busca espera.
     */
    private static void agregarFiltroDeTerreno(
            StringBuilder donde,
            Map<String, Object> parametros,
            String columna,
            @Nullable String prefijo,
            String alias) {
        if (prefijo == null) {
            return;
        }
        StringBuilder interior = new StringBuilder();
        RangoDePrefijo.condicion(
                interior, parametros, "t." + columna, prefijo.toUpperCase(Locale.ROOT), alias);
        donde.append(" AND EXISTS (SELECT 1 FROM edificacion_terreno t")
                .append(" WHERE t.municipalidad_id = e.municipalidad_id AND t.fue_id = e.id")
                .append(" AND t.version = (SELECT max(t2.version) FROM edificacion_terreno t2")
                .append(
                        "   WHERE t2.municipalidad_id = t.municipalidad_id AND t2.fue_id = t.fue_id)")
                .append(interior)
                .append(")");
    }

    // ---------- Secciones ----------

    @Override
    public TerrenoDelFue guardarTerreno(TerrenoDelFue terreno) {
        Long id =
                jdbc().sql(
                                "INSERT INTO edificacion_terreno"
                                        + " (municipalidad_id, fue_id, version, cod_catastral,"
                                        + "  direccion, manzana, lote, area_terreno, zonificacion,"
                                        + "  partida_registral, frente, fondo, usuario_registro,"
                                        + "  fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :fue, "
                                        + siguienteVersion("edificacion_terreno")
                                        + ", :catastral, :direccion, :manzana, :lote, :area,"
                                        + "  :zonificacion, :partida, :frente, :fondo, :usuario,"
                                        + "  :registrado, :observacion)"
                                        + " RETURNING id")
                        .param("fue", terreno.fueId())
                        .param("catastral", terreno.codigoCatastral())
                        .param("direccion", terreno.direccion())
                        .param("manzana", mayusculas(terreno.manzana()))
                        .param("lote", mayusculas(terreno.lote()))
                        .param("area", terreno.areaTerreno().valor())
                        .param("zonificacion", terreno.zonificacion())
                        .param("partida", terreno.partidaRegistral())
                        .param(
                                "frente",
                                terreno.frente() == null ? null : terreno.frente().magnitud())
                        .param("fondo", terreno.fondo() == null ? null : terreno.fondo().magnitud())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(terreno.registradoEn()))
                        .param("observacion", terreno.observacion().texto())
                        .query(Long.class)
                        .single();
        return jdbc().sql(
                        "SELECT id, fue_id, version, cod_catastral, direccion, manzana, lote,"
                                + " area_terreno, zonificacion, partida_registral, frente, fondo,"
                                + " usuario_registro, fecha_registro, observacion"
                                + " FROM edificacion_terreno WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(FueRepositoryJdbc::mapearTerreno)
                .single();
    }

    @Override
    public ProyectoDelFue guardarProyecto(ProyectoDelFue proyecto) {
        Long id =
                jdbc().sql(
                                "INSERT INTO edificacion_proyecto"
                                        + " (municipalidad_id, fue_id, version, uso, numero_pisos,"
                                        + "  area_techada, area_libre, estacionamientos, plazo_meses,"
                                        + "  usuario_registro, fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :fue, "
                                        + siguienteVersion("edificacion_proyecto")
                                        + ", :uso, :pisos, :techada, :libre, :estacionamientos,"
                                        + "  :plazo, :usuario, :registrado, :observacion)"
                                        + " RETURNING id")
                        .param("fue", proyecto.fueId())
                        .param("uso", proyecto.uso())
                        .param("pisos", proyecto.numeroPisos())
                        .param("techada", proyecto.areaTechada().valor())
                        .param(
                                "libre",
                                proyecto.areaLibre() == null ? null : proyecto.areaLibre().valor())
                        .param("estacionamientos", proyecto.estacionamientos())
                        .param("plazo", proyecto.plazoEnMeses())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(proyecto.registradoEn()))
                        .param("observacion", proyecto.observacion().texto())
                        .query(Long.class)
                        .single();
        return jdbc().sql(
                        "SELECT id, fue_id, version, uso, numero_pisos, area_techada, area_libre,"
                                + " estacionamientos, plazo_meses, usuario_registro,"
                                + " fecha_registro, observacion"
                                + " FROM edificacion_proyecto WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(FueRepositoryJdbc::mapearProyecto)
                .single();
    }

    @Override
    public List<EstructuraDelProyecto> guardarValorizacion(
            long fueId, List<EstructuraDelProyecto> estructuras) {
        int version = siguienteVersionDe("edificacion_estructura", fueId);
        for (EstructuraDelProyecto estructura : estructuras) {
            jdbc().sql(
                            "INSERT INTO edificacion_estructura"
                                    + " (municipalidad_id, fue_id, version, piso, partida,"
                                    + "  categoria, area)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :fue, :version, :piso, :partida, :categoria, :area)")
                    .param("fue", fueId)
                    .param("version", version)
                    .param("piso", estructura.piso())
                    .param("partida", estructura.partida().name())
                    .param("categoria", String.valueOf(estructura.categoria()))
                    .param("area", estructura.area().valor())
                    .update();
        }
        return valorizacionVigente(fueId);
    }

    @Override
    public List<ProfesionalDelFue> guardarProfesionales(
            long fueId, List<ProfesionalDelFue> profesionales) {
        int version = siguienteVersionDe("edificacion_profesional", fueId);
        for (ProfesionalDelFue profesional : profesionales) {
            jdbc().sql(
                            "INSERT INTO edificacion_profesional"
                                    + " (municipalidad_id, fue_id, version, tipo, nombre, colegio,"
                                    + "  colegiatura)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :fue, :version, :tipo, :nombre, :colegio, :colegiatura)")
                    .param("fue", fueId)
                    .param("version", version)
                    .param("tipo", profesional.tipo().name())
                    .param("nombre", profesional.nombre().toUpperCase(Locale.ROOT))
                    .param("colegio", profesional.colegio())
                    .param("colegiatura", profesional.colegiatura())
                    .update();
        }
        return profesionalesVigentes(fueId);
    }

    @Override
    public List<RequisitoDelFue> guardarRequisitos(long fueId, List<RequisitoDelFue> requisitos) {
        int version = siguienteVersionDe("edificacion_requisito", fueId);
        for (RequisitoDelFue requisito : requisitos) {
            jdbc().sql(
                            "INSERT INTO edificacion_requisito"
                                    + " (municipalidad_id, fue_id, version, requisito, presentado,"
                                    + "  folios)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :fue, :version, :requisito, :presentado, :folios)")
                    .param("fue", fueId)
                    .param("version", version)
                    .param("requisito", requisito.requisito())
                    .param("presentado", requisito.presentado())
                    .param("folios", requisito.folios())
                    .update();
        }
        return requisitosVigentes(fueId);
    }

    // ---------- Lectura de la version vigente ----------

    @Override
    public Optional<TerrenoDelFue> terrenoVigente(long fueId) {
        return jdbc().sql(
                        "SELECT id, fue_id, version, cod_catastral, direccion, manzana, lote,"
                                + " area_terreno, zonificacion, partida_registral, frente, fondo,"
                                + " usuario_registro, fecha_registro, observacion"
                                + " FROM edificacion_terreno WHERE fue_id = :fue"
                                + " ORDER BY version DESC LIMIT 1")
                .param("fue", fueId)
                .query(FueRepositoryJdbc::mapearTerreno)
                .optional();
    }

    @Override
    public Optional<ProyectoDelFue> proyectoVigente(long fueId) {
        return jdbc().sql(
                        "SELECT id, fue_id, version, uso, numero_pisos, area_techada, area_libre,"
                                + " estacionamientos, plazo_meses, usuario_registro,"
                                + " fecha_registro, observacion"
                                + " FROM edificacion_proyecto WHERE fue_id = :fue"
                                + " ORDER BY version DESC LIMIT 1")
                .param("fue", fueId)
                .query(FueRepositoryJdbc::mapearProyecto)
                .optional();
    }

    @Override
    public List<EstructuraDelProyecto> valorizacionVigente(long fueId) {
        return jdbc().sql(
                        "SELECT id, fue_id, version, piso, partida, categoria, area"
                                + " FROM edificacion_estructura WHERE fue_id = :fue"
                                + "   AND version = (SELECT max(version) FROM"
                                + "        edificacion_estructura WHERE fue_id = :fue)"
                                + " ORDER BY piso, partida")
                .param("fue", fueId)
                .query(FueRepositoryJdbc::mapearEstructura)
                .list();
    }

    @Override
    public List<ProfesionalDelFue> profesionalesVigentes(long fueId) {
        return jdbc().sql(
                        "SELECT id, fue_id, version, tipo, nombre, colegio, colegiatura"
                                + " FROM edificacion_profesional WHERE fue_id = :fue"
                                + "   AND version = (SELECT max(version) FROM"
                                + "        edificacion_profesional WHERE fue_id = :fue)"
                                + " ORDER BY tipo")
                .param("fue", fueId)
                .query(FueRepositoryJdbc::mapearProfesional)
                .list();
    }

    @Override
    public List<RequisitoDelFue> requisitosVigentes(long fueId) {
        return jdbc().sql(
                        "SELECT id, fue_id, version, requisito, presentado, folios"
                                + " FROM edificacion_requisito WHERE fue_id = :fue"
                                + "   AND version = (SELECT max(version) FROM"
                                + "        edificacion_requisito WHERE fue_id = :fue)"
                                + " ORDER BY requisito")
                .param("fue", fueId)
                .query(FueRepositoryJdbc::mapearRequisito)
                .list();
    }

    @Override
    public Map<Long, TerrenoDelFue> terrenosDe(Set<Long> fueIds) {
        if (fueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TerrenoDelFue> porFue = new LinkedHashMap<>();
        for (TerrenoDelFue terreno :
                jdbc().sql(
                                "SELECT t.id, t.fue_id, t.version, t.cod_catastral, t.direccion,"
                                        + " t.manzana, t.lote, t.area_terreno, t.zonificacion,"
                                        + " t.partida_registral, t.frente, t.fondo, t.usuario_registro,"
                                        + " t.fecha_registro, t.observacion"
                                        + " FROM edificacion_terreno t"
                                        + " WHERE t.fue_id IN (:ids)"
                                        + "   AND t.version = (SELECT max(t2.version) FROM"
                                        + "        edificacion_terreno t2 WHERE t2.fue_id = t.fue_id)")
                        .param("ids", fueIds)
                        .query(FueRepositoryJdbc::mapearTerreno)
                        .list()) {
            porFue.put(terreno.fueId(), terreno);
        }
        return porFue;
    }

    @Override
    public Map<Long, ProyectoDelFue> proyectosDe(Set<Long> fueIds) {
        if (fueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProyectoDelFue> porFue = new LinkedHashMap<>();
        for (ProyectoDelFue proyecto :
                jdbc().sql(
                                "SELECT p.id, p.fue_id, p.version, p.uso, p.numero_pisos,"
                                        + " p.area_techada, p.area_libre, p.estacionamientos,"
                                        + " p.plazo_meses, p.usuario_registro, p.fecha_registro,"
                                        + " p.observacion"
                                        + " FROM edificacion_proyecto p"
                                        + " WHERE p.fue_id IN (:ids)"
                                        + "   AND p.version = (SELECT max(p2.version) FROM"
                                        + "        edificacion_proyecto p2 WHERE p2.fue_id = p.fue_id)")
                        .param("ids", fueIds)
                        .query(FueRepositoryJdbc::mapearProyecto)
                        .list()) {
            porFue.put(proyecto.fueId(), proyecto);
        }
        return porFue;
    }

    @Override
    public Map<Long, List<EstructuraDelProyecto>> valorizacionesDe(Set<Long> fueIds) {
        if (fueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<EstructuraDelProyecto>> porFue = new LinkedHashMap<>();
        for (EstructuraDelProyecto estructura :
                jdbc().sql(
                                "SELECT s.id, s.fue_id, s.version, s.piso, s.partida, s.categoria,"
                                        + " s.area"
                                        + " FROM edificacion_estructura s"
                                        + " WHERE s.fue_id IN (:ids)"
                                        + "   AND s.version = (SELECT max(s2.version) FROM"
                                        + "        edificacion_estructura s2 WHERE s2.fue_id = s.fue_id)"
                                        + " ORDER BY s.fue_id, s.piso, s.partida")
                        .param("ids", fueIds)
                        .query(FueRepositoryJdbc::mapearEstructura)
                        .list()) {
            porFue.computeIfAbsent(estructura.fueId(), clave -> new java.util.ArrayList<>())
                    .add(estructura);
        }
        return porFue;
    }

    // ------------------------------------------------------------------

    /** La version siguiente, como subconsulta dentro del propio {@code INSERT}. */
    private static String siguienteVersion(String tabla) {
        return "(SELECT coalesce(max(version), 0) + 1 FROM "
                + tabla
                + " WHERE municipalidad_id = "
                + MUNICIPALIDAD_ACTUAL
                + " AND fue_id = :fue)";
    }

    /**
     * La version siguiente, leida antes de un lote de filas.
     *
     * <p>Las tres secciones de lista —valorizacion, profesionales y documentos— entran enteras, y
     * todas sus filas tienen que llevar la <b>misma</b> version; con la subconsulta dentro de cada
     * {@code INSERT}, la segunda fila veria la primera y se llevaria una version distinta. Se lee
     * una vez dentro de la misma transaccion, y quien intente dos lotes simultaneos choca contra
     * {@code edificacion_*_uq}.
     */
    private int siguienteVersionDe(String tabla, long fueId) {
        Integer ultima =
                jdbc().sql(
                                "SELECT coalesce(max(version), 0) + 1 FROM "
                                        + tabla
                                        + " WHERE fue_id = :fue")
                        .param("fue", fueId)
                        .query(Integer.class)
                        .single();
        return Objects.requireNonNull(ultima);
    }

    private static @Nullable String mayusculas(@Nullable String texto) {
        return texto == null ? null : texto.toUpperCase(Locale.ROOT);
    }

    private static FueDeEdificacion mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long origen = fila.getLong("licencia_origen_id");
        Long origenId = fila.wasNull() ? null : origen;
        String revision = fila.getString("revision");
        String repNombre = fila.getString("representante_nombre");

        RepresentanteLegal representante = null;
        if (repNombre != null) {
            Date vigencia = fila.getDate("representante_vigencia_poder");
            representante =
                    new RepresentanteLegal(
                            fila.getString("representante_documento"),
                            repNombre,
                            fila.getString("representante_partida"),
                            vigencia == null ? null : vigencia.toLocalDate());
        }

        return new FueDeEdificacion(
                fila.getLong("id"),
                fila.getString("expediente"),
                fila.getDate("fecha_declaracion").toLocalDate(),
                fila.getLong("contribuyente_id"),
                predioId,
                TipoDeTramiteDeEdificacion.porNombre(fila.getString("tipo_tramite")),
                TipoDeObra.porNombre(fila.getString("tipo_obra")),
                ModalidadDeAprobacion.porNombre(fila.getString("modalidad")),
                revision == null ? null : RevisionDelProyecto.porNombre(revision),
                fila.getString("expediente_anterior"),
                origenId,
                fila.getBoolean("solicitante_propietario"),
                representante,
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static TerrenoDelFue mapearTerreno(ResultSet fila, int numeroDeFila)
            throws SQLException {
        BigDecimal frente = fila.getBigDecimal("frente");
        BigDecimal fondo = fila.getBigDecimal("fondo");
        return new TerrenoDelFue(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                fila.getInt("version"),
                fila.getString("cod_catastral"),
                fila.getString("direccion"),
                fila.getString("manzana"),
                fila.getString("lote"),
                new AreaM2(fila.getBigDecimal("area_terreno")),
                fila.getString("zonificacion"),
                fila.getString("partida_registral"),
                frente == null ? null : new Medida(frente, "ML"),
                fondo == null ? null : new Medida(fondo, "ML"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static ProyectoDelFue mapearProyecto(ResultSet fila, int numeroDeFila)
            throws SQLException {
        BigDecimal libre = fila.getBigDecimal("area_libre");
        int estacionamientos = fila.getInt("estacionamientos");
        Integer cuantos = fila.wasNull() ? null : estacionamientos;
        int plazo = fila.getInt("plazo_meses");
        Integer meses = fila.wasNull() ? null : plazo;
        return new ProyectoDelFue(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                fila.getInt("version"),
                fila.getString("uso"),
                fila.getInt("numero_pisos"),
                new AreaM2(fila.getBigDecimal("area_techada")),
                libre == null ? null : new AreaM2(libre),
                cuantos,
                meses,
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static EstructuraDelProyecto mapearEstructura(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new EstructuraDelProyecto(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                fila.getInt("version"),
                fila.getInt("piso"),
                PartidaDeEdificacion.porNombre(fila.getString("partida")),
                fila.getString("categoria").charAt(0),
                new AreaM2(fila.getBigDecimal("area")));
    }

    private static ProfesionalDelFue mapearProfesional(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ProfesionalDelFue(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                fila.getInt("version"),
                TipoDeProfesional.porNombre(fila.getString("tipo")),
                fila.getString("nombre"),
                fila.getString("colegio"),
                fila.getString("colegiatura"));
    }

    private static RequisitoDelFue mapearRequisito(ResultSet fila, int numeroDeFila)
            throws SQLException {
        int folios = fila.getInt("folios");
        Integer cuantos = fila.wasNull() ? null : folios;
        return new RequisitoDelFue(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                fila.getInt("version"),
                fila.getString("requisito"),
                fila.getBoolean("presentado"),
                cuantos);
    }
}
