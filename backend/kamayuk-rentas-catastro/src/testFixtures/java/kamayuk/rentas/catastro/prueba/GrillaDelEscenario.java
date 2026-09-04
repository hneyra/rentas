package kamayuk.rentas.catastro.prueba;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FichasDelPadron;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: la grilla de fichas que sirve {@code catastro}, leida del escenario.
 *
 * <h2>Por que existe</h2>
 *
 * <p>`V6` retiro `predio` y `ficha_catastral` de esta base: la grilla la publica {@code catastro} y
 * en produccion se pide por HTTP. Veinte pruebas de {@code rentas} —la conciliacion, la escritura
 * de la declaracion jurada— siembran su escenario en SQL y esperan que la grilla lo refleje, y lo
 * que necesitan del vecino es exactamente eso.
 *
 * <p>Lee las tablas {@code _de_prueba} que crea {@link kamayuk.rentas.esquema.EscenarioDeCatastro}.
 * NO es la consulta de produccion: aquella vive en {@code catastro}, con su acotacion en el mismo
 * {@code WHERE} que el conteo y sus pruebas contra PostgreSQL. Esta es lo justo para que la prueba
 * de {@code rentas} pueda medir lo suyo.
 *
 * <h2>La acotacion SI se honra, y es lo que se mide</h2>
 *
 * <p>Lo que estas pruebas siguen midiendo tras P5C es que {@code rentas} <b>componga bien</b> la
 * acotacion por predio (#631) —«solo los que declararon» o «solo los que no»— y la mande. Un
 * fixture que la ignorara dejaria esa composicion sin medir, que es la mitad del defecto que #631
 * documenta.
 */
public final class GrillaDelEscenario implements FichasDelPadron {

    private final JdbcClient jdbc;

    public GrillaDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Pagina<FichaDelPadron> buscar(
            BusquedaDeFichas criterio, LocalDate aLaFecha, Paginacion paginacion) {
        if (criterio.acotacion().noPuedeTraerNada()) {
            return Pagina.vacia(paginacion);
        }

        StringBuilder donde =
                new StringBuilder(
                        " WHERE p.municipalidad_id = :municipalidad"
                                + "   AND f.vigencia_desde <= :fecha"
                                + "   AND (f.vigencia_hasta IS NULL OR f.vigencia_hasta >= :fecha)");
        if (criterio.codRefCatastral() != null) {
            donde.append(" AND p.codigo_ref_catastral LIKE :codigo || '%'");
        }
        if (criterio.tipo() != null) {
            donde.append(" AND f.tipo = :tipo");
        }
        if (criterio.acotacion().modo() == AcotacionPorPredio.Modo.SOLO_ESTOS) {
            donde.append(" AND p.id = ANY(:predios)");
        } else if (criterio.acotacion().modo() == AcotacionPorPredio.Modo.TODOS_MENOS_ESTOS) {
            donde.append(" AND NOT (p.id = ANY(:predios))");
        }

        String desde =
                " FROM ficha_catastral_de_prueba f"
                        + " JOIN predio_de_prueba p"
                        + "   ON p.municipalidad_id = f.municipalidad_id AND p.id = f.predio_id";

        List<FichaDelPadron> filas = new ArrayList<>();
        var pagina =
                conParametros(
                        jdbc.sql(
                                "SELECT f.id AS ficha_id, p.id AS predio_id,"
                                        + " p.codigo_ref_catastral, p.direccion, p.lote, f.tipo,"
                                        + " f.version, f.area_terreno, f.uso, f.vigencia_desde"
                                        + desde
                                        + donde
                                        + " ORDER BY p.codigo_ref_catastral, f.version"
                                        + " LIMIT "
                                        + paginacion.tamano()
                                        + " OFFSET "
                                        + (long) paginacion.pagina() * paginacion.tamano()),
                        criterio,
                        aLaFecha);
        pagina.query(
                        (fila, numero) ->
                                filas.add(
                                        new FichaDelPadron(
                                                fila.getLong("ficha_id"),
                                                fila.getLong("predio_id"),
                                                fila.getString("codigo_ref_catastral"),
                                                fila.getString("direccion"),
                                                null,
                                                fila.getString("lote"),
                                                fila.getString("tipo"),
                                                fila.getInt("version"),
                                                AreaM2.de(
                                                        fila.getBigDecimal("area_terreno")
                                                                .toPlainString()),
                                                null,
                                                fila.getString("uso"),
                                                fila.getDate("vigencia_desde").toLocalDate(),
                                                null)))
                .list();

        Long total =
                conParametros(jdbc.sql("SELECT count(*)" + desde + donde), criterio, aLaFecha)
                        .query(Long.class)
                        .single();
        return Pagina.de(List.copyOf(filas), paginacion, total == null ? 0 : total);
    }

    private static JdbcClient.StatementSpec conParametros(
            JdbcClient.StatementSpec spec, BusquedaDeFichas criterio, LocalDate aLaFecha) {
        spec =
                spec.param("municipalidad", municipalidadActual())
                        .param("fecha", Date.valueOf(aLaFecha));
        if (criterio.codRefCatastral() != null) {
            spec = spec.param("codigo", criterio.codRefCatastral());
        }
        if (criterio.tipo() != null) {
            spec = spec.param("tipo", criterio.tipo());
        }
        if (criterio.acotacion().modo() != AcotacionPorPredio.Modo.TODOS) {
            spec = spec.param("predios", criterio.acotacion().predios().toArray(Long[]::new));
        }
        return spec;
    }

    /**
     * El filtro por municipalidad va ESCRITO, y en produccion no haria falta.
     *
     * <p>Lo pone RLS sobre las tablas de verdad; las del escenario no la llevan —su {@code
     * municipalidad_id} es anulable a proposito, ver {@code EscenarioDeCatastro}— asi que sin esto
     * una prueba con dos municipalidades veria las filas de la vecina. Es el primero de los dos
     * defectos que P5B §11 documenta, repetido aqui por el mismo motivo.
     */
    private static long municipalidadActual() {
        return kamayuk.rentas.compartido.TenantContext.actual().valor();
    }
}
