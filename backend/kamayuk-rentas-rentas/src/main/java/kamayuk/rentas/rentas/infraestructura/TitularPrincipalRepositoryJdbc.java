package kamayuk.rentas.rentas.infraestructura;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.arbitrios.TitularPrincipalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TitularPrincipalRepositoryJdbc extends RepositorioJdbc
        implements TitularPrincipalRepository {

    public TitularPrincipalRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Long> principalDe(long predioId, LocalDate fecha) {
        return jdbc().sql(
                        """
                        SELECT contribuyente_id
                          FROM titularidad
                         WHERE predio_id = :predioId
                           AND vigencia_desde <= :fecha
                           AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)
                         ORDER BY porcentaje DESC, id ASC
                         LIMIT 1
                        """)
                .param("predioId", predioId)
                .param("fecha", fecha)
                .query(Long.class)
                .optional();
    }
}
