package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.catastro.prueba.GrillaDelEscenario;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeConciliacion.FichaConciliada;
import kamayuk.rentas.nucleo.dominio.PlantillaDeNumeroDeDeclaracion;
import kamayuk.rentas.nucleo.infraestructura.ConciliacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.DeclaracionJuradaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.web.DeclaracionJuradaController;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>El circuito completo de #365</b>: se presenta la declaracion jurada por el endpoint y el
 * predio pasa a conciliar en la lectura de #344, contra PostgreSQL de verdad y con RLS.
 *
 * <p>Es la prueba que este issue existe para poder escribir. ADR-0015 §3 dice que «el acto que
 * concilia es registrar la declaracion jurada, y hoy el sistema no lo publica»: hasta aqui, la
 * unica forma de que un predio apareciera conciliado era que alguien sembrara la fila a mano. Lo
 * que se verifica de punta a punta:
 *
 * <ol>
 *   <li>un predio con ficha y sin declaracion <b>no</b> concilia;
 *   <li>un {@code POST /api/v1/rentas/declaraciones} sobre el —HTTP de verdad, con su cuerpo y su
 *       observacion— lo deja conciliando;
 *   <li>observarlo <b>no</b> lo retira (ADR-0015 §1: observar objeta el contenido, no la
 *       presentacion);
 *   <li>anularlo si lo retira;
 *   <li>y una rectificatoria que <b>cambia de predio</b> mueve la conciliacion: el que se declaro
 *       por error deja de conciliar, el nuevo pasa a hacerlo, y ninguno de los dos cuenta dos
 *       veces.
 * </ol>
 *
 * <p>Las dos mitades corren contra la misma base y en su propia transaccion: la escritura por el
 * controlador y el caso de uso, la lectura por {@link ConsultaDeConciliacion}. Nada se comprueba
 * sobre objetos en memoria.
 */
