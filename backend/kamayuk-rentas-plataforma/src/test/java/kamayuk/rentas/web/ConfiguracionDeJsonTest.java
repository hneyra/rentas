package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * RNF-055 en el transporte.
 *
 * <p>Proteger los importes con {@code BigDecimal} en Java y despues emitirlos como numero JSON no
 * protege nada: el {@code number} de JavaScript es binario de doble precision, asi que el navegador
 * que recibe {@code 1234567890123.45} lo redondea antes de que nadie lo vea. El contrato ya lo dice
 * —su esquema {@code Importe} es {@code type: string}—; esta prueba verifica que el codigo lo
 * cumple.
 */
@DisplayName("Capa web — Los importes viajan como cadena decimal")
class ConfiguracionDeJsonTest {

    private final ObjectMapper json =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    record Cuerpo(Dinero importe, Alicuota alicuota, Porcentaje porcentaje, AreaM2 area) {}

    @Test
    @DisplayName("un importe sale entre comillas, no como numero")
    void unImporteSaleEntreComillas() {
        String salida =
                json.writeValueAsString(
                        new Cuerpo(
                                Dinero.de("1234.50"),
                                Alicuota.de("0.6000"),
                                Porcentaje.de("50.00"),
                                AreaM2.de("120.75")));

        assertThat(salida)
                .contains("\"importe\":\"1234.50\"")
                .contains("\"alicuota\":\"0.6000\"")
                .contains("\"porcentaje\":\"50.00\"")
                .contains("\"area\":\"120.75\"");
        assertThat(salida)
                .as("un solo digito sin comillas y el navegador lo convierte en double")
                .doesNotContain(":1234.50")
                .doesNotContain(":120.75");
    }

    @Test
    @DisplayName("un importe con muchos digitos no pierde ni un centimo")
    void unImporteLargoNoPierdeCentimos() {
        Dinero grande = Dinero.de("9007199254740993.99");

        assertThat(json.writeValueAsString(grande))
                .as("ese numero ya no cabe exacto en un double de JavaScript")
                .isEqualTo("\"9007199254740993.99\"");
        assertThat(json.readValue("\"9007199254740993.99\"", Dinero.class)).isEqualTo(grande);
    }

    @Test
    @DisplayName("se acepta el numero al leer, para no romper a un cliente que mande 100")
    void seAceptaElNumeroAlLeer() {
        assertThat(json.readValue("100", Dinero.class)).isEqualTo(Dinero.de("100"));
        assertThat(json.readValue("\"100.00\"", Dinero.class)).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName(
            "#395 — un importe o un area con TRES decimales en el cuerpo se rechaza nombrando el"
                    + " campo, como el tecleado")
    void elTercerDecimalDelCuerpoSeRechaza() {
        // El cuerpo JSON es la tercera puerta de lo tecleado, despues del controlador y del
        // archivo que se importa. Hasta #629 el deserializador de Dinero y de AreaM2 aceptaba
        // cualquier escala, y la columna `dinero numeric(15,2)` la redondeaba en silencio.
        assertThat(
                        catchThrowable(
                                () ->
                                        json.readValue(
                                                "{\"importe\":\"33.333\",\"alicuota\":\"0.6000\","
                                                        + "\"porcentaje\":\"50.00\",\"area\":\"1\"}",
                                                Cuerpo.class)))
                .as(
                        "33.333 no es un importe que se pueda guardar, y redondearlo es decidir por otro")
                .hasRootCauseInstanceOf(ProblemaDeNegocio.class)
                .rootCause()
                .hasMessageContaining("'importe'")
                .hasMessageContaining("3 decimales");
        assertThat(
                        catchThrowable(
                                () ->
                                        json.readValue(
                                                "{\"importe\":\"1\",\"alicuota\":\"0.6000\","
                                                        + "\"porcentaje\":\"50.00\",\"area\":120.004}",
                                                Cuerpo.class)))
                .as("y el area, tambien cuando llega como numero JSON")
                .hasRootCauseInstanceOf(ProblemaDeNegocio.class)
                .rootCause()
                .hasMessageContaining("'area'");
    }

    @Test
    @DisplayName(
            "#395 — los ceros de la derecha no son un decimal mas, y la alicuota conserva su escala")
    void losCerosDeLaDerechaYLaAlicuota() {
        Cuerpo leido =
                json.readValue(
                        "{\"importe\":\"10.500\",\"alicuota\":\"0.6000\","
                                + "\"porcentaje\":\"33.3333\",\"area\":\"120.50\"}",
                        Cuerpo.class);

        assertThat(leido.importe()).isEqualTo(Dinero.de("10.5"));
        assertThat(leido.area()).isEqualTo(AreaM2.de("120.5"));
        assertThat(leido.alicuota().valor())
                .as("la alicuota y el porcentaje tienen su propia escala: la regla no es suya")
                .isEqualByComparingTo("0.6000");
        assertThat(leido.porcentaje().valor()).isEqualByComparingTo("33.3333");
    }

    @Test
    @DisplayName("la respuesta paginada tiene una sola forma, en español camelCase")
    void laRespuestaPaginadaTieneUnaSolaForma() {
        Pagina<String> pagina = Pagina.de(List.of("a", "b"), Paginacion.de(0, 2, "codigo"), 5);

        assertThat(json.writeValueAsString(RespuestaPaginada.de(pagina)))
                .isEqualTo(
                        "{\"contenido\":[\"a\",\"b\"],\"pagina\":0,\"tamano\":2,"
                                + "\"totalElementos\":5,\"totalPaginas\":3,\"hayMas\":true}");
    }

    @Test
    @DisplayName("un importe con su fecha sale con los dos campos juntos (RNF-075)")
    void unImporteConSuFechaSaleConLosDosCampos() {
        String salida =
                json.writeValueAsString(
                        new ImporteActualizado(Dinero.de("845.30"), LocalDate.of(2026, 8, 18)));

        assertThat(salida)
                .contains("\"importe\":\"845.30\"")
                .contains("\"actualizadoA\":\"2026-08-18\"");
    }
}
