package kamayuk.rentas.valores.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
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
import kamayuk.rentas.valores.aplicacion.PrescripcionDeclarada;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.ObligacionPrescrita;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
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
 * #337 — La prescripcion marca el valor por sus lineas, contra PostgreSQL de verdad.
 *
 * <p>Lo que {@code DeclararPrescripcionTest} no puede decir con sus dobles: que el conjunto
 * prescrito que sale de {@code prescripcion} y {@code prescripcion_ejercicio} es el del
 * contribuyente, solo con los ejercicios que prescribieron, y <b>acumulado</b> entre resoluciones;
 * y que la consulta de candidatos trae los valores que tocan lo recien prescrito en cualquiera de
 * sus lineas. Si una de las dos se equivocara, la Specification decidiria bien sobre datos malos.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>El titular tiene cuatro valores: A (PREDIAL 2021), B (PREDIAL 2021 y 2022), C (PREDIAL 2021 y
 * ARBITRIOS 2021) y D (como C, pero en {@code COACTIVA}). Y <b>otro</b> contribuyente tiene ya
 * prescrito el PREDIAL 2022: si el conjunto no se acotara al titular, B saldria cubierto en la
 * primera solicitud, cuando su PREDIAL 2022 todavia es exigible.
 *
 * <p>Las fechas son las del issue: PREDIAL 2021–2022 presentada el 2026-03-01 (2021 prescribe el
 * 2026-01-01, 2022 no hasta el 2027-01-01), y PREDIAL 2022 presentada el 2027-02-01.
 *
 * <p>Conectada como {@code kamayuk_app}, que es quien sufre la politica RLS.
 */
