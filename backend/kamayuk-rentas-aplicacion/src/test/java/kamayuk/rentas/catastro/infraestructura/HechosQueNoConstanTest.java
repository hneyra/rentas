package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Paginacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que {@code catastro} contesta cuando el hecho del territorio NO CONSTA (#9).
 *
 * <h2>Por que hace falta distinguirlo, medido y no razonado</h2>
 *
 * <p>Las cuatro lecturas de C-5 se disenaron para que la ausencia viajara <b>como campo</b> —{@code
 * enElPadron: false}, {@code tieneCuota: false}—, asi que ahi un 4xx es una averia y {@code pedir}
 * hace bien en llamarla {@code CatastroInalcanzable}. Las de #4 y #5 no: {@code catastro} contesta
 * <b>422</b> cuando el predio esta y no tiene poligono, y <b>404</b> cuando ningun plan vigente lo
 * cubre, y lo hace a proposito —un 200 con la zona nula seria indistinguible de «este predio esta
 * en zona nula», que no admite ningun giro—.
 *
 * <p>Colapsar las dos en «catastro no responde» borraria de este lado justo la distincion que el
 * proveedor construyo: mandaria a mirar un despliegue cuando lo que falta es cargar un plano o
 * aprobar una ordenanza. Es la forma del defecto de C-1, con el proveedor haciendo lo correcto y el
 * consumidor deshaciendolo.
 */
@DisplayName("Un hecho que no consta no se lee como una averia de catastro")
class HechosQueNoConstanTest {

    private static final LocalDate AL_30_DE_JUNIO = LocalDate.of(2026, 6, 30);

    /** Lo que emite {@code ManejadorDeErrores} de catastro: RFC 7807 con su campo `codigo`. */
    private static String problema(String codigo, String detalle) {
        return "{\"type\":\"about:blank\",\"status\":422,\"codigo\":\""
                + codigo
                + "\",\"detail\":\""
                + detalle
                + "\"}";
    }

    @Test
    @DisplayName("un predio sin poligono: 422 con «VALIDACION», y NO cero zonas de riesgo")
    void elPredioSinPoligonoNoDevuelveCeroZonas() {
        CatastroQueNoContesta doble =
                CatastroQueNoContesta.queContesta(
                        422,
                        problema(
                                "VALIDACION",
                                "El predio 11 esta en el padron y no tiene poligono levantado"));

        assertThatThrownBy(() -> new RiesgoYItseDelPredioHttp(doble).riesgoDe(11L))
                .as(
                        "«cero zonas» se leeria como «no cae en ninguna» y acabaria autorizando lo"
                                + " que no debe")
                .isInstanceOf(ClienteHttpDeCatastro.NoConstaEnCatastro.class)
                .hasMessageContaining("VALIDACION")
                .hasMessageContaining("no tiene poligono");
    }

    @Test
    @DisplayName("y el codigo se lee del dato, no del mensaje en castellano")
    void elCodigoViajaComoDato() {
        CatastroQueNoContesta doble =
                CatastroQueNoContesta.queContesta(
                        404, problema("NO_ENCONTRADO", "Ningun plan vigente cubre ese predio"));

        Throwable lanzada =
                catchThrowable(
                        () -> new ZonificacionDelPredioHttp(doble).zonaDe(11L, AL_30_DE_JUNIO));

        assertThat(lanzada).isInstanceOf(ClienteHttpDeCatastro.NoConstaEnCatastro.class);
        assertThat(((ClienteHttpDeCatastro.NoConstaEnCatastro) lanzada).codigo())
                .as(
                        "quien atienda decide con esto que hacer —cargar el plano o aprobar la"
                                + " zonificacion—, y un texto en castellano se reescribe en cuanto"
                                + " alguien lo lee en voz alta")
                .isEqualTo("NO_ENCONTRADO");
    }

    @Test
    @DisplayName("EL CONTRASTE: un 4xx SIN codigo sigue siendo una averia, no un hecho")
    void unaRespuestaSinCodigoSigueSiendoUnaAveria() {
        // El HTML de un proxy, o una ruta que no existe: ahi no hay ningun hecho que leer.
        CatastroQueNoContesta doble =
                CatastroQueNoContesta.queContesta(404, "<html><body>404 Not Found</body></html>");

        assertThatThrownBy(() -> new FrentesDelPredioHttp(doble).delPredio(11L))
                .as(
                        "sin este contraste, «no consta» se tragaria tambien las averias y esta"
                                + " frontera dejaria de avisar de que catastro no esta")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class);
    }

    @Test
    @DisplayName("los hallazgos de UN predio no se inventan: se dice que ruta los serviria")
    void losHallazgosDeUnPredioNoSeInventan() {
        CatastroQueNoContesta doble = new CatastroQueNoContesta(ruta -> null);

        assertThatThrownBy(() -> new HallazgosDelPredioHttp(doble).de(11L))
                .as(
                        "devolver vacio diria que ese predio no tiene hallazgos; recorrer la"
                                + " campania y filtrar aqui devolveria los que cupieron en la"
                                + " primera pagina")
                .isInstanceOf(ClienteHttpDeCatastro.SinRutaEnCatastro.class)
                .hasMessageContaining("/fiscalizacion/predios/{predioId}/hallazgos");

        assertThat(doble.rutas)
                .as("y no se manda ninguna peticion: la operacion no existe")
                .isEmpty();
    }

    @Test
    @DisplayName("y la pagina de hallazgos de una campania SI sale, con su total")
    void laPaginaDeLaCampaniaSiSale() {
        // El contraste del contraste: si `de(...)` lanzara siempre y `deLaCampania` tambien,
        // este puerto no serviria para nada y las pruebas de arriba pasarian igual.
        CatastroQueNoContesta doble =
                new CatastroQueNoContesta(
                        ruta -> {
                            var json = new tools.jackson.databind.json.JsonMapper();
                            var cuerpo = json.createObjectNode();
                            cuerpo.putArray("contenido");
                            cuerpo.put("totalElementos", 0);
                            return cuerpo;
                        });

        assertThat(new HallazgosDelPredioHttp(doble).deLaCampania(7L, Paginacion.de(0, 20, "id")))
                .isNotNull();
        assertThat(doble.rutas).hasSize(1);
    }
}
