package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El rastro de la corrida de emision predial, contra PostgreSQL de verdad (#523).
 *
 * <p>Conectado como {@code sgtm_app}, que es lo unico que hace que estas pruebas verifiquen algo:
 * un superusuario <b>omite RLS incluso con FORCE ROW LEVEL SECURITY</b>, y la mitad de este archivo
 * pasaria en verde sin comprobar nada (DAT-01 §0).
 *
 * <p>Lo que se mide aqui es lo que ninguna prueba de capa web puede decir: que las corridas de una
 * municipalidad no se ven desde otra, y que <b>no se pueden corregir</b> — una corrida es un hecho,
 * y la inmutabilidad la sostiene el privilegio, no que nadie escriba el verbo.
 */
@DisplayName("#523 — La corrida de emision predial deja rastro")
class CorridaDeEmisionJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-01-28T07:14:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static CorridaDeEmisionRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("260101", "Municipalidad de corridas A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad de corridas B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new CorridaDeEmisionRepositoryJdbc(JdbcClient.create(pool), RELOJ);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("la corrida se guarda con sus observados, y se relee entera")
    void seGuardaYSeRelee() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2026, 3, 2, "9412204.60", observadosDePrueba()),
                                        Observacion.de("Emision anual 2026")));

        assertThat(guardada).isNotNull();
        assertThat(guardada.id()).isNotNull();

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2026)));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().determinados()).isEqualTo(2);
        assertThat(ultima.get().leidos()).isEqualTo(3);
        assertThat(ultima.get().montoEmitido()).isEqualTo(Dinero.de("9412204.60"));
        assertThat(ultima.get().fechaCalculo()).isEqualTo(LocalDate.of(2026, 1, 28));
    }

    /**
     * <b>La cabecera se lee sin ellos, y eso es deliberado.</b> Son cientos, y una portada que los
     * trajera siempre seria la peticion mas pesada del sistema para una cifra que casi nadie abre.
     */
    @Test
    @DisplayName("los observados se piden aparte, con su motivo")
    void losObservadosSePidenAparte() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2025, 3, 2, "100.00", observadosDePrueba()),
                                        Observacion.de("Emision anual 2025")));

        Optional<CorridaDeEmision> cabecera =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2025)));
        assertThat(cabecera).isPresent();
        assertThat(cabecera.get().observados())
                .as("la cabecera no los trae: se piden aparte y paginados")
                .isEmpty();

        Pagina<CorridaDeEmision.Observado> observados =
                transaccion.execute(
                        estado ->
                                repositorio.observadosDe(
                                        requireId(guardada),
                                        new Paginacion(
                                                0, 20, "id", Paginacion.Direccion.ASCENDENTE)));

        assertThat(observados.contenido()).hasSize(1);
        assertThat(observados.contenido().get(0).codContribuyente()).isEqualTo("C-000042");
        assertThat(observados.contenido().get(0).motivo())
                .as("un observado sin motivo no se puede arreglar, que es para lo que existe")
                .contains("sin arancel");
    }

    /**
     * <b>Una simulacion tambien deja rastro, y se distingue.</b> Esconderla haria que «ver los
     * observados antes de emitir» no dejara nada que mirar despues.
     */
    @Test
    @DisplayName("la ultima puede ser una simulacion, y la fila lo dice")
    void laUltimaPuedeSerUnaSimulacion() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                corridaDe(2024, 5, 5, "500.00", List.of()),
                                Observacion.de("Emision 2024")));
        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                new CorridaDeEmision(
                                        null,
                                        new Ejercicio(2024),
                                        "TODOS",
                                        null,
                                        null,
                                        null,
                                        "TRIMESTRAL",
                                        true,
                                        "",
                                        5,
                                        5,
                                        Dinero.de("500.00"),
                                        LocalDate.of(2026, 1, 28),
                                        List.of()),
                                Observacion.de("Simulacion antes de reemitir")));

        Optional<CorridaDeEmision> ultima =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2024)));

        assertThat(ultima).isPresent();
        assertThat(ultima.get().simulacion())
                .as("la ultima es la simulacion, y la lectura no la esconde ni la disfraza")
                .isTrue();
    }

    @Test
    @DisplayName("sin corridas del ejercicio no hay cabecera de ceros: no hay nada")
    void sinCorridasNoHayCabeceraDeCeros() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        Optional<CorridaDeEmision> ninguna =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2019)));

        assertThat(ninguna)
                .as("«todavia no se ha corrido» y «se corrio y no emitio nada» son dos cosas")
                .isEmpty();
    }

    @Test
    @DisplayName("las corridas de una municipalidad no se ven desde otra")
    void noSeVenDesdeOtraMunicipalidad() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        transaccion.execute(
                estado ->
                        repositorio.guardar(
                                corridaDe(2023, 9, 9, "999.00", List.of()),
                                Observacion.de("Emision 2023 de A")));

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        Optional<CorridaDeEmision> desdeB =
                transaccion.execute(estado -> repositorio.ultimaDe(new Ejercicio(2023)));

        assertThat(desdeB)
                .as("la corrida de A no existe para B: RLS con FORCE, y conectados como sgtm_app")
                .isEmpty();
    }

    /**
     * <b>La inmutabilidad la sostiene el privilegio, no el codigo.</b> {@code V62} no le concede a
     * {@code sgtm_app} ni {@code UPDATE} ni {@code DELETE}, asi que no hace falta confiar en que
     * nadie escriba el verbo: escribirlo falla.
     */
    @Test
    @DisplayName("una corrida no se corrige ni se borra: sgtm_app no puede")
    void unaCorridaNoSeCorrigeNiSeBorra() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        CorridaDeEmision guardada =
                transaccion.execute(
                        estado ->
                                repositorio.guardar(
                                        corridaDe(2022, 1, 1, "1.00", List.of()),
                                        Observacion.de("Emision 2022")));
        long id = requireId(guardada);

        assertThatThrownBy(() -> ejecutarComoApp("UPDATE corrida_predial SET determinados = 999"))
                .as("corregir lo que emitio se hace corriendo otra, que deja su propia fila")
                .hasMessageContaining("permission denied");

        assertThatThrownBy(() -> ejecutarComoApp("DELETE FROM corrida_predial WHERE id = " + id))
                .as("y no se borra: sin DELETE en nada que sea un hecho (regla 4)")
                .hasMessageContaining("permission denied");

        assertThatThrownBy(
                        () -> ejecutarComoApp("UPDATE corrida_predial_observado SET motivo = 'x'"))
                .as("el motivo de un observado tampoco se reescribe")
                .hasMessageContaining("permission denied");
    }

    // ------------------------------------------------------------ ayudantes

    private static long requireId(CorridaDeEmision corrida) {
        Long id = corrida.id();
        if (id == null) {
            throw new IllegalStateException("La corrida guardada tiene id");
        }
        return id;
    }

    private static CorridaDeEmision corridaDe(
            int ejercicio,
            int leidos,
            int determinados,
            String monto,
            List<CorridaDeEmision.Observado> observados) {
        return new CorridaDeEmision(
                null,
                new Ejercicio(ejercicio),
                "TODOS",
                null,
                null,
                null,
                "TRIMESTRAL",
                false,
                "Conjunto 2026 v1",
                leidos,
                determinados,
                Dinero.de(monto),
                LocalDate.of(2026, 1, 28),
                observados);
    }

    private static List<CorridaDeEmision.Observado> observadosDePrueba() {
        return List.of(
                new CorridaDeEmision.Observado(
                        "C-000042",
                        "MEDINA MEDINA, RUFINA",
                        "Uno de sus predios esta sin arancel para el ejercicio"));
    }

    private static void ejecutarComoApp(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException(fallo.getMessage(), fallo);
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
}
