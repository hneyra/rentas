package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionCompartida;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.AnularPapeleta;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #267 — Capa web: se prueba el transporte, no la escritura. Que el acto mueva la columna, dé de
 * baja lo que la papeleta cargó y deje su fila de auditoría lo verifica {@code SancionesJdbcTest}
 * contra PostgreSQL de verdad.
 */
@DisplayName("Capa web — POST /api/v1/transito/papeletas/{numero}/anulacion")
class AnulacionDePapeletaControllerTest {

    private static final String RUTA = "/rentas/api/v1/transito/papeletas/";

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();
    private final ValoresDeMentira valores = new ValoresDeMentira();
    private final ExtincionDeMentira extincion = new ExtincionDeMentira();

    private final AnularPapeleta servicio =
            new AnularPapeleta(
                    repositorio, valores, extincion, (RegistroDeAuditoria registro) -> {});

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new AnulacionDePapeletaController(servicio))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("anula y devuelve 201 con la papeleta ANULADA y lo que se dio de baja")
    void anulaYDevuelve201() throws Exception {
        repositorio.crear("PT-0001", EstadoDePapeleta.IMPUESTA);

        MvcResult resultado =
                anular(
                        "PT-0001",
                        "{\"observacion\":\"placa mal tomada\",\"fecha\":\"2026-04-01\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"estado\":\"ANULADA\"");
        assertThat(cuerpo)
                .as("la fila entera sigue publicandose: no se borro ni se edito nada mas")
                .contains("\"placa\":\"ABC-123\"")
                .contains("\"importeAPagar\":\"440\"");
        assertThat(cuerpo)
                .as("y lo que la anulacion extinguio, con su fecha (regla 9)")
                .contains("\"asientosDeBaja\":1");
        assertThat(extincion.causal)
                .as("«la baja que deshace un alta que no debio existir»")
                .isEqualTo(CausalDeBaja.ERROR_MATERIAL);
        assertThat(extincion.fecha)
                .as("la fecha valor es la del acto, no la del dia en que alguien teclea")
                .isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    @DisplayName("una papeleta que no existe es 404")
    void unaPapeletaQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                anular("PT-9999", "{\"observacion\":\"no esta\",\"fecha\":\"2026-04-01\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("sin observacion, 422 (regla 10)")
    void sinObservacion422() throws Exception {
        repositorio.crear("PT-0002", EstadoDePapeleta.IMPUESTA);

        MvcResult resultado = anular("PT-0002", "{\"fecha\":\"2026-04-01\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(repositorio.porNumero("PT-0002"))
                .get()
                .extracting(Papeleta::estado)
                .as("y no escribe nada")
                .isEqualTo(EstadoDePapeleta.IMPUESTA);
    }

    @Test
    @DisplayName("sin fecha, 422: es el dia del acto y la fecha valor de la baja (regla 9)")
    void sinFecha422() throws Exception {
        repositorio.crear("PT-0003", EstadoDePapeleta.IMPUESTA);

        MvcResult resultado = anular("PT-0003", "{\"observacion\":\"falta la fecha\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    /**
     * La siembra NO es uniforme: una por cada estado en el que ya no se debe nada. Con una sola
     * {@code ANULADA} esta prueba pasaría con la comprobación reducida a «ya estaba anulada».
     */
    @Test
    @DisplayName("desde un estado en que ya no se debe nada, 409 y no 422")
    void desdeUnEstadoTerminalEs409() throws Exception {
        for (EstadoDePapeleta terminal : EstadoDePapeleta.values()) {
            if (terminal.seDebe()) {
                continue;
            }
            String numero = "PT-09" + terminal.ordinal();
            repositorio.crear(numero, terminal);

            MvcResult resultado =
                    anular(numero, "{\"observacion\":\"ya no toca\",\"fecha\":\"2026-04-01\"}");

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "desde %s: la peticion es correcta, lo que no admite el acto es el"
                                    + " estado",
                            terminal)
                    .isEqualTo(409);
        }
    }

    @Test
    @DisplayName("con un valor vivo sobre su obligacion, 409 nombrando el valor")
    void conValorVivoEs409() throws Exception {
        repositorio.crear("PT-0004", EstadoDePapeleta.IMPUESTA);
        valores.conValorVivo("RM-2026-000123");

        MvcResult resultado =
                anular("PT-0004", "{\"observacion\":\"error material\",\"fecha\":\"2026-04-01\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat(resultado.getResponse().getContentAsString()).contains("RM-2026-000123");
        assertThat(valores.preguntada)
                .as("se pregunta por la obligacion del libro que la papeleta cargo (#372)")
                .extracting(SeleccionDeObligacion::tributo)
                .isEqualTo("MULTA_TRANSITO");
    }

    /**
     * #371: el libro rechaza porque otra papeleta del mismo obligado comparte la obligacion. La
     * respuesta es 409 —la peticion es correcta, lo que no admite el acto es la situacion del
     * libro— y nombra la otra papeleta por su numero impreso, no por su referencia interna.
     */
    @Test
    @DisplayName("con la obligacion compartida con otra papeleta, 409 nombrandola")
    void conLaObligacionCompartidaEs409() throws Exception {
        repositorio.crear("PT-0005", EstadoDePapeleta.IMPUESTA);
        repositorio.crear("PT-0006", EstadoDePapeleta.IMPUESTA);
        long otra = repositorio.porNumero("PT-0006").orElseThrow().identificador();
        extincion.compartidaCon("PAPELETA-" + otra);

        MvcResult resultado =
                anular("PT-0005", "{\"observacion\":\"error material\",\"fecha\":\"2026-04-01\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PT-0006")
                .doesNotContain("PAPELETA-" + otra);
    }

    @Test
    @DisplayName("la familia viaja en el cuerpo y por omision es transito")
    void laFamiliaViajaEnElCuerpo() throws Exception {
        repositorio.crear("PA-0001", EstadoDePapeleta.IMPUESTA);

        MvcResult resultado =
                anular(
                        "PA-0001",
                        "{\"observacion\":\"administrativa\",\"fecha\":\"2026-04-01\","
                                + "\"familia\":\"ADMINISTRATIVA\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(repositorio.ultimaFamiliaPedida).isEqualTo(Familia.ADMINISTRATIVA);
    }

    // ------------------------------------------------------------------

    private MvcResult anular(String numero, String cuerpo) throws Exception {
        return mvc.perform(
                        post(RUTA + numero + "/anulacion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static final class RepositorioDeMentira implements PapeletaRepository {

        private final List<Papeleta> filas = new ArrayList<>();
        private long siguiente = 1;
        private @Nullable Familia ultimaFamiliaPedida;

        void crear(String numero, EstadoDePapeleta estado) {
            filas.add(
                    new Papeleta(
                            siguiente++,
                            Familia.TRANSITO,
                            numero,
                            1L,
                            LocalDate.of(2026, 3, 1),
                            null,
                            "Av. Grau",
                            "ABC-123",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            1L,
                            Dinero.de("5500"),
                            Alicuota.de("8"),
                            Dinero.de("440"),
                            Alicuota.de("100"),
                            Dinero.de("440"),
                            null,
                            estado,
                            "prueba",
                            Observacion.de("Se registra para la prueba")));
        }

        @Override
        public Papeleta insertar(Papeleta papeleta) {
            throw new UnsupportedOperationException("esta prueba no registra papeletas nuevas");
        }

        @Override
        public Optional<Papeleta> porNumero(String numero) {
            return filas.stream().filter(fila -> fila.numero().equals(numero)).findFirst();
        }

        @Override
        public Optional<Papeleta> porNumero(Familia familia, String numero) {
            ultimaFamiliaPedida = familia;
            return porNumero(numero);
        }

        @Override
        public Optional<Papeleta> porId(long id) {
            return filas.stream().filter(fila -> fila.identificador() == id).findFirst();
        }

        @Override
        public Pagina<Papeleta> buscar(CriterioDePapeleta criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista papeletas");
        }

        @Override
        public Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo) {
            throw new UnsupportedOperationException("esta prueba no cambia numeros");
        }

        @Override
        public Papeleta anular(long papeletaId) {
            for (int i = 0; i < filas.size(); i++) {
                if (filas.get(i).identificador() == papeletaId) {
                    Papeleta anulada = filas.get(i).anulada();
                    filas.set(i, anulada);
                    return anulada;
                }
            }
            throw new IllegalStateException("No hay ninguna papeleta " + papeletaId);
        }
    }

    /** Lo que {@code valores} contesta: un valor vivo sobre la obligacion, o ninguno (#372). */
    private static final class ValoresDeMentira implements ValoresSobreUnaObligacion {

        private @Nullable String vivo;
        private @Nullable SeleccionDeObligacion preguntada;

        void conValorVivo(String numeroDelValor) {
            this.vivo = numeroDelValor;
        }

        @Override
        public Optional<String> vivoSobre(long contribuyenteId, SeleccionDeObligacion obligacion) {
            this.preguntada = obligacion;
            return Optional.ofNullable(vivo);
        }
    }

    private static final class ExtincionDeMentira implements ExtincionDeDeuda {

        private @Nullable CausalDeBaja causal;
        private @Nullable LocalDate fecha;
        private @Nullable String compartidaCon;

        /** Como si el libro tuviera en la misma obligacion la multa de ese otro origen (#371). */
        void compartidaCon(String otroOrigen) {
            this.compartidaCon = otroOrigen;
        }

        @Override
        public MovimientoAsentado extinguirLoOriginadoPor(
                long contribuyenteId,
                SeleccionDeObligacion obligacion,
                LocalDate fecha,
                String documentoOrigen,
                String referenciaExterna,
                CausalDeBaja causal,
                Observacion observacion) {
            if (compartidaCon != null) {
                throw new ObligacionCompartida(
                        obligacion, referenciaExterna, List.of(compartidaCon));
            }
            this.causal = causal;
            this.fecha = fecha;
            return new MovimientoAsentado(
                    List.of(
                            new DeudaAcogida(
                                    obligacion.tributo(),
                                    obligacion.ejercicio(),
                                    1,
                                    obligacion.predioId(),
                                    obligacion.vehiculoId(),
                                    "ORDINARIA",
                                    fecha,
                                    Dinero.de("440"),
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO)),
                    1,
                    fecha);
        }
    }
}
