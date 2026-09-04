package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDePrograma;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los programas de fiscalización contra PostgreSQL de verdad, conectado como {@code sgtm_app} —
 * nunca como superusuario, que omite RLS incluso con {@code FORCE ROW LEVEL SECURITY} y dejaría la
 * prueba en verde sin verificar nada.
 */
@DisplayName("#45 y #431 — Programas de fiscalizacion")
class ProgramaFiscalizacionRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long municipalidadDelConteo;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;
    private static ProgramaFiscalizacionRepositoryJdbc repositorio;

    /**
     * El caso de uso <b>con su proxy transaccional de verdad</b>.
     *
     * <p>Lo que se quiere verificar es la anotacion {@code @Transactional} del codigo de
     * produccion. Si la prueba abriera la transaccion ella misma, quitarsela al caso de uso no
     * pondria nada en rojo — mismo criterio que {@code TransferenciaJdbcTest}.
     */
    private static ConsultaDeProgramas consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250501", "Municipalidad de programas A");
        municipalidadB = crearMunicipalidad("250502", "Municipalidad de programas B");
        municipalidadDelConteo = crearMunicipalidad("250503", "Municipalidad del conteo");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new ProgramaFiscalizacionRepositoryJdbc(JdbcClient.create(pool));
        consulta = envolver(new ConsultaDeProgramas(repositorio));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.fiscalizacion", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("un programa se guarda y se relee")
    void unProgramaSeGuardaYSeRelee() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        ProgramaFiscalizacion guardado =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        ProgramaFiscalizacion.nuevo(
                                                "PF-100",
                                                "Muestra por riesgo",
                                                TipoDePrograma.PREDIAL,
                                                LocalDate.of(2026, 2, 1),
                                                null)));

        Optional<ProgramaFiscalizacion> releido =
                transaccion.execute(estado -> repositorio.findById(guardado.id()));

        assertThat(releido).isPresent();
        assertThat(releido.get().codigo()).isEqualTo("PF-100");
        assertThat(releido.get().estado()).isEqualTo(EstadoDePrograma.ABIERTO);
    }

    @Test
    @DisplayName("la lectura por identificador no cruza la municipalidad")
    void laLecturaPorIdentificadorNoCruzaLaMunicipalidad() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        ProgramaFiscalizacion guardadoEnA =
                transaccion.execute(
                        estado ->
                                repositorio.insertar(
                                        ProgramaFiscalizacion.nuevo(
                                                "PF-101",
                                                "Programa de A",
                                                TipoDePrograma.VEHICULAR,
                                                LocalDate.of(2026, 2, 1),
                                                null)));

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        Optional<ProgramaFiscalizacion> desdeB =
                transaccion.execute(estado -> repositorio.findById(guardadoEnA.id()));

        assertThat(desdeB).isEmpty();
    }

    /* ── #431: la grilla de programas ──────────────────────────────────── */

    @Test
    @DisplayName("la grilla devuelve los programas de la municipalidad, ordenados por codigo")
    void laGrillaDevuelveLosProgramasOrdenadosPorCodigo() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar("PF-902", "Segundo", TipoDePrograma.PREDIAL, LocalDate.of(2026, 3, 1), null);
        insertar("PF-901", "Primero", TipoDePrograma.VEHICULAR, LocalDate.of(2026, 3, 1), null);

        Pagina<ProgramaFiscalizacion> pagina = consultar(CriterioDeProgramas.todos());

        List<String> codigos =
                pagina.contenido().stream().map(ProgramaFiscalizacion::codigo).toList();
        assertThat(codigos).contains("PF-901", "PF-902");
        assertThat(codigos.indexOf("PF-901")).isLessThan(codigos.indexOf("PF-902"));
    }

    @Test
    @DisplayName("la grilla no cruza la municipalidad: lo de A no se ve desde B")
    void laGrillaNoCruzaLaMunicipalidad() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar("PF-910", "Solo de A", TipoDePrograma.PREDIAL, LocalDate.of(2026, 4, 1), null);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        Pagina<ProgramaFiscalizacion> desdeB = consultar(new CriterioDeProgramas("PF-910", null));

        assertThat(desdeB.contenido()).isEmpty();
        assertThat(desdeB.totalElementos()).isZero();
    }

    @Test
    @DisplayName("el filtro por codigo es exacto, y no distingue mayusculas al teclearlo")
    void elFiltroPorCodigoEsExacto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar("PF-920", "Con codigo", TipoDePrograma.PREDIAL, LocalDate.of(2026, 5, 1), null);
        insertar("PF-9201", "Otro", TipoDePrograma.PREDIAL, LocalDate.of(2026, 5, 1), null);

        Pagina<ProgramaFiscalizacion> exacto = consultar(new CriterioDeProgramas("pf-920", null));

        assertThat(exacto.contenido()).hasSize(1);
        assertThat(exacto.contenido().get(0).codigo()).isEqualTo("PF-920");
    }

    /**
     * El «Ejercicio» de la pantalla es la <b>vigencia</b>, no el año de inicio.
     *
     * <p>Es la diferencia que se mide: un programa que arranca el 1 de diciembre de 2025 y cierra
     * el 31 de marzo de 2026 tiene {@code EXTRACT(YEAR FROM fecha_inicio) = 2025}, y resolver el
     * filtro por ahí lo dejaría fuera de la búsqueda de 2026 — que es justo el ejercicio en el que
     * se está fiscalizando.
     */
    @Test
    @DisplayName("el filtro por ejercicio es por VIGENCIA, no por el ano de inicio")
    void elFiltroPorEjercicioEsPorVigencia() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar(
                "PF-930",
                "De diciembre a marzo",
                TipoDePrograma.PREDIAL,
                LocalDate.of(2025, 12, 1),
                LocalDate.of(2026, 3, 31));

        Pagina<ProgramaFiscalizacion> en2026 = consultar(new CriterioDeProgramas("PF-930", 2026));
        Pagina<ProgramaFiscalizacion> en2025 = consultar(new CriterioDeProgramas("PF-930", 2025));
        Pagina<ProgramaFiscalizacion> en2027 = consultar(new CriterioDeProgramas("PF-930", 2027));

        assertThat(en2026.contenido()).as("vigente en 2026: cierra en marzo").hasSize(1);
        assertThat(en2025.contenido()).as("vigente en 2025: empieza en diciembre").hasSize(1);
        assertThat(en2027.contenido()).as("ya habia cerrado").isEmpty();
    }

    @Test
    @DisplayName("un programa sin fecha de fin sigue vigente en todo ejercicio posterior")
    void unProgramaSinFechaDeFinSigueVigente() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar("PF-940", "Sin cierre", TipoDePrograma.PREDIAL, LocalDate.of(2026, 6, 1), null);

        assertThat(consultar(new CriterioDeProgramas("PF-940", 2026)).contenido()).hasSize(1);
        assertThat(consultar(new CriterioDeProgramas("PF-940", 2030)).contenido()).hasSize(1);
        assertThat(consultar(new CriterioDeProgramas("PF-940", 2025)).contenido()).isEmpty();
    }

    /**
     * Se siembra en <b>su propia municipalidad</b> y no en la de las demas pruebas: contar filas
     * exige que nadie mas escriba en la tabla, y las otras pruebas de esta clase dejan programas
     * sin fecha de fin —vigentes en todo ejercicio posterior— que se colarian en el total.
     */
    @Test
    @DisplayName("el total de la pagina cuenta todas las filas, no solo las devueltas")
    void elTotalCuentaTodasLasFilas() {
        TenantContext.fijar(new MunicipalidadId(municipalidadDelConteo));
        insertar("PF-951", "Uno", TipoDePrograma.PREDIAL, LocalDate.of(2027, 1, 1), null);
        insertar("PF-952", "Dos", TipoDePrograma.PREDIAL, LocalDate.of(2027, 1, 1), null);
        insertar("PF-953", "Tres", TipoDePrograma.PREDIAL, LocalDate.of(2027, 1, 1), null);

        Pagina<ProgramaFiscalizacion> primera =
                transaccion.execute(
                        estado ->
                                repositorio.consultar(
                                        new CriterioDeProgramas(null, 2027),
                                        Paginacion.de(0, 2, "codigo")));

        assertThat(primera.contenido()).hasSize(2);
        assertThat(primera.totalElementos()).isEqualTo(3);
        assertThat(primera.hayMas()).isTrue();
    }

    /**
     * El campo de ordenacion que pide el cliente <b>no se concatena</b>: pasa por la lista blanca
     * de {@code OrdenSeguro}, y lo que no esta en ella no llega a la consulta.
     *
     * <p>Siembra una fila primero a proposito: {@code paginar} cuenta antes de traer, y con cero
     * filas devuelve la pagina vacia sin llegar a componer el {@code ORDER BY} — la prueba pasaria
     * en verde sin haber ejercitado nada.
     */
    @Test
    @DisplayName("un orden que no esta en la lista blanca no llega a la consulta")
    void unOrdenFueraDeLaListaBlancaNoLlega() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar("PF-960", "Con orden", TipoDePrograma.PREDIAL, LocalDate.of(2026, 7, 1), null);

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                repositorio.consultar(
                                                        CriterioDeProgramas.todos(),
                                                        Paginacion.de(
                                                                0,
                                                                20,
                                                                "(SELECT clave FROM usuario)"))))
                .isInstanceOf(OrdenSeguro.OrdenNoAdmitido.class);
    }

    /**
     * La consulta abre su propia transaccion, y sin ella RLS no deja leer nada.
     *
     * <p>No es una prueba de cortesia: <b>sin transaccion no hay {@code SET LOCAL}</b>, y sin el la
     * politica de {@code programa_fiscalizacion} falla en vez de devolver filas. Es el defecto que
     * {@code ConsultaDeVias} cerro y que ha vuelto en cinco issues —#53 lo encontro otra vez en el
     * bucle de la corrida masiva—, asi que aqui se llama al caso de uso <b>por su proxy</b> y sin
     * ninguna transaccion alrededor.
     */
    @Test
    @DisplayName("la consulta abre su propia transaccion: sin ella, RLS no deja leer")
    void laConsultaAbreSuPropiaTransaccion() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        insertar(
                "PF-970",
                "Con su transaccion",
                TipoDePrograma.PREDIAL,
                LocalDate.of(2026, 9, 1),
                null);

        Pagina<ProgramaFiscalizacion> pagina =
                consulta.buscar(
                        new CriterioDeProgramas("PF-970", null), Paginacion.de(0, 20, "codigo"));

        assertThat(pagina.contenido()).hasSize(1);
        assertThat(pagina.contenido().get(0).codigo()).isEqualTo("PF-970");
    }

    // ------------------------------------------------------------------

    /** Envuelve el objetivo en un proxy transaccional de verdad. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private ProgramaFiscalizacion insertar(
            String codigo,
            String descripcion,
            TipoDePrograma tipo,
            LocalDate inicio,
            @Nullable LocalDate fin) {
        return transaccion.execute(
                estado ->
                        repositorio.insertar(
                                ProgramaFiscalizacion.nuevo(
                                        codigo, descripcion, tipo, inicio, fin)));
    }

    private Pagina<ProgramaFiscalizacion> consultar(CriterioDeProgramas criterio) {
        return transaccion.execute(
                estado -> repositorio.consultar(criterio, Paginacion.de(0, 20, "codigo")));
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
}
