package kamayuk.rentas.fiscalizacion.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las transferencias a rentas contra PostgreSQL.
 *
 * <p>Solo {@code INSERT} y {@code SELECT}: V49 no le concede {@code UPDATE} ni {@code DELETE} a
 * {@code kamayuk_app}, y el escaner del codigo fuente vigila lo mismo desde arriba.
 *
 * <p>Ninguna consulta lleva {@code WHERE municipalidad_id = ?}: el filtrado lo hace la politica RLS
 * con el valor que {@code SET LOCAL} fijo al abrir la transaccion (regla 2).
 */
@Repository
public class ResolucionDeDeterminacionRepositoryJdbc extends RepositorioJdbc
        implements ResolucionDeDeterminacionRepository {

    private static final String COLUMNAS =
            "id, numero, documento_id, liquidacion_id, contribuyente_id, predio_id, vehiculo_id,"
                    + " ficha_anterior_id, ficha_nueva_id, fecha, documento_sustento, sustento,"
                    + " base_legal, usuario_registro, observacion";

    public ResolucionDeDeterminacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ResolucionDeDeterminacion registrar(ResolucionDeDeterminacion resolucion) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("numero", resolucion.numero());
        campos.put("documento", resolucion.documentoId());
        campos.put("liquidacion", resolucion.liquidacionId());
        campos.put("contribuyente", resolucion.contribuyenteId());
        campos.put("predio", resolucion.predioId());
        campos.put("vehiculo", resolucion.vehiculoId());
        campos.put("fichaAnterior", resolucion.fichaAnteriorId());
        campos.put("fichaNueva", resolucion.fichaNuevaId());
        campos.put("fecha", resolucion.fecha());
        campos.put("sustentoDocumental", resolucion.documentoSustento());
        campos.put("sustento", resolucion.sustento());
        campos.put("baseLegal", resolucion.baseLegal());
        campos.put("usuario", OrigenContext.actual().usuario());
        campos.put("observacion", resolucion.observacion().texto());

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO resolucion_determinacion"
                                            + " (municipalidad_id, numero, documento_id,"
                                            + "  liquidacion_id, contribuyente_id, predio_id,"
                                            + "  vehiculo_id, ficha_anterior_id, ficha_nueva_id, fecha,"
                                            + "  documento_sustento, sustento, base_legal,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :documento, :liquidacion, :contribuyente,"
                                            + "  :predio, :vehiculo, :fichaAnterior, :fichaNueva,"
                                            + "  :fecha, :sustentoDocumental, :sustento,"
                                            + "  :baseLegal, :usuario, now(), :observacion)"
                                            + " RETURNING id")
                            .params(campos)
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException duplicada) {
            // `resolucion_determinacion_liquidacion_uq` (V49), que es el AC 6. La comprobacion no
            // se escribe solo en Java porque dos peticiones simultaneas pasan las dos por
            // cualquier `if`: la de arriba ahorra el trabajo, esta es la que lo impide.
            throw new LiquidacionYaTransferida(resolucion.liquidacionId());
        }

        return conIdentificador(resolucion, id);
    }

    @Override
    public Optional<ResolucionDeDeterminacion> porNumero(String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion WHERE numero = :numero")
                .param("numero", numero.strip().toUpperCase(java.util.Locale.ROOT))
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ResolucionDeDeterminacion> deLiquidacion(long liquidacionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion"
                                + " WHERE liquidacion_id = :liquidacion")
                .param("liquidacion", liquidacionId)
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<ResolucionDeDeterminacion> deContribuyente(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_determinacion"
                                + " WHERE contribuyente_id = :contribuyente"
                                + " ORDER BY fecha DESC, id DESC")
                .param("contribuyente", contribuyenteId)
                .query(ResolucionDeDeterminacionRepositoryJdbc::mapear)
                .list();
    }

    private static ResolucionDeDeterminacion conIdentificador(
            ResolucionDeDeterminacion resolucion, Long id) {
        return new ResolucionDeDeterminacion(
                id,
                resolucion.numero(),
                resolucion.documentoId(),
                resolucion.liquidacionId(),
                resolucion.contribuyenteId(),
                resolucion.predioId(),
                resolucion.vehiculoId(),
                resolucion.fichaAnteriorId(),
                resolucion.fichaNuevaId(),
                resolucion.fecha(),
                resolucion.documentoSustento(),
                resolucion.sustento(),
                resolucion.baseLegal(),
                OrigenContext.actual().usuario(),
                resolucion.observacion());
    }

    private static ResolucionDeDeterminacion mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ResolucionDeDeterminacion(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getLong("documento_id"),
                fila.getLong("liquidacion_id"),
                fila.getLong("contribuyente_id"),
                entero(fila, "predio_id"),
                entero(fila, "vehiculo_id"),
                entero(fila, "ficha_anterior_id"),
                entero(fila, "ficha_nueva_id"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("documento_sustento"),
                fila.getString("sustento"),
                fila.getString("base_legal"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static @Nullable Long entero(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }
}
