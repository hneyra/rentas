package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
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

/**
 * {@code RegistrarAlcabala} contra PostgreSQL real (#32).
 *
 * <p>Verifica que la elección de base —el mayor entre el valor de transferencia y el autovalúo
 * ajustado— gobierna el monto determinado, y que una transferencia que no grava alcabala (un
 * vehículo, o un tipo que no la afecta) se rechaza sin determinar nada.
 */
@DisplayName("#32 — Registrar la alcabala")
class RegistrarAlcabalaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long transferente;
    private static long adquiriente;
    private static TransferenciaRepository transferencias;
    private static org.springframework.transaction.support.TransactionTemplate transaccion;
    private static RegistrarAlcabala registrar;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        transferente = crearContribuyente("C-ALC-1", "50505001");
        adquiriente = crearContribuyente("C-ALC-2", "50505002");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new org.springframework.transaction.support.TransactionTemplate(gestor);
        // TransferenciaRepositoryJdbc.insertar no lleva @Transactional (eso vive en el caso de
        // uso): envolverlo en TransactionInterceptor no abriria ninguna transaccion. Para
        // sembrar una transferencia directo, sin pasar por RegistrarTransferencia, se abre la
        // transaccion a mano con TransactionTemplate, igual que RegistrarDeterminacionPredialTest
        // hace para leer directo del repositorio.
        transferencias = new TransferenciaRepositoryJdbc(jdbc);

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
        sellarConUitYAlicuota(base, administrarParametros, "3.0");

        registrar =
                envolver(
                        new RegistrarAlcabala(
                                new TransferenciaRepositoryJdbc(jdbc),
                                new kamayuk.rentas.nucleo.infraestructura
                                        .DeterminacionRepositoryJdbc(jdbc),
                                parametros,
                                new AuditoriaJdbc(jdbc, RELOJ),
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("La eleccion de base")
    class LaEleccionDeBase {

        @Test
        @DisplayName("cuando el autovaluo ajustado es mayor, el impuesto se calcula sobre el")
        void calculaSobreElAutovaluoCuandoEsMayor() {
            long transferenciaId =
                    registrarTransferencia(Dinero.de("40000.00"), TipoTransferencia.COMPRA_VENTA);

            Determinacion determinacion =
                    registrar.determinar(
                            transferenciaId,
                            Dinero.de("100000.00"),
                            Observacion.de("El autovaluo ajustado es mayor"));

            // Base 100000, tramo inafecto 10 UIT (con UIT ficticia de 4600) = 46000.
            // (100000 - 46000) * 3% = 1620.00
            assertThat(determinacion.baseImponible()).isEqualTo(Dinero.de("100000.00"));
            assertThat(determinacion.montoDeterminado()).isEqualTo(Dinero.de("1620.00"));
        }

        @Test
        @DisplayName("cuando el valor de transferencia es mayor, el impuesto se calcula sobre el")
        void calculaSobreElValorDeTransferenciaCuandoEsMayor() {
            long transferenciaId =
                    registrarTransferencia(Dinero.de("200000.00"), TipoTransferencia.COMPRA_VENTA);

            Determinacion determinacion =
                    registrar.determinar(
                            transferenciaId,
                            Dinero.de("100000.00"),
                            Observacion.de("El valor de transferencia es mayor"));

            assertThat(determinacion.baseImponible()).isEqualTo(Dinero.de("200000.00"));
        }
    }

    @Nested
    @DisplayName("Lo que no grava alcabala")
    class LoQueNoGravaAlcabala {

        @Test
        @DisplayName("una transferencia cuyo tipo no afecta a alcabala se rechaza")
        void unTipoQueNoAfectaSeRechaza() {
            long transferenciaId = registrarTransferenciaSinAlcabala(Dinero.de("40000.00"));

            assertThatThrownBy(
                            () ->
                                    registrar.determinar(
                                            transferenciaId,
                                            Dinero.de("50000.00"),
                                            Observacion.de("No deberia determinarse")))
                    .isInstanceOf(RegistrarAlcabala.NoGravaAlcabala.class);
        }

        @Test
        @DisplayName("una transferencia inexistente se rechaza")
        void unaTransferenciaInexistenteSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    registrar.determinar(
                                            999_999L,
                                            Dinero.de("50000.00"),
                                            Observacion.de("No existe")))
                    .isInstanceOf(RegistrarAlcabala.TransferenciaInexistente.class);
        }
    }

    // ------------------------------------------------------------------

    private static long registrarTransferencia(Dinero valorTransferencia, TipoTransferencia tipo) {
        long predioId = predioDeMentira();
        return requireId(
                transaccion.execute(
                        estado ->
                                transferencias.insertar(
                                        Transferencia.dePredio(
                                                predioId,
                                                transferente,
                                                adquiriente,
                                                tipo,
                                                LocalDate.of(2026, 6, 1),
                                                valorTransferencia,
                                                Porcentaje.total(),
                                                true,
                                                "ESCRITURA-001",
                                                Observacion.de("Transferencia de prueba")))));
    }

    private static long registrarTransferenciaSinAlcabala(Dinero valorTransferencia) {
        long predioId = predioDeMentira();
        return requireId(
                transaccion.execute(
                        estado ->
                                transferencias.insertar(
                                        Transferencia.dePredio(
                                                predioId,
                                                transferente,
                                                adquiriente,
                                                TipoTransferencia.ANTICIPO_DE_LEGITIMA,
                                                LocalDate.of(2026, 6, 1),
                                                valorTransferencia,
                                                Porcentaje.total(),
                                                false,
                                                "ESCRITURA-002",
                                                Observacion.de("No afecta alcabala")))));
    }

    private static long requireId(Transferencia transferencia) {
        Long id = transferencia.id();
        if (id == null) {
            throw new IllegalStateException("La transferencia guardada tiene identificador");
        }
        return id;
    }

    private static final java.util.concurrent.atomic.AtomicInteger CONTADOR_DE_PREDIOS =
            new java.util.concurrent.atomic.AtomicInteger();

    private static long predioDeMentira() {
        // predio_id no tiene FK activa hacia una fila real en este archivo: la prueba solo
        // necesita que Transferencia sea de PREDIO para que la alcabala pueda aplicar. Se crea
        // un predio real para no violar la FK de `transferencia_predio_fk`.
        String codigo = String.format("00000000000000%04d", CONTADOR_DE_PREDIOS.incrementAndGet());
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 456')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException(fallo);
        }
    }

    private static long sellarConUitYAlicuota(
            BaseDeDatosDePrueba base, AdministrarParametros administrarParametros, String alicuota)
            throws SQLException {
        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(
                        ejercicio2026(), Observacion.de("Conjunto de prueba para alcabala"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametro(base, "UIT", null, "4600.00"),
                Observacion.de("UIT ficticia de prueba"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametro(base, "ALICUOTA_ALCABALA", null, alicuota),
                Observacion.de("Alicuota de alcabala ficticia"));
        ConjuntoDeParametros sellado =
                administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
        return sellado.id();
    }

    private static kamayuk.rentas.dominio.Ejercicio ejercicio2026() {
        return new kamayuk.rentas.dominio.Ejercicio(2026);
    }

    private static long parametro(BaseDeDatosDePrueba base, String tipo, String clave, String valor)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, ?, DATE '2026-01-01', 'ficticio de"
                                        + " prueba, no representa ninguna norma', 'carga',"
                                        + " 'aprueba') RETURNING id")) {
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
                                        + " VALUES ('220501', 'Municipalidad de la alcabala',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, ALCABALA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
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
