package kamayuk.rentas.catastro.prueba;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: los predios de un contribuyente, leidos del escenario (P5C).
 *
 * <p>`V6` retiro `predio` y `titularidad` de esta base. Este es el puerto del que cuelga la
 * determinacion predial —lleva el <b>porcentaje de propiedad</b> con el que se pondera la base—, y
 * varias pruebas de {@code rentas} siembran su escenario en SQL y esperan que lo refleje.
 *
 * <p>Publica los DOS porcentajes que {@code PredioDelContribuyente} lleva desde #690: la cuota de
 * quien pregunta y lo que suman TODAS las cuotas vigentes del predio. El segundo es el que hace
 * visible el saneamiento pendiente —304 predios de Catacaos con cuotas que no llegan a 100—, y
 * publicar siempre 100 lo escondería.
 */
public final class PrediosDelEscenario implements PrediosDelContribuyente {

    private final JdbcClient jdbc;

    public PrediosDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        return jdbc.sql(
                        """
                        SELECT p.id, p.codigo_ref_catastral, p.tipo, p.direccion, t.porcentaje,
                               (SELECT COALESCE(sum(o.porcentaje), 0)
                                  FROM titularidad_de_prueba o
                                 WHERE o.municipalidad_id = t.municipalidad_id
                                   AND o.predio_id = t.predio_id
                                   AND o.vigencia_desde <= :fecha
                                   AND (o.vigencia_hasta IS NULL OR o.vigencia_hasta >= :fecha))
                                   AS registrado
                          FROM titularidad_de_prueba t
                          JOIN predio_de_prueba p
                            ON p.municipalidad_id = t.municipalidad_id AND p.id = t.predio_id
                         WHERE t.municipalidad_id = :municipalidad
                           AND t.contribuyente_id = :contribuyenteId
                           AND t.vigencia_desde <= :fecha
                           AND (t.vigencia_hasta IS NULL OR t.vigencia_hasta >= :fecha)
                         ORDER BY p.codigo_ref_catastral
                        """)
                .param("municipalidad", municipalidadActual())
                .param("contribuyenteId", contribuyenteId)
                .param("fecha", Date.valueOf(fecha))
                .query(
                        (fila, numero) ->
                                new PredioDelContribuyente(
                                        fila.getLong("id"),
                                        fila.getString("codigo_ref_catastral"),
                                        fila.getString("tipo"),
                                        fila.getString("direccion"),
                                        Porcentaje.de(
                                                fila.getBigDecimal("porcentaje").toPlainString()),
                                        Porcentaje.de(
                                                fila.getBigDecimal("registrado").toPlainString())))
                .list();
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
