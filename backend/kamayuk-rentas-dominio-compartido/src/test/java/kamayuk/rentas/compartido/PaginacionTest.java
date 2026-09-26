package kamayuk.rentas.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Paginacion y pagina")
class PaginacionTest {

    @Test
    @DisplayName("la pagina se cuenta desde 0 y el desplazamiento sale solo")
    void laPaginaSeCuentaDesdeCero() {
        assertThat(Paginacion.de(0, 20, "codigo").desplazamiento()).isZero();
        assertThat(Paginacion.de(3, 20, "codigo").desplazamiento()).isEqualTo(60);
    }

    /**
     * #456 — El desplazamiento cabe en un {@code int}, o la paginacion se rechaza.
     *
     * <p>Hasta #456 se calculaba {@code pagina * tamano} en {@code int} sin tope: 4 294 968 x 500
     * desbordaba a un {@code OFFSET} negativo —500 de PostgreSQL— y 8 589 935 x 500 daba la vuelta
     * entera a 204, un 200 con las filas 205 a 704 rotuladas como otra pagina. El segundo es el que
     * distingue: mirar solo {@code desplazamiento() < 0} lo dejaria pasar.
     */
    @Test
    @DisplayName(
            "#456 — una pagina cuyo desplazamiento no cabe en un int se rechaza, no se enrolla")
    void elDesplazamientoNoSeDesborda() {
        assertThat(Paginacion.de(4_294_967, 500, "id").desplazamiento())
                .as("el mayor que cabe: 2 147 483 500")
                .isEqualTo(2_147_483_500);
        assertThatThrownBy(() -> Paginacion.de(4_294_968, 500, "id"))
                .as("2 147 484 000 desbordaba a un OFFSET negativo")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4294968");
        assertThatThrownBy(() -> Paginacion.de(8_589_935, 500, "id"))
                .as("4 294 967 500 daba la vuelta a 204: otra pagina, en silencio")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un listado sin orden no es reproducible, y se rechaza")
    void unListadoSinOrdenSeRechaza() {
        // Sin ORDER BY el motor no garantiza ningun orden entre consultas: dos
        // paginas consecutivas pueden repetir una fila y omitir otra, y el usuario
        // ve un padron al que le faltan contribuyentes sin ningun error de por medio.
        assertThatThrownBy(() -> Paginacion.de(0, 20, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Paginacion(0, 20, "codigo", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("hay un tope de tamano, para que nadie pida el padron entero")
    void hayUnTopeDeTamano() {
        assertThat(Paginacion.de(0, Paginacion.TAMANO_MAXIMO, "id")).isNotNull();
        assertThatThrownBy(() -> Paginacion.de(0, Paginacion.TAMANO_MAXIMO + 1, "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Paginacion.de(0, 0, "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Paginacion.de(-1, 10, "id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el total de paginas se deduce del total de elementos")
    void elTotalDePaginasSeDeduce() {
        Paginacion peticion = Paginacion.de(0, 20, "id");

        assertThat(Pagina.de(List.of("a"), peticion, 41).totalPaginas()).isEqualTo(3);
        assertThat(Pagina.de(List.of("a"), peticion, 40).totalPaginas()).isEqualTo(2);
        assertThat(Pagina.de(List.of(), peticion, 0).totalPaginas()).isZero();
        assertThat(Pagina.vacia(peticion).estaVacia()).isTrue();
    }

    @Test
    @DisplayName("hayMas dice si queda pagina siguiente sin ir a buscarla")
    void hayMasDiceSiQuedaPaginaSiguiente() {
        assertThat(Pagina.de(List.of("a", "b"), Paginacion.de(0, 2, "id"), 5).hayMas()).isTrue();
        assertThat(Pagina.de(List.of("e"), Paginacion.de(2, 2, "id"), 5).hayMas()).isFalse();
    }

    @Test
    @DisplayName("el contenido de una pagina no se puede modificar por fuera")
    void elContenidoNoSeModificaPorFuera() {
        List<String> original = new java.util.ArrayList<>(List.of("a"));
        Pagina<String> pagina = Pagina.de(original, Paginacion.de(0, 10, "id"), 1);

        original.add("b");

        assertThat(pagina.contenido()).containsExactly("a");
    }

    @Test
    @DisplayName("mapear traduce el contenido y conserva el total")
    void mapearTraduceYConservaElTotal() {
        Pagina<Integer> longitudes =
                Pagina.de(List.of("uno", "cuatro"), Paginacion.de(1, 2, "id"), 9)
                        .mapear(String::length);

        assertThat(longitudes.contenido()).containsExactly(3, 6);
        assertThat(longitudes.totalElementos()).isEqualTo(9);
        assertThat(longitudes.pagina()).isEqualTo(1);
    }
}
