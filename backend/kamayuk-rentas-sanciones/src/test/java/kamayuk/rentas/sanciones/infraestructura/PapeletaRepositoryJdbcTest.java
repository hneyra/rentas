package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
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
 * Las papeletas —de las dos familias, #46 y #47— contra PostgreSQL de verdad, conectado como {@code
 * kamayuk_app}.
 *
 * <p>El AC "reimprimir una papeleta de hace tres años devuelve los mismos seis importes" se
 * verifica aquí releyendo la fila tal cual quedó guardada: nada en el camino de lectura recalcula
 * nada.
 */
@DisplayName("#46/#47 — Papeletas")
class PapeletaRepositoryJdbcTest {

    private static final LocalDate FECHA = LocalDate.of(2023, 3, 15);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static PapeletaRepositoryJdbc repositorio;

    /**
     * El otro camino que publica el estado de una papeleta (#259).
     *
     * <p>Está aquí porque es el que el issue nombra: una fila con un valor que el enumerado no
     * tuviera <b>revienta el padrón entero</b> con {@code IllegalArgumentException}, no devuelve
     * una lista corta. Comprobarlo sólo por {@code porNumero} mediría una fila, no el padrón.
     */
    private static PadronDePapeletasRepositoryJdbc padron;

    private static JdbcClient jdbc;

