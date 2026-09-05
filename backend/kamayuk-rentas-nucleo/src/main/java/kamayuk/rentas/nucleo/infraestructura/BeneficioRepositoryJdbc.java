package kamayuk.rentas.nucleo.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.Beneficio;
import kamayuk.rentas.nucleo.dominio.BeneficioRepository;
import kamayuk.rentas.nucleo.dominio.Clase;
import kamayuk.rentas.nucleo.dominio.CriterioDeBeneficio;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Beneficios y exoneraciones contra PostgreSQL. Sigue la plantilla de {@code
 * ContribuyenteRepositoryJdbc}: ninguna consulta filtra por {@code municipalidad_id} —lo hace la
 * politica RLS— y no hay ningun {@code DELETE}.
 *
 * <p>{@link #buscar} cruza con {@code contribuyente} para resolver el codigo de la ruta a un
 * identificador, en SQL: no es una dependencia de Java hacia ese contexto, asi que Spring Modulith
 * no la ve como tal.
 */
@Repository
public class BeneficioRepositoryJdbc extends RepositorioJdbc implements BeneficioRepository {

    private static final String COLUMNAS =
            "b.id, b.contribuyente_id, b.predio_id, b.vehiculo_id, b.tipo, b.tributo, b.clase,"
                    + " b.porcentaje, b.monto, b.vigencia_desde, b.vigencia_hasta, b.base_legal,"
                    + " b.documento_origen, b.observacion";

    private static final String DESDE = " FROM beneficio b";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("vigencia_desde", "tipo", "tributo", "id");

    public BeneficioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Beneficio> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE b.id = :id")
                .param("id", id)
                .query(BeneficioRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<Beneficio> buscar(CriterioDeBeneficio criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();
        String desde = DESDE;

        if (criterio.codigoContribuyente() != null) {
            desde = DESDE + " JOIN contribuyente c ON c.id = b.contribuyente_id";
            condiciones.add("c.codigo_contribuyente = :codigo");
            parametros.put("codigo", criterio.codigoContribuyente());
        }
        if (criterio.tipo() != null) {
            condiciones.add("b.tipo = :tipo");
            parametros.put("tipo", criterio.tipo());
        }
        if (criterio.vigentesA() != null) {
            condiciones.add(
                    "b.vigencia_desde <= :vigentesA AND (b.vigencia_hasta IS NULL OR"
                            + " b.vigencia_hasta >= :vigentesA)");
            parametros.put("vigentesA", criterio.vigentesA());
        }

        String donde = condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + desde + donde,
                "SELECT count(*)" + desde + donde,
                parametros,
                paginacion,
                ORDEN,
                BeneficioRepositoryJdbc::mapear);
    }

    @Override
    public List<Beneficio> delContribuyente(long contribuyenteId, String tipo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE b.contribuyente_id = :contribuyenteId AND b.tipo = :tipo")
                .param("contribuyenteId", contribuyenteId)
                .param("tipo", tipo)
                .query(BeneficioRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<Beneficio> vigentesDelPredio(long predioId, String tributo, LocalDate fecha) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE b.predio_id = :predioId AND b.tributo = :tributo"
                                + "   AND b.vigencia_desde <= :fecha"
                                + "   AND (b.vigencia_hasta IS NULL OR b.vigencia_hasta >= :fecha)")
                .param("predioId", predioId)
                .param("tributo", tributo)
                .param("fecha", fecha)
                .query(BeneficioRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<Beneficio> vigentesDelContribuyente(long contribuyenteId, LocalDate fecha) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE b.contribuyente_id = :contribuyente"
                                + "   AND b.vigencia_desde <= :fecha"
                                + "   AND (b.vigencia_hasta IS NULL OR b.vigencia_hasta >= :fecha)"
                                + " ORDER BY b.tributo, b.tipo, b.id")
                .param("contribuyente", contribuyenteId)
                .param("fecha", fecha)
                .query(BeneficioRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Beneficio insertar(Beneficio beneficio) {
        Long id =
                jdbc().sql(
                                "INSERT INTO beneficio"
                                        + " (municipalidad_id, contribuyente_id, predio_id,"
                                        + "  vehiculo_id, tipo, tributo, clase, porcentaje, monto,"
                                        + "  vigencia_desde, vigencia_hasta, base_legal,"
                                        + "  documento_origen, observacion, usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :contribuyenteId, :predioId, :vehiculoId, :tipo,"
                                        + "  :tributo, :clase, :porcentaje, :monto,"
                                        + "  :vigenciaDesde, :vigenciaHasta, :baseLegal,"
                                        + "  :documentoOrigen, :observacion, :usuario)"
                                        + " RETURNING id")
                        .params(camposDe(beneficio))
                        .param("usuario", usuarioActual())
                        .query(Long.class)
                        .single();

        return conId(beneficio, id);
    }

    @Override
    public Beneficio actualizar(Beneficio beneficio) {
        long id =
                java.util.Objects.requireNonNull(
                        beneficio.id(), "Solo se actualiza un beneficio ya guardado");

        Map<String, Object> campos = new HashMap<>(camposDe(beneficio));
        campos.put("id", id);

        int filas =
                jdbc().sql(
                                """
                                UPDATE beneficio
                                   SET predio_id        = :predioId,
                                       vehiculo_id      = :vehiculoId,
                                       tipo             = :tipo,
                                       tributo          = :tributo,
                                       clase            = :clase,
                                       porcentaje       = :porcentaje,
                                       monto            = :monto,
                                       vigencia_desde   = :vigenciaDesde,
                                       vigencia_hasta   = :vigenciaHasta,
                                       base_legal       = :baseLegal,
                                       documento_origen = :documentoOrigen
                                 WHERE id = :id
                                """)
                        .params(campos)
                        .update();
        if (filas == 0) {
            // No existe, o existe en otra municipalidad. Desde aqui son indistinguibles.
            throw new IllegalStateException(
                    "No hay ningun beneficio con identificador " + id + " en esta municipalidad");
        }
        return beneficio;
    }

    private static Map<String, Object> camposDe(Beneficio beneficio) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("contribuyenteId", beneficio.contribuyenteId());
        campos.put("predioId", beneficio.predioId());
        campos.put("vehiculoId", beneficio.vehiculoId());
        campos.put("tipo", beneficio.tipo());
        campos.put("tributo", beneficio.tributo());
        campos.put("clase", beneficio.clase().name());
        campos.put(
                "porcentaje",
                beneficio.porcentaje() == null ? null : beneficio.porcentaje().valor());
        campos.put("monto", beneficio.monto() == null ? null : beneficio.monto().valor());
        campos.put("vigenciaDesde", beneficio.vigenciaDesde());
        campos.put("vigenciaHasta", beneficio.vigenciaHasta());
        campos.put("baseLegal", beneficio.baseLegal());
        campos.put("documentoOrigen", beneficio.documentoOrigen());
        campos.put("observacion", beneficio.observacion().texto());
        return campos;
    }

    private static Beneficio conId(Beneficio beneficio, long id) {
        return new Beneficio(
                id,
                beneficio.contribuyenteId(),
                beneficio.predioId(),
                beneficio.vehiculoId(),
                beneficio.tipo(),
                beneficio.tributo(),
                beneficio.clase(),
                beneficio.porcentaje(),
                beneficio.monto(),
                beneficio.vigenciaDesde(),
                beneficio.vigenciaHasta(),
                beneficio.baseLegal(),
                beneficio.documentoOrigen(),
                beneficio.observacion());
    }

    /** El manual exige que toda modificacion diga quien la hizo, igual que en contribuyentes. */
    private static String usuarioActual() {
        return kamayuk.rentas.auditoria.OrigenContext.actual().usuario();
    }

    private static Beneficio mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;

        java.math.BigDecimal porcentaje = fila.getBigDecimal("porcentaje");
        java.math.BigDecimal monto = fila.getBigDecimal("monto");

        return new Beneficio(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                predioId,
                vehiculoId,
                fila.getString("tipo"),
                fila.getString("tributo"),
                Clase.valueOf(fila.getString("clase")),
                porcentaje == null ? null : new Alicuota(porcentaje),
                monto == null ? null : new Dinero(monto),
                fila.getDate("vigencia_desde").toLocalDate(),
                fila.getDate("vigencia_hasta") == null
                        ? null
                        : fila.getDate("vigencia_hasta").toLocalDate(),
                fila.getString("base_legal"),
                fila.getString("documento_origen"),
                Observacion.de(fila.getString("observacion")));
    }
}
