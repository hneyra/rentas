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
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.infraestructura.ValorReferencialRepositoryJdbc;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * Los valores referenciales salen del <b>conjunto sellado</b>, no del ejercicio.
 *
 * <p>La prueba que da valor a este archivo es {@link #dosVersionesSelladasDelMismoEjercicio()}: se
 * sellan dos conjuntos del mismo ejercicio con valores distintos —un valor corregido a mitad de
 * año, que es exactamente lo que pasa en la practica— y se comprueba que cada uno devuelve el suyo.
 *
 * <p>Con la clave puesta en el ejercicio, como estaba la tabla antes de V17, los dos devolvian lo
 * mismo: la version vigente hoy. Recalcular una determinacion emitida con la v1 daria la cifra de
 * la v2 sin ningun error de por medio, y el contribuyente ya tendria su recibo (ARQ-09 §3).
 *
 * <p><b>Aqui no hay ninguna cifra tributaria.</b> Los importes son de relleno y no representan
 * ningun valor referencial real: lo que se prueba es de donde se lee, no cuanto vale (D-02).
 */
@DisplayName("ARQ-09 §3 — El valor referencial sale del conjunto, no del ejercicio")
class ValoresReferencialesTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Ejercicio FABRICACION = new Ejercicio(2020);
    private static final BigDecimal DE_LA_V1 = new BigDecimal("1000.00");
    private static final BigDecimal DE_LA_V2 = new BigDecimal("1200.00");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long conjuntoV1;
    private static long conjuntoV2;
    private static ValoresReferenciales valores;
    private static LectorDeParametros lector;
    private static ValorReferencialRepositoryJdbc repositorio;
    private static org.springframework.transaction.support.TransactionTemplate transacciones;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        conjuntoV1 = sellarConjunto(1, DE_LA_V1);
        conjuntoV2 = sellarConjunto(2, DE_LA_V2);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        repositorio = new ValorReferencialRepositoryJdbc(jdbc);
        transacciones =
                new org.springframework.transaction.support.TransactionTemplate(
                        new TenantTransactionManager(pool));
        lector = envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)), pool);
        valores = envolver(new ValoresReferenciales(repositorio, lector), pool);
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
    @DisplayName("el ejercicio resuelve al conjunto sellado de mayor version")
    void elEjercicioResuelveAlSelladoVigente() {
        assertThat(lector.conjuntoVigenteEn(EJERCICIO))
                .isEqualTo(IdentificadorDeConjunto.de(conjuntoV2));
    }

    @Test
    @DisplayName("dos versiones selladas del mismo ejercicio devuelven valores distintos")
    void dosVersionesSelladasDelMismoEjercicio() {
        ValorReferencial v1 = exigir(conjuntoV1);
        ValorReferencial v2 = exigir(conjuntoV2);

        assertThat(v1.valor().valor())
                .as(
                        "es el valor con el que se emitio, y es el que un recalculo tiene que"
                                + " recuperar")
                .isEqualByComparingTo(DE_LA_V1);
        assertThat(v2.valor().valor())
                .as("y el corregido es otro. Con la clave en el ejercicio, los dos darian este")
                .isEqualByComparingTo(DE_LA_V2);
    }

    @Test
    @DisplayName("la consulta por ejercicio usa el vigente, que es lo correcto para una emision")
    void laConsultaPorEjercicioUsaElVigente() {
        assertThat(valores.de(vehiculo(), EJERCICIO))
                .get()
                .extracting(valor -> valor.valor().valor())
                .isEqualTo(DE_LA_V2);
    }

    @Test
    @DisplayName("un ejercicio sin ningun conjunto sellado no devuelve nada: se niega")
    void sinConjuntoSelladoSeNiega() {
        assertThatThrownBy(() -> valores.catalogoDe(new Ejercicio(2027)))
                .as(
                        "no hay valor por omision: calcular con un conjunto abierto daria una cifra"
                                + " que manana puede ser otra")
                .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class);
    }

    @Test
    @DisplayName("el catalogo de marcas y modelos es el del conjunto, sin repetidos")
    void elCatalogoSaleDeLaTablaDeValores() {
        assertThat(valores.catalogoDe(EJERCICIO))
                .as("no hay tabla de catalogo: la lista mantenible es la tabla de valores")
                .singleElement()
                .satisfies(
                        entrada -> {
                            assertThat(entrada.marca()).isEqualTo("TOYOTA");
                            assertThat(entrada.modelo()).isEqualTo("YARIS");
                        });
    }

    /**
     * Lee el valor de un conjunto concreto, que es la lectura de la reproducibilidad.
     *
     * <p>Va dentro de una transaccion explicita porque un repositorio no la abre: la abre el caso
     * de uso, y aqui se esta llamando al repositorio a proposito, para leer por identificador sin
     * pasar por la resolucion del ejercicio.
     */
    private static ValorReferencial exigir(long conjunto) {
        ValorReferencial valor =
                transacciones.execute(
                        estado ->
                                repositorio
                                        .buscar(
                                                IdentificadorDeConjunto.de(conjunto),
                                                "TOYOTA",
                                                "YARIS",
                                                FABRICACION.valor())
                                        .orElse(null));
        if (valor == null) {
            throw new AssertionError("El conjunto " + conjunto + " no trae el valor");
        }
        return valor;
    }

    private static Vehiculo vehiculo() {
        return Vehiculo.nuevo(
                Placa.de("V1A-111"), 1, "TOYOTA", "YARIS", "M1", FABRICACION, new Ejercicio(2021));
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220302', 'Municipalidad de los valores',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /**
     * Un conjunto sellado del mismo ejercicio, que <b>compone</b> una edicion del cuadro con su
     * valor referencial.
     *
     * <p>Desde V55 (D-13, ADR-0017) la tabla del MEF es nacional: no cuelga de este conjunto, y su
     * edicion la publica {@code rol_carga_parametros}. Lo que el conjunto guarda es <b>que edicion
     * uso</b>, en {@code conjunto_parametro_detalle}, que es donde ya guardaba que UIT uso.
     *
     * <p>Y el orden importa: el conjunto se crea ABIERTO y se sella al final. Componer sobre uno ya
     * sellado lo rechaza {@code detalle_de_conjunto_sellado_inmutable} (V9), que es exactamente lo
     * que esa restriccion existe para impedir.
     */
    private static long sellarConjunto(int version, BigDecimal valor) throws SQLException {
        long edicion = publicarEdicion(version, valor);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, EJERCICIO.valor());
                sentencia.setInt(3, version);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    conjunto = fila.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id, conjunto_id,"
                                    + " parametro_id) VALUES (?, ?, ?)")) {
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
            return conjunto;
        }
    }

    /** La edicion nacional del cuadro, con rol_carga_parametros y sin contexto de municipalidad. */
    private static long publicarEdicion(int version, BigDecimal valor) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES (NULL, 'TABLA_DE_LA_PRUEBA', ?,"
                                    + " 'tabla de la prueba, sin valor normativo', DATE '2026-01-01',"
                                    + " 'tabla de la prueba, sin valor normativo', 'quien transcribe',"
                                    + " 'quien verifica') RETURNING id")) {
                sentencia.setString(1, "v" + version);
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
                                    + " VALUES (?, ?, 'A2', 'TOYOTA', 'YARIS', ?, ?,"
                                    + "         'tabla de la prueba, sin valor normativo')")) {
                sentencia.setLong(1, edicion);
                sentencia.setInt(2, EJERCICIO.valor());
                sentencia.setInt(3, FABRICACION.valor());
                sentencia.setBigDecimal(4, valor);
                sentencia.executeUpdate();
            }
            carga.commit();
            return edicion;
        }
    }
}
