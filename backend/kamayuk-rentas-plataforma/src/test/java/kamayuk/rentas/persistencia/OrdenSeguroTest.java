package kamayuk.rentas.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.compartido.Paginacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Orden seguro")
class OrdenSeguroTest {

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("codigo", "nombre_razon_social", "fecha_registro");

    @Test
    @DisplayName("arma la clausula con la columna y el sentido")
    void armaLaClausula() {
        assertThat(ORDEN.clausula(Paginacion.de(0, 10, "codigo"))).isEqualTo("ORDER BY codigo ASC");
        assertThat(
                        ORDEN.clausula(
                                new Paginacion(0, 10, "codigo", Paginacion.Direccion.DESCENDENTE)))
                .isEqualTo("ORDER BY codigo DESC");
    }

    @Test
    @DisplayName("el cliente ordena en camelCase y la tabla recibe snake_case")
    void traduceCamelCaseASnakeCase() {
        assertThat(ORDEN.clausula(Paginacion.de(0, 10, "nombreRazonSocial")))
                .as("quien consume la API no tiene por que saber como se llama la columna")
                .isEqualTo("ORDER BY nombre_razon_social ASC");
        assertThat(ORDEN.clausula(Paginacion.de(0, 10, "nombre_razon_social")))
                .isEqualTo("ORDER BY nombre_razon_social ASC");
    }

    /**
     * La razon de ser de esta clase. {@code ORDER BY} no admite parametros de enlace, asi que el
     * texto del cliente se concatenaria a la consulta; con una subconsulta se pueden extraer datos
     * fila a fila sin necesidad de ver ningun mensaje de error.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "(SELECT nombre FROM municipalidad LIMIT 1)",
                "codigo; DROP TABLE via_de_prueba",
                "codigo, (SELECT 1)",
                "1",
                "codigo--",
                "otra_columna"
            })
    @DisplayName("lo que no esta en la lista blanca no llega a la consulta")
    void loQueNoEstaEnLaListaBlancaNoLlegaALaConsulta(String campo) {
        assertThatThrownBy(() -> ORDEN.clausula(Paginacion.de(0, 10, campo)))
                .isInstanceOf(OrdenSeguro.OrdenNoAdmitido.class)
                .hasMessageContaining(campo);
    }

    @Test
    @DisplayName("el error dice que campos si se admiten")
    void elErrorDiceQueCamposSeAdmiten() {
        assertThatThrownBy(() -> ORDEN.clausula(Paginacion.de(0, 10, "inventado")))
                .hasMessageContaining("codigo")
                .hasMessageContaining("fechaRegistro");
    }

    /**
     * El desempate (#543): sin el, un listado cuya columna de orden empata no tiene orden.
     *
     * <p>Medido sobre los doce modulos del sistema, que tienen todos {@code orden = 0}: el orden
     * relativo <b>cambia con el tamano de pagina</b> —el plan de ejecucion no es el mismo—, asi que
     * dos paginas consecutivas pueden repetir una fila y omitir otra.
     */
    @Test
    @DisplayName("el desempate declarado se anade a la clausula, y no se repite")
    void elDesempateSeAnadeYNoSeRepite() {
        OrdenSeguro conDesempate = OrdenSeguro.sobre("codigo", "orden", "id").desempatandoPor("id");

        assertThat(conDesempate.clausula(Paginacion.de(0, 10, "orden")))
                .as("sin esto, doce filas con el mismo «orden» salen como el plan quiera")
                .isEqualTo("ORDER BY orden ASC, id ASC");
        assertThat(
                        conDesempate.clausula(
                                new Paginacion(0, 10, "codigo", Paginacion.Direccion.DESCENDENTE)))
                .as("el desempate va siempre ASC: lo que hace falta es que el orden sea total")
                .isEqualTo("ORDER BY codigo DESC, id ASC");
        assertThat(conDesempate.clausula(Paginacion.de(0, 10, "id")))
                .as("ordenar por la propia columna de desempate no la repite")
                .isEqualTo("ORDER BY id ASC");
    }

