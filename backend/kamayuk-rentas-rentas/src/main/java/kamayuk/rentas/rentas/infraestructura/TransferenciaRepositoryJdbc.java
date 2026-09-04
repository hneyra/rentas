package kamayuk.rentas.rentas.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.rentas.dominio.TipoTransferencia;
import kamayuk.rentas.rentas.dominio.Transferencia;
import kamayuk.rentas.rentas.dominio.TransferenciaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las transferencias contra PostgreSQL (#29). */
@Repository
public class TransferenciaRepositoryJdbc extends RepositorioJdbc
        implements TransferenciaRepository {

    private static final String COLUMNAS =
            "t.id, t.objeto, t.predio_id, t.vehiculo_id, t.transferente_id, t.adquiriente_id,"
                    + " t.tipo_transferencia, t.fecha_transferencia, t.valor_transferencia,"
                    + " t.porcentaje_transferido, t.afecta_alcabala, t.documento_origen,"
                    + " t.observacion, t.usuario_registro";

    public TransferenciaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Transferencia insertar(Transferencia transferencia) {
        String usuario = OrigenContext.actual().usuario();

        Long id =
                jdbc().sql(
                                "INSERT INTO transferencia"
                                        + " (municipalidad_id, objeto, predio_id, vehiculo_id,"
                                        + "  transferente_id, adquiriente_id, tipo_transferencia,"
                                        + "  fecha_transferencia, valor_transferencia,"
                                        + "  porcentaje_transferido, afecta_alcabala,"
                                        + "  documento_origen, observacion, usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :objeto, :predioId, :vehiculoId, :transferenteId,"
                                        + "  :adquirienteId, :tipo, :fecha, :valor, :porcentaje,"
                                        + "  :afectaAlcabala, :documento, :observacion, :usuario)"
                                        + " RETURNING id")
                        .param("objeto", transferencia.objeto().name())
                        .param("predioId", transferencia.predioId())
                        .param("vehiculoId", transferencia.vehiculoId())
                        .param("transferenteId", transferencia.transferenteId())
                        .param("adquirienteId", transferencia.adquirienteId())
                        .param("tipo", transferencia.tipoTransferencia().name())
                        .param("fecha", transferencia.fechaTransferencia())
                        .param("valor", transferencia.valorTransferencia().valor())
                        .param("porcentaje", transferencia.porcentajeTransferido().valor())
                        .param("afectaAlcabala", transferencia.afectaAlcabala())
                        .param("documento", transferencia.documentoOrigen())
                        .param("observacion", transferencia.observacion().texto())
                        .param("usuario", usuario)
                        .query(Long.class)
                        .single();

        return new Transferencia(
                id,
                transferencia.objeto(),
                transferencia.predioId(),
                transferencia.vehiculoId(),
                transferencia.transferenteId(),
                transferencia.adquirienteId(),
                transferencia.tipoTransferencia(),
                transferencia.fechaTransferencia(),
                transferencia.valorTransferencia(),
                transferencia.porcentajeTransferido(),
                transferencia.afectaAlcabala(),
                transferencia.documentoOrigen(),
                transferencia.observacion(),
                usuario);
    }

    @Override
    public Optional<Transferencia> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM transferencia t WHERE t.id = :id")
                .param("id", id)
                .query(TransferenciaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<Transferencia> historicoDePredio(long predioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM transferencia t"
                                + " WHERE t.predio_id = :predio"
                                + " ORDER BY t.fecha_transferencia, t.id")
                .param("predio", predioId)
                .query(TransferenciaRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<Long> contribuyentePorCodigo(String codigo) {
        return jdbc().sql("SELECT c.id FROM contribuyente c WHERE c.codigo_contribuyente = :codigo")
                .param("codigo", codigo)
                .query((fila, numeroDeFila) -> fila.getLong("id"))
                .optional();
    }

    /**
     * De la fila a la transferencia.
     *
     * <p>{@code tipo_transferencia} se lee con {@link TipoTransferencia#de}, que <b>lanza</b> si el
     * texto no es uno de los nueve. Es deliberado: {@code V64} anade el {@code CHECK} como {@code
     * NOT VALID}, de modo que una fila anterior a #542 con el vocabulario libre —{@code
     * COMPRAVENTA}, sin ir mas lejos, que es lo que sembraba {@code ejemplos/transferencias.csv}—
     * <b>sigue en la tabla, sin tocar</b> (regla 4) y al leerla dice que valor tiene en vez de
     * devolver un tipo inventado. Una lectura tolerante la haria pasar por otra cosa, que es
     * exactamente lo que este issue vino a impedir.
     */
    private static Transferencia mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;

        return new Transferencia(
                fila.getLong("id"),
                ObjetoDeTransferencia.valueOf(fila.getString("objeto")),
                predioId,
                vehiculoId,
                fila.getLong("transferente_id"),
                fila.getLong("adquiriente_id"),
                TipoTransferencia.de(fila.getString("tipo_transferencia")),
                fila.getDate("fecha_transferencia").toLocalDate(),
                new Dinero(fila.getBigDecimal("valor_transferencia")),
                new Porcentaje(fila.getBigDecimal("porcentaje_transferido")),
                fila.getBoolean("afecta_alcabala"),
                fila.getString("documento_origen"),
                Observacion.de(fila.getString("observacion")),
                fila.getString("usuario_registro"));
    }
}
