package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.prueba.TitularidadDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.nucleo.aplicacion.RegistrarDeclaracionJurada;
import kamayuk.rentas.nucleo.aplicacion.RegistrarTransferencia;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.PlantillaDeNumeroDeDeclaracion;
import kamayuk.rentas.nucleo.infraestructura.BeneficioRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.CuotaDeArbitrioRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.DeclaracionJuradaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * La fila de auditoria cae en el ejercicio del ACTO, no en el de la fecha de negocio (#398).
 *
 * <p>La particion de {@code auditoria} es {@code ejercicio}, y solo existen {@code auditoria_2026}
 * y {@code auditoria_2027} (V1). Hasta #398 ese ejercicio lo ponia cada llamador con la fecha que
 * tuviera a mano —la de presentacion de la DJ, la de la transferencia, la de la infraccion—, y
 * {@code AuditoriaJdbc} tomaba la {@code fecha} de su reloj: <b>dos fuentes para el mismo
 * hecho</b>. Con la fecha de negocio en un año sin particion el {@code INSERT} fallaba con «no
 * partition of relation "auditoria" found for row» y el acto entero se deshacia con 500; con la
 * fecha de negocio en otro año con particion, la fila quedaba donde la bitacora del ejercicio no la
 * busca.
 *
 * <p><b>La siembra que distingue pone el reloj y la fecha de negocio en años distintos.</b> Las
 * pruebas de al lado fijan el reloj en 2026 y fechan todo en 2026: es la muestra uniforme, y con
 * ella el defecto pasa en verde. Aqui cada caso cruza el año a proposito.
 *
 * <p>Contra PostgreSQL real y con la conexion de {@code kamayuk_app}: la particion que falta solo
 * la ve el motor, y un superusuario se saltaria la RLS con que la bitacora se lee.
 */
@DisplayName("#398 — La auditoria cae en el ejercicio del acto")
class LaAuditoriaEsDelEjercicioDelActoTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    /** Enero de 2026: la ventana de puesta en marcha, con actos de 2025 por registrar. */
    private static final Clock RELOJ_ENERO_2026 =
            Clock.fixed(Instant.parse("2026-01-05T15:00:00Z"), LIMA);

    /** Enero de 2027: el primer dia habil en que se actua sobre documentos del año anterior. */
    private static final Clock RELOJ_ENERO_2027 =
            Clock.fixed(Instant.parse("2027-01-10T15:00:00Z"), LIMA);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;
    private static MockMvc mvcEnEnero2026;
    private static RegistrarDeclaracionJurada declaracionesEnEnero2026;
    private static RegistrarDeclaracionJurada declaracionesEnEnero2027;

    private static long transferente;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("300101", "Municipalidad del ejercicio del acto");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        mvcEnEnero2026 = transferenciasConElReloj(RELOJ_ENERO_2026);
        declaracionesEnEnero2026 = declaracionesConElReloj(RELOJ_ENERO_2026);
        declaracionesEnEnero2027 = declaracionesConElReloj(RELOJ_ENERO_2027);

        transferente = crearContribuyente("EA-0001", "80910001");
        crearContribuyente("EA-0002", "80910002");
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

    // ------------------------------------------------------------------
    //  (a) El 500 vivo: el vehiculo comprado en diciembre y registrado en enero
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "el 2026-01-05 se registra una transferencia vehicular del 2025-12-15: 201, y la fila"
                    + " de auditoria es de 2026 con la fecha del reloj")
    void unaTransferenciaDeDiciembreRegistradaEnEnero() throws Exception {
        long vehiculo = crearVehiculo("EAV-398", transferente);

        MvcResult resultado = transferirVehiculo("EAV-398", "2025-12-15");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "hasta #398 el INSERT en auditoria iba con ejercicio 2025, que no tiene"
                                + " particion, y el acto entero se deshacia con 500: el cuerpo fue"
                                + " %s",
                        resultado.getResponse().getContentAsString())
                .isEqualTo(201);

        FilaDeAuditoria fila =
                unicaFilaDe(
                        "transferencia",
                        String.valueOf(
                                idDe(
                                        "SELECT id FROM transferencia WHERE vehiculo_id = "
                                                + vehiculo)));
        assertThat(fila.ejercicio())
                .as("la particion es el ejercicio del ACTO, no el de la fecha de la transferencia")
                .isEqualTo(2026);
        assertThat(fila.dia())
                .as("y la fecha de la fila es la del reloj, del mismo instante que el ejercicio")
                .isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(fila.datosNuevos())
                .as("la fecha de negocio no se pierde: viaja dentro de datos_nuevos")
                .contains("\"fechaTransferencia\": \"2025-12-15\"");
        assertThat(titularDelVehiculo(vehiculo))
                .as("y el cambio de titular no se deshizo")
                .isNotEqualTo(transferente);
    }

    // ------------------------------------------------------------------
    //  (c) La fila archivada en el ejercicio equivocado
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "el 2027-01-10 se anula una DJ presentada el 2026-03-01: la fila es de 2027 y la"
                    + " bitacora de 2027 la devuelve")
    void anularEnEneroUnaDjDelAnoAnterior() throws SQLException {
        String numero = "DJ-2026-398001";
        long dj = sembrarDeclaracion(numero, new Ejercicio(2026), LocalDate.of(2026, 3, 1));

        declaracionesEnEnero2027.anular(
                numero,
                new Ejercicio(2026),
                Observacion.de("Se anula en enero una declaracion del ejercicio anterior"));

        FilaDeAuditoria fila = unicaFilaDe("declaracion_jurada", String.valueOf(dj));
        assertThat(fila.ejercicio())
                .as(
                        "hasta #398 caia en auditoria_2026 con fecha 2027-01-10: ni la bitacora de"
                                + " 2027 ni la de 2026 con un rango de 2026 la encontraban")
                .isEqualTo(2027);
        assertThat(fila.dia()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(fila.datosNuevos())
                .as("la fecha de presentacion, que es la de negocio, va en datos_nuevos")
                .contains("\"fechaPresentacion\": \"2026-03-01\"")
                .contains("\"estado\": \"ANULADA\"");

        assertThat(laBitacoraDe(2027, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
                .as("la consulta de la bitacora filtra por ejercicio Y por rango de fecha")
                .contains(String.valueOf(dj));
    }

    // ------------------------------------------------------------------
    //  (d) La DJ venida de un volcado, anterior a toda particion
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "el 2026-01-05 se observa una DJ presentada en 2025, venida de un volcado: el acto"
                    + " se registra en 2026")
    void observarUnaDjDeUnEjercicioSinParticion() throws SQLException {
        String numero = "DJ-2025-398002";
        long dj = sembrarDeclaracion(numero, new Ejercicio(2025), LocalDate.of(2025, 3, 1));

        var observada =
                declaracionesEnEnero2026.observar(
                        numero,
                        new Ejercicio(2025),
                        Observacion.de("El area declarada en 2025 no cuadra con la ficha"));

        assertThat(observada.estado())
                .as("hasta #398 esto era 500: la fila de auditoria iba a la particion de 2025")
                .isEqualTo(EstadoDeDeclaracion.OBSERVADA);
        FilaDeAuditoria fila = unicaFilaDe("declaracion_jurada", String.valueOf(dj));
        assertThat(fila.ejercicio()).isEqualTo(2026);
        assertThat(fila.dia()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(fila.datosNuevos()).contains("\"fechaPresentacion\": \"2025-03-01\"");
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    private record FilaDeAuditoria(int ejercicio, LocalDate dia, String datosNuevos) {}

    /** La fila de auditoria de una clave, leida como administrador: la unica, o la prueba cae. */
    private static FilaDeAuditoria unicaFilaDe(String tabla, String clave) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT ejercicio, (fecha AT TIME ZONE 'America/Lima')::date,"
                                        + " datos_nuevos::text FROM auditoria WHERE tabla = ? AND"
                                        + " clave = ?")) {
            sentencia.setString(1, tabla);
            sentencia.setString(2, clave);
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).as("una fila de auditoria de %s %s", tabla, clave).isTrue();
                FilaDeAuditoria leida =
                        new FilaDeAuditoria(
                                fila.getInt(1),
                                fila.getObject(2, LocalDate.class),
                                fila.getString(3));
                assertThat(fila.next()).as("y solo una").isFalse();
                return leida;
            }
        }
    }

    /**
     * Las claves de {@code declaracion_jurada} que devuelve la bitacora de un ejercicio y un rango.
     *
     * <p>Es la misma forma que {@code SesionRepositoryJdbc#auditoria} —{@code WHERE ejercicio =
     * :ejercicio} mas {@code fecha >= desde} y {@code fecha < hasta + 1}, con los extremos como
     * medianoche—, y se lee como {@code kamayuk_app} bajo la RLS del tenant. No se importa aquella
     * clase porque es de {@code seguridad}, y este modulo no la conoce.
     */
    private static List<String> laBitacoraDe(int ejercicio, LocalDate desde, LocalDate hasta) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "SELECT clave FROM auditoria WHERE ejercicio = :ejercicio"
                                                + " AND tabla = 'declaracion_jurada'"
                                                + " AND fecha >= :desde AND fecha < :hasta")
                                .param("ejercicio", ejercicio)
                                .param("desde", desde.atStartOfDay())
                                .param("hasta", hasta.plusDays(1).atStartOfDay())
                                .query(String.class)
                                .list());
    }

    private static MvcResult transferirVehiculo(String placa, String fecha) throws Exception {
        String cuerpo =
                """
                {"observacion":"Se registra en enero la compra de diciembre",
                 "placa":"%s",
                 "codAdquiriente":"EA-0002",
                 "tipoTransferencia":"COMPRA_VENTA",
                 "fechaTransferencia":"%s",
                 "valorTransferencia":"15000.00",
                 "afectaAlcabala":false,
                 "documentoOrigen":"CT-%s"}
                """
                        .formatted(placa, fecha, placa);
        return mvcEnEnero2026
                .perform(
                        post("/rentas/api/v1/rentas/transferencias/vehiculo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static MockMvc transferenciasConElReloj(Clock reloj) {
        TransferenciaRepositoryJdbc transferencias = new TransferenciaRepositoryJdbc(jdbc);
        VehiculoRepositoryJdbc vehiculos = new VehiculoRepositoryJdbc(jdbc);
        RegistrarTransferencia registrar =
                conLaTransaccionQueDiceLaAnotacion(
                        new RegistrarTransferencia(
                                transferencias,
                                new TitularidadDelEscenario(jdbc),
                                vehiculos,
                                new AuditoriaJdbc(jdbc, reloj)));
        ConsultasDeRentas consultas =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultasDeRentas(
                                new CuotaDeArbitrioRepositoryJdbc(jdbc),
                                new BeneficioRepositoryJdbc(jdbc),
                                transferencias,
                                new DeclaracionJuradaRepositoryJdbc(jdbc)));
        ConsultaDeVehiculos consultaDeVehiculos =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultaDeVehiculos(vehiculos, (contribuyenteId, fecha) -> List.of()));
        return MockMvcBuilders.standaloneSetup(
                        new TransferenciaVehiculoController(
                                registrar, consultas, consultaDeVehiculos))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /**
     * El caso de uso de la DJ con el reloj de la auditoria que se pide. Observar y anular no leen
     * ni el plazo, ni la ficha, ni el padron: si alguno de los dos empezara a hacerlo, esta prueba
     * caeria con un {@code NullPointerException} en vez de pasar mirando otra cosa.
     */
    private static RegistrarDeclaracionJurada declaracionesConElReloj(Clock reloj) {
        return conLaTransaccionQueDiceLaAnotacion(
                new RegistrarDeclaracionJurada(
                        new DeclaracionJuradaRepositoryJdbc(jdbc),
                        PlantillaDeNumeroDeDeclaracion.POR_OMISION,
                        null,
                        null,
                        null,
                        new AuditoriaJdbc(jdbc, reloj)));
    }

    /** Una DJ ya presentada, como la deja un volcado: sin pasar por el correlativo ni el plazo. */
    private static long sembrarDeclaracion(
            String numero, Ejercicio ejercicio, LocalDate fechaPresentacion) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                                    + " contribuyente_id, tipo, fecha_presentacion, fecha_limite,"
                                    + " usuario_registro, observacion) VALUES (?, ?, ?, ?, 'HR',"
                                    + " ?, ?, 'migracion', 'Fila heredada del sistema anterior')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, numero);
                sentencia.setInt(3, ejercicio.valor());
                sentencia.setLong(4, transferente);
                sentencia.setObject(5, fechaPresentacion);
                sentencia.setObject(6, LocalDate.of(ejercicio.valor(), 6, 30));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long idDe(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            assertThat(resultado.next()).as(sql).isTrue();
            return resultado.getLong(1);
        }
    }

    private static long titularDelVehiculo(long vehiculoId) throws SQLException {
        return idDe("SELECT contribuyente_id FROM vehiculo WHERE id = " + vehiculoId);
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

    private static long crearContribuyente(String codigo, String dni) throws SQLException {
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
                    return id;
                }
            }
        }
    }

    private static long crearVehiculo(String placa, long contribuyenteId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO vehiculo (municipalidad_id, contribuyente_id, placa,"
                                    + " marca, modelo, categoria, anio_fabricacion,"
                                    + " anio_inscripcion)"
                                    + " VALUES (?, ?, ?, 'TOYOTA', 'YARIS', 'M1', 2022, 2022)"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, contribuyenteId);
                sentencia.setString(3, placa);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor (#486): con el {@code @Transactional}
     * quitado, el cambio de titular no se desharia y la prueba (a) no podria decir nada de eso.
     */
    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
