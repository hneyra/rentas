package kamayuk.rentas.contribuyentes.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.CriterioDeBusqueda;
import kamayuk.rentas.contribuyentes.dominio.TipoPersona;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.TipoDocumento;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El padron contra PostgreSQL de verdad, conectado como {@code kamayuk_app}.
 *
 * <p>Los nombres sembrados son <b>inventados</b>. Se eligieron con la forma de los del padron real
 * —apellidos primero, con coma, con enie y con tilde— porque es justamente esa forma la que rompe
 * una busqueda escrita sin pensar en ella.
 */
@DisplayName("RF-011/014 — Padron de contribuyentes")
class ContribuyenteRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    /** Donde escriben las pruebas de escritura, para no mover los totales que las otras cuentan. */
    private static long municipalidadC;

    private static TransactionTemplate transaccion;
    private static ContribuyenteRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("210101", "Municipalidad del padron A");
        municipalidadB = crearMunicipalidad("210102", "Municipalidad del padron B");
        municipalidadC = crearMunicipalidad("210103", "Municipalidad del padron C");

        // El mismo codigo en dos municipalidades: no es un choque, son dos padrones.
        sembrar(
                municipalidadA,
                "00001",
                TipoDocumento.DNI,
                "40123456",
                "PEÑA GARCIA, MARIA DEL CARMEN");
        sembrar(municipalidadA, "00002", TipoDocumento.DNI, "40123457", "QUISPE MAMANI, JOSE LUIS");
        sembrar(
                municipalidadA,
                "00003",
                TipoDocumento.RUC,
                "20123456789",
                "CONSTRUCTORA DEL NORTE S.A.C.");
        sembrar(
                municipalidadA,
                "00004",
                TipoDocumento.DNI,
                "40123458",
                "RODRIGUEZ VILLANUEVA, ANA SOFIA");

        sembrar(
                municipalidadB,
                "00001",
                TipoDocumento.DNI,
                "40123456",
                "OTRO PADRON, PERSONA DISTINTA");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new ContribuyenteRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Aislamiento entre padrones")
    class Aislamiento {

        @Test
        @DisplayName("la prueba se conecta como kamayuk_app, no como superusuario")
        void seConectaComoKamayukApp() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            String usuario =
                    transaccion.execute(
                            estado -> jdbc.sql("SELECT current_user").query(String.class).single());

            assertThat(usuario)
                    .as("con superusuario, RLS se omite y todo lo de abajo pasaria sin verificar")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }

        @Test
        @DisplayName("dos municipalidades usan el mismo codigo sin colisionar, y no se ven")
        void elMismoCodigoEnDosPadrones() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Optional<Contribuyente> desdeA =
                    transaccion.execute(
                            estado -> repositorio.findByCodigo(CodigoContribuyente.de("00001")));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            Optional<Contribuyente> desdeB =
                    transaccion.execute(
                            estado -> repositorio.findByCodigo(CodigoContribuyente.de("00001")));

            assertThat(desdeA).isPresent();
            assertThat(desdeB).isPresent();
            assertThat(desdeA.get().nombreRazonSocial())
                    .as("el codigo 00001 identifica a personas distintas en cada padron")
                    .isNotEqualTo(desdeB.get().nombreRazonSocial());
        }

        @Test
        @DisplayName("el mismo DNI en otra municipalidad no es un duplicado")
        void elMismoDocumentoEnOtraMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Optional<Contribuyente> hallado =
                    transaccion.execute(
                            estado ->
                                    repositorio.findByDocumento(
                                            DocumentoIdentidad.dni("40123456")));

            assertThat(hallado)
                    .as("una persona puede tener predios en dos distritos; son dos padrones")
                    .isPresent();
            assertThat(hallado.get().nombreRazonSocial()).startsWith("OTRO PADRON");
        }

        @Test
        @DisplayName("un contribuyente ajeno no se encuentra ni por identificador")
        void unContribuyenteAjenoNoSeEncuentra() throws SQLException {
            long deB = primeroDe(municipalidadB);
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Optional<Contribuyente> hallado =
                    transaccion.execute(estado -> repositorio.findById(deB));

            assertThat(hallado).isEmpty();
        }
    }

    @Nested
    @DisplayName("RF-014 — Busqueda")
    class Busqueda {

        @Test
        @DisplayName("por codigo exacto")
        void porCodigo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porCodigo("00002"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.totalElementos()).isEqualTo(1);
            assertThat(pagina.contenido().get(0).nombreRazonSocial()).startsWith("QUISPE");
        }

        @Test
        @DisplayName("por numero de documento, sin decir de que tipo es")
        void porNumeroDeDocumentoSinTipo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porNumeroDeDocumento("20123456789"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.contenido())
                    .as("quien atiende teclea el numero del carne, no lo clasifica")
                    .singleElement()
                    .satisfies(c -> assertThat(c.tipoPersona()).isEqualTo(TipoPersona.JURIDICA));
        }

        @Test
        @DisplayName(
                "un nombre mal escrito encuentra a la persona: sin tilde, sin enie y con erratas")
        void laAproximacionEncuentraElNombreMalEscrito() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porNombre("pena garsia maria"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.contenido())
                    .as("si esto devuelve vacio, en ventanilla se da de alta al mismo dos veces")
                    .isNotEmpty();
            assertThat(pagina.contenido().get(0).nombreRazonSocial())
                    .as("y el mas parecido va primero, no el primero por orden alfabetico")
                    .startsWith("PEÑA GARCIA");
        }

        @Test
        @DisplayName("la aproximacion distingue un parecido de uno que no lo es")
        void laAproximacionDistingueUnParecidoDeUnoQueNoLoEs() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porNombre("pena garsia maria"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            List<String> nombres =
                    pagina.contenido().stream().map(Contribuyente::nombreRazonSocial).toList();

            assertThat(nombres)
                    .as(
                            "un umbral demasiado bajo devuelve el padron entero, que en ventanilla"
                                    + " es lo mismo que no encontrar nada")
                    .noneMatch(n -> n.startsWith("QUISPE"))
                    .noneMatch(n -> n.startsWith("CONSTRUCTORA"));
        }

        @Test
        @DisplayName("el orden alfabetico invertido tambien encuentra")
        void elOrdenDeLosApellidosNoImpide() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porNombre("ana sofia rodriguez"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.contenido()).isNotEmpty();
            assertThat(pagina.contenido().get(0).nombreRazonSocial()).startsWith("RODRIGUEZ");
        }

        @Test
        @DisplayName("la busqueda no cruza el padron de al lado")
        void laBusquedaNoCruzaElPadron() {
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.porNombre("pena garsia maria"),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.contenido())
                    .as("la aproximacion tampoco es una puerta trasera al padron ajeno")
                    .noneSatisfy(c -> assertThat(c.nombreRazonSocial()).startsWith("PEÑA"));
        }

        @Test
        @DisplayName("sin criterios devuelve el padron completo, paginado")
        void sinCriterios() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Pagina<Contribuyente> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            CriterioDeBusqueda.todos(),
                                            Paginacion.de(0, 20, "codigo_contribuyente")));

            assertThat(pagina).isNotNull();
            assertThat(pagina.totalElementos()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Escritura")
    class Escritura {

        @Test
        @DisplayName("un alta se guarda con su identificador y se vuelve a leer")
        void unAltaSeGuarda() {
            TenantContext.fijar(new MunicipalidadId(municipalidadC));

            Contribuyente guardado =
                    transaccion.execute(
                            estado ->
                                    repositorio.save(
                                            Contribuyente.nuevo(
                                                    CodigoContribuyente.de("C-0001"),
                                                    DocumentoIdentidad.dni("45678901"),
                                                    TipoPersona.NATURAL,
                                                    "VARGAS LLOSA, JORGE MARIO")));

            assertThat(guardado).isNotNull();
            assertThat(guardado.id()).isNotNull();

            Optional<Contribuyente> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));
            assertThat(releido).isPresent();
            assertThat(releido.get().nombreRazonSocial()).isEqualTo("VARGAS LLOSA, JORGE MARIO");
        }

        @Test
        @DisplayName("dar de baja no borra: la fila sigue ahi, inactiva")
        void darDeBajaNoBorra() {
            TenantContext.fijar(new MunicipalidadId(municipalidadC));

            Contribuyente guardado =
                    transaccion.execute(
                            estado ->
                                    repositorio.save(
                                            Contribuyente.nuevo(
                                                    CodigoContribuyente.de("C-0002"),
                                                    DocumentoIdentidad.dni("45678902"),
                                                    TipoPersona.NATURAL,
                                                    "BAJA PRUEBA, PERSONA")));

            transaccion.execute(estado -> repositorio.save(guardado.dadoDeBaja()));

            Optional<Contribuyente> releido =
                    transaccion.execute(estado -> repositorio.findById(guardado.id()));

            assertThat(releido)
                    .as("su codigo aparece en recibos ya emitidos; borrarla los dejaria huerfanos")
                    .isPresent();
            assertThat(releido.get().activo()).isFalse();
        }

        @Test
        @DisplayName("un codigo repetido dentro del mismo padron lo rechaza la base")
        void unCodigoRepetidoLoRechazaLaBase() {
            TenantContext.fijar(new MunicipalidadId(municipalidadC));

            transaccion.execute(
                    estado ->
                            repositorio.save(
                                    Contribuyente.nuevo(
                                            CodigoContribuyente.de("C-0003"),
                                            DocumentoIdentidad.dni("45678903"),
                                            TipoPersona.NATURAL,
                                            "PRIMERO CON EL CODIGO")));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.save(
                                                            Contribuyente.nuevo(
                                                                    CodigoContribuyente.de(
                                                                            "C-0003"),
                                                                    DocumentoIdentidad.dni(
                                                                            "45678904"),
                                                                    TipoPersona.NATURAL,
                                                                    "SEGUNDO CON EL MISMO"))))
                    .as("la barrera de verdad es la restriccion de la tabla, no el caso de uso")
                    .isNotNull();
        }

        @Test
        @DisplayName("un documento repetido dentro del mismo padron lo rechaza la base")
        void unDocumentoRepetidoLoRechazaLaBase() {
            TenantContext.fijar(new MunicipalidadId(municipalidadC));

            transaccion.execute(
                    estado ->
                            repositorio.save(
                                    Contribuyente.nuevo(
                                            CodigoContribuyente.de("C-0004"),
                                            DocumentoIdentidad.dni("45678905"),
                                            TipoPersona.NATURAL,
                                            "PRIMERO CON EL DNI")));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.save(
                                                            Contribuyente.nuevo(
                                                                    CodigoContribuyente.de(
                                                                            "C-0005"),
                                                                    DocumentoIdentidad.dni(
                                                                            "45678905"),
                                                                    TipoPersona.NATURAL,
                                                                    "SEGUNDO CON EL MISMO DNI"))))
                    .isNotNull();
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

    private static void sembrar(
            long municipalidadId, String codigo, TipoDocumento tipo, String numero, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, 'siembra')")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, tipo.name());
                sentencia.setString(4, numero);
                sentencia.setString(5, tipo == TipoDocumento.RUC ? "JURIDICA" : "NATURAL");
                sentencia.setString(6, nombre);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long primeroDe(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                            app.prepareStatement(
                                    "SELECT id FROM contribuyente ORDER BY id LIMIT 1");
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }
}
