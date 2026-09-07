package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que el adaptador de {@code caja} PIDE, contra lo que el contrato declara (#27 AC-4).
 *
 * <p>Es la guarda equivalente a {@code PeticionesACatastroTest}, en la frontera de al lado. Sin
 * ella, el contrato enumera sus parametros a mano y el adaptador construye la URL a mano: los dos
 * pueden discrepar sin que nada se ponga rojo, y el CI del proveedor comprobaria un contrato que su
 * unico cliente no cumple.
 *
 * <p><b>Con el estado anterior a #27 esta prueba sale roja nombrando los dos</b>: {@code
 * AvanceDeCajaHttp} mandaba {@code ?dia=&aLaFecha=} y {@code caja} admite {@code desde} y {@code
 * hasta}. Medido contra las dos aplicaciones levantadas, eso es un {@code 422 «Parametros
 * desconocidos: 'aLaFecha', 'dia'»} y el panel del modulo en 500.
 *
 * <p>Vive en el paquete del adaptador por lo mismo que su hermana de {@code catastro}: {@code
 * enviar(...)} es de paquete, y se prefiere una prueba dentro a abrir un metodo de produccion para
 * poder probarlo.
 */
@DisplayName("El adaptador de caja pide exactamente los parametros que el contrato declara")
class PeticionesACajaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 6);

    /**
     * Aqui se mide lo que SALE, no lo que vuelve — la respuesta la mide {@code LecturaDeCajaTest}.
     *
     * <p>Por eso las llamadas van envueltas en {@code catchThrowable}: con un cuerpo vacio el
     * adaptador falla al leer, y lo que interesa ya quedo anotado en el espia. El resultado se
     * descarta a proposito; comprobarlo aqui seria medir dos cosas en una prueba.
     */
    @Test
    @DisplayName("el avance del dia: `desde` y `hasta`, que es lo que `caja` lee")
    void elAvanceMandaLoDeclarado() {
        CajaQueNoContesta espia = cajaQueContesta();

        catchThrowable(() -> new AvanceDeCajaHttp(espia).delDia(DIA, DIA));

        assertThat(mandados(espia))
                .as(
                        "hasta #27 mandaba «dia» y «aLaFecha», y `caja` no admite ninguno de los"
                                + " dos: 422 «Parametros desconocidos», y el panel del modulo en"
                                + " 500 con las dos aplicaciones sanas")
                .isEqualTo(declaradosPara("GET /recaudacion/avance"));
    }

    @Test
    @DisplayName("y el rango es el de UN dia: los dos extremos, la fecha que se pidio")
    void elRangoEsElDelDia() {
        CajaQueNoContesta espia = cajaQueContesta();

        catchThrowable(() -> new AvanceDeCajaHttp(espia).delDia(DIA, DIA));

        // Que los nombres cuadren no basta: `desde` y `hasta` con fechas distintas sumarian
        // varios dias y la cifra saldria igual de plausible. La suma de un mes publicada como
        // «lo que la ventanilla lleva cobrado hoy» no se distingue de la buena.
        assertThat(espia.rutas)
                .containsExactly("/recaudacion/avance?desde=2026-09-06&hasta=2026-09-06");
    }

    @Test
    @DisplayName("lo recaudado por una tasa: `desde` y `hasta` tambien")
    void laRecaudacionDeUnaTasaMandaLoDeclarado() {
        CajaQueNoContesta espia = cajaQueContesta();

        catchThrowable(
                () -> new CobrosDeTasasHttp(espia).recaudado("TUPA-01", DIA, DIA.plusDays(30)));

        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /tasas/{codigo}/recaudacion"));
    }

    @Test
    @DisplayName("y el espia de verdad captura: sin peticiones, esta prueba no diria nada")
    void elEspiaCaptura() {
        CajaQueNoContesta espia = cajaQueContesta();

        catchThrowable(() -> new AvanceDeCajaHttp(espia).delDia(DIA, DIA));

        assertThat(espia.rutas)
                .as(
                        "una prueba que no captura ninguna URL compara el conjunto vacio contra el"
                                + " conjunto vacio y pasa en verde sin haber mirado nada")
                .hasSize(1);
    }

    @Test
    @DisplayName("las tres lecturas sin parametros no mandan ninguno, y el contrato lo dice")
    void lasQueNoLlevanParametrosNoLosMandan() {
        CajaQueNoContesta espia = cajaQueContesta();

        catchThrowable(() -> new RecibosDeTramiteHttp(espia).porNumeroImpreso("001-0000123"));
        catchThrowable(() -> new CobrosDeTasasHttp(espia).acreditar("001-0000123", "TUPA-01"));

        assertThat(mandados(espia)).isEmpty();
        assertThat(declaradosPara("GET /recibos/{numero}")).isEmpty();
        assertThat(declaradosPara("GET /tasas/{codigo}/cobros/{numero}")).isEmpty();
    }

    // ------------------------------------------------------------------

    /** Una caja que contesta un cuerpo vacio: aqui no se mira la respuesta. */
    private static CajaQueNoContesta cajaQueContesta() {
        JsonMapper json = new JsonMapper();
        return new CajaQueNoContesta(ruta -> json.createObjectNode());
    }

    /** Los nombres de parametro de todas las URL que el adaptador construyo. */
    private static Set<String> mandados(CajaQueNoContesta espia) {
        Set<String> nombres = new TreeSet<>();
        for (String ruta : espia.rutas) {
            int interrogacion = ruta.indexOf('?');
            if (interrogacion < 0) {
                continue;
            }
            for (String par : ruta.substring(interrogacion + 1).split("&")) {
                if (!par.isBlank()) {
                    nombres.add(par.split("=", 2)[0]);
                }
            }
        }
        return nombres;
    }

    /** Lo que el contrato comprometido declara para esa operacion. */
    private static Set<String> declaradosPara(String operacion) {
        ContratoDelConsumidor contrato = new ContratoQueConsumeDeCaja().contrato();
        ContratoDelConsumidor.OperacionEsperada esperada = contrato.operaciones().get(operacion);
        assertThat(esperada).as("el contrato no declara «%s»", operacion).isNotNull();
        return new TreeSet<>(esperada.parametros());
    }
}
