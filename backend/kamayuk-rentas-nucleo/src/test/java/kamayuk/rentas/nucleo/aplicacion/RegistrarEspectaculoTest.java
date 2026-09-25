package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.EspectaculoPublicoRepositoryJdbc;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
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
 * {@code RegistrarEspectaculo} contra PostgreSQL real (#32).
 *
 * <p>Verifica que registrar el evento y determinar el impuesto es un solo acto, y que la alícuota
 * se busca <b>por clase de espectáculo del art. 57</b> —dos clases distintas, dos alícuotas
 * distintas, mismo conjunto sellado—, igual que {@code RT001ValorDeTerreno} busca el arancel por
 * vía.
 *
 * <p><b>El conjunto sellado es el del derivado que {@code normativa} despliega</b> (#376). Hasta
 * #376 se sembraban a mano {@code ALICUOTA_ESPECTACULO:CONCIERTO} y {@code :TEATRO}: ni el prefijo
 * ni las claves son los que se publican, y la prueba pasaba en verde mientras la operación real
 * contestaba siempre 422.
 */
@DisplayName("#32 — Registrar un espectaculo publico")
class RegistrarEspectaculoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long organizador;
    private static RegistrarEspectaculo registrar;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        organizador = crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        LectorDeParametros parametros =
                envolver(
                        new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)), gestor);
        AdministrarParametros administrarParametros =
                envolver(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);
        // El sellado necesita el contexto de tenant (conjunto_parametros tiene RLS) y el origen
        // de peticion (AdministrarParametros audita), y BeforeEach todavia no corrio: se fijan
        // aqui, y BeforeEach los vuelve a fijar antes de cada prueba sin que eso sea un problema.
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
        sellarElDerivado(administrarParametros);

        registrar =
                envolver(
                        new RegistrarEspectaculo(
                                new EspectaculoPublicoRepositoryJdbc(jdbc),
                                new DeterminacionRepositoryJdbc(jdbc),
                                parametros,
                                envolver(
                                        new kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc(
                                                new kamayuk.rentas.contribuyentes.infraestructura
                                                        .ContribuyenteRepositoryJdbc(jdbc),
                                                new kamayuk.rentas.contribuyentes.infraestructura
                                                        .FichaRepositoryJdbc(jdbc)),
                                        gestor),
                                new AuditoriaJdbc(jdbc, RELOJ)),
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("registra el evento y determina el impuesto con la alicuota de su clase")
    void registraElEventoYDeterminaElImpuestoConLaAlicuotaDeSuTipo() {
        Determinacion cine =
                registrar.registrar(
                        organizador,
                        "Festival de Cine de la Ciudad",
                        "CINEMATOGRAFICO",
                        "Estadio Municipal",
                        LocalDate.of(2026, 12, 15),
                        null,
                        null,
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));

        Determinacion carreras =
                registrar.registrar(
                        organizador,
                        "Clasico de Fin de Ano",
                        "CARRERAS-CABALLOS",
                        "Hipodromo Municipal",
                        LocalDate.of(2026, 12, 20),
                        null,
                        null,
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));

        assertThat(cine.montoDeterminado())
                .as("10% de 10000 = 1000.00")
                .isEqualTo(Dinero.de("1000.00"));
        assertThat(carreras.montoDeterminado())
                .as("15% de 10000 = 1500.00: mismo ingreso, otra alicuota por la clase")
                .isEqualTo(Dinero.de("1500.00"));
        assertThat(cine.montoDeterminado()).isNotEqualTo(carreras.montoDeterminado());
    }

    /**
     * El taurino a los dos lados del umbral, contra la UIT que el derivado publica para 2026
     * (#376).
     *
     * <p>5 500 × 0,5 % = 27,50: una entrada de 27,51 lo supera y paga el 10 %; una de 27,50 lo
     * iguala, «no es superior», y paga el 5 %.
     */
    @Test
    @DisplayName("#376 — el taurino paga el 10 % con la entrada a 27,51 y el 5 % a 27,50")
    void elTaurinoALosDosLadosDelUmbral() {
        Determinacion porEncima =
                registrar.registrar(
                        organizador,
                        "Corrida de la Feria",
                        "TAURINO",
                        "Plaza de Toros",
                        LocalDate.of(2026, 10, 3),
                        2000,
                        Dinero.de("27.51"),
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));
        Determinacion enElUmbral =
                registrar.registrar(
                        organizador,
                        "Novillada de la Feria",
                        "taurino",
                        "Plaza de Toros",
                        LocalDate.of(2026, 10, 4),
                        2000,
                        Dinero.de("27.50"),
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));

        assertThat(porEncima.montoDeterminado()).isEqualTo(Dinero.de("1000.00"));
        assertThat(porEncima.reglasAplicadas())
                .containsExactly("ESPECTACULO_ALICUOTA:TAURINO-SUPERIOR-0.5-UIT");
        assertThat(enElUmbral.montoDeterminado()).isEqualTo(Dinero.de("500.00"));
        assertThat(enElUmbral.reglasAplicadas())
                .containsExactly("ESPECTACULO_ALICUOTA:TAURINO-RESTO");
    }

    @Test
    @DisplayName("el evento queda registrado, con su propio id, ademas de la determinacion")
    void elEventoQuedaRegistrado() throws SQLException {
        registrar.registrar(
                organizador,
                "Otro Festival",
                "MUSICA-GENERAL",
                "Coliseo",
                LocalDate.of(2026, 11, 1),
                500,
                Dinero.de("50.00"),
                Dinero.de("5000.00"),
                Observacion.de("Registro de prueba"));

        assertThat(contarEventos()).isGreaterThanOrEqualTo(1L);
    }

    // ------------------------------------------------------------------

    private long contarEventos() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM espectaculo WHERE municipalidad_id = ? AND"
                                        + " estado = 'LIQUIDADO'")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    /** Sella 2026 con todo lo que el derivado publica para ese ejercicio, sin elegir (#376). */
    private static void sellarElDerivado(AdministrarParametros administrarParametros)
            throws SQLException {
        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(
                        new Ejercicio(2026),
                        Observacion.de("Conjunto 2026 del derivado de normativa"));
        for (java.util.Map.Entry<String, String> fila :
                DerivadoPublicado.numerosVigentesEn(2026).entrySet()) {
            String[] llave = fila.getKey().split("\\|", -1);
            administrarParametros.agregarParametro(
                    conjunto.id(),
                    parametro(llave[0], llave[1].isEmpty() ? null : llave[1], fila.getValue()),
                    Observacion.de("Fila del derivado publicable"));
        }
        administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
    }

    private static long parametro(String tipo, String clave, String valor) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, ?, DATE '2026-01-01', 'derivado"
                                        + " publicable de normativa', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setBigDecimal(3, new java.math.BigDecimal(valor));
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220601', 'Municipalidad de los espectaculos',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, 'C-ESP-1', 'RUC', '20505050501', 'JURIDICA',"
                                    + " 'ORGANIZADOR DE PRUEBA', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
