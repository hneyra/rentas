package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * La misma área, serializada por las cuatro proyecciones del módulo (#546, AC 2).
 *
 * <h2>Qué se mide, y por qué hace falta medirlo</h2>
 *
 * <p>{@code AreaM2} salía con <b>dos formas</b> en el mismo módulo: {@code "180.50 m2"} en {@code
 * OmisoResource} y {@code MuestraResource} —que componían el campo con {@code toString()}— y {@code
 * "180.50"} en {@code LiquidacionResource} y {@code ResolucionResource}, que lo componían con
 * {@code valor().toPlainString()}. El serializador que {@code ConfiguracionDeJson} registra para
 * {@code AreaM2} <b>no intervenía en ninguna de las cuatro</b>, porque los cuatro campos ya eran
 * {@code String}: el módulo tenía dos formas de escribir el mismo dato y ninguna era la del
 * sistema.
 *
 * <p>La forma que queda es la del serializador registrado —{@code toPlainString()}, sin unidad—,
 * que es la que el resto del sistema usa para los objetos de valor (RNF-055: todo decimal sale como
 * cadena). <b>La unidad, si se quiere enseñar, la pone la cabecera de la columna, no el dato</b>:
 * metida dentro obliga a cada consumidor a recortarla antes de poder comparar, y «180.50 m2» no es
 * un número para nadie.
 *
 * <p>Se serializa con el <b>mismo módulo de Jackson</b> que registra la aplicación. Comparar los
 * {@code record} en memoria no diría nada: lo que tiene que coincidir es el texto que sale por el
 * cable.
 *
 * <h2>La excepción, que se queda y por qué</h2>
 *
 * <p>{@code LiquidacionResource.CambioResource} —el historial de versiones— sigue escribiendo
 * {@code "300.00 m2"} en {@code antes} y {@code despues}, y no es un descuido. Esa celda <b>no es
 * la proyección de un área</b>: es la explicación de un cambio, y el mismo par de columnas lleva un
 * periodo, una condición o un uso según la fila —el javadoc de {@code CambioEntreVersiones} lo dice
 * desde #49—. Ahí la unidad no sobra: la distingue de las otras cosas con las que comparte columna.
 * Lo que este archivo fija es la forma del <b>dato</b>; lo que aquella escribe es prosa, y lo
 * comprueban {@code LiquidacionYSusVersionesTest} y {@code LiquidacionControllerTest}.
 */
@DisplayName("#546 — El area sale con UNA sola forma en las cuatro proyecciones del modulo")
class AreaEnUnaSolaFormaTest {

    /** La misma superficie para las cuatro. Dos decimales, que es lo que el dominio guarda. */
    private static final AreaM2 AREA = AreaM2.de("180.50");

    /** Lo que el serializador de {@code ConfiguracionDeJson} escribe: la cifra, sin unidad. */
    private static final String ESPERADO = "\"180.50\"";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    @Test
    @DisplayName("las cuatro escriben la misma cadena, y es la del serializador registrado")
    void lasCuatroEscribenLoMismo() {
        String omiso = campoDe(JSON.writeValueAsString(omiso()), "areaCatastral");
        String muestra = campoDe(JSON.writeValueAsString(muestra()), "areaCatastral");
        String liquidacion = campoDe(JSON.writeValueAsString(lineaDeLiquidacion()), "areaHallada");
        String resolucion = campoDe(JSON.writeValueAsString(lineaDeterminada()), "areaHallada");

        assertThat(List.of(omiso, muestra, liquidacion, resolucion))
                .as(
                        "hasta #546 omisos y muestra decian «180.50 m2» y liquidacion y resolucion"
                                + " «180.50»: cuatro proyecciones del mismo modulo con dos formas"
                                + " del mismo dato")
                .containsExactly(ESPERADO, ESPERADO, ESPERADO, ESPERADO);
    }

    @Test
    @DisplayName("y ninguna lleva la unidad dentro: la pone la cabecera de la columna")
    void ningunaLlevaLaUnidad() {
        assertThat(JSON.writeValueAsString(omiso())).doesNotContain("m2");
        assertThat(JSON.writeValueAsString(muestra())).doesNotContain("m2");
        assertThat(JSON.writeValueAsString(lineaDeLiquidacion())).doesNotContain("m2");
        assertThat(JSON.writeValueAsString(lineaDeterminada())).doesNotContain("m2");
    }

    @Test
    @DisplayName("el acta, que ya escribia la forma buena, sigue escribiendola")
    void elActaSigueIgual() {
        String acta =
                JSON.writeValueAsString(
                        new ActaFiscalizacionResource(
                                1L,
                                2L,
                                1,
                                3L,
                                4L,
                                null,
                                null,
                                "2026-03-01",
                                "J. PEREZ",
                                "SUBVALUADOR",
                                AREA,
                                null,
                                null,
                                "ABIERTA"));

        assertThat(campoDe(acta, "areaHallada")).isEqualTo(ESPERADO);
    }

    // ------------------------------------------------------------------

    private static OmisoResource omiso() {
        return OmisoResource.de(
                new FilaDeOmisos(
                        1L,
                        "000000000000000001",
                        "01",
                        List.of(),
                        new Ejercicio(2024),
                        CondicionFiscalizada.SUBVALUADOR,
                        false,
                        AREA,
                        AreaM2.de("120.00"),
                        null,
                        null,
                        null),
                Map.of());
    }

    private static MuestraResource muestra() {
        return MuestraResource.de(
                new MuestraDelPrograma(
                        5L,
                        2L,
                        1L,
                        "000000000000000001",
                        3L,
                        CondicionFiscalizada.SUBVALUADOR,
                        AREA,
                        AreaM2.de("120.00"),
                        "01",
                        LocalDate.of(2026, 3, 15)),
                "C-000001",
                "TITULAR, PRUEBA",
                false);
    }

    private static LiquidacionResource.LineaResource lineaDeLiquidacion() {
        return LiquidacionResource.LineaResource.de(linea());
    }

    private static ResolucionResource.LineaDeterminadaResource lineaDeterminada() {
        return ResolucionResource.LineaDeterminadaResource.de(linea());
    }

    private static LineaDeLiquidacion linea() {
        return new LineaDeLiquidacion(
                1L,
                2L,
                new Ejercicio(2024),
                7L,
                1L,
                null,
                CondicionFiscalizada.SUBVALUADOR,
                AreaM2.de("120.00"),
                AREA,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** El valor de un campo del JSON, tal cual sale: con sus comillas si es una cadena. */
    private static String campoDe(String json, String campo) {
        int desde = json.indexOf("\"" + campo + "\":");
        assertThat(desde).as("el campo %s tenia que estar en %s", campo, json).isNotNegative();
        int inicio = desde + campo.length() + 3;
        int fin = json.indexOf(',', inicio);
        if (fin < 0) {
            fin = json.indexOf('}', inicio);
        }
        return json.substring(inicio, fin);
    }
}
