package kamayuk.rentas.nucleo.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublico;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublicoRepository;
import kamayuk.rentas.nucleo.dominio.espectaculos.EstadoDeEspectaculo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Los espectáculos públicos contra PostgreSQL, sobre la tabla {@code espectaculo} de V2 (#32). */
@Repository
public class EspectaculoPublicoRepositoryJdbc extends RepositorioJdbc
        implements EspectaculoPublicoRepository {

    private static final String COLUMNAS =
            "e.id, e.contribuyente_id, e.denominacion, e.tipo, e.lugar, e.fecha_evento, e.aforo,"
                    + " e.valor_entrada, e.base_imponible, e.estado, e.usuario_registro";

    public EspectaculoPublicoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public EspectaculoPublico insertar(EspectaculoPublico evento) {
        String usuario = OrigenContext.actual().usuario();

        Long id =
                jdbc().sql(
                                "INSERT INTO espectaculo"
                                        + " (municipalidad_id, contribuyente_id, denominacion, tipo,"
                                        + "  lugar, fecha_evento, aforo, valor_entrada, estado,"
                                        + "  usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :contribuyenteId, :denominacion, :tipo, :lugar,"
                                        + "  :fechaEvento, :aforo, :valorEntrada, :estado, :usuario)"
                                        + " RETURNING id")
                        .param("contribuyenteId", evento.contribuyenteId())
                        .param("denominacion", evento.denominacion())
                        .param("tipo", evento.tipo())
                        .param("lugar", evento.lugar())
                        .param("fechaEvento", evento.fechaEvento())
                        .param("aforo", evento.aforo())
                        .param("valorEntrada", valorDe(evento.valorEntrada()))
                        .param("estado", evento.estado().name())
                        .param("usuario", usuario)
                        .query(Long.class)
                        .single();

        return new EspectaculoPublico(
                id,
                evento.contribuyenteId(),
                evento.denominacion(),
                evento.tipo(),
                evento.lugar(),
                evento.fechaEvento(),
                evento.aforo(),
                evento.valorEntrada(),
                evento.baseImponible(),
                evento.estado(),
                usuario);
    }

    @Override
    public Optional<EspectaculoPublico> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM espectaculo e WHERE e.id = :id")
                .param("id", id)
                .query(EspectaculoPublicoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public EspectaculoPublico liquidar(long id, Dinero baseImponible) {
        jdbc().sql(
                        "UPDATE espectaculo SET base_imponible = :baseImponible, estado ="
                                + " 'LIQUIDADO' WHERE id = :id")
                .param("baseImponible", baseImponible.valor())
                .param("id", id)
                .update();
        return findById(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El espectaculo " + id + " desaparecio tras liquidarlo"));
    }

    private static @Nullable BigDecimal valorDe(@Nullable Dinero dinero) {
        return dinero == null ? null : dinero.valor();
    }

    private static EspectaculoPublico mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        int aforo = fila.getInt("aforo");
        Integer aforoValor = fila.wasNull() ? null : aforo;
        BigDecimal valorEntrada = fila.getBigDecimal("valor_entrada");
        BigDecimal baseImponible = fila.getBigDecimal("base_imponible");

        return new EspectaculoPublico(
                fila.getLong("id"),
                fila.getLong("contribuyente_id"),
                fila.getString("denominacion"),
                fila.getString("tipo"),
                fila.getString("lugar"),
                fila.getDate("fecha_evento").toLocalDate(),
                aforoValor,
                valorEntrada == null ? null : new Dinero(valorEntrada),
                baseImponible == null ? null : new Dinero(baseImponible),
                EstadoDeEspectaculo.valueOf(fila.getString("estado")),
                fila.getString("usuario_registro"));
    }
}
