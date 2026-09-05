package kamayuk.rentas.nucleo.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmisionRepository;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las corridas de emision predial contra PostgreSQL (#523).
 *
 * <p>Solo inserta y lee. La migracion {@code V62} no le concede {@code UPDATE} ni {@code DELETE}
 * sobre ninguna de las dos tablas, asi que la inmutabilidad no depende de que nadie escriba el
 * verbo: la sostiene el privilegio.
 */
@Repository
public class CorridaDeEmisionRepositoryJdbc extends RepositorioJdbc
        implements CorridaDeEmisionRepository {

    private static final String COLUMNAS =
            "id, ejercicio, alcance, sector, codigo_desde, codigo_hasta, modalidad,"
                    + " simulacion, conjunto,"
                    + " leidos, determinados, monto_emitido, fecha_calculo";

    /**
     * El unico orden que esta lectura admite: los observados salen en el orden en que se anotaron.
     */
    private static final OrdenSeguro ORDEN_DE_OBSERVADOS = OrdenSeguro.sobre("id");

    private final Clock reloj;

    public CorridaDeEmisionRepositoryJdbc(JdbcClient jdbc, Clock reloj) {
        super(jdbc);
        this.reloj = reloj;
    }

    @Override
    public CorridaDeEmision guardar(CorridaDeEmision corrida, Observacion observacion) {
        String usuario = OrigenContext.actual().usuario();

        /* `fecha_registro` sale del reloj inyectado y no de `now()`: la fila tiene
        que caer en el mismo instante con que se determino, que es lo que #24
        dejo escrito para la auditoria y vale igual aqui. */
        Long id =
                jdbc().sql(
                                "INSERT INTO corrida_predial"
                                        + " (municipalidad_id, ejercicio, alcance, sector, codigo_desde,"
                                        + "  codigo_hasta,"
                                        + "  modalidad, simulacion, conjunto, leidos,"
                                        + "  determinados, monto_emitido, fecha_calculo,"
                                        + "  usuario_registro, fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, :alcance, :sector, :codigoDesde,"
                                        + "  :codigoHasta, :modalidad,"
                                        + "  :simulacion, :conjunto, :leidos, :determinados,"
                                        + "  :monto, :fechaCalculo, :usuario, :registro,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("ejercicio", corrida.ejercicio().valor())
                        .param("alcance", corrida.alcance())
                        .param("sector", corrida.sector())
                        .param("codigoDesde", corrida.codigoDesde())
                        .param("codigoHasta", corrida.codigoHasta())
                        .param("modalidad", corrida.modalidad())
                        .param("simulacion", corrida.simulacion())
                        .param("conjunto", corrida.conjunto())
                        .param("leidos", corrida.leidos())
                        .param("determinados", corrida.determinados())
                        .param("monto", corrida.montoEmitido().valor())
                        .param("fechaCalculo", corrida.fechaCalculo())
                        .param("usuario", usuario)
                        .param("registro", OffsetDateTime.now(reloj))
                        .param("observacion", observacion.texto())
                        .query(Long.class)
                        .single();

        for (CorridaDeEmision.Observado observado : corrida.observados()) {
            jdbc().sql(
                            "INSERT INTO corrida_predial_observado"
                                    + " (municipalidad_id, corrida_id, cod_contribuyente,"
                                    + "  nombre, motivo)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :corrida, :codigo, :nombre, :motivo)")
                    .param("corrida", id)
                    .param("codigo", observado.codContribuyente())
                    .param("nombre", observado.nombre())
                    .param("motivo", observado.motivo())
                    .update();
        }

        return new CorridaDeEmision(
                id,
                corrida.ejercicio(),
                corrida.alcance(),
                corrida.sector(),
                corrida.codigoDesde(),
                corrida.codigoHasta(),
                corrida.modalidad(),
                corrida.simulacion(),
                corrida.conjunto(),
                corrida.leidos(),
                corrida.determinados(),
                corrida.montoEmitido(),
                corrida.fechaCalculo(),
                corrida.observados());
    }

    @Override
    public Optional<CorridaDeEmision> ultimaDe(Ejercicio ejercicio) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM corrida_predial"
                                + " WHERE ejercicio = :ejercicio"
                                + " ORDER BY id DESC"
                                + " LIMIT 1")
                .param("ejercicio", ejercicio.valor())
                .query(CorridaDeEmisionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<CorridaDeEmision> ultimas(int cuantas) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM corrida_predial ORDER BY id DESC LIMIT :n")
                .param("n", cuantas)
                .query(CorridaDeEmisionRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Pagina<CorridaDeEmision.Observado> observadosDe(long corridaId, Paginacion paginacion) {
        String desde = " FROM corrida_predial_observado WHERE corrida_id = :corrida";
        return paginar(
                "SELECT cod_contribuyente, nombre, motivo" + desde,
                "SELECT count(*)" + desde,
                Map.of("corrida", corridaId),
                paginacion,
                ORDEN_DE_OBSERVADOS,
                CorridaDeEmisionRepositoryJdbc::mapearObservado);
    }

    /**
     * La corrida <b>sin</b> sus observados: los pide la pantalla aparte.
     *
     * <p>Devolver la lista vacia y no nula es deliberado. Un {@code null} obligaria a cada lector a
     * distinguir «no los pedi» de «no hubo ninguno», y esas dos cosas se leen igual en una
     * pantalla: la corrida perfecta y la corrida a medio leer dirian lo mismo. Quien quiera los
     * observados llama a {@link #observadosDe}, que es donde esa distincion la hace el conteo.
     */
    private static CorridaDeEmision mapear(ResultSet fila, int numero) throws SQLException {
        return new CorridaDeEmision(
                fila.getLong("id"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getString("alcance"),
                fila.getString("sector"),
                fila.getString("codigo_desde"),
                fila.getString("codigo_hasta"),
                fila.getString("modalidad"),
                fila.getBoolean("simulacion"),
                fila.getString("conjunto"),
                fila.getInt("leidos"),
                fila.getInt("determinados"),
                new Dinero(fila.getBigDecimal("monto_emitido")),
                fila.getObject("fecha_calculo", java.time.LocalDate.class),
                List.of());
    }

    private static CorridaDeEmision.Observado mapearObservado(ResultSet fila, int numero)
            throws SQLException {
        return new CorridaDeEmision.Observado(
                fila.getString("cod_contribuyente"),
                fila.getString("nombre"),
                fila.getString("motivo"));
    }
}
