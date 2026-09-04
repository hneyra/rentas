package kamayuk.rentas.parametros.infraestructura;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.PublicacionDeParametros;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Publicacion de valores normativos contra {@code parametro_tributario}.
 *
 * <p><b>{@code @Profile("batch")} y no un bean cualquiera.</b> La escritura de esta tabla solo la
 * puede ejecutar {@code rol_carga_parametros} (V7), y esa credencial la lleva el Job de un solo uso
 * que corre {@code PublicarParametros}, no el proceso web. Un bean disponible en el perfil por
 * omision seria un camino que existe y no funciona: cualquiera podria inyectarlo desde un
 * controlador y el fallo llegaria en produccion como un error de privilegio, no como una
 * compilacion rota.
 *
 * <p>Ninguna sentencia de aqui menciona {@code municipalidad_id} en el {@code WHERE} (regla 2), y
 * la insercion lo deja en {@code NULL} a proposito: lo que este proceso publica son valores de
 * <b>ambito nacional</b> —la UIT, los tramos del TUO—, la unica excepcion admitida al filtrado por
 * tenant, implementada como politica RLS y no desactivandola (ADR-0007).
 */
@Repository
@Profile("batch")
public class PublicacionDeParametrosJdbc implements PublicacionDeParametros {

    private static final String COLUMNAS =
            "id, tipo, clave, valor_numerico, valor_texto, vigencia_desde, vigencia_hasta,"
                    + " documento_fuente";

    private final JdbcClient jdbc;

    public PublicacionDeParametrosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long publicar(ParametroTributario parametro, String transcribio, String verifico) {
        return jdbc.sql(
                        "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                + " valor_numerico, valor_texto, vigencia_desde, vigencia_hasta,"
                                + " documento_fuente, usuario_carga, usuario_aprueba)"
                                + " VALUES (NULL, :tipo, :clave, :numerico, :texto, :desde, :hasta,"
                                + " :fuente, :carga, :aprueba) RETURNING id")
                .param("tipo", parametro.tipo())
                .param("clave", parametro.clave())
                .param("numerico", parametro.numero().map(ValorNormativo::valor).orElse(null))
                .param("texto", parametro.valorTexto())
                .param("desde", fecha(parametro.vigencia().desde()))
                .param("hasta", fecha(parametro.vigencia().hasta()))
                .param("fuente", parametro.documentoFuente())
                .param("carga", transcribio)
                .param("aprueba", verifico)
                .query(Long.class)
                .single();
    }

    @Override
    public List<ParametroTributario> publicados(LlaveDeParametro llave) {
        // IS NOT DISTINCT FROM y no `=`, por lo mismo que en ParametrosRepositoryJdbc: la clave
        // admite nulo —la UIT es el tipo con un solo valor— y `clave = NULL` no devuelve ninguna
        // fila ni falla, que es la peor de las dos respuestas posibles. Con `=`, la segunda corrida
        // volveria a publicar las cinco filas de la UIT sin decir nada.
        return jdbc.sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM parametro_tributario"
                                + " WHERE tipo = :tipo"
                                + "   AND clave IS NOT DISTINCT FROM :clave"
                                + "   AND vigencia_desde = :desde"
                                + " ORDER BY id")
                .param("tipo", llave.tipo())
                .param("clave", llave.clave())
                .param("desde", Date.valueOf(llave.vigenciaDesde()))
                .query(PublicacionDeParametrosJdbc::mapear)
                .list();
    }

    private static @Nullable Date fecha(@Nullable LocalDate valor) {
        return valor == null ? null : Date.valueOf(valor);
    }

    private static ParametroTributario mapear(ResultSet fila, int numero) throws SQLException {
        BigDecimal numerico = fila.getBigDecimal("valor_numerico");
        Date desde = fila.getDate("vigencia_desde");
        Date hasta = fila.getDate("vigencia_hasta");
        return new ParametroTributario(
                fila.getLong("id"),
                fila.getString("tipo"),
                fila.getString("clave"),
                numerico == null ? null : new ValorNormativo(numerico),
                fila.getString("valor_texto"),
                new Vigencia(
                        desde == null ? null : desde.toLocalDate(),
                        hasta == null ? null : hasta.toLocalDate()),
                fila.getString("documento_fuente"));
    }
}
