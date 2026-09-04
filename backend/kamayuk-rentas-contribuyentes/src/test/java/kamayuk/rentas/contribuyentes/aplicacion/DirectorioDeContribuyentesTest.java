package kamayuk.rentas.contribuyentes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
 * La API que este contexto publica a los demas, contra PostgreSQL real.
 *
 * <p>Se prueba aqui y no en {@code catastro} a proposito: es este modulo el que sabe buscar por
 * aproximacion y resolver un domicilio a una fecha, y una prueba en el vecino diria que la llamada
 * se hizo, no que encuentra a quien tiene que encontrar.
 *
 * <p>Lo que defiende: que el vecino y la pantalla del padron <b>encuentren al mismo contribuyente
 * escribiendo lo mismo</b>. Si cada uno tuviera su consulta, un dia divergirian y nadie sabria cual
 * de las dos es la buena.
 */
@DisplayName("ARQ-01 §4, regla 1 — Directorio de contribuyentes (API publica del contexto)")
class DirectorioDeContribuyentesTest {

    private static final LocalDate HOY = LocalDate.of(2026, 8, 19);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static DirectorioDeContribuyentes directorio;

    private static long maria;
    private static long jose;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad del directorio");
        otraMunicipalidad = crearMunicipalidad("260102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        directorio =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)),
                        gestor);

        maria =
                crearContribuyente(
                        municipalidad, "C-000100", "40100100", "PEÑA GARCIA, MARIA DEL CARMEN");
        jose = crearContribuyente(municipalidad, "C-000200", "40100200", "TORRES SILVA, JOSE LUIS");
        crearDomicilio(maria, "AV. GRAU 100", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        crearDomicilio(maria, "JR. LIMA 250", LocalDate.of(2026, 7, 1), null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
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
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("encuentra a quien esta mal escrito, que es como llega desde ventanilla")
    void encuentraElNombreMalEscrito() {
        List<ResumenDeContribuyente> hallados = directorio.buscar("pena garsia maria", 10);

        assertThat(hallados)
                .as(
                        "un vecino que solo supiera comparar por igualdad no encontraria a nadie, y"
                                + " el tecnico concluiria que el predio no tiene titular")
                .extracting(ResumenDeContribuyente::id)
                .contains(maria);
    }

    @Test
    @DisplayName("un nombre sin relacion no se cuela")
    void unNombreSinRelacionNoSeCuela() {
        assertThat(directorio.buscar("zzzz qqqq wwww", 10))
                .as("si todo se pareciera a todo, el filtro devolveria el padron entero")
                .isEmpty();
    }

    @Test
    @DisplayName("el codigo exacto gana, y no se entierra entre parecidos")
    void elCodigoExactoGana() {
        List<ResumenDeContribuyente> hallados = directorio.buscar("C-000200", 10);

        assertThat(hallados).hasSize(1);
        assertThat(hallados.get(0).id()).isEqualTo(jose);
    }

    @Test
    @DisplayName("lo que no tiene forma de codigo se busca como nombre, sin reventar")
    void loQueNoEsCodigoSeBuscaComoNombre() {
        assertThat(directorio.porCodigo("torres silva")).isEmpty();
        assertThat(directorio.buscar("torres silva", 10))
                .extracting(ResumenDeContribuyente::id)
                .contains(jose);
    }

    @Test
    @DisplayName("resuelve varios de golpe, indexados por identificador")
    void resuelveVariosDeGolpe() {
        Map<Long, ResumenDeContribuyente> resumenes = directorio.porIds(Set.of(maria, jose));

        assertThat(resumenes).hasSize(2);
        assertThat(resumenes.get(maria).nombre()).contains("MARIA");
        assertThat(resumenes.get(jose).codigo()).isEqualTo("C-000200");
    }

    @Test
    @DisplayName("un identificador que no existe simplemente no aparece")
    void unIdentificadorInexistenteNoAparece() {
        Map<Long, ResumenDeContribuyente> resumenes = directorio.porIds(Set.of(maria, 999_999L));

        assertThat(resumenes)
                .as(
                        "el predio cuyo titular se dio de baja sigue en la grilla, sin nombre;"
                                + " reventar aqui lo sacaria del listado y escondería el caso")
                .hasSize(1)
                .containsKey(maria);
    }

    @Test
    @DisplayName("pedir cero es un error, no una lista vacia")
    void pedirCeroEsUnError() {
        assertThatThrownBy(() -> directorio.buscar("maria", 0))
                .as("devolver vacio escondería que quien llama calculo mal el tope")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el domicilio sale VIGENTE a la fecha, no el ultimo")
    void elDomicilioSaleVigenteALaFecha() {
        Optional<String> enMarzo = directorio.domicilioFiscalDe(maria, LocalDate.of(2026, 3, 15));
        Optional<String> hoy = directorio.domicilioFiscalDe(maria, HOY);

        assertThat(enMarzo)
                .as(
                        "reimprimir en 2029 la ficha con que se atendio en marzo tiene que dar la"
                                + " direccion de marzo; con «la ultima», el documento no explicaria"
                                + " la notificacion que se hizo (regla 9)")
                .contains("AV. GRAU 100");
        assertThat(hoy).contains("JR. LIMA 250");
    }

    @Test
    @DisplayName("desde otra municipalidad no existe nadie de esta")
    void desdeOtraMunicipalidadNoExisteNadie() {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

        assertThat(directorio.porIds(Set.of(maria, jose))).isEmpty();
        assertThat(directorio.porCodigo("C-000100")).isEmpty();
        assertThat(directorio.buscar("pena garsia maria", 10)).isEmpty();
    }

    // ------------------------------------------------------------------

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

    private static long crearContribuyente(long muni, String codigo, String dni, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.setString(4, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void crearDomicilio(
            long contribuyenteId, String direccion, LocalDate desde, LocalDate hasta)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO domicilio (municipalidad_id, contribuyente_id, tipo,"
                                    + " direccion, vigencia_desde, vigencia_hasta,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, 'FISCAL', ?, ?, ?, 'DJ-SIEMBRA')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, contribuyenteId);
                sentencia.setString(3, direccion);
                sentencia.setObject(4, desde);
                sentencia.setObject(5, hasta);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }
}
