package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los predios de un contribuyente, la API publica que {@code consulta_predios} y {@code
 * consulta_resumen_predial} necesitan de este contexto (#25).
 */
@DisplayName("Predios de un contribuyente (#25)")
class PrediosDelContribuyenteCatastroTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    private static TransactionTemplate transaccion;
    private static CatastroRepositoryJdbc repositorio;
    private static RegistrarPredio registrar;
    private static PrediosDelContribuyenteCatastro consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230201", "Municipalidad de los predios");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new CatastroRepositoryJdbc(jdbc);
        // Envuelto en el mismo proxy transaccional que usa el controlador: sin el, RegistrarPredio
        // fallaria por falta de contexto, igual que ya demuestra RegistrarPredioTest.
        registrar =
                envolver(
                        new RegistrarPredio(
                                repositorio,
                                new AuditoriaJdbc(jdbc, Clock.systemUTC()),
                                Clock.systemUTC()),
                        gestor);
        consulta = new PrediosDelContribuyenteCatastro(repositorio);
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
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("lista los predios del contribuyente, con su codigo, direccion y porcentaje")
    void listaLosPrediosDelContribuyente() throws SQLException {
        long titular = crearContribuyente(municipalidad, "P-0001", "50200001", "TITULAR, UNO");
        long predioId = predioNuevo("20020100100100101010001", "AV. LOS PREDIOS 100");
        registrar.registrarTitularidad(
                Titularidad.unico(predioId, titular, LocalDate.of(2020, 1, 1), "FICHA-0001"),
                Observacion.de("Alta de titularidad para la prueba"));

        List<PredioDelContribuyente> predios =
                transaccion.execute(estado -> consulta.de(titular, LocalDate.of(2026, 1, 1)));

        assertThat(predios).hasSize(1);
        PredioDelContribuyente predio = predios.get(0);
        assertThat(predio.predioId()).isEqualTo(predioId);
        assertThat(predio.codigoReferenciaCatastral()).isEqualTo("20020100100100101010001");
        assertThat(predio.direccion()).isEqualTo("AV. LOS PREDIOS 100");
        assertThat(predio.tipo()).isEqualTo("URBANO");
        assertThat(predio.porcentajeTitularidad()).isEqualTo(Porcentaje.total());
    }

    @Test
    @DisplayName("#690 — publica cuanto del predio esta registrado, no solo la cuota propia")
    void publicaCuantoDelPredioEstaRegistrado() throws SQLException {
        long unTitular = crearContribuyente(municipalidad, "P-0690", "50200690", "MEDIO, DUEÑO");
        long otroTitular = crearContribuyente(municipalidad, "P-0691", "50200691", "OTRO, DUEÑO");
        long aMedias = predioNuevo("20020100100100101010690", "AV. A MEDIAS 690");
        long completo = predioNuevo("20020100100100101010691", "AV. COMPLETA 691");

        registrar.registrarTitularidad(
                Titularidad.parcial(
                        aMedias,
                        unTitular,
                        CondicionDeTitularidad.COPROPIETARIO,
                        Porcentaje.de("60"),
                        LocalDate.of(2020, 1, 1),
                        "FICHA-0690"),
                Observacion.de("El 60 % que se conoce; del 40 % restante no se sabe quien es"));
        // El otro predio si tiene dueño entero, entre dos: es la copropiedad corriente.
        registrar.registrarTitularidad(
                Titularidad.parcial(
                        completo,
                        unTitular,
                        CondicionDeTitularidad.COPROPIETARIO,
                        Porcentaje.de("60"),
                        LocalDate.of(2020, 1, 1),
                        "FICHA-0691"),
                Observacion.de("Copropiedad: la primera cuota"));
        registrar.registrarTitularidad(
                Titularidad.parcial(
                        completo,
                        otroTitular,
                        CondicionDeTitularidad.COPROPIETARIO,
                        Porcentaje.de("40"),
                        LocalDate.of(2020, 1, 1),
                        "FICHA-0691"),
                Observacion.de("Copropiedad: la segunda cuota"));

        List<PredioDelContribuyente> predios =
                transaccion.execute(estado -> consulta.de(unTitular, LocalDate.of(2026, 1, 1)));

        PredioDelContribuyente elDeMedias =
                predios.stream().filter(x -> x.predioId() == aMedias).findFirst().orElseThrow();
        PredioDelContribuyente elCompleto =
                predios.stream().filter(x -> x.predioId() == completo).findFirst().orElseThrow();

        assertThat(elDeMedias.porcentajeTitularidad())
                .as("la cuota propia es la misma en los dos: 60 %")
                .isEqualTo(Porcentaje.de("60"));
        assertThat(elCompleto.porcentajeTitularidad()).isEqualTo(Porcentaje.de("60"));

        assertThat(elDeMedias.porcentajeRegistradoDelPredio())
                .as("y lo que los distingue es cuanto del PREDIO tiene dueño registrado")
                .isEqualTo(Porcentaje.de("60"));
        assertThat(elDeMedias.titularidadCompleta()).isFalse();

        assertThat(elCompleto.porcentajeRegistradoDelPredio())
                .as(
                        "la copropiedad legitima suma 100 aunque la cuota propia sea 60: sumar solo"
                                + " la del contribuyente daria un aviso en el caso corriente, y un"
                                + " aviso que salta siempre deja de leerse")
                .isEqualTo(Porcentaje.de("100"));
        assertThat(elCompleto.titularidadCompleta()).isTrue();
    }

    @Test
    @DisplayName("un contribuyente sin predios devuelve vacio, no un error")
    void unContribuyenteSinPrediosDevuelveVacio() throws SQLException {
        long sinPredios = crearContribuyente(municipalidad, "P-0002", "50200002", "SIN, PREDIOS");

        List<PredioDelContribuyente> predios =
                transaccion.execute(estado -> consulta.de(sinPredios, LocalDate.of(2026, 1, 1)));

        assertThat(predios).isEmpty();
    }

    @Test
    @DisplayName("solo trae la titularidad vigente a la fecha, no una ya transferida")
    void soloTraeLaTitularidadVigenteALaFecha() throws SQLException {
        long transferente =
                crearContribuyente(municipalidad, "P-0003", "50200003", "TRANSFERENTE, TRES");
        long adquiriente =
                crearContribuyente(municipalidad, "P-0004", "50200004", "ADQUIRIENTE, CUATRO");
        long predioId = predioNuevo("20020100100100101010002", "AV. LOS PREDIOS 200");
        Titularidad original =
                registrar.registrarTitularidad(
                        Titularidad.unico(
                                predioId, transferente, LocalDate.of(2020, 1, 1), "FICHA-0002"),
                        Observacion.de("Alta de titularidad para la prueba"));

        registrar.transferir(
                original,
                Titularidad.unico(predioId, adquiriente, LocalDate.of(2025, 6, 1), "EP-2025-0001"),
                Observacion.de("Transferencia para la prueba"));

        List<PredioDelContribuyente> deHoy =
                transaccion.execute(estado -> consulta.de(transferente, LocalDate.of(2026, 1, 1)));
        assertThat(deHoy)
                .as("el titular original ya no lo es a esta fecha: la transferencia lo cerro")
                .isEmpty();

        List<PredioDelContribuyente> antesDeLaTransferencia =
                transaccion.execute(estado -> consulta.de(transferente, LocalDate.of(2024, 1, 1)));
        assertThat(antesDeLaTransferencia)
                .as("antes de la transferencia, el titular original si lo era")
                .singleElement()
                .extracting(PredioDelContribuyente::predioId)
                .isEqualTo(predioId);
    }

    // ------------------------------------------------------------------

    private static long predioNuevo(String codigo, String direccion) {
        Predio predio =
                registrar.registrar(
                        Predio.urbano(CodigoReferenciaCatastral.de(codigo), direccion),
                        Observacion.de("Alta del predio para la prueba"));
        return Objects.requireNonNull(predio.id());
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

    private static long crearContribuyente(long muni, String codigo, String dni, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
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
}
