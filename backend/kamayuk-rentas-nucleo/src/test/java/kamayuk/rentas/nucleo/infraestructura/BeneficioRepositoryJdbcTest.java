package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

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
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.Beneficio;
import kamayuk.rentas.nucleo.dominio.Clase;
import kamayuk.rentas.nucleo.dominio.CriterioDeBeneficio;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Beneficios y exoneraciones contra PostgreSQL de verdad, conectado como {@code kamayuk_app}. */
@DisplayName("RF-029 — Beneficios y exoneraciones")
class BeneficioRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static BeneficioRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("250101", "Municipalidad de beneficios A");
        municipalidadB = crearMunicipalidad("250102", "Municipalidad de beneficios B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new BeneficioRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura")
    class Escritura {

        @Test
        @DisplayName("un alta se guarda y se relee")
        void unAltaSeGuardaYSeRelee() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "B-0001", "70100001");

            Beneficio guardado =
                    transaccion.execute(
                            estado -> repositorio.insertar(beneficioDe(titular, "PENSIONISTA")));

            assertThat(guardado.id()).isNotNull();

            Optional<Beneficio> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));
            assertThat(releido).isPresent();
            assertThat(releido.get().tipo()).isEqualTo("PENSIONISTA");
            assertThat(releido.get().estaVigente()).isTrue();
        }

        @Test
        @DisplayName("cesar deja la fila con vigencia_hasta, no la borra")
        void cesarDejaLaFilaConVigenciaHasta() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "B-0002", "70100002");

            Beneficio guardado =
                    transaccion.execute(
                            estado -> repositorio.insertar(beneficioDe(titular, "PENSIONISTA")));

            Beneficio cesado =
                    transaccion.execute(
                            estado ->
                                    repositorio.actualizar(
                                            guardado.cesadoEl(LocalDate.of(2026, 6, 30))));

            Optional<Beneficio> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));

            assertThat(cesado.estaVigente()).isFalse();
            assertThat(releido).isPresent();
            assertThat(releido.get().vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(releido.get().tipo())
                    .as("el resto de la fila no se toca")
                    .isEqualTo("PENSIONISTA");
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("delContribuyente filtra por tipo")
        void delContribuyenteFiltraPorTipo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "B-0010", "70100010");

            transaccion.execute(
                    estado -> repositorio.insertar(beneficioDe(titular, "PENSIONISTA")));
            transaccion.execute(
                    estado -> repositorio.insertar(beneficioDe(titular, "MONUMENTO_HISTORICO")));

            List<Beneficio> pensionista =
                    transaccion.execute(
                            estado -> repositorio.delContribuyente(titular, "PENSIONISTA"));

            assertThat(pensionista).hasSize(1);
            assertThat(pensionista.get(0).tipo()).isEqualTo("PENSIONISTA");
        }

        @Test
        @DisplayName("la consulta por codigo no cruza la municipalidad")
        void laConsultaPorCodigoNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titularA = crearContribuyente(municipalidadA, "B-0020", "70100020");
            transaccion.execute(
                    estado -> repositorio.insertar(beneficioDe(titularA, "PENSIONISTA")));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            crearContribuyente(municipalidadB, "B-0020", "70100021");

            Pagina<Beneficio> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeBeneficio("B-0020", null, null),
                                            Paginacion.de(0, 20, "vigencia_desde")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        @Test
        @DisplayName("vigentesA solo devuelve lo que rige a esa fecha")
        void vigentesASoloDevuelveLoQueRigeAEsaFecha() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "B-0030", "70100030");

            Beneficio guardado =
                    transaccion.execute(
                            estado -> repositorio.insertar(beneficioDe(titular, "PENSIONISTA")));
            transaccion.execute(
                    estado -> repositorio.actualizar(guardado.cesadoEl(LocalDate.of(2026, 3, 31))));

            Pagina<Beneficio> enFebrero =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeBeneficio(
                                                    null, null, LocalDate.of(2026, 2, 1)),
                                            Paginacion.de(0, 20, "vigencia_desde")));
            Pagina<Beneficio> enJulio =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeBeneficio(
                                                    null, null, LocalDate.of(2026, 7, 1)),
                                            Paginacion.de(0, 20, "vigencia_desde")));

            assertThat(enFebrero.totalElementos()).isGreaterThanOrEqualTo(1);
            assertThat(enFebrero.contenido().stream().anyMatch(b -> b.id().equals(guardado.id())))
                    .isTrue();
            assertThat(enJulio.contenido().stream().anyMatch(b -> b.id().equals(guardado.id())))
                    .as("ya se ceso el 31 de marzo, en julio no rige")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------

    private static Beneficio beneficioDe(long contribuyenteId, String tipo) {
        return Beneficio.nuevo(
                contribuyenteId,
                null,
                null,
                tipo,
                "PREDIAL",
                Clase.DEDUCCION,
                Alicuota.de("50"),
                null,
                LocalDate.of(2026, 1, 1),
                "Ley 27157",
                "RESOLUCION-2026-0001",
                Observacion.de("Se registra el beneficio para la prueba"));
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
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
