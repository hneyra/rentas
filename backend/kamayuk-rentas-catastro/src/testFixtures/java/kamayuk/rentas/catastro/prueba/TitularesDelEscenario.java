package kamayuk.rentas.catastro.prueba;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: los titulares que publica {@code catastro}, leidos del escenario.
 *
 * <h2>Por que existe</h2>
 *
 * <p>`V6` retiro `titularidad` de esta base: quien es titular de un predio a una fecha lo contesta
 * {@code catastro}, y en produccion se pregunta por HTTP. Doce pruebas de {@code rentas} y de
 * {@code fiscalizacion} siembran su escenario en SQL —copropiedades, transferencias, predios sin
 * titular— y esperan que el puerto lo refleje.
 *
 * <p>Lee {@code titularidad_de_prueba}, que crea {@link
 * kamayuk.rentas.esquema.EscenarioDeCatastro}. NO es la consulta de produccion: aquella vive en
 * {@code catastro} con su indice y su prueba de plan (#561).
 *
 * <h2>El orden se conserva, y no es un detalle</h2>
 *
 * <p>De mayor a menor porcentaje, y con un desempate TOTAL. Varias pruebas de {@code rentas} lo
 * afirman —la fila de omisos lleva los titulares ordenados—, y un fixture que devolviera el orden
 * del monton dejaria esas aserciones pasando por casualidad.
 */
public final class TitularesDelEscenario implements TitularesDelPredio {

    private final JdbcClient jdbc;

    public TitularesDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
        return deVarios(List.of(predioId), fecha).getOrDefault(predioId, List.of());
    }

    @Override
    public boolean estaEnElPadron(long predioId) {
        Long cuantos =
                jdbc.sql(
                                "SELECT count(*) FROM predio_de_prueba"
                                        + " WHERE municipalidad_id = :municipalidad"
                                        + "   AND id = :predioId")
                        .param("municipalidad", municipalidadActual())
                        .param("predioId", predioId)
                        .query(Long.class)
                        .single();
        return cuantos != null && cuantos > 0;
    }

    @Override
    public Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha) {
        if (predioIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TitularDelPredio>> porPredio = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        SELECT predio_id, contribuyente_id, condicion, porcentaje
                          FROM titularidad_de_prueba
                         WHERE municipalidad_id = :municipalidad
                           AND predio_id = ANY(:predios)
                           AND vigencia_desde <= :fecha
                           AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)
                        """)
                .param("municipalidad", municipalidadActual())
                .param("predios", predioIds.toArray(Long[]::new))
                .param("fecha", Date.valueOf(fecha))
                .query(
                        (fila, numero) ->
                                porPredio
                                        .computeIfAbsent(
                                                fila.getLong("predio_id"), id -> new ArrayList<>())
                                        .add(
                                                new TitularDelPredio(
                                                        fila.getLong("contribuyente_id"),
                                                        fila.getString("condicion"),
                                                        Porcentaje.de(
                                                                fila.getBigDecimal("porcentaje")
                                                                        .toPlainString()))))
                .list();

        Map<Long, List<TitularDelPredio>> ordenado = new LinkedHashMap<>();
        porPredio.forEach(
                (predioId, cuotas) -> {
                    cuotas.sort(
                            Comparator.comparing(
                                            (TitularDelPredio cuota) -> cuota.porcentaje().valor())
                                    .reversed()
                                    .thenComparingLong(TitularDelPredio::contribuyenteId));
                    ordenado.put(predioId, List.copyOf(cuotas));
                });
        return Map.copyOf(ordenado);
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
