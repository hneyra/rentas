package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.ValuacionRecibidaJdbc;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * #358 — la determinacion predial lee la valuacion sellada <b>en una transaccion</b>, aunque ella
 * misma no abra ninguna.
 *
 * <h2>Lo que distingue esta prueba de las que habia</h2>
 *
 * <p>Que <b>no envuelve</b> la llamada. {@link DeterminarPredial#determinar} no es transaccional a
 * proposito —la trampa del <i>rollback-only</i> de #54 y #72—, y desde #52 lee {@code
 * valuacion_predio} antes de {@code registrar}. Las pruebas de {@link DeterminarPredial} montaban
 * un doble en memoria, y las dos que usaban {@link ValuacionRecibidaJdbc} real ({@code
 * CandadoDeEmisionJdbcTest}, {@code IngestionDeCatastroJdbcTest}) la envolvian siempre en una
 * transaccion: por eso ninguna vio que, sin ella, no hay {@code SET LOCAL} y la politica de RLS de
 * {@code valuacion_predio} —forma estricta de {@code current_setting}— <b>falla</b> en vez de
 * devolver filas, y la peticion sale 500.
 *
 * <h2>Como se monta</h2>
 *
 * <p>Como lo monta Spring y nada mas: cada componente de esta base —el adaptador de la valuacion,
 * el del padron y el que registra— pasa por un proxy con {@link TransactionInterceptor} sobre
 * {@link TenantTransactionManager}, que <b>solo abre transaccion donde hay una anotacion</b>. El
 * tenant lo pone {@link TenantContext}, igual que el filtro. Dobles solo para lo que no es de esta
 * base: el padron de {@code catastro} (va por HTTP), el directorio (es de otro contexto), los
 * beneficios y el conjunto sellado.
 *
 * <p>La siembra que distingue: la sellada vale <b>180 000,00</b> y la declarada 100 000,00. Si la
 * lectura no llegara a la base, la determinacion no «saldria con la declarada»: saldria roja con el
 * SQLSTATE de la politica. Y si llegara sin el tenant, la cifra no seria la sellada.
 */
