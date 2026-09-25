package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import kamayuk.rentas.catastro.ContratoDelLectorDeValoresUnitarios;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * #350 — el adaptador de produccion del cuadro cumple el contrato del puerto.
 *
 * <p>Es la mitad que no lo cumplia: {@code catastro} contesta <b>404 con su {@code codigo}</b> a un
 * ejercicio sin conjunto sellado y el adaptador lo dejaba salir como {@code CatastroInalcanzable},
 * un 500 al llegar al borde. Hasta #350 ninguna prueba le daba un 404: solo un 200, o un cuerpo que
 * no era un array.
 *
 * <p>Los dos casos propios de abajo son la <b>siembra que distingue</b>. Un 404 sin codigo —el HTML
 * de un proxy, una ruta que no existe— no es «ese ejercicio no tiene cuadro», es que no se pudo
 * preguntar; y un codigo con un estado de averia tampoco. Si la traduccion se tragara cualquiera de
 * los dos, una caida de verdad se leeria como «falta sellar el cuadro» y mandaria a quien opera a
 * publicar una cifra que ya esta publicada.
 */
@DisplayName("#350 — ValoresUnitariosHttp cumple el contrato del puerto")
class ValoresUnitariosHttpCumpleElContratoTest extends ContratoDelLectorDeValoresUnitarios {

    private static final JsonMapper JSON = new JsonMapper();

    /** Lo que emite el `ManejadorDeErrores` de catastro: RFC 7807 con su campo `codigo`. */
    private static String problema(int estado, String codigo, String detalle) {
        return "{\"type\":\"about:blank\",\"status\":"
                + estado
                + ",\"codigo\":\""
                + codigo
                + "\",\"detail\":\""
                + detalle
                + "\"}";
    }

    @Override
    protected LectorDeValoresUnitarios sinCuadroPara(Ejercicio ejercicio) {
        return new ValoresUnitariosHttp(
                CatastroQueNoContesta.queContesta(
                        404,
                        problema(
                                404,
                                "NO_ENCONTRADO",
                                "El ejercicio " + ejercicio + " no tiene conjunto sellado")));
    }

    @Override
    protected LectorDeValoresUnitarios conUnaCelda(
            Ejercicio ejercicio, String partida, char categoria, String valorM2) {
        ArrayNode cuadro = JSON.createArrayNode();
        cuadro.addObject()
                .put("partida", partida)
                .put("categoria", String.valueOf(categoria))
                .put("anioConstruccionDesde", 1990)
                .putNull("anioConstruccionHasta")
                .put("valorM2", valorM2);
        return new ValoresUnitariosHttp(new CatastroQueNoContesta(ruta -> cuadro));
    }

    @Test
    @DisplayName("un 404 SIN codigo —el HTML de un proxy— sigue siendo una averia")
    void unCuatrocientosCuatroSinCodigoEsUnaAveria() {
        ValoresUnitariosHttp adaptador =
                new ValoresUnitariosHttp(
                        CatastroQueNoContesta.queContesta(
                                404, "<html><body>404 Not Found</body></html>"));

        Throwable fallo = catchThrowable(() -> adaptador.valoresUnitariosVigentesEn(SIN_SELLAR));

        assertThat(fallo)
                .as(
                        "sin el codigo de catastro no hay respuesta de catastro: no se sabe si el"
                                + " ejercicio tiene cuadro, y decir que no lo tiene seria inventarlo")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("un codigo con un estado de averia —500 ERROR_INTERNO— tampoco es «sin sellar»")
    void unCodigoConEstadoDeAveriaNoEsSinSellar() {
        ValoresUnitariosHttp adaptador =
                new ValoresUnitariosHttp(
                        CatastroQueNoContesta.queContesta(
                                500, problema(500, "ERROR_INTERNO", "Incidencia 4f2a")));

        Throwable fallo = catchThrowable(() -> adaptador.valoresUnitariosVigentesEn(SIN_SELLAR));

        assertThat(fallo)
                .as("lo que dice «ese ejercicio no tiene cuadro» es el 404, no el codigo solo")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .isNotInstanceOf(LectorDeParametros.EjercicioSinSellar.class)
                .hasMessageContaining("500");
    }
}
