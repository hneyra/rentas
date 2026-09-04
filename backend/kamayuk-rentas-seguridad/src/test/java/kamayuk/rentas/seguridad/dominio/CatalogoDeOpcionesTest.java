package kamayuk.rentas.seguridad.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RF-122 — Catalogo de opciones")
class CatalogoDeOpcionesTest {

    @Test
    @DisplayName("lee el catalogo real, con sus 134 opciones")
    void leeElCatalogoReal() {
        List<CatalogoDeOpciones.Opcion> opciones = CatalogoDeOpciones.leer();

        // Si el analisis devolviera vacio o a medias, la siembra crearia menos
        // accesos de la cuenta y nadie lo notaria hasta que alguien no pudiera dar
        // permiso sobre una pantalla concreta.
        assertThat(opciones).as("las 134 opciones de los 12 modulos (NEG-03)").hasSize(134);
        assertThat(opciones.stream().map(CatalogoDeOpciones.Opcion::codigo).distinct().toList())
                .as("el id de cada opcion es unico: es la clave de acceso.codigo")
                .hasSize(134);
        assertThat(
                        opciones.stream()
                                .map(CatalogoDeOpciones.Opcion::moduloCodigo)
                                .distinct()
                                .toList())
                .hasSize(12);
        assertThat(opciones)
                .anySatisfy(
                        o -> {
                            assertThat(o.codigo()).isEqualTo("calles");
                            assertThat(o.moduloCodigo()).isEqualTo("CATASTRO");
                            assertThat(o.nombre()).isEqualTo("Mantenimiento de vías y calles");
                        });
    }

    @Test
    @DisplayName("una opcion nueva en el catalogo aparece sola, sin tocar ninguna otra lista")
    void unaOpcionNuevaApareceSola() {
        // Es la promesa del manual, verificada sobre el analizador: se agrega una
        // fila al catalogo y la opcion sale. No hay segunda lista que mantener.
        String catalogo =
                """
                ## Catastro

                | id | Opción | Bloque | Endpoint |
                |---|---|---|---|
                | `calles` | Mantenimiento de vías | Registro | `GET /api/v1/catastro/vias` |
                | `opcion_nueva` | Pantalla recien agregada | Consultas | `GET /api/v1/nueva` |
                """;

        assertThat(CatalogoDeOpciones.analizar(catalogo))
                .extracting(CatalogoDeOpciones.Opcion::codigo)
                .containsExactly("calles", "opcion_nueva");
    }

    @Test
    @DisplayName("la tabla resumen del encabezado no se confunde con opciones")
    void laTablaResumenNoSeConfunde() {
        // La primera tabla del documento lista los modulos y sus totales, con la
        // misma forma de fila. Si se colara, se sembrarian accesos inexistentes.
        String catalogo =
                """
                | Módulo | Manual | Contexto | Opciones |
                |---|---|---|---|
                | Catastro | cap. 2 | `catastro` | 12 |

                ## Catastro

                | `calles` | Mantenimiento de vías | Registro | `GET /api/v1/catastro/vias` |
                """;

        assertThat(CatalogoDeOpciones.analizar(catalogo))
                .extracting(CatalogoDeOpciones.Opcion::codigo)
                .containsExactly("calles");
    }

    @Test
    @DisplayName("el codigo del modulo se genera del nombre, sin tildes ni puntuacion")
    void elCodigoDelModuloSeGenera() {
        assertThat(CatalogoDeOpciones.codigoDe("Rentas · Registro")).isEqualTo("RENTAS_REGISTRO");
        assertThat(CatalogoDeOpciones.codigoDe("Fiscalización")).isEqualTo("FISCALIZACION");
        assertThat(CatalogoDeOpciones.codigoDe("Autorizaciones y licencias"))
                .isEqualTo("AUTORIZACIONES_Y_LICENCIAS");
        assertThat(CatalogoDeOpciones.codigoDe("Infracciones administrativas"))
                .as("el codigo cabe en varchar(30)")
                .hasSizeLessThanOrEqualTo(30);
    }
}
