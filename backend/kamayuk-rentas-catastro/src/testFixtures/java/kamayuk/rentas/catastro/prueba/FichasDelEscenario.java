package kamayuk.rentas.catastro.prueba;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.dominio.AreaM2;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>FIXTURE DE PRUEBA</b>: la ficha vigente y su area, leidas del escenario (P5C).
 *
 * <p>`V6` retiro `ficha_catastral` de esta base. Lo que varias pruebas de {@code rentas} y de
 * {@code fiscalizacion} necesitan del vecino es la premisa —«este predio tiene esta ficha, con esta
 * area, vigente a esta fecha»— y la siembran en SQL; esto la lee.
 *
 * <p>La resolucion por fecha se conserva porque es lo que hace que las aserciones de {@code rentas}
 * digan algo: que la fecha VIAJE, y que una declaracion de 2024 quede enlazada a la ficha que regia
 * entonces (#28). Que esa resolucion sea correcta contra el esquema es de {@code catastro}, y sus
 * once pruebas viven alli.
 */
public final class FichasDelEscenario implements LectorDeFichas, LectorDeFichasEconomicas {

    private final JdbcClient jdbc;

    public FichasDelEscenario(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
        return vigente(predioId, fecha, "UNICA");
    }

    @Override
    public Optional<Long> fichaEconomicaVigenteEn(long predioId, LocalDate fecha) {
        return vigente(predioId, fecha, "ECONOMICA");
    }

    @Override
    public Optional<AreaM2> areaDeLaVersion(long fichaId) {
        return jdbc.sql(
                        "SELECT area_terreno FROM ficha_catastral_de_prueba"
                                + " WHERE municipalidad_id = :municipalidad AND id = :fichaId")
                .param("municipalidad", municipalidadActual())
                .param("fichaId", fichaId)
                .query(java.math.BigDecimal.class)
                .optional()
                .map(valor -> AreaM2.de(valor.toPlainString()));
    }

    private Optional<Long> vigente(long predioId, LocalDate fecha, String tipo) {
        return jdbc.sql(
                        """
                        SELECT id
                          FROM ficha_catastral_de_prueba
                         WHERE municipalidad_id = :municipalidad
                           AND predio_id = :predioId
                           AND tipo = :tipo
                           AND vigencia_desde <= :fecha
                           AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)
                         ORDER BY version DESC
                         LIMIT 1
                        """)
                .param("municipalidad", municipalidadActual())
                .param("predioId", predioId)
                .param("tipo", tipo)
                .param("fecha", Date.valueOf(fecha))
                .query(Long.class)
                .optional();
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
