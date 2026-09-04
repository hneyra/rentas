package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.DeteccionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.LiquidacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MuestraDelProgramaRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
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
 * Dos vacíos del módulo que salían idénticos, de HTTP a PostgreSQL y sin un doble por el camino
 * (#546, AC 1 y AC 5).
 *
 * <h2>Qué se mide</h2>
 *
 * <ul>
 *   <li><b>La muestra de un programa que no existe</b> contestaba {@code 200
 *       {"contenido":[],"totalElementos":0}}, byte a byte lo mismo que un programa recién
 *       registrado al que nadie ha sorteado todavía la muestra. Quien pide con un identificador
 *       equivocado no puede saber que se equivocó, y la pantalla dice «este programa no tiene
 *       predios seleccionados» de un programa que no está.
 *   <li><b>El estado de cuenta de quien nunca fue fiscalizado</b> contestaba {@code
 *       "total":{"importe":"0"}}, lo mismo que quien sí lo fue y no debe nada. El propio javadoc de
 *       {@code EstadoDeCuentaResource} ya decía que «un cero se lee como *no debe nada*».
 * </ul>
 *
 * <p>De paso quedan medidas por HTTP las otras dos mitades del issue: el área sale con <b>una sola
 * forma</b> —{@code "300.00"}, sin unidad, la del serializador que {@code ConfiguracionDeJson}
 * registra (AC 2)— y el listado se ordena por el nombre que la fila <b>publica</b>, {@code
 * codRefCatastral}, que hasta este issue daba {@code 422 ORDEN_NO_ADMITIDO} (AC 3).
 *
 * <h2>Por qué hasta la base</h2>
 *
 * <p>Porque la diferencia entre «no existe» y «existe y está vacío» la decide una consulta, y con
 * un doble la escribiría la propia prueba. La conexión es la de {@code sgtm_app}: un superusuario
 * omite RLS incluso con {@code FORCE ROW LEVEL SECURITY} (DAT-01 §0, primer hallazgo), así que el
 * programa de la municipalidad vecina «existiría» y el 404 no se podría demostrar.
 *
 * <p>Los casos de uso se envuelven con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotación</b> como haría el contenedor: un {@code TransactionTemplate}
 * incondicional dejaría la prueba pasando con el {@code @Transactional} quitado, que es el modo de
 * fallo que #486 existe para impedir.
 *
 * <p>Y siembra filas de las <b>dos clases</b> en los dos casos (AC 11): un programa con muestra y
 * otro sin ella, un contribuyente con liquidación y otro sin ninguna. Sobre la base como está
 * —cinco tablas del módulo con cero filas— una prueba de esto pasaría en verde sin verificar nada.
 */
@DisplayName("#546 — Los dos vacios del modulo que salian identicos, contra PostgreSQL")
class VaciosQueNoSeConfundenFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

    /** El contribuyente que SÍ fue fiscalizado, y cuya liquidación quedó cancelada. */
    private static final String FISCALIZADO = "F-000001";

    /** El que nunca lo fue. Está en el padrón, y eso es lo único que tiene. */
    private static final String NUNCA_FISCALIZADO = "F-000002";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long programaConMuestra;
    private static long programaSinMuestra;
    private static long programaDeLaVecina;
    private static long contribuyenteFiscalizado;
    private static long contribuyenteSinFiscalizar;
    private static long predioFiscalizado;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Municipalidad fiscalizadora");
        municipalidadB = crearMunicipalidad("270102", "Municipalidad vecina");

        // ── La municipalidad A ────────────────────────────────────────────
        contribuyenteFiscalizado = crearContribuyente(municipalidadA, FISCALIZADO, "70200001");
        contribuyenteSinFiscalizar =
                crearContribuyente(municipalidadA, NUNCA_FISCALIZADO, "70200002");
        predioFiscalizado = crearPredio(municipalidadA, "000000000000000901");
        long otroPredio = crearPredio(municipalidadA, "000000000000000902");

        programaConMuestra = crearPrograma(municipalidadA, "PF-546-CON");
        programaSinMuestra = crearPrograma(municipalidadA, "PF-546-SIN");

        // Dos filas de muestra, para que el orden tenga algo que ordenar.
        sembrarMuestra(municipalidadA, programaConMuestra, otroPredio, contribuyenteFiscalizado);
        sembrarMuestra(
                municipalidadA, programaConMuestra, predioFiscalizado, contribuyenteFiscalizado);

        // La liquidación CANCELADA: existe, y el libro dice que no queda saldo.
        long conjunto = crearConjuntoSellado(municipalidadA);
        long acta =
                crearActa(
                        municipalidadA,
                        programaConMuestra,
                        contribuyenteFiscalizado,
                        predioFiscalizado);
        long liquidacion = crearLiquidacion(municipalidadA, acta);
        crearDetalle(municipalidadA, liquidacion, conjunto, predioFiscalizado);

        // ── La municipalidad B ────────────────────────────────────────────
        // El programa de la vecina EXISTE, y desde A tiene que ser un 404: si la
        // conexion omitiera RLS, esta peticion devolveria su muestra.
        programaDeLaVecina = crearPrograma(municipalidadB, "PF-546-VECINA");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        ProgramaFiscalizacionRepositoryJdbc programas =
                new ProgramaFiscalizacionRepositoryJdbc(jdbc);
        MuestraDelProgramaRepositoryJdbc muestras = new MuestraDelProgramaRepositoryJdbc(jdbc);
        ActaFiscalizacionRepositoryJdbc actas = new ActaFiscalizacionRepositoryJdbc(jdbc);
        LiquidacionRepositoryJdbc liquidaciones = new LiquidacionRepositoryJdbc(jdbc);
        DeteccionRepositoryJdbc deteccion = new DeteccionRepositoryJdbc(jdbc);

        DirectorioDeContribuyentes directorio = new PadronDeLaPrueba();

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MuestraController(
                                        sorteoQueNoSeUsa(),
                                        envolver(
                                                new ConsultaDeMuestra(programas, muestras, actas),
                                                gestor),
                                        directorio),
                                new OmisosController(
                                        envolver(
                                                new DeteccionDeOmisos(
                                                        deteccion, new TitularesDeMentira()),
                                                gestor),
                                        envolver(
                                                new EstadoDeCuentaDeFiscalizacion(
                                                        liquidaciones, new LibroSinSaldo()),
                                                gestor),
                                        directorio,
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

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-09", "10.0.0.9"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("AC 1 — el programa que no existe y el que no tiene muestra dicen cosas distintas")
    class LaMuestraDeUnProgramaQueNoEsta {

        @Test
        @DisplayName("un programa que existe y no sorteo su muestra es 200 con cero filas")
        void elProgramaSinMuestraEsUnaPaginaVacia() throws Exception {
            MvcResult resultado = muestraDe(programaSinMuestra);

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "sin el @Transactional del caso de uso la politica RLS no devuelve"
                                    + " vacio: revienta con «invalid input syntax for type bigint:"
                                    + " \"\"» y esto seria 500 (#486)")
                    .isEqualTo(200);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"totalElementos\":0")
                    .contains("\"contenido\":[]");
        }

        @Test
        @DisplayName("un programa que no existe es 404 nombrandolo, no una pagina vacia")
        void elProgramaInexistenteEs404() throws Exception {
            MvcResult resultado = muestraDe(99999L);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("99999")
                    .doesNotContain("contenido");
        }

        @Test
        @DisplayName("y las dos respuestas NO son la misma: es lo que hoy no se podia decir")
        void lasDosRespuestasSonDistintas() throws Exception {
            String sinMuestra = muestraDe(programaSinMuestra).getResponse().getContentAsString();
            String inexistente = muestraDe(99999L).getResponse().getContentAsString();

            assertThat(inexistente)
                    .as(
                            "hasta #546 los dos eran"
                                    + " {\"contenido\":[],\"totalElementos\":0,…} byte a byte, asi que"
                                    + " un identificador equivocado era indistinguible de un programa"
                                    + " sin sortear")
                    .isNotEqualTo(sinMuestra);
        }

        @Test
        @DisplayName("el programa de la municipalidad vecina existe, y desde aqui es un 404")
        void elProgramaDeLaVecinaNoSeVe() throws Exception {
            MvcResult resultado = muestraDe(programaDeLaVecina);

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "con el pool conectado como superusuario esto seria 200 con la muestra"
                                    + " de la otra municipalidad, y nada en la respuesta lo diria")
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("AC 2 y AC 3 — una sola forma del area, y el orden por el nombre publicado")
    class LoQueLaFilaPublica {

        @Test
        @DisplayName(
                "el area sale «300.00», sin unidad: la del serializador de los objetos de valor")
        void elAreaSaleSinUnidad() throws Exception {
            String cuerpo = muestraDe(programaConMuestra).getResponse().getContentAsString();

            assertThat(cuerpo)
                    .as(
                            "hasta #546 este campo era un String compuesto con toString() y salia"
                                    + " «300.00 m2» aqui y «300.00» en la liquidacion")
                    .contains("\"areaCatastral\":\"300.00\"")
                    .doesNotContain("m2");
        }

        @Test
        @DisplayName("se ordena por «codRefCatastral», que es el nombre que la fila publica")
        void seOrdenaPorElNombrePublicado() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/fiscalizacion/programas/"
                                                    + programaConMuestra
                                                    + "/muestra")
                                            .param("ordenarPor", "codRefCatastral")
                                            .param("direccion", "DESCENDENTE"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo.indexOf("000000000000000902"))
                    .as("descendente: el 902 va antes que el 901")
                    .isLessThan(cuerpo.indexOf("000000000000000901"));
        }

        @Test
        @DisplayName("y el orden se sigue validando: un campo que no publica ninguna fila es 422")
        void unCampoQueNadiePublicaSigueSiendo422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/fiscalizacion/programas/"
                                                    + programaConMuestra
                                                    + "/muestra")
                                            .param("ordenarPor", "(SELECT 1)"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("No se puede ordenar por");
        }
    }

    @Nested
    @DisplayName("AC 5 — quien nunca fue fiscalizado no debe cero")
    class ElEstadoDeCuentaDistingueSusVacios {

        @Test
        @DisplayName("quien nunca fue fiscalizado lo dice, y no publica ningun total")
        void nuncaFiscalizado() throws Exception {
            String cuerpo = estadoDeCuentaDe(NUNCA_FISCALIZADO);

            assertThat(cuerpo)
                    .as(
                            "hasta #546 esto decia «total»:{«importe»:«0»}, que es exactamente lo"
                                    + " que el javadoc del recurso dice de si mismo que hay que"
                                    + " evitar: un cero se lee como «no debe nada»")
                    .contains("\"fiscalizado\":false")
                    .contains("\"lineas\":[]")
                    .contains("\"total\":null")
                    .doesNotContain("\"importe\":\"0\"");
            assertThat(cuerpo)
                    .as("y la respuesta sigue diciendo a que dia esta (regla 9, RNF-075)")
                    .contains("\"fechaDeConsulta\":\"2026-09-01\"");
        }

        @Test
        @DisplayName("quien SI lo fue y no debe nada publica su cero, con su fecha")
        void fiscalizadoYSinSaldo() throws Exception {
            String cuerpo = estadoDeCuentaDe(FISCALIZADO);

            assertThat(cuerpo)
                    .as(
                            "sin el @Transactional del caso de uso, RLS tumba la lectura de las"
                                    + " liquidaciones y esto ni siquiera llegaria a componerse")
                    .contains("\"fiscalizado\":true");
            assertThat(cuerpo)
                    .as("el cero de aqui SI significa «no debe nada», y lleva su fecha")
                    .contains("\"total\":{\"importe\":\"0\",\"actualizadoA\":\"2026-09-01\"}");
        }

        @Test
        @DisplayName("y las dos respuestas NO son la misma")
        void lasDosRespuestasSonDistintas() throws Exception {
            assertThat(estadoDeCuentaDe(NUNCA_FISCALIZADO))
                    .as("hasta #546 las dos decian «importe»:«0»")
                    .isNotEqualTo(estadoDeCuentaDe(FISCALIZADO));
        }
    }

    // ------------------------------------------------------------------

    private static MvcResult muestraDe(long programaId) throws Exception {
        return mvc.perform(get("/rentas/api/v1/fiscalizacion/programas/" + programaId + "/muestra"))
                .andReturn();
    }

    private static String estadoDeCuentaDe(String codigo) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/estado-cuenta")
                                        .param("contribuyente", codigo))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /**
     * El sorteo no se ejercita aqui: {@code MuestraController} lo exige en el constructor y este
     * archivo mide sus lecturas. Se construye con los repositorios reales para no fingir nada.
     */
    private static GenerarMuestra sorteoQueNoSeUsa() {
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        return new GenerarMuestra(
                new ProgramaFiscalizacionRepositoryJdbc(jdbc),
                new MuestraDelProgramaRepositoryJdbc(jdbc),
                new ActaFiscalizacionRepositoryJdbc(jdbc),
                new DeteccionDeOmisos(new DeteccionRepositoryJdbc(jdbc), new TitularesDeMentira()),
                registro -> {},
                RELOJ);
    }

    /** El proxy que obedece a la anotacion, como el contenedor. Ver el javadoc de la clase. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /**
     * El libro sin ningun asiento vivo: la liquidacion se cobro y no queda saldo.
     *
     * <p>Es lo que hace comparable el AC 5: el cero de este contribuyente es un cero DE VERDAD, y
     * el del que nunca fue fiscalizado no era ninguna cifra.
     */
    private static final class LibroSinSaldo
            implements kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica {

        @Override
        public List<kamayuk.rentas.cuentacorriente.ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate aLaFecha) {
            return List.of(
                    new kamayuk.rentas.cuentacorriente.ObligacionPublica(
                            "PREDIAL",
                            new kamayuk.rentas.dominio.Ejercicio(2024),
                            predioFiscalizado,
                            null,
                            aLaFecha,
                            kamayuk.rentas.dominio.Dinero.CERO,
                            kamayuk.rentas.dominio.Dinero.CERO,
                            kamayuk.rentas.dominio.Dinero.CERO,
                            kamayuk.rentas.dominio.Dinero.CERO));
        }
    }

    /** El padron: los dos contribuyentes sembrados, resueltos por su codigo. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (FISCALIZADO.equals(codigo)) {
                return Optional.of(resumen(contribuyenteFiscalizado, FISCALIZADO));
            }
            if (NUNCA_FISCALIZADO.equals(codigo)) {
                return Optional.of(resumen(contribuyenteSinFiscalizar, NUNCA_FISCALIZADO));
            }
            return Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of(
                    contribuyenteFiscalizado, resumen(contribuyenteFiscalizado, FISCALIZADO),
                    contribuyenteSinFiscalizar,
                            resumen(contribuyenteSinFiscalizar, NUNCA_FISCALIZADO));
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }

        private static ResumenDeContribuyente resumen(long id, String codigo) {
            return new ResumenDeContribuyente(id, codigo, "TITULAR, PRUEBA", "70200001");
        }
    }

    // ── Siembra ────────────────────────────────────────────────────────

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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static long crearPredio(long municipalidadId, String codigo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                municipalidadId,
                codigo);
    }

    private static long crearPrograma(long municipalidadId, String codigo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio, ejercicio, sector_codigo, criterio, fiscalizador)"
                        + " VALUES (?, ?, 'Programa de prueba', 'PREDIAL', ?, 2026, '01', 'OMISO',"
                        + "         'R. MENDOZA CRUZ') RETURNING id",
                municipalidadId,
                codigo,
                LocalDate.of(2026, 1, 1));
    }

    private static void sembrarMuestra(
            long municipalidadId, long programaId, long predioId, long contribuyenteId) {
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO programa_muestra (municipalidad_id, programa_id, predio_id,"
                        + " cod_ref_catastral, contribuyente_id, condicion, area_catastral,"
                        + " area_declarada, sector_codigo, fecha_sorteo, observacion,"
                        + " usuario_registro, fecha_registro)"
                        + " VALUES (?, ?, ?, (SELECT codigo_ref_catastral FROM predio"
                        + "                    WHERE municipalidad_id = ? AND id = ?), ?, 'OMISO',"
                        + "         300.00, 120.00, '01', ?, 'siembra', 'siembra', now())"
                        + " RETURNING id",
                municipalidadId,
                programaId,
                predioId,
                municipalidadId,
                predioId,
                contribuyenteId,
                LocalDate.of(2026, 3, 15));
    }

    private static long crearConjuntoSellado(long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version, estado,"
                        + " fecha_sellado, usuario_sellado)"
                        + " VALUES (?, 2024, 1, 'SELLADO', now(), 'siembra') RETURNING id",
                municipalidadId);
    }

    private static long crearActa(
            long municipalidadId, long programaId, long contribuyenteId, long predioId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                        + " contribuyente_id, predio_id, fecha_visita, fiscalizador, hallazgo,"
                        + " area_hallada, estado, observacion, usuario_registro)"
                        + " VALUES (?, ?, 1, ?, ?, ?, 'J. Perez', 'SUBVALUADOR', 300.00, 'ABIERTA',"
                        + "         'acta de prueba', 'siembra') RETURNING id",
                municipalidadId,
                programaId,
                contribuyenteId,
                predioId,
                LocalDate.of(2026, 3, 1));
    }

    private static long crearLiquidacion(long municipalidadId, long actaId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO liquidacion_fiscalizacion (municipalidad_id, numero, ejercicio,"
                        + " correlativo, acta_id, version, ejercicio_desde, ejercicio_hasta,"
                        + " tipo_fiscalizacion, motivo_determinante, fecha, usuario_registro,"
                        + " fecha_registro, observacion)"
                        + " VALUES (?, 'LIQ-546-0001', 2026, 1, ?, 1, 2024, 2024, 'CIERTA',"
                        + "         'SUBVALUACION DETECTADA', ?, 'siembra', now(), 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                actaId,
                LocalDate.of(2026, 4, 1));
    }

    private static long crearDetalle(
            long municipalidadId, long liquidacionId, long conjuntoId, long predioId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO liquidacion_detalle (municipalidad_id, liquidacion_id, ejercicio,"
                        + " conjunto_id, predio_id, condicion, area_declarada, area_hallada)"
                        + " VALUES (?, ?, 2024, ?, ?, 'SUBVALUADOR', 120.00, 300.00) RETURNING id",
                municipalidadId,
                liquidacionId,
                conjuntoId,
                predioId);
    }

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
