package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ImporteActualizado;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * La forma que {@link FormaDeLaRespuesta} describe es la que Jackson emite.
 *
 * <h2>Por que hace falta</h2>
 *
 * <p>{@code docs/50-api/formas-de-la-api.json} lo deriva {@link FormasDeLaApiTest} de los {@code
 * record} de cada controlador, y el frontend lo usa para decidir si su proxy publica la forma
 * correcta (#400). Todo eso descansa en una traduccion escrita a mano —de tipos de Java a hojas de
 * JSON— y esa traduccion <b>ya se equivoco una vez</b>: {@code Dinero} es un record, asi que salia
 * como <code>{valor: numero}</code> cuando {@code ConfiguracionDeJson} lo serializa con {@code
 * writeString} (RNF-055). No lo detecto nada, porque el comparador del frontend ve una cadena donde
 * la forma dice objeto, no puede comparar y se calla.
 *
 * <p>Asi que aqui no se razona sobre lo que Jackson emite: <b>se le hace emitir</b>. Se serializa
 * una respuesta con el mismo modulo que registra la aplicacion y se compara su arbol de claves
 * contra lo que el resolutor dice de ese mismo tipo.
 *
 * <h2>Por que un ejemplo y no las 178</h2>
 *
 * <p>Porque instanciar los 178 recursos exigiria inventar un valor valido para cada componente, y
 * varios objetos de valor validan en su constructor —un ejercicio fuera de rango, un codigo de
 * referencia catastral con otro formato—: la mitad se caeria construyendo, y una prueba que salta
 * la mitad de sus casos no dice lo que parece decir. El ejemplo lleva <b>lo que puede
 * equivocarse</b>: el sobre paginado, los cuatro objetos de valor que se serializan como cadena, un
 * record anidado, una lista de records, una fecha, un instante, un enumerado y un nulo.
 */
@DisplayName("La forma descrita es la que Jackson emite")
class FormaSegunJacksonTest {

    /** Un enumerado sale como su nombre, que en JSON es una cadena. */
    enum EstadoDeEjemplo {
        VIGENTE
    }

    /** Un record anidado dentro de otro, para que la comparacion baje un nivel. */
    record HijoDeEjemplo(String descripcion, AreaM2 area, @Nullable LocalDate desde) {}

    /**
     * Lo que puede equivocarse, en un solo recurso.
     *
     * <p>Los cuatro objetos de valor son records y se serializan como cadena; {@link
     * ImporteActualizado} es un record que <b>si</b> sale como objeto, y tenerlos juntos es lo que
     * distingue «es un record» de «se serializa como objeto».
     */
    record RecursoDeEjemplo(
            long id,
            String codigo,
            EstadoDeEjemplo estado,
            LocalDate fecha,
            Instant calculadoEn,
            Dinero importe,
            Alicuota alicuota,
            Porcentaje porcentaje,
            AreaM2 area,
            ImporteActualizado conSuFecha,
            List<HijoDeEjemplo> hijos,
            @Nullable String sinValor) {}

    /** El metodo cuyo tipo de retorno describe el resolutor. Se lee por reflexion, no se llama. */
    RespuestaPaginada<RecursoDeEjemplo> ejemplo() {
        throw new UnsupportedOperationException("Solo se lee su tipo de retorno");
    }

    @Test
    @DisplayName("el arbol de claves coincide, campo a campo y nivel a nivel")
    void loDescritoYLoEmitidoCoinciden() throws Exception {
        Object descrito = FormaDeLaRespuesta.de(getClass().getDeclaredMethod("ejemplo"));

        JsonMapper mapper =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();
        Object emitido =
                clavesDe(mapper.readValue(mapper.writeValueAsString(unaRespuesta()), Object.class));

        assertThat(emitido)
                .as(
                        "lo que Jackson emite y lo que «FormaDeLaRespuesta» dice de ese mismo tipo"
                                + " tienen que tener las mismas claves en los mismos sitios")
                .isEqualTo(clavesDe(descrito));
    }

    @Test
    @DisplayName("los cuatro objetos de valor salen como cadena, no como objeto")
    void losObjetosDeValorSalenComoCadena() {
        JsonMapper mapper =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();

        // La comprobacion de arriba compara claves, y una cadena y un objeto vacio
        // tienen las mismas: ninguna. Esta mira el JSON tal cual.
        assertThat(mapper.writeValueAsString(Dinero.de("1842.60"))).isEqualTo("\"1842.60\"");
        assertThat(mapper.writeValueAsString(Alicuota.de("0.60"))).isEqualTo("\"0.60\"");
        assertThat(mapper.writeValueAsString(Porcentaje.de("50.00"))).isEqualTo("\"50.00\"");
        assertThat(mapper.writeValueAsString(AreaM2.de("120.50"))).isEqualTo("\"120.50\"");
    }

    // ------------------------------------------------------------------

    private static RespuestaPaginada<RecursoDeEjemplo> unaRespuesta() {
        RecursoDeEjemplo recurso =
                new RecursoDeEjemplo(
                        1L,
                        "P-001",
                        EstadoDeEjemplo.VIGENTE,
                        LocalDate.of(2026, 8, 13),
                        Instant.parse("2026-08-13T09:00:00Z"),
                        Dinero.de("1842.60"),
                        Alicuota.de("0.60"),
                        Porcentaje.de("50.00"),
                        AreaM2.de("120.50"),
                        new ImporteActualizado(Dinero.de("10.00"), LocalDate.of(2026, 8, 13)),
                        List.of(
                                new HijoDeEjemplo(
                                        "Piso 1", AreaM2.de("60.00"), LocalDate.of(2026, 1, 1))),
                        null);
        return new RespuestaPaginada<>(List.of(recurso), 0, 1, 1L, 1, false);
    }

    /**
     * El arbol de claves de un valor, sin sus datos: objetos con sus campos, listas con su primer
     * elemento y cualquier otra cosa como la cadena vacia.
     *
     * <p>Se reduce a claves porque lo que se compara son dos idiomas: el resolutor dice «texto» y
     * Jackson emite «1842.60». Lo que tiene que coincidir es <b>que campos hay y donde</b>.
     */
    private static Object clavesDe(@Nullable Object valor) {
        if (valor instanceof Map<?, ?> objeto) {
            Map<String, Object> claves = new LinkedHashMap<>();
            for (String clave :
                    new TreeSet<>(objeto.keySet().stream().map(String::valueOf).toList())) {
                claves.put(clave, clavesDe(objeto.get(clave)));
            }
            return claves;
        }
        if (valor instanceof List<?> lista) {
            List<Object> elementos = new ArrayList<>();
            if (!lista.isEmpty()) {
                elementos.add(clavesDe(lista.get(0)));
            }
            return elementos;
        }
        return "";
    }
}
