package kamayuk.rentas.fiscalizacion.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelProgramaRepository;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La muestra de un programa contra PostgreSQL. Ninguna consulta filtra por {@code municipalidad_id}
 * —lo hace la política RLS— y no hay ningún {@code UPDATE} ni {@code DELETE}: {@code V60} tampoco
 * concede el privilegio.
 */
@Repository
public class MuestraDelProgramaRepositoryJdbc extends RepositorioJdbc
        implements MuestraDelProgramaRepository {

    private static final String COLUMNAS =
            "id, programa_id, predio_id, cod_ref_catastral, contribuyente_id, condicion,"
                    + " area_catastral, area_declarada, sector_codigo, fecha_sorteo";

    private static final String DESDE = " FROM programa_muestra";

    /**
     * Por lo que la fila <b>publica</b> (#546): {@code sector_codigo} sale por HTTP como {@code
     * sector}, y {@code id} pasa a desempate porque {@code MuestraResource} no lo publica.
     */
    static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("cod_ref_catastral", "condicion", "sector_codigo")
                    .publicandoComo("sector", "sector_codigo")
                    .desempatandoPor("id");

    public MuestraDelProgramaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * Escribe la muestra fila a fila dentro de la transacción que abrió el caso de uso.
     *
     * <p>No es un lote: son inserciones sueltas en una sola transacción, que es lo que permite que
     * la que viole {@code programa_muestra_uq} se distinga de las demás. Una muestra son cientos de
     * filas, no las treinta mil del padrón — la detección ya filtró.
     */
    @Override
    public int insertar(
            List<MuestraDelPrograma> filas, Observacion observacion, Instant fechaRegistro) {
        String usuario = OrigenContext.actual().usuario();
        int escritas = 0;
        for (MuestraDelPrograma fila : filas) {
            Map<String, Object> campos = new HashMap<>();
            campos.put("programaId", fila.programaId());
            campos.put("predioId", fila.predioId());
            campos.put("codRefCatastral", fila.codigoReferenciaCatastral());
            // Nulo cuando el predio no tiene titular vigente (#586, V73): es el predio que nadie
            // reclama, y la columna lo dice en vez de apartarlo.
            campos.put("contribuyenteId", fila.contribuyenteId());
            campos.put("condicion", fila.condicion().name());
            campos.put("areaCatastral", valor(fila.areaCatastral()));
            campos.put("areaDeclarada", valor(fila.areaDeclarada()));
            campos.put("sectorCodigo", fila.sectorCodigo());
            campos.put("fechaSorteo", fila.fechaSorteo());
            campos.put("observacion", observacion.texto());
            campos.put("usuario", usuario);
            campos.put("fechaRegistro", java.sql.Timestamp.from(fechaRegistro));

            escritas +=
                    jdbc().sql(
                                    "INSERT INTO programa_muestra"
                                            + " (municipalidad_id, programa_id, predio_id,"
                                            + "  cod_ref_catastral, contribuyente_id, condicion,"
                                            + "  area_catastral, area_declarada, sector_codigo,"
                                            + "  fecha_sorteo, observacion, usuario_registro,"
                                            + "  fecha_registro)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :programaId, :predioId, :codRefCatastral,"
                                            + "  :contribuyenteId, :condicion, :areaCatastral,"
                                            + "  :areaDeclarada, :sectorCodigo, :fechaSorteo,"
                                            + "  :observacion, :usuario, :fechaRegistro)")
                            .params(campos)
                            .update();
        }
        return escritas;
    }

    @Override
    public boolean tieneMuestra(long programaId) {
        Long cuantas =
                jdbc().sql("SELECT count(*)" + DESDE + " WHERE programa_id = :programaId")
                        .param("programaId", programaId)
                        .query(Long.class)
                        .single();
        return cuantas != null && cuantas > 0;
    }

    @Override
    public Pagina<MuestraDelPrograma> delPrograma(
            long programaId, @Nullable Long predioId, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE programa_id = :programaId");
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("programaId", programaId);
        if (predioId != null) {
            donde.append(" AND predio_id = :predioId");
            parametros.put("predioId", predioId);
        }

        String filtro = DESDE + donde;
        return paginar(
                "SELECT " + COLUMNAS + filtro,
                "SELECT count(*)" + filtro,
                parametros,
                paginacion,
                ORDEN,
                MuestraDelProgramaRepositoryJdbc::mapear);
    }

    /**
     * Los predios que otro programa <b>que admite visitas</b> ya se llevó.
     *
     * <p>El estado se lee de {@code programa_fiscalizacion} y no se copia en la fila: un programa
     * que se cierra deja de excluir el mismo día, y una copia diría lo de antes.
     */
    @Override
    public Set<Long> prediosEnProgramasAbiertos(long programaPropio, Set<Long> predios) {
        if (predios.isEmpty()) {
            return Set.of();
        }
        Map<String, Object> campos = new HashMap<>();
        campos.put("programaPropio", programaPropio);
        campos.put("predios", predios);

        return new HashSet<>(
                jdbc().sql(
                                "SELECT DISTINCT m.predio_id"
                                        + " FROM programa_muestra m"
                                        + " JOIN programa_fiscalizacion p"
                                        + "   ON p.municipalidad_id = m.municipalidad_id"
                                        + "  AND p.id = m.programa_id"
                                        + " WHERE m.predio_id IN (:predios)"
                                        + "   AND m.programa_id <> :programaPropio"
                                        + "   AND p.estado IN ('ABIERTO', 'EN_PROCESO')")
                        .params(campos)
                        .query(Long.class)
                        .list());
    }

    // ------------------------------------------------------------------

    private static @Nullable BigDecimal valor(@Nullable AreaM2 area) {
        return area == null ? null : area.valor();
    }

    private static MuestraDelPrograma mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new MuestraDelPrograma(
                fila.getLong("id"),
                fila.getLong("programa_id"),
                fila.getLong("predio_id"),
                fila.getString("cod_ref_catastral"),
                // getObject y no getLong: `getLong` devuelve 0 ante un NULL, y desde V73 esa
                // columna puede serlo. Un titular «0» no existe en ningun padron y es
                // indistinguible de uno que si — el defecto que #188 midio con getInt.
                fila.getObject("contribuyente_id", Long.class),
                CondicionFiscalizada.valueOf(fila.getString("condicion")),
                area(fila, "area_catastral"),
                area(fila, "area_declarada"),
                fila.getString("sector_codigo"),
                fila.getDate("fecha_sorteo").toLocalDate());
    }

    private static @Nullable AreaM2 area(ResultSet fila, String columna) throws SQLException {
        BigDecimal valor = fila.getBigDecimal(columna);
        return valor == null ? null : new AreaM2(valor);
    }
}
