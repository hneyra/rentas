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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos.VehiculoConDeuda;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
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
 * El padron vehicular contra PostgreSQL real, como {@code sgtm_app} (RF-024, #26).
 *
 * <p>Tres propiedades se verifican aqui, y las tres tienen una implementacion equivocada que parece
 * mas natural que la correcta:
 *
 * <ol>
 *   <li><b>La placa es unica sin su guion.</b> {@code Placa} compara asi desde que existe, pero la
 *       restriccion de V2 comparaba el texto tal cual: la base admitia {@code ABC-123} y {@code
 *       ABC123} como dos vehiculos. No es un duplicado incomodo, son dos historiales de papeletas.
 *   <li><b>Cambiar la placa no reescribe las papeletas.</b> {@code papeleta} guarda {@code placa} y
 *       {@code vehiculo_id}; sincronizar la primera «para que quede coherente» es reescribir un
 *       acta, y con eso se cae la imputacion en un descargo.
 *   <li><b>El historial se reconstruye.</b> Y solo se puede si la auditoria se llavea por el
 *       identificador: llaveada por la placa, el primer cambio la parte en dos trozos que ya no se
 *       pueden juntar.
 * </ol>
 */
@DisplayName("RF-024 — Padron vehicular: placa unica, cambio con traza, papeletas intactas")
class PadronVehicularTest {

    private static final Ejercicio FABRICACION = new Ejercicio(2020);
    private static final Ejercicio INSCRIPCION = new Ejercicio(2021);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static VehiculoRepository repositorio;
    private static RegistrarVehiculo registrar;
    private static CambiarPlaca cambiarPlaca;
    private static ConsultaDeVehiculos consulta;
    private static DeudaDeMentira deuda;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        contribuyente = crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        repositorio = new VehiculoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        registrar = envolver(new RegistrarVehiculo(repositorio, auditoria, RELOJ), pool);
        cambiarPlaca = envolver(new CambiarPlaca(repositorio, auditoria, RELOJ), pool);
        // La consulta se envuelve igual que las escrituras, y por el mismo motivo:
        // sin transaccion no hay SET LOCAL y la politica RLS no puede evaluarse.
        // Leer «simple» desde el controlador no funcionaria nunca.
        deuda = new DeudaDeMentira();
        consulta = envolver(new ConsultaDeVehiculos(repositorio, deuda), pool);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, DriverManagerDataSource pool) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(
                        new TenantTransactionManager(pool),
                        new AnnotationTransactionAttributeSource()));
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
        OrigenContext.fijar(new Origen("jperez", null, "10.2.2.2"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("La placa")
    class LaPlaca {

        @Test
        @DisplayName("'ABC-123' y 'ABC123' no pueden ser dos vehiculos")
        void elGuionNoCreaUnSegundoVehiculo() {
            registrar.registrar(nuevo("W1A-111"), Observacion.de("Alta por tarjeta de propiedad"));

            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            nuevo("W1A111"),
                                            Observacion.de("Alta duplicada, sin el guion")))
                    .as(
                            "para el dominio son la misma placa. Con la restriccion de V2 —sobre el"
                                    + " texto tal cual— la base admitia las dos filas, y el que se"
                                    + " libra de una cobranza es el que el operador no escribio"
                                    + " igual")
                    .hasMessageContaining("vehiculo_placa_uq");
        }

        @Test
        @DisplayName("se encuentra escribiendola de las dos formas")
        void seEncuentraDeLasDosFormas() {
            registrar.registrar(nuevo("W2B-222"), Observacion.de("Alta por tarjeta de propiedad"));

            assertThat(consulta.porPlaca(Placa.de("W2B-222")).vehiculo().placa())
                    .isEqualTo(Placa.de("W2B-222"));
            assertThat(consulta.porPlaca(Placa.de("W2B222")).vehiculo().placa())
                    .as("en ventanilla la placa llega escrita como se le ocurrio a quien pregunta")
                    .isEqualTo(Placa.de("W2B-222"));
            assertThat(consulta.porPlaca(Placa.de("w2b 222")).vehiculo().placa())
                    .as("`Placa` normaliza al construirse: minusculas y espacios no son otra placa")
                    .isEqualTo(Placa.de("W2B-222"));
        }
    }

    @Nested
    @DisplayName("El cambio de placa")
    class ElCambio {

        @Test
        @DisplayName("no reescribe la papeleta: el acta dice la placa que decia")
        void noReescribeLaPapeleta() throws SQLException {
            Vehiculo vehiculo =
                    registrar.registrar(nuevo("W3C-333"), Observacion.de("Alta del vehiculo"));
            long vehiculoId = requireId(vehiculo);
            long papeleta = imponerPapeleta(vehiculoId, "W3C-333");

            cambiarPlaca.cambiar(
                    vehiculoId,
                    Placa.de("W9Z-999"),
                    Observacion.de("Regrabacion autorizada por SUNARP"));

            assertThat(placaDeLaPapeleta(papeleta))
                    .as(
                            "sincronizar papeleta.placa es lo que uno escribe sin pensar, y es"
                                    + " reescribir un acta: el documento pasaria a decir una placa"
                                    + " que ese dia no existia")
                    .isEqualTo("W3C-333");
            assertThat(vehiculoDeLaPapeleta(papeleta))
                    .as("y el enlace sigue siendo el mismo vehiculo, que es lo que no cambia")
                    .isEqualTo(vehiculoId);
        }

        @Test
        @DisplayName("dos cambios seguidos se reconstruyen enteros desde la auditoria")
        void elHistorialSeReconstruye() {
            Vehiculo vehiculo =
                    registrar.registrar(nuevo("W4D-444"), Observacion.de("Alta del vehiculo"));
            long vehiculoId = requireId(vehiculo);

            cambiarPlaca.cambiar(
                    vehiculoId,
                    Placa.de("W5E-555"),
                    Observacion.de("Primer cambio, por duplicado"));
            cambiarPlaca.cambiar(
                    vehiculoId, Placa.de("W6F-666"), Observacion.de("Segundo cambio, regrabacion"));

            List<CambioDePlaca> historial = consulta.porPlaca(Placa.de("W6F-666")).historial();

            assertThat(historial)
                    .as(
                            "llaveada por la placa, la auditoria daria dos trozos sin nada que los"
                                    + " una; llaveada por el identificador, el historial es uno")
                    .hasSize(2);
            assertThat(historial.get(0).nueva()).isEqualTo(Placa.de("W6F-666"));
            assertThat(historial.get(0).anterior()).isEqualTo(Placa.de("W5E-555"));
            assertThat(historial.get(1).nueva()).isEqualTo(Placa.de("W5E-555"));
            assertThat(historial.get(1).anterior()).isEqualTo(Placa.de("W4D-444"));
            assertThat(historial.get(0).observacion())
                    .as("el historial dice por que, que es lo que se pregunta en un reclamo")
                    .contains("regrabacion");
            assertThat(historial.get(0).usuario()).isEqualTo("jperez");
        }

        @Test
        @DisplayName("el alta no aparece en el historial de placas")
        void elAltaNoEsUnCambioDePlaca() {
            Vehiculo vehiculo =
                    registrar.registrar(nuevo("W7G-777"), Observacion.de("Alta del vehiculo"));

            assertThat(consulta.porPlaca(vehiculo.placa()).historial())
                    .as("un alta no es un cambio: no hay placa anterior de la que hablar")
                    .isEmpty();
        }

        @Test
        @DisplayName("cambiar a la misma placa con otro guion no es un cambio")
        void elMismoDatoNoEsUnCambio() {
            Vehiculo vehiculo =
                    registrar.registrar(nuevo("W8H-888"), Observacion.de("Alta del vehiculo"));

            assertThatThrownBy(
                            () ->
                                    cambiarPlaca.cambiar(
                                            requireId(vehiculo),
                                            Placa.de("W8H888"),
                                            Observacion.de("Quitar el guion, por prolijidad")))
                    .as("dejaria en la auditoria un cambio que no cambia nada")
                    .hasMessageContaining("no hay nada que cambiar");
        }

        @Test
        @DisplayName("una placa que no esta en el padron no devuelve una ficha vacia")
        void laPlacaQueNoEstaNoDevuelveFichaVacia() {
            assertThatThrownBy(() -> consulta.porPlaca(Placa.de("Z9Z-999")))
                    .as(
                            "una ficha con los campos en blanco se lee como «el vehiculo no tiene datos»")
                    .hasMessageContaining("Z9Z-999");
        }

        @Test
        @DisplayName("el cambio queda auditado bajo el identificador, no bajo la placa")
        void laClaveDeLaAuditoriaEsElIdentificador() throws SQLException {
            Vehiculo vehiculo =
                    registrar.registrar(nuevo("W1J-101"), Observacion.de("Alta del vehiculo"));
            long vehiculoId = requireId(vehiculo);

            cambiarPlaca.cambiar(
                    vehiculoId, Placa.de("W2K-202"), Observacion.de("Cambio por regrabacion"));

            assertThat(clavesAuditadas())
                    .as("la clave es el identificador del vehiculo, estable ante el cambio")
                    .contains(String.valueOf(vehiculoId))
                    .doesNotContain("W1J-101", "W2K-202");
        }
    }

    @Nested
    @DisplayName("consulta_vehiculos (#25)")
    class LaConsultaDelPadron {

        @Test
        @DisplayName("filtra por placa, por motor y por el codigo del titular")
        void filtraPorPlacaMotorYTitular() {
            registrar.registrar(
                    conMotor(nuevo("Y1A-111"), "MOT-Y1A"), Observacion.de("Alta del vehiculo"));
            registrar.registrar(
                    conMotor(nuevo("Y2B-222"), "MOT-Y2B"),
                    Observacion.de("Otro vehiculo, mismo" + " titular"));

            assertThat(buscar(new CriterioDeVehiculo("Y1A111", null, null, null)).contenido())
                    .as("sin guion, igual que findByPlaca")
                    .extracting(fila -> fila.fila().vehiculo().placa())
                    .containsExactly(Placa.de("Y1A-111"));

            assertThat(buscar(new CriterioDeVehiculo(null, "MOT-Y2B", null, null)).contenido())
                    .extracting(fila -> fila.fila().vehiculo().placa())
                    .containsExactly(Placa.de("Y2B-222"));

            assertThat(buscar(new CriterioDeVehiculo(null, null, "C-VEH-1", null)).contenido())
                    .as(
                            "las dos son del mismo titular; puede haber mas de otros metodos de"
                                    + " esta misma clase, que comparten la base sembrada")
                    .extracting(fila -> fila.fila().vehiculo().placa())
                    .contains(Placa.de("Y1A-111"), Placa.de("Y2B-222"));
        }

        @Test
        @DisplayName("el titular de otro contribuyente no aparece al filtrar por el primero")
        void elFiltroPorContribuyenteNoTraeAOtroTitular() throws SQLException {
            long otro = crearContribuyente("C-VEH-9", "40404099", "OTRO PROPIETARIO");
            registrar.registrar(nuevo("Y3C-333"), Observacion.de("Del titular de siempre"));
            sembrarVehiculo(otro, "Y4D-444", EstadoVehiculo.ACTIVO);

            List<VehiculoConDeuda> encontrados =
                    buscar(new CriterioDeVehiculo(null, null, "C-VEH-1", null)).contenido();

            assertThat(encontrados)
                    .extracting(fila -> fila.fila().vehiculo().placa())
                    .doesNotContain(Placa.de("Y4D-444"));
        }

        @Test
        @DisplayName("trae el nombre del titular resuelto, sin que quien llama pida otra cosa")
        void traeElTitularResuelto() {
            registrar.registrar(nuevo("Y5E-555"), Observacion.de("Alta del vehiculo"));

            VehiculoConDeuda fila =
                    buscar(new CriterioDeVehiculo("Y5E555", null, null, null)).contenido().get(0);

            assertThat(fila.fila().titular()).isEqualTo("PROPIETARIO DE PRUEBA");
            assertThat(fila.fila().codigoContribuyente()).isEqualTo("C-VEH-1");
        }

        @Test
        @DisplayName("solo 'BAJA' filtra contra el padron; el resto de la afectacion no")
        void soloBajaFiltraContraElPadron() throws SQLException {
            sembrarVehiculo(contribuyente, "Y6F-666", EstadoVehiculo.BAJA);

            assertThat(
                            buscar(new CriterioDeVehiculo(null, null, null, EstadoVehiculo.BAJA))
                                    .contenido())
                    .extracting(fila -> fila.fila().vehiculo().placa())
                    .containsExactly(Placa.de("Y6F-666"));
        }

        @Test
        @DisplayName(
                "la deuda de la fila es solo la de ese vehiculo, no la de otro del mismo titular")
        void laDeudaEsSoloDeEseVehiculo() {
            Vehiculo primero =
                    registrar.registrar(nuevo("Y7G-777"), Observacion.de("Alta del vehiculo"));
            Vehiculo segundo =
                    registrar.registrar(
                            nuevo("Y8H-888"), Observacion.de("Otro vehiculo, mismo" + " titular"));

            deuda.para(
                    contribuyente,
                    List.of(
                            obligacion("VEHICULAR", 2026, requireId(primero), Dinero.de("150.00")),
                            obligacion(
                                    "VEHICULAR", 2026, requireId(segundo), Dinero.de("999.00"))));

            VehiculoConDeuda fila =
                    buscar(new CriterioDeVehiculo("Y7G777", null, null, null)).contenido().get(0);

            assertThat(fila.deuda().importe())
                    .as("la deuda del segundo vehiculo (999.00) no puede colarse en la del primero")
                    .isEqualTo(Dinero.de("150.00"));
            assertThat(fila.deuda().actualizadoA()).isEqualTo(LocalDate.of(2026, 8, 20));
        }

        @Test
        @DisplayName("sin ninguna obligacion asentada, la deuda es cero, no una fila sin cifra")
        void sinObligacionesLaDeudaEsCero() {
            registrar.registrar(nuevo("Y9J-999"), Observacion.de("Alta del vehiculo"));

            VehiculoConDeuda fila =
                    buscar(new CriterioDeVehiculo("Y9J999", null, null, null)).contenido().get(0);

            assertThat(fila.deuda().importe()).isEqualTo(Dinero.CERO);
        }

        private Pagina<VehiculoConDeuda> buscar(CriterioDeVehiculo criterio) {
            return consulta.buscar(
                    criterio,
                    LocalDate.now(RELOJ),
                    new Paginacion(0, 20, "placa", Paginacion.Direccion.ASCENDENTE));
        }

        /**
         * Siembra un vehiculo por SQL directo, no por {@link VehiculoRepository#save}: ese metodo
         * no esta envuelto en transaccion (a diferencia de {@code registrar} y {@code
         * cambiarPlaca}), y sin {@code SET LOCAL} la politica RLS no puede evaluarse. Es el mismo
         * patron que {@link #crearContribuyente}.
         */
        private void sembrarVehiculo(long contribuyenteId, String placa, EstadoVehiculo estado)
                throws SQLException {
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                kamayuk.rentas.esquema.ContextoDeTenant.fijar(app, municipalidad);
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id,"
                                        + " marca, modelo, categoria, anio_fabricacion,"
                                        + " anio_inscripcion, estado)"
                                        + " VALUES (?, ?, ?, 'TOYOTA', 'HILUX', 'CAMIONETA', 2020,"
                                        + " 2021, ?)")) {
                    sentencia.setLong(1, municipalidad);
                    sentencia.setString(2, placa);
                    sentencia.setLong(3, contribuyenteId);
                    sentencia.setString(4, estado.name());
                    sentencia.executeUpdate();
                    app.commit();
                }
            }
        }

        private Vehiculo conMotor(Vehiculo vehiculo, String motor) {
            return new Vehiculo(
                    vehiculo.id(),
                    vehiculo.placa(),
                    vehiculo.contribuyenteId(),
                    vehiculo.marca(),
                    vehiculo.modelo(),
                    vehiculo.categoria(),
                    vehiculo.anioFabricacion(),
                    vehiculo.anioInscripcion(),
                    motor,
                    vehiculo.numeroSerie(),
                    vehiculo.estado());
        }

        private ObligacionPublica obligacion(
                String tributo, int ejercicio, long vehiculoId, Dinero total) {
            return new ObligacionPublica(
                    tributo,
                    new Ejercicio(ejercicio),
                    null,
                    vehiculoId,
                    LocalDate.of(2026, 8, 20),
                    total,
                    Dinero.CERO,
                    Dinero.CERO,
                    Dinero.CERO);
        }
    }

    /**
     * Doble de {@link ConsultaDeDeudaPublica}: aqui no hay libro de asientos, asi que la deuda de
     * cada contribuyente la fija el propio caso a mano. Es lo que permite demostrar que {@link
     * ConsultaDeVehiculos#buscar} suma solo las obligaciones del vehiculo de la fila —no las de
     * otro predio o vehiculo del mismo titular— sin levantar {@code cuentacorriente}.
     */
    private static final class DeudaDeMentira implements ConsultaDeDeudaPublica {
        private final Map<Long, List<ObligacionPublica>> porContribuyente = new HashMap<>();

        void para(long contribuyenteId, List<ObligacionPublica> obligaciones) {
            porContribuyente.put(contribuyenteId, obligaciones);
        }

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return porContribuyente.getOrDefault(contribuyenteId, List.of());
        }
    }

    /* ── Utilidades ────────────────────────────────────────────────────── */

    private static Vehiculo nuevo(String placa) {
        return Vehiculo.nuevo(
                Placa.de(placa), contribuyente, "TOYOTA", "YARIS", "M1", FABRICACION, INSCRIPCION);
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
                                        + " VALUES ('220301', 'Municipalidad del padron vehicular',"
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
        return crearContribuyente("C-VEH-1", "40404040", "PROPIETARIO DE PRUEBA");
    }

    private static long crearContribuyente(String codigo, String dni, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            kamayuk.rentas.esquema.ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'prueba')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.setString(4, nombre);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /**
     * Impone una papeleta de transito contra el vehiculo, con la placa <b>del acta</b>.
     *
     * <p>Los importes son de relleno y no representan ninguna regla: el calculo de la sancion
     * depende de la UIT y sigue bloqueado por D-02.
     */
    private static long imponerPapeleta(long vehiculoId, String placa) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            kamayuk.rentas.esquema.ContextoDeTenant.fijar(app, municipalidad);
            long codigo = crearCodigoDeInfraccion(app, vehiculoId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO papeleta (municipalidad_id, familia, numero,"
                                    + " codigo_infraccion_id, fecha_infraccion, lugar, placa,"
                                    + " vehiculo_id, propietario_id, obligado_id, base_imponible,"
                                    + " porcentaje_infraccion, importe_infraccion,"
                                    + " porcentaje_a_cobrar, importe_a_pagar, usuario_registro,"
                                    + " observacion)"
                                    + " VALUES (?, 'TRANSITO', ?, ?, DATE '2026-03-04', 'Av. Grau',"
                                    + "         ?, ?, ?, ?, 1000.00, 8.0000, 100.00, 100.0000,"
                                    + "         100.00, 'jperez', 'papeleta de la prueba')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, "P-" + vehiculoId);
                sentencia.setLong(3, codigo);
                sentencia.setString(4, placa);
                sentencia.setLong(5, vehiculoId);
                sentencia.setLong(6, contribuyente);
                // El obligado: a quien se le asiento el cargo de la multa (V41 §1).
                sentencia.setLong(7, contribuyente);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearCodigoDeInfraccion(Connection app, long sufijo) throws SQLException {
        try (PreparedStatement sentencia =
                app.prepareStatement(
                        "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                + " VALUES (?, 'TRANSITO', ?, 'Infraccion de la prueba', 1.0000,"
                                + "         'fixture de la prueba', DATE '2026-01-01')"
                                + " RETURNING id")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setString(2, "G-" + sufijo);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static String placaDeLaPapeleta(long papeletaId) throws SQLException {
        return leerDeLaPapeleta(papeletaId, "placa");
    }

    private static long vehiculoDeLaPapeleta(long papeletaId) throws SQLException {
        return Long.parseLong(leerDeLaPapeleta(papeletaId, "vehiculo_id"));
    }

    private static String leerDeLaPapeleta(long papeletaId, String columna) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT " + columna + " FROM papeleta WHERE id = ?")) {
            sentencia.setLong(1, papeletaId);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getString(1);
            }
        }
    }

    private static List<String> clavesAuditadas() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT clave FROM auditoria WHERE tabla = 'vehiculo'")) {
            try (ResultSet filas = sentencia.executeQuery()) {
                List<String> claves = new java.util.ArrayList<>();
                while (filas.next()) {
                    claves.add(filas.getString(1));
                }
                return claves;
            }
        }
    }
}
