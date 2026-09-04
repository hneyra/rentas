package kamayuk.rentas.rentas.infraestructura.web;

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
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeTitulares;
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
 * El transporte de la resolucion del titular, por HTTP de verdad y sin base de datos (#366,
 * ADR-0015 §2.4).
 *
 * <p>Lo que se verifica aqui es lo que la base no puede decir: <b>quien puede pedirlo y que cruza
 * la frontera</b>. La respuesta lleva el codigo con el que se entra a la ficha del contribuyente, y
 * por eso va detras del permiso del <b>padron</b> —no del de la pantalla desde la que se hace clic—
 * y deja fila de {@code ACCESO} en la bitacora. Del padron no viaja nada mas, y de la titularidad
 * tampoco.
 *
 * <p><b>El guardia de verdad esta puesto</b> como interceptor: el 403 no lo simula la prueba, lo
 * produce {@link GuardiaDeAcceso} leyendo la anotacion del controlador. Por eso quitarle el
 * {@code @RequiereAcceso} no deja «pasar»: el guardia niega cuando falta, y el camino feliz se pone
 * rojo.
 *
 * <p>La vigencia a la fecha, las cuotas contra el padron real y el aislamiento tienen sus pruebas
 * en {@code TitularDelPredioJdbcTest}, contra PostgreSQL.
 */
