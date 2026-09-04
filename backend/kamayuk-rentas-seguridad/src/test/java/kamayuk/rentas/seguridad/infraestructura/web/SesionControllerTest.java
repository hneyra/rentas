package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSesion;
import kamayuk.rentas.seguridad.dominio.ConsultaDeAuditoria;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Respaldo;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.seguridad.dominio.SesionRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #544 — capa web de {@code GET /api/v1/seguridad/auditoria}: por donde entra cada filtro.
 *
 * <p>Lo que esta prueba mide es que <b>los filtros que el contrato declara son los que el
 * controlador lee</b>. El desajuste que cerro este issue no se ve desde dentro del dominio ni desde
 * el repositorio: {@code accion} llegaba por la URL, Spring lo ignoraba por no ser parametro de
 * ningun metodo, y la consulta salia sin filtrar —1 441 filas de 1 441—. Aqui se comprueba desde
 * fuera, que es donde ocurre.
 *
 * <p>Lo que ocurre <b>contra PostgreSQL</b> —que cada filtro descarte de verdad lo que no pide— lo
 * mide {@code AdministrarSesionTest}; las dos mitades hacen falta: un filtro puede llegar al
 * criterio y no acotar nada, y puede acotar y no llegar nunca.
 */
@DisplayName("Capa web — GET /api/v1/seguridad/auditoria (#544)")
class SesionControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);

    private @Nullable ConsultaDeAuditoria ultimaConsulta;

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new SesionController(
                                    new AdministrarSesion(
                                            repositorioDeMentira(), null, null, RELOJ),
                                    null,
                                    null,
                                    null))
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
    @DisplayName("los cinco filtros que el contrato declara llegan al criterio")
    void losCincoFiltrosLleganAlCriterio() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/seguridad/auditoria")
                                .param("ejercicio", "2026")
                                .param("usuario", "jcardenas")
                                .param("tabla", "recibo")
                                .param("operacion", "ANULACION")
                                .param("desde", "2026-03-01")
                                .param("hasta", "2026-03-31"))
                .andReturn();

        assertThat(ultimaConsulta).isNotNull();
        assertThat(ultimaConsulta.ejercicio()).isEqualTo(new Ejercicio(2026));
        assertThat(ultimaConsulta.usuario()).isEqualTo("jcardenas");
        assertThat(ultimaConsulta.tabla()).isEqualTo("recibo");
        assertThat(ultimaConsulta.operacion()).isEqualTo(Operacion.ANULACION);
        assertThat(ultimaConsulta.desde()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(ultimaConsulta.hasta()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("«accion» ya no es parametro de esta operacion: no lo lee nadie")
    void laAccionDelPrototipoNoEsParametro() throws Exception {
        // El defecto que cerro #544, escrito como prueba para que no vuelva por la
        // puerta de atras: mientras el contrato lo declaraba, la pantalla lo mandaba
        // y la consulta salia sin acotar. Es `operacion` con otro nombre, y el
        // contrato publica ahora ese (SUPRIMIDOS de generar-openapi.mjs).
        mvc.perform(
                        get("/rentas/api/v1/seguridad/auditoria")
                                .param("ejercicio", "2026")
                                .param("accion", "ALTA"))
                .andReturn();

        assertThat(ultimaConsulta).isNotNull();
        assertThat(ultimaConsulta.operacion())
                .as("si esto dejara de ser nulo, «accion» habria vuelto a filtrar por la espalda")
                .isNull();
        assertThat(ultimaConsulta.tabla()).isNull();
    }

    @Test
    @DisplayName("una palabra que la bitacora no puede guardar se rechaza; no devuelve nada vacio")
    void laPalabraQueNoExisteSeRechaza() throws Exception {
        // «ELIMINACIÓN» es una de las cinco del desplegable del prototipo y no existe
        // ni puede existir: la aplicacion no borra (RNF-051, regla 4). Aceptarla
        // devolveria una tabla vacia, que se lee como «no hubo ninguna».
        MvcResult respuesta =
                mvc.perform(
                                get("/rentas/api/v1/seguridad/auditoria")
                                        .param("ejercicio", "2026")
                                        .param("operacion", "ELIMINACION"))
                        .andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .as("el mensaje dice el vocabulario entero, incluido el que el prototipo no ofrece")
                .contains(
                        "ALTA",
                        "MODIFICACION",
                        "BAJA",
                        "ANULACION",
                        "REVERSION",
                        "PERMISO",
                        "ACCESO");
        assertThat(ultimaConsulta).as("no se llego a consultar nada").isNull();
    }

    @Test
    @DisplayName("«Todas» tampoco se inventa aqui: o es una operacion, o no hay filtro")
    void elTodasDelDesplegableNoSeTraduce() throws Exception {
        // La primera opcion del desplegable significa «sin filtrar», y quien no la
        // manda es la pantalla (ver `pantallas/seguridad`). Traducirla aqui haria de
        // «Todas» una palabra del vocabulario, y entonces habria dos formas de decir
        // lo mismo y una de ellas dependeria del idioma del prototipo.
        MvcResult respuesta =
                mvc.perform(
                                get("/rentas/api/v1/seguridad/auditoria")
                                        .param("ejercicio", "2026")
                                        .param("operacion", "Todas"))
                        .andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("sin filtro de operacion, la consulta no acota por ninguna")
    void sinOperacionNoAcota() throws Exception {
        mvc.perform(get("/rentas/api/v1/seguridad/auditoria").param("ejercicio", "2026"))
                .andReturn();

        assertThat(ultimaConsulta).isNotNull();
        assertThat(ultimaConsulta.operacion()).isNull();
    }

    private SesionRepository repositorioDeMentira() {
        return new SesionRepository() {
            @Override
            public Optional<Sesion> abiertaDe(long usuarioId) {
                return Optional.empty();
            }

            @Override
            public Sesion abrir(long usuarioId) {
                throw new UnsupportedOperationException("esta prueba solo lee la auditoria");
            }

            @Override
            public Sesion fijarEjercicioDeTrabajo(long sesionId, Ejercicio ejercicio) {
                throw new UnsupportedOperationException("esta prueba solo lee la auditoria");
            }

            @Override
            public Pagina<RegistroAuditado> auditoria(
                    ConsultaDeAuditoria consulta, Paginacion paginacion) {
                ultimaConsulta = consulta;
                return Pagina.de(List.of(), paginacion, 0);
            }

            @Override
            public Pagina<Respaldo> respaldos(Paginacion paginacion) {
                return Pagina.de(List.of(), paginacion, 0);
            }
        };
    }
}
