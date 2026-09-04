package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
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
 * Las papeletas —de las dos familias, #46 y #47— contra PostgreSQL de verdad, conectado como {@code
 * sgtm_app}.
 *
 * <p>El AC "reimprimir una papeleta de hace tres años devuelve los mismos seis importes" se
 * verifica aquí releyendo la fila tal cual quedó guardada: nada en el camino de lectura recalcula
 * nada.
 */
@DisplayName("#46/#47 — Papeletas")
class PapeletaRepositoryJdbcTest {

    private static final LocalDate FECHA = LocalDate.of(2023, 3, 15);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static PapeletaRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    /**
     * A quién se le cobra la multa en las papeletas de tránsito de esta prueba.
     *
     * <p>{@code papeleta.obligado_id} es {@code NOT NULL} y tiene clave foránea desde #50 (V41 §1):
     * sin un contribuyente de verdad detrás, ningún {@code INSERT} de papeleta entra.
     */
    private static long obligadoDeA;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250601", "Municipalidad de papeletas A");
        municipalidadB = crearMunicipalidad("250602", "Municipalidad de papeletas B");
        obligadoDeA = crearContribuyente(municipalidadA, "19000001");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new PapeletaRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("inspector.transito", null, null));
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
        @DisplayName("reimprimir devuelve los mismos seis importes, sin recalcular nada")
        void reimprimirDevuelveLosMismosSeisImportes() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0001");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(papeletaTransitoDe("PT-0001", codigoId)));

            Papeleta reimpresa =
                    transaccion.execute(estado -> repositorio.porNumero("PT-0001")).orElseThrow();

            assertThat(reimpresa.baseImponible()).isEqualTo(Dinero.de("4950"));
            assertThat(reimpresa.porcentajeInfraccion()).isEqualTo(Alicuota.de("8"));
            assertThat(reimpresa.importeInfraccion()).isEqualTo(Dinero.de("396"));
            assertThat(reimpresa.porcentajeACobrar()).isEqualTo(Alicuota.de("100"));
            assertThat(reimpresa.importeAPagar()).isEqualTo(Dinero.de("396"));
            assertThat(reimpresa.importeConBeneficio()).isEqualTo(Dinero.de("198"));
            assertThat(reimpresa.id()).isEqualTo(guardada.id());
        }

        @Test
        @DisplayName("cambiar el numero deja traza y no cambia el id ni el desglose")
        void cambiarElNumeroDejaTraza() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0002");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(papeletaTransitoDe("PT-0002", codigoId)));

            Papeleta renumerada =
                    transaccion.execute(
                            estado ->
                                    repositorio.cambiarNumero(
                                            guardada.id(),
                                            "PT-0002-B",
                                            "correccion de digitacion"));

            assertThat(renumerada.id()).isEqualTo(guardada.id());
            assertThat(renumerada.numero()).isEqualTo("PT-0002-B");
            assertThat(renumerada.importeAPagar()).isEqualTo(guardada.importeAPagar());

            long trazas = transaccion.execute(estado -> contarTrazas(guardada.id()));
            assertThat(trazas).isEqualTo(1L);
        }

        @Test
        @DisplayName("una papeleta administrativa se guarda sin notificacion previa (#47 AC1)")
        void unaPapeletaAdministrativaSeGuardaSinNotificacionPrevia() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-01");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000001");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            papeletaAdministrativaDe(
                                                    "PA-0001",
                                                    codigoId,
                                                    contribuyenteId,
                                                    null,
                                                    null)));

            assertThat(guardada.id()).isNotNull();
            assertThat(guardada.familia()).isEqualTo(Familia.ADMINISTRATIVA);
            assertThat(guardada.notificacionPreviaId()).isNull();
            assertThat(guardada.placa()).isNull();
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("la busqueda por placa no cruza la municipalidad")
        void laBusquedaPorPlacaNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0003");
            transaccion.execute(
                    estado -> repositorio.insertar(papeletaTransitoDe("PT-0003", codigoId)));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Pagina<Papeleta> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            criterioTransito("ABC-123"),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        @Test
        @DisplayName("soloPendientes excluye PAGADA, ANULADA y PRESCRITA")
        void soloPendientesExcluyeLasCerradas() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0004");
            transaccion.execute(
                    estado -> repositorio.insertar(papeletaTransitoDe("PT-0004", codigoId)));

            Pagina<Papeleta> pendientes =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.TRANSITO,
                                                    "PT-0004",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    true),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(pendientes.totalElementos()).isEqualTo(1);
        }

        @Test
        @DisplayName("la busqueda de administrativa no devuelve papeletas de transito")
        void laBusquedaDeAdministrativaNoDevuelvePapeletasDeTransito() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoTransito = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0005");
            transaccion.execute(
                    estado -> repositorio.insertar(papeletaTransitoDe("PT-0005", codigoTransito)));

            Pagina<Papeleta> administrativas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(administrativas.totalElementos()).isZero();
        }

        @Test
        @DisplayName("busca la papeleta administrativa por el documento del contribuyente")
        void buscaLaPapeletaAdministrativaPorElDocumentoDelContribuyente() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-02");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000002");
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    papeletaAdministrativaDe(
                                            "PA-0002", codigoId, contribuyenteId, null, null)));

            Pagina<Papeleta> encontradas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    "10000002",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(encontradas.totalElementos()).isEqualTo(1);
        }

        @Test
        @DisplayName("busca por el codigo del catalogo, con el JOIN a codigo_infraccion")
        void buscaPorElCodigoDelCatalogo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-03");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000003");
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    papeletaAdministrativaDe(
                                            "PA-0003", codigoId, contribuyenteId, null, null)));

            Pagina<Papeleta> encontradas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    "G-ADM-03",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(encontradas.totalElementos()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------

    private static CriterioDePapeleta criterioTransito(String placa) {
        return new CriterioDePapeleta(
                Familia.TRANSITO, null, placa, null, null, null, null, null, null, null, false);
    }

    private static Papeleta papeletaTransitoDe(String numero, long codigoInfraccionId) {
        return Papeleta.nuevaTransito(
                numero,
                codigoInfraccionId,
                FECHA,
                null,
                "Av. Grau",
                "ABC-123",
                null,
                null,
                null,
                null,
                obligadoDeA,
                Dinero.de("4950"),
                Alicuota.de("8"),
                Dinero.de("396"),
                Alicuota.de("100"),
                Dinero.de("396"),
                Dinero.de("198"),
                Observacion.de("Se registra para la prueba"));
    }

    private static Papeleta papeletaAdministrativaDe(
            String numero,
            long codigoInfraccionId,
            Long contribuyenteId,
            Long predioId,
            Long notificacionPreviaId) {
        return Papeleta.nuevaAdministrativa(
                numero,
                codigoInfraccionId,
                FECHA,
                null,
                "Av. Grau",
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                1L,
                Dinero.de("4950"),
                Alicuota.de("8"),
                Dinero.de("396"),
                Alicuota.de("100"),
                Dinero.de("396"),
                Dinero.de("198"),
                Observacion.de("Se registra para la prueba"));
    }

    private static long contarTrazas(long papeletaId) {
        return jdbc.sql(
                        "SELECT count(*) FROM papeleta_cambio_numero WHERE papeleta_id = :papeletaId")
                .param("papeletaId", papeletaId)
                .query(Long.class)
                .single();
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

    private static long crearCodigo(long municipalidadId, Familia familia, String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                    + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                    + " VALUES (?, ?, ?, 'Infraccion de prueba', 8.0000,"
                                    + "         'Base legal de prueba', '2020-01-01')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, familia.name());
                sentencia.setString(3, codigo);
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

    private static long crearContribuyente(long municipalidadId, String numeroDocumento) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente"
                                    + " (municipalidad_id, codigo_contribuyente, tipo_documento,"
                                    + "  numero_documento, tipo_persona, nombre_razon_social,"
                                    + "  usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'Contribuyente de"
                                    + " prueba', 'prueba') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, "C-" + numeroDocumento);
                sentencia.setString(3, numeroDocumento);
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