@DisplayName("#365 — De presentar la DJ por HTTP a que el predio concilie")
class EscrituraDeDeclaracionJuradaJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 28);
    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);
    private static final LocalDate PLAZO = LocalDate.of(2026, 6, 30);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long vecina;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static DeclaracionJuradaRepositoryJdbc declaraciones;
    private static ConsultaDeConciliacion conciliacion;
    private static MockMvc mvc;

    private static int siguiente = 1;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("270201", "Municipalidad del circuito");
        vecina = crearMunicipalidad("270202", "Municipalidad vecina del circuito");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        declaraciones = new DeclaracionJuradaRepositoryJdbc(jdbc);

        sellarPlazo(E2026, PLAZO);

        DirectorioDeContribuyentes padron = new PadronJdbc();
        conciliacion =
                envolver(
                        new ConsultaDeConciliacion(
                                new GrillaDelEscenario(jdbc),
                                declaraciones,
                                new ConciliacionRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ));

        RegistrarDeclaracionJurada actos =
                envolver(
                        new RegistrarDeclaracionJurada(
                                declaraciones,
                                PlantillaDeNumeroDeDeclaracion.POR_OMISION,
                                envolver(
                                        new LectorDeParametrosSellados(
                                                new ParametrosRepositoryJdbc(jdbc))),
                                new FichasDeLaBase(),
                                padron,
                                new AuditoriaJdbc(jdbc, RELOJ)));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new DeclaracionJuradaController(
                                        envolver(
                                                new ConsultasDeRentas(
                                                        null, null, null, declaraciones)),
                                        actos,
                                        envolver(
                                                new ConsultaDeLaHojaDeDeclaracion(
                                                        declaraciones,
                                                        padron,
                                                        new PrediosDeLaHoja(),
                                                        new DeterminacionRepositoryJdbc(jdbc))),
                                        RELOJ))
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

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("cajero.rentas", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El acto que concilia (ADR-0015 §3)")
    class ElActoQueConcilia {

        @Test
        @DisplayName("presentar la DJ por el endpoint deja el predio CONCILIADO")
        void presentarDejaElPredioConciliado() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String contribuyente = nuevoContribuyente(municipalidad);

            assertThat(concilia(codigo))
                    .as("antes del acto, el predio esta fichado y fuera del padron afecto")
                    .isFalse();

            MvcResult respuesta = presentar(contribuyente, predio);

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("\"estado\":\"PRESENTADA\"")
                    .as("el numero lo pone el sistema, con la plantilla de D-09")
                    .containsPattern("\"numero\":\"DJ-2026-\\d{6}\"");

            assertThat(concilia(codigo))
                    .as(
                            "conciliar no es escribir un codigo en la ficha: es que exista una"
                                    + " declaracion del ejercicio sobre el predio (ADR-0015 §1)")
                    .isTrue();
        }

        @Test
        @DisplayName("y concilia el ejercicio que declara, no cualquiera (regla 9)")
        void conciliaSuEjercicio() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            presentar(nuevoContribuyente(municipalidad), predio);

            assertThat(concilia(codigo)).isTrue();
            assertThat(conciliaEn(codigo, new Ejercicio(2025)))
                    .as("el padron afecto se rehace cada ejercicio")
                    .isFalse();
        }

        @Test
        @DisplayName("observar la DJ NO retira el predio del padron afecto")
        void observarNoRetira() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String numero = numeroDe(presentar(nuevoContribuyente(municipalidad), predio));

            MvcResult respuesta =
                    acto(numero, "observacion", "El area declarada no cuadra con la ficha");

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("\"estado\":\"OBSERVADA\"");
            assertThat(concilia(codigo))
                    .as(
                            "la administracion objeto el CONTENIDO de una declaracion que existe;"
                                    + " negarle la conciliacion seria acusar de omiso a quien declaro")
                    .isTrue();
        }

        @Test
        @DisplayName("anular la DJ SI retira el predio del padron afecto")
        void anularRetira() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String numero = numeroDe(presentar(nuevoContribuyente(municipalidad), predio));
            assertThat(concilia(codigo)).isTrue();

            MvcResult respuesta =
                    acto(numero, "anulacion", "Se anula: el contribuyente presento dos veces");

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("\"estado\":\"ANULADA\"");
            assertThat(concilia(codigo))
                    .as("una anulada dejo de sustentar nada (ADR-0015 §1)")
                    .isFalse();
        }

        @Test
        @DisplayName(
                "la rectificatoria que cambia de predio mueve la conciliacion, y no la duplica")
        void laRectificatoriaMueveLaConciliacion() throws Exception {
            String codigoOriginal = nuevoCodigoCatastral();
            String codigoNuevo = nuevoCodigoCatastral();
            long original = crearPredioConFicha(municipalidad, codigoOriginal);
            long nuevo = crearPredioConFicha(municipalidad, codigoNuevo);
            String contribuyente = nuevoContribuyente(municipalidad);

            String numero = numeroDe(presentar(contribuyente, original));
            assertThat(concilia(codigoOriginal)).isTrue();
            assertThat(concilia(codigoNuevo)).isFalse();

            MvcResult respuesta =
                    mvc.perform(
                                    post(
                                                    "/rentas/api/v1/rentas/declaraciones/{djNro}/rectificacion",
                                                    numero)
                                            .param("ano", "2026")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"observacion\":\"Se declaro el predio"
                                                            + " equivocado\",\"predioId\":"
                                                            + nuevo
                                                            + ",\"fechaPresentacion\":\"2026-05-20\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("\"tipo\":\"RECTIFICATORIA\"");

            assertThat(concilia(codigoOriginal))
                    .as("el predio que se declaro por error deja de conciliar por esa cadena")
                    .isFalse();
            assertThat(concilia(codigoNuevo))
                    .as("y el que la rectificatoria declara pasa a conciliar")
                    .isTrue();
            assertThat(prediosConciliados(Set.of(original, nuevo)))
                    .as("ninguno de los dos sale dos veces, y el original no sale")
                    .containsExactly(nuevo);
        }

        @Test
        @DisplayName("la declaracion de la municipalidad vecina no concilia el predio de esta")
        void laDeclaracionDeLaVecinaNoConciliaAqui() throws Exception {
            String codigo = nuevoCodigoCatastral();
            crearPredioConFicha(municipalidad, codigo);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(vecina));
            long deLaVecina = crearPredioConFicha(vecina, codigo);
            sembrarDeclaracion(vecina, deLaVecina, codigo);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidad));

            assertThat(concilia(codigo))
                    .as("dos padrones con el mismo codigo, y cada uno con su respuesta (RLS)")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("La hoja resumen que se imprime y se firma (#563)")
    class LaHoja {

        @Test
        @DisplayName("consigna el declarante, sus predios y las cifras de la determinacion")
        void consignaLoQueLaHojaDice() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String contribuyente = nuevoContribuyente(municipalidad);
            long contribuyenteId = idDeContribuyente(contribuyente);
            PrediosDeLaHoja.del(contribuyenteId, predio, codigo, "CALLE SANTA ROSA 116");
            domicilioFiscal(contribuyenteId, "URB. SANTA ROSA - EL ALTO 116");
            String numero = numeroDe(presentar(contribuyente, predio));
            determinar(contribuyenteId, predio, "132196.75", "587.44");

            String hoja = hojaDe(numero);

            assertThat(hoja)
                    .as("el declarante sale del padron, no de la maqueta")
                    .contains("\"codigo\":\"" + contribuyente + "\"")
                    .contains("\"nombre\":\"TITULAR, CIRCUITO\"")
                    .contains("\"domicilioFiscal\":\"URB. SANTA ROSA - EL ALTO 116\"");
            assertThat(hoja)
                    .as("y sus predios de catastro, con su codigo y su direccion")
                    .contains("\"codRefCatastral\":\"" + codigo + "\"")
                    .contains("\"direccion\":\"CALLE SANTA ROSA 116\"");
            assertThat(hoja)
                    .as(
                            "las cifras son las de la determinacion del ejercicio: el sistema no"
                                    + " sabe valorizar un predio (#395), asi que el autovaluo se"
                                    + " declara y de ahi sale")
                    .contains("\"autovaluo\":\"132196.75\"")
                    .contains("\"valuoAfectoTotal\":\"132196.75\"")
                    .contains("\"impuestoInsoluto\":\"587.44\"");
            assertThat(hoja)
                    .as("y toda la hoja dice a que dia se leyo (regla 9)")
                    .contains("\"aLaFecha\":");
        }

        @Test
        @DisplayName("sin determinacion del ejercicio no inventa cifras: las nombra como ausentes")
        void sinDeterminacionNoInventaCifras() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String contribuyente = nuevoContribuyente(municipalidad);
            PrediosDeLaHoja.del(
                    idDeContribuyente(contribuyente), predio, codigo, "CALLE SIN CIFRAS 1");
            String numero = numeroDe(presentar(contribuyente, predio));

            String hoja = hojaDe(numero);

            assertThat(hoja)
                    .as(
                            "un cero se leeria como «no debe nada» en un papel que alguien firma,"
                                    + " y una celda en blanco tambien")
                    .contains("\"autovaluo\":null")
                    .contains("\"valuoAfectoTotal\":null")
                    .contains("\"impuestoInsoluto\":null");
            assertThat(hoja)
                    .as("y la hoja dice QUE falta y donde se resuelve")
                    .contains("No hay determinacion del impuesto predial del ejercicio 2026");
            assertThat(hoja)
                    .as("el predio sigue saliendo, con su % de titularidad: eso si es un dato")
                    .contains("\"codRefCatastral\":\"" + codigo + "\"");
        }

        @Test
        @DisplayName("el derecho de emision y el total a pagar no se publican, y se dice por que")
        void elDerechoDeEmisionNoSePublica() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String contribuyente = nuevoContribuyente(municipalidad);
            long contribuyenteId = idDeContribuyente(contribuyente);
            PrediosDeLaHoja.del(contribuyenteId, predio, codigo, "CALLE CON CIFRAS 2");
            String numero = numeroDe(presentar(contribuyente, predio));
            determinar(contribuyenteId, predio, "100000.00", "400.00");

            String hoja = hojaDe(numero);

            assertThat(hoja)
                    .as(
                            "la determinacion guarda la base y el impuesto, no el derecho: es una"
                                    + " cifra de ordenanza local y sumarla con un cero inventado es"
                                    + " lo que este issue existe para impedir")
                    .contains("DERECHO_EMISION_PREDIAL")
                    .doesNotContain("totalAPagar")
                    .doesNotContain("derechoDeEmision\":");
        }

        @Test
        @DisplayName("una DJ que no existe es 404, no una hoja vacia")
        void unaDeclaracionQueNoExisteEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/rentas/declaraciones/DJ-2026-999999/hoja")
                                            .param("ano", "2026"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("el domicilio es el VIGENTE a la fecha de corte, no el ultimo")
        void elDomicilioEsElVigenteALaFecha() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String contribuyente = nuevoContribuyente(municipalidad);
            long contribuyenteId = idDeContribuyente(contribuyente);
            PrediosDeLaHoja.del(contribuyenteId, predio, codigo, "CALLE QUE SE MUDA 3");
            domicilioFiscalConVigencia(
                    contribuyenteId, "DONDE VIVIA EN MARZO 1", "2026-01-01", "2026-08-31");
            domicilioFiscalConVigencia(
                    contribuyenteId, "DONDE VIVE DESDE SETIEMBRE 2", "2026-09-01", null);
            String numero = numeroDe(presentar(contribuyente, predio));

            assertThat(hojaDe(numero, "2026-03-15"))
                    .as(
                            "la hoja de una DJ de marzo tiene que poder reimprimirse como se"
                                    + " imprimio: resolver «el ultimo» la firmaria con la direccion"
                                    + " de setiembre")
                    .contains("DONDE VIVIA EN MARZO 1")
                    .doesNotContain("DONDE VIVE DESDE SETIEMBRE 2");
            assertThat(hojaDe(numero, "2026-09-15")).contains("DONDE VIVE DESDE SETIEMBRE 2");
        }
    }

    @Nested
    @DisplayName("Lo que el endpoint rechaza")
    class LoQueRechaza {

        @Test
        @DisplayName("sin observacion no se guarda: 422 (regla 10, RNF-052)")
        void sinObservacionNoSeGuarda() throws Exception {
            String contribuyente = nuevoContribuyente(municipalidad);

            MvcResult respuesta =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/declaraciones")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"ano\":\"2026\",\"codContribuyente\":\""
                                                            + contribuyente
                                                            + "\",\"tipo\":\"HR\",\"fechaPresentacion\":\"2026-03-01\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("observacion del usuario");
        }

        @Test
        @DisplayName("una rectificatoria no se presenta como declaracion nueva: 422")
        void unaRectificatoriaNoSePresentaComoNueva() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/declaraciones")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"observacion\":\"Intento de"
                                                            + " rectificatoria\",\"ano\":\"2026\",\"codContribuyente\":\""
                                                            + nuevoContribuyente(municipalidad)
                                                            + "\",\"tipo\":\"RECTIFICATORIA\",\"fechaPresentacion\":\"2026-03-01\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("rectificacion");
        }

        @Test
        @DisplayName("un ejercicio sellado sin el plazo responde 422 nombrando la llave")
        void sinPlazoParametrizadoNombraLaLlave() throws Exception {
            sellarSinPlazo(new Ejercicio(2033));

            MvcResult respuesta =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/declaraciones")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"observacion\":\"Declaracion de un ejercicio"
                                                            + " sin plazo\",\"ano\":\"2033\",\"codContribuyente\":\""
                                                            + nuevoContribuyente(municipalidad)
                                                            + "\",\"tipo\":\"HR\",\"fechaPresentacion\":\"2033-03-01\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .as(
                            "quien recibe el 422 en ventanilla no puede hacer nada con «falta un"
                                    + " parametro»: la llave es lo unico accionable (regla 5)")
                    .contains("PLAZO:DECLARACION_JURADA");
            assertThat(respuesta.getResponse().getContentAsString())
                    .as(
                            "#691 — y por programa: la interfaz reacciona a la presencia del"
                                    + " miembro, nunca al texto del mensaje")
                    .contains(
                            "\"parametroQueFalta\":{\"ejercicio\":2033,"
                                    + "\"llave\":\"PLAZO:DECLARACION_JURADA\"}");
        }

        @Test
        @DisplayName("un acto sobre una DJ anulada responde 409, no 422")
        void unActoSobreUnaAnuladaEsConflicto() throws Exception {
            String codigo = nuevoCodigoCatastral();
            long predio = crearPredioConFicha(municipalidad, codigo);
            String numero = numeroDe(presentar(nuevoContribuyente(municipalidad), predio));
            acto(numero, "anulacion", "Se anula la declaracion");

            MvcResult respuesta = acto(numero, "observacion", "Observando una anulada");

            assertThat(respuesta.getResponse().getStatus())
                    .as(
                            "la peticion es correcta; lo que no admite el acto es el estado, y la"
                                    + " interfaz distingue las dos cosas para saber si reintentar")
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("un acto sobre una DJ que no existe responde 404")
        void unActoSobreUnaDjInexistente() throws Exception {
            MvcResult respuesta = acto("DJ-2026-999999", "anulacion", "No deberia escribir nada");

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------

    private static MvcResult presentar(String contribuyente, long predioId) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/declaraciones")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"Declaracion presentada en"
                                                + " ventanilla\",\"ano\":\"2026\",\"codContribuyente\":\""
                                                + contribuyente
                                                + "\",\"tipo\":\"HR\",\"predioId\":"
                                                + predioId
                                                + ",\"fechaPresentacion\":\"2026-03-01\"}"))
                .andReturn();
    }

    private static String hojaDe(String numero) throws Exception {
        return hojaDe(numero, null);
    }

    private static String hojaDe(String numero, @org.jspecify.annotations.Nullable String fecha)
            throws Exception {
        var peticion =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/rentas/api/v1/rentas/declaraciones/{djNro}/hoja", numero)
                        .param("ano", "2026");
        if (fecha != null) {
            peticion = peticion.param("fecha", fecha);
        }
        return mvc.perform(peticion).andReturn().getResponse().getContentAsString();
    }

    private static long idDeContribuyente(String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT id FROM contribuyente WHERE codigo_contribuyente = ?")) {
                sentencia.setString(1, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        }
    }

    private static void domicilioFiscal(long contribuyenteId, String direccion)
            throws SQLException {
        domicilioFiscalConVigencia(contribuyenteId, direccion, "2026-01-01", null);
    }

    private static void domicilioFiscalConVigencia(
            long contribuyenteId,
            String direccion,
            String desde,
            @org.jspecify.annotations.Nullable String hasta)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO domicilio (municipalidad_id, contribuyente_id, tipo,"
                                    + " direccion, vigencia_desde, vigencia_hasta,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, 'FISCAL', ?, CAST(? AS date),"
                                    + "         CAST(? AS date), 'siembra')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, contribuyenteId);
                sentencia.setString(3, direccion);
                sentencia.setString(4, desde);
                sentencia.setString(5, hasta);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /**
     * Una determinacion predial del ejercicio, con su detalle por predio.
     *
     * <p>Se siembra por SQL y no por el caso de uso a proposito: lo que la hoja mide es que LEE la
     * determinacion, y hacerla pasar por el calculo obligaria a sellar un conjunto con el cuadro
     * entero — que es D-02a y no es lo que esta prueba examina.
     */
    private static void determinar(
            long contribuyenteId, long predioId, String autovaluo, String impuesto)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long determinacion;
            try (PreparedStatement cabecera =
                    app.prepareStatement(
                            "INSERT INTO determinacion (municipalidad_id, ejercicio, tributo,"
                                    + " contribuyente_id, conjunto_id, base_imponible,"
                                    + " monto_determinado, reglas_aplicadas, usuario_calculo)"
                                    + " SELECT ?, 2026, 'PREDIAL', ?, c.id, CAST(? AS dinero),"
                                    + "        CAST(? AS dinero), ARRAY['RT-011'], 'siembra'"
                                    + "   FROM conjunto_parametros_de_prueba c"
                                    + "  WHERE c.municipalidad_id = ? AND c.ejercicio = 2026"
                                    + "  ORDER BY c.id DESC LIMIT 1"
                                    + " RETURNING id")) {
                cabecera.setLong(1, municipalidad);
                cabecera.setLong(2, contribuyenteId);
                cabecera.setString(3, autovaluo);
                cabecera.setString(4, impuesto);
                cabecera.setLong(5, municipalidad);
                try (ResultSet fila = cabecera.executeQuery()) {
                    fila.next();
                    determinacion = fila.getLong(1);
                }
            }
            try (PreparedStatement detalle =
                    app.prepareStatement(
                            "INSERT INTO determinacion_predio_detalle (municipalidad_id,"
                                    + " ejercicio, determinacion_id, predio_id, autovaluo,"
                                    + " valuo_exonerado, porcentaje_propiedad,"
                                    + " base_imponible_predio)"
                                    + " VALUES (?, 2026, ?, ?, CAST(? AS dinero), 0, 100,"
                                    + "         CAST(? AS dinero))")) {
                detalle.setLong(1, municipalidad);
                detalle.setLong(2, determinacion);
                detalle.setLong(3, predioId);
                detalle.setString(4, autovaluo);
                detalle.setString(5, autovaluo);
                detalle.executeUpdate();
            }
            app.commit();
        }
    }

    private static MvcResult acto(String numero, String verbo, String observacion)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/declaraciones/{djNro}/{verbo}", numero, verbo)
                                .param("ano", "2026")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observacion\":\"" + observacion + "\"}"))
                .andReturn();
    }

    private static String numeroDe(MvcResult respuesta) throws Exception {
        String cuerpo = respuesta.getResponse().getContentAsString();
        java.util.regex.Matcher marca =
                java.util.regex.Pattern.compile("\"numero\":\"([^\"]+)\"").matcher(cuerpo);
        assertThat(marca.find()).as("la respuesta trae el numero que puso el sistema").isTrue();
        return marca.group(1);
    }

    private static boolean concilia(String codigo) {
        return conciliaEn(codigo, E2026);
    }

    private static boolean conciliaEn(String codigo, Ejercicio ejercicio) {
        Pagina<FichaConciliada> pagina =
                conciliacion.todas(
                        new BusquedaDeFichas(codigo, null, null, null, null),
                        ejercicio,
                        HOY,
                        new Paginacion(0, 20, "codRefCatastral", Paginacion.Direccion.ASCENDENTE));
        assertThat(pagina.contenido()).as("una ficha por predio en la grilla").hasSize(1);
        return pagina.contenido().get(0).conciliada();
    }

    private static Set<Long> prediosConciliados(Set<Long> predios) {
        Set<Long> resultado =
                transaccion.execute(
                        estado -> declaraciones.prediosConDeclaracionVigente(predios, E2026));
        return resultado == null ? Set.of() : resultado;
    }

    /** El puerto de fichas de catastro, resuelto contra la misma base y en la misma transaccion. */
    private static final class FichasDeLaBase implements LectorDeFichas {

        @Override
        public Optional<kamayuk.rentas.dominio.AreaM2> areaDeLaVersion(long fichaId) {
            throw new UnsupportedOperationException("este circuito no lee superficies");
        }

        @Override
        public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
            return jdbc.sql(
                            "SELECT id FROM ficha_catastral_de_prueba WHERE predio_id = :predioId AND"
                                    + " vigencia_desde <= :fecha AND (vigencia_hasta IS NULL OR"
                                    + " vigencia_hasta >= :fecha)")
                    .param("predioId", predioId)
                    .param("fecha", fecha)
                    .query(Long.class)
                    .optional();
        }
    }

    /** El padron de contribuyentes contra la misma base: lo que la grilla y el acto resuelven. */
    private static final class PadronJdbc implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return jdbc.sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social FROM"
                                    + " contribuyente WHERE codigo_contribuyente = :codigo")
                    .param("codigo", codigo)
                    .query(
                            (fila, numero) ->
                                    new ResumenDeContribuyente(
                                            fila.getLong("id"),
                                            fila.getString("codigo_contribuyente"),
                                            fila.getString("nombre_razon_social"),
                                            "DNI 00000000"))
                    .optional();
        }

        /** Lee la base de verdad: la hoja de #563 resuelve por aqui quien declara. */
        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            if (ids.isEmpty()) {
                return Map.of();
            }
            Map<Long, ResumenDeContribuyente> encontrados = new java.util.LinkedHashMap<>();
            jdbc.sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social,"
                                    + " tipo_documento, numero_documento"
                                    + "  FROM contribuyente WHERE id = ANY(:ids)")
                    .param("ids", ids.toArray(Long[]::new))
                    .query(
                            (fila, numero) ->
                                    new ResumenDeContribuyente(
                                            fila.getLong("id"),
                                            fila.getString("codigo_contribuyente"),
                                            fila.getString("nombre_razon_social"),
                                            fila.getString("tipo_documento")
                                                    + " "
                                                    + fila.getString("numero_documento")))
                    .list()
                    .forEach(quien -> encontrados.put(quien.id(), quien));
            return encontrados;
        }

        /** El VIGENTE a la fecha, no el ultimo: la hoja de marzo se reimprime como se imprimio. */
        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return jdbc.sql(
                            "SELECT direccion FROM domicilio"
                                    + " WHERE contribuyente_id = :contribuyente"
                                    + "   AND tipo = 'FISCAL'"
                                    + "   AND vigencia_desde <= :fecha"
                                    + "   AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha)"
                                    + " ORDER BY vigencia_desde DESC LIMIT 1")
                    .param("contribuyente", contribuyenteId)
                    .param("fecha", fecha)
                    .query(String.class)
                    .optional();
        }
    }

    // ---------- Siembra ----------

    private static void sellarPlazo(Ejercicio ejercicio, LocalDate plazo) throws SQLException {
        long parametro = parametroDePlazo(ejercicio, plazo);
        administrar(
                true,
                administrador -> {
                    ConjuntoDeParametros conjunto =
                            administrador.abrirVersion(
                                    ejercicio, Observacion.de("Se abre el ejercicio del circuito"));
                    administrador.agregarParametro(
                            conjunto.id(),
                            parametro,
                            Observacion.de("Se incorpora el plazo de declaracion jurada"));
                    administrador.sellar(
                            conjunto.id(), Observacion.de("Se sella para poder registrar"));
                });
    }

    private static void sellarSinPlazo(Ejercicio ejercicio) throws SQLException {
        long otro = parametroFicticio(ejercicio);
        administrar(
                false,
                administrador -> {
                    ConjuntoDeParametros conjunto =
                            administrador.abrirVersion(
                                    ejercicio,
                                    Observacion.de("Se abre el ejercicio sin el plazo de DJ"));
                    administrador.agregarParametro(
                            conjunto.id(),
                            otro,
                            Observacion.de("Un parametro que no es el plazo de DJ"));
                    administrador.sellar(
                            conjunto.id(),
                            Observacion.de("Se sella, deliberadamente sin el plazo de DJ"));
                });
    }

    private interface ConParametros {
        void hacer(AdministrarParametros administrador);
    }

    /**
     * @param fijaSuContexto {@code true} cuando se llama desde {@code @BeforeAll}, que corre sin
     *     contexto ambiente; {@code false} desde un test, donde lo fijo {@code @BeforeEach} y
     *     limpiarlo aqui dejaria al test sin el en la linea siguiente.
     */
    private static void administrar(boolean fijaSuContexto, ConParametros accion) {
        AdministrarParametros administrador =
                envolver(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ));
        if (fijaSuContexto) {
            TenantContext.fijar(new MunicipalidadId(municipalidad));
            OrigenContext.fijar(new Origen("carga.parametros", null, null));
        }
        try {
            accion.hacer(administrador);
        } finally {
            if (fijaSuContexto) {
                TenantContext.limpiar();
                OrigenContext.limpiar();
            }
        }
    }

    private static long parametroDePlazo(Ejercicio ejercicio, LocalDate plazo) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL, 'PLAZO',"
                                        + " 'DECLARACION_JURADA', ?, ?, 'Plazo ficticio de prueba;"
                                        + " no representa ninguna ordenanza', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, plazo.toString());
            sentencia.setObject(2, LocalDate.of(ejercicio.valor(), 1, 1));
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long parametroFicticio(Ejercicio ejercicio) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL,"
                                        + " 'FICTICIO', ?, 1.000000, DATE '2026-01-01', 'Valor"
                                        + " ficticio de prueba; no representa ninguna norma',"
                                        + " 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, "SIN_PLAZO_CIRCUITO_" + ejercicio.valor());
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static synchronized String nuevoCodigoCatastral() {
        return String.format("270201001001001%08d", siguiente++);
    }

    private static synchronized String nuevoContribuyente(long muni) throws SQLException {
        String codigo = String.format("C-%06d", siguiente++);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, CIRCUITO',"
                                    + " 'siembra')")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("7%07d", siguiente));
                sentencia.executeUpdate();
                app.commit();
            }
        }
        return codigo;
    }

    private static long crearPredioConFicha(long muni, String codigo) throws SQLException {
        long predio = crearPredio(muni, codigo);
        crearFicha(muni, predio);
        return predio;
    }

    private static long crearPredio(long muni, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, "AV. CIRCUITO " + codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void crearFicha(long muni, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, ?, 'CASA HABITACION', ?,"
                                    + " 'DECLARACION_JURADA', 'DJ-SIEMBRA', 'Siembra del"
                                    + " circuito', 'prueba')")) {
                sentencia.setLong(1, muni);
                sentencia.setLong(2, predioId);
                sentencia.setBigDecimal(3, new BigDecimal("120.00"));
                sentencia.setObject(4, ALTA);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** Una DJ sembrada por SQL en otra municipalidad: aqui no se prueba su acto, sino la RLS. */
    private static void sembrarDeclaracion(long muni, long predioId, String sufijo)
            throws SQLException {
        String contribuyente = nuevoContribuyente(muni);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                                    + " contribuyente_id, tipo, predio_id, fecha_presentacion,"
                                    + " fecha_limite, usuario_registro, observacion) SELECT ?, ?,"
                                    + " 2026, c.id, 'HR', ?, DATE '2026-03-01', DATE '2026-06-30',"
                                    + " 'siembra', 'Declaracion de la vecina' FROM contribuyente c"
                                    + " WHERE c.codigo_contribuyente = ?")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, "VEC-" + sufijo.substring(sufijo.length() - 8));
                sentencia.setLong(3, predioId);
                sentencia.setString(4, contribuyente);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /**
     * Los predios del contribuyente, en memoria.
     *
     * <p>Es un puerto de <b>catastro</b> y este archivo mide lo que rentas guarda: la DJ y la
     * determinacion se leen de PostgreSQL de verdad, y de catastro se toma lo que la hoja necesita
     * —el codigo, la direccion y el % de titularidad— sin arrastrar su esquema a esta prueba. Que
     * la lectura de catastro funcione lo mide {@code PrediosDelContribuyenteCatastroTest}.
     */
    private static final class PrediosDeLaHoja
            implements kamayuk.rentas.catastro.PrediosDelContribuyente {

        private static final Map<Long, List<kamayuk.rentas.catastro.PredioDelContribuyente>> SUYOS =
                new java.util.LinkedHashMap<>();

        static void del(long contribuyenteId, long predioId, String codigo, String direccion) {
            SUYOS.computeIfAbsent(contribuyenteId, quien -> new java.util.ArrayList<>())
                    .add(
                            new kamayuk.rentas.catastro.PredioDelContribuyente(
                                    predioId,
                                    codigo,
                                    "URBANO",
                                    direccion,
                                    kamayuk.rentas.dominio.Porcentaje.total()));
        }

        static void limpiar() {
            SUYOS.clear();
        }

        @Override
        public List<kamayuk.rentas.catastro.PredioDelContribuyente> de(
                long contribuyenteId, LocalDate fecha) {
            return List.copyOf(SUYOS.getOrDefault(contribuyenteId, List.of()));
        }
    }
}