@DisplayName("#358 — la valuacion sellada se lee con su SET LOCAL, sin que nadie envuelva")
class DeterminarPredialContraLaValuacionDeLaBaseTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final long PREDIO = 11L;
    private static final long CONJUNTO_DE_LA_CORRIDA = 77L;
    private static final ResumenDeContribuyente CONTRIBUYENTE =
            new ResumenDeContribuyente(501L, "C-001", "SUC. RUFINA MEDINA MEDINA", "03593174");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static DeterminarPredial determinar;
    private static ValuacionRecibida valuaciones;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "271358", "Municipalidad de #358");
        sellarLaValuacion(PREDIO, "180000.00");

        // `kamayuk_app`, que es quien lee la proyeccion en produccion: `V5` no le da mas que
        // SELECT. Un pool sin reutilizacion: cada conexion es nueva, y sin `SET LOCAL` el
        // parametro no existe en ella.
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        JdbcClient jdbc = JdbcClient.create(pool);

        LectorDeParametros lector = lectorDe(conjuntoConRedondeo());
        DeterminacionRepositoryJdbc determinaciones = new DeterminacionRepositoryJdbc(jdbc);
        valuaciones = comoLoMontaSpring(new ValuacionRecibidaJdbc(jdbc), gestor);
        determinar =
                new DeterminarPredial(
                        comoLoMontaSpring(new PadronPredialDelEjercicio(determinaciones), gestor),
                        (contribuyenteId, fecha) ->
                                contribuyenteId == CONTRIBUYENTE.id()
                                        ? List.of(
                                                new PredioDelContribuyente(
                                                        PREDIO,
                                                        "10001",
                                                        "URBANO",
                                                        "AV. GRAU 100",
                                                        Porcentaje.total()))
                                        : List.of(),
                        (predioId, fecha) -> Optional.empty(),
                        new UnSoloContribuyente(),
                        new CuadroPredialParametrizado(lector),
                        valuaciones,
                        (contribuyenteId, fecha) -> List.of(),
                        comoLoMontaSpring(
                                new RegistrarDeterminacionPredial(
                                        determinaciones,
                                        registro -> {
                                            throw new AssertionError(
                                                    "Simular no audita: " + registro);
                                        }),
                                gestor),
                        RELOJ);
    }

    @AfterAll
    static void cerrar() {
        TenantContext.limpiar();
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("simular con una valuacion sellada en la base: manda la sellada, y no es un 500")
    void simularLeeLaSelladaDeLaBase() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        DeterminacionPredialCalculada calculada;
        try {
            // Nada alrededor: ni `TransactionTemplate` ni un proxy sobre `DeterminarPredial`. Es
            // exactamente como lo llama `PredialController`.
            calculada =
                    determinar.determinar(
                            new DeterminarPredial.Peticion(
                                    EJERCICIO,
                                    CONTRIBUYENTE.codigo(),
                                    List.of(
                                            new DeterminarPredial.PredioDeclarado(
                                                    PREDIO, Dinero.de("100000.00"), null)),
                                    ModalidadDelPredial.TRIMESTRAL,
                                    true),
                            Observacion.de(
                                    "Simulacion del predial 2026 a pedido del contribuyente"));
        } finally {
            TenantContext.limpiar();
        }

        PredioEnLaBase enLaBase = calculada.predios().get(0);
        assertThat(enLaBase.autovaluo())
                .as("la cifra que `catastro` sello y que esta EN LA BASE, no la declarada")
                .isEqualTo(Dinero.de("180000.00"));
        assertThat(enLaBase.autovaluoSellado()).isTrue();
        assertThat(enLaBase.valuacionConjuntoId()).isEqualTo(CONJUNTO_DE_LA_CORRIDA);
        assertThat(enLaBase.autovaluoDeclarado()).isEqualTo(Dinero.de("100000.00"));
        assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("180000.00"));
        // 82 500 x 0,2 % = 165,00 ; 97 500 x 0,6 % = 585,00 ; total 750,00. Con la declarada
        // saldria 82 500 x 0,2 % + 17 500 x 0,6 % = 270,00.
        assertThat(calculada.impuestoInsoluto()).isEqualTo(Dinero.de("750.00"));
        assertThat(calculada.cabecera().esNueva())
                .as("es una simulacion: no hay fila que le de identificador")
                .isTrue();
    }

    /**
     * Los otros cuatro metodos del puerto, llamados igual: sin nada alrededor.
     *
     * <p>Hoy sus llamadores son transaccionales ({@code CandadoDeEmision} lee tres de ellos dentro
     * de la suya), y por eso ninguna prueba veria que les falta la anotacion. Pero la anotacion
     * esta ahi para el llamador que <b>todavia no existe</b> —el que haga con {@code cierreDe} o
     * {@code delPredio} lo que #52 hizo con {@code deLosPredios}—, y una proteccion que nadie
     * ejercita se «limpia» sin que nada se ponga rojo.
     */
    @Test
    @DisplayName("y los otros cuatro metodos del puerto tambien traen su transaccion")
    void losOtrosCuatroTambienTraenLaSuya() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            assertThat(valuaciones.cierreDe(EJERCICIO))
                    .as("ninguna corrida cerrada en esta siembra: vacio, no un fallo de RLS")
                    .isEmpty();
            assertThat(valuaciones.delPredio(EJERCICIO, PREDIO))
                    .hasValueSatisfying(
                            sellada ->
                                    assertThat(sellada.autovaluo())
                                            .contains(Dinero.de("180000.00")));
            assertThat(valuaciones.valuacionesRecibidasDe(EJERCICIO)).isEqualTo(1L);
            assertThat(valuaciones.huellaDeLoRecibido(EJERCICIO))
                    .as("la huella de la unica huella recibida")
                    .isEqualTo(sha256(sha256("valuacion-" + PREDIO)));
        } finally {
            TenantContext.limpiar();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Lo que Spring hace con un bean anotado: un proxy que abre la transaccion SOLO en los metodos
     * que llevan {@code @Transactional}. Sin anotacion, la llamada pasa derecho —que es justamente
     * lo que #358 mide—.
     */
    @SuppressWarnings("unchecked")
    private static <T> T comoLoMontaSpring(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /**
     * La valuacion sellada del predio, escrita como la escribe el ingestor: con su rol, con su
     * evento anotado (la clave foranea de `V9`) y con las cuatro cifras.
     */
    private static void sellarLaValuacion(long predioId, String valorDelPredio)
            throws SQLException {
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);
            String evento = UUID.randomUUID().toString();
            try (PreparedStatement sentencia =
                    ingestor.prepareStatement(
                            "INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id,"
                                    + " secuencia, tipo, predio_id, aplicado_en, huella)"
                                    + " VALUES (?, CAST(? AS uuid), 1, 'VALUACION_SELLADA', ?,"
                                    + " now(), ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, evento);
                sentencia.setLong(3, predioId);
                sentencia.setString(4, sha256(evento));
                sentencia.executeUpdate();
            }
            try (PreparedStatement sentencia =
                    ingestor.prepareStatement(
                            "INSERT INTO valuacion_predio (municipalidad_id, ejercicio,"
                                    + " predio_id, fecha_de_corte, valor_terreno,"
                                    + " valor_construccion, valor_obras, valor_del_predio,"
                                    + " conjunto_id, reglas_version, reglas_aplicadas, huella,"
                                    + " evento_id, secuencia, recibida_en)"
                                    + " VALUES (?, 2026, ?, DATE '2025-12-31', CAST(? AS numeric),"
                                    + " 0, 0, CAST(? AS numeric), ?, 'v1', 'RT-001', ?,"
                                    + " CAST(? AS uuid), 1, now())")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setString(3, valorDelPredio);
                sentencia.setString(4, valorDelPredio);
                sentencia.setLong(5, CONJUNTO_DE_LA_CORRIDA);
                sentencia.setString(6, sha256("valuacion-" + predioId));
                sentencia.setString(7, evento);
                sentencia.executeUpdate();
            }
            ingestor.commit();
        }
    }

    /**
     * Un conjunto que <b>trae</b> los puntos {@code REDONDEO:*}: es lo que destapa #358. Sin ellos
     * {@code vigente.redondeo()} sale antes con {@code SinPuntosObservados} y la lectura de la
     * valuacion no llega a correr.
     */
    private static ParametrosSellados conjuntoConRedondeo() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .texto("REDONDEO", "CUOTA", "HALF_UP")
                .construir();
    }

    private static LectorDeParametros lectorDe(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(CONJUNTO_DE_LA_CORRIDA);
            }
        };
    }

    /** El directorio es de `contribuyentes`: aqui solo hace falta que conteste por el codigo. */
    private static final class UnSoloContribuyente implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return CONTRIBUYENTE.codigo().equals(codigo)
                    ? Optional.of(CONTRIBUYENTE)
                    : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return ids.contains(CONTRIBUYENTE.id())
                    ? Map.of(CONTRIBUYENTE.id(), CONTRIBUYENTE)
                    : Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException(imposible);
        }
    }
}
