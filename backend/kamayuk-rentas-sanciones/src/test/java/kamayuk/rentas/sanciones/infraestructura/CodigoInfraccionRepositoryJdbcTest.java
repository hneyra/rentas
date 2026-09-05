package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.Familia;
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

/**
 * El catálogo de códigos de infracción (#43) contra PostgreSQL de verdad, como {@code kamayuk_app}.
 */
@DisplayName("#43 — Catálogo de códigos de infracción")
class CodigoInfraccionRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static CodigoInfraccionRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("260101", "Municipalidad de infracciones A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad de infracciones B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new CodigoInfraccionRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.sanciones", null, null));
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

            CodigoInfraccion guardado =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            codigoDe("W-0001", LocalDate.of(2026, 1, 1))));

            assertThat(guardado.id()).isNotNull();

            Optional<CodigoInfraccion> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));
            assertThat(releido).isPresent();
            assertThat(releido.get().codigo()).isEqualTo("W-0001");
            assertThat(releido.get().estaVigente()).isTrue();
        }

        @Test
        @DisplayName("modificar cierra la version vigente con vigencia_hasta, no la borra")
        void modificarCierraLaVersionVigente() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            CodigoInfraccion original =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            codigoDe("W-0002", LocalDate.of(2026, 1, 1))));

            CodigoInfraccion cerrado =
                    transaccion.execute(
                            estado ->
                                    repositorio.actualizar(
                                            original.cerradoEl(LocalDate.of(2026, 6, 30))));
            CodigoInfraccion nuevaVersion =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            codigoDe("W-0002", LocalDate.of(2026, 7, 1))));

            Optional<CodigoInfraccion> anteriorReleida =
                    transaccion.execute(estado -> repositorio.findById(original.id()));

            assertThat(cerrado.estaVigente()).isFalse();
            assertThat(anteriorReleida).isPresent();
            assertThat(anteriorReleida.get().vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(nuevaVersion.id()).isNotEqualTo(original.id());
            assertThat(nuevaVersion.estaVigente()).isTrue();
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("vigenteA devuelve la version vigente a esa fecha, no la ultima")
        void vigenteADevuelveLaVersionVigenteAEsaFecha() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            CodigoInfraccion original =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            codigoDe("W-0010", LocalDate.of(2026, 1, 1))));
            transaccion.execute(
                    estado ->
                            repositorio.actualizar(original.cerradoEl(LocalDate.of(2026, 6, 30))));
            transaccion.execute(
                    estado -> repositorio.insertar(codigoDe("W-0010", LocalDate.of(2026, 7, 1))));

            Optional<CodigoInfraccion> enMarzo =
                    transaccion.execute(
                            estado ->
                                    repositorio.vigenteA(
                                            Familia.TRANSITO, "W-0010", LocalDate.of(2026, 3, 1)));
            Optional<CodigoInfraccion> enAgosto =
                    transaccion.execute(
                            estado ->
                                    repositorio.vigenteA(
                                            Familia.TRANSITO, "W-0010", LocalDate.of(2026, 8, 1)));

            assertThat(enMarzo).isPresent();
            assertThat(enMarzo.get().id()).isEqualTo(original.id());
            assertThat(enAgosto).isPresent();
            assertThat(enAgosto.get().id()).isNotEqualTo(original.id());
        }

        @Test
        @DisplayName("la busqueda por codigo no cruza la municipalidad")
        void laBusquedaPorCodigoNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            transaccion.execute(
                    estado -> repositorio.insertar(codigoDe("W-0020", LocalDate.of(2026, 1, 1))));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Pagina<CodigoInfraccion> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeCodigoInfraccion(
                                                    Familia.TRANSITO, "W-0020", null, null),
                                            Paginacion.de(0, 20, "codigo")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        @Test
        @DisplayName("buscar filtra por familia: el mismo codigo puede existir en la otra familia")
        void buscarFiltraPorFamilia() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            transaccion.execute(
                    estado -> repositorio.insertar(codigoDe("W-0030", LocalDate.of(2026, 1, 1))));
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    new CodigoInfraccion(
                                            null,
                                            Familia.ADMINISTRATIVA,
                                            "W-0030",
                                            "Comercio sin licencia",
                                            Alicuota.de("15"),
                                            null,
                                            null,
                                            "CUIS-2026",
                                            LocalDate.of(2026, 1, 1),
                                            null)));

            Pagina<CodigoInfraccion> transito =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeCodigoInfraccion(
                                                    Familia.TRANSITO, "W-0030", null, null),
                                            Paginacion.de(0, 20, "codigo")));
            Pagina<CodigoInfraccion> administrativa =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeCodigoInfraccion(
                                                    Familia.ADMINISTRATIVA, "W-0030", null, null),
                                            Paginacion.de(0, 20, "codigo")));

            assertThat(transito.totalElementos()).isEqualTo(1);
            assertThat(administrativa.totalElementos()).isEqualTo(1);
            assertThat(transito.contenido().get(0).baseLegal())
                    .isNotEqualTo(administrativa.contenido().get(0).baseLegal());
        }
    }

    // ------------------------------------------------------------------

    private static CodigoInfraccion codigoDe(String codigo, LocalDate vigenciaDesde) {
        return CodigoInfraccion.nuevo(
                Familia.TRANSITO,
                codigo,
                "Exceso de velocidad",
                Alicuota.de("8"),
                "Retencion de licencia",
                (short) 4,
                "RNT art. 300",
                vigenciaDesde);
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (var resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }
}
