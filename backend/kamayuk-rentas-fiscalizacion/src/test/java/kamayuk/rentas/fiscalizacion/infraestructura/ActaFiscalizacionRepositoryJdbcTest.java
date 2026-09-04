package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las actas de fiscalización contra PostgreSQL de verdad, conectado como {@code sgtm_app} (#45).
 *
 * <p>El AC "registrar un acta no modifica ninguna fila de catastro" se verifica aquí como el propio
 * AC lo pide: contando las filas de {@code predio} y {@code ficha_catastral} antes y después,
 * contra la base real — no razonando sobre el código.
 */
@DisplayName("#45 — Actas de fiscalizacion")
class ActaFiscalizacionRepositoryJdbcTest {

    private static final Observacion OBSERVACION = Observacion.de("Se fiscaliza para la prueba");
    private static final LocalDate VISITA = LocalDate.of(2026, 3, 15);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static TransactionTemplate transaccion;
    private static ActaFiscalizacionRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250401", "Municipalidad de fiscalizacion A");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new ActaFiscalizacionRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("fiscalizador.campo", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura")
    class Escritura {

        @Test
        @DisplayName("un acta predial se guarda y trae la ficha vigente que se le paso")
        void unActaPredialSeGuardaYTraeLaFichaVigente() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0001", "60100001");
            long predioId = crearPredio(municipalidadA, "F-0001");
            long programaId = crearPrograma(municipalidadA, "PF-0001", "PREDIAL");
            long fichaId = crearFicha(municipalidadA, predioId);

            ActaFiscalizacion guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            ActaFiscalizacion.nuevaPredial(
                                                    programaId,
                                                    1,
                                                    titular,
                                                    predioId,
                                                    fichaId,
                                                    VISITA,
                                                    "J. Perez",
                                                    Hallazgo.CONFORME,
                                                    kamayuk.rentas.dominio.AreaM2.de("125.50"),
                                                    null,
                                                    "sin novedad",
                                                    OBSERVACION)));

            assertThat(guardada.id()).isNotNull();
            assertThat(guardada.fichaId()).isEqualTo(fichaId);
        }

        @Test
        @DisplayName("registrar un acta no modifica ninguna fila de catastro (AC de #45)")
        void registrarUnActaNoModificaNingunaFilaDeCatastro() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0002", "60100002");
            long predioId = crearPredio(municipalidadA, "F-0002");
            long programaId = crearPrograma(municipalidadA, "PF-0002", "PREDIAL");
            long fichaId = crearFicha(municipalidadA, predioId);

            long prediosAntes = transaccion.execute(estado -> contar("predio_de_prueba"));
            long fichasAntes = transaccion.execute(estado -> contar("ficha_catastral_de_prueba"));

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    ActaFiscalizacion.nuevaPredial(
                                            programaId,
                                            1,
                                            titular,
                                            predioId,
                                            fichaId,
                                            VISITA,
                                            "J. Perez",
                                            Hallazgo.SUBVALUADOR,
                                            kamayuk.rentas.dominio.AreaM2.de("300.00"),
                                            null,
                                            "area distinta a la declarada",
                                            OBSERVACION)));

            assertThat((long) transaccion.execute(estado -> contar("predio_de_prueba")))
                    .as("ninguna fila de predio cambia")
                    .isEqualTo(prediosAntes);
            assertThat((long) transaccion.execute(estado -> contar("ficha_catastral_de_prueba")))
                    .as("ninguna fila de ficha_catastral cambia")
                    .isEqualTo(fichasAntes);
        }

        @Test
        @DisplayName("un acta vehicular se guarda sin ficha")
        void unActaVehicularSeGuardaSinFicha() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0003", "60100003");
            long vehiculoId = crearVehiculo(municipalidadA, titular, "F03");
            long programaId = crearPrograma(municipalidadA, "PF-0003", "VEHICULAR");

            ActaFiscalizacion guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            ActaFiscalizacion.nuevaVehicular(
                                                    programaId,
                                                    1,
                                                    titular,
                                                    vehiculoId,
                                                    VISITA,
                                                    "J. Perez",
                                                    Hallazgo.OMISO,
                                                    "no declarado",
                                                    OBSERVACION)));

            assertThat(guardada.fichaId()).isNull();
            assertThat(guardada.vehiculoId()).isEqualTo(vehiculoId);
        }
    }

    @Nested
    @DisplayName("Version")
    class Version {

        @Test
        @DisplayName("sin actas previas, la siguiente version es 1; tras una, es 2")
        void laSiguienteVersionSube() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0004", "60100004");
            long vehiculoId = crearVehiculo(municipalidadA, titular, "F04");
            long programaId = crearPrograma(municipalidadA, "PF-0004", "VEHICULAR");

            int primera =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteVersion(
                                            programaId, titular, null, vehiculoId));
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    ActaFiscalizacion.nuevaVehicular(
                                            programaId,
                                            primera,
                                            titular,
                                            vehiculoId,
                                            VISITA,
                                            "J. Perez",
                                            null,
                                            null,
                                            OBSERVACION)));
            int segunda =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteVersion(
                                            programaId, titular, null, vehiculoId));

            assertThat(primera).isEqualTo(1);
            assertThat(segunda).isEqualTo(2);
        }

        @Test
        @DisplayName(
                "un contribuyente con DOS predios en la muestra tiene dos actas en version 1"
                        + " (V60)")
        void dosPrediosDelMismoTitularSonDosActasEnVersionUno() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0010", "60100010");
            long uno = crearPredio(municipalidadA, "F-0010a");
            long otro = crearPredio(municipalidadA, "F-0010b");
            long programaId = crearPrograma(municipalidadA, "PF-0010", "PREDIAL");

            int versionDelPrimero =
                    transaccion.execute(
                            estado -> repositorio.siguienteVersion(programaId, titular, uno, null));
            transaccion.execute(
                    estado -> repositorio.insertar(actaSobre(programaId, titular, uno, 1)));

            int versionDelSegundo =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteVersion(programaId, titular, otro, null));

            assertThat(versionDelPrimero).isEqualTo(1);
            assertThat(versionDelSegundo)
                    .as(
                            "llaveada solo por contribuyente daria 2, y el papel diria que es una"
                                    + " reinspeccion que nunca ocurrio")
                    .isEqualTo(1);
            // Y la base lo admite: con la unicidad anterior, esta segunda insercion chocaba.
            transaccion.execute(
                    estado -> repositorio.insertar(actaSobre(programaId, titular, otro, 1)));
        }

        @Test
        @DisplayName("dos actas vehiculares iguales chocan: NULLS NOT DISTINCT (V60)")
        void dosActasVehicularesIgualesChocan() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0011", "60100011");
            long vehiculoId = crearVehiculo(municipalidadA, titular, "F11");
            long programaId = crearPrograma(municipalidadA, "PF-0011", "VEHICULAR");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    ActaFiscalizacion.nuevaVehicular(
                                            programaId,
                                            1,
                                            titular,
                                            vehiculoId,
                                            VISITA,
                                            "J. Perez",
                                            Hallazgo.OMISO,
                                            null,
                                            OBSERVACION)));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.insertar(
                                                            ActaFiscalizacion.nuevaVehicular(
                                                                    programaId,
                                                                    1,
                                                                    titular,
                                                                    vehiculoId,
                                                                    VISITA,
                                                                    "J. Perez",
                                                                    Hallazgo.OMISO,
                                                                    null,
                                                                    OBSERVACION))))
                    .as(
                            "un acta vehicular deja predio_id en NULL: sin NULLS NOT DISTINCT la"
                                    + " unicidad no protegeria nada en el caso que ocurre siempre")
                    .hasMessageContaining("acta_fisc_version_uq");
        }
    }

    private static ActaFiscalizacion actaSobre(
            long programaId, long titular, long predioId, int version) {
        return ActaFiscalizacion.nuevaPredial(
                programaId,
                version,
                titular,
                predioId,
                null,
                VISITA,
                "J. Perez",
                Hallazgo.CONFORME,
                null,
                null,
                null,
                OBSERVACION);
    }

    @Nested
    @DisplayName("#599 — el uso hallado, y las guardas que lo sostienen en la base")
    class ElUsoHallado {

        @Test
        @DisplayName("un acta predial guarda el uso hallado y lo devuelve al releerla")
        void unActaPredialGuardaElUsoHallado() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0020", "60100020");
            long predioId = crearPredio(municipalidadA, "F-0020");
            long programaId = crearPrograma(municipalidadA, "PF-0020", "PREDIAL");

            ActaFiscalizacion guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            ActaFiscalizacion.nuevaPredial(
                                                    programaId,
                                                    1,
                                                    titular,
                                                    predioId,
                                                    null,
                                                    VISITA,
                                                    "J. Perez",
                                                    Hallazgo.USO_DISTINTO,
                                                    kamayuk.rentas.dominio.AreaM2.de("120.00"),
                                                    "COMERCIO",
                                                    "vivienda convertida en bodega",
                                                    OBSERVACION)));

            ActaFiscalizacion releida =
                    transaccion
                            .execute(
                                    estado ->
                                            repositorio.findById(
                                                    java.util.Objects.requireNonNull(
                                                            guardada.id())))
                            .orElseThrow();

            assertThat(releida.usoHallado())
                    .as("sin la columna en el INSERT y en el SELECT vuelve nulo")
                    .isEqualTo("COMERCIO");
            assertThat(releida.hallazgo()).isEqualTo(Hallazgo.USO_DISTINTO);
        }

        @Test
        @DisplayName("el CHECK de la columna admite USO_DISTINTO desde V76")
        void elCheckAdmiteUsoDistinto() {
            // Mide la guarda de la BASE y no el `if` de Java (#188, #435, #542): sin el DROP+ADD
            // de V76 esta fila da 23514 y las pruebas del dominio siguen todas en verde.
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0021", "60100021");
            long predioId = crearPredio(municipalidadA, "F-0021");
            long programaId = crearPrograma(municipalidadA, "PF-0021", "PREDIAL");

            long id =
                    insertarActaPorSql(
                            programaId, titular, predioId, null, "USO_DISTINTO", "COMERCIO");

            assertThat(id).isPositive();
        }

        @Test
        @DisplayName("un acta VEHICULAR con uso hallado la rechaza la base, no el dominio")
        void unActaVehicularConUsoHalladoLaRechazaLaBase() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0022", "60100022");
            long vehiculoId = crearVehiculo(municipalidadA, titular, "F22");
            long programaId = crearPrograma(municipalidadA, "PF-0022", "VEHICULAR");

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    insertarActaPorSql(
                                            programaId,
                                            titular,
                                            null,
                                            vehiculoId,
                                            "CONFORME",
                                            "COMERCIO"))
                    .as("un vehiculo no declara uso: no hay uso declarado del que difiera")
                    .hasMessageContaining("acta_fisc_uso_hallado_predial_ck");
        }

        @Test
        @DisplayName("USO_DISTINTO sin uso observado la rechaza la base, no el dominio")
        void usoDistintoSinUsoLaRechazaLaBase() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "F-0023", "60100023");
            long predioId = crearPredio(municipalidadA, "F-0023");
            long programaId = crearPrograma(municipalidadA, "PF-0023", "PREDIAL");

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    insertarActaPorSql(
                                            programaId,
                                            titular,
                                            predioId,
                                            null,
                                            "USO_DISTINTO",
                                            null))
                    .hasMessageContaining("acta_fisc_uso_distinto_ck");
        }
    }

    /**
     * Inserta un acta por SQL directo, saltandose el dominio a proposito.
     *
     * <p>Es lo unico que mide una restriccion de la base: por el caso de uso, la guarda de Java
     * rechaza antes y el `CHECK` nunca llega a hablar (#188, #435, #542).
     */
    private static long insertarActaPorSql(
            long programaId,
            long contribuyenteId,
            @org.jspecify.annotations.Nullable Long predioId,
            @org.jspecify.annotations.Nullable Long vehiculoId,
            String hallazgo,
            @org.jspecify.annotations.Nullable String usoHallado) {
        return ejecutarComoApp(
                municipalidadA,
                "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                        + " contribuyente_id, predio_id, vehiculo_id, fecha_visita, fiscalizador,"
                        + " hallazgo, uso_hallado, estado, observacion, usuario_registro)"
                        + " VALUES (?, ?, 1, ?, ?, ?, ?, 'J. Perez', ?, ?, 'ABIERTA',"
                        + "         'siembra por SQL directo', 'prueba') RETURNING id",
                municipalidadA,
                programaId,
                contribuyenteId,
                predioId,
                vehiculoId,
                VISITA,
                hallazgo,
                usoHallado);
    }

    // ------------------------------------------------------------------

    private static long contar(String tabla) {
        return jdbc.sql("SELECT count(*) FROM " + tabla).query(Long.class).single();
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona,"
                        + " nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                        + " 'siembra') RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static long crearPredio(long municipalidadId, String sufijo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                municipalidadId,
                codigoCatastralDe(sufijo));
    }

    private static long crearFicha(long municipalidadId, long predioId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA_HABITACION', ?,"
                        + "         'DECLARACION_JURADA', 'DJ-001', 'ficha de prueba', 'prueba')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                LocalDate.of(2026, 1, 1));
    }

    private static long crearVehiculo(long municipalidadId, long contribuyenteId, String sufijo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca, modelo,"
                        + " categoria, anio_fabricacion, anio_inscripcion)"
                        + " VALUES (?, ?, ?, 'MARCA', 'MODELO', 'M1', 2020, 2021) RETURNING id",
                municipalidadId,
                "ABC-" + sufijo,
                contribuyenteId);
    }

    private static long crearPrograma(long municipalidadId, String codigo, String tipo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio)"
                        + " VALUES (?, ?, 'Programa de prueba', ?, ?) RETURNING id",
                municipalidadId,
                codigo,
                tipo,
                LocalDate.of(2026, 1, 1));
    }

    private static final AtomicInteger SIGUIENTE_CATASTRAL = new AtomicInteger(1);
    private static final ConcurrentHashMap<String, String> CODIGOS_CATASTRALES =
            new ConcurrentHashMap<>();

    /** Codigo catastral de relleno: el dominio {@code cod_catastral} exige 18-25 digitos. */
    private static String codigoCatastralDe(String sufijo) {
        return CODIGOS_CATASTRALES.computeIfAbsent(
                sufijo, s -> String.format("%018d", SIGUIENTE_CATASTRAL.getAndIncrement()));
    }

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
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
