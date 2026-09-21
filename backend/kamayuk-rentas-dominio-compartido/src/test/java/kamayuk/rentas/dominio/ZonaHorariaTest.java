package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #273, #188 — La zona del producto: que sea la del Peru, que sea de la IANA, que no se lea sola y
 * que la hora que se publica la lleve encima.
 *
 * <p>Ninguna prueba de esta clase llama a {@code Instant.now()} ni a {@code LocalDate.now()}: todos
 * los instantes estan escritos. La regla 6 no se relaja porque el sujeto sea una zona.
 */
@DisplayName("#273 — La zona horaria del producto")
class ZonaHorariaTest {

    /**
     * Las 20:00 del miercoles 4 de marzo de 2026 en el Peru.
     *
     * <p>Esta escrito como instante UTC y no derivado de la zona a proposito: una prueba que
     * compusiera la hora con {@code ZonaHoraria.DEL_PRODUCTO} estaria verificando la constante con
     * la constante. En UTC este instante <b>ya es del dia 5</b>, y esa discrepancia es justo el
     * defecto que #273 arreglo.
     */
    private static final Instant LAS_OCHO_DE_LA_NOCHE = Instant.parse("2026-03-05T01:00:00Z");

    @Nested
    @DisplayName("Que zona es")
    class QueZonaEs {

        @Test
        @DisplayName("es America/Lima, y se dice aqui porque en produccion se escribe una sola vez")
        void esLaDelPeru() {
            assertThat(ZonaHoraria.DEL_PRODUCTO.getId()).isEqualTo("America/Lima");
        }

        @Test
        @DisplayName("NO es un desfase fijo: un ZoneOffset perderia la historia de la zona")
        void noEsUnDesfaseFijo() {
            assertThat(ZonaHoraria.DEL_PRODUCTO)
                    .as(
                            "escribir -05:00 parece equivalente y deja de valer el dia que la zona"
                                    + " cambie (#224, punto 2 de la decision)")
                    .isNotInstanceOf(ZoneOffset.class);
        }

        @Test
        @DisplayName("y la diferencia se ve en 1990, cuando el Peru estuvo a -04:00")
        void enMilNovecientosNoventaLaDiferenciaSeVe() {
            // El Peru tuvo horario de verano del 1 de enero al 1 de abril de 1990. Este instante
            // cae dentro de esa ventana, y es uno en el que las dos lecturas NO coinciden: con la
            // zona de verdad ya es el 16; con un -05:00 cableado seguiria siendo el 15.
            Instant dentroDelHorarioDeVerano = Instant.parse("1990-01-16T04:30:00Z");

            assertThat(ZonaHoraria.diaDe(dentroDelHorarioDeVerano))
                    .as("America/Lima estaba a -04:00 ese dia: son las 00:30 del 16")
                    .isEqualTo(LocalDate.of(1990, 1, 16));
            assertThat(LocalDate.ofInstant(dentroDelHorarioDeVerano, ZoneOffset.of("-05:00")))
                    .as(
                            "y un desfase fijo lo habria fechado el 15: la diferencia no es"
                                    + " teorica, es un dia distinto en un documento")
                    .isEqualTo(LocalDate.of(1990, 1, 15));
        }
    }

    @Nested
    @DisplayName("diaDe: el dia en que ocurrio un instante")
    class ElDiaDeUnInstante {

        @Test
        @DisplayName("un hecho de las 20:00 es del dia que es en el Peru, no del siguiente en UTC")
        void lasOchoDeLaNocheSonDelMismoDia() {
            assertThat(ZonaHoraria.diaDe(LAS_OCHO_DE_LA_NOCHE)).isEqualTo(LocalDate.of(2026, 3, 4));
            assertThat(LocalDate.ofInstant(LAS_OCHO_DE_LA_NOCHE, ZoneOffset.UTC))
                    .as("y en UTC seria el 5: esta es la discrepancia que cobraba de menos (#273)")
                    .isEqualTo(LocalDate.of(2026, 3, 5));
        }

