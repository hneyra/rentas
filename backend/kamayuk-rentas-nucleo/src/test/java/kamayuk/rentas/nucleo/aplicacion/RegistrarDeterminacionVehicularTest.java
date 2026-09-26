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
import java.time.LocalDate;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.prueba.TitularidadDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
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
 * plazo de afectación se respeta sin intervención manual, la alícuota sale del conjunto sellado
 * —cambiarla cambia el importe—, y el ejercicio se determina a quien era propietario <b>al 1 de
 * enero</b> y no a quien lo es hoy (TUO LTM art. 31, #329).
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
    private static long comprador;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static VehiculoRepositoryJdbc vehiculos;
    private static DeterminacionRepositoryJdbc determinaciones;
    private static RegistrarDeterminacionVehicular registrar;
    private static RegistrarTransferencia transferir;
    private static AdministrarParametros administrarParametros;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        contribuyente =
                crearContribuyente("C-VEHDET-1", "40404141", "TITULAR, DETERMINACION VEHICULAR");
        comprador = crearContribuyente("C-VEHDET-2", "40404142", "COMPRADOR, A MITAD DE ANIO");

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
                                new TransferenciaRepositoryJdbc(jdbc),
                                new ValoresReferenciales(
                                        new ValorReferencialRepositoryJdbc(jdbc), parametros),
                                determinaciones,
                                parametros,
                                new AuditoriaJdbc(jdbc, RELOJ)));
        // La transferencia por el camino de verdad: el caso de uso que sobrescribe el titular del
        // vehiculo y deja la fecha solo en la fila de `transferencia` (#329). La titularidad
        // predial no la toca una transferencia de vehiculo, pero el constructor la pide.
        transferir =
                envolver(
                        new RegistrarTransferencia(
                                new TransferenciaRepositoryJdbc(jdbc),
                                new TitularidadDelEscenario(jdbc),
                                vehiculos,
                                new AuditoriaJdbc(jdbc, RELOJ)));
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

    @Nested
    @DisplayName("El ejercicio es de quien era propietario al 1 de enero (TUO LTM art. 31, #329)")
    class ElPropietarioAlPrimeroDeEnero {

        /**
         * La siembra que distingue: una transferencia <b>dentro</b> del ejercicio. Un vehiculo sin
         * transferencias —la muestra de las demas pruebas de este archivo— da el mismo verde con el
         * titular de hoy y con el del 1 de enero.
         */
        @Test
        @DisplayName("vendido el 10 de junio: 2026 se determina al vendedor y 2027 al comprador")
        void unaTransferenciaDentroDelEjercicio() throws SQLException {
            long vehiculoId = crearVehiculoConValorReferencial("W6F-666", "SUZUKI", "SWIFT");
            sellarConValorReferencialYAlicuota(
                    new Ejercicio(2027), "SUZUKI", "SWIFT", new BigDecimal("1.0"));
            venderAlComprador(vehiculoId, LocalDate.of(2026, 6, 10));

            Determinacion de2026 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    false,
                                    Observacion.de("Determinacion del ejercicio de la venta"))
                            .determinacion();
            Determinacion de2027 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    new Ejercicio(2027),
                                    false,
                                    Observacion.de("Determinacion del ejercicio siguiente"))
                            .determinacion();

            assertThat(contribuyenteDeLaFila(de2026))
                    .as(
                            "al 1 de enero de 2026 el vehiculo era del vendedor: el comprador asume"
                                    + " la condicion de contribuyente desde el 1 de enero de 2027")
                    .isEqualTo(contribuyente);
            assertThat(contribuyenteDeLaFila(de2027))
                    .as("al 1 de enero de 2027 ya era del comprador")
                    .isEqualTo(comprador);
        }

        /**
         * El borde: la transferencia fechada el mismo 1 de enero. El art. 31, segundo parrafo, lo
         * decide: «el adquirente asume la condicion de contribuyente a partir del 1 de enero del
         * ano siguiente». Vendido el 2026-01-01, el 2026 es del vendedor y el 2027 del comprador.
         */
        @Test
        @DisplayName(
                "vendido el mismo 1 de enero: 2026 se determina al vendedor y 2027 al comprador")
        void unaTransferenciaDelPrimeroDeEnero() throws SQLException {
            long vehiculoId = crearVehiculoConValorReferencial("W7G-777", "MAZDA", "DEMIO");
            sellarConValorReferencialYAlicuota(
                    new Ejercicio(2027), "MAZDA", "DEMIO", new BigDecimal("1.0"));
            venderAlComprador(vehiculoId, LocalDate.of(2026, 1, 1));

            Determinacion de2026 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    false,
                                    Observacion.de("Vendido el primer dia del ejercicio"))
                            .determinacion();
            Determinacion de2027 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    new Ejercicio(2027),
                                    false,
                                    Observacion.de("El ejercicio siguiente a la venta"))
                            .determinacion();

            assertThat(contribuyenteDeLaFila(de2026))
                    .as(
                            "vendido el 1 de enero de 2026, el comprador es contribuyente desde el"
                                    + " 1 de enero de 2027: el 2026 sigue siendo del vendedor")
                    .isEqualTo(contribuyente);
            assertThat(contribuyenteDeLaFila(de2027)).isEqualTo(comprador);
        }

        /**
         * El otro lado del borde: vendido el ultimo dia de un ejercicio, el siguiente ya es del
         * comprador. Se mide con el 31 de diciembre de 2026 y no con el de 2025 porque la base de
         * prueba solo tiene las particiones de {@code auditoria} de 2026 y 2027, y la transferencia
         * se audita en el ejercicio de su fecha; el 2025-12-31 lo fija la prueba pura.
         */
        @Test
        @DisplayName(
                "vendido el 31 de diciembre de 2026: 2026 es del vendedor y 2027 del comprador")
        void unaTransferenciaDelTreintaYUnoDeDiciembre() throws SQLException {
            long vehiculoId = crearVehiculoConValorReferencial("W8H-888", "KIA", "PICANTO");
            sellarConValorReferencialYAlicuota(
                    new Ejercicio(2027), "KIA", "PICANTO", new BigDecimal("1.0"));
            venderAlComprador(vehiculoId, LocalDate.of(2026, 12, 31));

            Determinacion de2026 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    EJERCICIO_AFECTO,
                                    false,
                                    Observacion.de("Vendido el ultimo dia del ejercicio"))
                            .determinacion();
            Determinacion de2027 =
                    registrar
                            .calcular(
                                    vehiculoId,
                                    new Ejercicio(2027),
                                    false,
                                    Observacion.de("El ejercicio siguiente a la venta"))
                            .determinacion();

            assertThat(contribuyenteDeLaFila(de2026)).isEqualTo(contribuyente);
            assertThat(contribuyenteDeLaFila(de2027))
                    .as("vendido el 31 de diciembre, al 1 de enero siguiente ya era del comprador")
                    .isEqualTo(comprador);
        }
    }

    // ------------------------------------------------------------------

    /** Vende el vehiculo del titular de siempre al comprador, por el caso de uso de verdad. */
    @Nested
    @DisplayName("La base es la del art. 32: el mayor entre la adquisicion y la tabla (#330)")
    class LaBaseDelArticulo32 {

        /**
         * La siembra que distingue: una adquisicion DISTINTA de la tabla, por encima y por debajo.
         * Con la adquisicion ausente o igual a la tabla —la muestra de las demas pruebas de este
         * archivo— la implementacion que la ignora da el mismo verde. La tabla vale 10 000,00 y la
         * alicuota es 1 %.
         */
        @Test
        @DisplayName("comprado en 30 000,00 con tabla de 10 000,00: la base es 30 000,00")
        void laAdquisicionMayorManda() {
            long vehiculoId = crearVehiculoConValorReferencial("W7A-701", "TOYOTA", "HILUX");
            capturarAdquisicion(vehiculoId, "30000.00", LocalDate.of(2024, 3, 2));

            Determinacion determinada = determinar(vehiculoId, EJERCICIO_AFECTO);

            assertThat(determinada.baseImponible().valor())
                    .as("el art. 32 toma el valor de adquisicion: la tabla es solo el piso")
                    .isEqualByComparingTo("30000.00");
            assertThat(determinada.montoDeterminado().valor()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("comprado en 8 000,00 con tabla de 10 000,00: la tabla es el piso")
        void laTablaEsElPiso() {
            long vehiculoId = crearVehiculoConValorReferencial("W7B-702", "TOYOTA", "YARIS");
            capturarAdquisicion(vehiculoId, "8000.00", LocalDate.of(2024, 3, 2));

            Determinacion determinada = determinar(vehiculoId, EJERCICIO_AFECTO);

            assertThat(determinada.baseImponible().valor())
                    .as("«en ningun caso sera menor» que la tabla")
                    .isEqualByComparingTo("10000.00");
        }

        /**
         * La adquisicion es la del propietario al 1 de enero: la del primero es la del vehiculo, la
         * de uno posterior es el valor de su transferencia (60 000,00 en {@code
         * venderAlComprador}).
         */
        @Test
        @DisplayName(
                "vendido en junio en 60 000,00: 2026 usa la del vendedor y 2027 la del comprador")
        void laAdquisicionEsLaDelPropietarioDelEjercicio() throws SQLException {
            long vehiculoId = crearVehiculoConValorReferencial("W7C-703", "NISSAN", "FRONTIER");
            sellarConValorReferencialYAlicuota(
                    new Ejercicio(2027), "NISSAN", "FRONTIER", new BigDecimal("1.0"));
            capturarAdquisicion(vehiculoId, "30000.00", LocalDate.of(2024, 3, 2));
            venderAlComprador(vehiculoId, LocalDate.of(2026, 6, 10));

            assertThat(determinar(vehiculoId, EJERCICIO_AFECTO).baseImponible().valor())
                    .as("2026 es del vendedor, y su adquisicion es la del vehiculo")
                    .isEqualByComparingTo("30000.00");
            assertThat(determinar(vehiculoId, new Ejercicio(2027)).baseImponible().valor())
                    .as("2027 es del comprador, y su adquisicion es lo que pago en junio")
                    .isEqualByComparingTo("60000.00");
        }

        /**
         * #477 — De donde salio la base se guarda con la fila, y se relee.
         *
         * <p>El par que distingue: una adquisicion por encima de la tabla y un vehiculo sin
         * adquisicion. Un origen fijo —o uno que no se escribiera— daria el mismo valor en los dos,
         * o nulo; aqui cada fila tiene que decir el suyo.
         */
        @Test
        @DisplayName(
                "#477 — la fila guarda de donde salio la base: ADQUISICION y"
                        + " TABLA_SIN_ADQUISICION")
        void laFilaGuardaElOrigenDeLaBase() {
            // Cada vehiculo se determina en cuanto se crea: crear el siguiente sella otro conjunto,
            // que solo trae el valor referencial de su modelo.
            long conAdquisicion = crearVehiculoConValorReferencial("W7D-704", "TOYOTA", "HILUX");
            capturarAdquisicion(conAdquisicion, "30000.00", LocalDate.of(2024, 3, 2));
            String delQueSeCompro = releida(determinar(conAdquisicion, EJERCICIO_AFECTO));
            long sinAdquisicion = crearVehiculoConValorReferencial("W7E-705", "TOYOTA", "YARIS");
            String delQueNoTieneElDato = releida(determinar(sinAdquisicion, EJERCICIO_AFECTO));

            assertThat(java.util.Arrays.asList(delQueSeCompro, delQueNoTieneElDato))
                    .as(
                            "una determinacion reclamada tiene que decir si hubo comparacion con"
                                    + " la adquisicion o solo el piso, y por que")
                    .containsExactly("ADQUISICION", "TABLA_SIN_ADQUISICION");
        }

        @Test
        @DisplayName("#477 — y la base no admite un origen del art. 32 en otro tributo")
        void soloElVehicularLlevaOrigen() {
            long vehiculoId = crearVehiculoConValorReferencial("W7F-706", "TOYOTA", "HILUX");
            long id =
                    java.util.Objects.requireNonNull(determinar(vehiculoId, EJERCICIO_AFECTO).id());

            // La misma fila, como si fuera un PREDIAL: el origen no tiene sentido fuera del
            // vehicular, y la base lo dice aunque el dominio no llegue a construirla.
            assertThatThrownBy(
                            () ->
                                    transaccion.executeWithoutResult(
                                            estado ->
                                                    jdbc.sql(
                                                                    "INSERT INTO determinacion"
                                                                            + " (municipalidad_id,"
                                                                            + " ejercicio, tributo,"
                                                                            + " contribuyente_id,"
                                                                            + " conjunto_id,"
                                                                            + " base_imponible,"
                                                                            + " monto_determinado,"
                                                                            + " reglas_aplicadas,"
                                                                            + " usuario_calculo,"
                                                                            + " origen_base)"
                                                                            + " SELECT"
                                                                            + " municipalidad_id,"
                                                                            + " ejercicio,"
                                                                            + " 'PREDIAL',"
                                                                            + " contribuyente_id,"
                                                                            + " conjunto_id,"
                                                                            + " base_imponible,"
                                                                            + " monto_determinado,"
                                                                            + " reglas_aplicadas,"
                                                                            + " usuario_calculo,"
                                                                            + " origen_base"
                                                                            + " FROM determinacion"
                                                                            + " WHERE id = :id")
                                                            .param("id", id)
                                                            .update()))
                    .hasStackTraceContaining("determinacion_origen_base_solo_vehicular_ck");
        }

        /** El origen de la base, tal como quedo en la fila: se relee, no se toma del calculo. */
        private String releida(Determinacion asentada) {
            long id = java.util.Objects.requireNonNull(asentada.id());
            return transaccion.execute(
                    estado ->
                            new kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc(
                                            jdbc)
                                    .findById(id)
                                    .orElseThrow()
                                    .origenDeLaBase());
        }

        private void capturarAdquisicion(long vehiculoId, String valor, LocalDate fecha) {
            transaccion.executeWithoutResult(
                    estado ->
                            vehiculos.save(
                                    vehiculos
                                            .findById(vehiculoId)
                                            .orElseThrow()
                                            .conAdquisicion(Dinero.de(valor), fecha)));
        }

        private Determinacion determinar(long vehiculoId, Ejercicio ejercicio) {
            return registrar
                    .calcular(vehiculoId, ejercicio, false, Observacion.de("Determinacion #330"))
                    .determinacion();
        }
    }

    private static void venderAlComprador(long vehiculoId, LocalDate fecha) {
        transferir.transferirVehiculo(
                vehiculoId,
                comprador,
                TipoTransferencia.COMPRA_VENTA,
                fecha,
                Dinero.de("60000.00"),
                false,
                "Tarjeta de propiedad",
                Observacion.de("Compraventa del vehiculo a mitad de anio"));
    }

    /**
     * El contribuyente de la fila que quedo en {@code determinacion}, leido de la tabla y no del
     * objeto devuelto: lo que se cobra es lo que se asento.
     */
    private static long contribuyenteDeLaFila(Determinacion determinacion) {
        return transaccion.execute(
                estado ->
                        jdbc.sql("SELECT contribuyente_id FROM determinacion WHERE id = :id")
                                .param("id", determinacion.id())
                                .query(Long.class)
                                .single());
    }

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
                parametroNumerico("VEHICULAR_ALICUOTA", alicuota),
                Observacion.de("Alicuota vehicular ficticia"));
        // El minimo imponible del art. 34 y la UIT con que se convierte: desde #399 salen del
        // conjunto sellado y no del cuerpo de la peticion, asi que sin ellos no hay determinacion
        // que calcular. Las dos cifras son ficticias, como la alicuota de arriba.
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("VEHICULAR_MINIMO_UIT", new BigDecimal("1.5")),
                Observacion.de("Minimo imponible vehicular ficticio"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("UIT", new BigDecimal("100.00")),
                Observacion.de("UIT ficticia"));
        administrarParametros.agregarParametro(
                conjunto.id(), edicion, Observacion.de("Cuadro vehicular ficticio"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroDeRedondeo("IMPUESTO_VEHICULAR"),
                Observacion.de("Politica de redondeo de ADR-0018 (#378)"));
        administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
    }

    /**
     * La fila {@code REDONDEO:‹punto›} con la politica de ADR-0018 —escala 2, {@code HALF_UP}—, que
     * el derivado de {@code normativa} todavia no publica (#378). La escala en {@code
     * valor_numerico} y el modo en {@code valor_texto}, en la misma fila.
     */
    private static long parametroDeRedondeo(String punto) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, valor_texto, vigencia_desde,"
                                        + " documento_fuente, usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, 'REDONDEO', ?, 2, 'HALF_UP',"
                                        + " DATE '2026-01-01', 'ADR-0018 de normativa, sembrado"
                                        + " para la prueba (#378)', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, punto);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                carga.commit();
                return id;
            }
        }
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

    private static long crearContribuyente(String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
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
}
