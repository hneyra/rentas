package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCatastro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La otra mitad de la ida y vuelta: lo que el adaptador PIDE, contra lo que el contrato declara
 * (C-1).
 *
 * <p>{@code LecturaDeCatastroTest} ata los campos que este backend <b>lee</b> de la respuesta a los
 * que el contrato declara. Nada ataba los que <b>manda</b>: el contrato enumera sus parametros a
 * mano y el adaptador construye la URL a mano, asi que los dos podian discrepar sin que nada se
 * pusiera rojo — y el CI del proveedor comprobaria un contrato que su unico cliente no cumple.
 *
 * <p>Es exactamente el defecto que C-1 cerro tres veces: {@code ?aLaFecha=} contra {@code fecha},
 * {@code ?ejercicio=} contra {@code anio}, y {@code soloPredio}/{@code exceptoPredio} declarados y
 * no leidos. Los tres se descartan en silencio con 200 delante, y el sintoma —la grilla del padron
 * entero, la ficha de hoy en vez de la de marzo— no se parece a la causa.
 *
 * <p>Vive en el paquete del adaptador por lo mismo que su hermana: {@code pedir(...)} es de
 * paquete, y se prefiere una prueba dentro a abrir un metodo de produccion para poder probarlo.
 */
@DisplayName("El adaptador de catastro pide exactamente los parametros que el contrato declara")
class PeticionesACatastroTest {

    private static final LocalDate A_LA_FECHA = LocalDate.of(2026, 3, 15);

    @Test
    @DisplayName("la grilla de fichas: los diez, contando las dos formas de la acotacion")
    void laGrillaDeFichasMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        var adaptador = new FichasDelPadronHttp(espia);

        BusquedaDeFichas criterio =
                new BusquedaDeFichas(
                        "270101001",
                        "PEREZ",
                        "M-01",
                        "L-03",
                        "UNICA",
                        AcotacionPorPredio.ninguna());

        // Las dos formas de acotar, porque son dos parametros distintos y una sola peticion
        // solo puede llevar uno: `soloPredio` y `exceptoPredio` se excluyen (catastro los
        // rechaza juntos con 422, y con razon).
        adaptador.buscar(
                criterio.acotadaA(AcotacionPorPredio.soloEstos(List.of(11L, 12L))),
                A_LA_FECHA,
                Paginacion.de(3, 50, "codRefCatastral"));
        adaptador.buscar(
                criterio.acotadaA(AcotacionPorPredio.todosMenosEstos(List.of(13L))),
                A_LA_FECHA,
                Paginacion.de(0, 20, "codRefCatastral"));

        assertThat(mandados(espia))
                .as(
                        "el contrato declara lo que este backend le exige a catastro. Un parametro"
                                + " que el adaptador manda y el contrato no declara NO lo comprueba"
                                + " nadie: viaja en la URL y se descarta en silencio")
                .isEqualTo(declaradosPara("GET /catastro/fichas"));
    }

    @Test
    @DisplayName("y el espia de verdad captura: sin peticiones, esta prueba no diria nada")
    void elEspiaCaptura() {
        CatastroQueNoContesta espia = respuestaDe();
        new ValoresUnitariosHttp(espia).valoresUnitariosVigentesEn(new Ejercicio(2026));
        assertThat(espia.rutas)
                .as(
                        "una prueba que no captura ninguna URL compara el conjunto vacio contra el"
                                + " conjunto vacio y pasa en verde sin haber mirado nada")
                .hasSize(1);
    }

    @Test
    @DisplayName("el cuadro de valores unitarios: `ejercicio`, que es como catastro lo lee")
    void elCuadroMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new ValoresUnitariosHttp(espia).valoresUnitariosVigentesEn(new Ejercicio(2026));

        assertThat(mandados(espia))
                .as(
                        "hasta C-1 mandaba «ejercicio» contra un `@RequestParam int anio`"
                                + " OBLIGATORIO: la peticion no llegaba a 200, salia 400 y el"
                                + " cliente la traducia a «catastro no responde»")
                .isEqualTo(declaradosPara("GET /catastro/tablas/valores-unitarios"));
    }

    // ------------------------------------------------------------------

    /**
     * Un catastro de mentira que contesta lo minimo que cada adaptador sabe leer sin caerse.
     *
     * <p>Lo que se mide aqui es la PETICION, no lo que vuelve: de eso se ocupa {@code
     * LecturaDeCatastroTest}, que usa el mismo doble con una respuesta fabricada del contrato.
     */
    private static CatastroQueNoContesta respuestaDe() {
        ObjectMapper json = new ObjectMapper();
        return new CatastroQueNoContesta(
                ruta -> {
                    // El cuadro sellado sale como ARRAY y la grilla como sobre paginado
                    // (C-1, desajuste 6). Los dos, vacios: aqui se mide la peticion.
                    if (ruta.startsWith("/catastro/tablas/")) {
                        return json.createArrayNode();
                    }
                    ObjectNode sobre = json.createObjectNode();
                    sobre.putArray("contenido");
                    sobre.put("totalElementos", 0);
                    return sobre;
                });
    }

    /** Los nombres de parametro de todas las URL que el adaptador construyo. */
    private static Set<String> mandados(CatastroQueNoContesta espia) {
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
        ContratoDelConsumidor contrato = new ContratoQueConsumeDeCatastro().contrato();
        ContratoDelConsumidor.OperacionEsperada esperada = contrato.operaciones().get(operacion);
        assertThat(esperada).as("el contrato no declara «%s»", operacion).isNotNull();
        return new TreeSet<>(esperada.parametros());
    }
}
