package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.Agregacion;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeudaPorContribuyente;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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

/**
 * {@code ConsultarDeuda.porContribuyente} contra PostgreSQL real (RF-041, #25).
 *
 * <p>Lo que da valor a este archivo: que <b>varios periodos de la misma obligacion</b> —tributo,
 * ejercicio y unidad— se agregan en una sola fila con {@link CalculoDeDeuda#deudaActualizadaA}
 * corrido sobre todos sus asientos juntos, mientras que dos obligaciones distintas salen en filas
 * separadas; que el filtro de {@code fase} y la paginacion trabajan sobre esas filas ya agregadas,
 * no sobre los asientos sueltos; y que una fecha de corte pasada excluye lo posterior (regla 9),
 * igual que ya prueba {@code CalculoDeDeudaTest} a nivel puro.
 */
@DisplayName("RF-041 — consulta_deuda: la deuda de todas las obligaciones de un contribuyente")
class ConsultarDeudaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Observacion OBSERVACION = Observacion.de("asiento de la prueba");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static RegistrarAsiento registrarAsiento;
    private static ConsultarDeuda consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250102", "Municipalidad de consulta_deuda");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);

        registrarAsiento =
                envolver(
                        new RegistrarAsiento(asientos, saldos, new AuditoriaDePrueba(), RELOJ),
                        gestor);
        consulta =
                envolver(
                        new ConsultarDeuda(
                                asientos,
                                saldos,
                                new CalculoDeDeuda(new SinAcumulacionDePrueba()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                                RELOJ),
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
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("dos obligaciones distintas del contribuyente salen en dos filas")
    void dosObligacionesDistintasSonDosFilas() {
        String codigo = crearContribuyenteConCodigo("D-0001", "80400001");
        long titular = idDe(codigo);

        cargar(titular, "PREDIAL", 2026, 0, null, Dinero.de(1000), Fase.ORDINARIA);
        cargar(titular, "ARBITRIO", 2026, 3, null, Dinero.de(200), Fase.ORDINARIA);

        Pagina<ObligacionConDeuda> pagina =
                consultar(codigo, LocalDate.of(2026, 6, 1), null, 0, 20);

        assertThat(pagina.totalElementos()).isEqualTo(2);
        assertThat(pagina.contenido())
                .extracting(ObligacionConDeuda::tributo, o -> o.deuda().insoluto())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("PREDIAL", Dinero.de(1000)),
                        org.assertj.core.groups.Tuple.tuple("ARBITRIO", Dinero.de(200)));
    }

    @Test
    @DisplayName(
            "varios periodos de la MISMA obligacion se agregan en una sola fila, no una por cuota")
    void variosPeriodosDeLaMismaObligacionSonUnaFila() {
        String codigo = crearContribuyenteConCodigo("D-0002", "80400002");
        long titular = idDe(codigo);

        cargar(titular, "ARBITRIO", 2026, 3, null, Dinero.de(100), Fase.ORDINARIA);
        cargar(titular, "ARBITRIO", 2026, 7, null, Dinero.de(150), Fase.ORDINARIA);

        Pagina<ObligacionConDeuda> pagina =
                consultar(codigo, LocalDate.of(2026, 6, 1), null, 0, 20);

        assertThat(pagina.totalElementos()).isEqualTo(1);
        ObligacionConDeuda fila = pagina.contenido().get(0);
        assertThat(fila.periodoDesde()).isEqualTo(3);
        assertThat(fila.periodoHasta()).isEqualTo(7);
        assertThat(fila.deuda().insoluto())
                .as("las dos cuotas se netean juntas, no una fila por cuota")
                .isEqualTo(Dinero.de(250));
    }

    @Test
    @DisplayName("el filtro de fase deja fuera las obligaciones en otra etapa de cobranza")
    void elFiltroDeFaseDejaFueraLasDeOtraEtapa() {
        String codigo = crearContribuyenteConCodigo("D-0003", "80400003");
        long titular = idDe(codigo);

        cargar(titular, "PREDIAL", 2026, 0, null, Dinero.de(500), Fase.ORDINARIA);
        cargar(titular, "ARBITRIO", 2027, 0, null, Dinero.de(300), Fase.COACTIVA);

        Pagina<ObligacionConDeuda> pagina =
                consultar(codigo, LocalDate.of(2026, 6, 1), Fase.COACTIVA, 0, 20);

        assertThat(pagina.contenido())
                .singleElement()
                .satisfies(fila -> assertThat(fila.tributo()).isEqualTo("ARBITRIO"));
    }

    @Test
    @DisplayName("la paginacion trabaja sobre las obligaciones ya agregadas, con el total correcto")
    void laPaginacionTrabajaSobreLasObligacionesAgregadas() {
        String codigo = crearContribuyenteConCodigo("D-0004", "80400004");
        long titular = idDe(codigo);

        // Solo hay particiones para 2026 y 2027 (V2): la tercera obligacion distinta se
        // consigue variando el tributo, no el ejercicio.
        cargar(titular, "PREDIAL", 2026, 0, null, Dinero.de(100), Fase.ORDINARIA);
        cargar(titular, "ARBITRIO", 2026, 0, null, Dinero.de(100), Fase.ORDINARIA);
        cargar(titular, "PREDIAL", 2027, 0, null, Dinero.de(100), Fase.ORDINARIA);

        Pagina<ObligacionConDeuda> primera =
                consultar(codigo, LocalDate.of(2026, 6, 1), null, 0, 2);
        assertThat(primera.contenido()).hasSize(2);
        assertThat(primera.totalElementos()).isEqualTo(3);
        assertThat(primera.hayMas()).isTrue();

        Pagina<ObligacionConDeuda> segunda =
                consultar(codigo, LocalDate.of(2026, 6, 1), null, 1, 2);
        assertThat(segunda.contenido()).hasSize(1);
        assertThat(segunda.hayMas()).isFalse();
    }

    @Test
    @DisplayName("un codigo de contribuyente que no existe da una pagina vacia, no un error")
    void unCodigoInexistenteDaPaginaVacia() {
        Pagina<ObligacionConDeuda> pagina =
                consultar("NO-EXISTE-9999", LocalDate.of(2026, 6, 1), null, 0, 20);

        assertThat(pagina.estaVacia()).isTrue();
        assertThat(pagina.totalElementos()).isZero();
    }

    @Test
    @DisplayName(
            "todasLasObligacionesDe agrega igual que porContribuyente, sin pagina ni filtro de"
                    + " fase")
    void todasLasObligacionesDeAgregaIgualQuePorContribuyente() {
        String codigo = crearContribuyenteConCodigo("D-0006", "80400006");
        long titular = idDe(codigo);

        // Dos periodos de la misma obligacion (se agregan en una fila) y una obligacion
        // distinta en otra fase, para probar las dos cosas que porContribuyente ya prueba
        // por separado: el neteo por obligacion, y que no filtra fase.
        cargar(titular, "ARBITRIO", 2026, 3, null, Dinero.de(100), Fase.ORDINARIA);
        cargar(titular, "ARBITRIO", 2026, 7, null, Dinero.de(150), Fase.ORDINARIA);
        cargar(titular, "PREDIAL", 2027, 0, null, Dinero.de(300), Fase.COACTIVA);

        List<ObligacionConDeuda> todas =
                consulta.todasLasObligacionesDe(titular, LocalDate.of(2026, 6, 1));

        assertThat(todas)
                .as("las dos obligaciones, en cualquier fase: este metodo no filtra fase")
                .hasSize(2);
        ObligacionConDeuda arbitrios =
                todas.stream()
                        .filter(o -> "ARBITRIO".equals(o.tributo()))
                        .findFirst()
                        .orElseThrow();
        assertThat(arbitrios.periodoDesde()).isEqualTo(3);
        assertThat(arbitrios.periodoHasta()).isEqualTo(7);
        assertThat(arbitrios.deuda().insoluto())
                .as("las dos cuotas se netean juntas, igual que en porContribuyente")
                .isEqualTo(Dinero.de(250));
        ObligacionConDeuda predial =
                todas.stream().filter(o -> "PREDIAL".equals(o.tributo())).findFirst().orElseThrow();
        assertThat(predial.fase())
                .as("distinta obligacion, con su propia fase, aunque el corte sea el mismo")
                .isEqualTo(Fase.COACTIVA);
    }

    @Test
    @DisplayName("un contribuyente sin ningun asiento da una lista vacia, no un error")
    void unContribuyenteSinAsientosDaListaVacia() {
        String codigo = crearContribuyenteConCodigo("D-0007", "80400007");
        long titular = idDe(codigo);

        assertThat(consulta.todasLasObligacionesDe(titular, LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    @DisplayName("un asiento con fecha posterior a la de corte no entra en el calculo (regla 9)")
    void unAsientoPosteriorALaFechaDeCorteNoCuenta() {
        String codigo = crearContribuyenteConCodigo("D-0005", "80400005");
        long titular = idDe(codigo);

        cargarConFecha(
                titular,
                "PREDIAL",
                2026,
                0,
                null,
                Dinero.de(1000),
                Fase.ORDINARIA,
                LocalDate.of(2026, 3, 1));
        cargarConFecha(
                titular,
                "PREDIAL",
                2026,
                0,
                null,
                Dinero.de(500),
                Fase.ORDINARIA,
                LocalDate.of(2026, 7, 1));

        Pagina<ObligacionConDeuda> pagina =
                consultar(codigo, LocalDate.of(2026, 6, 1), null, 0, 20);

        assertThat(pagina.contenido())
                .singleElement()
                .satisfies(
                        fila ->
                                assertThat(fila.deuda().insoluto())
                                        .as("el asiento de julio es posterior al corte de junio")
                                        .isEqualTo(Dinero.de(1000)));
    }

    // ------------------------------------------------------------------

    private static Pagina<ObligacionConDeuda> consultar(
            String codigo, LocalDate fecha, @Nullable Fase fase, int pagina, int tamano) {
        CriterioDeDeudaPorContribuyente criterio =
                new CriterioDeDeudaPorContribuyente(codigo, fecha, fase, Agregacion.POR_OBLIGACION);
        return consulta.porContribuyente(
                criterio,
                new Paginacion(pagina, tamano, "ejercicio", Paginacion.Direccion.ASCENDENTE));
    }

    private void cargar(
            long titular,
            String tributo,
            int ejercicio,
            int periodo,
            @Nullable Long predioId,
            Dinero insoluto,
            Fase fase) {
        cargarConFecha(
                titular,
                tributo,
                ejercicio,
                periodo,
                predioId,
                insoluto,
                fase,
                LocalDate.of(2026, 3, 1));
    }

    private void cargarConFecha(
            long titular,
            String tributo,
            int ejercicio,
            int periodo,
            @Nullable Long predioId,
            Dinero insoluto,
            Fase fase,
            LocalDate fechaValor) {
        Asiento asiento =
                Asiento.nuevo(
                        new Ejercicio(ejercicio),
                        titular,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        fase,
                        periodo,
                        predioId,
                        null,
                        null,
                        insoluto,
                        fechaValor,
                        "RES-PRUEBA-0001");
        registrarAsiento.asentar(asiento, OBSERVACION);
    }

    private static long idDe(String codigo) {
        return java.util.Objects.requireNonNull(CODIGOS.get(codigo));
    }

    private static final java.util.Map<String, Long> CODIGOS = new java.util.HashMap<>();

    private static String crearContribuyenteConCodigo(String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    CODIGOS.put(codigo, id);
                    return codigo;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
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

    /** No acumula nada: estas pruebas miran el insoluto agregado, no la mora (D-02). */
    private static final class SinAcumulacionDePrueba implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /** Auditoria de prueba: no verifica el rastro, solo que el asiento se pueda guardar. */
    private static final class AuditoriaDePrueba implements kamayuk.rentas.auditoria.Auditoria {
        @Override
        public void registrar(kamayuk.rentas.auditoria.RegistroDeAuditoria registro) {
            // sin base: esta prueba no verifica la pista de auditoria.
        }
    }
}
