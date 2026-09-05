package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.ValorReferencialRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code RegistrarDeterminacionVehicular} contra PostgreSQL real (#32).
 *
 * <p>Lo que este archivo verifica con la base de por medio: el modo simulación no escribe nada, el
 * plazo de afectación se respeta sin intervención manual, y la alícuota sale del conjunto sellado
 * —cambiarla cambia el importe—.
 */
@DisplayName("#32 — Registrar la determinacion vehicular")
class RegistrarDeterminacionVehicularTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final Ejercicio FABRICACION = new Ejercicio(2020);
    private static final Ejercicio INSCRIPCION = new Ejercicio(2024);
    private static final Ejercicio EJERCICIO_AFECTO = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static VehiculoRepositoryJdbc vehiculos;
    private static DeterminacionRepositoryJdbc determinaciones;
    private static RegistrarDeterminacionVehicular registrar;
    private static AdministrarParametros administrarParametros;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        contribuyente = crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        vehiculos = new VehiculoRepositoryJdbc(jdbc);
        determinaciones = new DeterminacionRepositoryJdbc(jdbc);

        LectorDeParametros parametros =
                envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)));
        administrarParametros =
                envolver(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ));
        registrar =
                envolver(
                        new RegistrarDeterminacionVehicular(
                                vehiculos,
                                new ValoresReferenciales(
                                        new ValorReferencialRepositoryJdbc(jdbc), parametros),
                                determinaciones,
                                parametros,
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ));
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
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

    @Nested
    @DisplayName("El modo simulacion")
    class ModoSimulacion {

        @Test
        @DisplayName("no escribe ninguna fila: ni determinacion, ni auditoria")
        void noEscribeNingunaFila() throws SQLException {
            long vehiculoId = crearVehiculoConValorReferencial("W1A-111", "TOYOTA", "YARIS");

            long filasAntes = contarFilas("determinacion");
            long auditoriaAntes = contarFilas("auditoria");

            Determinacion resultado =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    true,
                                    Observacion.de("Simulacion, no debe escribir nada"))
                            .determinacion();

            assertThat(resultado.esNueva()).as("nunca se guardo: sigue sin id").isTrue();
            assertThat(contarFilas("determinacion")).isEqualTo(filasAntes);
            assertThat(contarFilas("auditoria")).isEqualTo(auditoriaAntes);
        }

        @Test
        @DisplayName("calcula el mismo importe que el modo real, sin persistirlo")
        void calculaElMismoImporteQueElModoReal() {
            long vehiculoId = crearVehiculoConValorReferencial("W2B-222", "KIA", "RIO");

            Determinacion simulado =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    true,
                                    Observacion.de("Simulacion de prueba"))
                            .determinacion();
            Determinacion real =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    false,
                                    Observacion.de("Calculo real de prueba"))
                            .determinacion();

            assertThat(simulado.montoDeterminado()).isEqualTo(real.montoDeterminado());
        }
    }

    @Nested
    @DisplayName("El plazo de afectacion")
    class PlazoDeAfectacion {

        @Test
        @DisplayName("un vehiculo fuera de su plazo de tres anios no se determina")
        void unVehiculoFueraDePlazoNoSeDetermina() {
            long vehiculoId = crearVehiculoConValorReferencial("W3C-333", "NISSAN", "SENTRA");
            // Inscrito en 2024: afecto de 2025 a 2027. 2030 ya vencio.
            Ejercicio fueraDePlazo = new Ejercicio(2030);

            assertThatThrownBy(
                            () ->
                                    registrar.calcular(
                                            vehiculoId,
                                            fueraDePlazo,
                                            false,
                                            Observacion.de("No deberia determinarse")))
                    .isInstanceOf(RegistrarDeterminacionVehicular.VehiculoNoAfecto.class);
        }
    }

    @Nested
    @DisplayName("La alicuota sale del conjunto sellado")
    class LaAlicuotaSaleDelConjuntoSellado {

        @Test
        @DisplayName("cambiar la alicuota del conjunto cambia el monto determinado")
        void cambiarLaAlicuotaCambiaElMonto() throws SQLException {
            long vehiculoUno = crearVehiculoConValorReferencial("W4D-444", "HYUNDAI", "ACCENT");
            Determinacion primera =
                    registrar
                            .calcular(
                                    vehiculoUno,
                                    EJERCICIO_AFECTO,
                                    false,
                                    Observacion.de("Con la alicuota del 1%"))
                            .determinacion();

            // Otro vehiculo, ejercicio 2027: sella un conjunto distinto con otra alicuota.
            long vehiculoDos = crearVehiculoConValorReferencial2027("W5E-555", "HYUNDAI", "ACCENT");
            Determinacion segunda =
                    registrar
                            .calcular(
                                    vehiculoDos,
                                    new Ejercicio(2027),
                                    false,
                                    Observacion.de("Con otra alicuota"))
                            .determinacion();

            assertThat(primera.montoDeterminado())
                    .as("misma base, alicuotas distintas: el importe tiene que diferir")
                    .isNotEqualTo(segunda.montoDeterminado());
        }
    }

    // ------------------------------------------------------------------

    private static long contarFilas(String tabla) throws SQLException {
        return transaccion.execute(
                estado -> jdbc.sql("SELECT count(*) FROM " + tabla).query(Long.class).single());
    }

    private static long crearVehiculoConValorReferencial(
            String placa, String marca, String modelo) {
        return crearVehiculoConValorReferencialImpl(
                placa, marca, modelo, EJERCICIO_AFECTO, new BigDecimal("1.0"));
    }

    private static long crearVehiculoConValorReferencial2027(
            String placa, String marca, String modelo) {
        return crearVehiculoConValorReferencialImpl(
                placa, marca, modelo, new Ejercicio(2027), new BigDecimal("2.0"));
    }

    private static long crearVehiculoConValorReferencialImpl(
            String placa, String marca, String modelo, Ejercicio ejercicio, BigDecimal alicuota) {
        Vehiculo vehiculo =
                transaccion.execute(
                        estado ->
                                vehiculos.save(
                                        Vehiculo.nuevo(
                                                Placa.de(placa),
                                                contribuyente,
                                                marca,
                                                modelo,
                                                "M1",
                                                FABRICACION,
                                                INSCRIPCION)));
        long vehiculoId = requireId(vehiculo);
        try {
            sellarConValorReferencialYAlicuota(ejercicio, marca, modelo, alicuota);
        } catch (SQLException fallo) {
            throw new IllegalStateException(fallo);
        }
        return vehiculoId;
    }

    private static void sellarConValorReferencialYAlicuota(
            Ejercicio ejercicio, String marca, String modelo, BigDecimal alicuota)
            throws SQLException {
        // valor_referencial_vehiculo dejo de ser tabla de negocio de esta municipalidad: desde V55
        // (D-13, ADR-0017) es un catalogo NACIONAL y solo lo escribe rol_carga_parametros, sin
        // contexto de tenant porque el dato no es de nadie en particular. Lo que el conjunto guarda
        // es que EDICION uso, componiendola como un parametro mas.
        //
        // Por eso la edicion se publica ANTES de sellar: componer sobre un conjunto ya sellado lo
        // rechaza detalle_de_conjunto_sellado_inmutable (V9).
        long edicion = publicarEdicionDelCuadro(ejercicio, marca, modelo);

        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(ejercicio, Observacion.de("Conjunto de prueba"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("ALICUOTA_VEHICULAR", alicuota),
                Observacion.de("Alicuota vehicular ficticia"));
        // El minimo imponible del art. 34 y la UIT con que se convierte: desde #399 salen del
        // conjunto sellado y no del cuerpo de la peticion, asi que sin ellos no hay determinacion
        // que calcular. Las dos cifras son ficticias, como la alicuota de arriba.
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("VEHICULAR_MINIMO", new BigDecimal("1.5")),
                Observacion.de("Minimo imponible vehicular ficticio"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("UIT", new BigDecimal("100.00")),
                Observacion.de("UIT ficticia"));
        administrarParametros.agregarParametro(
                conjunto.id(), edicion, Observacion.de("Cuadro vehicular ficticio"));
        administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
    }

    /** La edicion nacional del cuadro vehicular: una cabecera y su unica fila ficticia. */
    private static long publicarEdicionDelCuadro(Ejercicio ejercicio, String marca, String modelo)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES (NULL, 'TABLA_DE_LA_PRUEBA', ?,"
                                    + " 'ficticio de prueba', ?, 'ficticio de prueba, no representa"
                                    + " ninguna norma', 'carga', 'aprueba') RETURNING id")) {
                sentencia.setString(1, marca + "/" + modelo + "/" + ejercicio.valor());
                sentencia.setDate(
                        2, java.sql.Date.valueOf(java.time.LocalDate.of(ejercicio.valor(), 1, 1)));
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    edicion = fila.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO valor_referencial_de_prueba (publicacion_id, ejercicio,"
                                    + " categoria, marca, modelo, anio_fabricacion, valor,"
                                    + " documento_fuente)"
                                    + " VALUES (?, ?, 'M1', ?, ?, ?, 10000.00, 'ficticio de"
                                    + " prueba')")) {
                sentencia.setLong(1, edicion);
                sentencia.setInt(2, ejercicio.valor());
                sentencia.setString(3, marca);
                sentencia.setString(4, modelo);
                sentencia.setInt(5, FABRICACION.valor());
                sentencia.executeUpdate();
            }
            carga.commit();
            return edicion;
        }
    }

    private static long parametroNumerico(String tipo, BigDecimal valor) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, NULL, ?,"
                                        + " DATE '2026-01-01', 'ficticio de prueba, no representa"
                                        + " ninguna norma', 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setBigDecimal(2, valor);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long requireId(Vehiculo vehiculo) {
        Long id = vehiculo.id();
        if (id == null) {
            throw new IllegalStateException("El vehiculo guardado tiene identificador");
        }
        return id;
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220401', 'Municipalidad de la determinacion"
                                        + " vehicular', 'DISTRITAL') RETURNING id")) {
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
                                    + " VALUES (?, 'C-VEHDET-1', 'DNI', '40404141', 'NATURAL',"
                                    + " 'TITULAR, DETERMINACION VEHICULAR', 'siembra') RETURNING"
                                    + " id")) {
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
