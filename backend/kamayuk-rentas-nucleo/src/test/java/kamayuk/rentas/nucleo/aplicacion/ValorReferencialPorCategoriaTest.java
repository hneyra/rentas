package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.infraestructura.ValorReferencialRepositoryJdbc;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
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

/**
 * #360 — El valor referencial se acota por la categoria del vehiculo, contra PostgreSQL.
 *
 * <p>El anexo del MEF publica un mismo {@code (marca, modelo, ano)} en mas de una categoria: en la
 * TVR 2026 sellada, 466 pares cruzan categorias y 163 de ellos traen cifras distintas. Hasta #360
 * la consulta filtraba por conjunto, marca, modelo y ano —nunca por categoria— y cualquier par
 * repetido lanzaba una excepcion que nadie traducia: 500 con incidencia, <b>aunque el vehiculo
 * tuviera su categoria registrada</b>.
 *
 * <p><b>La siembra que distingue</b>: el mismo modelo del mismo ano en {@code A1} y en {@code A2}
 * <b>con cifras distintas</b>. Con cifras iguales, «quedarse con la primera» tambien pasaria, y
 * pedir las dos categorias descarta a la vez «la primera» y «la ultima». Junto a ella, un par con
 * la misma cifra en dos categorias —la otra mitad de los 466— y un modelo publicado en una sola.
 *
 * <p><b>Aqui no hay ninguna cifra tributaria.</b> Los importes son de relleno: lo que se prueba es
 * que fila se elige, no cuanto vale.
 */
