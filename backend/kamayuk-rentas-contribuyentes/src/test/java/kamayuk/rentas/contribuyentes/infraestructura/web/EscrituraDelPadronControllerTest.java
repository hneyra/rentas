package kamayuk.rentas.contribuyentes.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.ActualizarFicha;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDeLaFichaDelContribuyente;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDelPadron;
import kamayuk.rentas.contribuyentes.aplicacion.RegistrarContribuyente;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * El padron, escrito por HTTP y hasta PostgreSQL, sin un doble por el camino (#488).
 *
 * <h2>Por que va hasta la base y no contra dobles</h2>
 *
 * <p>Porque lo que este issue tiene que demostrar no se puede demostrar de otro modo. La mudanza
 * <b>cierra el domicilio anterior</b>, y lo que impide que queden dos abiertos es un indice parcial
 * de PostgreSQL ({@code domicilio_fiscal_vigente_uq}): un doble del repositorio guardaria los dos
 * tan contento. Y el aislamiento entre municipalidades lo sostiene RLS, que un doble tampoco tiene.
 *
 * <p>Y por lo que ya enseño #486: entre las pruebas de repositorio —que hablan con PostgreSQL desde
 * dentro de una transaccion que abre la prueba— y las de capa web —que llegan por HTTP contra un
 * doble— queda sin cubrir justo el trozo que falla en produccion. El proxy transaccional se
 * construye con {@link AnnotationTransactionAttributeSource}, obedeciendo a la anotacion como el
 * contenedor: si un caso de uso deja de declarar {@code @Transactional}, aqui se cae.
 *
 * <p>La conexion es la de {@code kamayuk_app}. Un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}, asi que una prueba escrita sobre el no verificaria ningun aislamiento.
 */
@DisplayName("RF-013…016 — El padron se escribe: alta, mudanza, contactos y responsables (#488)")
class EscrituraDelPadronControllerTest {

