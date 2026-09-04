package kamayuk.rentas.catastro.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import kamayuk.rentas.catastro.dominio.Arancel;
import kamayuk.rentas.catastro.dominio.Depreciacion;
import kamayuk.rentas.catastro.dominio.Partida;
import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import kamayuk.rentas.catastro.dominio.ValuacionRepository;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aranceles, valores unitarios de edificacion y depreciacion, leidos siempre por conjunto (#17);
 * escrito, solo el arancel (D-13, ADR-0017).
 *
 * <h2>Desde P5B los dos cuadros nacionales salen de la CACHE LOCAL, no de `normativa`</h2>
 *
 * <p>`valor_unitario_edificacion` y `depreciacion` se fueron a `normativa` con `V2`. Lo que estas
 * dos consultas leen ahora es `normativa_valor_unitario` y `normativa_depreciacion` (`V3`): la
 * copia del conjunto SELLADO, descargada una vez y verificada por su sha256 (ADR-0025 §1).
 *
 * <p><b>La forma de la consulta no cambia, y eso es deliberado.</b> Sigue siendo «todo el cuadro de
 * este conjunto», que es como la pide `TablasDeValuacion` al abrir una corrida —una vez, no una por
 * predio—. Lo que desaparece es el `JOIN` con `conjunto_parametro_detalle`: alli servia para ver
 * solo lo que el conjunto compuso, y aqui la copia local YA ES lo que el conjunto compuso.
 *
 * <p>El {@code INSERT} del arancel no comprueba el estado del conjunto antes de escribir: la
 * comprobacion la hace el disparador {@code valuacion_de_conjunto_sellado_es_inmutable} de {@code
 * V18}, y si aqui se leyera el estado antes de insertar habria una ventana entre las dos sentencias
 * donde una carga concurrente podria sellar el conjunto en medio. El disparador no tiene esa
 * ventana: corre en la misma sentencia.
 *
 * <p>El aislamiento se mantiene igual y por el mismo mecanismo: las dos tablas de la cache son de
 * tenant, con `municipalidad_id NOT NULL` y su politica RLS, de modo que preguntar por el conjunto
 * de otra municipalidad no devuelve nada en vez de devolver su cuadro. Antes lo garantizaba el
 * `JOIN` con una tabla de tenant; ahora lo garantiza que la copia misma lo sea.
 */
@Repository
public class ValuacionRepositoryJdbc extends RepositorioJdbc implements ValuacionRepository {

    public ValuacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    // ---------- Arancel ----------

    @Override
    public List<Arancel> arancelesDe(IdentificadorDeConjunto conjunto) {
        return jdbc().sql(
                        """
                        SELECT id, via_id, tramo, valor_m2, documento_fuente
                          FROM arancel
                         WHERE conjunto_id = :conjunto
                         ORDER BY via_id, tramo
                        """)
                .param("conjunto", conjunto.valor())
                .query(ValuacionRepositoryJdbc::mapearArancel)
                .list();
    }

    @Override
    public Arancel guardarArancel(Arancel arancel, IdentificadorDeConjunto conjunto) {
        long id =
                jdbc().sql(
                                """
                                INSERT INTO arancel
                                    (municipalidad_id, conjunto_id, via_id, tramo, valor_m2,
                                     documento_fuente)
                                VALUES
                                    (%s, :conjunto, :via, :tramo, :valorM2, :documentoFuente)
                                RETURNING id
                                """
                                        .formatted(MUNICIPALIDAD_ACTUAL))
                        .param("conjunto", conjunto.valor())
                        .param("via", arancel.viaId())
                        .param("tramo", arancel.tramo())
                        .param("valorM2", arancel.valorM2().valor())
                        .param("documentoFuente", arancel.documentoFuente())
                        .query(Long.class)
                        .single();
        return new Arancel(
                id, arancel.viaId(), arancel.tramo(), arancel.valorM2(), arancel.documentoFuente());
    }

    private static Arancel mapearArancel(ResultSet fila, int numero) throws SQLException {
        return new Arancel(
                fila.getLong("id"),
                fila.getLong("via_id"),
                fila.getString("tramo"),
                new ValorNormativo(fila.getBigDecimal("valor_m2")),
                fila.getString("documento_fuente"));
    }

    // ---------- Valor unitario de edificacion ----------

    @Override
    public List<ValorUnitarioEdificacion> valoresUnitariosDe(IdentificadorDeConjunto conjunto) {
        return jdbc().sql(
                        """
                        SELECT v.partida, v.categoria, v.anio_construccion_desde,
                               v.anio_construccion_hasta, v.valor_m2, v.documento_fuente
                          FROM normativa_valor_unitario v
                         WHERE v.conjunto_id = :conjunto
                         ORDER BY v.partida, v.categoria, v.anio_construccion_desde
                        """)
                .param("conjunto", conjunto.valor())
                .query(ValuacionRepositoryJdbc::mapearValorUnitario)
                .list();
    }

    private static ValorUnitarioEdificacion mapearValorUnitario(ResultSet fila, int numero)
            throws SQLException {
        Integer anioHasta = (Integer) fila.getObject("anio_construccion_hasta");
        return new ValorUnitarioEdificacion(
                // La copia local no lleva `id` propio: el de `normativa` no significa nada aqui y
                // arrastrarlo invitaria a usarlo para volver a esa base. Ninguna consulta de este
                // sistema lo mira; lo que identifica una celda es su llave (partida, categoria,
                // ano).
                0L,
                Partida.valueOf(fila.getString("partida")),
                fila.getString("categoria").charAt(0),
                fila.getInt("anio_construccion_desde"),
                anioHasta,
                new ValorNormativo(fila.getBigDecimal("valor_m2")),
                fila.getString("documento_fuente"));
    }

    // ---------- Depreciacion ----------

    @Override
    public List<Depreciacion> depreciacionesDe(IdentificadorDeConjunto conjunto) {
        return jdbc().sql(
                        """
                        SELECT p.uso, p.material, p.estado_conservacion,
                               p.antiguedad_hasta, p.porcentaje, p.documento_fuente
                          FROM normativa_depreciacion p
                         WHERE p.conjunto_id = :conjunto
                         ORDER BY p.uso, p.material, p.estado_conservacion,
                                  p.antiguedad_hasta NULLS LAST
                        """)
                .param("conjunto", conjunto.valor())
                .query(ValuacionRepositoryJdbc::mapearDepreciacion)
                .list();
    }

    private static Depreciacion mapearDepreciacion(ResultSet fila, int numero) throws SQLException {
        // getInt sobre un nulo devuelve 0, y aqui el nulo es «mas de 50 anios»: leerlo como cero
        // convertiria el tramo abierto en uno que no cubre nada, sin ningun error de por medio.
        Object tope = fila.getObject("antiguedad_hasta");
        return new Depreciacion(
                // Mismo motivo que en el valor unitario: la copia no lleva el `id` de `normativa`.
                0L,
                fila.getString("uso"),
                fila.getString("material"),
                fila.getString("estado_conservacion"),
                tope == null ? null : ((Number) tope).intValue(),
                new Alicuota(fila.getBigDecimal("porcentaje")),
                fila.getString("documento_fuente"));
    }
}
