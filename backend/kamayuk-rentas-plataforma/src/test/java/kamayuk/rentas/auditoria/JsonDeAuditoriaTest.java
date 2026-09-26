package kamayuk.rentas.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El JSON de la auditoria, leido de vuelta por un parser de verdad (#434). */
@DisplayName("#434 — JsonDeAuditoria escribe lo que un parser estricto lee de vuelta")
class JsonDeAuditoriaTest {

    private static final JsonMapper ESTRICTO = new JsonMapper();

    @Test
    @DisplayName(
            "comilla, barra, tabulador, salto de linea y un control se leen de vuelta tal cual")
    void elTextoLibreVuelveEntero() {
        String raro = "Deposito \"San Jose\" \\ \t tab \n linea \r \u0001 fin";
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("deposito", raro);

        JsonNode leido = ESTRICTO.readTree(JsonDeAuditoria.objeto(campos));

        assertThat(leido.path("deposito").asString()).isEqualTo(raro);
    }

    @Test
    @DisplayName("una clave inyectada en un valor no es una clave: sigue dentro de su cadena")
    void noSeInyectanClaves() {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("placa", "ABC-123");
        campos.put("deposito", "Central\",\"placa\":\"XYZ-999");

        JsonNode leido = ESTRICTO.readTree(JsonDeAuditoria.objeto(campos));

        assertThat(leido.path("placa").asString()).isEqualTo("ABC-123");
        assertThat(leido.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("numeros, logicos, nulos, fechas, listas y mapas conservan su tipo")
    void losTiposSeConservan() {
        Map<String, Object> interior = new LinkedHashMap<>();
        interior.put("n", 3);
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("importe", new BigDecimal("120.50"));
        campos.put("id", 42L);
        campos.put("enPlazo", true);
        campos.put("origen", null);
        campos.put("fecha", LocalDate.of(2026, 9, 26));
        campos.put("lista", List.of("a", 1));
        campos.put("mapa", interior);

        assertThat(JsonDeAuditoria.objeto(campos))
                .isEqualTo(
                        "{\"importe\":120.50,\"id\":42,\"enPlazo\":true,\"origen\":null,"
                                + "\"fecha\":\"2026-09-26\",\"lista\":[\"a\",1],\"mapa\":{\"n\":3}}");
        assertThat(JsonDeAuditoria.de(null)).isNull();
    }
}
