package kamayuk.rentas.valores.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.aplicacion.CarteraDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.aplicacion.DeclararPrescripcion;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #674 — Declarar la prescripcion no mueve el libro, y la baja de RF-044 si: las dos cifras.
 *
 * <h2>Que decide esta prueba</h2>
 *
 * <p>La pregunta de #674 era «una deuda cuya accion de cobro prescribio, ¿sigue siendo cartera
 * pendiente y emision del ejercicio?». La respuesta es <b>si, hasta que la administracion la de de
 * baja</b>, y el razonamiento —art. 43 del TUO del Codigo Tributario, que dice que lo que prescribe
 * es la <i>accion</i> y no la obligacion— esta en el javadoc de {@link DeclararPrescripcion}.
 *
 * <p>Lo que aqui se mide es que esa respuesta sea <b>verdadera de las cifras y no solo del
 * razonamiento</b>, y se mide con las cifras y no con un booleano (el criterio de #601 AC 5 y de
 * #639 AC 5): las mismas dos del panel de recaudacion —«lo cargado», que es el denominador de todas
 * las barras de avance, y la cartera pendiente— <b>antes y despues</b> de declarar la prescripcion,
 * y otra vez despues de la baja.
 *
 * <p><b>Y el acto ocurre de verdad</b>: la declaracion se comprueba {@link
 * ResultadoDeLaSolicitud#PROCEDE} con su ejercicio prescrito antes de volver a medir. Sin eso, «las
 * cifras no se movieron» seria compatible con que no hubiera pasado nada.
 *
 * <h2>Por que el ejercicio es 2026 y la solicitud se presenta en 2033</h2>
 *
 * <p>Porque una deuda prescrita es por construccion de hace anios, y {@code
 * cuenta_corriente_asiento} solo tiene declaradas las particiones de <b>2026 y 2027</b> (V2): hoy
 * no se puede asentar deuda de 2019 en el libro, que es lo que #52 ya encontro al transferir una
 * fiscalizacion de 2024. Asi que la deuda es de 2026 y quien solicita lo hace en 2033, cuando los
 * cuatro anios del art. 43 ya corrieron. La fecha de presentacion es un argumento del caso de uso,
 * no el reloj (regla 6), de modo que esto no es un truco de la prueba: es como se resuelve.
 *
 * <p>Conectada como {@code sgtm_app} —quien sufre la politica RLS—, nunca como {@code sgtm_owner}:
 * con {@code FORCE ROW LEVEL SECURITY} el dueno tambien queda sujeto a la politica, asi que esa
 * mutacion pasaria en verde sin demostrar nada (#537, #545).
 */
@DisplayName("#674 — La prescripcion declarada y las dos cifras del panel")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PrescripcionYLaCarteraJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TRIBUTO = "PREDIAL";

    /** Cuando se presenta la solicitud: ya corridos los cuatro anios del art. 43. */
    private static final LocalDate PRESENTACION = LocalDate.of(2033, 6, 1);

    /** A que fecha se lee la cartera: la misma en las tres medidas, o no se podrian comparar. */
    private static final LocalDate CORTE = LocalDate.of(2033, 6, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static long conjunto;
    private static long predioUno;
    private static long predioDos;
    private static TransactionTemplate transaccion;
    private static CarteraDelLibroCuentaCorriente cartera;
    private static DeclararPrescripcion declarar;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad de la prescripcion");
        contribuyente = crearContribuyente("P-0001", "80660001");
        conjunto = crearConjuntoSellado();
        predioUno = crearPredio("000000000000000674");
        predioDos = crearPredio("000000000000000675");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        cartera = new CarteraDelLibroCuentaCorriente(new AsientoRepositoryJdbc(jdbc));
        declarar =
                new DeclararPrescripcion(
                        new PrescripcionRepositoryJdbc(jdbc),
                        new ValorRepositoryJdbc(jdbc),
                        new PlazosParametrizados(new ParametrosDelConjuntoSembrado()),
                        (RegistroDeAuditoria registro) -> {});

        // La deuda viva del ejercicio: dos obligaciones, 500,00 y 200,00.
        asentarCargo(predioUno, new BigDecimal("500.00"));
        asentarCargo(predioDos, new BigDecimal("200.00"));
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
        OrigenContext.fijar(new Origen("prueba.674", "equipo-de-prueba", "127.0.0.1"));
    }

    @AfterEach
    void limpiar() {
        OrigenContext.limpiar();
        TenantContext.limpiar();
    }

    @Test
    @Order(1)
    @DisplayName("las dos cifras del panel son las mismas antes y despues de declarar")
    void lasDosCifrasNoSeMueven() {
        Dinero cargadoAntes = cargado();
        Dinero pendienteAntes = pendiente();
        assertThat(cargadoAntes).isEqualTo(new Dinero(new BigDecimal("700.00")));
        assertThat(pendienteAntes).isEqualTo(new Dinero(new BigDecimal("700.00")));

        Prescripcion declarada = declararLaPrescripcion();

        assertThat(declarada.resultado())
                .as("el acto ocurre de verdad: sin esto, «no se movio» seria «no paso nada»")
                .isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
        assertThat(declarada.ejerciciosPrescritos()).containsExactly(EJERCICIO);

        assertThat(cargado())
                .as("la emision del ejercicio no la cambia una prescripcion declarada")
                .isEqualTo(cargadoAntes);
        assertThat(pendiente())
                .as("ni la cartera: lo que prescribio es la accion de cobro, no la obligacion")
                .isEqualTo(pendienteAntes);
        assertThat(cargado()).isEqualTo(new Dinero(new BigDecimal("700.00")));
        assertThat(pendiente()).isEqualTo(new Dinero(new BigDecimal("700.00")));
    }

    @Test
    @Order(2)
    @DisplayName("y el libro sigue con los mismos asientos: ninguno nuevo")
    void elLibroConservaSusAsientos() {
        long antes = asientos();
        assertThat(antes).isEqualTo(2);

        declararLaPrescripcion();

        assertThat(asientos())
                .as("declarar la prescripcion no escribe ni un asiento (#39, regla 4)")
                .isEqualTo(antes);
        assertThat(asientos()).isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("la baja de deuda —el acto que si decide— mueve las dos, y a la vez")
    void laBajaSiLasMueve() {
        assertThat(cargado()).isEqualTo(new Dinero(new BigDecimal("700.00")));
        assertThat(pendiente()).isEqualTo(new Dinero(new BigDecimal("700.00")));

        // El mismo asiento que produce `MovimientoDeDeuda#enAsientos` para una baja: ABONO de
        // INSOLUTO estampado BAJA_DEUDA. El camino entero de RF-044 —con su documento, su
        // observacion y su comprobacion de que la baja no excede la deuda— lo mide
        // `BajaDeDeudaYLoCargadoJdbcTest` en cuentacorriente; lo que aqui hace falta es el
        // efecto sobre las dos cifras, para poder compararlo con el de la declaracion.
        asentarBaja(predioUno, new BigDecimal("500.00"));

        assertThat(cargado())
                .as("lo cargado baja porque la deuda dejo de estar puesta a cobrar")
                .isEqualTo(new Dinero(new BigDecimal("200.00")));
        assertThat(pendiente()).isEqualTo(new Dinero(new BigDecimal("200.00")));
    }

    // ------------------------------------------------------------------

    private static Prescripcion declararLaPrescripcion() {
        return enTransaccion(
                () ->
                        declarar.declarar(
                                contribuyente,
                                TRIBUTO,
                                EJERCICIO,
                                EJERCICIO,
                                PRESENTACION,
                                CausalDePrescripcion.DECLARACION_PRESENTADA,
                                List.of(),
                                "RES-674-2033",
                                Observacion.de("Se declara la prescripcion solicitada")));
    }

    private static Dinero cargado() {
        CargadoEnElLibro leido = enTransaccion(() -> cartera.cargadoPorTributo(EJERCICIO, CORTE));
        return leido.de(TRIBUTO);
    }

    private static Dinero pendiente() {
        CarteraPendiente leida = enTransaccion(() -> cartera.pendientePorTributo(EJERCICIO, CORTE));
        return leida.total();
    }

    private static long asientos() {
        Long contados =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM cuenta_corriente_asiento"
                                                        + " WHERE ejercicio = :ejercicio")
                                        .param("ejercicio", EJERCICIO.valor())
                                        .query(Long.class)
                                        .single());
        return contados == null ? 0 : contados;
    }

    private static <T> T enTransaccion(Supplier<T> accion) {
        return transaccion.execute(estado -> accion.get());
    }

    /**
     * Los plazos del art. 43 y el desfase del art. 44, con el conjunto REAL que se sembro.
     *
     * <p>Tiene que ser el sembrado y no uno inventado porque {@code prescripcion.conjunto_id} tiene
     * clave foranea contra {@code conjunto_parametros}: ese identificador queda en la fila para que
     * revisar la resolucion dentro de dos anios no resuelva otro plazo (ARQ-09 §3).
     */
    private static final class ParametrosDelConjuntoSembrado implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .texto("PLAZO", "PRESCRIPCION-DECLARACION_PRESENTADA", "4 ANIOS")
                    .texto("PLAZO", "PRESCRIPCION_INICIO-PREDIAL", "1 ANIOS")
                    .construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw new UnsupportedOperationException("#39 resuelve por ejercicio del hecho");
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(conjunto);
        }
    }

    // ---------- siembra ----------

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

    private static long crearContribuyente(String codigo, String dni) {
        return comoApp(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'PRESCRITO, TITULAR', 'siembra')"
                        + " RETURNING id",
                municipalidad,
                codigo,
                dni);
    }

    private static long crearConjuntoSellado() {
        return comoApp(
                "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                        + " VALUES (?, 2033, 1) RETURNING id",
                municipalidad);
    }

    private static long crearPredio(String codigo) {
        return comoApp(
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Prescripcion de prueba') RETURNING id",
                municipalidad,
                codigo);
    }

    private static void asentarCargo(long predioId, BigDecimal monto) {
        comoApp(
                "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                        + " contribuyente_id, tributo, concepto, tipo, fase, predio_id, monto,"
                        + " fecha_valor, documento_origen, usuario_id)"
                        + " VALUES (?, 2026, ?, 'PREDIAL', 'INSOLUTO', 'CARGO', 'ORDINARIA', ?, ?,"
                        + " DATE '2026-02-28', 'EMISION-2026', 'siembra') RETURNING id",
                municipalidad,
                contribuyente,
                predioId,
                monto);
    }

    private static void asentarBaja(long predioId, BigDecimal monto) {
        comoApp(
                "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                        + " contribuyente_id, tributo, concepto, tipo, fase, predio_id, monto,"
                        + " fecha_valor, documento_origen, usuario_id, acto, causal)"
                        + " VALUES (?, 2026, ?, 'PREDIAL', 'INSOLUTO', 'ABONO', 'ORDINARIA', ?, ?,"
                        + " DATE '2033-06-01', 'NC-2033-000001', 'siembra', 'BAJA_DEUDA',"
                        + " 'PRESCRIPCION_DECLARADA')"
                        + " RETURNING id",
                municipalidad,
                contribuyente,
                predioId,
                monto);
    }

    private static long comoApp(String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
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
