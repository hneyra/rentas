package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.MuestraDelPrograma;
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
 * La muestra de un programa contra PostgreSQL de verdad, conectada como {@code kamayuk_app} (#481).
 *
 * <p>Se conecta como {@code kamayuk_app} y no con la conexión por omisión de la base de prueba
 * porque <b>un superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}</b>: las dos
 * pruebas de aislamiento de aquí pasarían en verde sin verificar nada (DAT-01 §0, primer hallazgo).
 */
@DisplayName("#481 — La muestra del programa de fiscalizacion")
class MuestraDelProgramaRepositoryJdbcTest {

    private static final Observacion OBSERVACION = Observacion.de("Se sortea para la prueba");
    private static final LocalDate SORTEO = LocalDate.of(2026, 3, 15);
    private static final Instant REGISTRO = Instant.parse("2026-03-15T10:00:00Z");
    private static final Paginacion PRIMERA =
            new Paginacion(0, 20, "codRefCatastral", Paginacion.Direccion.ASCENDENTE);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static MuestraDelProgramaRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250501", "Municipalidad de muestra A");
        municipalidadB = crearMunicipalidad("250502", "Municipalidad de muestra B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new MuestraDelProgramaRepositoryJdbc(JdbcClient.create(pool));
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
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Se escribe y se lee")
    class Escritura {

        @Test
        @DisplayName("una muestra se guarda entera y se lee con su condicion y sus dos areas")
        void seGuardaYSeLee() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0001", "70100001");
            long predioId = crearPredio(municipalidadA, "M-0001");
            long programaId = crearPrograma(municipalidadA, "PM-0001");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(programaId, predioId, titular, "M-0001")),
                                    OBSERVACION,
                                    REGISTRO));

            Pagina<MuestraDelPrograma> leidas =
                    transaccion.execute(
                            estado -> repositorio.delPrograma(programaId, null, PRIMERA));

            assertThat(leidas.contenido()).hasSize(1);
            MuestraDelPrograma fila = leidas.contenido().get(0);
            assertThat(fila.predioId()).isEqualTo(predioId);
            assertThat(fila.contribuyenteId()).isEqualTo(titular);
            assertThat(fila.condicion()).isEqualTo(CondicionFiscalizada.OMISO);
            assertThat(fila.areaCatastral()).isEqualTo(AreaM2.de("300.00"));
            assertThat(fila.areaDeclarada()).isEqualTo(AreaM2.de("120.00"));
            assertThat(fila.fechaSorteo()).isEqualTo(SORTEO);
        }

        @Test
        @DisplayName("el mismo predio no entra dos veces en el mismo programa")
        void elMismoPredioNoEntraDosVeces() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0002", "70100002");
            long predioId = crearPredio(municipalidadA, "M-0002");
            long programaId = crearPrograma(municipalidadA, "PM-0002");
            MuestraDelPrograma fila = fila(programaId, predioId, titular, "M-0002");

            transaccion.execute(
                    estado -> repositorio.insertar(List.of(fila), OBSERVACION, REGISTRO));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.insertar(
                                                            List.of(fila), OBSERVACION, REGISTRO)))
                    .as("programa_muestra_uq: sortearlo dos veces son dos visitas al mismo sitio")
                    .hasMessageContaining("programa_muestra_uq");
        }

        @Test
        @DisplayName("se puede pedir la fila de UN predio, que es como el acta resuelve la suya")
        void seLeeLaFilaDeUnPredio() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0003", "70100003");
            long uno = crearPredio(municipalidadA, "M-0003a");
            long otro = crearPredio(municipalidadA, "M-0003b");
            long programaId = crearPrograma(municipalidadA, "PM-0003");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(
                                            fila(programaId, uno, titular, "M-0003a"),
                                            fila(programaId, otro, titular, "M-0003b")),
                                    OBSERVACION,
                                    REGISTRO));

            Pagina<MuestraDelPrograma> soloUno =
                    transaccion.execute(
                            estado -> repositorio.delPrograma(programaId, uno, PRIMERA));

            assertThat(soloUno.contenido()).hasSize(1);
            assertThat(soloUno.contenido().get(0).predioId()).isEqualTo(uno);
        }
    }

    @Nested
    @DisplayName("#586 — El predio sin titular entra, y entra NULO")
    class SinTitular {

        @Test
        @DisplayName("una fila sin titular se guarda y vuelve con la columna NULA, no con un cero")
        void unaFilaSinTitularVuelveNula() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long predioId = crearPredio(municipalidadA, "M-0007");
            long programaId = crearPrograma(municipalidadA, "PM-0007");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(programaId, predioId, null, "M-0007")),
                                    OBSERVACION,
                                    REGISTRO));

            Pagina<MuestraDelPrograma> leidas =
                    transaccion.execute(
                            estado -> repositorio.delPrograma(programaId, null, PRIMERA));

            assertThat(leidas.contenido()).hasSize(1);
            assertThat(leidas.contenido().get(0).contribuyenteId())
                    .as(
                            "con getLong el NULL vuelve como 0, un titular que no existe en ningun"
                                    + " padron e indistinguible de uno que si (#188 con getInt)")
                    .isNull();
            assertThat(leidas.contenido().get(0).sinTitular()).isTrue();
        }

        @Test
        @DisplayName("y el NOT NULL que V60 le puso ya no la para: se mide por SQL directo")
        void laBaseYaNoRechazaElNulo() throws SQLException {
            long predioId = crearPredio(municipalidadA, "M-0008");
            long programaId = crearPrograma(municipalidadA, "PM-0008");

            // Por SQL directo y no por el caso de uso: lo que se mide aqui es la guarda de la
            // BASE, aparte de la de Java. Devolviendo el NOT NULL en V73 esto vuelve a dar 23502
            // y ninguna otra prueba del archivo se entera (la leccion de #188 y #435).
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                try (PreparedStatement sentencia =
                        app.prepareStatement(sqlDeInsercionCruda(programaId, predioId, null))) {
                    assertThat(sentencia.executeUpdate()).isEqualTo(1);
                }
                app.commit();
            }
        }

        @Test
        @DisplayName("pero un contribuyente que no existe se sigue rechazando: la foranea sirve")
        void laForaneaSigueSirviendo() throws SQLException {
            long predioId = crearPredio(municipalidadA, "M-0009");
            long programaId = crearPrograma(municipalidadA, "PM-0009");

            // MATCH SIMPLE —el de PostgreSQL por omision— se da por satisfecho en cuanto UNA
            // columna de la foranea es nula, asi que el nulo pasa. Lo que no pasa es un
            // identificador inventado, y eso hay que medirlo: es lo unico que separa «no hay
            // titular» de «hay uno y es de otra municipalidad».
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                assertThatThrownBy(
                                () ->
                                        app.prepareStatement(
                                                        sqlDeInsercionCruda(
                                                                programaId, predioId, 999999L))
                                                .executeUpdate())
                        .hasMessageContaining("programa_muestra_contribuyente_fk");
            }
        }
    }

    @Nested
    @DisplayName("La exclusion de #481")
    class Exclusion {

        @Test
        @DisplayName("un predio de otro programa ABIERTO ya no se puede volver a sortear")
        void unPredioDeOtroProgramaAbiertoNoSeVuelveASortear() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0004", "70100004");
            long predioId = crearPredio(municipalidadA, "M-0004");
            long primero = crearPrograma(municipalidadA, "PM-0004a");
            long segundo = crearPrograma(municipalidadA, "PM-0004b");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(primero, predioId, titular, "M-0004")),
                                    OBSERVACION,
                                    REGISTRO));

            Set<Long> excluidos =
                    transaccion.execute(
                            estado ->
                                    repositorio.prediosEnProgramasAbiertos(
                                            segundo, Set.of(predioId)));

            assertThat(excluidos).containsExactly(predioId);
        }

        @Test
        @DisplayName(
                "pero uno de un programa CERRADO si: un programa de 2021 no bloquea para siempre")
        void unProgramaCerradoNoExcluye() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0005", "70100005");
            long predioId = crearPredio(municipalidadA, "M-0005");
            long viejo = crearPrograma(municipalidadA, "PM-0005a");
            long nuevo = crearPrograma(municipalidadA, "PM-0005b");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(viejo, predioId, titular, "M-0005")),
                                    OBSERVACION,
                                    REGISTRO));
            cerrarPrograma(municipalidadA, viejo);

            Set<Long> excluidos =
                    transaccion.execute(
                            estado ->
                                    repositorio.prediosEnProgramasAbiertos(
                                            nuevo, Set.of(predioId)));

            assertThat(excluidos).isEmpty();
        }

        @Test
        @DisplayName("y su propia muestra no se excluye a si misma")
        void laPropiaMuestraNoSeExcluye() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "M-0006", "70100006");
            long predioId = crearPredio(municipalidadA, "M-0006");
            long programaId = crearPrograma(municipalidadA, "PM-0006");

            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(programaId, predioId, titular, "M-0006")),
                                    OBSERVACION,
                                    REGISTRO));

            assertThat(
                            (Set<Long>)
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.prediosEnProgramasAbiertos(
                                                            programaId, Set.of(predioId))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Aislamiento y privilegios")
    class AislamientoYPrivilegios {

        @Test
        @DisplayName("la muestra de B no se ve desde A")
        void laMuestraDeBNoSeVeDesdeA() {
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            long titular = crearContribuyente(municipalidadB, "M-B001", "70200001");
            long predioId = crearPredio(municipalidadB, "M-B001");
            long deB = crearPrograma(municipalidadB, "PM-B001");
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    List.of(fila(deB, predioId, titular, "M-B001")),
                                    OBSERVACION,
                                    REGISTRO));
            TenantContext.limpiar();

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Pagina<MuestraDelPrograma> desdeA =
                    transaccion.execute(estado -> repositorio.delPrograma(deB, null, PRIMERA));

            assertThat(desdeA.contenido())
                    .as("fuga de filas de la municipalidad B hacia A")
                    .isEmpty();
        }

        @Test
        @DisplayName("kamayuk_app no puede editar ni borrar una fila de la muestra (regla 4)")
        void sgtmAppNoPuedeEditarNiBorrar() throws SQLException {
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                assertThatThrownBy(
                                () ->
                                        app.prepareStatement(
                                                        "UPDATE programa_muestra SET condicion ="
                                                                + " 'CONFORME'")
                                                .executeUpdate())
                        .hasMessageContaining("programa_muestra");
            }
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                assertThatThrownBy(
                                () ->
                                        app.prepareStatement("DELETE FROM programa_muestra")
                                                .executeUpdate())
                        .hasMessageContaining("programa_muestra");
            }
        }
    }

    // ------------------------------------------------------------------

    private static MuestraDelPrograma fila(
            long programaId,
            long predioId,
            @org.jspecify.annotations.Nullable Long titular,
            String sufijo) {
        return new MuestraDelPrograma(
                null,
                programaId,
                predioId,
                codigoCatastralDe(sufijo),
                titular,
                CondicionFiscalizada.OMISO,
                AreaM2.de("300.00"),
                AreaM2.de("120.00"),
                "01",
                SORTEO);
    }

    /**
     * El {@code INSERT} escrito a mano, para medir lo que la BASE acepta al margen de lo que Java
     * comprueba. {@code contribuyente} nulo se escribe como {@code NULL} literal.
     */
    private static String sqlDeInsercionCruda(
            long programaId, long predioId, @org.jspecify.annotations.Nullable Long contribuyente) {
        return "INSERT INTO programa_muestra (municipalidad_id, programa_id, predio_id,"
                + " cod_ref_catastral, contribuyente_id, condicion, fecha_sorteo, observacion,"
                + " usuario_registro, fecha_registro)"
                + " SELECT municipalidad_id, "
                + programaId
                + ", id, codigo_ref_catastral, "
                + (contribuyente == null ? "NULL" : contribuyente)
                + ", 'OMISO', DATE '2026-03-15', 'siembra cruda', 'siembra', now()"
                + " FROM predio_de_prueba WHERE id = "
                + predioId;
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

    private static long crearPrograma(long municipalidadId, String codigo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio, ejercicio, sector_codigo, criterio, fiscalizador)"
                        + " VALUES (?, ?, 'Programa de prueba', 'PREDIAL', ?, 2026, '01', 'OMISO',"
                        + "         'R. MENDOZA CRUZ') RETURNING id",
                municipalidadId,
                codigo,
                LocalDate.of(2026, 1, 1));
    }

    private static void cerrarPrograma(long municipalidadId, long programaId) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            // Lo cierra el owner: `kamayuk_app` no tiene UPDATE sobre `programa_fiscalizacion`, que
            // es
            // otra tabla que solo se agrega (V7). Aqui solo hace falta el estado sembrado.
            ContextoDeTenant.fijar(owner, municipalidadId);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "UPDATE programa_fiscalizacion SET estado = 'CERRADO' WHERE id = ?")) {
                sentencia.setLong(1, programaId);
                sentencia.executeUpdate();
                owner.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static final AtomicInteger SIGUIENTE_CATASTRAL = new AtomicInteger(5000);
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
