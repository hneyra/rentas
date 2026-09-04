package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.aplicacion.TablasDeValuacion;
import kamayuk.rentas.catastro.dominio.Arancel;
import kamayuk.rentas.catastro.dominio.Depreciacion;
import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import kamayuk.rentas.catastro.dominio.ValuacionRepository;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #723 — Los tres cuadros de catastro: por que siguen contestando 404, y que lleva ese 404 dentro.
 *
 * <h2>Que se decidio, y por que</h2>
 *
 * <p>Estas tres rutas eran las <b>unicas tres</b> del backend que traducian una excepcion de la
 * familia «falta publicar» a algo que no fuera un 422, y por eso el censo de #691 no las toco. La
 * decision de #723 es que <b>el codigo se queda</b> y lo que cambia es el cuerpo. El motivo largo
 * esta en {@link ArancelController}; el corto son dos frases: lo que se pide aqui es un documento
 * —«la tabla de aranceles sellada de 2026»— y no un acto que el servidor intentara ejecutar; y
 * <b>en esta misma ruta el 422 ya significa otra cosa</b>, que es lo que la segunda prueba de abajo
 * mide.
 *
 * <h2>Lo que estas pruebas miden y ninguna otra puede</h2>
 *
 * <p>Que el 404 lleve {@code parametroQueFalta}. Sin el, «esta tabla no existe» y «el ejercicio no
 * esta sellado» salen con el mismo estado y el mismo par {@code codigo}/{@code mensaje}, y solo se
 * distinguen leyendo el texto —que se reescribe en cuanto alguien lo lee en voz alta—. Son dos
 * cosas con dos remedios distintos y ninguno de los dos lo aplica quien mira la pantalla.
 *
 * <p>Van por HTTP y sin base de datos: lo que se verifica es el borde. Que el conjunto sellado se
 * resuelva bien tiene sus pruebas contra PostgreSQL en {@code TablasDeValuacionTest}.
 */
@DisplayName("#723 — GET /api/v1/catastro/tablas/* cuando el ejercicio no tiene conjunto sellado")
class CuadrosSinSellarControllerTest {

    /** Un ejercicio que ninguna municipalidad ha sellado, y que no es el de ninguna otra prueba. */
    private static final Ejercicio SIN_SELLAR = new Ejercicio(2033);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneId.of("America/Lima"));

    /** Las tres rutas, con el nombre que sale en el rojo si una se queda sin discriminador. */
    static Stream<Arguments> lasTresLecturasDeCuadro() {
        return Stream.of(
                Arguments.of("/rentas/api/v1/catastro/tablas/aranceles"),
                Arguments.of("/rentas/api/v1/catastro/tablas/depreciacion"),
                Arguments.of("/rentas/api/v1/catastro/tablas/valores-unitarios"));
    }

    private final MockMvc mvc = servidor();

    @ParameterizedTest(name = "{0}")
    @MethodSource("lasTresLecturasDeCuadro")
    @DisplayName("contesta 404 con el discriminador, que dice que ejercicio hay que sellar")
    void elCuadroSinSellarDiceQueFaltaPublicar(String ruta) throws Exception {
        MvcResult resultado = mvc.perform(get(ruta).param("anio", "2033")).andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "%s: lo que se pide es un cuadro publicado, no un calculo. #540 y #547 ya"
                                + " dejaron escrita esa distincion y #723 la confirma",
                        ruta)
                .isEqualTo(404);
        assertThat(cuerpo)
                .as("%s: y sigue siendo el codigo del catalogo, no un 404 pelado", ruta)
                .contains("\"codigo\":\"NO_ENCONTRADO\"");
        assertThat(cuerpo)
                .as(
                        "%s: sin el miembro, «esta tabla no existe» y «el ejercicio no esta"
                                + " sellado» son la misma respuesta, y solo una la arregla quien"
                                + " publica",
                        ruta)
                .contains("\"parametroQueFalta\":{\"ejercicio\":2033}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lasTresLecturasDeCuadro")
    @DisplayName("y en la MISMA ruta el 422 ya es otra cosa: un ano que no es un ejercicio")
    void elCuatroVeintidosDeEsaRutaEsUnCampoQueSeCorrigeEnLaPantalla(String ruta) throws Exception {
        MvcResult resultado = mvc.perform(get(ruta).param("anio", "1800")).andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "%s: 1800 no es un ejercicio (1990..2100). Eso lo arregla quien atiende"
                                + " tecleando un ano de verdad, y por eso es un 422",
                        ruta)
                .isEqualTo(422);
        assertThat(cuerpo)
                .as(
                        "%s: y NO lleva el miembro, que es lo que lo separa de «falta publicar». Es"
                                + " la razon de que el cuadro sin sellar no se mueva a 422: pondria las"
                                + " dos cosas bajo el mismo codigo justo donde conviven",
                        ruta)
                .doesNotContain("parametroQueFalta");
    }

    @Test
    @DisplayName("el contraste: un 404 que de verdad significa «no esta» sigue sin discriminador")
    void unNoEncontradoDeVerdadNoLlevaElMiembro() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/tablas/aranceles/no-existe-esta-ruta"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "ponerlo en todos es tan inutil como no ponerlo en ninguno: lo que le da"
                                + " significado al miembro es que falte donde no toca")
                .doesNotContain("parametroQueFalta");
    }

    // ------------------------------------------------------------------

    private static MockMvc servidor() {
        TablasDeValuacion tablas =
                new TablasDeValuacion(
                        new RepositorioQueNadieLlega(),
                        new SinConjuntoSellado(),
                        new AuditoriaQueNadieLlega(),
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(
                        new ArancelController(tablas),
                        new DepreciacionController(tablas),
                        new ValorUnitarioController(tablas))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /** El estado de hoy en toda municipalidad: ningun ejercicio tiene conjunto sellado (D-02a). */
    private static final class SinConjuntoSellado implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw new UnsupportedOperationException("La lectura del cuadro no pasa por aqui");
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
        }
    }

    /** Si el repositorio se llegara a llamar, la prueba estaria midiendo otra cosa. */
    private static final class RepositorioQueNadieLlega implements ValuacionRepository {

        @Override
        public List<Arancel> arancelesDe(IdentificadorDeConjunto conjunto) {
            throw new AssertionError("Sin conjunto sellado no hay a que tabla preguntarle");
        }

        @Override
        public Arancel guardarArancel(Arancel arancel, IdentificadorDeConjunto conjunto) {
            throw new AssertionError("Estas rutas solo leen");
        }

        @Override
        public List<ValorUnitarioEdificacion> valoresUnitariosDe(IdentificadorDeConjunto conjunto) {
            throw new AssertionError("Sin conjunto sellado no hay a que tabla preguntarle");
        }

        @Override
        public List<Depreciacion> depreciacionesDe(IdentificadorDeConjunto conjunto) {
            throw new AssertionError("Sin conjunto sellado no hay a que tabla preguntarle");
        }
    }

    /** Leer un cuadro no escribe en la bitacora; si llegara aqui, seria un defecto. */
    private static final class AuditoriaQueNadieLlega implements Auditoria {

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            throw new AssertionError("Leer un cuadro publicado no registra nada");
        }
    }
}