    /**
     * A quién se le cobra la multa en las papeletas de tránsito de esta prueba.
     *
     * <p>{@code papeleta.obligado_id} es {@code NOT NULL} y tiene clave foránea desde #50 (V41 §1):
     * sin un contribuyente de verdad detrás, ningún {@code INSERT} de papeleta entra.
     */
    private static long obligadoDeA;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250601", "Municipalidad de papeletas A");
        municipalidadB = crearMunicipalidad("250602", "Municipalidad de papeletas B");
        obligadoDeA = crearContribuyente(municipalidadA, "19000001");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new PapeletaRepositoryJdbc(jdbc);
        padron = new PadronDePapeletasRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("inspector.transito", null, null));
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
        @DisplayName("reimprimir devuelve los mismos seis importes, sin recalcular nada")
        void reimprimirDevuelveLosMismosSeisImportes() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0001");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(papeletaTransitoDe("PT-0001", codigoId)));

            Papeleta reimpresa =
                    transaccion.execute(estado -> repositorio.porNumero("PT-0001")).orElseThrow();

            assertThat(reimpresa.baseImponible()).isEqualTo(Dinero.de("4950"));
            assertThat(reimpresa.porcentajeInfraccion()).isEqualTo(Alicuota.de("8"));
            assertThat(reimpresa.importeInfraccion()).isEqualTo(Dinero.de("396"));
            assertThat(reimpresa.porcentajeACobrar()).isEqualTo(Alicuota.de("100"));
            assertThat(reimpresa.importeAPagar()).isEqualTo(Dinero.de("396"));
            assertThat(reimpresa.importeConBeneficio()).isEqualTo(Dinero.de("198"));
            assertThat(reimpresa.id()).isEqualTo(guardada.id());
        }

        @Test
        @DisplayName("cambiar el numero deja traza y no cambia el id ni el desglose")
        void cambiarElNumeroDejaTraza() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0002");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(papeletaTransitoDe("PT-0002", codigoId)));

            Papeleta renumerada =
                    transaccion.execute(
                            estado ->
                                    repositorio.cambiarNumero(
                                            guardada.id(),
                                            "PT-0002-B",
                                            "correccion de digitacion"));

            assertThat(renumerada.id()).isEqualTo(guardada.id());
            assertThat(renumerada.numero()).isEqualTo("PT-0002-B");
            assertThat(renumerada.importeAPagar()).isEqualTo(guardada.importeAPagar());

            long trazas = transaccion.execute(estado -> contarTrazas(guardada.id()));
            assertThat(trazas).isEqualTo(1L);
        }

        @Test
        @DisplayName("una papeleta administrativa se guarda sin notificacion previa (#47 AC1)")
        void unaPapeletaAdministrativaSeGuardaSinNotificacionPrevia() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-01");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000001");

            Papeleta guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            papeletaAdministrativaDe(
                                                    "PA-0001",
                                                    codigoId,
                                                    contribuyenteId,
                                                    null,
                                                    null)));

            assertThat(guardada.id()).isNotNull();
            assertThat(guardada.familia()).isEqualTo(Familia.ADMINISTRATIVA);
            assertThat(guardada.notificacionPreviaId()).isNull();
            assertThat(guardada.placa()).isNull();
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("la busqueda por placa no cruza la municipalidad")
        void laBusquedaPorPlacaNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0003");
            transaccion.execute(
                    estado -> repositorio.insertar(papeletaTransitoDe("PT-0003", codigoId)));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            Pagina<Papeleta> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            criterioTransito("ABC-123"),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        /**
         * Las tres que ya no se deben quedan fuera; las cuatro que sí, dentro (#259).
         *
         * <h2>Esto era un VERDE FALSO, y está medido</h2>
         *
         * <p>Hasta #259 esta prueba sembraba <b>una</b> papeleta —{@code IMPUESTA}, que es lo único
         * que este sistema escribe— y comprobaba que {@code soloPendientes} la devolvía. Su nombre
         * prometía «excluye PAGADA, ANULADA y PRESCRITA» y no creaba <b>ninguna</b> de las tres.
         * Medido sobre {@code d084bb6}: borrado entero el {@code condiciones.add("p.estado NOT IN
         * …")} de {@code PapeletaRepositoryJdbc}, esta prueba salió <b>PASSED</b> y {@code
         * :kamayuk-rentas-sanciones:test} entero, {@code BUILD SUCCESSFUL}. El filtro se podía
         * borrar y nadie se enteraba.
         *
         * <p>Ahora siembra las <b>siete</b>, que es lo único que distingue un filtro que descarta
         * de uno que no descarta nada. Los seis estados que no son {@code IMPUESTA} se ponen por
         * {@code UPDATE} directo porque es lo único que hay: no los escribe ningún camino de
         * producción, los trae un <b>padrón migrado</b> ({@link #comoUnPadronMigrado}).
         */
        @Test
        @DisplayName(
                "soloPendientes deja fuera PAGADA, ANULADA y PRESCRITA, y dentro las otras cuatro")
        void soloPendientesExcluyeLasCerradas() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0004");
            sembrarUnPadronMigrado("PT-0004", codigoId);

            Pagina<Papeleta> pendientes =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.TRANSITO,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    "G-0004",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    true),
                                            Paginacion.de(0, 20, "numero")));

            assertThat(pendientes.contenido())
                    .as("las cuatro que todavia se deben, y ninguna de las tres cerradas")
                    .extracting(Papeleta::estado)
                    .containsExactlyInAnyOrder(
                            EstadoDePapeleta.IMPUESTA,
                            EstadoDePapeleta.NOTIFICADA,
                            EstadoDePapeleta.RESUELTA,
                            EstadoDePapeleta.COACTIVA);
        }

        @Test
        @DisplayName("la busqueda de administrativa no devuelve papeletas de transito")
        void laBusquedaDeAdministrativaNoDevuelvePapeletasDeTransito() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoTransito = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0005");
            transaccion.execute(
                    estado -> repositorio.insertar(papeletaTransitoDe("PT-0005", codigoTransito)));

            Pagina<Papeleta> administrativas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(administrativas.totalElementos()).isZero();
        }

        @Test
        @DisplayName("busca la papeleta administrativa por el documento del contribuyente")
        void buscaLaPapeletaAdministrativaPorElDocumentoDelContribuyente() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-02");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000002");
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    papeletaAdministrativaDe(
                                            "PA-0002", codigoId, contribuyenteId, null, null)));

            Pagina<Papeleta> encontradas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    "10000002",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(encontradas.totalElementos()).isEqualTo(1);
        }

        @Test
        @DisplayName("busca por el codigo del catalogo, con el JOIN a codigo_infraccion")
        void buscaPorElCodigoDelCatalogo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.ADMINISTRATIVA, "G-ADM-03");
            long contribuyenteId = crearContribuyente(municipalidadA, "10000003");
            transaccion.execute(
                    estado ->
                            repositorio.insertar(
                                    papeletaAdministrativaDe(
                                            "PA-0003", codigoId, contribuyenteId, null, null)));

            Pagina<Papeleta> encontradas =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.ADMINISTRATIVA,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    "G-ADM-03",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "fechaInfraccion")));

            assertThat(encontradas.totalElementos()).isEqualTo(1);
        }
    }

    /**
     * Los siete valores son el vocabulario de LECTURA de una columna con escritor ajeno (#259).
     *
     * <h2>Qué decidió #259, y qué medida la sostiene</h2>
     *
     * <p>#243 midió que la producción escribe <b>uno</b> —{@code IMPUESTA}— y dejó abierto si los
     * otros seis salían del enumerado y del {@code CHECK}. #259 decidió que <b>no salen</b>, y por
     * un motivo que {@code V19} no tenía enfrente: {@code acta_fiscalizacion} sólo la llena este
     * sistema, y {@code papeleta} es la tabla que un <b>padrón migrado</b> trae llena. Un {@code
     * 'PAGADA'} suyo es un hecho cierto del sistema anterior, no una promesa vacía, y bajo {@code
     * FORCE ROW LEVEL SECURITY} ninguna migración puede normalizarlo: el migrador corre sin
     * contexto de tenant.
     *
     * <p><b>Lo que faltaba era que eso pudiera fallar.</b> Medido sobre {@code d084bb6}, con {@code
     * RESUELTA} —el único de los siete sin una sola referencia en Java fuera del propio enumerado,
     * así que se borra sin romper la compilación— fuera del enumerado: {@code ./gradlew build}
     * salió <b>BUILD SUCCESSFUL</b>, exit 0, 3 080 pruebas. Ni una guarda lo vio.
     *
     * <h2>Y por eso la siembra es un padrón migrado y no una transición</h2>
     *
     * <p>{@link #comoUnPadronMigrado} pone el estado por {@code UPDATE} directo. No es un atajo
     * para no escribir el caso de uso: <b>no hay caso de uso</b>, y no lo va a haber para estos
     * seis valores —#243 dejó escrito valor por valor de qué hecho se deriva cada uno—. Lo que esta
     * prueba necesita es exactamente una fila como la que trae un padrón migrado.
     */
    @Nested
    @DisplayName("El vocabulario de lectura (#259)")
    class VocabularioDeLectura {

        /**
         * Los dos vocabularios dicen lo mismo, en las dos direcciones.
         *
         * <p>Retirar un valor del enumerado sale rojo por aquí; estrecharle el {@code CHECK} —que
         * es la otra mitad que #243 dejó abierta— también, y además por {@link
         * #laRenumeracionDeIssue46AlcanzaALasSiete}.
         */
        @Test
        @DisplayName("el enumerado y papeleta_estado_check admiten exactamente lo mismo")
        void elEnumeradoYElCheckDicenLoMismo() {
            List<String> delCheck = estadosQueAdmiteElCheck();

            assertThat(delCheck)
                    .as("el CHECK no admite ningun estado que el enumerado no sepa leer")
                    .containsExactlyInAnyOrderElementsOf(
                            Stream.of(EstadoDePapeleta.values()).map(Enum::name).toList());
        }

        /**
         * Un padrón migrado en los siete estados se lee entero, por los dos caminos.
         *
         * <p>El padrón es el que importa: no devuelve una lista corta cuando encuentra un valor que
         * no sabe leer, <b>revienta</b> con {@code IllegalArgumentException} y se lleva la página
         * entera. Por eso se pide sin filtrar por estado y se cuentan las siete.
         */
        @Test
        @DisplayName("un padron migrado en los siete estados se lee entero, y por los dos caminos")
        void losSieteSeLeenPorLosDosCaminos() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0259-A");
            Map<String, String> sembradas = sembrarUnPadronMigrado("PV-A", codigoId);

            for (Map.Entry<String, String> fila : sembradas.entrySet()) {
                Papeleta leida =
                        transaccion.execute(
                                estado ->
                                        repositorio
                                                .porNumero(Familia.TRANSITO, fila.getValue())
                                                .orElseThrow());
                assertThat(leida.estado().name())
                        .as("papeleta %s leida por porNumero", fila.getValue())
                        .isEqualTo(fila.getKey());
            }

            Pagina<PapeletaDelPadron> entero =
                    transaccion.execute(
                            estado ->
                                    padron.buscar(
                                            padronDelCodigo("G-0259-A", false),
                                            Paginacion.de(0, 50, "numero")));

            assertThat(entero.contenido())
                    .as("el padron entero: si uno de los siete no se supiera leer, no habria lista")
                    .extracting(fila -> fila.estado().name())
                    .containsExactlyInAnyOrderElementsOf(sembradas.keySet());
        }

        /**
         * La renumeración de #46 alcanza a las siete, que es el punto 3 del issue.
         *
         * <p>Un {@code CHECK} estrechado volvería intocables las filas migradas: un {@code NOT
         * VALID} sigue comprobando <b>toda fila que se actualiza</b>, así que {@code UPDATE
         * papeleta SET numero} sobre una papeleta que constase {@code PAGADA} fallaría. Aquí se
         * ejerce sobre las siete, de modo que estrechar el {@code CHECK} deja de ser un cambio que
         * pasa en verde.
         */
        @Test
        @DisplayName("la renumeracion de #46 alcanza a las siete, tambien a las migradas")
        void laRenumeracionDeIssue46AlcanzaALasSiete() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0259-B");
            Map<String, String> sembradas = sembrarUnPadronMigrado("PV-B", codigoId);

            for (Map.Entry<String, String> fila : sembradas.entrySet()) {
                Papeleta antes =
                        transaccion.execute(
                                estado ->
                                        repositorio
                                                .porNumero(Familia.TRANSITO, fila.getValue())
                                                .orElseThrow());
                Papeleta renumerada =
                        transaccion.execute(
                                estado ->
                                        repositorio.cambiarNumero(
                                                antes.id(),
                                                fila.getValue() + "-R",
                                                "correccion de digitacion"));

                assertThat(renumerada.numero()).isEqualTo(fila.getValue() + "-R");
                assertThat(renumerada.estado().name())
                        .as("y renumerar no mueve el estado de una papeleta migrada")
                        .isEqualTo(fila.getKey());
            }
        }

        /**
         * Por qué estrechar el {@code CHECK} volvería <b>intocables</b> las filas migradas.
         *
         * <p>Es el punto 3 de #259, y hasta aquí era una afirmación sobre PostgreSQL y no una
         * medida. Se mide sobre una tabla de usar y tirar porque lo que se comprueba <b>es el
         * motor</b>: que un {@code CHECK} declarado {@code NOT VALID} no mire las filas que ya
         * están —por eso {@code V10}, {@code V19} y {@code V20} lo usan— pero sí vuelva a
         * comprobarlas en cuanto se actualiza <b>cualquier</b> columna de la fila, aunque no sea la
         * suya.
         *
         * <p>De ahí que la renumeración de #46 —{@code UPDATE papeleta SET numero}, que no toca
         * {@code estado}— fallaría sobre una papeleta migrada que constase {@code PAGADA}. Ese es
         * el coste de estrechar el {@code CHECK}, y es lo que lo descarta.
         */
        @Test
        @DisplayName("un CHECK NOT VALID no mira la fila que ya esta, pero SI la que se actualiza")
        void elCheckNotValidVuelveIntocableLaFilaMigrada() throws SQLException {
            try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                    Statement sentencia = owner.createStatement()) {
                sentencia.execute(
                        "CREATE TEMPORARY TABLE ensayo_del_check"
                                + " (id int, numero text, estado text)");
                sentencia.execute("INSERT INTO ensayo_del_check VALUES (1, 'PT-1', 'PAGADA')");
                sentencia.execute(
                        "ALTER TABLE ensayo_del_check ADD CONSTRAINT ensayo_estado_ck"
                                + " CHECK (estado IN ('IMPUESTA')) NOT VALID");

                try (ResultSet quedan =
                        sentencia.executeQuery("SELECT count(*) FROM ensayo_del_check")) {
                    quedan.next();
                    assertThat(quedan.getLong(1))
                            .as("el NOT VALID no miro la fila migrada: por eso se puede declarar")
                            .isEqualTo(1L);
                }

                assertThatThrownBy(
                                () ->
                                        sentencia.executeUpdate(
                                                "UPDATE ensayo_del_check SET numero = 'PT-1-B'"
                                                        + " WHERE id = 1"))
                        .as("pero renumerarla —sin tocar `estado`— ya no se puede")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ensayo_estado_ck");
            }
        }

        /**
         * Lo que el filtro descarta y lo que la API publica como {@code pendiente} dicen lo mismo.
         *
         * <p>Hasta #259 la lista de «ya no se debe» estaba <b>tres</b> veces —dos en SQL y una en
         * Java, y la de Java es la que {@code PapeletaDelPadronResource} publica—. Ahora las tres
         * se derivan de {@code EstadoDePapeleta.seDebe()}; esta prueba es la que comprueba que
         * siguen diciendo lo mismo sobre <b>cada uno</b> de los siete estados, que es donde una
         * cuarta copia escrita a mano volvería a salir rojo.
         */
        @Test
        @DisplayName(
                "el filtro de pendientes y el `pendiente` que publica la API coinciden en los siete")
        void elFiltroYElCampoPublicadoCoinciden() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long codigoId = crearCodigo(municipalidadA, Familia.TRANSITO, "G-0259-C");
            sembrarUnPadronMigrado("PV-C", codigoId);

            List<PapeletaDelPadron> todas =
                    transaccion
                            .execute(
                                    estado ->
                                            padron.buscar(
                                                    padronDelCodigo("G-0259-C", false),
                                                    Paginacion.de(0, 50, "numero")))
                            .contenido();
            List<PapeletaDelPadron> pendientes =
                    transaccion
                            .execute(
                                    estado ->
                                            padron.buscar(
                                                    padronDelCodigo("G-0259-C", true),
                                                    Paginacion.de(0, 50, "numero")))
                            .contenido();

            assertThat(todas).hasSize(estadosQueAdmiteElCheck().size());
            assertThat(pendientes)
                    .as("lo que el WHERE deja pasar es exactamente lo que estaPendiente() afirma")
                    .extracting(PapeletaDelPadron::numero)
                    .containsExactlyInAnyOrderElementsOf(
                            todas.stream()
                                    .filter(PapeletaDelPadron::estaPendiente)
                                    .map(PapeletaDelPadron::numero)
                                    .toList());
            assertThat(pendientes)
                    .as(
                            "y no son ni las siete ni ninguna: sin esto, dos listas vacias coincidirian")
                    .hasSize(4);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Siembra una papeleta por cada estado que el enumerado declara, como un padrón MIGRADO.
     *
     * <p>Todas nacen {@code IMPUESTA} —es lo único que {@code Papeleta.nuevaTransito} sabe poner— y
     * las otras se mueven con {@link #comoUnPadronMigrado}. Devuelve el número de cada una indexado
     * por su estado.
     *
     * <p><b>La lista de estados sale del {@code CHECK} de la base y NO de {@code
     * EstadoDePapeleta.values()}</b>, y eso es lo que hace que la guarda muerda. Derivándola del
     * enumerado, retirar un valor lo quitaría también de la siembra y la prueba seguiría verde
     * leyendo seis filas: justo lo contrario de lo que hay que medir. Saliendo del {@code CHECK},
     * la fila migrada se siembra igual y el mapeo revienta con {@code IllegalArgumentException},
     * que es la avería que este issue describe.
     *
     * @param prefijo el que llevan los números, para que cada prueba siembre los suyos
     * @param codigoInfraccionId el código del catálogo con el que luego se las vuelve a encontrar
     */
    private static Map<String, String> sembrarUnPadronMigrado(
            String prefijo, long codigoInfraccionId) {
        Map<String, String> numeros = new LinkedHashMap<>();
        for (String estado : estadosQueAdmiteElCheck()) {
            String numero = prefijo + "-" + estado;
            transaccion.execute(
                    ignorado ->
                            repositorio.insertar(papeletaTransitoDe(numero, codigoInfraccionId)));
            if (!EstadoDePapeleta.IMPUESTA.name().equals(estado)) {
                comoUnPadronMigrado(numero, estado);
            }
            numeros.put(estado, numero);
        }
        return numeros;
    }

    /**
     * Pone {@code papeleta.estado} por SQL directo, que es lo que hace un padrón MIGRADO.
     *
     * <p>No es un atajo: <b>no hay camino de producción</b> que escriba estos seis valores —el
     * único {@code UPDATE papeleta} de {@code src/main} es {@code SET numero} (#46, #243)—, y
     * ninguno de los seis va a tenerlo, porque los cuatro derivables se derivan de otros hechos y
     * los dos que no —{@code ANULADA} y {@code PRESCRITA}— son actos que todavía no existen.
     * Escribirlo así es exactamente la fila que este sistema tiene que <b>poder leer</b>, que es lo
     * que #259 decidió y esta prueba fija.
     *
     * <p>Se conecta como {@code kamayuk_app}, no como el dueño: {@code V20} le dejó justo {@code
     * GRANT UPDATE (numero, estado)}, así que esta escritura la puede hacer la aplicación y no hace
     * falta saltarse RLS para sembrarla.
     */
    private static void comoUnPadronMigrado(String numero, String estado) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE papeleta SET estado = ?"
                                    + " WHERE familia = 'TRANSITO' AND numero = ?")) {
                sentencia.setString(1, estado);
                sentencia.setString(2, numero);
                if (sentencia.executeUpdate() != 1) {
                    throw new IllegalStateException("No se sembro la papeleta migrada " + numero);
                }
                app.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /**
     * Los estados que {@code papeleta_estado_check} admite, leídos del catálogo de PostgreSQL.
     *
     * <p>Se leen y no se escriben a mano: una lista transcrita aquí sería una <b>cuarta</b> copia
     * del mismo vocabulario, y entonces estrechar el {@code CHECK} en una migración pasaría en
     * verde mientras la copia de la prueba siguiera diciendo los siete.
     */
    private static List<String> estadosQueAdmiteElCheck() {
        String definicion =
                jdbc.sql(
                                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                        + " WHERE conname = 'papeleta_estado_check'")
                        .query(String.class)
                        .single();
        Matcher literales = Pattern.compile("'([A-Z_]+)'").matcher(definicion);
        List<String> estados = new ArrayList<>();
        while (literales.find()) {
            estados.add(literales.group(1));
        }
        return estados;
    }

    private static CriterioDePadron padronDelCodigo(String codigo, boolean soloPendientes) {
        return new CriterioDePadron(
                Familia.TRANSITO,
                null,
                null,
                null,
                codigo,
                null,
                null,
                null,
                null,
                null,
                soloPendientes);
    }

    private static CriterioDePapeleta criterioTransito(String placa) {
        return new CriterioDePapeleta(
                Familia.TRANSITO, null, placa, null, null, null, null, null, null, null, false);
    }

    private static Papeleta papeletaTransitoDe(String numero, long codigoInfraccionId) {
        return Papeleta.nuevaTransito(
                numero,
                codigoInfraccionId,
                FECHA,
                null,
                "Av. Grau",
                "ABC-123",
                null,
                null,
                null,
                null,
                obligadoDeA,
                Dinero.de("4950"),
                Alicuota.de("8"),
                Dinero.de("396"),
                Alicuota.de("100"),
                Dinero.de("396"),
                Dinero.de("198"),
                Observacion.de("Se registra para la prueba"));
    }

    private static Papeleta papeletaAdministrativaDe(
            String numero,
            long codigoInfraccionId,
            Long contribuyenteId,
            Long predioId,
            Long notificacionPreviaId) {
        return Papeleta.nuevaAdministrativa(
                numero,
                codigoInfraccionId,
                FECHA,
                null,
                "Av. Grau",
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                1L,
                Dinero.de("4950"),
                Alicuota.de("8"),
                Dinero.de("396"),
                Alicuota.de("100"),
                Dinero.de("396"),
                Dinero.de("198"),
                Observacion.de("Se registra para la prueba"));
    }

    private static long contarTrazas(long papeletaId) {
        return jdbc.sql(
                        "SELECT count(*) FROM papeleta_cambio_numero WHERE papeleta_id = :papeletaId")
                .param("papeletaId", papeletaId)
                .query(Long.class)
                .single();
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

    private static long crearCodigo(long municipalidadId, Familia familia, String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                    + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                    + " VALUES (?, ?, ?, 'Infraccion de prueba', 8.0000,"
                                    + "         'Base legal de prueba', '2020-01-01')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, familia.name());
                sentencia.setString(3, codigo);
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

    private static long crearContribuyente(long municipalidadId, String numeroDocumento) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente"
                                    + " (municipalidad_id, codigo_contribuyente, tipo_documento,"
                                    + "  numero_documento, tipo_persona, nombre_razon_social,"
                                    + "  usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'Contribuyente de"
                                    + " prueba', 'prueba') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, "C-" + numeroDocumento);
                sentencia.setString(3, numeroDocumento);
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
