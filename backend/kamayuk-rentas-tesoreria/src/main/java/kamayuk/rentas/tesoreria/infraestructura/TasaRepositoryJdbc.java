package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.tesoreria.dominio.TasaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las tarifas del TUPA contra PostgreSQL (V3, V29). */
@Repository
public class TasaRepositoryJdbc extends RepositorioJdbc implements TasaRepository {

    private static final String COLUMNAS =
            "id, codigo, descripcion, area_id, partida_presupuestal, importe, vigencia_desde,"
                    + " vigencia_hasta, documento_fuente";

    public TasaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    /**
     * La tarifa vigente a la fecha: la que ya habia empezado y todavia no habia terminado.
     *
     * <p>{@code ORDER BY vigencia_desde DESC LIMIT 1} y no un {@code max()} sobre el codigo: si dos
     * ordenanzas se solaparan por error, esto devuelve la mas reciente en vez de fallar con «mas de
     * una fila», que en ventanilla seria un error incomprensible. El indice {@code
     * tasa_vigencia_ix} (V29) esta escrito con el mismo orden, asi que se resuelve leyendo una
     * fila.
     */
    @Override
    public Optional<Tasa> vigenteA(String codigo, LocalDate fecha) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM tasa"
                                + " WHERE codigo = :codigo"
                                + "   AND vigencia_desde <= :fecha"
                                + "   AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)"
                                + " ORDER BY vigencia_desde DESC"
                                + " LIMIT 1")
                .param("codigo", codigo.strip().toUpperCase(Locale.ROOT))
                .param("fecha", fecha)
                .query(TasaRepositoryJdbc::mapear)
                .optional();
    }

    private static Tasa mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        Date hasta = fila.getDate("vigencia_hasta");
        return new Tasa(
                fila.getLong("id"),
                fila.getString("codigo"),
                fila.getString("descripcion"),
                fila.getLong("area_id"),
                fila.getString("partida_presupuestal"),
                new Dinero(fila.getBigDecimal("importe")),
                fila.getDate("vigencia_desde").toLocalDate(),
                hasta == null ? null : hasta.toLocalDate(),
                fila.getString("documento_fuente"));
    }
}
