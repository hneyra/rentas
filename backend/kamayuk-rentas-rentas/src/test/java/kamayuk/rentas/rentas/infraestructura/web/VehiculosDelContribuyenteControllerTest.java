package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.rentas.dominio.CambioDePlaca;
import kamayuk.rentas.rentas.dominio.CriterioDeVehiculo;
import kamayuk.rentas.rentas.dominio.EstadoVehiculo;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoEncontrado;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * La coleccion de vehiculos de un contribuyente, por HTTP y sin base de datos (#524).
 *
 * <p>Lo que se verifica aqui es lo que la base no puede decir: <b>quien puede pedirlo, con que
 * criterio se pide y que cruza la frontera</b>.
 *
 * <p>El endpoint existe porque la consulta que ya habia vive bajo la opcion del modulo
 * <b>Consultas</b>, y el expediente de Rentas no puede tomarla prestada de otro modulo (#503 F2).
 * Lo delicado no es publicarla: es <b>que no se convierta en una segunda puerta al padron vehicular
 * entero detras de un permiso mas estrecho</b>, y de eso trata la mitad de este archivo.
 *
 * <p><b>El guardia de verdad esta puesto</b> como interceptor: el 403 no lo simula la prueba, lo
 * produce {@link GuardiaDeAcceso} leyendo la anotacion del controlador.
 */
@DisplayName("Capa web — GET /api/v1/rentas/vehiculos")
class VehiculosDelContribuyenteControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-31T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final long CONTRIBUYENTE = 501L;

    private static final Vehiculo SUYO =
            new Vehiculo(
                    7L,
                    Placa.de("V1H-882"),
                    CONTRIBUYENTE,
                    "HYUNDAI",
                    "TUCSON",
                    "M1",
                    new Ejercicio(2024),
                    new Ejercicio(2025),
                    null,
                    null,
                    EstadoVehiculo.ACTIVO);

    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final PadronDePrueba padron = new PadronDePrueba();
    private final DirectorioDePrueba directorio = new DirectorioDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new VehiculoController(
                                    new ConsultaDeVehiculos(padron, new DeudaDePrueba()),
                                    directorio,
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
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    private static MockHttpServletRequestBuilder deQuien(String contribuyente) {
        return get("/rentas/api/v1/rentas/vehiculos").param("contribuyente", contribuyente);
    }

    @Test
    @DisplayName("sin el contribuyente no lista nada: es 4xx, no el padron entero")
    void sinContribuyenteNoListaNada() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/vehiculos")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin criterio esto seria una segunda puerta al padron vehicular entero"
                                + " detras de un permiso mas estrecho que el de Consultas")
                .isGreaterThanOrEqualTo(400);
        assertThat(padron.buscado)
                .as("y no se llega a preguntar: la peticion muere en el borde")
                .isNull();
    }

    @Test
    @DisplayName("sin permiso es 403, y no se pregunta por nadie")
    void sinPermisoEs403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado = mvc.perform(deQuien("C-000501")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("V1H-882");
        assertThat(padron.buscado).isNull();
    }

    @Test
    @DisplayName("el acceso que se exige es el de la opcion de Rentas, con LECTURA")
    void elAccesoQueSeExige() throws Exception {
        mvc.perform(deQuien("C-000501")).andReturn();

        assertThat(comprobador.acceso)
                .as(
                        "el de «Ficha de vehiculo», que es la opcion de Rentas: pedir el de"
                                + " Consultas dejaria al expediente sin poder dibujarla, que es"
                                + " justo lo que este endpoint viene a arreglar")
                .isEqualTo("vehiculos");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("el criterio lleva el contribuyente y nada mas")
    void elCriterioLlevaSoloElContribuyente() throws Exception {
        mvc.perform(deQuien("C-000501")).andReturn();

        assertThat(padron.buscado).isNotNull();
        assertThat(padron.buscado.contribuyente()).isEqualTo("C-000501");
        // Los otros tres que `CriterioDeVehiculo` admite son los de la busqueda del
        // padron, y esta operacion no es una busqueda: admitirlos aqui devolveria por la
        // puerta estrecha lo que el parametro obligatorio acaba de cerrar.
        assertThat(padron.buscado.placa()).isNull();
        assertThat(padron.buscado.nroMotor()).isNull();
        assertThat(padron.buscado.estado()).isNull();
    }

    @Test
    @DisplayName("la fila es la misma que publica /consultas/vehiculos, con su deuda fechada")
    void laFilaEsLaMismaConSuDeudaFechada() throws Exception {
        MvcResult resultado = mvc.perform(deQuien("C-000501")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"placa\":\"V1H-882\"").contains("\"marca\":\"HYUNDAI\"");
        // Toda cifra de deuda indica a que fecha esta actualizada (RNF-075, regla 9).
        assertThat(cuerpo).contains("\"actualizadoA\":\"2026-08-31\"");
    }

    @Test
    @DisplayName("la fecha de corte de la deuda se puede pedir, y no es siempre hoy")
    void laFechaDeCorteSePuedePedir() throws Exception {
        MvcResult resultado =
                mvc.perform(deQuien("C-000501").param("fecha", "2026-03-15")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("resolver la deuda con el reloj haria que la misma consulta dijera otra cosa")
                .contains("\"actualizadoA\":\"2026-03-15\"");
    }

    @Test
    @DisplayName("la deuda que se suma es la del vehiculo, no la del contribuyente entero")
    void laDeudaEsLaDelVehiculo() throws Exception {
        MvcResult resultado = mvc.perform(deQuien("C-000501")).andReturn();

        // 120.00 del vehiculo 7. Los 900.00 del predio del mismo contribuyente no entran:
        // una fila de vehiculo que sumara la deuda predial diria que ese coche debe lo que
        // debe la casa.
        assertThat(resultado.getResponse().getContentAsString()).contains("120.00");
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("1020.00");
    }

    @Test
    @DisplayName("un codigo que no esta en el padron es 404 nombrandolo, no una pagina vacia")
    void codigoFueraDelPadronEs404() throws Exception {
        MvcResult resultado = mvc.perform(deQuien("NO-EXISTE")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "un 200 con cero filas se lee como «esta persona no tiene ningun"
                                + " vehiculo», y sobre un codigo que no existe eso es falso: es la"
                                + " misma frase que su hermana de predios cerro en #541")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("NO-EXISTE");
        assertThat(padron.buscado).as("y no se llega a preguntar al padron vehicular").isNull();
    }

    @Test
    @DisplayName("un contribuyente del padron SIN vehiculos sigue siendo 200 con cero filas")
    void sinVehiculosSigueSiendo200() throws Exception {
        padron.vacio = true;

        MvcResult resultado = mvc.perform(deQuien("C-000501")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("es el unico caso que de verdad significa «no tiene»")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("«codContribuyente» es el mismo filtro con el otro nombre")
    void codContribuyenteEsElOtroNombre() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/rentas/vehiculos")
                                        .param("codContribuyente", "C-000501"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "quien conecta esta lectura copiando la de predios escribe"
                                + " «codContribuyente», y hasta #595 recibia un 422 que nombra un"
                                + " parametro que la pantalla no dibuja")
                .isEqualTo(200);
        assertThat(padron.buscado).isNotNull();
        assertThat(padron.buscado.contribuyente()).isEqualTo("C-000501");
    }

    @Test
    @DisplayName("sin ninguno de los dos nombres sigue sin listar nada")
    void sinNingunoDeLosDosNombres() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/vehiculos")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("el alias no puede ser una puerta al padron vehicular entero")
                .isEqualTo(422);
        assertThat(padron.buscado).isNull();
    }

    @Test
    @DisplayName("el codigo que viaja al padron es el canonico, no el tecleado")
    void elCodigoQueViajaEsElCanonico() throws Exception {
        mvc.perform(deQuien("c-000501")).andReturn();

        assertThat(directorio.preguntado)
                .as("se pregunta en mayusculas, como hace su hermana de predios")
                .isEqualTo("C-000501");
        assertThat(padron.buscado).isNotNull();
        assertThat(padron.buscado.contribuyente()).isEqualTo("C-000501");
    }

    // ---------------------------------------------------------------- dobles

    /** El padron de contribuyentes: solo C-000501 esta en el. */
    private static final class DirectorioDePrueba implements DirectorioDeContribuyentes {

        private String preguntado;

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            preguntado = codigo;
            return "C-000501".equals(codigo)
                    ? Optional.of(
                            new ResumenDeContribuyente(
                                    CONTRIBUYENTE,
                                    "C-000501",
                                    "MEDINA MEDINA, RUFINA",
                                    "DNI 03593174"))
                    : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /** El padron vehicular en memoria, que ademas recuerda con que criterio se le pregunto. */
    private static final class PadronDePrueba implements VehiculoRepository {

        private CriterioDeVehiculo buscado;
        private boolean vacio;

        @Override
        public Optional<Vehiculo> findByPlaca(Placa placa) {
            return SUYO.placa().equals(placa) ? Optional.of(SUYO) : Optional.empty();
        }

        @Override
        public Optional<Vehiculo> findById(long id) {
            return id == 7L ? Optional.of(SUYO) : Optional.empty();
        }

        @Override
        public Pagina<VehiculoEncontrado> buscar(
                CriterioDeVehiculo criterio, Paginacion paginacion) {
            buscado = criterio;
            List<VehiculoEncontrado> filas =
                    vacio
                            ? List.<VehiculoEncontrado>of()
                            : List.of(
                                    new VehiculoEncontrado(
                                            SUYO, "MEDINA MEDINA, RUFINA", "C-000501"));
            return new Pagina<>(filas, 0, 20, filas.size());
        }

        @Override
        public Vehiculo save(Vehiculo vehiculo) {
            return vehiculo;
        }

        @Override
        public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
            return List.of();
        }
    }

    /** El libro: una obligacion del vehiculo y otra de un predio del mismo contribuyente. */
    private static final class DeudaDePrueba implements ConsultaDeDeudaPublica {

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.of(
                    new ObligacionPublica(
                            "VEHICULAR",
                            new Ejercicio(2026),
                            null,
                            7L,
                            fecha,
                            Dinero.de("120.00"),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO),
                    new ObligacionPublica(
                            "PREDIAL",
                            new Ejercicio(2026),
                            3L,
                            null,
                            fecha,
                            Dinero.de("900.00"),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO));
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String acceso = "";
        private Privilegio privilegio = Privilegio.LECTURA;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }
}
