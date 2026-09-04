package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

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
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.Reporte;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.UnidadAfecta;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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

/**
 * La ficha del contribuyente que se imprime en ventanilla (RF-010), contra PostgreSQL real.
 *
 * <p>Lo que defiende: que el documento se pueda <b>reimprimir</b>. Una ficha de marzo tiene que dar
 * en 2029 lo mismo que dio en 2029 —el domicilio de marzo, la titularidad de marzo, la version de
 * la ficha de marzo—, porque si no, no sirve para explicar lo que ya se emitio (regla 9).
 *
 * <p>Y que no lleve ni un importe: el autovaluo es una regla de calculo bloqueada por D-02a.
 */
@DisplayName("RF-010 — Ficha del contribuyente")
class ReporteDeFichaDelContribuyenteTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);
    private static final LocalDate MARZO = LocalDate.of(2026, 3, 15);
    private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;

    private static ReporteDeFichaDelContribuyente reporte;
    private static ActualizarFichaCatastral fichas;
    private static PadronDePrueba padron;

    private static long contribuyente;
    private static long conFicha;
    private static long sinFicha;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("280101", "Municipalidad del reporte");
        otraMunicipalidad = crearMunicipalidad("280102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);
        FichaCatastralRepositoryJdbc repositorio = new FichaCatastralRepositoryJdbc(jdbc);
        padron = new PadronDePrueba();

        fichas =
                envolver(
                        new ActualizarFichaCatastral(
                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        reporte =
                envolver(
                        new ReporteDeFichaDelContribuyente(padron, catastro, repositorio, RELOJ),
                        gestor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
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
    void fijarContexto() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
        if (contribuyente == 0) {
            sembrar();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    private static void sembrar() throws SQLException {
        contribuyente = crearContribuyente("C-000900", "40900900", "SANTOS RIVERA, ELENA");
        padron.registrar(contribuyente, "C-000900", "SANTOS RIVERA, ELENA");
        padron.direccion(MARZO, "AV. GRAU 100");
        padron.direccion(JULIO, "JR. LIMA 250");

        conFicha = crearPredio("28010100100100101010001", "AV. GRAU 100");
        sinFicha = crearPredio("28010100100100101010002", "AV. NUEVA 200");
        titular(conFicha, contribuyente, "60.0000");
        titular(sinFicha, contribuyente, "100.0000");

        fichas.registrarPrimera(
                FichaCatastral.primera(
                        conFicha,
                        TipoFicha.UNICA,
                        new AreaM2(new BigDecimal("150.00")),
                        "CASA HABITACION",
                        ALTA,
                        OrigenDeLaFicha.DECLARACION_JURADA,
                        "Declaracion jurada 900-2026",
                        Observacion.de("Version inicial de la ficha del predio")),
                Observacion.de("Alta de la ficha por declaracion jurada"));
    }

    @Test
    @DisplayName("junta el padron y el catastro, y no lleva ni un importe")
    void juntaLosDosLadosSinImportes() {
        Optional<Reporte> hallado = reporte.de("C-000900", JULIO);

        assertThat(hallado).isPresent();
        Reporte impreso = hallado.get();

        assertThat(impreso.contribuyente().nombre()).isEqualTo("SANTOS RIVERA, ELENA");
        assertThat(impreso.domicilioFiscal()).isEqualTo("JR. LIMA 250");
        assertThat(impreso.unidades()).hasSize(2);

        UnidadAfecta conArea =
                impreso.unidades().stream()
                        .filter(unidad -> unidad.codigo().endsWith("0001"))
                        .findFirst()
                        .orElseThrow();

        assertThat(conArea.area()).isEqualTo(new AreaM2(new BigDecimal("150.00")));
        assertThat(conArea.uso()).isEqualTo("CASA HABITACION");
        assertThat(conArea.porcentaje().valor()).isEqualByComparingTo(new BigDecimal("60.0000"));
    }

    @Test
    @DisplayName("un predio registrado y todavia sin ficha sale, y sale sin area")
    void unPredioSinFichaSaleSinArea() {
        UnidadAfecta pendiente =
                reporte.de("C-000900", JULIO).orElseThrow().unidades().stream()
                        .filter(unidad -> unidad.codigo().endsWith("0002"))
                        .findFirst()
                        .orElseThrow();

        assertThat(pendiente.area())
                .as(
                        "un cero se leeria como un terreno de cero metros, que es una cifra; esto"
                                + " es la ausencia de una, y el predio esta pendiente de fichar")
                .isNull();
        assertThat(pendiente.uso()).isNull();
        assertThat(pendiente.version()).isNull();
    }

    @Test
    @DisplayName("reimprimir a una fecha pasada da la direccion de ENTONCES")
    void reimprimirDaLaDireccionDeEntonces() {
        assertThat(reporte.de("C-000900", MARZO).orElseThrow().domicilioFiscal())
                .as(
                        "con «la ultima» direccion, el documento no explicaria la notificacion que"
                                + " se hizo en marzo")
                .isEqualTo("AV. GRAU 100");
    }

    @Test
    @DisplayName("el reporte dice a que fecha esta armado")
    void elReporteDiceSuFecha() {
        assertThat(reporte.de("C-000900", null).orElseThrow().aLaFecha())
                .as("toda cifra impresa dice de cuando es (regla 9); sin fecha, el papel no sirve")
                .isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    @DisplayName("un codigo que no existe no devuelve un reporte vacio")
    void unCodigoInexistenteNoDevuelveReporteVacio() {
        assertThat(reporte.de("C-999999", JULIO))
                .as("un reporte con nombre en blanco y cero unidades se imprimiria igual")
                .isEmpty();
    }

    @Test
    @DisplayName("desde otra municipalidad no hay unidades que imprimir")
    void desdeOtraMunicipalidadNoHayUnidades() {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

        assertThat(reporte.de("C-000900", JULIO).orElseThrow().unidades())
                .as("la prueba corre como sgtm_app, que es a quien la politica RLS aplica")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Doble de la API publica del padron; su busqueda real la prueba su propio modulo. */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        private final Map<String, ResumenDeContribuyente> porCodigo = new java.util.HashMap<>();
        private final java.util.TreeMap<LocalDate, String> direcciones = new java.util.TreeMap<>();

        void registrar(long id, String codigo, String nombre) {
            porCodigo.put(codigo, new ResumenDeContribuyente(id, codigo, nombre, "DNI 40900900"));
        }

        void direccion(LocalDate desde, String direccion) {
            direcciones.put(desde, direccion);
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return Optional.ofNullable(porCodigo.get(codigo));
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.ofNullable(direcciones.floorEntry(fecha)).map(Map.Entry::getValue);
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

    private static long crearContribuyente(String codigo, String dni, String nombre)
            throws SQLException {
        return insertar(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente, tipo_documento,"
                        + " numero_documento, tipo_persona, nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra') RETURNING id",
                codigo,
                dni,
                nombre);
    }

    private static long crearPredio(String codigo, String direccion) throws SQLException {
        return insertar(
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', ?) RETURNING id",
                codigo,
                direccion);
    }

    private static void titular(long predioId, long contribuyenteId, String porcentaje)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, 'COPROPIETARIO', ?::numeric, ?,"
                                    + " 'MINUTA-900')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setString(4, porcentaje);
                sentencia.setObject(5, ALTA);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static long insertar(String sql, String... valores) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setLong(1, municipalidad);
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setString(i + 2, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
