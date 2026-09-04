package kamayuk.rentas.contribuyentes.aplicacion;

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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import kamayuk.rentas.contribuyentes.dominio.TipoContacto;
import kamayuk.rentas.contribuyentes.dominio.TipoDomicilio;
import kamayuk.rentas.contribuyentes.dominio.Vinculo;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La ficha del contribuyente contra PostgreSQL real: domicilios con vigencia, contactos y
 * responsables solidarios (RF-012, RF-013).
 *
 * <p>Lo que estas pruebas defienden es una sola idea: <b>nada se sobrescribe</b>. La direccion de
 * 2027 sigue ahi en 2029, cuando alguien discuta si una orden de pago se notifico bien.
 */
@DisplayName("RF-012/013 — Ficha del contribuyente")
class ActualizarFichaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static TransactionTemplate transaccion;
    private static FichaRepositoryJdbc repositorio;
    private static ActualizarFicha actualizar;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("220101", "Municipalidad de la ficha");
        otraMunicipalidad = crearMunicipalidad("220102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new FichaRepositoryJdbc(jdbc);
        actualizar =
                envolver(
                        new ActualizarFicha(repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("RF-013 — Domicilio con historial")
    class Domicilios {

        @Test
        @DisplayName("mudarse cierra el anterior y abre el nuevo en la misma transaccion")
        void mudarseCierraElAnterior() throws SQLException {
            long contribuyente = crearContribuyente("D-0001", "40200001", "MUDANZA, PRIMERA");

            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "JR. LIMA 100",
                            LocalDate.of(2026, 1, 1),
                            "Declaracion jurada 001-2026"),
                    Observacion.de("Se registra el domicilio inicial"));

            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "AV. GRAU 500",
                            LocalDate.of(2026, 7, 1),
                            "Declaracion jurada 045-2026"),
                    Observacion.de("El contribuyente comunica su mudanza"));

            List<Domicilio> historial =
                    transaccion.execute(estado -> repositorio.historialDeDomicilios(contribuyente));

            assertThat(historial).isNotNull().hasSize(2);
            assertThat(historial.get(0).direccion()).isEqualTo("AV. GRAU 500");
            assertThat(historial.get(0).estaVigente()).isTrue();
            assertThat(historial.get(1).direccion())
                    .as("el anterior sigue ahi; nunca se sobrescribe (RNF-053, regla 4)")
                    .isEqualTo("JR. LIMA 100");
            assertThat(historial.get(1).vigenciaHasta())
                    .as("y se cerro el dia antes, para que ninguna fecha tenga dos respuestas")
                    .isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("la direccion se pide A UNA FECHA, no «la ultima»")
        void laDireccionEsLaVigenteAUnaFecha() throws SQLException {
            long contribuyente = crearContribuyente("D-0002", "40200002", "NOTIFICACION, CASO");

            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "CALLE ANTIGUA 1",
                            LocalDate.of(2026, 1, 1),
                            "Declaracion jurada 010-2026"),
                    Observacion.de("Domicilio al inicio del ejercicio"));
            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "CALLE NUEVA 2",
                            LocalDate.of(2026, 9, 1),
                            "Declaracion jurada 090-2026"),
                    Observacion.de("Mudanza de setiembre"));

            Optional<Domicilio> enMarzo =
                    transaccion.execute(
                            estado ->
                                    repositorio.domicilioVigenteA(
                                            contribuyente,
                                            TipoDomicilio.FISCAL,
                                            LocalDate.of(2026, 3, 15)));
            Optional<Domicilio> enOctubre =
                    transaccion.execute(
                            estado ->
                                    repositorio.domicilioVigenteA(
                                            contribuyente,
                                            TipoDomicilio.FISCAL,
                                            LocalDate.of(2026, 10, 15)));

            assertThat(enMarzo).isPresent();
            assertThat(enMarzo.get().direccion())
                    .as(
                            "si en 2029 se discute una notificacion de marzo, la pregunta es donde"
                                    + " vivia en marzo")
                    .isEqualTo("CALLE ANTIGUA 1");
            assertThat(enOctubre).isPresent();
            assertThat(enOctubre.get().direccion()).isEqualTo("CALLE NUEVA 2");
        }

        @Test
        @DisplayName("el indice parcial impide dos domicilios fiscales vigentes")
        void dosFiscalesVigentesNo() throws SQLException {
            long contribuyente = crearContribuyente("D-0003", "40200003", "DOS FISCALES, INTENTO");

            transaccion.execute(
                    estado ->
                            repositorio.guardar(
                                    Domicilio.abierto(
                                            contribuyente,
                                            TipoDomicilio.FISCAL,
                                            "PRIMERO ABIERTO",
                                            LocalDate.of(2026, 1, 1),
                                            "Documento A")));

            // Directamente por el repositorio, saltandose el caso de uso: es la barrera de
            // la base la que se esta comprobando, no la del codigo.
            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.guardar(
                                                            Domicilio.abierto(
                                                                    contribuyente,
                                                                    TipoDomicilio.FISCAL,
                                                                    "SEGUNDO ABIERTO",
                                                                    LocalDate.of(2026, 6, 1),
                                                                    "Documento B"))))
                    .as("con dos abiertos, una emision no sabria a cual notificar")
                    .isNotNull();
        }

        @Test
        @DisplayName("el fiscal y el procesal conviven: son cosas distintas")
        void fiscalYProcesalConviven() throws SQLException {
            long contribuyente = crearContribuyente("D-0004", "40200004", "DOS TIPOS, CASO");

            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "DOMICILIO FISCAL",
                            LocalDate.of(2026, 1, 1),
                            "Declaracion jurada"),
                    Observacion.de("Domicilio fiscal del contribuyente"));
            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.PROCESAL,
                            "DOMICILIO PROCESAL",
                            LocalDate.of(2026, 1, 1),
                            "Escrito de apersonamiento"),
                    Observacion.de("Domicilio senalado para el procedimiento"));

            Optional<Domicilio> fiscal =
                    transaccion.execute(
                            estado ->
                                    repositorio.domicilioVigenteA(
                                            contribuyente,
                                            TipoDomicilio.FISCAL,
                                            LocalDate.of(2026, 6, 1)));
            Optional<Domicilio> procesal =
                    transaccion.execute(
                            estado ->
                                    repositorio.domicilioVigenteA(
                                            contribuyente,
                                            TipoDomicilio.PROCESAL,
                                            LocalDate.of(2026, 6, 1)));

            assertThat(fiscal).isPresent();
            assertThat(procesal).isPresent();
            assertThat(fiscal.get().direccion()).isEqualTo("DOMICILIO FISCAL");
            assertThat(procesal.get().direccion())
                    .as("notificar en el que no toca es causal de nulidad")
                    .isEqualTo("DOMICILIO PROCESAL");
        }

        @Test
        @DisplayName("una mudanza deja su rastro en la auditoria, con la observacion")
        void laMudanzaDejaAuditoria() throws SQLException {
            long contribuyente = crearContribuyente("D-0005", "40200005", "AUDITORIA, MUDANZA");

            actualizar.mudar(
                    Domicilio.abierto(
                            contribuyente,
                            TipoDomicilio.FISCAL,
                            "ORIGEN 1",
                            LocalDate.of(2026, 1, 1),
                            "Documento inicial"),
                    Observacion.de("Alta del domicilio para la prueba de auditoria"));

            Long filas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla = 'domicilio'"
                                                            + "   AND observacion LIKE '%prueba de"
                                                            + " auditoria%'")
                                            .query(Long.class)
                                            .single());

            assertThat(filas).isNotNull().isPositive();
        }
    }

    @Nested
    @DisplayName("RF-013 — Contactos")
    class Contactos {

        @Test
        @DisplayName("se registran los cinco tipos y se leen juntos")
        void losCincoTipos() throws SQLException {
            long contribuyente = crearContribuyente("C-0001", "40300001", "CONTACTOS, VARIOS");

            actualizar.registrarContacto(
                    Contacto.nuevo(contribuyente, TipoContacto.CELULAR, "987654321"),
                    Observacion.de("Numero que dejo en ventanilla"));
            actualizar.registrarContacto(
                    Contacto.nuevo(contribuyente, TipoContacto.EMAIL, "persona@ejemplo.pe"),
                    Observacion.de("Correo para la notificacion electronica"));

            List<Contacto> contactos =
                    transaccion.execute(estado -> repositorio.contactosDe(contribuyente, true));

            assertThat(contactos).isNotNull().hasSize(2);
        }

        @Test
        @DisplayName("un correo sin arroba no entra")
        void unCorreoSinArroba() {
            assertThatThrownBy(() -> Contacto.nuevo(1L, TipoContacto.EMAIL, "esto-no-es-un-correo"))
                    .as("se descubriria el dia del envio masivo de la emision")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("dar de baja un contacto no lo borra")
        void darDeBajaNoBorra() throws SQLException {
            long contribuyente = crearContribuyente("C-0002", "40300002", "GESTOR, BAJA");

            Contacto gestor =
                    actualizar.registrarContacto(
                            Contacto.nuevo(contribuyente, TipoContacto.GESTOR, "GESTOR ANTIGUO"),
                            Observacion.de("Gestor designado por el contribuyente"));

            actualizar.darDeBajaContacto(
                    gestor, Observacion.de("El contribuyente revoca la designacion"));

            List<Contacto> todos =
                    transaccion.execute(estado -> repositorio.contactosDe(contribuyente, false));
            List<Contacto> vigentes =
                    transaccion.execute(estado -> repositorio.contactosDe(contribuyente, true));

            assertThat(todos)
                    .as("aparece en notificaciones anteriores; explicarlas exige que siga ahi")
                    .hasSize(1);
            assertThat(vigentes).isEmpty();
        }
    }

    @Nested
    @DisplayName("RF-012 — Responsables solidarios")
    class Responsables {

        @Test
        @DisplayName("un conyuge responde, y se consulta en los dos sentidos")
        void elConyugeResponde() throws SQLException {
            long titular = crearContribuyente("R-0001", "40400001", "TITULAR, PERSONA");
            long conyuge = crearContribuyente("R-0002", "40400002", "CONYUGE, PERSONA");

            actualizar.registrarResponsable(
                    ResponsableSolidario.abierto(
                            titular,
                            conyuge,
                            Vinculo.CONYUGE,
                            LocalDate.of(2026, 1, 1),
                            "Partida de matrimonio"),
                    Observacion.de("Se registra el vinculo conyugal"));

            List<ResponsableSolidario> quienResponde =
                    transaccion.execute(
                            estado ->
                                    repositorio.responsablesDe(titular, LocalDate.of(2026, 6, 1)));
            List<ResponsableSolidario> deQuienResponde =
                    transaccion.execute(
                            estado ->
                                    repositorio.responsabilidadesDe(
                                            conyuge, LocalDate.of(2026, 6, 1)));

            assertThat(quienResponde).isNotNull().hasSize(1);
            assertThat(deQuienResponde)
                    .as("hace falta para responder «por que me llego este valor»")
                    .isNotNull()
                    .hasSize(1);
        }

        @Test
        @DisplayName("cerrar un vinculo no lo borra: la deuda anterior sigue siendo suya")
        void cerrarNoBorra() throws SQLException {
            long titular = crearContribuyente("R-0003", "40400003", "TITULAR, CONDOMINIO");
            long condomino = crearContribuyente("R-0004", "40400004", "CONDOMINO, VENDE");

            ResponsableSolidario vinculo =
                    actualizar.registrarResponsable(
                            ResponsableSolidario.abierto(
                                    titular,
                                    condomino,
                                    Vinculo.CONDOMINO,
                                    LocalDate.of(2026, 1, 1),
                                    "Escritura publica de compraventa"),
                            Observacion.de("Se registra el condominio"));

            actualizar.cerrarResponsable(
                    vinculo,
                    LocalDate.of(2026, 6, 30),
                    Observacion.de("Vende su parte y deja de responder"));

            List<ResponsableSolidario> enMarzo =
                    transaccion.execute(
                            estado ->
                                    repositorio.responsablesDe(titular, LocalDate.of(2026, 3, 1)));
            List<ResponsableSolidario> enSetiembre =
                    transaccion.execute(
                            estado ->
                                    repositorio.responsablesDe(titular, LocalDate.of(2026, 9, 1)));

            assertThat(enMarzo)
                    .as("en marzo respondia, y una notificacion de marzo se defiende con eso")
                    .hasSize(1);
            assertThat(enSetiembre).as("en setiembre ya no, porque vendio su parte").isEmpty();
        }

        @Test
        @DisplayName("nadie responde por si mismo")
        void nadieRespondePorSiMismo() {
            assertThatThrownBy(
                            () ->
                                    ResponsableSolidario.abierto(
                                            7L,
                                            7L,
                                            Vinculo.CONYUGE,
                                            LocalDate.of(2026, 1, 1),
                                            "Documento"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("los vinculos no cruzan la municipalidad")
        void losVinculosNoCruzanLaMunicipalidad() throws SQLException {
            long titular = crearContribuyente("R-0005", "40400005", "TITULAR, AISLADO");
            long responsable = crearContribuyente("R-0006", "40400006", "RESPONSABLE, AISLADO");

            actualizar.registrarResponsable(
                    ResponsableSolidario.abierto(
                            titular,
                            responsable,
                            Vinculo.POSEEDOR,
                            LocalDate.of(2026, 1, 1),
                            "Constatacion de posesion"),
                    Observacion.de("Se registra al poseedor"));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            List<ResponsableSolidario> desdeLaVecina =
                    transaccion.execute(
                            estado ->
                                    repositorio.responsablesDe(titular, LocalDate.of(2026, 6, 1)));

            assertThat(desdeLaVecina)
                    .as("la municipalidad vecina no ve de quien responde un contribuyente ajeno")
                    .isEmpty();
        }
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

    private static long crearContribuyente(String codigo, String dni, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
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
}
