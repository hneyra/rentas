package kamayuk.rentas.licencias.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.licencias.dobles.DerechosDeMentira;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.PartidaDeEdificacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #455 — cada obra se valoriza con el cuadro <b>de su acto</b>, y la ficha y el reporte no pueden
 * dar dos motivos para el mismo hecho.
 */
@DisplayName("#455 — la valorizacion del FUE: el cuadro del acto, y un solo camino")
class ValorizacionDelFueTest {

    private static final LocalDate EN_2025 = LocalDate.of(2025, 6, 10);
    private static final LocalDate EN_2026 = LocalDate.of(2026, 3, 1);

    private static final List<EstructuraDelProyecto> DIEZ_M2_DE_MUROS_A =
            List.of(
                    new EstructuraDelProyecto(
                            null,
                            3L,
                            1,
                            1,
                            PartidaDeEdificacion.MUROS,
                            'A',
                            new AreaM2(new BigDecimal("10"))));

    /** Un cuadro por ejercicio, y la cuenta de cuantas veces se leyo cada uno. */
    private static final class CuadroPorEjercicio implements LectorDeValoresUnitarios {
        private final Map<Integer, List<ValorUnitarioPublicado>> porAnio = new HashMap<>();
        private final Map<Integer, Integer> lecturas = new HashMap<>();

        CuadroPorEjercicio con(int anio, String partida, char categoria, String valorM2) {
            porAnio.computeIfAbsent(anio, k -> new java.util.ArrayList<>())
                    .add(
                            new ValorUnitarioPublicado(
                                    partida, categoria, 1990, null, ValorNormativo.de(valorM2)));
            return this;
        }

        /** Sellado y sin ninguna celda. */
        CuadroPorEjercicio vacio(int anio) {
            porAnio.put(anio, List.of());
            return this;
        }

        @Override
        public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
            lecturas.merge(ejercicio.valor(), 1, Integer::sum);
            List<ValorUnitarioPublicado> celdas = porAnio.get(ejercicio.valor());
            if (celdas == null) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }
            return List.copyOf(celdas);
        }
    }

    private static ValorizacionDelFue con(LectorDeValoresUnitarios cuadro) {
        return new ValorizacionDelFue(
                cuadro,
                new DerechosDeMentira(null, null)
                        .conRedondeo(
                                PuntoDeRedondeo.VALOR_DE_OBRA_DEL_FUE, 2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName(
            "dos actos de dos ejercicios: cada fila con SU cuadro, y cada cuadro leido una vez")
    void cadaObraConElCuadroDeSuActo() {
        CuadroPorEjercicio cuadro =
                new CuadroPorEjercicio()
                        .con(2025, "MUROS", 'A', "100")
                        .con(2026, "MUROS", 'A', "150");
        ValorizacionDelFue valorizaciones = con(cuadro);

        Map<Long, ValorizacionDelFue.ObraAValorizar> obras = new LinkedHashMap<>();
        obras.put(1L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2025));
        obras.put(2L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2026));
        obras.put(3L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2026));
        Map<Long, ValorizacionDelFue.Resultado> hoja = valorizaciones.valorizarVarias(obras);

        assertThat(hoja.get(1L))
                .as(
                        "la fila del acto de 2025 es la de su ficha, no la del cuadro de otro ejercicio")
                .isEqualTo(con(cuadro).valorizar(DIEZ_M2_DE_MUROS_A, EN_2025));
        assertThat(hoja.get(2L)).isEqualTo(con(cuadro).valorizar(DIEZ_M2_DE_MUROS_A, EN_2026));
        assertThat(hoja.get(1L).obra().orElseThrow().total())
                .as("y las dos cifras son distintas: la siembra distingue")
                .isNotEqualTo(hoja.get(2L).obra().orElseThrow().total());
        assertThat(hoja.get(1L).ejercicio()).isEqualTo(new Ejercicio(2025));
    }

    @Test
    @DisplayName("una pagina lee cada cuadro una sola vez, aunque varias filas sean del mismo anio")
    void cadaCuadroUnaVez() {
        CuadroPorEjercicio cuadro =
                new CuadroPorEjercicio()
                        .con(2025, "MUROS", 'A', "100")
                        .con(2026, "MUROS", 'A', "150");

        Map<Long, ValorizacionDelFue.ObraAValorizar> obras = new LinkedHashMap<>();
        obras.put(1L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2025));
        obras.put(2L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2026));
        obras.put(3L, new ValorizacionDelFue.ObraAValorizar(DIEZ_M2_DE_MUROS_A, EN_2026));
        obras.put(4L, new ValorizacionDelFue.ObraAValorizar(List.of(), EN_2026));
        con(cuadro).valorizarVarias(obras);

        assertThat(cuadro.lecturas)
                .as("media hoja con una version y media con otra es lo que una lectura unica evita")
                .isEqualTo(Map.of(2025, 1, 2026, 1));
    }

    static Stream<Arguments> losCuatroCasos() {
        return Stream.of(
                Arguments.of(
                        "sin estructuras Y sin sellar —la siembra que separaba las dos copias—",
                        new CuadroPorEjercicio(),
                        List.of()),
                Arguments.of("sin sellar", new CuadroPorEjercicio(), DIEZ_M2_DE_MUROS_A),
                Arguments.of(
                        "sin celdas", new CuadroPorEjercicio().vacio(2026), DIEZ_M2_DE_MUROS_A),
                Arguments.of(
                        "la celda que falta",
                        new CuadroPorEjercicio().con(2026, "TECHOS", 'B', "80"),
                        DIEZ_M2_DE_MUROS_A));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("losCuatroCasos")
    @DisplayName("la ficha y el reporte dicen el mismo motivo y la misma llave")
    void elMismoMotivo(
            String caso, CuadroPorEjercicio cuadro, List<EstructuraDelProyecto> estructuras) {
        ValorizacionDelFue valorizaciones = con(cuadro);

        ValorizacionDelFue.Resultado ficha = valorizaciones.valorizar(estructuras, EN_2026);
        ValorizacionDelFue.Resultado fila =
                valorizaciones
                        .valorizarVarias(
                                Map.of(
                                        7L,
                                        new ValorizacionDelFue.ObraAValorizar(
                                                estructuras, EN_2026)))
                        .get(7L);

        assertThat(ficha.estaDisponible()).as(caso).isFalse();
        assertThat(fila)
                .as("hasta #455 eran dos copias, y con %s daban motivos distintos", caso)
                .isEqualTo(ficha);
    }
}