@DisplayName("#337 — La cobertura de la prescripcion contra PostgreSQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LaCoberturaDeLaPrescripcionJdbcTest {

    private static final LocalDate PRIMERA = LocalDate.of(2026, 3, 1);
    private static final LocalDate SEGUNDA = LocalDate.of(2027, 2, 1);
    private static final ObligacionPrescrita PREDIAL_2021 =
            new ObligacionPrescrita("PREDIAL", new Ejercicio(2021));
    private static final ObligacionPrescrita PREDIAL_2022 =
            new ObligacionPrescrita("PREDIAL", new Ejercicio(2022));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long titular;
    private static long otro;
    private static long conjunto;
    private static TransactionTemplate transaccion;
    private static ValorRepositoryJdbc valores;
    private static PrescripcionRepositoryJdbc prescripciones;
    private static DeclararPrescripcion declarar;

    private static Valor a;
    private static Valor b;
    private static Valor c;
    private static Valor d;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        titular = crearContribuyente("C-0337", "80330001");
        otro = crearContribuyente("C-0338", "80330002");
        conjunto =
                comoApp(
                        "INSERT INTO conjunto_parametros_de_prueba"
                                + " (municipalidad_id, ejercicio, version)"
                                + " VALUES (?, 2026, 1) RETURNING id",
                        municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        valores = new ValorRepositoryJdbc(jdbc);
        prescripciones = new PrescripcionRepositoryJdbc(jdbc);
        declarar =
                new DeclararPrescripcion(
                        prescripciones,
                        valores,
                        new PlazosParametrizados(new ParametrosDelConjuntoSembrado()),
                        (RegistroDeAuditoria registro) -> {},
                        Clock.fixed(
                                SEGUNDA.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("prueba.337", "equipo-de-prueba", "127.0.0.1"));
        try {
            a = emitir("OP-2026-033701", EstadoDeValor.EMITIDO, linea("PREDIAL", 2021));
            b =
                    emitir(
                            "OP-2026-033702",
                            EstadoDeValor.EMITIDO,
                            linea("PREDIAL", 2021),
                            linea("PREDIAL", 2022));
            c =
                    emitir(
                            "OP-2026-033703",
                            EstadoDeValor.EMITIDO,
                            linea("PREDIAL", 2021),
                            linea("ARBITRIOS", 2021));
            d =
                    emitir(
                            "OP-2026-033704",
                            EstadoDeValor.EMITIDO,
                            linea("PREDIAL", 2021),
                            linea("ARBITRIOS", 2021));
            enTransaccion(() -> valores.cambiarEstado(d.id(), EstadoDeValor.COACTIVA));
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
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
        OrigenContext.fijar(new Origen("prueba.337", "equipo-de-prueba", "127.0.0.1"));
    }

    @AfterEach
    void limpiar() {
        OrigenContext.limpiar();
        TenantContext.limpiar();
    }

    @Test
    @Order(0)
    @DisplayName("los candidatos: alguna linea en lo pedido, cada valor una vez, sin mayusculas")
    void losCandidatos() {
        // B toca los dos ejercicios pedidos y sale UNA vez; lo decide el EXISTS, no un JOIN.
        assertThat(
                        enTransaccion(
                                () ->
                                        valores.cobrablesConAlgunaLineaEn(
                                                titular,
                                                "predial",
                                                List.of(new Ejercicio(2021), new Ejercicio(2022)))))
                .extracting(Valor::numero)
                .containsExactly(
                        "OP-2026-033701", "OP-2026-033702", "OP-2026-033703", "OP-2026-033704");
        assertThat(
                        enTransaccion(
                                () ->
                                        valores.cobrablesConAlgunaLineaEn(
                                                titular, "PREDIAL", List.of(new Ejercicio(2022)))))
                .extracting(Valor::numero)
                .containsExactly("OP-2026-033702");
    }

    @Test
    @Order(1)
    @DisplayName("el PREDIAL 2022 prescrito de OTRO contribuyente no cuenta para el titular")
    void loDeOtroNoCuenta() {
        PrescripcionDeclarada deOtro = declararPredial(otro, 2022, 2022, SEGUNDA);

        assertThat(deOtro.prescripcion().resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
        assertThat(enTransaccion(() -> prescripciones.obligacionesPrescritasDe(otro)))
                .containsExactly(PREDIAL_2022);
        assertThat(enTransaccion(() -> prescripciones.obligacionesPrescritasDe(titular))).isEmpty();
        assertThat(estados()).containsExactly("EMITIDO", "EMITIDO", "EMITIDO", "COACTIVA");
    }

    @Test
    @Order(2)
    @DisplayName("la solicitud de 2021–2022 marca A, y deja B, C y D en el estado en que estaban")
    void laPrimeraSoloMarcaA() {
        PrescripcionDeclarada primera = declararPredial(titular, 2021, 2022, PRIMERA);

        assertThat(primera.prescripcion().resultado())
                .isEqualTo(ResultadoDeLaSolicitud.PROCEDE_EN_PARTE);
        assertThat(enTransaccion(() -> prescripciones.obligacionesPrescritasDe(titular)))
                .as("el 2022 se computo y NO prescribio: no entra en el conjunto")
                .containsExactly(PREDIAL_2021);
        assertThat(estados()).containsExactly("PRESCRITO", "EMITIDO", "EMITIDO", "COACTIVA");
        assertThat(primera.valoresPrescritos())
                .extracting(Valor::numero)
                .containsExactly("OP-2026-033701");
        assertThat(primera.valoresCubiertosEnParte())
                .extracting(Valor::numero)
                .containsExactly("OP-2026-033702", "OP-2026-033703", "OP-2026-033704");
    }

    @Test
    @Order(3)
    @DisplayName("la del PREDIAL 2022 completa B con la anterior, y C y D siguen vivos")
    void laSegundaCompletaB() {
        PrescripcionDeclarada segunda = declararPredial(titular, 2022, 2022, SEGUNDA);

        assertThat(segunda.prescripcion().resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
        assertThat(enTransaccion(() -> prescripciones.obligacionesPrescritasDe(titular)))
                .containsExactlyInAnyOrder(PREDIAL_2021, PREDIAL_2022);
        assertThat(estados()).containsExactly("PRESCRITO", "PRESCRITO", "EMITIDO", "COACTIVA");
        assertThat(segunda.valoresPrescritos())
                .extracting(Valor::numero)
                .containsExactly("OP-2026-033702");
        assertThat(segunda.valoresCubiertosEnParte()).isEmpty();
    }

    // ------------------------------------------------------------------

    private static PrescripcionDeclarada declararPredial(
            long contribuyente, int desde, int hasta, LocalDate presentacion) {
        return enTransaccion(
                () ->
                        declarar.declarar(
                                contribuyente,
                                "PREDIAL",
                                new Ejercicio(desde),
                                new Ejercicio(hasta),
                                presentacion,
                                CausalDePrescripcion.DECLARACION_PRESENTADA,
                                List.of(),
                                null,
                                Observacion.de("Se resuelve la solicitud de prescripcion")));
    }

    /** El estado de A, B, C y D releido de la base, en ese orden. */
    private static List<String> estados() {
        return Arrays.stream(new Valor[] {a, b, c, d})
                .map(
                        valor ->
                                enTransaccion(() -> valores.porId(valor.id()))
                                        .orElseThrow()
                                        .estado()
                                        .name())
                .toList();
    }

    private static Valor emitir(String numero, EstadoDeValor estado, ValorDetalle... lineas) {
        LocalDate emision = LocalDate.of(2026, 3, 2);
        return enTransaccion(
                () ->
                        valores.insertar(
                                new Valor(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        numero,
                                        new Ejercicio(2026),
                                        titular,
                                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                        Dinero.de(String.valueOf(100 * lineas.length) + ".00"),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        emision,
                                        estado,
                                        emision,
                                        null,
                                        Observacion.de("Se emite para la prueba")),
                                List.of(lineas)));
    }

    private static ValorDetalle linea(String tributo, int ejercicio) {
        return ValorDetalle.nuevo(
                tributo,
                new Ejercicio(ejercicio),
                null,
                null,
                null,
                null,
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    private static <T> T enTransaccion(Supplier<T> accion) {
        return transaccion.execute(estado -> accion.get());
    }

    /**
     * Los plazos del art. 43 y el desfase del art. 44, con el conjunto REAL que se sembro: {@code
     * prescripcion.conjunto_id} tiene clave foranea contra el.
     */
    private static final class ParametrosDelConjuntoSembrado implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .texto("PLAZO", "PRESCRIPCION-DECLARACION_PRESENTADA", "4 ANIOS")
                    .texto("PLAZO", "PRESCRIPCION_INICIO-PREDIAL", "1 ANIOS")
                    .construir();
        }

        /** El ejercicio de la ultima resolucion: el conjunto sembrado es el suyo. */
        private Ejercicio ultimoResuelto = new Ejercicio(2026);

        /**
         * Los del conjunto sembrado, leidos por su identificador: desde #361 {@code
         * PlazosParametrizados} resuelve el conjunto una vez y lee los parametros por el id que le
         * salio, en vez de preguntar dos veces. Hasta entonces este doble lanzaba aqui.
         */
        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            if (identificador.valor() != conjunto) {
                throw new ConjuntoNoSellado(identificador);
            }
            return vigenteEn(ultimoResuelto);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            ultimoResuelto = ejercicio;
            return IdentificadorDeConjunto.de(conjunto);
        }
    }

    // ---------- siembra ----------

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('200337', 'Municipalidad de la cobertura',"
                                        + " 'DISTRITAL') RETURNING id")) {
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
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'COBERTURA, TITULAR', 'siembra')"
                        + " RETURNING id",
                municipalidad,
                codigo,
                dni);
    }

    private static long comoApp(String sql, Object... parametros) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
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
