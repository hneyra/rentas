package kamayuk.rentas.parametros.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La cache local de conjuntos sellados (`V3`).
 *
 * <p>Escribe una vez y lee siempre. No hay ningun {@code UPDATE} ni ningun {@code DELETE}, y
 * tampoco el privilegio para hacerlos: `V3` le concede a {@code kamayuk_app} {@code INSERT} y
 * {@code SELECT} y nada mas, de modo que la guarda no depende de que este codigo se porte bien.
 */
@Repository
public class CacheDeSnapshotsJdbc extends RepositorioJdbc implements CacheDeSnapshots {

    private final Clock reloj;

    public CacheDeSnapshotsJdbc(JdbcClient jdbc, Clock reloj) {
        super(jdbc);
        this.reloj = reloj;
    }

    @Override
    public boolean tiene(long conjuntoId, String ambito) {
        return jdbc().sql(
                                """
                        SELECT count(*) FROM normativa_conjunto
                         WHERE conjunto_id = :conjunto AND ambito = :ambito
                        """)
                        .param("conjunto", conjuntoId)
                        .param("ambito", ambito)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public Optional<Long> conjuntoCacheadoDe(Ejercicio ejercicio) {
        return jdbc().sql(
                        """
                        SELECT conjunto_id FROM normativa_conjunto
                         WHERE ejercicio = :ejercicio
                         ORDER BY version DESC, conjunto_id DESC
                         LIMIT 1
                        """)
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<IdentidadDelConjunto> identidadDe(long conjuntoId) {
        return jdbc().sql(
                        """
                        SELECT ejercicio, version FROM normativa_conjunto
                         WHERE conjunto_id = :conjunto
                         LIMIT 1
                        """)
                .param("conjunto", conjuntoId)
                .query(
                        (ResultSet fila, int numero) ->
                                new IdentidadDelConjunto(
                                        new Ejercicio(fila.getInt("ejercicio")),
                                        fila.getInt("version")))
                .optional();
    }

    @Override
    public List<SnapshotDeNormativa.Parametro> parametrosDe(long conjuntoId) {
        return jdbc().sql(
                        """
                        SELECT tipo, clave, valor_numerico, valor_texto,
                               vigencia_desde, vigencia_hasta, documento_fuente
                          FROM normativa_parametro
                         WHERE conjunto_id = :conjunto
                         ORDER BY tipo, clave NULLS FIRST, vigencia_desde NULLS FIRST
                        """)
                .param("conjunto", conjuntoId)
                .query(CacheDeSnapshotsJdbc::mapearParametro)
                .list();
    }

    private static SnapshotDeNormativa.Parametro mapearParametro(ResultSet fila, int numero)
            throws SQLException {
        return new SnapshotDeNormativa.Parametro(
                fila.getString("tipo"),
                fila.getString("clave"),
                texto(fila, "valor_numerico"),
                fila.getString("valor_texto"),
                texto(fila, "vigencia_desde"),
                texto(fila, "vigencia_hasta"),
                fila.getString("documento_fuente"));
    }

    private static @Nullable String texto(ResultSet fila, String columna) throws SQLException {
        Object valor = fila.getObject(columna);
        return valor == null ? null : valor.toString();
    }

    @Override
    public void guardar(SnapshotDeNormativa snapshot) {
        // El candado es de TRANSACCION y no de sesion: uno de sesion sobrevive a la devolucion de
        // la conexion al pool y bloquearia la peticion de otra municipalidad, que es la regla 3
        // aplicada a los candados. La clave es (municipalidad, conjunto), y la municipalidad sale
        // del contexto que fijo SET LOCAL, no de ningun argumento (regla 2).
        // La clave del candado es (municipalidad, conjunto), que es la forma de dos enteros que
        // `pg_advisory_xact_lock` admite. Se envuelve en un `count(*)` porque la funcion devuelve
        // `void` y un `SELECT` de void no se puede mapear a ningun tipo.
        jdbc().sql(
                        "SELECT count(*) FROM (SELECT pg_advisory_xact_lock("
                                + MUNICIPALIDAD_ACTUAL
                                + "::int, CAST(:conjunto AS int))) AS candado")
                .param("conjunto", snapshot.conjuntoId())
                .query(Long.class)
                .single();

        insertarIdentidad(snapshot);
        if (!tieneParametros(snapshot.conjuntoId())) {
            insertarParametros(snapshot);
        }
        insertarValoresUnitarios(snapshot);
        insertarDepreciaciones(snapshot);
        insertarValoresReferenciales(snapshot);
    }

    private boolean tieneParametros(long conjuntoId) {
        return jdbc().sql("SELECT count(*) FROM normativa_parametro WHERE conjunto_id = :conjunto")
                        .param("conjunto", conjuntoId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void insertarIdentidad(SnapshotDeNormativa snapshot) {
        jdbc().sql(
                        """
                        INSERT INTO normativa_conjunto
                            (municipalidad_id, conjunto_id, ejercicio, version, ambito,
                             sha256, filas, origen, descargado_en)
                        VALUES (%s, :conjunto, :ejercicio, :version, :ambito,
                                :sha256, :filas, :origen, :cuando)
                        """
                                .formatted(MUNICIPALIDAD_ACTUAL))
                .param("conjunto", snapshot.conjuntoId())
                .param("ejercicio", snapshot.ejercicio().valor())
                .param("version", snapshot.version())
                .param("ambito", snapshot.ambito())
                .param("sha256", snapshot.sha256())
                .param("filas", snapshot.filas())
                .param("origen", snapshot.origen())
                .param("cuando", java.time.OffsetDateTime.now(reloj))
                .update();
    }

    private void insertarParametros(SnapshotDeNormativa snapshot) {
        for (SnapshotDeNormativa.Parametro parametro : snapshot.parametros()) {
            jdbc().sql(
                            """
                            INSERT INTO normativa_parametro
                                (municipalidad_id, conjunto_id, tipo, clave, valor_numerico,
                                 valor_texto, vigencia_desde, vigencia_hasta, documento_fuente)
                            VALUES (%s, :conjunto, :tipo, :clave, CAST(:numerico AS numeric),
                                    :texto, CAST(:desde AS date), CAST(:hasta AS date), :fuente)
                            """
                                    .formatted(MUNICIPALIDAD_ACTUAL))
                    .param("conjunto", snapshot.conjuntoId())
                    .param("tipo", parametro.tipo())
                    .param("clave", parametro.clave())
                    .param("numerico", parametro.valorNumerico())
                    .param("texto", parametro.valorTexto())
                    .param("desde", parametro.vigenciaDesde())
                    .param("hasta", parametro.vigenciaHasta())
                    .param("fuente", parametro.documentoFuente())
                    .update();
        }
    }

    private void insertarValoresUnitarios(SnapshotDeNormativa snapshot) {
        for (SnapshotDeNormativa.ValorUnitario valor : snapshot.valoresUnitarios()) {
            jdbc().sql(
                            """
                            INSERT INTO normativa_valor_unitario
                                (municipalidad_id, conjunto_id, partida, categoria,
                                 anio_construccion_desde, anio_construccion_hasta,
                                 valor_m2, documento_fuente)
                            VALUES (%s, :conjunto, :partida, :categoria, :desde, :hasta,
                                    CAST(:valor AS numeric), :fuente)
                            """
                                    .formatted(MUNICIPALIDAD_ACTUAL))
                    .param("conjunto", snapshot.conjuntoId())
                    .param("partida", valor.partida())
                    .param("categoria", valor.categoria())
                    .param("desde", valor.anioConstruccionDesde())
                    .param("hasta", valor.anioConstruccionHasta())
                    .param("valor", valor.valorM2())
                    .param("fuente", valor.documentoFuente())
                    .update();
        }
    }

    private void insertarDepreciaciones(SnapshotDeNormativa snapshot) {
        for (SnapshotDeNormativa.Depreciacion fila : snapshot.depreciaciones()) {
            jdbc().sql(
                            """
                            INSERT INTO normativa_depreciacion
                                (municipalidad_id, conjunto_id, uso, material,
                                 estado_conservacion, antiguedad_hasta, porcentaje,
                                 documento_fuente)
                            VALUES (%s, :conjunto, :uso, :material, :estado, :hasta,
                                    CAST(:porcentaje AS numeric), :fuente)
                            """
                                    .formatted(MUNICIPALIDAD_ACTUAL))
                    .param("conjunto", snapshot.conjuntoId())
                    .param("uso", fila.uso())
                    .param("material", fila.material())
                    .param("estado", fila.estadoConservacion())
                    .param("hasta", fila.antiguedadHasta())
                    .param("porcentaje", fila.porcentaje())
                    .param("fuente", fila.documentoFuente())
                    .update();
        }
    }

    private void insertarValoresReferenciales(SnapshotDeNormativa snapshot) {
        for (SnapshotDeNormativa.ValorReferencial fila : snapshot.valoresReferenciales()) {
            jdbc().sql(
                            """
                            INSERT INTO normativa_valor_referencial
                                (municipalidad_id, conjunto_id, ejercicio, categoria, marca,
                                 modelo, anio_fabricacion, valor, documento_fuente)
                            VALUES (%s, :conjunto, :ejercicio, :categoria, :marca, :modelo,
                                    :anio, CAST(:valor AS numeric), :fuente)
                            """
                                    .formatted(MUNICIPALIDAD_ACTUAL))
                    .param("conjunto", snapshot.conjuntoId())
                    .param("ejercicio", fila.ejercicio())
                    .param("categoria", fila.categoria())
                    .param("marca", fila.marca())
                    .param("modelo", fila.modelo())
                    .param("anio", fila.anioFabricacion())
                    .param("valor", fila.valor())
                    .param("fuente", fila.documentoFuente())
                    .update();
        }
    }
}
