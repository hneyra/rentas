package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibreRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDeConstancias;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las constancias libres de infracciones contra PostgreSQL (V47 §3, #53).
 *
 * <p><b>Solo inserta y lee.</b> No hay {@code UPDATE} ni {@code DELETE}, ni existe el privilegio:
 * la constancia se entrega al administrado, que se lleva el papel. Una equivocada se deja sin
 * efecto con otra, y las dos quedan (regla 4, RNF-051).
 */
@Repository
public class ConstanciaLibreRepositoryJdbc extends RepositorioJdbc
        implements ConstanciaLibreRepository {

    private static final String COLUMNAS =
            "c.id, c.numero, c.documento_id, c.placa, c.vehiculo_id, c.solicitante_id,"
                    + " c.verificada_al, c.fecha_emision, c.usuario_registro, c.fecha_registro,"
                    + " c.observacion";

    private static final String DESDE = " FROM constancia_libre c";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_emision", "numero", "placa", "id");

    public ConstanciaLibreRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ConstanciaLibre registrar(ConstanciaLibre constancia) {
        if (!constancia.esNueva()) {
            throw new IllegalArgumentException(
                    "Una constancia ya emitida no se vuelve a insertar ni se corrige: se deja sin"
                            + " efecto con otra");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO constancia_libre"
                                        + " (municipalidad_id, numero, documento_id, placa,"
                                        + "  vehiculo_id, solicitante_id, verificada_al,"
                                        + "  fecha_emision, usuario_registro, fecha_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :numero, :documento, :placa, :vehiculo, :solicitante,"
                                        + "  :verificada, :emision, :usuario, :registrado,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("numero", constancia.numero())
                        .param("documento", constancia.documentoId())
                        .param("placa", constancia.placa())
                        .param("vehiculo", constancia.vehiculoId())
                        .param("solicitante", constancia.solicitanteId())
                        .param("verificada", constancia.verificadaAl())
                        .param("emision", constancia.fechaEmision())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(constancia.registradoEn()))
                        .param("observacion", constancia.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La constancia recien insertada no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<ConstanciaLibre> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE c.numero = :numero")
                .param("numero", numero.strip())
                .query(ConstanciaLibreRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<ConstanciaLibre> buscar(CriterioDeConstancias criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.desde() != null) {
            condiciones.add("c.fecha_emision >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            condiciones.add("c.fecha_emision <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        if (criterio.numero() != null) {
            condiciones.add("c.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.usuarioQueEmitio() != null) {
            condiciones.add("upper(c.usuario_registro) = :usuario");
            parametros.put("usuario", criterio.usuarioQueEmitio());
        }
        if (criterio.placa() != null) {
            condiciones.add("c.placa = :placa");
            parametros.put("placa", criterio.placa());
        }

        String donde = condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + DESDE + donde,
                "SELECT count(*)" + DESDE + donde,
                parametros,
                paginacion,
                ORDEN,
                ConstanciaLibreRepositoryJdbc::mapear);
    }

    private Optional<ConstanciaLibre> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE c.id = :id")
                .param("id", id)
                .query(ConstanciaLibreRepositoryJdbc::mapear)
                .optional();
    }

    private static ConstanciaLibre mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new ConstanciaLibre(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getLong("documento_id"),
                fila.getString("placa"),
                nulable(fila, "vehiculo_id"),
                nulable(fila, "solicitante_id"),
                fila.getDate("verificada_al").toLocalDate(),
                fila.getDate("fecha_emision").toLocalDate(),
                fila.getString("usuario_registro"),
                fila.getTimestamp("fecha_registro").toInstant(),
                Observacion.de(fila.getString("observacion")));
    }

    private static @Nullable Long nulable(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }
}
