package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.rentas.dominio.predial.Tramo;
import kamayuk.rentas.rentas.infraestructura.DeterminacionRepositoryJdbc;
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
 * {@code RegistrarDeterminacionPredial} contra PostgreSQL real (#30).
 *
 * <p>Lo que este archivo verifica, con la base de por medio y no con un doble:
 *
 * <ul>
 *   <li><b>AC2/AC3</b>: recalcular el mismo contribuyente y ejercicio con otro conjunto sellado
 *       crea <b>otra fila</b>, nunca modifica la primera —{@link DeterminacionRepository} ni
 *       siquiera tiene un metodo de actualizar, y una prueba de reflexion lo deja explicito—.
 *   <li><b>AC4</b>: {@code determinacion_predial_sin_predio_ck} (V20) rechaza en la base cualquier
 *       intento de guardar una fila {@code PREDIAL} con {@code predio_id} distinto de nulo, aunque
 *       se escriba por SQL directo, no solo a traves del dominio.
 * </ul>
 *
 * <p>AC1 —que agregar antes de aplicar los tramos da un impuesto distinto de calcular predio por
 * predio— ya esta demostrado con las clases reales y sin base de datos en {@code
 * RT011BaseImponibleDelContribuyenteTest}, siguiendo la misma division que el resto del modulo: lo
 * puro se prueba sin Docker, lo que toca la base se prueba aqui.
 */
@DisplayName("#30 — Registrar la determinacion predial")
class RegistrarDeterminacionPredialTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /** Cuadro ficticio: 0.2 % hasta 1000, 0.6 % hasta 3000, 1.0 % en adelante. */
    private static final List<Tramo> CUADRO_FICTICIO =
            List.of(
                    Tramo.hasta(Dinero.de(1000), Alicuota.de("0.2")),
                    Tramo.hasta(Dinero.de(3000), Alicuota.de("0.6")),
                    Tramo.sinTope(Alicuota.de("1.0")));

    private static final Dinero MINIMO_FICTICIO = Dinero.de("1.00");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static DeterminacionRepositoryJdbc repositorio;
    private static RegistrarDeterminacionPredial registrar;
    private static AdministrarParametros administrarParametros;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("270102", "Municipalidad de la determinacion predial");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new DeterminacionRepositoryJdbc(jdbc);

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
                        new RegistrarDeterminacionPredial(
                                repositorio, parametros, new AuditoriaJdbc(jdbc, RELOJ), RELOJ));
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
    @DisplayName("AC2/AC3 — recalcular crea otra fila, nunca modifica la anterior")
    class Recalculo {

        @Test
        @DisplayName(
                "dos calculos del mismo contribuyente con dos conjuntos sellados son dos filas")
        void recalcularConOtroConjuntoCreaOtraFila() throws SQLException {
            long titular = crearContribuyente("DET-0001", "80300001");
            long predioA = crearPredio("000000000000000101");
            long predioB = crearPredio("000000000000000102");

            long conjuntoV1 = sellarConjunto(EJERCICIO, "Version 1 para la primera determinacion");

            Determinacion primera =
                    registrar.registrar(
                            EJERCICIO,
                            titular,
                            prediosDeclarados(predioA, predioB),
                            CUADRO_FICTICIO,
                            MINIMO_FICTICIO,
                            Observacion.de("Primera determinacion del ejercicio 2026"));

            assertThat(primera.id()).isNotNull();
            assertThat(primera.conjuntoId()).isEqualTo(conjuntoV1);
            // 1000 + 1000 = 2000 -> 1000*0.2% + 1000*0.6% = 2.00 + 6.00 = 8.00
            assertThat(primera.baseImponible()).isEqualTo(Dinero.de(2000));
            assertThat(primera.montoDeterminado()).isEqualTo(Dinero.de("8.00"));

            long conjuntoV2 =
                    sellarConjunto(EJERCICIO, "Version 2, corrige el conjunto de la primera");
            assertThat(conjuntoV2).isNotEqualTo(conjuntoV1);

            Determinacion segunda =
                    registrar.registrar(
                            EJERCICIO,
                            titular,
                            prediosDeclarados(predioA, predioB),
                            CUADRO_FICTICIO,
                            MINIMO_FICTICIO,
                            Observacion.de("Recalculo con el conjunto sellado corregido"));

            assertThat(segunda.id())
                    .as("recalcular no reutiliza la fila anterior: crea otra")
                    .isNotEqualTo(primera.id());
            assertThat(segunda.conjuntoId()).isEqualTo(conjuntoV2);

            // La primera fila sigue exactamente como se guardo: nadie la toco con un UPDATE.
            Determinacion primeraTrasElRecalculo =
                    transaccion.execute(estado -> repositorio.findById(primera.id()).orElseThrow());
            assertThat(primeraTrasElRecalculo).isEqualTo(primera);

            Long totalDeFilas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM determinacion WHERE"
                                                            + " contribuyente_id = :titular")
                                            .param("titular", titular)
                                            .query(Long.class)
                                            .single());
            assertThat(totalDeFilas).isEqualTo(2L);

            List<DetalleDeterminacionPredio> detalleDeLaPrimera =
                    transaccion.execute(estado -> repositorio.detalleDe(primera.id()));
            assertThat(detalleDeLaPrimera).hasSize(2);
        }

        @Test
        @DisplayName(
                "DeterminacionRepository no declara ningun metodo de actualizar: es estructural,"
                        + " no una convencion")
        void elRepositorioNoTieneActualizar() {
            List<String> nombresDeMetodo =
                    Arrays.stream(DeterminacionRepository.class.getDeclaredMethods())
                            .map(Method::getName)
                            .toList();

            assertThat(nombresDeMetodo)
                    // "insertar" aparece dos veces: la sobrecarga con detalle (predial) y la de
                    // una sola partida sin detalle (vehicular, alcabala, espectaculos — #32).
                    // "ultimasPredialesDe" y "ultimaPredialDe" son lecturas de #395: la corrida
                    // masiva y el recalculo individual necesitan saber que autovaluos se
                    // declararon ya en el ejercicio, y ninguna de las dos abre camino de
                    // escritura. La lista es exhaustiva a proposito: un metodo nuevo obliga a
                    // volver aqui y a decir en el diff que hace.
                    .containsExactlyInAnyOrder(
                            "findById",
                            "detalleDe",
                            "ultimasPredialesDe",
                            "ultimaPredialDe",
                            "insertar",
                            "insertar")
                    .as("sin actualizar ni eliminar: recalcular solo puede insertar otra fila")
                    .noneMatch(
                            nombre ->
                                    nombre.toLowerCase(Locale.ROOT).contains("actualizar")
                                            || nombre.toLowerCase(Locale.ROOT).contains("update"));
        }

        @Test
        @DisplayName("un contribuyente sin ningun predio declarado no tiene determinacion")
        void sinPrediosNoHayDeterminacion() {
            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            EJERCICIO,
                                            999_999L,
                                            List.of(),
                                            CUADRO_FICTICIO,
                                            MINIMO_FICTICIO,
                                            Observacion.de("No deberia llegar a calcular nada")))
                    .isInstanceOf(RegistrarDeterminacionPredial.SinPrediosDeclarados.class);
        }
    }

    @Nested
    @DisplayName("E-7 §3 — el redondeo sale del conjunto sellado, no del codigo (D-03c)")
    class ElRedondeoSaleDelConjunto {

        @Test
        @DisplayName("cambiar la escala de la fila cambia el importe determinado")
        void cambiarLaFilaCambiaElImporte() throws SQLException {
            long titular = crearContribuyente("DET-0003", "80300003");
            long predio = crearPredio("000000000000000104");
            // 2026 y no un ejercicio cualquiera: `determinacion` esta particionada por ejercicio
            // y solo existen las particiones de 2026 y 2027 (V2).
            Ejercicio ejercicio = EJERCICIO;

            sellarConRedondeo(ejercicio, 4, "HALF_UP", "Redondeo ficticio a cuatro decimales");
            Determinacion conCuatro =
                    registrar.registrar(
                            ejercicio,
                            titular,
                            List.of(aporte(predio, "1234.5678")),
                            List.of(Tramo.sinTope(Alicuota.de("1.0"))),
                            MINIMO_FICTICIO,
                            Observacion.de("Determinacion con redondeo a cuatro decimales"));

            sellarConRedondeo(ejercicio, 0, "DOWN", "Redondeo ficticio a cero decimales");
            Determinacion conCero =
                    registrar.registrar(
                            ejercicio,
                            titular,
                            List.of(aporte(predio, "1234.5678")),
                            List.of(Tramo.sinTope(Alicuota.de("1.0"))),
                            MINIMO_FICTICIO,
                            Observacion.de("Determinacion con redondeo a cero decimales"));

            assertThat(conCuatro.montoDeterminado())
                    .as("ninguna escala vive en el servicio: la de la fila es la que se aplica")
                    .isNotEqualTo(conCero.montoDeterminado());
            assertThat(conCero.montoDeterminado()).isEqualTo(Dinero.de("12.00"));
        }

        @Test
        @DisplayName("un conjunto sellado sin ninguna fila REDONDEO no determina: falla")
        void sinFilaDeRedondeoNoDetermina() throws SQLException {
            long titular = crearContribuyente("DET-0004", "80300004");
            long predio = crearPredio("000000000000000105");
            Ejercicio ejercicio = new Ejercicio(2027);
            sellarConjuntoSinRedondeo(ejercicio, "Conjunto sin ningun punto observado");

            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            ejercicio,
                                            titular,
                                            List.of(aporte(predio, "1000.00")),
                                            CUADRO_FICTICIO,
                                            MINIMO_FICTICIO,
                                            Observacion.de("Determinacion sin redondeo observado")))
                    .as(
                            "sin puntos observados el importe saldria sin redondear y nadie lo"
                                    + " distinguiria del correcto")
                    .isInstanceOf(PoliticasDeRedondeoSelladas.SinPuntosObservados.class)
                    .hasMessageContaining("D-03c");
        }
    }

    @Nested
    @DisplayName("AC4 — el predial nunca queda ligado a un solo predio, ni por SQL directo")
    class ElPredialNuncaPorUnSoloPredio {

        @Test
        @DisplayName(
                "determinacion_predial_sin_predio_ck rechaza un PREDIAL con predio_id, aunque se"
                        + " escriba sin pasar por el dominio")
        void elCheckRechazaUnPredialConPredioId() throws SQLException {
            long titular = crearContribuyente("DET-0002", "80300002");
            long predio = crearPredio("000000000000000103");
            long conjunto = sellarConjunto(EJERCICIO, "Conjunto para probar el CHECK de V20");

            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidad);
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement sentencia =
                                            app.prepareStatement(
                                                    "INSERT INTO determinacion (municipalidad_id,"
                                                            + " ejercicio, tributo,"
                                                            + " contribuyente_id, predio_id,"
                                                            + " conjunto_id, base_imponible,"
                                                            + " monto_determinado,"
                                                            + " reglas_aplicadas, usuario_calculo)"
                                                            + " VALUES (?, 2026, 'PREDIAL', ?, ?,"
                                                            + " ?, 100.00, 10.00,"
                                                            + " ARRAY['RT-000']::varchar(200)[],"
                                                            + " 'prueba')")) {
                                        sentencia.setLong(1, municipalidad);
                                        sentencia.setLong(2, titular);
                                        sentencia.setLong(3, predio);
                                        sentencia.setLong(4, conjunto);
                                        sentencia.execute();
                                    }
                                })
                        .as("V20: el predial se determina por contribuyente, nunca por un predio")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("determinacion_predial_sin_predio_ck");
            }
        }
    }

    // ------------------------------------------------------------------

    private static List<DetalleDeterminacionPredio> prediosDeclarados(long predioA, long predioB) {
        return List.of(
                DetalleDeterminacionPredio.nuevo(
                        predioA, Dinero.de(1000), Porcentaje.total(), Dinero.de(1000)),
                DetalleDeterminacionPredio.nuevo(
                        predioB, Dinero.de(1000), Porcentaje.total(), Dinero.de(1000)));
    }

    private static DetalleDeterminacionPredio aporte(long predio, String base) {
        return DetalleDeterminacionPredio.nuevo(
                predio, Dinero.de(base), Porcentaje.total(), Dinero.de(base));
    }

    private static long sellarConjunto(Ejercicio ejercicio, String motivo) throws SQLException {
        return sellarConRedondeo(ejercicio, 2, "HALF_UP", motivo);
    }

    /** Sella un conjunto cuyo unico parametro es el punto de redondeo, con la escala y el modo. */
    private static long sellarConRedondeo(
            Ejercicio ejercicio, int escala, String modo, String motivo) throws SQLException {
        return sellarCon(ejercicio, motivo, parametroDeRedondeo(escala, modo, motivo));
    }

    /** Sella un conjunto valido —no se puede sellar uno vacio— y sin ningun punto de redondeo. */
    private static long sellarConjuntoSinRedondeo(Ejercicio ejercicio, String motivo)
            throws SQLException {
        return sellarCon(ejercicio, motivo, parametroAjenoAlRedondeo(motivo));
    }

    private static long sellarCon(Ejercicio ejercicio, String motivo, long parametro)
            throws SQLException {
        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(ejercicio, Observacion.de(motivo));
        administrarParametros.agregarParametro(conjunto.id(), parametro, Observacion.de(motivo));
        ConjuntoDeParametros sellado =
                administrarParametros.sellar(conjunto.id(), Observacion.de(motivo));
        return sellado.id();
    }

    /**
     * La fila {@code REDONDEO:IMPUESTO_POR_TRAMO} que el servicio <b>lee</b> del conjunto sellado
     * (E-7 §Entregable 3, #203): escala en {@code valor_numerico}, modo en {@code valor_texto}, las
     * dos mitades en la misma fila.
     *
     * <p>La escala y el modo de aqui son <b>ficticios</b>: la campana de observacion del SRTM del
     * MEF no ha empezado, y su documento fuente lo dice. Lo que esta prueba verifica no es que sean
     * los correctos sino que <b>salgan de la base</b>: cambiarlos cambia el importe, y ninguno vive
     * en el codigo del servicio.
     */
    private static long parametroDeRedondeo(int escala, String modo, String motivo)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, valor_texto, vigencia_desde,"
                                        + " documento_fuente, usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, ?, ?, DATE '2026-01-01', ?,"
                                        + " 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, PoliticasDeRedondeoSelladas.TIPO);
            sentencia.setString(2, PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name());
            sentencia.setBigDecimal(3, java.math.BigDecimal.valueOf(escala));
            sentencia.setString(4, modo);
            sentencia.setString(
                    5,
                    motivo
                            + "; escala y modo ficticios, no observados del SRTM del MEF (D-03c"
                            + " sigue abierta)");
            return devolverId(carga, sentencia);
        }
    }

    /**
     * Un parametro que no es de redondeo: el conjunto es valido y aun asi no tiene ningun punto.
     */
    private static long parametroAjenoAlRedondeo(String motivo) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL,"
                                        + " 'FICTICIO', ?, 1.000000, DATE '2026-01-01', ?,"
                                        + " 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, "SIN_REDONDEO_" + System.nanoTime());
            sentencia.setString(
                    2, motivo + "; valor ficticio de prueba, no representa ninguna norma");
            return devolverId(carga, sentencia);
        }
    }

    private static long devolverId(Connection carga, PreparedStatement sentencia)
            throws SQLException {
        try (ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            long id = resultado.getLong(1);
            carga.commit();
            return id;
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

    private static long crearPredio(String codigoRefCatastral) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 123')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigoRefCatastral);
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
