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
import kamayuk.rentas.sanciones.dominio.CriterioDeNotificacion;
import kamayuk.rentas.sanciones.dominio.EstadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
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
 * La notificación administrativa previa (#47) contra PostgreSQL de verdad.
 *
 * <p>El AC "el listado de vencidas se calcula contra el plazo parametrizado" (#47 AC3) se verifica
 * aquí con dos filas que solo difieren en su {@code plazo_dias}: la comparación la hace el SQL
 * contra la columna de cada fila, nunca un número fijo.
 */
@DisplayName("#47 — NotificacionAdministrativaRepositoryJdbc")
class NotificacionAdministrativaRepositoryJdbcTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 1, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static NotificacionAdministrativaRepositoryJdbc repositorio;
    private static PapeletaRepositoryJdbc papeletas;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250701", "Municipalidad de notificaciones A");
        municipalidadB = crearMunicipalidad("250702", "Municipalidad de notificaciones B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new NotificacionAdministrativaRepositoryJdbc(jdbc);
        papeletas = new PapeletaRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("inspector.administrativo", null, null));
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
        @DisplayName("una notificacion se guarda y se relee por numero")
        void unaNotificacionSeGuardaYSeReleePorNumero() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            NotificacionAdministrativa guardada =
                    transaccion.execute(
                            estado -> repositorio.insertar(notificacionDe("NA-0001", (short) 10)));

            NotificacionAdministrativa releida =
                    transaccion.execute(estado -> repositorio.porNumero("NA-0001")).orElseThrow();

            assertThat(releida.id()).isEqualTo(guardada.id());
            assertThat(releida.plazoDias()).isEqualTo((short) 10);
            assertThat(releida.estado()).isEqualTo(EstadoDeNotificacion.EMITIDA);
        }

        @Test
        @DisplayName("subsanar persiste el cambio de estado")
        void subsanarPersisteElCambioDeEstado() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            NotificacionAdministrativa guardada =
                    transaccion.execute(
                            estado -> repositorio.insertar(notificacionDe("NA-0002", (short) 10)));

            NotificacionAdministrativa subsanada =
                    transaccion.execute(estado -> repositorio.subsanar(guardada.id()));

            assertThat(subsanada.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);

            NotificacionAdministrativa releida =
                    transaccion.execute(estado -> repositorio.porNumero("NA-0002")).orElseThrow();
            assertThat(releida.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("vencidas se calcula contra el plazo parametrizado de cada fila (#47 AC3)")
        void vencidasSeCalculaContraElPlazoParametrizadoDeCadaFila() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            // Misma fecha de emision, plazos distintos: solo una debe aparecer como vencida al
            // corte -si el codigo comparara contra un numero fijo, las dos caerian igual.
            transaccion.execute(
                    estado -> repositorio.insertar(notificacionDe("NA-0010", (short) 5)));
            transaccion.execute(
                    estado -> repositorio.insertar(notificacionDe("NA-0011", (short) 30)));

            LocalDate corte = FECHA.plusDays(10);

            Pagina<NotificacionAdministrativa> vencidas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    null, null, corte, null, null, null),
                                            Paginacion.de(0, 200, "fecha")));

            assertThat(vencidas.contenido())
                    .extracting(NotificacionAdministrativa::numero)
                    .contains("NA-0010")
                    .doesNotContain("NA-0011");
        }

        @Test
        @DisplayName("una notificacion sin plazoDias nunca aparece como vencida")
        void unaNotificacionSinPlazoDiasNuncaApareceComoVencida() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            transaccion.execute(estado -> repositorio.insertar(notificacionDe("NA-0012", null)));

            Pagina<NotificacionAdministrativa> vencidas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    "NA-0012",
                                                    null,
                                                    FECHA.plusYears(5),
                                                    null,
                                                    null,
                                                    null),
                                            Paginacion.de(0, 20, "fecha")));

            assertThat(vencidas.totalElementos()).isZero();
        }

        @Test
        @DisplayName("una notificacion subsanada no aparece como vencida")
        void unaNotificacionSubsanadaNoApareceComoVencida() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            NotificacionAdministrativa guardada =
                    transaccion.execute(
                            estado -> repositorio.insertar(notificacionDe("NA-0013", (short) 5)));
            transaccion.execute(estado -> repositorio.subsanar(guardada.id()));

            Pagina<NotificacionAdministrativa> vencidas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    "NA-0013",
                                                    null,
                                                    FECHA.plusDays(30),
                                                    null,
                                                    null,
                                                    null),
                                            Paginacion.de(0, 20, "fecha")));

            assertThat(vencidas.totalElementos()).isZero();
        }

        @Test
        @DisplayName("la busqueda de vencidas no cruza la municipalidad")
        void laBusquedaDeVencidasNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            transaccion.execute(
                    estado -> repositorio.insertar(notificacionDe("NA-0014", (short) 5)));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Pagina<NotificacionAdministrativa> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    null,
                                                    null,
                                                    FECHA.plusDays(30),
                                                    null,
                                                    null,
                                                    null),
                                            Paginacion.de(0, 20, "fecha")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        @Test
        @DisplayName("conPapeleta distingue las notificaciones ya enlazadas a una papeleta")
        void conPapeletaDistingueLasNotificacionesYaEnlazadas() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, "G-ADM-VENC");
            long contribuyenteId = crearContribuyente(municipalidadA, "20000001");

            NotificacionAdministrativa guardada =
                    transaccion.execute(
                            estado -> repositorio.insertar(notificacionDe("NA-0015", (short) 5)));
            transaccion.execute(
                    estado ->
                            papeletas.insertar(
                                    Papeleta.nuevaAdministrativa(
                                            "PA-VENC-0001",
                                            codigoId,
                                            FECHA,
                                            null,
                                            "Av. Grau",
                                            contribuyenteId,
                                            null,
                                            guardada.id(),
                                            contribuyenteId,
                                            Dinero.de("4950"),
                                            Alicuota.de("8"),
                                            Dinero.de("396"),
                                            Alicuota.de("100"),
                                            Dinero.de("396"),
                                            null,
                                            Observacion.de("papeleta ya generada"))));

            Pagina<NotificacionAdministrativa> conPapeleta =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    "NA-0015",
                                                    null,
                                                    FECHA.plusDays(30),
                                                    null,
                                                    null,
                                                    true),
                                            Paginacion.de(0, 20, "fecha")));
            Pagina<NotificacionAdministrativa> sinPapeleta =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscarVencidas(
                                            new CriterioDeNotificacion(
                                                    "NA-0015",
                                                    null,
                                                    FECHA.plusDays(30),
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fecha")));

            assertThat(conPapeleta.totalElementos()).isEqualTo(1);
            assertThat(sinPapeleta.totalElementos()).isZero();
        }
    }

    // ------------------------------------------------------------------

    private static NotificacionAdministrativa notificacionDe(String numero, Short plazoDias) {
        return NotificacionAdministrativa.emitida(
                numero, FECHA, null, null, "Av. Grau 123", "Falta administrativa", plazoDias);
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

    private static long crearCodigo(long municipalidadId, String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                    + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                    + " VALUES (?, 'ADMINISTRATIVA', ?, 'Infraccion de prueba',"
                                    + "         8.0000, 'Ordenanza de prueba', '2020-01-01')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
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
