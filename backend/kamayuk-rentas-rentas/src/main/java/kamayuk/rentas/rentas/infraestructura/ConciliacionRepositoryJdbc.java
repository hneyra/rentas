package kamayuk.rentas.rentas.infraestructura;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.ConciliacionRepository;
import kamayuk.rentas.rentas.dominio.EstadoDeDeclaracion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El recuento de la conciliacion, en <b>una</b> consulta (#564).
 *
 * <p>Por que esta consulta vive en {@code rentas} y no en {@code catastro} esta escrito en {@link
 * ConciliacionRepository}. Lo que hay que saber para leerla es esto:
 *
 * <ol>
 *   <li><b>La poblacion es la de la grilla, letra por letra.</b> {@code FROM ficha_ref f JOIN
 *       predio_ref p} y la vigencia a la fecha: desde P5C las dos son la <b>proyeccion local</b> de
 *       catastro (`V4`), no sus tablas — el recuento y la grilla tienen que contar lo mismo, y con
 *       dos bases eso solo se sostiene si el predicado cabe en un `WHERE` de esta base (ADR-0029, y
 *       #631 midio lo que pasa cuando no cabe). Un predio sin ficha no esta en la grilla y tampoco
 *       aqui; contarlo cambiaria el denominador y ninguna de las dos cifras pareceria mal. Que
 *       sigan siendo la misma poblacion lo comprueba una prueba, no este comentario.
 *   <li><b>La declaracion se busca con un {@code LATERAL} que trae una fila o ninguna.</b> Un
 *       predio puede tener mas de una declaracion vigente del mismo ejercicio y la respuesta es un
 *       si o un no: con un {@code JOIN} normal ese predio contaria dos veces y el total saldria
 *       mayor que el padron.
 *   <li><b>Los dos recuentos salen de la misma pasada.</b> Dos consultas —una para el total y otra
 *       para los conciliados— podrian responder a dos instantes distintos, y la resta de las dos
 *       daria un «sin conciliar» que no es de nadie.
 * </ol>
 *
 * <p>El {@code LEFT JOIN} sobre {@code predio_ref} no hace falta y seria enganoso: {@code
 * ficha_ref.predio_id} es obligatorio.
 */
@Repository
public class ConciliacionRepositoryJdbc extends RepositorioJdbc implements ConciliacionRepository {

    private static final String CONSULTA =
            """
            SELECT count(*)                                        AS total,
                   count(*) FILTER (WHERE dj.existe IS NOT NULL)    AS conciliados
              FROM ficha_ref f
              JOIN predio_ref p ON p.predio_id = f.predio_id
              LEFT JOIN LATERAL (
                  SELECT 1 AS existe
                    FROM declaracion_jurada d
                   WHERE d.predio_id = p.predio_id
                     AND d.ejercicio = :ejercicio
                     AND d.estado = ANY(:estados)
                   LIMIT 1) dj ON true
             WHERE f.vigencia_desde <= :fecha
               AND (f.vigencia_hasta IS NULL OR f.vigencia_hasta >= :fecha)
            """;

    public ConciliacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ResumenDeConciliacion contar(Ejercicio ejercicio, LocalDate aLaFecha) {
        Recuento recuento =
                jdbc().sql(CONSULTA)
                        .param("ejercicio", ejercicio.valor())
                        .param("estados", EstadoDeDeclaracion.nombresDeLasVigentes())
                        .param("fecha", aLaFecha)
                        .query(
                                (fila, numeroDeFila) ->
                                        new Recuento(
                                                fila.getLong("total"), fila.getLong("conciliados")))
                        .single();
        return ResumenDeConciliacion.de(
                ejercicio, aLaFecha, recuento.total(), recuento.conciliados());
    }

    /** Las dos cifras tal como salen de la misma fila. */
    private record Recuento(long total, long conciliados) {}
}
