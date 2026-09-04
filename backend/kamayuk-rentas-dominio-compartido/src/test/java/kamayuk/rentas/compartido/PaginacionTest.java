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