@DisplayName("Capa web — GET /api/v1/catastro/predios/{predioId}/titulares")
class TitularesDelPredioControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final long PREDIO_DE_DOS = 10L;
    private static final long PREDIO_SIN_TITULAR = 11L;
    private static final long PREDIO_DEL_QUE_YA_NO_ESTA = 12L;

    private static final long JUAN = 501L;
    private static final long MARIA = 502L;
    private static final long BORRADO = 503L;

    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();
    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new TitularesDelPredioController(
                                    new ConsultaDeTitulares(
                                            new TitularidadDePrueba(),
                                            new PadronDePrueba(),
                                            auditoria,
                                            RELOJ),
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

    @Test
    @DisplayName("sin el permiso del padron es 403, y no deja rastro de lo que no vio")
    void sinPermisoDelPadronEs403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado = mvc.perform(peticion(PREDIO_DE_DOS)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el identificador del contribuyente es dato del padron, con su propio"
                                + " permiso: quien solo puede listar fichas no lo obtiene"
                                + " (ADR-0015 §2.4)")
                .isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("C-000123");
        assertThat(auditoria.registros)
                .as("una consulta que no ocurrio no deja constancia de haber ocurrido")
                .isEmpty();
    }

    @Test
    @DisplayName("y el acceso que se exige es el del padron, con LECTURA")
    void elAccesoQueSeExige() throws Exception {
        mvc.perform(peticion(PREDIO_DE_DOS)).andReturn();

        assertThat(comprobador.acceso)
                .as(
                        "el de la opcion del padron, no el de `consulta_fichas`: su publico"
                                + " —cualquiera que opere catastro— es mucho mas amplio")
                .isEqualTo("contribuyentes");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.LECTURA);
        assertThat(comprobador.usuario).isEqualTo("cajero.ventanilla");
    }

    @Test
    @DisplayName("con permiso devuelve el codigo del titular y deja su fila de ACCESO")
    void conPermisoDevuelveElCodigoYDejaFilaDeAcceso() throws Exception {
        MvcResult resultado = mvc.perform(peticion(PREDIO_DE_DOS)).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("es el codigo, y no el nombre, lo que permite enlazar sin homonimia (#322)")
                .contains("\"codigo\":\"C-000123\"");

        assertThat(auditoria.registros).hasSize(1);
        RegistroDeAuditoria registro = auditoria.registros.get(0);
        assertThat(registro.operacion()).isEqualTo(Operacion.ACCESO);
        assertThat(registro.tabla())
                .as("lo que la consulta atraviesa es la correlacion predio→persona")
                .isEqualTo("titularidad");
        assertThat(registro.clave()).isEqualTo("predio=10;vigenteA=2026-08-28");
        assertThat(registro.observacion().texto())
                .as("la bitacora tiene que decir que se resolvio, no solo que alguien miro")
                .contains("titular del predio 10")
                .contains("2026-08-28");
    }

    @Test
    @DisplayName("las cuotas viajan enteras: dos titulares al 50 % son dos filas")
    void lasCuotasViajanEnteras() throws Exception {
        String cuerpo = cuerpoDe(peticion(PREDIO_DE_DOS));

        assertThat(cuerpo)
                .as(
                        "no existe «el titular» de un predio con dos conyuges: devolver uno solo"
                                + " obligaria a elegir y a callar al otro")
                .contains("\"codigo\":\"C-000123\"")
                .contains("\"codigo\":\"C-000456\"")
                .contains("\"porcentaje\":\"50.00\"")
                .contains("\"condicion\":\"CONYUGE\"");
    }

    @Test
    @DisplayName("la respuesta dice siempre a que fecha contesta, aunque no se la pidan")
    void laRespuestaDiceSiempreAQueFechaContesta() throws Exception {
        assertThat(cuerpoDe(peticion(PREDIO_DE_DOS)))
                .as("sin fecha, «el titular» es una afirmacion que manana puede ser otra (regla 9)")
                .contains("\"vigenteA\":\"2026-08-28\"");
    }

    @Test
    @DisplayName("y si se la piden, contesta a esa y lo dice")
    void siLaPidenContestaAEsa() throws Exception {
        String cuerpo =
                cuerpoDe(
                        get("/rentas/api/v1/catastro/predios/{predioId}/titulares", PREDIO_DE_DOS)
                                .param("vigenteA", "2026-03-15"));

        assertThat(cuerpo).contains("\"vigenteA\":\"2026-03-15\"");
        assertThat(auditoria.registros.get(0).clave())
                .as("y la bitacora anota a que fecha se pregunto")
                .isEqualTo("predio=10;vigenteA=2026-03-15");
    }

    @Test
    @DisplayName("del padron y de la titularidad no viaja nada mas")
    void noViajaNadaMas() throws Exception {
        String cuerpo = cuerpoDe(peticion(PREDIO_DE_DOS));

        assertThat(cuerpo)
                .as(
                        "el codigo es lo que hace falta para enlazar; el identificador interno y el"
                                + " documento son padron que nadie pidio")
                .doesNotContain("contribuyenteId")
                .doesNotContain("documento")
                .doesNotContain("12345678");
        assertThat(cuerpo)
                .as("y de la titularidad no salen ni sus fechas ni el documento que la sustenta")
                .doesNotContain("vigenciaDesde")
                .doesNotContain("vigenciaHasta")
                .doesNotContain("documentoOrigen")
                .doesNotContain("titularidadId");
        assertThat(cuerpo).doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("un predio sin titular vigente devuelve lista vacia, y deja rastro igual")
    void unPredioSinTitularDevuelveVacio() throws Exception {
        MvcResult resultado = mvc.perform(peticion(PREDIO_SIN_TITULAR)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "no distingue «no existe» de «no tiene titular»: contestar distinto"
                                + " convertiria esta lectura en un detector de predios ajenos")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"titulares\":[]");
        assertThat(auditoria.registros)
                .as(
                        "quien va probando identificadores para levantar el mapa del padron deja su"
                                + " nombre en cada intento, tambien en los que no devuelven nada")
                .hasSize(1);
    }

    @Test
    @DisplayName("un titular que ya no esta en el padron sale igual, sin codigo")
    void unTitularQueYaNoEstaSaleIgual() throws Exception {
        String cuerpo = cuerpoDe(peticion(PREDIO_DEL_QUE_YA_NO_ESTA));

        assertThat(cuerpo)
                .as("ocultarlo escondería el predio que catastro tiene que revisar")
                .contains("\"codigo\":null")
                .contains("\"nombre\":null")
                .contains("\"porcentaje\":\"100\"");
    }

    @Test
    @DisplayName("una fecha que no es una fecha es 422, sin nombrar columnas")
    void unaFechaQueNoEsFechaEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get(
                                                "/rentas/api/v1/catastro/predios/{predioId}/titulares",
                                                PREDIO_DE_DOS)
                                        .param("vigenteA", "el martes"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("AAAA-MM-DD")
                .doesNotContain("titularidad");
        assertThat(auditoria.registros).isEmpty();
    }

    // ------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            peticion(long predioId) {
        return get("/rentas/api/v1/catastro/predios/{predioId}/titulares", predioId);
    }

    private String cuerpoDe(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion)
            throws Exception {
        return mvc.perform(peticion).andReturn().getResponse().getContentAsString();
    }

    /** Tres predios: uno con dos conyuges, uno sin titular y uno cuyo titular ya no esta. */
    private static final class TitularidadDePrueba implements TitularesDelPredio {

        @Override
        public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
            if (predioId == PREDIO_DE_DOS) {
                return List.of(
                        new TitularDelPredio(JUAN, "CONYUGE", Porcentaje.de("50.00")),
                        new TitularDelPredio(MARIA, "CONYUGE", Porcentaje.de("50.00")));
            }
            if (predioId == PREDIO_DEL_QUE_YA_NO_ESTA) {
                return List.of(
                        new TitularDelPredio(BORRADO, "PROPIETARIO_UNICO", Porcentaje.total()));
            }
            return List.of();
        }

        /** No lo usa esta pantalla, pero el puerto lo declara desde #680. */
        @Override
        public boolean estaEnElPadron(long predioId) {
            return true;
        }

        /** No lo usa esta pantalla —resuelve un predio al clic—, pero el puerto lo declara. */
        @Override
        public java.util.Map<Long, List<TitularDelPredio>> deVarios(
                java.util.Collection<Long> predioIds, LocalDate fecha) {
            java.util.Map<Long, List<TitularDelPredio>> porPredio = new java.util.LinkedHashMap<>();
            for (Long predioId : predioIds) {
                List<TitularDelPredio> cuotas = de(predioId, fecha);
                if (!cuotas.isEmpty()) {
                    porPredio.put(predioId, cuotas);
                }
            }
            return porPredio;
        }
    }

    /** Solo lo que el caso de uso llama; el resto no se implementa porque no se usa. */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> resumenes = new LinkedHashMap<>();
            if (ids.contains(JUAN)) {
                resumenes.put(
                        JUAN,
                        new ResumenDeContribuyente(
                                JUAN, "C-000123", "PEÑA GARCIA, JUAN", "DNI 12345678"));
            }
            if (ids.contains(MARIA)) {
                resumenes.put(
                        MARIA,
                        new ResumenDeContribuyente(
                                MARIA, "C-000456", "SILVA DE PEÑA, MARIA", "DNI 87654321"));
            }
            // BORRADO no esta: el identificador que no existe simplemente no aparece.
            return resumenes;
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("la resolucion no busca por nombre");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            throw new UnsupportedOperationException("la resolucion no busca por codigo");
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("el domicilio no cruza esta frontera");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String usuario = "";
        private String acceso = "";
        private Privilegio privilegio = Privilegio.LECTURA;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.usuario = usuario;
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }
}
