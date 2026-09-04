package kamayuk.rentas.rentas.infraestructura;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.predial.ValuacionRecibida;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lee lo que la proyeccion de valuacion tiene (P5C, `V5`).
 *
 * <p>Ni un `INSERT`: quien escribe estas dos tablas es `rol_ingestor_catastro`, y a `sgtm_app` `V5`
 * no le da mas que `SELECT`. Eso no es disciplina de esta clase — es un privilegio, y
 * `ProyeccionDeSoloLecturaTest` lo comprueba contra el catalogo.
 */
@Repository
public class ValuacionRecibidaJdbc extends RepositorioJdbc implements ValuacionRecibida {

    public ValuacionRecibidaJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<CierreDeCorrida> cierreDe(Ejercicio ejercicio) {
        return jdbc().sql(
                        """
                        SELECT corrida_id, conjunto_id, fecha_de_corte, reglas_version,
                               conteo, huella
                          FROM valuacion_corrida
                         WHERE ejercicio = :ejercicio
                        """)
                .param("ejercicio", ejercicio.valor())
                .query(
                        (fila, numeroDeFila) ->
                                new CierreDeCorrida(
                                        fila.getLong("corrida_id"),
                                        fila.getLong("conjunto_id"),
                                        fila.getDate("fecha_de_corte").toLocalDate(),
                                        fila.getString("reglas_version"),
                                        fila.getInt("conteo"),
                                        fila.getString("huella")))
                .optional();
    }

    @Override
    public long valuacionesRecibidasDe(Ejercicio ejercicio) {
        return jdbc().sql("SELECT count(*) FROM valuacion_predio WHERE ejercicio = :ejercicio")
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .single();
    }

    @Override
    public String huellaDeLoRecibido(Ejercicio ejercicio) {
        // La huella de las huellas, en un orden TOTAL y declarado. Sin `ORDER BY` el agregado
        // depende del plan, y entonces la misma proyeccion daria huellas distintas segun por
        // donde el motor decidiera leerla: la comparacion fallaria sin que faltara nada, que es
        // la peor forma de que un candado se ponga rojo.
        //
        // `encode(digest(...))` viene de pgcrypto, que este esquema no instala, asi que se
        // compone en SQL con `md5`... no: se usa `sha256` del propio motor, disponible desde
        // PostgreSQL 11 como `sha256(bytea)`.
        return jdbc().sql(
                        """
                        SELECT encode(
                                 sha256(
                                   convert_to(
                                     coalesce(string_agg(h.huella, ',' ORDER BY h.predio_id), ''),
                                     'UTF8')),
                                 'hex')
                          FROM (SELECT predio_id, huella
                                  FROM valuacion_predio
                                 WHERE ejercicio = :ejercicio) h
                        """)
                .param("ejercicio", ejercicio.valor())
                .query(String.class)
                .single();
    }
}
