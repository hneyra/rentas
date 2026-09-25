package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La hora del historial de placas sale con el desfase de la zona del producto (#327).
 *
 * <p>Es <b>el mismo dato</b> que #188 arreglo en la bitacora de auditoria —{@code auditoria.fecha},
 * un {@code timestamptz}— publicado por otra ruta: {@code GET /rentas/vehiculos/{placa}} → {@code
 * historialDePlacas[].fecha}. Hasta #327 salia como {@code String} escrito con {@code
 * OffsetDateTime.toString()} sobre lo que entrega pgjdbc, que llega con desfase UTC: el contrato lo
 * tipaba «texto» y la guarda de #188, que mira el tipo, no lo veia.
 *
 * <p>Se serializa con el mismo {@link JsonMapper} que monta la aplicacion, y no se mira el record:
 * lo que importa es lo que viaja.
 */
@DisplayName("#327 — La hora del historial de placas sale con su desfase")
class VehiculoResourceTest {

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    /**
     * Las 20:00 del 4 de marzo en el Peru, tal como la entrega pgjdbc: <b>con desfase UTC</b>, o
     * sea la 01:00 del dia 5. Escrita en UTC y no compuesta con la zona del producto a proposito:
     * una muestra que se compusiera con la misma constante que verifica no verificaria nada.
     */
    private static final OffsetDateTime COMO_LA_ENTREGA_PGJDBC =
            OffsetDateTime.parse("2026-03-05T01:00:00Z");

    @Test
    @DisplayName("un cambio de las 20:00 del 4 sale el 4 a las 20:00 con -05:00, no el 5 con Z")
    void laFechaDelCambioLlevaSuDesfase() {
        CambioDePlaca cambio =
                new CambioDePlaca(
                        Placa.de("V1H-882"),
                        Placa.de("V1H-883"),
                        "registrador.a",
                        COMO_LA_ENTREGA_PGJDBC,
                        "Duplicado de placa por robo");

        JsonNode ficha =
                JSON.readTree(
                        JSON.writeValueAsString(
                                VehiculoResource.de(unVehiculo(), List.of(cambio))));

        assertThat(ficha.get("historialDePlacas").get(0).get("fecha").asString())
                .as(
                        "las 20:00 del 4 de marzo en el Peru. Con el texto de pgjdbc salia"
                                + " «2026-03-05T01:00Z»: otro dia, y sin el desfase que la"
                                + " interfaz exige")
                .isEqualTo("2026-03-04T20:00:00-05:00");
    }

    private static Vehiculo unVehiculo() {
        return new Vehiculo(
                7L,
                Placa.de("V1H-883"),
                501L,
                "TOYOTA",
                "YARIS",
                "M1",
                new Ejercicio(2024),
                new Ejercicio(2025),
                null,
                null,
                EstadoVehiculo.ACTIVO);
    }
}