    /** Congelado dentro de una particion declarada de {@code auditoria} (2026). */
    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 8, 30).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    private static final String ATIENDE = "registrador.ventanilla";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    /** Lo que el comprobador concede. Se cambia por prueba para medir el 403. */
    private static final List<Privilegio> CONCEDIDOS = new ArrayList<>();

    /** Un contribuyente de la municipalidad vecina, para la prueba de aislamiento. */
    private static long ajeno;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("230101", "Municipalidad que escribe");
        municipalidadB = crearMunicipalidad("230102", "Municipalidad vecina");
        ajeno = sembrar(municipalidadB, "V-0001", "45000001", "VECINA AJENA, PERSONA");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        mvc = montar(new FichaRepositoryJdbc(jdbc));
    }

    /**
     * Los dos controladores sobre la base de verdad, con el repositorio de la ficha que se pida.
     *
     * <p>Es un parametro por una sola prueba, la de la carrera de #420: necesita un repositorio que
     * deje ganar a otra mudanza entre la lectura del tramo abierto y su cierre.
     */
    private static MockMvc montar(FichaRepositoryJdbc fichas) {
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        ContribuyenteRepositoryJdbc padron = new ContribuyenteRepositoryJdbc(jdbc);

        ComprobadorDeAcceso comprobador =
                (usuario, acceso, privilegio, fecha) -> CONCEDIDOS.contains(privilegio);

        return MockMvcBuilders.standaloneSetup(
                        new ContribuyenteController(
                                envolver(new ConsultaDelPadron(padron), gestor),
                                envolver(new RegistrarContribuyente(padron, auditoria), gestor),
                                comprobador,
                                RELOJ),
                        new FichaDelContribuyenteController(
                                envolver(
                                        new ConsultaDeLaFichaDelContribuyente(padron, fichas),
                                        gestor),
                                envolver(new ActualizarFicha(fichas, auditoria), gestor),
                                comprobador,
                                RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen(ATIENDE, "PC-11", "10.0.0.11"));
        CONCEDIDOS.clear();
        CONCEDIDOS.addAll(List.of(Privilegio.values()));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ── El alta ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("El alta del contribuyente")
    class Alta {

        @Test
        @DisplayName("se da de alta por HTTP y aparece en la grilla del padron")
        void seDaDeAltaYAparece() throws Exception {
            String cuerpo = alta("C-0100", "40100100", "CHUNGA PANTA, ROSA ELENA");

            MvcResult creado = enviar(post("/rentas/api/v1/rentas/contribuyentes"), cuerpo);

            assertThat(creado.getResponse().getStatus()).isEqualTo(201);
            assertThat(creado.getResponse().getContentAsString()).contains("CHUNGA PANTA");

            MvcResult grilla =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("codigo", "C-0100"))
                            .andReturn();
            assertThat(grilla.getResponse().getContentAsString())
                    .as("una municipalidad recien implantada tiene que poder ver a quien registro")
                    .contains("CHUNGA PANTA");
        }

        @Test
        @DisplayName("sin observacion no se guarda: 422 diciendo que falta")
        void sinObservacionNoSeGuarda() throws Exception {
            String cuerpo =
                    """
                    {"codigo":"C-0199","tipoDocumento":"DNI","numeroDocumento":"40100199",
                     "tipoPersona":"NATURAL","nombreRazonSocial":"SIN OBSERVACION, NADIE"}
                    """;

            MvcResult rechazado = enviar(post("/rentas/api/v1/rentas/contribuyentes"), cuerpo);

            assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
            assertThat(rechazado.getResponse().getContentAsString())
                    .contains("observacion del usuario");
            assertThat(cuantosHay("C-0199")).as("y no se guardo nada (regla 10)").isZero();
        }

        @Test
        @DisplayName("el codigo repetido es 409, y dice cual de los dos se repitio")
        void codigoRepetido() throws Exception {
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes"),
                    alta("C-0101", "40100101", "UNO, UNO"));

            MvcResult choque =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes"),
                            alta("C-0101", "40100102", "DOS, DOS"));

            assertThat(choque.getResponse().getStatus()).isEqualTo(409);
            assertThat(choque.getResponse().getContentAsString()).contains("C-0101");
        }

        @Test
        @DisplayName("el documento repetido es 409 y NO dice con quien")
        void documentoRepetido() throws Exception {
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes"),
                    alta("C-0102", "40100103", "TRES, TRES"));

            MvcResult choque =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes"),
                            alta("C-0103", "40100103", "CUATRO, CUATRO"));

            assertThat(choque.getResponse().getStatus()).isEqualTo(409);
            assertThat(choque.getResponse().getContentAsString())
                    .as(
                            "decir con quien choca convierte el alta en un buscador de personas por"
                                    + " documento para quien no puede leer el padron")
                    .doesNotContain("TRES");
        }

        @Test
        @DisplayName("sin el privilegio de REGISTRO, 403")
        void sinPrivilegioDeRegistro() throws Exception {
            // El guardia real no corre en `standaloneSetup`; lo que se mide aqui es que la
            // anotacion del metodo pida REGISTRO y no herede la LECTURA de la clase.
            assertThat(privilegioDe("registrar")).isEqualTo(Privilegio.REGISTRO);
        }

        @Test
        @DisplayName("el GET del padron sigue exigiendo LECTURA, no la escritura de la clase")
        void elGetNoHeredaLaEscritura() throws Exception {
            assertThat(
                            ContribuyenteController.class
                                    .getAnnotation(kamayuk.rentas.autorizacion.RequiereAcceso.class)
                                    .privilegio())
                    .as(
                            "#431: una anotacion de clase con privilegio de escritura se la come"
                                    + " tambien el GET, y quien solo consulta el padron deja de"
                                    + " poder abrirlo")
                    .isEqualTo(Privilegio.LECTURA);
            assertThat(
                            ContribuyenteController.class
                                    .getMethod(
                                            "buscar",
                                            String.class,
                                            String.class,
                                            String.class,
                                            String.class,
                                            kamayuk.rentas.web.ParametrosDePaginacion.class)
                                    .isAnnotationPresent(
                                            kamayuk.rentas.autorizacion.RequiereAcceso.class))
                    .as("el GET no declara ninguno propio: hereda el LECTURA de la clase")
                    .isFalse();
        }
    }

    // ── La correccion y la baja ────────────────────────────────────────

    @Nested
    @DisplayName("La correccion y la baja")
    class Correccion {

        @Test
        @DisplayName("corrige el nombre y conserva lo que no vino")
        void corrigeElNombre() throws Exception {
            long id = altaDe("C-0200", "40100200", "MAL ESCRITO, NOMBRE");

            MvcResult corregido =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/" + id),
                            """
                            {"observacion":"Corrige el nombre segun DNI",
                             "nombreRazonSocial":"BIEN ESCRITO, NOMBRE"}
                            """);

            assertThat(corregido.getResponse().getStatus()).isEqualTo(200);
            String cuerpo = corregido.getResponse().getContentAsString();
            assertThat(cuerpo).contains("BIEN ESCRITO");
            assertThat(cuerpo)
                    .as("el codigo y el documento no se tocan: son la identidad")
                    .contains("C-0200")
                    .contains("40100200");
            assertThat(cuerpo).contains("\"activo\":true");
        }

        @Test
        @DisplayName("corrige los tres datos personales que el expediente dibujaba y no guardaba")
        void corrigeLosDatosPersonales() throws Exception {
            long conyuge = altaDe("C-0210", "40100210", "CONYUGE, LA");
            long id = altaDe("C-0211", "40100211", "SIN FECHA, ALGUIEN");

            MvcResult corregido =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/" + id),
                            """
                            {"observacion":"Corrige los datos personales segun DNI",
                             "fechaNacimiento":"1958-03-14",
                             "estadoCivil":"CASADO",
                             "conyugeId":"""
                                    + conyuge
                                    + "}");

            assertThat(corregido.getResponse().getStatus())
                    .as("respuesta: %s", corregido.getResponse().getContentAsString())
                    .isEqualTo(200);

            String ficha = fichaDe(id);
            assertThat(ficha)
                    .as(
                            "hasta #552 esta escritura los copiaba de la fila existente: la"
                                    + " pantalla los dibujaba editables y no viajaban")
                    .contains("\"fechaNacimiento\":\"1958-03-14\"")
                    .contains("\"estadoCivil\":\"CASADO\"")
                    .contains("\"conyugeId\":" + conyuge);
        }

        @Test
        @DisplayName("la ficha los publica y la grilla no: la grilla es una busqueda")
        void laGrillaNoPublicaLosDatosPersonales() throws Exception {
            altaDe("C-0212", "40100212", "EN LA GRILLA, ALGUIEN");

            MvcResult grilla =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/rentas/contribuyentes")
                                            .param("codigo", "C-0212"))
                            .andReturn();

            assertThat(grilla.getResponse().getContentAsString())
                    .as(
                            "«lo que no se publica no se filtra»: la fila de una busqueda no"
                                    + " necesita los datos personales para hacer su trabajo")
                    .doesNotContain("fechaNacimiento")
                    .doesNotContain("estadoCivil")
                    .doesNotContain("conyugeId");
        }

        @Test
        @DisplayName("nadie es su propio conyuge")
        void nadieEsSuPropioConyuge() throws Exception {
            long id = altaDe("C-0213", "40100213", "SOLO, ALGUIEN");

            MvcResult resultado =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/" + id),
                            """
                            {"observacion":"Intento de sociedad conyugal de uno",
                             "conyugeId":"""
                                    + id
                                    + "}");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("conyuge");
        }

        /**
         * #422 — Un conyuge que no esta en el padron es 404, y no el 500 de la clave foranea.
         *
         * <p>Hasta #422 {@code conyugeDe} solo rechazaba el propio identificador: cualquier otro
         * numero llegaba al {@code UPDATE} y {@code contribuyente_conyuge_fk} lo rechazaba, que el
         * manejador contesta como averia del servidor con su incidencia ERROR. Las dos siembras que
         * la muestra de siempre no usa: un identificador que no existe en ninguna parte, y el de un
         * contribuyente que <b>si</b> existe pero en la municipalidad vecina — la clave es {@code
         * (municipalidad_id, conyuge_id)}, asi que para esta municipalidad tampoco existe.
         */
        @Test
        @DisplayName("#422 — un conyuge que no esta en el padron es 404, sin incidencia ERROR")
        void unConyugeQueNoEstaEnElPadronEs404() throws Exception {
            long id = altaDe("C-0215", "40100215", "SIN CONYUGE, ALGUIEN");

            for (long conyuge : new long[] {999_999L, ajeno}) {
                Rechazo rechazo =
                        rechazo(
                                () ->
                                        enviar(
                                                put("/rentas/api/v1/rentas/contribuyentes/" + id),
                                                """
                                                {"observacion":"Declara su sociedad conyugal",
                                                 "conyugeId":"""
                                                        + conyuge
                                                        + "}"));

                assertThat(rechazo.estado())
                        .as("el conyuge %s no esta en el padron de esta municipalidad", conyuge)
                        .isEqualTo(404);
                assertThat(rechazo.cuerpo())
                        .contains(String.valueOf(conyuge))
                        .doesNotContain("contribuyente_conyuge_fk")
                        .doesNotContain("incidencia");
                assertThat(rechazo.errores())
                        .as("un rechazo del usuario no es una incidencia del servidor")
                        .isEmpty();
            }
            assertThat(fichaDe(id))
                    .as("y la ficha sigue sin conyuge: no se guardo nada")
                    .contains("\"conyugeId\":null");
        }

        @Test
        @DisplayName("la cadena vacia borra el estado civil; la ausencia lo conserva")
        void laCadenaVaciaBorraYLaAusenciaConserva() throws Exception {
            long id = altaDe("C-0214", "40100214", "CAMBIA DE ESTADO, ALGUIEN");
            enviar(
                    put("/rentas/api/v1/rentas/contribuyentes/" + id),
                    """
                    {"observacion":"Declara su estado civil","estadoCivil":"SOLTERO"}
                    """);

            enviar(
                    put("/rentas/api/v1/rentas/contribuyentes/" + id),
                    """
                    {"observacion":"Corrige el nombre y nada mas",
                     "nombreRazonSocial":"CAMBIA DE ESTADO, OTRO"}
                    """);
            assertThat(fichaDe(id))
                    .as("lo que no viene, no cambia")
                    .contains("\"estadoCivil\":\"SOLTERO\"");

            enviar(
                    put("/rentas/api/v1/rentas/contribuyentes/" + id),
                    """
                    {"observacion":"Se retira el estado civil declarado por error",
                     "estadoCivil":""}
                    """);
            assertThat(fichaDe(id))
                    .as("la cadena vacia es una instruccion, no una omision")
                    .contains("\"estadoCivil\":null");
        }

        @Test
        @DisplayName("la baja no borra: la fila sigue, con activo en falso")
        void laBajaNoBorra() throws Exception {
            long id = altaDe("C-0201", "40100201", "SE DA DE BAJA, ALGUIEN");

            MvcResult baja =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/" + id),
                            """
                            {"observacion":"Baja por duplicidad detectada","activo":false}
                            """);

            assertThat(baja.getResponse().getStatus()).isEqualTo(200);
            assertThat(baja.getResponse().getContentAsString()).contains("\"activo\":false");
            assertThat(cuantosHay("C-0201")).as("nada se borra (RNF-051)").isEqualTo(1);
        }

        @Test
        @DisplayName("sin ELIMINACION la baja se niega, y la fila sigue activa")
        void laBajaExigeEliminacion() throws Exception {
            long id = altaDe("C-0202", "40100202", "NO SE PUEDE BAJAR, NADIE");
            CONCEDIDOS.remove(Privilegio.ELIMINACION);

            MvcResult negada =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/" + id),
                            """
                            {"observacion":"Intento de baja sin privilegio","activo":false}
                            """);

            assertThat(negada.getResponse().getStatus()).isEqualTo(403);
            assertThat(activo("C-0202")).isTrue();
        }

        @Test
        @DisplayName("un identificador que no existe es 404, no un alta encubierta")
        void inexistenteEs404() throws Exception {
            MvcResult respuesta =
                    enviar(
                            put("/rentas/api/v1/rentas/contribuyentes/999999"),
                            """
                            {"observacion":"Da igual","nombreRazonSocial":"FANTASMA, EL"}
                            """);

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(404);
        }
    }

    // ── La mudanza ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("La mudanza")
    class Mudanza {

        @Test
        @DisplayName("mudar cierra el domicilio anterior: nunca quedan dos vigentes")
        void mudarCierraElAnterior() throws Exception {
            long id = altaDe("C-0300", "40100300", "SE MUDA, PERSONA");

            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. GRAU 100", "2026-01-01"));
            MvcResult segunda =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                            domicilio("JR. LIMA 250", "2026-07-01"));

            assertThat(segunda.getResponse().getStatus()).isEqualTo(201);
            assertThat(domiciliosAbiertos(id))
                    .as(
                            "no cerrar el anterior deja dos domicilios fiscales abiertos, y una"
                                    + " emision que corriera en ese instante notificaria mal (#24). El"
                                    + " indice domicilio_fiscal_vigente_uq lo impide en la base; esto"
                                    + " comprueba que el codigo no depende de que salte")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("el anterior se cierra el DIA ANTES, no el mismo dia")
        void seCierraElDiaAntes() throws Exception {
            long id = altaDe("C-0301", "40100301", "DOS TRAMOS, PERSONA");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. GRAU 100", "2026-01-01"));
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("JR. LIMA 250", "2026-07-01"));

            assertThat(vigenciaHastaDe(id, "AV. GRAU 100"))
                    .as(
                            "si los dos rigieran el mismo dia, preguntar donde vivia ese dia"
                                    + " tendria dos respuestas")
                    .isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("la ficha da el domicilio VIGENTE A LA FECHA, no el ultimo")
        void elDomicilioSaleVigenteALaFecha() throws Exception {
            long id = altaDe("C-0302", "40100302", "SE CONSULTA, PERSONA");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. GRAU 100", "2026-01-01"));
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("JR. LIMA 250", "2026-07-01"));

            MvcResult enMarzo =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes/" + id + "/ficha")
                                            .param("fecha", "2026-03-15"))
                            .andReturn();
            MvcResult hoy =
                    mvc.perform(get("/rentas/api/v1/rentas/contribuyentes/" + id + "/ficha"))
                            .andReturn();

            // Se mira el CAMPO `domicilioFiscal`, no el cuerpo entero: el historial trae las dos
            // direcciones, asi que buscar «AV. GRAU 100» en todo el JSON pasa en verde aunque el
            // campo diga la de setiembre. Medido: resolver «la ultima» en vez de «la vigente a la
            // fecha» dejaba esta prueba VERDE hasta que se acoto la asercion.
            assertThat(direccionFiscalDe(enMarzo))
                    .as(
                            "reimprimir en 2029 la ficha con que se atendio en marzo tiene que dar"
                                    + " la direccion de marzo; con «la ultima», el documento no"
                                    + " explicaria la notificacion que se hizo (#24, regla 9)")
                    .isEqualTo("AV. GRAU 100");
            assertThat(direccionFiscalDe(hoy)).isEqualTo("JR. LIMA 250");
        }

        @Test
        @DisplayName("el historial conserva los dos tramos: nada se borra")
        void elHistorialConservaLosDos() throws Exception {
            long id = altaDe("C-0303", "40100303", "CON HISTORIAL, PERSONA");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. GRAU 100", "2026-01-01"));
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("JR. LIMA 250", "2026-07-01"));

            MvcResult ficha =
                    mvc.perform(get("/rentas/api/v1/rentas/contribuyentes/" + id + "/ficha"))
                            .andReturn();

            assertThat(ficha.getResponse().getContentAsString())
                    .contains("AV. GRAU 100")
                    .contains("JR. LIMA 250");
        }

        @Test
        @DisplayName("sin observacion no se muda, y sin documento de origen tampoco")
        void loQueFaltaSeDice() throws Exception {
            long id = altaDe("C-0304", "40100304", "NO SE MUDA, PERSONA");

            MvcResult sinObservacion =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                            """
                            {"tipo":"FISCAL","direccion":"AV. SIN NADA 1",
                             "documentoOrigen":"DJ-1"}
                            """);
            MvcResult sinDocumento =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                            """
                            {"observacion":"Muda sin el documento que lo sustenta","tipo":"FISCAL",
                             "direccion":"AV. SIN NADA 1"}
                            """);

            assertThat(sinObservacion.getResponse().getStatus()).isEqualTo(422);
            assertThat(sinDocumento.getResponse().getStatus()).isEqualTo(422);
            assertThat(sinDocumento.getResponse().getContentAsString())
                    .as("el documento de origen es lo que sostiene la notificacion si la impugnan")
                    .contains("documentoOrigen");
            assertThat(domiciliosAbiertos(id)).isZero();
        }

        @Test
        @DisplayName("colgar un domicilio de alguien que no existe es 404, no un 500 de la base")
        void mudarAQuienNoExisteEs404() throws Exception {
            MvcResult respuesta =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/999999/domicilios"),
                            domicilio("AV. FANTASMA 1", "2026-01-01"));

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(404);
        }
    }

    // ── La mudanza fuera de orden (#420) ───────────────────────────────

    /**
     * #420 — Una mudanza solo se anade al final del historial.
     *
     * <p>Todas las pruebas de arriba mudan con fechas crecientes —enero y despues julio—, que es
     * justo la muestra en la que el defecto no se ve. La mudanza buscaba el domicilio que <b>rige
     * en</b> {@code vigenciaDesde}, no el que esta abierto, y con una fecha anterior al tramo
     * abierto se estrellaba: contra {@code domicilio_fiscal_vigente_uq} si no regia ninguno (500),
     * contra {@code Domicilio.cerradoEl} si regia uno ya cerrado (500), y en el PROCESAL, que no
     * tiene indice, contestaba 201 y dejaba dos tramos abiertos.
     *
     * <p>Las siembras son las tres que distinguen: un tramo abierto y una fecha anterior a el; un
     * historial A cerrado y B abierto con la fecha dentro de A; y el mismo caso del primero en el
     * PROCESAL. En las tres se mira el estado, que el registro no tenga ninguna linea ERROR y
     * <b>cuantos tramos abiertos quedan en la base</b>, por tipo.
     */
    @Nested
    @DisplayName("#420 — La mudanza solo se anade al final del historial")
    class MudanzaFueraDeOrden {

        @Test
        @DisplayName("FISCAL abierto desde junio y mudanza a marzo: 422, y sigue uno abierto")
        void unFiscalAnteriorAlAbiertoEs422() throws Exception {
            long id = altaDe("C-0310", "40100310", "MUDA HACIA ATRAS, FISCAL");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. JUNIO 600", "2026-06-01"));

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(
                                                    "/rentas/api/v1/rentas/contribuyentes/"
                                                            + id
                                                            + "/domicilios"),
                                            domicilio("AV. MARZO 300", "2026-03-01")));

            assertThat(rechazo.estado())
                    .as(
                            "no rige ninguno en marzo, asi que no se cerraba nada y el segundo"
                                    + " FISCAL abierto chocaba con domicilio_fiscal_vigente_uq: 500")
                    .isEqualTo(422);
            assertThat(rechazo.cuerpo())
                    .as("el mensaje dice la regla y el tramo que la pone")
                    .contains("solo se anade al final")
                    .contains("2026-06-01")
                    .doesNotContain("domicilio_fiscal_vigente_uq")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores())
                    .as("un rechazo del usuario no es una incidencia del servidor")
                    .isEmpty();
            assertThat(abiertos(id, "FISCAL")).isEqualTo(1);
            assertThat(tramos(id)).as("y no se escribio nada").isEqualTo(1);
        }

        @Test
        @DisplayName("A cerrado, B abierto y mudanza dentro de A: 422, y el historial no cambia")
        void unaFechaDentroDeUnTramoCerradoEs422() throws Exception {
            long id = altaDe("C-0311", "40100311", "MUDA DENTRO DE A, FISCAL");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("CALLE A 1", "2020-01-01"));
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("CALLE B 2", "2026-06-01"));
            assertThat(vigenciaHastaDe(id, "CALLE A 1"))
                    .as("la siembra: A cerrado el dia antes de B")
                    .isEqualTo(LocalDate.of(2026, 5, 31));

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(
                                                    "/rentas/api/v1/rentas/contribuyentes/"
                                                            + id
                                                            + "/domicilios"),
                                            domicilio("CALLE C 3", "2026-03-01")));

            assertThat(rechazo.estado())
                    .as(
                            "en marzo rige A, que ya esta cerrado: cerradoEl lanzaba"
                                    + " IllegalStateException y nadie la traducia (500)")
                    .isEqualTo(422);
            assertThat(rechazo.cuerpo())
                    .as("el limite es el tramo ABIERTO, B, y no el que rige en la fecha pedida")
                    .contains("solo se anade al final")
                    .contains("2026-06-01")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(abiertos(id, "FISCAL")).isEqualTo(1);
            assertThat(vigenciaHastaDe(id, "CALLE A 1"))
                    .as("A no se reescribe")
                    .isEqualTo(LocalDate.of(2026, 5, 31));
            assertThat(tramos(id)).isEqualTo(2);
        }

        @Test
        @DisplayName("PROCESAL abierto desde junio y mudanza a marzo: 422, y no dos abiertos")
        void unProcesalAnteriorAlAbiertoEs422() throws Exception {
            long id = altaDe("C-0312", "40100312", "MUDA HACIA ATRAS, PROCESAL");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("PROCESAL", "JR. JUNIO 600", "2026-06-01"));

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(
                                                    "/rentas/api/v1/rentas/contribuyentes/"
                                                            + id
                                                            + "/domicilios"),
                                            domicilio("PROCESAL", "JR. MARZO 300", "2026-03-01")));

            assertThat(rechazo.estado())
                    .as(
                            "el PROCESAL no tiene indice parcial: sin la regla contestaba 201 y"
                                    + " dejaba el de marzo abierto y solapado con el de junio")
                    .isEqualTo(422);
            assertThat(rechazo.cuerpo()).contains("solo se anade al final").contains("2026-06-01");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(abiertos(id, "PROCESAL"))
                    .as("un tramo abierto por tipo, que el resto del modulo da por hecho")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la misma fecha que el tramo abierto (el doble clic) es 422 y lo dice")
        void laMismaFechaEs422() throws Exception {
            long id = altaDe("C-0313", "40100313", "DOBLE CLIC, PERSONA");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. PRIMERA 1", "2026-01-01"));
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. SEGUNDA 2", "2026-07-01"));

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(
                                                    "/rentas/api/v1/rentas/contribuyentes/"
                                                            + id
                                                            + "/domicilios"),
                                            domicilio("AV. SEGUNDA 2", "2026-07-01")));

            assertThat(rechazo.estado()).isEqualTo(422);
            assertThat(rechazo.cuerpo())
                    .as(
                            "salia 422, pero con «No se puede cerrar el 2026-06-30 un domicilio que"
                                    + " empezo a regir el 2026-07-01», que no explica nada")
                    .contains("solo se anade al final")
                    .contains("2026-07-01");
            assertThat(abiertos(id, "FISCAL")).isEqualTo(1);
            assertThat(tramos(id)).isEqualTo(2);
        }

        @Test
        @DisplayName("una fecha posterior al tramo abierto sigue mudando, en los dos tipos")
        void unaFechaPosteriorSigueMudando() throws Exception {
            long id = altaDe("C-0314", "40100314", "MUDA HACIA ADELANTE, PERSONA");
            for (String tipo : new String[] {"FISCAL", "PROCESAL"}) {
                enviar(
                        post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                        domicilio(tipo, "PRIMERO " + tipo, "2026-06-01"));
                MvcResult siguiente =
                        enviar(
                                post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                                domicilio(tipo, "SEGUNDO " + tipo, "2026-06-02"));

                assertThat(siguiente.getResponse().getStatus())
                        .as("el dia siguiente al inicio del abierto es el primero que se admite")
                        .isEqualTo(201);
                assertThat(abiertos(id, tipo)).isEqualTo(1);
                assertThat(vigenciaHastaDe(id, "PRIMERO " + tipo))
                        .isEqualTo(LocalDate.of(2026, 6, 1));
            }
        }

        /**
         * La carrera: otra mudanza cierra el tramo abierto entre la lectura de esta y su {@code
         * UPDATE}, que toca cero filas y lanza {@code DomicilioNoVigente}.
         *
         * <p>Dos hilos no la reproducen a voluntad; un repositorio que deja ganar a la otra justo
         * antes del cierre, si. La otra mudanza escribe por su propia conexion y confirma, que es
         * lo que habria hecho la peticion que gano.
         */
        @Test
        @DisplayName("si otra mudanza gana la carrera, la que pierde es 409 y no 500")
        void laCarreraEs409() throws Exception {
            long id = altaDe("C-0315", "40100315", "CARRERA, PERSONA");
            enviar(
                    post("/rentas/api/v1/rentas/contribuyentes/" + id + "/domicilios"),
                    domicilio("AV. ORIGEN 1", "2026-01-01"));

            MockMvc conCarrera = montar(new OtraMudanzaSeAdelanta(jdbc));
            Rechazo rechazo =
                    rechazo(
                            () ->
                                    conCarrera
                                            .perform(
                                                    post("/rentas/api/v1/rentas/contribuyentes/"
                                                                    + id
                                                                    + "/domicilios")
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    domicilio(
                                                                            "AV. PERDEDORA 2",
                                                                            "2026-07-01")))
                                            .andReturn());

            assertThat(rechazo.estado())
                    .as(
                            "DomicilioNoVigente es una RuntimeException que nadie traducia: 500 con"
                                    + " incidencia, que el cliente reintenta")
                    .isEqualTo(409);
            assertThat(rechazo.cuerpo())
                    .contains("CONFLICTO")
                    .contains("Otra mudanza")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(abiertos(id, "FISCAL"))
                    .as("la perdedora no escribio nada: la transaccion revirtio")
                    .isZero();
        }
    }

    // ── Contactos y responsables ───────────────────────────────────────

    @Nested
    @DisplayName("Contactos y responsables solidarios")
    class ContactosYResponsables {

        @Test
        @DisplayName("un contacto se da de alta y se da de baja; la fila sigue")
        void contactoAltaYBaja() throws Exception {
            long id = altaDe("C-0400", "40100400", "CON CONTACTO, PERSONA");

            MvcResult creado =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/contactos"),
                            """
                            {"observacion":"Registra el celular que dejo en ventanilla",
                             "tipo":"CELULAR","valor":"969000001","nota":"Llamar tras las 6"}
                            """);
            assertThat(creado.getResponse().getStatus()).isEqualTo(201);
            long contactoId = idDe(creado);

            MvcResult baja =
                    enviar(
                            put(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + id
                                            + "/contactos/"
                                            + contactoId),
                            """
                            {"observacion":"Ya no atiende ese numero","vigente":false}
                            """);

            assertThat(baja.getResponse().getStatus()).isEqualTo(200);
            assertThat(baja.getResponse().getContentAsString()).contains("\"vigente\":false");
            assertThat(contactosDe(id))
                    .as(
                            "un gestor que ya no lo es aparece en notificaciones anteriores:"
                                    + " borrarlo dejaria sin explicar por que se le notifico")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un correo sin arroba es 422, no una fila con un correo que no lo es")
        void elCorreoSeValida() throws Exception {
            long id = altaDe("C-0401", "40100401", "MAL CORREO, PERSONA");

            MvcResult rechazado =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/contactos"),
                            """
                            {"observacion":"Registra correo","tipo":"EMAIL","valor":"sinarroba"}
                            """);

            assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
            assertThat(contactosDe(id)).isZero();
        }

        @Test
        @DisplayName("sin ELIMINACION el contacto no se da de baja")
        void laBajaDelContactoExigeEliminacion() throws Exception {
            long id = altaDe("C-0402", "40100402", "CONTACTO PROTEGIDO, PERSONA");
            MvcResult creado =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + id + "/contactos"),
                            """
                            {"observacion":"Registra","tipo":"TELEFONO","valor":"073000001"}
                            """);
            long contactoId = idDe(creado);
            CONCEDIDOS.remove(Privilegio.ELIMINACION);

            MvcResult negada =
                    enviar(
                            put(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + id
                                            + "/contactos/"
                                            + contactoId),
                            """
                            {"observacion":"Intento sin privilegio","vigente":false}
                            """);

            assertThat(negada.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("un responsable solidario se registra y su vinculo se cierra, sin borrarse")
        void responsableAltaYCierre() throws Exception {
            long obligado = altaDe("C-0403", "40100403", "OBLIGADO PRINCIPAL, EL");
            long responde = altaDe("C-0404", "40100404", "RESPONDE CON EL, LA");

            MvcResult creado =
                    enviar(
                            post(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables"),
                            """
                            {"observacion":"Sociedad conyugal acreditada con partida",
                             "responsableId":%d,"vinculo":"CONYUGE","vigenciaDesde":"2026-01-01",
                             "documentoOrigen":"PARTIDA-2026-1"}
                            """
                                    .formatted(responde));

            assertThat(creado.getResponse().getStatus()).isEqualTo(201);
            long vinculoId = idDe(creado);

            MvcResult cerrado =
                    enviar(
                            put(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables/"
                                            + vinculoId),
                            """
                            {"observacion":"Divorcio inscrito","vigenciaHasta":"2026-08-01"}
                            """);

            assertThat(cerrado.getResponse().getStatus()).isEqualTo(200);
            assertThat(cerrado.getResponse().getContentAsString()).contains("2026-08-01");
            assertThat(responsablesDe(obligado))
                    .as("la deuda anterior sigue siendo suya: el vinculo se cierra, no se borra")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un vinculo ya cerrado es 404: no hay tal vinculo abierto que cerrar")
        void cerrarDosVecesEs404() throws Exception {
            long obligado = altaDe("C-0405", "40100405", "SE CIERRA UNA VEZ, EL");
            long responde = altaDe("C-0406", "40100406", "RESPONDIA, LA");
            MvcResult creado =
                    enviar(
                            post(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables"),
                            """
                            {"observacion":"Condominio","responsableId":%d,"vinculo":"CONDOMINO",
                             "porcentaje":"50","vigenciaDesde":"2026-01-01",
                             "documentoOrigen":"ESCRITURA-1"}
                            """
                                    .formatted(responde));
            long vinculoId = idDe(creado);
            enviar(
                    put(
                            "/rentas/api/v1/rentas/contribuyentes/"
                                    + obligado
                                    + "/responsables/"
                                    + vinculoId),
                    """
                    {"observacion":"Se cierra","vigenciaHasta":"2026-06-01"}
                    """);

            MvcResult otraVez =
                    enviar(
                            put(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables/"
                                            + vinculoId),
                            """
                            {"observacion":"Se vuelve a cerrar","vigenciaHasta":"2026-07-01"}
                            """);

            assertThat(otraVez.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("un porcentaje en un vinculo que no reparte es 422, no un campo ignorado")
        void elPorcentajeSoloDondeReparte() throws Exception {
            long obligado = altaDe("C-0407", "40100407", "NO REPARTE, EL");
            long responde = altaDe("C-0408", "40100408", "REPRESENTA, LA");

            MvcResult rechazado =
                    enviar(
                            post(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables"),
                            """
                            {"observacion":"Representante","responsableId":%d,
                             "vinculo":"REPRESENTANTE","porcentaje":"50",
                             "vigenciaDesde":"2026-01-01","documentoOrigen":"PODER-1"}
                            """
                                    .formatted(responde));

            assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
            assertThat(responsablesDe(obligado)).isZero();
        }

        @Test
        @DisplayName("un responsable que no esta en el padron es 404, no una clave foranea rota")
        void elResponsableTieneQueEstarEnElPadron() throws Exception {
            long obligado = altaDe("C-0409", "40100409", "SIN RESPONSABLE, EL");

            MvcResult rechazado =
                    enviar(
                            post(
                                    "/rentas/api/v1/rentas/contribuyentes/"
                                            + obligado
                                            + "/responsables"),
                            """
                            {"observacion":"Alguien de fuera","responsableId":999999,
                             "vinculo":"POSEEDOR","vigenciaDesde":"2026-01-01",
                             "documentoOrigen":"ACTA-1"}
                            """);

            assertThat(rechazado.getResponse().getStatus())
                    .as(
                            "para notificarle hace falta su domicilio, y el domicilio cuelga del"
                                    + " padron")
                    .isEqualTo(404);
        }
    }

    // ── Aislamiento ────────────────────────────────────────────────────

    @Nested
    @DisplayName("El aislamiento entre municipalidades")
    class Aislamiento {

        @Test
        @DisplayName("la ficha de un contribuyente de otra municipalidad no se ve")
        void noSeVe() throws Exception {
            MvcResult respuesta =
                    mvc.perform(get("/rentas/api/v1/rentas/contribuyentes/" + ajeno + "/ficha"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus())
                    .as(
                            "con el pool conectado como superusuario esto seria 200 y ensenaria la"
                                    + " ficha de la municipalidad vecina: RLS es lo unico que lo"
                                    + " separa")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("ni se toca: no se le puede colgar un domicilio desde aqui")
        void niSeToca() throws Exception {
            MvcResult respuesta =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes/" + ajeno + "/domicilios"),
                            domicilio("AV. DE OTRA MUNICIPALIDAD 1", "2026-01-01"));

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(404);
            assertThat(domiciliosAbiertos(ajeno)).isZero();
        }

        @Test
        @DisplayName("y el mismo codigo puede existir en las dos sin chocar")
        void elCodigoSeRepiteEntreMunicipalidades() throws Exception {
            MvcResult creado =
                    enviar(
                            post("/rentas/api/v1/rentas/contribuyentes"),
                            alta("V-0001", "45000002", "MISMO CODIGO, OTRA MUNICIPALIDAD"));

            assertThat(creado.getResponse().getStatus())
                    .as("la unicidad del codigo es POR municipalidad, no global")
                    .isEqualTo(201);
        }
    }

    // ------------------------------------------------------------------

    private static String alta(String codigo, String documento, String nombre) {
        return """
               {"observacion":"Alta en ventanilla con DNI a la vista","codigo":"%s",
                "tipoDocumento":"DNI","numeroDocumento":"%s","tipoPersona":"NATURAL",
                "nombreRazonSocial":"%s"}
               """
                .formatted(codigo, documento, nombre);
    }

    private static String domicilio(String direccion, String desde) {
        return domicilio("FISCAL", direccion, desde);
    }

    private static String domicilio(String tipo, String direccion, String desde) {
        return """
               {"observacion":"Muda segun declaracion jurada presentada","tipo":"%s",
                "direccion":"%s","vigenciaDesde":"%s","documentoOrigen":"DJ-2026-1"}
               """
                .formatted(tipo, direccion, desde);
    }

    private static long altaDe(String codigo, String documento, String nombre) throws Exception {
        return idDe(
                enviar(
                        post("/rentas/api/v1/rentas/contribuyentes"),
                        alta(codigo, documento, nombre)));
    }

    /** La ficha completa del contribuyente, que es donde viven los datos personales (#552). */
    private static String fichaDe(long id) throws Exception {
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/rentas/api/v1/rentas/contribuyentes/" + id + "/ficha"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static MvcResult enviar(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion,
            String cuerpo)
            throws Exception {
        return mvc.perform(peticion.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn();
    }

    /** Lo que un rechazo contesta, y las lineas ERROR que dejo en el registro del manejador. */
    private record Rechazo(int estado, String cuerpo, List<String> errores) {}

    /**
     * La peticion, con las lineas ERROR que deja en el registro del manejador (#422).
     *
     * <p>Es la otra mitad de un rechazo bien contestado: un error del usuario que sale como
     * incidencia entierra las averias de verdad aunque el estado HTTP ya fuera el correcto.
     */
    private static Rechazo rechazo(java.util.concurrent.Callable<MvcResult> peticion)
            throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult resultado;
        try {
            resultado = peticion.call();
        } finally {
            registro.detachAppender(anotados);
        }
        return new Rechazo(
                resultado.getResponse().getStatus(),
                resultado.getResponse().getContentAsString(),
                anotados.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList());
    }

    /**
     * La direccion del <b>campo</b> {@code domicilioFiscal}, no la primera que aparezca.
     *
     * <p>{@code DomicilioResource} no anida nada, asi que su objeto va de la llave a la primera
     * llave de cierre. Buscar la direccion en el cuerpo entero no mide lo que dice medir: el
     * historial trae todos los tramos, y la asercion pasaria con el campo diciendo otra cosa.
     */
    private static String direccionFiscalDe(MvcResult resultado) throws Exception {
        String cuerpo = resultado.getResponse().getContentAsString();
        java.util.regex.Matcher objeto =
                java.util.regex.Pattern.compile("\"domicilioFiscal\":\\{([^}]*)\\}")
                        .matcher(cuerpo);
        assertThat(objeto.find()).as("la ficha trae domicilioFiscal: " + cuerpo).isTrue();
        java.util.regex.Matcher direccion =
                java.util.regex.Pattern.compile("\"direccion\":\"([^\"]*)\"")
                        .matcher(objeto.group(1));
        assertThat(direccion.find()).as("y ese domicilio trae su direccion").isTrue();
        return direccion.group(1);
    }

    /** El {@code id} del recurso creado, leido del JSON sin montar un mapeador entero. */
    private static long idDe(MvcResult resultado) throws Exception {
        String cuerpo = resultado.getResponse().getContentAsString();
        java.util.regex.Matcher encontrado =
                java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(cuerpo);
        assertThat(encontrado.find()).as("la respuesta trae el id: " + cuerpo).isTrue();
        return Long.parseLong(encontrado.group(1));
    }

    /** Se pregunta a la base, no a la respuesta: es lo que quedo escrito lo que importa. */
    private static long domiciliosAbiertos(long contribuyenteId) throws SQLException {
        return contar(
                "SELECT count(*) FROM domicilio WHERE contribuyente_id = "
                        + contribuyenteId
                        + " AND vigencia_hasta IS NULL",
                municipalidadA);
    }

    /** Los tramos abiertos de un tipo: el resto del modulo da por hecho que es uno (#420). */
    private static long abiertos(long contribuyenteId, String tipo) throws SQLException {
        return contar(
                "SELECT count(*) FROM domicilio WHERE contribuyente_id = "
                        + contribuyenteId
                        + " AND tipo = '"
                        + tipo
                        + "' AND vigencia_hasta IS NULL",
                municipalidadA);
    }

    /** Todos los tramos, abiertos y cerrados: un rechazo no escribe ninguno. */
    private static long tramos(long contribuyenteId) throws SQLException {
        return contar(
                "SELECT count(*) FROM domicilio WHERE contribuyente_id = " + contribuyenteId,
                municipalidadA);
    }

    private static long contactosDe(long contribuyenteId) throws SQLException {
        return contar(
                "SELECT count(*) FROM contacto WHERE contribuyente_id = " + contribuyenteId,
                municipalidadA);
    }

    private static long responsablesDe(long contribuyenteId) throws SQLException {
        return contar(
                "SELECT count(*) FROM responsable_solidario WHERE contribuyente_id = "
                        + contribuyenteId,
                municipalidadA);
    }

    private static long cuantosHay(String codigo) throws SQLException {
        return contar(
                "SELECT count(*) FROM contribuyente WHERE codigo_contribuyente = '" + codigo + "'",
                municipalidadA);
    }

    private static boolean activo(String codigo) throws SQLException {
        return contar(
                        "SELECT count(*) FROM contribuyente WHERE codigo_contribuyente = '"
                                + codigo
                                + "' AND activo",
                        municipalidadA)
                == 1;
    }

    private static LocalDate vigenciaHastaDe(long contribuyenteId, String direccion)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                            app.prepareStatement(
                                    "SELECT vigencia_hasta FROM domicilio WHERE contribuyente_id ="
                                            + " ? AND direccion = ?");
                    ResultSet resultado = ejecutar(sentencia, contribuyenteId, direccion)) {
                resultado.next();
                return resultado.getObject(1, LocalDate.class);
            }
        }
    }

    private static ResultSet ejecutar(
            PreparedStatement sentencia, long contribuyenteId, String direccion)
            throws SQLException {
        sentencia.setLong(1, contribuyenteId);
        sentencia.setString(2, direccion);
        return sentencia.executeQuery();
    }

    private static long contar(String consulta, long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(consulta);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }

    private static Privilegio privilegioDe(String metodo) {
        for (java.lang.reflect.Method candidato : ContribuyenteController.class.getMethods()) {
            if (candidato.getName().equals(metodo)) {
                return candidato
                        .getAnnotation(kamayuk.rentas.autorizacion.RequiereAcceso.class)
                        .privilegio();
            }
        }
        throw new AssertionError("No existe el metodo " + metodo);
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

    private static long sembrar(
            long municipalidadId, String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                sentencia.setString(4, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /**
     * Otra mudanza gana la carrera (#420): justo antes de que esta cierre el tramo, la otra lo
     * cierra por su propia conexion y confirma. El {@code UPDATE ... WHERE vigencia_hasta IS NULL}
     * de esta toca entonces cero filas, que es lo que pasa con dos peticiones simultaneas.
     */
    private static final class OtraMudanzaSeAdelanta extends FichaRepositoryJdbc {

        OtraMudanzaSeAdelanta(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Domicilio guardar(Domicilio domicilio) {
            if (!domicilio.esNuevo()) {
                try (Connection otra = base.conexion(BaseDeDatosDePrueba.APP)) {
                    ContextoDeTenant.fijar(otra, municipalidadA);
                    try (PreparedStatement cierre =
                            otra.prepareStatement(
                                    "UPDATE domicilio SET vigencia_hasta = ?"
                                            + " WHERE id = ? AND vigencia_hasta IS NULL")) {
                        cierre.setObject(1, domicilio.vigenciaHasta());
                        cierre.setLong(2, java.util.Objects.requireNonNull(domicilio.id()));
                        assertThat(cierre.executeUpdate())
                                .as("la otra mudanza cierra el tramo de verdad")
                                .isEqualTo(1);
                    }
                    otra.commit();
                } catch (SQLException fallo) {
                    throw new IllegalStateException(fallo);
                }
            }
            return super.guardar(domicilio);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
