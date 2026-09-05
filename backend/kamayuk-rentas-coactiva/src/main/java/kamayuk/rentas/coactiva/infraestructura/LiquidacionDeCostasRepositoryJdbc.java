package kamayuk.rentas.coactiva.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.coactiva.dominio.CostaLiquidada;
import kamayuk.rentas.coactiva.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.coactiva.dominio.ObligacionDeCostas;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las liquidaciones de costas contra PostgreSQL (V35, #42).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE liquidacion_costas} ni un {@code UPDATE
 * costa_procesal} ni un {@code DELETE}: V35 le retira a {@code kamayuk_app} el {@code UPDATE} sobre
 * {@code costa_procesal} y no le concede ninguno sobre las dos tablas nuevas, y el escaner de
 * fuentes rechaza esas cadenas antes de que lleguen a ejecutarse. El unico {@code UPDATE} de esta
 * clase es el del contador de {@code liquidacion_costas_correlativo}, que es infraestructura de
 * numeracion y no un acto administrativo.
 *
 * <p><b>El estado no se lee de ninguna columna</b>: no existe. Se deriva del libro, y de eso se
 * ocupa {@code ConsultaDeCostas}.
 */
@Repository
public class LiquidacionDeCostasRepositoryJdbc extends RepositorioJdbc
        implements LiquidacionDeCostasRepository {

    private static final String COLUMNAS =
            "id, numero, ejercicio, correlativo, expediente_id, contribuyente_id, tributo, fecha,"
                    + " conjunto_id, total, usuario_registro, fecha_registro, observacion";

    private static final String COLUMNAS_DE_LA_GRILLA =
            "l.id, l.numero, l.ejercicio, l.correlativo, l.expediente_id, l.contribuyente_id,"
                    + " l.tributo, l.fecha, l.conjunto_id, l.total, l.usuario_registro,"
                    + " l.fecha_registro, l.observacion";

    private static final String COLUMNAS_DE_LA_LINEA =
            "id, liquidacion_id, expediente_id, acto_id, acto_tipo, concepto, tributo, monto,"
                    + " fecha, arancel_fuente, arancel_conjunto_id";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha", "ejercicio", "expediente_id");

    public LiquidacionDeCostasRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos liquidaciones concurrentes del mismo ejercicio se serializan en el motor y salen
        // con numeros consecutivos. Nunca un SELECT seguido de un UPDATE.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO liquidacion_costas_correlativo (municipalidad_id,"
                                        + " ejercicio, ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = liquidacion_costas_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public LiquidacionDeCostas registrar(LiquidacionDeCostas liquidacion) {
        if (!liquidacion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una liquidacion ya registrada no se vuelve a insertar ni se corrige: su cargo"
                            + " ya esta en el libro, y lo que hay que deshacer es el asiento");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO liquidacion_costas"
                                        + " (municipalidad_id, numero, ejercicio, correlativo,"
                                        + "  expediente_id, contribuyente_id, tributo, fecha,"
                                        + "  conjunto_id, total, usuario_registro, fecha_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :numero, :ejercicio, :correlativo, :expediente,"
                                        + "  :contribuyente, :tributo, :fecha, :conjunto, :total,"
                                        + "  :usuario, :registrado, :observacion)"
                                        + " RETURNING id")
                        .param("numero", liquidacion.numero())
                        .param("ejercicio", liquidacion.ejercicio().valor())
                        .param("correlativo", liquidacion.correlativo())
                        .param("expediente", liquidacion.expedienteId())
                        .param("contribuyente", liquidacion.contribuyenteId())
                        .param("tributo", liquidacion.tributo())
                        .param("fecha", liquidacion.fecha())
                        .param("conjunto", liquidacion.conjuntoId())
                        .param("total", liquidacion.total().valor())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(liquidacion.registradoEn()))
                        .param("observacion", liquidacion.observacion().texto())
                        .query(Long.class)
                        .single();

        long liquidacionId = Objects.requireNonNull(id);

        for (CostaLiquidada costa : liquidacion.costas()) {
            try {
                jdbc().sql(
                                "INSERT INTO costa_procesal"
                                        + " (municipalidad_id, liquidacion_id, expediente_id,"
                                        + "  acto_id, acto_tipo, concepto, tributo, monto, fecha,"
                                        + "  arancel_fuente, arancel_conjunto_id)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :liquidacion, :expediente, :acto, :actoTipo,"
                                        + "  :concepto, :tributo, :monto, :fecha, :fuente,"
                                        + "  :conjunto)")
                        .param("liquidacion", liquidacionId)
                        .param("expediente", costa.expedienteId())
                        .param("acto", costa.actoId())
                        .param("actoTipo", costa.actoTipo().name())
                        .param("concepto", costa.concepto())
                        .param("tributo", costa.tributo())
                        .param("monto", costa.monto().valor())
                        .param("fecha", costa.fecha())
                        .param("fuente", costa.arancelFuente())
                        .param("conjunto", costa.arancelConjuntoId())
                        .update();
            } catch (DuplicateKeyException yaLiquidado) {
                throw new ActoYaLiquidado(
                        "El acto "
                                + costa.actoId()
                                + " ("
                                + costa.actoTipo().titulo()
                                + ") ya tiene costa liquidada: liquidarlo dos veces es cobrar dos"
                                + " veces la misma actuacion",
                        yaLiquidado);
            }
        }

        reclamarLaObligacion(liquidacion);

        return porId(liquidacionId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La liquidacion recien insertada no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<LiquidacionDeCostas> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM liquidacion_costas WHERE numero = :numero")
                .param("numero", numero.strip())
                .query(LiquidacionDeCostasRepositoryJdbc::mapear)
                .optional()
                .map(this::conSusLineas);
    }

    @Override
    public List<LiquidacionDeCostas> deExpediente(long expedienteId) {
        List<Cabecera> encontradas =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM liquidacion_costas"
                                        + " WHERE expediente_id = :expediente"
                                        + " ORDER BY fecha, id")
                        .param("expediente", expedienteId)
                        .query(LiquidacionDeCostasRepositoryJdbc::mapear)
                        .list();
        List<LiquidacionDeCostas> completas = new ArrayList<>(encontradas.size());
        for (Cabecera cabecera : encontradas) {
            completas.add(conSusLineas(cabecera));
        }
        return completas;
    }

    @Override
    public List<ObligacionDeCostas> obligacionesDe(long expedienteId) {
        return jdbc().sql(
                        "SELECT tributo, ejercicio FROM costa_obligacion"
                                + " WHERE expediente_id = :expediente"
                                + " ORDER BY tributo, ejercicio")
                .param("expediente", expedienteId)
                .query(
                        (fila, numeroDeFila) ->
                                new ObligacionDeCostas(
                                        fila.getString("tributo"),
                                        new Ejercicio(fila.getInt("ejercicio"))))
                .list();
    }

    @Override
    public Set<Long> actosYaLiquidados(Collection<Long> actoIds) {
        if (actoIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc().sql("SELECT acto_id FROM costa_procesal WHERE acto_id IN (:actos)")
                        .param("actos", new LinkedHashSet<>(actoIds))
                        .query(Long.class)
                        .list());
    }

    @Override
    public Pagina<LiquidacionDeCostas> consultar(
            CriterioDeLiquidaciones criterio, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.numero() != null) {
            donde.append(" AND upper(l.numero) = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.numeroDeExpediente() != null) {
            donde.append(
                    " AND EXISTS (SELECT 1 FROM expediente_coactivo e"
                            + "        WHERE e.id = l.expediente_id"
                            + "          AND upper(e.numero) = :expediente)");
            parametros.put("expediente", criterio.numeroDeExpediente());
        }
        if (criterio.contribuyenteId() != null) {
            donde.append(" AND l.contribuyente_id = :contribuyente");
            parametros.put("contribuyente", criterio.contribuyenteId());
        }

        String desde = " FROM liquidacion_costas l" + donde;
        Pagina<Cabecera> pagina =
                paginar(
                        "SELECT " + COLUMNAS_DE_LA_GRILLA + desde,
                        "SELECT count(*)" + desde,
                        parametros,
                        paginacion,
                        ORDEN,
                        LiquidacionDeCostasRepositoryJdbc::mapear);
        return pagina.mapear(this::conSusLineas);
    }

    // ------------------------------------------------------------------

    /**
     * Reclama la obligacion de costas para este expediente (V35 §3).
     *
     * <p>{@code ON CONFLICT DO NOTHING} y una relectura: si la fila ya existe y es de <b>este</b>
     * expediente —una segunda liquidacion del mismo procedimiento, que es lo corriente— no pasa
     * nada; si es de otro, se rechaza nombrandolo. Un {@code INSERT} a secas daria un choque de
     * clave tambien en el caso normal.
     */
    private void reclamarLaObligacion(LiquidacionDeCostas liquidacion) {
        jdbc().sql(
                        "INSERT INTO costa_obligacion (municipalidad_id, contribuyente_id,"
                                + " tributo, ejercicio, expediente_id) VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :contribuyente, :tributo, :ejercicio, :expediente)"
                                + " ON CONFLICT (municipalidad_id, contribuyente_id, tributo,"
                                + " ejercicio) DO NOTHING")
                .param("contribuyente", liquidacion.contribuyenteId())
                .param("tributo", liquidacion.tributo())
                .param("ejercicio", liquidacion.ejercicio().valor())
                .param("expediente", liquidacion.expedienteId())
                .update();

        Long dueno =
                jdbc().sql(
                                "SELECT expediente_id FROM costa_obligacion"
                                        + " WHERE contribuyente_id = :contribuyente"
                                        + "   AND tributo = :tributo AND ejercicio = :ejercicio")
                        .param("contribuyente", liquidacion.contribuyenteId())
                        .param("tributo", liquidacion.tributo())
                        .param("ejercicio", liquidacion.ejercicio().valor())
                        .query(Long.class)
                        .single();

        if (dueno == null || dueno != liquidacion.expedienteId()) {
            throw new ObligacionDeOtroExpediente(
                    "Las costas de "
                            + liquidacion.tributo()
                            + " "
                            + liquidacion.ejercicio()
                            + " de este obligado ya son del expediente "
                            + dueno
                            + ". El libro no distingue expedientes en la clave de una obligacion,"
                            + " asi que compartirla dejaria la columna «Costas S/» diciendo lo"
                            + " mismo en las dos filas de la grilla, sin que nada fallara (V35 §3)",
                    new IllegalStateException("costa_obligacion la tiene el expediente " + dueno));
        }
    }

    private Optional<LiquidacionDeCostas> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM liquidacion_costas WHERE id = :id")
                .param("id", id)
                .query(LiquidacionDeCostasRepositoryJdbc::mapear)
                .optional()
                .map(this::conSusLineas);
    }

    private LiquidacionDeCostas conSusLineas(Cabecera cabecera) {
        List<CostaLiquidada> lineas =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS_DE_LA_LINEA
                                        + " FROM costa_procesal"
                                        + " WHERE liquidacion_id = :liquidacion"
                                        + " ORDER BY id")
                        .param("liquidacion", cabecera.id())
                        .query(LiquidacionDeCostasRepositoryJdbc::mapearLinea)
                        .list();
        return new LiquidacionDeCostas(
                cabecera.id(),
                cabecera.numero(),
                cabecera.ejercicio(),
                cabecera.correlativo(),
                cabecera.expedienteId(),
                cabecera.contribuyenteId(),
                cabecera.tributo(),
                cabecera.fecha(),
                cabecera.conjuntoId(),
                cabecera.total(),
                lineas,
                cabecera.registradoEn(),
                cabecera.usuarioRegistro(),
                cabecera.observacion());
    }

    /** La cabecera tal como esta en la tabla, todavia sin sus lineas. */
    private record Cabecera(
            long id,
            String numero,
            Ejercicio ejercicio,
            long correlativo,
            long expedienteId,
            long contribuyenteId,
            String tributo,
            LocalDate fecha,
            long conjuntoId,
            Dinero total,
            Instant registradoEn,
            String usuarioRegistro,
            Observacion observacion) {}

    /**
     * La cabecera sola.
     *
     * <p>No se mapea directamente a {@link LiquidacionDeCostas} porque ese tipo <b>exige</b> al
     * menos una linea y que el total cuadre con ellas, y esa invariante es justamente lo que impide
     * asentar en el libro un cargo que las lineas no expliquen. Relajarla para poder leer por
     * partes seria quitarle la guarda al tipo por comodidad del repositorio.
     */
    private static Cabecera mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Cabecera(
                fila.getLong("id"),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("correlativo"),
                fila.getLong("expediente_id"),
                fila.getLong("contribuyente_id"),
                fila.getString("tributo"),
                fila.getDate("fecha").toLocalDate(),
                fila.getLong("conjunto_id"),
                new Dinero(fila.getBigDecimal("total")),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static CostaLiquidada mapearLinea(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new CostaLiquidada(
                fila.getLong("id"),
                fila.getLong("liquidacion_id"),
                fila.getLong("expediente_id"),
                fila.getLong("acto_id"),
                TipoDeActoCoactivo.porNombre(fila.getString("acto_tipo")),
                fila.getString("concepto"),
                fila.getString("tributo"),
                new Dinero(fila.getBigDecimal("monto")),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("arancel_fuente"),
                fila.getLong("arancel_conjunto_id"));
    }
}