    /**
     * Los nulos al final (#608): en PostgreSQL {@code ASC} los pone al final y {@code DESC} los
     * pone <b>delante</b>, asi que «de mayor a menor» sobre una columna que admite nulos abre por
     * las filas cuyo valor no se puede calcular.
     */
    @Test
    @DisplayName("la columna declarada anulable emite NULLS LAST en las dos direcciones")
    void laColumnaAnulableEmiteNullsLast() {
        OrdenSeguro conNulos =
                OrdenSeguro.sobre("codigo", "sector_codigo").conNulosAlFinal("sector_codigo");

        assertThat(conNulos.clausula(Paginacion.de(0, 10, "sectorCodigo")))
                .isEqualTo("ORDER BY sector_codigo ASC NULLS LAST");
        assertThat(
                        conNulos.clausula(
                                new Paginacion(
                                        0, 10, "sectorCodigo", Paginacion.Direccion.DESCENDENTE)))
                .as("sin esto, «de mayor a menor» abre por las filas sin valor")
                .isEqualTo("ORDER BY sector_codigo DESC NULLS LAST");
    }

    @Test
    @DisplayName("y el desempate va detras del NULLS LAST, no en medio")
    void elDesempateVaDetrasDelNullsLast() {
        OrdenSeguro conNulos =
                OrdenSeguro.sobre("codigo", "diferencia_de_area")
                        .conNulosAlFinal("diferencia_de_area")
                        .desempatandoPor("id");

        assertThat(
                        conNulos.clausula(
                                new Paginacion(
                                        0,
                                        10,
                                        "diferenciaDeArea",
                                        Paginacion.Direccion.DESCENDENTE)))
                .isEqualTo("ORDER BY diferencia_de_area DESC NULLS LAST, id ASC");
    }

    /**
     * El contraste, y es lo que impide que esta ampliacion cambie el orden de los otros nueve
     * listados que usan esta clase: una columna que NO se declara anulable produce exactamente la
     * clausula de siempre.
     */
    @Test
    @DisplayName("la columna que no se declara anulable produce la clausula de siempre")
    void laColumnaSinDeclararNoCambia() {
        OrdenSeguro conNulos =
                OrdenSeguro.sobre("codigo", "sector_codigo").conNulosAlFinal("sector_codigo");

        assertThat(conNulos.clausula(Paginacion.de(0, 10, "codigo")))
                .as("el resto del listado tiene que seguir ordenando igual que antes de #608")
                .isEqualTo("ORDER BY codigo ASC");
        assertThat(ORDEN.clausula(Paginacion.de(0, 10, "codigo")))
                .as("y una lista blanca que no declara ninguna anulable, tampoco")
                .isEqualTo("ORDER BY codigo ASC");
    }

    @Test
    @DisplayName("la declaracion sobrevive a publicandoComo y a desempatandoPor")
    void laDeclaracionSobreviveALosDemasConstructores() {
        OrdenSeguro orden =
                OrdenSeguro.sobre("codigo", "sector_codigo")
                        .conNulosAlFinal("sector_codigo")
                        .publicandoComo("sector", "sector_codigo")
                        .desempatandoPor("id");

        assertThat(orden.clausula(Paginacion.de(0, 10, "sector")))
                .as(
                        "cada constructor devuelve un objeto nuevo: perder la declaracion por el camino"
                                + " no se veria en ninguna otra parte")
                .isEqualTo("ORDER BY sector_codigo ASC NULLS LAST, id ASC");
    }

    @Test
    @DisplayName("no se puede declarar anulable una columna que no esta en la lista blanca")
    void noSePuedeDeclararAnulableLoQueNoEstaEnLaLista() {
        assertThatThrownBy(() -> ORDEN.conNulosAlFinal("inventada"))
                .as("declarar anulable una columna que no existe deja la declaracion muerta")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventada");
    }

    @Test
    @DisplayName("y el desempate tampoco admite nada que no sea un nombre de columna")
    void elDesempateNoAdmiteCualquierCosa() {
        assertThatThrownBy(() -> ORDEN.desempatandoPor("id DESC, (SELECT 1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una lista blanca no admite nada que no sea un nombre de columna")
    void laListaBlancaNoAdmiteCualquierCosa() {
        assertThatThrownBy(() -> OrdenSeguro.sobre("codigo ASC"))
                .as("si la propia lista blanca admitiera SQL, no serviria de nada")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrdenSeguro.sobre("(SELECT 1)"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
