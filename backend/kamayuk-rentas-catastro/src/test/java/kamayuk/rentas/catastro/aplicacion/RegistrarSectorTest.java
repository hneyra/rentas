package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
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
 * Mismo patron que {@link RegistrarViaTest}: el caso de uso, envuelto en un proxy transaccional de
 * verdad, contra PostgreSQL real. Ver su javadoc para el porque de cada eleccion.
 *
 * <p>Cubre las <b>tres</b> operaciones de auditoria que el caso de uso puede asentar —{@code ALTA},
 * {@code MODIFICACION} con los datos anteriores y {@code BAJA}— porque la que se elige es la unica
 * decision del caso de uso, y una que se equivoque no rompe nada: deja la pista mintiendo.
 *
 * <p>Las aserciones sobre la auditoria van por {@code ->>'campo'} y no por el texto de la columna:
 * {@code jsonb} renormaliza lo que se le escribe —{@code "activo":false} vuelve como {@code
 * "activo": false}, con espacio— y una comparacion por subcadena se rompe por donde no es.
 */
@DisplayName("Caso de uso: registrar, editar y dar de baja un sector, con su auditoria")
class RegistrarSectorTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static RegistrarSector registrarSector;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230101", "Municipalidad del caso de uso (sector)");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));

        RegistrarSector objetivo =
                new RegistrarSector(
                        new CatastroRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, reloj), reloj);

        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(
                        new TenantTransactionManager(pool),
                        new AnnotationTransactionAttributeSource()));
        registrarSector = (RegistrarSector) fabrica.getProxy();
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
        OrigenContext.fijar(new Origen("mtorres", "PC-CATASTRO-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("el alta deja el sector y su auditoria")
    void elAltaDejaElSectorYSuAuditoria() throws SQLException {
        Sector guardado =
                registrarSector.registrar(
                        Sector.nuevo("SC-100", "Sector Centro"),
                        Observacion.de("Alta por carga inicial del catalogo territorial"));

        assertThat(guardado.id()).isNotNull();

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, observacion FROM auditoria"
                                        + " WHERE tabla = 'sector' AND clave = ?")) {
            sentencia.setString(1, String.valueOf(guardado.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1)).isEqualTo("ALTA");
                assertThat(fila.getString(2)).contains("carga inicial");
            }
        }
    }

    @Test
    @DisplayName("un segundo alta con el mismo codigo falla y no deja auditoria")
    void unSegundoAltaConElMismoCodigoFalla() throws SQLException {
        registrarSector.registrar(
                Sector.nuevo("SC-REPET", "Primero"),
                Observacion.de("Primera alta, esta si debe quedar"));

        long antes = contar("SELECT count(*) FROM auditoria WHERE tabla = 'sector'");

        assertThatThrownBy(
                        () ->
                                registrarSector.registrar(
                                        Sector.nuevo("SC-REPET", "Repetido a proposito"),
                                        Observacion.de("Segunda alta con codigo ya usado")))
                .isNotNull();

        assertThat(contar("SELECT count(*) FROM auditoria WHERE tabla = 'sector'"))
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("la edicion se audita como MODIFICACION y deja los datos anteriores")
    void laEdicionSeAuditaComoModificacion() throws SQLException {
        Sector original =
                registrarSector.registrar(
                        new Sector(null, "SC-200", "Sector Piura", "Zona A", true),
                        Observacion.de("Alta previa a la correccion"));

        Sector cambiado =
                new Sector(
                        original.id(),
                        original.codigo(),
                        "Sector Piura Norte",
                        original.zona(),
                        original.activo());
        registrarSector.editar(
                original, cambiado, Observacion.de("Correccion de nomenclatura, oficio 2026-31"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, datos_anteriores->>'nombre',"
                                        + " datos_nuevos->>'nombre'"
                                        + " FROM auditoria WHERE tabla = 'sector' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1)).isEqualTo("MODIFICACION");
                assertThat(fila.getString(2))
                        .as("el contrato de MODIFICACION es que el estado previo quede aqui")
                        .isEqualTo("Sector Piura");
                assertThat(fila.getString(3)).isEqualTo("Sector Piura Norte");
            }
        }
    }

    @Test
    @DisplayName("retirar del catalogo se audita como BAJA, no como MODIFICACION")
    void retirarDelCatalogoSeAuditaComoBaja() throws SQLException {
        Sector original =
                registrarSector.registrar(
                        new Sector(null, "SC-201", "Sector Fusionado", "Zona B", true),
                        Observacion.de("Alta previa a la baja"));

        registrarSector.editar(
                original,
                original.dadoDeBaja(),
                Observacion.de("Sector absorbido por el Sector Centro"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, datos_anteriores->>'activo',"
                                        + " datos_nuevos->>'activo'"
                                        + " FROM auditoria WHERE tabla = 'sector' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1)).isEqualTo("BAJA");
                assertThat(fila.getString(2)).isEqualTo("true");
                assertThat(fila.getString(3)).isEqualTo("false");
            }
        }

        assertThat(contar("SELECT count(*) FROM sector WHERE codigo = 'SC-201'"))
                .as("dar de baja no es borrar (RNF-051): su codigo esta en el codigo catastral")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reactivar un sector dado de baja es MODIFICACION, no una BAJA al reves")
    void reactivarEsModificacion() throws SQLException {
        Sector original =
                registrarSector.registrar(
                        new Sector(null, "SC-202", "Sector Reabierto", null, true),
                        Observacion.de("Alta previa a la baja y la reapertura"));
        Sector dadoDeBaja =
                registrarSector.editar(
                        original, original.dadoDeBaja(), Observacion.de("Baja transitoria"));

        registrarSector.editar(
                dadoDeBaja,
                new Sector(
                        dadoDeBaja.id(),
                        dadoDeBaja.codigo(),
                        dadoDeBaja.nombre(),
                        dadoDeBaja.zona(),
                        true),
                Observacion.de("Se reabre por acuerdo de concejo 2026-22"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT operacion, datos_anteriores->>'activo',"
                                        + " datos_nuevos->>'activo'"
                                        + " FROM auditoria WHERE tabla = 'sector' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1))
                        .as("volver a poner algo en el catalogo no es retirarlo")
                        .isEqualTo("MODIFICACION");
                assertThat(fila.getString(2)).isEqualTo("false");
                assertThat(fila.getString(3)).isEqualTo("true");
            }
        }
    }

    @Test
    @DisplayName("la zona vaciada se asienta como null JSON, no como la cadena «null»")
    void laZonaVaciadaSeAsientaComoNull() throws SQLException {
        Sector original =
                registrarSector.registrar(
                        new Sector(null, "SC-203", "Sector Con Zona", "Zona D", true),
                        Observacion.de("Alta previa al vaciado de la zona"));

        registrarSector.editar(
                original,
                new Sector(
                        original.id(),
                        original.codigo(),
                        original.nombre(),
                        null,
                        original.activo()),
                Observacion.de("La zona estaba mal asignada"));

        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT datos_anteriores->>'zona',"
                                        + " datos_nuevos->>'zona',"
                                        + " jsonb_typeof(datos_nuevos->'zona')"
                                        + " FROM auditoria WHERE tabla = 'sector' AND clave = ?"
                                        + " ORDER BY id DESC LIMIT 1")) {
            sentencia.setString(1, String.valueOf(original.id()));
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).isTrue();
                assertThat(fila.getString(1)).isEqualTo("Zona D");
                assertThat(fila.getString(2)).isNull();
                assertThat(fila.getString(3))
                        .as("una zona ausente es null JSON; «null» en texto seria un valor")
                        .isEqualTo("null");
            }
        }
    }

    @Test
    @DisplayName("registrar no acepta un sector que ya tiene identificador: para eso esta editar")
    void registrarNoAceptaUnSectorYaGuardado() {
        Sector original =
                registrarSector.registrar(
                        Sector.nuevo("SC-204", "Sector Ya Guardado"),
                        Observacion.de("Alta para la comprobacion"));

        assertThatThrownBy(
                        () ->
                                registrarSector.registrar(
                                        original, Observacion.de("Alta encubierta de una edicion")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("editar exige que el antes y el despues sean la misma fila")
    void editarExigeQueSeaLaMismaFila() {
        Sector uno =
                registrarSector.registrar(
                        Sector.nuevo("SC-205", "Sector Uno"), Observacion.de("Alta del primero"));
        Sector otro =
                registrarSector.registrar(
                        Sector.nuevo("SC-206", "Sector Otro"), Observacion.de("Alta del segundo"));

        assertThatThrownBy(
                        () ->
                                registrarSector.editar(
                                        uno, otro, Observacion.de("Edicion cruzada, no valida")))
                .isInstanceOf(IllegalArgumentException.class);
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

    private static long contar(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return resultado.getLong(1);
        }
    }
}
