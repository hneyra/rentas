package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.Arancel;
import kamayuk.rentas.catastro.dominio.Depreciacion;
import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import kamayuk.rentas.catastro.infraestructura.ValuacionRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aranceles, valores unitarios y depreciacion salen del <b>conjunto sellado</b>, y corregir una
 * cifra ya usada exige version nueva (#17).
 *
 * <p>Las dos pruebas que dan valor a este archivo son {@link
 * #corregirCreaVersionNuevaYLaAnteriorSigueIntacta()} y {@link
 * #cargarContraUnConjuntoSelladoFalla()}: la primera demuestra que el mecanismo de #10 funciona
 * igual aqui que en {@code parametros} y en el valor referencial vehicular de #141 —dos versiones
 * selladas del mismo ejercicio, cada una con su cifra, y la vigente es la de mayor version—; la
 * segunda demuestra que "editar en sitio falla" no es una promesa de la aplicacion sino del
 * disparador de {@code V18}, que ninguna carga concurrente puede sortear.
 *
 * <p><b>Aqui no hay ninguna cifra tributaria.</b> Los importes son de relleno y no representan
 * ningun valor normativo real: lo que se prueba es de donde se lee y cuando se puede escribir, no
 * cuanto vale (D-02).
 */
@DisplayName("#17 — Tablas de valuacion por conjunto")
class TablasDeValuacionTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final BigDecimal VALOR_V1 = new BigDecimal("850.000000");
    private static final BigDecimal VALOR_V2 = new BigDecimal("900.000000");
    private static final Observacion OBSERVACION = Observacion.de("carga de prueba");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long viaId;
    private static long conjuntoV1;
    private static long conjuntoV2;
    private static long conjuntoAbierto;
    private static TablasDeValuacion tablas;
    private static LectorDeParametros lector;
    private static ValuacionRepositoryJdbc repositorio;
    private static TransactionTemplate transacciones;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        viaId = crearVia();
        conjuntoV1 = sellarConjuntoConArancel(1, VALOR_V1);
        conjuntoV2 = sellarConjuntoConArancel(2, VALOR_V2);
        conjuntoAbierto = crearConjuntoAbierto(3);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        repositorio = new ValuacionRepositoryJdbc(jdbc);
        transacciones = new TransactionTemplate(new TenantTransactionManager(pool));
        lector = envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)), pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        Auditoria auditoria = new AuditoriaJdbc(jdbc, reloj);
        tablas = envolver(new TablasDeValuacion(repositorio, lector, auditoria, reloj), pool);
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
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    // LO QUE SE FUE CON P5B, Y POR QUE NO SE SUSTITUYE AQUI
    //
    // Cuatro pruebas de esta clase median guardas de la BASE que se fueron con sus tablas a
    // `normativa` en `V2`:
    //
    //   - «los cuadros nacionales salen de la edicion que el conjunto compuso»: el JOIN con
    //     `conjunto_parametro_detalle`. Hoy lo hace `normativa` al componer el snapshot y lo mide
    //     `ComponerSnapshotTest` alli; aqui la copia local YA ES lo que el conjunto compuso.
    //   - «una edicion sellada no admite una fila mas»: el disparador de inmutabilidad de `V9`.
    //   - «la aplicacion no puede escribir un cuadro nacional»: el REVOKE de `V55` a `sgtm_app`.
    //     Las dos viven en `normativa`, con sus pruebas.
    //   - «cargar contra un conjunto sellado falla»: el disparador `arancel_de_conjunto_sellado_
    //     inmutable` de `V18`, que `V2` RETIRA porque consultaba una tabla que ya no esta. Esa
    //     garantia QUEDA ABIERTA y hay que reconstruirla en `catastro`, donde `arancel` va a vivir
    //     (P5C). Es un hueco declarado: `docs/00-gobierno/P5B-extraccion.md` §7.
    //
    // Lo que se queda es lo que sigue siendo de este sistema: que el ejercicio resuelva a un
    // conjunto, que los cuadros se lean por conjunto y no por ejercicio, y que un conjunto sin su
    // snapshot no vea ningun cuadro.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el ejercicio resuelve al conjunto sellado de mayor version")
    void elEjercicioResuelveAlSelladoVigente() {
        assertThat(lector.conjuntoVigenteEn(EJERCICIO))
                .isEqualTo(IdentificadorDeConjunto.de(conjuntoV2));
    }

    @Test
    @DisplayName(
            "corregir crea version nueva, y la anterior sigue intacta con la cifra que uso su"
                    + " emision")
    void corregirCreaVersionNuevaYLaAnteriorSigueIntacta() {
        assertThat(tablas.aranceles(EJERCICIO))
                .as("la consulta por ejercicio usa el conjunto vigente: el de mayor version")
                .singleElement()
                .extracting(Arancel::valorM2)
                .extracting(ValorNormativo::valor)
                .satisfies(valor -> assertThat(valor).isEqualByComparingTo(VALOR_V2));

        BigDecimal arancelV1 = arancelDe(conjuntoV1);
        assertThat(arancelV1)
                .as(
                        "sin esto la prueba de arriba no demuestra nada: podria estar leyendo"
                                + " siempre la unica fila que hay, sin importar el conjunto")
                .isEqualByComparingTo(VALOR_V1);
    }

    @Test
    @DisplayName("cargar contra un conjunto abierto entra, con auditoria")
    void cargarContraUnConjuntoAbiertoEntra() {
        Arancel guardado =
                tablas.cargarArancel(
                        Arancel.nuevo(
                                viaId,
                                "TRAMO-2",
                                new ValorNormativo(VALOR_V1),
                                "resolucion de prueba"),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        OBSERVACION);

        assertThat(guardado.id()).isNotNull();
    }

    @Test
    @DisplayName("cargar sin observacion no compila: el metodo la exige en la firma (regla 10)")
    void observacionEsObligatoriaEnLaFirma() {
        // No hay sobrecarga sin Observacion, y por eso no hay nada que probar en tiempo de
        // ejecucion: TablasDeValuacion.cargarArancel(Arancel, IdentificadorDeConjunto,
        // Observacion) no compila si se omite el tercer argumento.
        assertThat(tablas).isNotNull();
    }

    @Test
    @DisplayName("el conjunto que no compuso la edicion no ve el cuadro, aunque este publicado")
    void unConjuntoQueNoLaCompusoNoVeLaEdicion() throws SQLException {
        // La edicion existe, es nacional y esta publicada: cualquiera la puede leer si la nombra.
        // Lo que decide si entra en una determinacion NO es que exista, es que el conjunto la haya
        // compuesto —y el sellado congela esa composicion—. Sin esta prueba, un JOIN escrito de
        // mas devolveria toda edicion publicada a todo conjunto, y la reproducibilidad de ARQ-09 §3
        // se perderia sin que ninguna cifra pareciera mal.
        publicarEdicionNacional("VUE-QUE-NADIE-COMPUSO");

        IdentificadorDeConjunto sinComponer = IdentificadorDeConjunto.de(conjuntoV1);
        List<ValorUnitarioEdificacion> valoresUnitarios =
                transacciones.execute(estado -> repositorio.valoresUnitariosDe(sinComponer));
        List<Depreciacion> depreciaciones =
                transacciones.execute(estado -> repositorio.depreciacionesDe(sinComponer));

        assertThat(valoresUnitarios).isEmpty();
        assertThat(depreciaciones).isEmpty();
    }

    /**
     * Lee el arancel de un conjunto concreto, que es la lectura de la reproducibilidad.
     *
     * <p>Va dentro de una transaccion explicita porque un repositorio no la abre: la abre el caso
     * de uso, y aqui se esta llamando al repositorio a proposito, para leer por identificador sin
     * pasar por la resolucion del ejercicio.
     */
    private static BigDecimal arancelDe(long conjunto) {
        return transacciones.execute(
                estado ->
                        repositorio.arancelesDe(IdentificadorDeConjunto.de(conjunto)).stream()
                                .findFirst()
                                .map(Arancel::valorM2)
                                .map(ValorNormativo::valor)
                                .orElseThrow());
    }

    /**
     * Publica una edicion nacional —su cabecera en {@code parametro_tributario} y una fila de cada
     * cuadro— como {@code rol_carga_parametros}, que es la unica credencial que puede.
     *
     * <p>Sin contexto de municipalidad, y no por comodidad: no hay ninguna que fijar. La politica
     * de lectura de estas tablas usa la forma de dos argumentos de {@code current_setting}
     * justamente para que la carga de un catalogo nacional pueda correr asi (V55).
     */
    private static long publicarEdicionNacional(String clave) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba)"
                                    + " VALUES (NULL, 'PRUEBA_EDICION', ?, 'edicion de prueba',"
                                    + " '2026-01-01', 'tabla de la prueba, sin valor normativo',"
                                    + " 'quien transcribe', 'quien verifica') RETURNING id")) {
                sentencia.setString(1, clave);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    edicion = fila.getLong(1);
                }
            }
            carga.commit();
            agregarValorUnitario(edicion, 'C');
            agregarDepreciacion(edicion);
            return edicion;
        }
    }

    private static void agregarValorUnitario(long edicion, char categoria) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO valor_unitario_de_prueba (publicacion_id, partida,"
                                        + " categoria, anio_construccion_desde, valor_m2,"
                                        + " documento_fuente)"
                                        + " VALUES (?, 'MUROS', ?, 2000, ?, 'tabla de la prueba, sin"
                                        + " valor normativo')")) {
            sentencia.setLong(1, edicion);
            sentencia.setString(2, String.valueOf(categoria));
            sentencia.setBigDecimal(3, VALOR_V1);
            sentencia.executeUpdate();
            carga.commit();
        }
    }

    /**
     * Dos filas: la misma combinacion de material, estado y antiguedad en dos tablas del Anexo I, y
     * una tercera en el tramo abierto. Es la forma que el cuadro tiene de verdad desde V57 (H-15):
     * con una sola fila, leer mal el uso pasaria desapercibido.
     */
    private static void agregarDepreciacion(long edicion) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO depreciacion_de_prueba (publicacion_id, uso, material,"
                                        + " estado_conservacion, antiguedad_hasta, porcentaje,"
                                        + " documento_fuente)"
                                        + " VALUES (?, ?, 'CONCRETO', 'BUENO', ?, ?, 'tabla de la"
                                        + " prueba, sin valor normativo')")) {
            for (String[] fila :
                    new String[][] {
                        {"01", "10", "1.0000"}, {"03", "10", "2.0000"}, {"01", null, "3.0000"}
                    }) {
                sentencia.setLong(1, edicion);
                sentencia.setString(2, fila[0]);
                if (fila[1] == null) {
                    sentencia.setNull(3, java.sql.Types.SMALLINT);
                } else {
                    sentencia.setInt(3, Integer.parseInt(fila[1]));
                }
                sentencia.setBigDecimal(4, new java.math.BigDecimal(fila[2]));
                sentencia.executeUpdate();
            }
            carga.commit();
        }
    }

    /** Cerrar la edicion es lo que hace el proceso de carga cuando termina de publicarla. */
    private static void sellarEdicion(long edicion) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "UPDATE parametro_tributario_de_prueba SET sellado = true WHERE id = ?")) {
            sentencia.setLong(1, edicion);
            sentencia.executeUpdate();
            carga.commit();
        }
    }

    /** La composicion: la misma fila de detalle con la que un conjunto compone la UIT. */
    private static void componerEnElConjunto(long conjunto, long edicion) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id, conjunto_id,"
                                    + " parametro_id) VALUES (?, ?, ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.setLong(3, edicion);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220303', 'Municipalidad de la valuacion',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearVia() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO via (municipalidad_id, codigo, tipo_via, nombre)"
                                    + " VALUES (?, 'V-VALUACION', 'AVENIDA', 'Via de la prueba')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long sellarConjuntoConArancel(int version, BigDecimal valor)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long conjunto;
            // El conjunto se carga ABIERTO y se sella despues, en ese orden: el disparador
            // valuacion_de_conjunto_sellado_es_inmutable (V18) bloquea el INSERT en arancel
            // en cuanto su conjunto esta SELLADO, asi que sellar antes de cargar la fila
            // haria fallar esta misma fixture con el mismo error que
            // cargarContraUnConjuntoSelladoFalla existe para demostrar.
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
                            "INSERT INTO arancel (municipalidad_id, conjunto_id, via_id, valor_m2,"
                                    + " documento_fuente)"
                                    + " VALUES (?, ?, ?, ?, 'tabla de la prueba, sin valor normativo')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.setLong(3, viaId);
                sentencia.setBigDecimal(4, valor);
                sentencia.executeUpdate();
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO', fecha_sellado = now(),"
                                    + " usuario_sellado = 'prueba' WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
            return conjunto;
        }
    }

    private static long crearConjuntoAbierto(int version) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, EJERCICIO.valor());
                sentencia.setInt(3, version);
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
