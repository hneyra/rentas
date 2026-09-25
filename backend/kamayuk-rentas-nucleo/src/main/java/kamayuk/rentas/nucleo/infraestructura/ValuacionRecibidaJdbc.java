package kamayuk.rentas.nucleo.infraestructura;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionSellada;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee lo que la proyeccion de valuacion tiene (P5C, `V5`).
 *
 * <p>Ni un `INSERT`: quien escribe estas dos tablas es `rol_ingestor_catastro`, y a `kamayuk_app`
 * `V5` no le da mas que `SELECT`. Eso no es disciplina de esta clase — es un privilegio, y
 * `ProyeccionDeSoloLecturaTest` lo comprueba contra el catalogo.
 *
 * <h2>Los cinco metodos llevan {@code @Transactional}, y no es decorativo (#358)</h2>
 *
 * <p>Es la excepcion declarada a la convencion de {@link RepositorioJdbc} —«no abre transacciones:
 * las abre {@code @Transactional} sobre el caso de uso»—, y vale para una <b>proyeccion de solo
 * lectura</b> como esta: no hay escritura que esa convencion tenga que proteger, y en cambio la
 * lectura si necesita su transaccion. {@code valuacion_predio} y {@code valuacion_corrida} tienen
 * RLS forzada con la forma estricta de {@code current_setting}, y el {@code SET LOCAL} que la
 * politica lee lo emite {@code TenantTransactionManager} <b>al abrir una transaccion</b>: sin ella,
 * PostgreSQL no devuelve cero filas, falla — 42704 «unrecognized configuration parameter» en una
 * conexion nueva, 22P02 en una que ya sirvio otra transaccion —.
 *
 * <p>Y hay un llamador que no puede ponerla: {@code DeterminarPredial#determinar} no es
 * transaccional a proposito —una excepcion capturada dentro de la transaccion del anfitrion la deja
 * <i>rollback-only</i> (#54, #72)— y desde #52 lee {@link #deLosPredios} antes de escribir. Hasta
 * #358 esa lectura corria en autocommit y toda determinacion predial, individual, masiva o
 * simulada, contestaba 500 en cuanto el conjunto trajera los puntos {@code REDONDEO}. Con
 * propagacion {@code REQUIRED}, cada metodo se une a la transaccion de {@code CandadoDeEmision}
 * cuando la hay y abre la suya cuando no: la frontera de una lectura bajo RLS la pone quien lee, no
 * quien se acuerde. Es la misma doctrina de {@code LecturaDeLaCopiaLocalJdbc}, y la vigila {@code
 * DeterminarPredialContraLaValuacionDeLaBaseTest}, que llama sin envolver.
 */
@Repository
public class ValuacionRecibidaJdbc extends RepositorioJdbc implements ValuacionRecibida {

    public ValuacionRecibidaJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
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

    /** Las columnas de una valuacion sellada, escritas una sola vez para las dos lecturas. */
    private static final String COLUMNAS_DE_LA_VALUACION =
            """
            SELECT predio_id, fecha_de_corte, valor_terreno, valor_construccion,
                   valor_obras, valor_del_predio, motivo, llave_que_falta,
                   conjunto_id, reglas_version, huella
              FROM valuacion_predio
             WHERE ejercicio = :ejercicio
            """;

    @Override
    @Transactional(readOnly = true)
    public Optional<ValuacionSellada> delPredio(Ejercicio ejercicio, long predioId) {
        return jdbc().sql(COLUMNAS_DE_LA_VALUACION + "   AND predio_id = :predio")
                .param("ejercicio", ejercicio.valor())
                .param("predio", predioId)
                .query((fila, numeroDeFila) -> leer(fila))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ValuacionSellada> deLosPredios(Ejercicio ejercicio, List<Long> predioIds) {
        Map<Long, ValuacionSellada> porPredio = new LinkedHashMap<>();
        if (predioIds.isEmpty()) {
            // `IN ()` no es SQL valido, y preguntar por el ejercicio entero para filtrar despues
            // leeria el padron completo para no devolver nada.
            return porPredio;
        }
        for (ValuacionSellada valuacion :
                jdbc().sql(COLUMNAS_DE_LA_VALUACION + "   AND predio_id IN (:predios)")
                        .param("ejercicio", ejercicio.valor())
                        .param("predios", predioIds)
                        .query((fila, numeroDeFila) -> leer(fila))
                        .list()) {
            porPredio.put(valuacion.predioId(), valuacion);
        }
        return porPredio;
    }

    /**
     * Una fila de {@code valuacion_predio}.
     *
     * <p>Las cuatro cifras se leen con {@link #dineroOnulo}, que devuelve {@code null} y NO {@link
     * Dinero#CERO} cuando la columna es nula: {@code getBigDecimal} ya devuelve {@code null}, pero
     * envolverlo sin mirar produciria un cero, y un cero aqui es indistinguible de un predio que no
     * vale nada (#48). Es el mismo motivo por el que la tabla tiene su `CHECK`.
     */
    private static ValuacionSellada leer(java.sql.ResultSet fila) throws java.sql.SQLException {
        return new ValuacionSellada(
                fila.getLong("predio_id"),
                fila.getDate("fecha_de_corte").toLocalDate(),
                dineroOnulo(fila.getBigDecimal("valor_terreno")),
                dineroOnulo(fila.getBigDecimal("valor_construccion")),
                dineroOnulo(fila.getBigDecimal("valor_obras")),
                dineroOnulo(fila.getBigDecimal("valor_del_predio")),
                fila.getString("motivo"),
                fila.getString("llave_que_falta"),
                fila.getLong("conjunto_id"),
                fila.getString("reglas_version"),
                fila.getString("huella"));
    }

    private static @Nullable Dinero dineroOnulo(@Nullable BigDecimal valor) {
        return valor == null ? null : new Dinero(valor);
    }

    @Override
    @Transactional(readOnly = true)
    public long valuacionesRecibidasDe(Ejercicio ejercicio) {
        return jdbc().sql("SELECT count(*) FROM valuacion_predio WHERE ejercicio = :ejercicio")
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
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
