package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El padron predial de rentas por HTTP, sin base de datos (#395).
 *
 * <p>Lo que se verifica: que los filtros que la pantalla dibuja <b>filtran de verdad</b> —aceptar
 * uno que no filtra devuelve una respuesta equivocada, no una incompleta—, que la condicion que
 * viaja es la <b>del contribuyente que pregunta</b> y no la del primer titular del predio, y que lo
 * que el sistema no sabe —el autovaluo— no se publica en vez de publicarse en blanco.
 */
@DisplayName("Capa web — GET /api/v1/rentas/predios")
class PrediosDeRentasControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final long JUAN = 501L;
    private static final long MARIA = 502L;
    private static final long SIN_PREDIOS = 503L;

    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final PrediosDePrueba predios = new PrediosDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new PrediosDeRentasController(
                                    predios,
                                    new CaracteristicasDePrueba(),
                                    new TitularidadDePrueba(),
                                    new DirectorioDePrueba(),
                                    RELOJ))
                    .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(22L, "20002", "JR. LIMA 250", Porcentaje.de("50"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("lista los predios del contribuyente con su uso, su sector y su condicion")
    void listaLoQueLaPantallaDibuja() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json).contains("\"codigoReferenciaCatastral\":\"10001\"");
        assertThat(json).contains("\"uso\":\"CASA HABITACION\"");
        assertThat(json).contains("\"sector\":\"S-01\"");
        assertThat(json).contains("\"areaTerreno\":\"120.00\"");
        assertThat(json).contains("\"condicion\":\"PROPIETARIO_UNICO\"");
        assertThat(json).contains("\"totalElementos\":2");
    }

    @Test
    @DisplayName("no publica autovaluo: el sistema no sabe valorizar un predio todavia")
    void noPublicaAutovaluo() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-001"))
                        .andReturn();

        // Una columna de dinero siempre en blanco es peor que ninguna: en una grilla, una cifra
        // ausente y un cero no se distinguen. Quien quiera el autovaluo lo encuentra donde se
        // declara, que es la determinacion (D-11, GOB-03 H-14/H-15).
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("autovaluo");
    }

    @Test
    @DisplayName("la condicion es la del contribuyente que pregunta, no la del primer titular")
    void laCondicionEsLaSuya() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-002"))
                        .andReturn();

        // El predio 22 tiene dos titulares: Juan como COPROPIETARIO y Maria como SUCESION. Cada
        // uno lo tiene en su condicion, y la fila que se le dibuja a Maria dice la de Maria.
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"condicion\":\"SUCESION\"");
    }

    @Test
    @DisplayName("el filtro por codigo predial busca por prefijo y filtra de verdad")
    void elFiltroPorCodigoFiltra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predios")
                                        .param("codContribuyente", "C-001")
                                        .param("codigoPredial", "200"))
                        .andReturn();

        String json = resultado.getResponse().getContentAsString();
        assertThat(json).contains("\"totalElementos\":1").contains("20002").doesNotContain("10001");
    }

    @Test
    @DisplayName("los filtros de sector y condicion tambien filtran")
    void losOtrosFiltrosFiltran() throws Exception {
        MvcResult porSector =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predios")
                                        .param("codContribuyente", "C-001")
                                        .param("sector", "s-02"))
                        .andReturn();
        MvcResult porCondicion =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predios")
                                        .param("codContribuyente", "C-001")
                                        .param("condicion", "COPROPIETARIO"))
                        .andReturn();

        assertThat(porSector.getResponse().getContentAsString())
                .contains("\"totalElementos\":1")
                .contains("20002");
        assertThat(porCondicion.getResponse().getContentAsString())
                .contains("\"totalElementos\":1")
                .contains("20002");
    }

    @Test
    @DisplayName("sin contribuyente es 422 nombrando el parametro, no 200 con cero filas")
    void sinContribuyenteEs422() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/predios")).andReturn();

        // «no hay filtro» y «esta persona no tiene predios» son dos cosas distintas, y hasta
        // #541 se decian igual: 200 con la pagina vacia. La hermana de al lado —GET
        // /rentas/vehiculos— ya contestaba 422 al mismo descuido.
        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("codContribuyente")
                .doesNotContain("10001");
    }

    @Test
    @DisplayName("«contribuyente» y «codContribuyente» son el mismo filtro")
    void losDosNombresDelMismoFiltro() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("contribuyente", "C-001"))
                        .andReturn();

        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":2");
    }

    @Test
    @DisplayName("un contribuyente que no esta en el padron es 404, y lo dice")
    void contribuyenteInexistente() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predios")
                                        .param("codContribuyente", "NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("NO-EXISTE");
    }

    @Test
    @DisplayName("«no esta en el padron» y «no tiene predios» son dos respuestas distintas")
    void elQueNoEstaYElQueNoTienePredios() throws Exception {
        MvcResult inexistente =
                mvc.perform(
                                get("/rentas/api/v1/rentas/predios")
                                        .param("codContribuyente", "NO-EXISTE"))
                        .andReturn();
        MvcResult sinPredios =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-003"))
                        .andReturn();

        // Hoy eran identicas byte a byte, y son la pregunta que se hace en ventanilla: si el
        // codigo esta mal tecleado o si la persona de verdad no tiene ningun predio.
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        assertThat(sinPredios.getResponse().getStatus()).isEqualTo(200);
        assertThat(sinPredios.getResponse().getContentAsString()).contains("\"totalElementos\":0");
        assertThat(inexistente.getResponse().getContentAsString())
                .isNotEqualTo(sinPredios.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("sin el permiso de la opcion es 403 y no viaja ni un codigo predial")
    void sinPermisoEs403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("10001");
        assertThat(comprobador.acceso).isEqualTo("predios_rentas");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("la municipalidad no viaja en la respuesta ni se puede pedir")
    void laMunicipalidadNoViaja() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/predios").param("codContribuyente", "C-001"))
                        .andReturn();

        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("municipalidad");
    }

    // ---------------------------------------------------------------- dobles

    private static final class PrediosDePrueba implements PrediosDelContribuyente {

        private final Map<Long, List<PredioDelContribuyente>> porContribuyente =
                new LinkedHashMap<>();

        void con(long predioId, String codigo, String direccion, Porcentaje cuota) {
            porContribuyente
                    .computeIfAbsent(JUAN, quien -> new ArrayList<>())
                    .add(new PredioDelContribuyente(predioId, codigo, "URBANO", direccion, cuota));
            if (predioId == 22L) {
                porContribuyente
                        .computeIfAbsent(MARIA, quien -> new ArrayList<>())
                        .add(
                                new PredioDelContribuyente(
                                        predioId,
                                        codigo,
                                        "URBANO",
                                        direccion,
                                        Porcentaje.de("50")));
            }
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.copyOf(porContribuyente.getOrDefault(contribuyenteId, List.of()));
        }
    }

    private static final class CaracteristicasDePrueba implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
            return predioId == 11L
                    ? Optional.of(
                            new CaracteristicasDelPredio(
                                    "CASA HABITACION", "S-01", AreaM2.de("120.00")))
                    : Optional.of(
                            new CaracteristicasDelPredio("COMERCIO", "S-02", AreaM2.de("300.00")));
        }
    }

    private static final class TitularidadDePrueba implements TitularesDelPredio {
        @Override
        public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
            if (predioId == 11L) {
                return List.of(new TitularDelPredio(JUAN, "PROPIETARIO_UNICO", Porcentaje.total()));
            }
            return List.of(
                    new TitularDelPredio(JUAN, "COPROPIETARIO", Porcentaje.de("50")),
                    new TitularDelPredio(MARIA, "SUCESION", Porcentaje.de("50")));
        }

        /** No lo usa esta pantalla, pero el puerto lo declara desde #680. */
        @Override
        public boolean estaEnElPadron(long predioId) {
            return true;
        }

        /** No lo usa esta pantalla, pero el puerto lo declara desde #545. */
        @Override
        public java.util.Map<Long, List<TitularDelPredio>> deVarios(
                java.util.Collection<Long> predioIds, LocalDate fecha) {
            java.util.Map<Long, List<TitularDelPredio>> porPredio = new java.util.LinkedHashMap<>();
            for (Long predioId : predioIds) {
                porPredio.put(predioId, de(predioId, fecha));
            }
            return porPredio;
        }
    }

    private static final class DirectorioDePrueba implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente UNO =
                new ResumenDeContribuyente(JUAN, "C-001", "JUAN PEREZ", "03593174");
        private static final ResumenDeContribuyente DOS =
                new ResumenDeContribuyente(MARIA, "C-002", "MARIA MEDINA", "03593175");

        /** Esta si esta en el padron, y no tiene ni un predio: la otra mitad de #541 AC 4. */
        private static final ResumenDeContribuyente TRES =
                new ResumenDeContribuyente(SIN_PREDIOS, "C-003", "PEDRO SIN PREDIOS", "03593176");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("El padron predial no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if ("C-001".equals(codigo)) {
                return Optional.of(UNO);
            }
            if ("C-002".equals(codigo)) {
                return Optional.of(DOS);
            }
            return "C-003".equals(codigo) ? Optional.of(TRES) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            throw new UnsupportedOperationException("El padron predial resuelve por codigo");
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String acceso = "";
        private Privilegio privilegio = Privilegio.REGISTRO;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }
}
