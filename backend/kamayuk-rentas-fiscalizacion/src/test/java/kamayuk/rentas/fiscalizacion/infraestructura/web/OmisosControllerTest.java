package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.dobles.DeteccionDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #545 — Lo que la fila de omisos enseña por HTTP, y lo que cuesta enseñarlo.
 *
 * <p>La detección se mide contra PostgreSQL ({@code DeteccionDeOmisosJdbcTest}), que es donde vive
 * su consulta. Aquí se mide lo otro: que la columna «Titular» lleve el <b>nombre</b>, que su código
 * viaje aparte, y —sobre todo— <b>cuántas lecturas cuesta una página</b>. Resolver los nombres
 * desde el cliente costaba una petición por fila, que es la mitad del defecto que #545 cierra; si
 * el servidor lo resolviera fila a fila estaría haciendo lo mismo por dentro.
 */
@DisplayName("#545 — La fila de omisos por HTTP")
class OmisosControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Ejercicio E2024 = new Ejercicio(2024);

    /**
     * Cuatro predios, cinco titulares distintos: uno de ellos es de dos cónyuges, otro no tiene.
     */
    private static final long PREDIO_A = 20L;

    private static final long PREDIO_B = 21L;
    private static final long PREDIO_CONYUGES = 22L;
    private static final long PREDIO_SIN_TITULAR = 23L;

    private DirectorioQueSeDejaContar directorio;
    private TitularesDeMentira titulares;
    private MockMvc mvc;

    @BeforeEach
    void armar() {
        directorio = new DirectorioQueSeDejaContar();
        titulares =
                new TitularesDeMentira()
                        .con(PREDIO_A, 10L)
                        .con(PREDIO_B, 11L)
                        .con(PREDIO_CONYUGES, 12L, "50.00")
                        .con(PREDIO_CONYUGES, 13L, "50.00");

        DeteccionDeMentira deteccion =
                new DeteccionDeMentira()
                        .con(fila(PREDIO_A, "000000000000000020", CondicionFiscalizada.OMISO))
                        .con(fila(PREDIO_B, "000000000000000021", CondicionFiscalizada.SUBVALUADOR))
                        .con(
                                fila(
                                        PREDIO_CONYUGES,
                                        "000000000000000022",
                                        CondicionFiscalizada.OMISO))
                        .con(
                                fila(
                                        PREDIO_SIN_TITULAR,
                                        "000000000000000023",
                                        CondicionFiscalizada.OMISO));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OmisosController(
                                        new DeteccionDeOmisos(deteccion, titulares),
                                        new EstadoDeCuentaDeFiscalizacion(
                                                new LiquidacionesEnMemoria(),
                                                (contribuyenteId, fecha) -> List.of()),
                                        directorio,
                                        Clock.fixed(
                                                HOY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                                ZoneOffset.UTC)))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @Test
    @DisplayName("una pagina de cuatro filas cuesta UNA lectura del padron y UNA de titulares")
    void unaPaginaCuestaUnaLectura() throws Exception {
        String cuerpo = omisos();

        assertThat(cuerpo).contains("\"totalElementos\":4");
        assertThat(directorio.lecturasPorLote())
                .as(
                        "cuatro filas, una lectura: resolver el nombre fila a fila es lo que se"
                                + " estaba haciendo desde el cliente, y hacerlo en el servidor cuesta"
                                + " lo mismo")
                .isEqualTo(1);
        assertThat(directorio.lecturasDeUno())
                .as("y ni una por codigo: eso seria la peticion por fila mudada de sitio")
                .isZero();
        assertThat(titulares.lecturasPorLote())
                .as("los titulares tambien salen en una sola lectura de catastro")
                .isEqualTo(1);
        assertThat(titulares.lecturasDeUno()).isZero();
        assertThat(directorio.identificadoresPedidos())
                .as("y en esa unica lectura van los cinco titulares de la pagina")
                .containsExactlyInAnyOrder(10L, 11L, 12L, 13L);
    }

    @Test
    @DisplayName("la columna «Titular» lleva el NOMBRE y el codigo viaja aparte")
    void elTitularEsElNombre() throws Exception {
        String cuerpo = omisos();

        assertThat(cuerpo)
                .as("hasta #545 la celda decia «C-000010»")
                .contains("\"titular\":\"PEREZ LOPEZ, ANA\"")
                .contains("\"codigoDelTitular\":\"C-000010\"")
                .doesNotContain("\"titular\":\"C-000010\"");
    }

    @Test
    @DisplayName("el predio de dos conyuges es UNA fila con los dos, y sin UN codigo")
    void elPredioDeDosConyugesEsUnaFila() throws Exception {
        String cuerpo = omisos();

        assertThat(cuerpo)
                .as("un predio al 50/50 salia dos veces y la pantalla lo leia como dos predios")
                .containsOnlyOnce("\"codRefCatastral\":\"000000000000000022\"")
                .contains("\"titular\":\"CONYUGE UNO y CONYUGE DOS\"")
                .contains("{\"codigo\":\"C-000012\",\"nombre\":\"CONYUGE UNO\"}")
                .contains("{\"codigo\":\"C-000013\",\"nombre\":\"CONYUGE DOS\"}");
    }

    @Test
    @DisplayName("el predio sin titular sale, con la columna vacia y sin codigo")
    void elPredioSinTitularSale() throws Exception {
        String cuerpo = omisos();

        assertThat(cuerpo)
                .as("es el 34,5 % del padron de Catacaos, y el primero que hay que fiscalizar")
                .contains("\"codRefCatastral\":\"000000000000000023\"");
        assertThat(filaDe(cuerpo, "000000000000000023"))
                .contains("\"titular\":null")
                .contains("\"codigoDelTitular\":null")
                .contains("\"titulares\":[]");
    }

    @Test
    @DisplayName("el filtro de condicion llega al criterio, y el sobre cuenta lo filtrado")
    void elFiltroLlegaAlCriterio() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/omisos")
                                        .param("ejercicio", "2024")
                                        .param("condicion", "SUBVALUADOR"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("cero filas diciendo que hay cuatro es lo que #545 cierra")
                .contains("\"totalElementos\":1")
                .contains("\"codRefCatastral\":\"000000000000000021\"")
                .doesNotContain("\"codRefCatastral\":\"000000000000000020\"");
    }

    // ------------------------------------------------------------------

    private String omisos() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/omisos").param("ejercicio", "2024"))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /** El trozo del JSON que empieza en la fila de ese codigo, para no leer la del vecino. */
    private static String filaDe(String cuerpo, String codigo) {
        int desde = cuerpo.indexOf("\"codRefCatastral\":\"" + codigo + "\"");
        assertThat(desde).as("la fila %s no esta en la respuesta", codigo).isNotNegative();
        int hasta = cuerpo.indexOf("},{", desde);
        return hasta < 0 ? cuerpo.substring(desde) : cuerpo.substring(desde, hasta);
    }

    /** Una fila detectada, sin titulares: los pone {@code DeteccionDeOmisos} al leer la pagina. */
    private static FilaDeOmisos fila(long predioId, String codigo, CondicionFiscalizada condicion) {
        return new FilaDeOmisos(
                predioId,
                codigo,
                "01",
                List.of(),
                E2024,
                condicion,
                false,
                AreaM2.de("300.00"),
                null,
                null,
                null,
                null);
    }

    /**
     * El padron, que apunta cuantas veces se le pregunta. Es lo unico que distingue «una lectura
     * por pagina» de «una por fila», y sin contarlo las dos formas dan el mismo JSON.
     */
    private static final class DirectorioQueSeDejaContar implements DirectorioDeContribuyentes {

        private static final Map<Long, ResumenDeContribuyente> PADRON = new HashMap<>();

        static {
            PADRON.put(10L, resumen(10L, "PEREZ LOPEZ, ANA"));
            PADRON.put(11L, resumen(11L, "QUISPE RAMOS, LUIS"));
            PADRON.put(12L, resumen(12L, "CONYUGE UNO"));
            PADRON.put(13L, resumen(13L, "CONYUGE DOS"));
        }

        private final List<Long> pedidos = new ArrayList<>();
        private int lecturasPorLote;
        private int lecturasDeUno;

        int lecturasPorLote() {
            return lecturasPorLote;
        }

        int lecturasDeUno() {
            return lecturasDeUno;
        }

        List<Long> identificadoresPedidos() {
            return List.copyOf(pedidos);
        }

        private static ResumenDeContribuyente resumen(long id, String nombre) {
            return new ResumenDeContribuyente(
                    id, String.format("C-%06d", id), nombre, "DNI 6010000" + id);
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            lecturasDeUno++;
            return PADRON.values().stream().filter(r -> r.codigo().equals(codigo)).findFirst();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            lecturasPorLote++;
            pedidos.addAll(ids);
            Map<Long, ResumenDeContribuyente> encontrados = new HashMap<>();
            for (Long id : ids) {
                ResumenDeContribuyente resumen = PADRON.get(id);
                if (resumen != null) {
                    encontrados.put(id, resumen);
                }
            }
            return Map.copyOf(encontrados);
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }
}