@DisplayName("#360 — El valor referencial se elige por la categoria del vehiculo")
class ValorReferencialPorCategoriaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /** Un ejercicio cuyo conjunto sellado no trae ni una fila del cuadro vehicular. */
    private static final Ejercicio SIN_CUADRO = new Ejercicio(2027);

    private static final Ejercicio FABRICACION = new Ejercicio(2024);

    /** Publicado en A1 y en A2 con cifras distintas: la siembra que distingue. */
    private static final String MARCA = "CHEVROLET";

    private static final String MODELO_QUE_CRUZA = "SPARK";
    private static final BigDecimal EN_A1 = new BigDecimal("1000.00");
    private static final BigDecimal EN_A2 = new BigDecimal("1300.00");

    /** Publicado en A1 y en A2 con la MISMA cifra: la base no depende de la categoria. */
    private static final String MODELO_DE_UNA_CIFRA = "BEAT";

    private static final BigDecimal LA_MISMA = new BigDecimal("900.00");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static ValoresReferenciales valores;
    private static LectorDeParametros lector;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        sellarConjunto(EJERCICIO, publicarEdicion());
        sellarConjunto(SIN_CUADRO, publicarEdicionVacia());

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        lector = envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)), pool);
        valores =
                envolver(
                        new ValoresReferenciales(new ValorReferencialRepositoryJdbc(jdbc), lector),
                        pool);
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
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("con la categoria A2 registrada, la cifra es la de A2")
    void conLaCategoriaA2EsLaDeA2() {
        assertThat(valorDe(vehiculo(MODELO_QUE_CRUZA, "A2")))
                .as(
                        "hasta #360 la consulta ignoraba la categoria y lanzaba: dos candidatos,"
                                + " 500, aunque el padron dijera cual era")
                .isEqualByComparingTo(EN_A2);
    }

    @Test
    @DisplayName("y con A1, la de A1: no es «la primera» ni «la ultima»")
    void conLaCategoriaA1EsLaDeA1() {
        assertThat(valorDe(vehiculo(MODELO_QUE_CRUZA, "A1"))).isEqualByComparingTo(EN_A1);
    }

    @Test
    @DisplayName("sin categoria y con cifras distintas, se para y nombra las dos categorias")
    void sinCategoriaYConCifrasDistintasSeNombranLasCategorias() {
        assertThatThrownBy(
                        () ->
                                valores.de(
                                        vehiculo(MODELO_QUE_CRUZA, null),
                                        EJERCICIO,
                                        lector.conjuntoVigenteEn(EJERCICIO)))
                .as(
                        "elegir una sin saber la categoria daria otra base imponible sin ningun"
                                + " error; y la ventanilla tiene que saber que completar en el"
                                + " padron")
                .isInstanceOfSatisfying(
                        ValorReferencialRepository.ValorReferencialAmbiguo.class,
                        ambiguo -> assertThat(ambiguo.categorias()).containsExactly("A1", "A2"))
                .hasMessageContaining("en las categorias A1, A2 del anexo");
    }

    @Test
    @DisplayName("sin categoria pero con la misma cifra en todas, la base no depende de ella")
    void sinCategoriaPeroConLaMismaCifraNoSeRechaza() {
        assertThat(valorDe(vehiculo(MODELO_DE_UNA_CIFRA, null)))
                .as(
                        "rechazar seria mas estricto de lo necesario: cualquiera de las filas da"
                                + " la misma base, igual que la fila unica que ya se aceptaba")
                .isEqualByComparingTo(LA_MISMA);
    }

    @Test
    @DisplayName("una categoria del anexo que no publica ese modelo es «sin valor», no otra cifra")
    void unaCategoriaDelAnexoQueNoPublicaElModeloNoTomaLaDeOtra() {
        assertThat(
                        valores.de(
                                vehiculo(MODELO_QUE_CRUZA, "A3"),
                                EJERCICIO,
                                lector.conjuntoVigenteEn(EJERCICIO)))
                .as(
                        "A3 es del anexo —otro modelo la usa— pero el SPARK no esta en ella:"
                                + " tomar la cifra de A1 o A2 seria valorizarlo con la de otra"
                                + " categoria")
                .isEmpty();
    }

    @Test
    @DisplayName("una categoria que el anexo no conoce es su propio rechazo, no «sin valor»")
    void unaCategoriaQueElAnexoNoConoceSeNombra() {
        assertThatThrownBy(
                        () ->
                                valores.de(
                                        vehiculo(MODELO_QUE_CRUZA, "M1"),
                                        EJERCICIO,
                                        lector.conjuntoVigenteEn(EJERCICIO)))
                .as(
                        "M1 es la clase del reglamento de vehiculos, no una categoria del anexo:"
                                + " leerlo como «el cuadro no trae el vehiculo» mandaria a buscar"
                                + " en la tabla del MEF lo que falta corregir en el padron")
                .isInstanceOf(ValoresReferenciales.CategoriaFueraDelCuadro.class)
                .hasMessageContaining("'M1'")
                .hasMessageContaining("(A1, A2, A3)");
    }

    @Test
    @DisplayName(
            "un conjunto sellado sin cuadro vehicular es «sin valor», no «corrija la categoria»")
    void unConjuntoSinCuadroNoCulpaALaCategoria() {
        assertThat(
                        valores.de(
                                vehiculo(MODELO_QUE_CRUZA, "A2"),
                                SIN_CUADRO,
                                lector.conjuntoVigenteEn(SIN_CUADRO)))
                .as(
                        "sin ni una fila en el cuadro no hay vocabulario contra el que comparar: lo"
                                + " que falta es la tabla, y culpar a la categoria —con la lista"
                                + " vacia «()»— mandaria a corregir en el padron un dato que esta"
                                + " bien")
                .isEmpty();
    }

    // ---------------------------------------------------------------- utilidades

    private static BigDecimal valorDe(Vehiculo vehiculo) {
        return valores.de(vehiculo, EJERCICIO, lector.conjuntoVigenteEn(EJERCICIO))
                .map(ValorReferencial::valor)
                .orElseThrow(() -> new AssertionError("El cuadro no devolvio ningun valor"))
                .valor();
    }

    private static Vehiculo vehiculo(String modelo, @Nullable String categoria) {
        return Vehiculo.nuevo(
                Placa.de("C3G-360"), 1, MARCA, modelo, categoria, FABRICACION, new Ejercicio(2025));
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220360', 'Municipalidad de las categorias',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /** Un conjunto sellado que compone la edicion del cuadro: abierto, compuesto y sellado. */
    private static void sellarConjunto(Ejercicio ejercicio, long edicion) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio,"
                                    + " version) VALUES (?, ?, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, ejercicio.valor());
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    conjunto = fila.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                    + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.setLong(3, edicion);
                sentencia.executeUpdate();
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'prueba'"
                                    + " WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /**
     * La edicion nacional del cuadro. Las filas van en un orden que no favorece a ninguna
     * categoria: A2 antes que A1, para que «la primera que devuelva la base» no coincida con la que
     * se pide por casualidad.
     */
    private static long publicarEdicion() throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES (NULL, 'TABLA_DE_LA_PRUEBA', 'tvr',"
                                    + " 'tabla de la prueba, sin valor normativo', DATE '2026-01-01',"
                                    + " 'tabla de la prueba, sin valor normativo', 'quien transcribe',"
                                    + " 'quien verifica') RETURNING id")) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    edicion = fila.getLong(1);
                }
            }
            fila(carga, edicion, "A2", MODELO_QUE_CRUZA, EN_A2);
            fila(carga, edicion, "A1", MODELO_QUE_CRUZA, EN_A1);
            fila(carga, edicion, "A2", MODELO_DE_UNA_CIFRA, LA_MISMA);
            fila(carga, edicion, "A1", MODELO_DE_UNA_CIFRA, LA_MISMA);
            // A3 existe en el anexo, pero no para el SPARK.
            fila(carga, edicion, "A3", "CAPTIVA", new BigDecimal("2500.00"));
            carga.commit();
            return edicion;
        }
    }

    /**
     * Una edicion sin ni una fila del cuadro: lo que compone el conjunto de un ejercicio sellado
     * sin tabla vehicular (#360, ronda 1).
     */
    private static long publicarEdicionVacia() throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo,"
                                        + " clave, valor_texto, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL,"
                                        + " 'TABLA_DE_LA_PRUEBA', 'sin-cuadro', 'tabla de la prueba,"
                                        + " sin valor normativo', DATE '2027-01-01', 'tabla de la"
                                        + " prueba, sin valor normativo', 'quien transcribe',"
                                        + " 'quien verifica') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long edicion = fila.getLong(1);
                carga.commit();
                return edicion;
            }
        }
    }

    private static void fila(
            Connection carga, long edicion, String categoria, String modelo, BigDecimal valor)
            throws SQLException {
        try (PreparedStatement sentencia =
                carga.prepareStatement(
                        "INSERT INTO valor_referencial_de_prueba (publicacion_id, ejercicio,"
                                + " categoria, marca, modelo, anio_fabricacion, valor,"
                                + " documento_fuente)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?,"
                                + "         'tabla de la prueba, sin valor normativo')")) {
            sentencia.setLong(1, edicion);
            sentencia.setInt(2, EJERCICIO.valor());
            sentencia.setString(3, categoria);
            sentencia.setString(4, MARCA);
            sentencia.setString(5, modelo);
            sentencia.setInt(6, FABRICACION.valor());
            sentencia.setBigDecimal(7, valor);
            sentencia.executeUpdate();
        }
    }
}
