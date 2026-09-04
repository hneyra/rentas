package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.infraestructura.ValuacionRecibidaJdbc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P5C / AC 3 — con el ingestor detenido a mitad, {@code rentas} SE NIEGA a emitir y dice por que.
 *
 * <h2>Que se mide, contra PostgreSQL de verdad</h2>
 *
 * <p>Las cuatro situaciones en que puede estar la valuacion de un ejercicio cuando alguien pulsa
 * «emitir», y que <b>cada una se distinga de las otras tres</b>. No basta con que la corrida no
 * arranque: las tres negativas se arreglan de tres maneras distintas —correr la valuacion, esperar
 * a la cola, volver a valorizar— y contestar la equivocada manda a quien opera a buscar donde no
 * es. Es la leccion que #540 y #547 dejaron escrita para los 422 que nombran la llave que falta.
 *
 * <h2>Y por que el conteo viene del cierre y no se cuenta aqui</h2>
 *
 * <p>Porque si {@code rentas} lo derivara de lo que recibio, comprobaria que lo que tiene es igual
 * a lo que tiene. La prueba de «faltan tres» se escribe por eso: se declara un cierre de cinco y se
 * aplican dos, que es exactamente lo que deja un ingestor detenido.
 */
@DisplayName("P5C / AC 3 — el candado antes de emitir, contra PostgreSQL")
class CandadoDeEmisionJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static CandadoDeEmision candado;
    private static TransactionTemplate transaccion;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "271001", "Municipalidad A");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidad, parametroId, "CE");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        candado = new CandadoDeEmision(new ValuacionRecibidaJdbc(JdbcClient.create(pool)));
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void vaciarLaCola() throws SQLException {
        // Va ANTES y no despues, y el motivo se midio: `DatosDePrueba` siembra una valuacion y su
        // corrida cerrada —para que la prueba de aislamiento tenga filas que mirar—, asi que la
        // primera prueba de esta clase encontraba una corrida que ella no puso.
        //
        // Cada prueba describe un ESTADO distinto de la cola, y el estado es justo lo que se
        // mide, asi que hay que vaciarla entre una y otra.
        //
        // Va con el OWNER y no con el ingestor, y eso dice algo del diseno: `V5` no le da DELETE
        // al ingestor sobre ninguna de las dos —una valuacion es un hecho sellado (ADR-0027 §1) y
        // un evento que retira un predio publica otra, no borra la anterior—, asi que este vaciado
        // NO ES UN CAMINO DEL SISTEMA: es teardown de prueba, y solo lo puede hacer quien es dueno
        // de la tabla.
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidad);
            ejecutar(owner, "DELETE FROM valuacion_predio");
            ejecutar(owner, "DELETE FROM valuacion_corrida");
            owner.commit();
        }
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("sin cierre de corrida no se emite, y lo dice: catastro no ha valorizado")
    void sinCierreNoSeEmite() {
        // Es el estado de hoy en TODAS las municipalidades: nadie ha corrido una valuacion.
        assertThatThrownBy(() -> enTransaccion(() -> candado.exigirLaValuacionCompleta(EJERCICIO)))
                .isInstanceOf(CandadoDeEmision.ValuacionSinCerrar.class)
                .hasMessageContaining("no ha cerrado su corrida de valuacion")
                .hasMessageContaining("2026");
    }

    @Test
    @DisplayName("AC 3 — con el ingestor detenido a mitad se niega, y dice CUANTAS faltan")
    void conElIngestorDetenidoAMitadSeNiega() throws SQLException {
        // Catastro cerro con cinco; el ingestor alcanzo a aplicar dos y se paro.
        cerrarCorrida(5, "da".repeat(32));
        aplicarValuaciones(2);

        assertThatThrownBy(() -> enTransaccion(() -> candado.exigirLaValuacionCompleta(EJERCICIO)))
                .isInstanceOf(CandadoDeEmision.ValuacionIncompleta.class)
                .hasMessageContaining("cerro su corrida con 5 valuaciones")
                .hasMessageContaining("aqui han llegado 2")
                .hasMessageContaining("Faltan 3")
                .satisfies(
                        fallo -> {
                            CandadoDeEmision.ValuacionIncompleta incompleta =
                                    (CandadoDeEmision.ValuacionIncompleta) fallo;
                            assertThat(incompleta.esperadas()).isEqualTo(5);
                            assertThat(incompleta.recibidas()).isEqualTo(2);
                        });
    }

    @Test
    @DisplayName("con todas las valuaciones pero otras, tampoco: y eso no se arregla esperando")
    void conLaHuellaQueNoCuadraTampoco() throws SQLException {
        // El caso peor: el numero cuadra y los hechos no son los mismos. Un candado que solo
        // contara lo dejaria pasar, y el padron saldria calculado con valuaciones que catastro
        // no emitio.
        aplicarValuaciones(3);
        cerrarCorrida(3, "ff".repeat(32));

        assertThatThrownBy(() -> enTransaccion(() -> candado.exigirLaValuacionCompleta(EJERCICIO)))
                .isInstanceOf(CandadoDeEmision.ValuacionQueNoCuadra.class)
                .hasMessageContaining("NO son las")
                .hasMessageContaining("no se arregla esperando");
    }

    @Test
    @DisplayName("con la corrida cerrada y todo aplicado, deja pasar: es el contraste")
    void conTodoAplicadoDejaPasar() throws SQLException {
        // La mitad que impide pasarse de listo. Un candado que se negara siempre pasaria las tres
        // pruebas de arriba y dejaria la emision inalcanzable para siempre.
        aplicarValuaciones(3);
        cerrarCorrida(3, huellaDeTres());

        ValuacionRecibidaCierre cierre =
                enTransaccion(
                        () -> {
                            var abierto = candado.exigirLaValuacionCompleta(EJERCICIO);
                            return new ValuacionRecibidaCierre(
                                    abierto.conteo(), abierto.conjuntoId());
                        });

        assertThat(cierre.conteo()).isEqualTo(3);
        assertThat(cierre.conjuntoId())
                .as("y devuelve el conjunto que LA CORRIDA fijo, no el que rentas resolveria")
                .isEqualTo(77L);
    }

    @Test
    @DisplayName("una corrida de OTRO ejercicio no abre la puerta de este")
    void elCierreEsPorEjercicio() throws SQLException {
        aplicarValuaciones(3);
        cerrarCorrida(3, huellaDeTres());

        assertThatCode(() -> enTransaccion(() -> candado.exigirLaValuacionCompleta(EJERCICIO)))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                enTransaccion(
                                        () ->
                                                candado.exigirLaValuacionCompleta(
                                                        new Ejercicio(2027))))
                .as("la valuacion es de un ejercicio: la regla 9 aplicada al autovaluo")
                .isInstanceOf(CandadoDeEmision.ValuacionSinCerrar.class);
    }

    // ------------------------------------------------------------------

    private record ValuacionRecibidaCierre(int conteo, long conjuntoId) {}

    private static <T> T enTransaccion(java.util.function.Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return transaccion.execute(estado -> accion.get());
        } finally {
            TenantContext.limpiar();
        }
    }

    private static void cerrarCorrida(int conteo, String huella) throws SQLException {
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);
            String evento = anotarElEvento(ingestor, "CORRIDA_CERRADA", null, 9);
            try (PreparedStatement sentencia =
                    ingestor.prepareStatement(
                            "INSERT INTO valuacion_corrida (municipalidad_id, ejercicio,"
                                    + " corrida_id, conjunto_id, fecha_de_corte,"
                                    + " reglas_version, conteo, huella, cerrada_en,"
                                    + " recibida_en, evento_id, secuencia)"
                                    + " VALUES (?, 2026, 9, 77, DATE '2025-12-31', 'v1', ?, ?,"
                                    + " now(), now(), CAST(? AS uuid), 9)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, conteo);
                sentencia.setString(3, huella);
                sentencia.setString(4, evento);
                sentencia.executeUpdate();
            }
            ingestor.commit();
        }
    }

    /**
     * Anota el evento en el buzon y devuelve su identificador (`V9`).
     *
     * <p>Sin esto, la clave foranea de `V9` rechaza la fila proyectada: una procedencia que apunta
     * a un evento que nunca se aplico no es procedencia.
     */
    private static String anotarElEvento(
            Connection ingestor, String tipo, Long predioId, long secuencia) throws SQLException {
        String eventoId = java.util.UUID.randomUUID().toString();
        try (PreparedStatement sentencia =
                ingestor.prepareStatement(
                        "INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id,"
                                + " secuencia, tipo, predio_id, aplicado_en, huella)"
                                + " VALUES (?, CAST(? AS uuid), ?, ?, ?, now(), ?)")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setString(2, eventoId);
            sentencia.setLong(3, secuencia);
            sentencia.setString(4, tipo);
            sentencia.setObject(5, predioId);
            sentencia.setString(6, sha256(eventoId));
            sentencia.executeUpdate();
        }
        return eventoId;
    }

    private static void aplicarValuaciones(int cuantas) throws SQLException {
        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, municipalidad);
            for (int i = 1; i <= cuantas; i++) {
                String evento = anotarElEvento(ingestor, "VALUACION_SELLADA", (long) i, i);
                try (PreparedStatement sentencia =
                        ingestor.prepareStatement(
                                "INSERT INTO valuacion_predio (municipalidad_id, ejercicio,"
                                        + " predio_id, fecha_de_corte, motivo, conjunto_id,"
                                        + " reglas_version, reglas_aplicadas, huella, evento_id,"
                                        + " secuencia, recibida_en)"
                                        + " VALUES (?, 2026, ?, DATE '2025-12-31',"
                                        + " 'El sistema no sabe valorizar todavia (D-02a, D-11)',"
                                        + " 77, 'v1', '', ?, CAST(? AS uuid), ?, now())")) {
                    sentencia.setLong(1, municipalidad);
                    sentencia.setLong(2, i);
                    sentencia.setString(3, huellaDe(i));
                    sentencia.setString(4, evento);
                    sentencia.setLong(5, i);
                    sentencia.executeUpdate();
                }
            }
            ingestor.commit();
        }
    }

    /** La huella agregada de las tres que {@link #aplicarValuaciones} escribe. */
    private static String huellaDeTres() {
        return sha256(huellaDe(1) + "," + huellaDe(2) + "," + huellaDe(3));
    }

    private static String huellaDe(int predio) {
        return sha256("valuacion-" + predio);
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            texto.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    private static void ejecutar(Connection conexion, String sql) throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            sentencia.execute();
        }
    }
}