        @Test
        @DisplayName("a mediodia las dos lecturas coinciden, y por eso no sirve de muestra")
        void aMediodiaNoSeDistingueNada() {
            Instant mediodia = Instant.parse("2026-03-04T17:00:00Z");

            assertThat(ZonaHoraria.diaDe(mediodia)).isEqualTo(LocalDate.of(2026, 3, 4));
            assertThat(LocalDate.ofInstant(mediodia, ZoneOffset.UTC))
                    .as(
                            "queda escrito para que nadie vuelva a sembrar una prueba de zona a"
                                    + " mediodia creyendo que distingue algo")
                    .isEqualTo(LocalDate.of(2026, 3, 4));
        }

        @Test
        @DisplayName("sin instante no hay dia")
        void sinInstanteNoHayDia() {
            assertThatThrownBy(() -> ZonaHoraria.diaDe(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("No hay dia sin instante");
        }
    }

    @Nested
    @DisplayName("comienzoDelDia: y la ida y vuelta")
    class ElComienzoDelDia {

        @Test
        @DisplayName("la medianoche de un dia es local: las 05:00 UTC de ese mismo dia")
        void laMedianocheEsLocal() {
            assertThat(ZonaHoraria.comienzoDelDia(LocalDate.of(2026, 3, 4)))
                    .isEqualTo(Instant.parse("2026-03-04T05:00:00Z"));
        }

        @Test
        @DisplayName("el dia sobrevive a la ida y vuelta, que es lo que el controlador necesita")
        void elDiaSobreviveALaIdaYVuelta() {
            // Es la propiedad que ata `InternamientosController.instanteDe` con
            // `RegistrarInternamiento`: la pantalla manda un dia, el modelo lo guarda como
            // instante y el acta lo vuelve a truncar. Con la medianoche UTC en la ida y la zona
            // del producto en la vuelta, el acta imprimia el dia ANTERIOR.
            for (LocalDate dia :
                    new LocalDate[] {
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3, 4),
                        LocalDate.of(2026, 12, 31),
                        LocalDate.of(1990, 1, 16)
                    }) {
                assertThat(ZonaHoraria.diaDe(ZonaHoraria.comienzoDelDia(dia)))
                        .as("ida y vuelta de " + dia)
                        .isEqualTo(dia);
            }
        }

        @Test
        @DisplayName("sin dia no hay comienzo")
        void sinDiaNoHayComienzo() {
            assertThatThrownBy(() -> ZonaHoraria.comienzoDelDia(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("No hay comienzo sin dia");
        }
    }

    @Nested
    @DisplayName("conSuDesfase: la hora que la API publica (#188)")
    class LaHoraQueSePublica {

        @Test
        @DisplayName("el mismo instante, pero escrito con -05:00 en vez de con una Z")
        void lleveElDesfaseEncima() {
            assertThat(ZonaHoraria.conSuDesfase(LAS_OCHO_DE_LA_NOCHE))
                    .as("los digitos que salen ya son la hora de aqui: no hay nada que restar")
                    .hasToString("2026-03-04T20:00-05:00");
            assertThat(LAS_OCHO_DE_LA_NOCHE.atOffset(ZoneOffset.UTC))
                    .as("y asi salia antes de #188: el dia 5 y a las 01:00")
                    .hasToString("2026-03-05T01:00Z");
        }

        @Test
        @DisplayName("no mueve el instante: es el mismo punto de la linea del tiempo")
        void noMueveElInstante() {
            // Es la diferencia entre presentar y convertir. Si esto dejara de cumplirse, el
            // instante publicado no seria el instante ocurrido y la bitacora mentiria.
            assertThat(ZonaHoraria.conSuDesfase(LAS_OCHO_DE_LA_NOCHE).toInstant())
                    .isEqualTo(LAS_OCHO_DE_LA_NOCHE);
        }

        @Test
        @DisplayName("y el desfase sale de la zona, no de un -05:00 escrito: en 1990 es -04:00")
        void elDesfaseSaleDeLaZona() {
            // La misma medida que `noEsUnDesfaseFijo`, del lado de la publicacion: un `-05:00`
            // cableado fecharia este hecho el dia 15 y una hora antes de lo que fue.
            assertThat(ZonaHoraria.conSuDesfase(Instant.parse("1990-01-16T04:30:00Z")))
                    .hasToString("1990-01-16T00:30-04:00");
        }

        @Test
        @DisplayName("sin instante no hay hora publicada")
        void sinInstanteNoHayHora() {
            assertThatThrownBy(() -> ZonaHoraria.conSuDesfase(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("No hay hora publicada sin instante");
        }
    }
}
