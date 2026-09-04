package kamayuk.rentas.parametros.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El proceso batch que abre, compone y sella el conjunto de parametros de un ejercicio (#247 §2),
 * contra PostgreSQL real.
 *
 * <p>Es el camino de invocacion que le faltaba a {@link AdministrarParametros}: hasta aqui el
 * metodo existia y estaba probado, pero nada lo llamaba contra un ambiente, asi que {@code
 * cargar-arancel-vial.sh --conjunto-id N} no tenia ningun {@code N} que recibir.
 *
 * <p><b>Ninguna cifra tributaria aparece en esta prueba.</b> Los parametros que se siembran son
 * ficticios y estan marcados como tales, igual que en {@code AdministrarParametrosTest}: lo que se
 * verifica es el mecanismo —abrir, componer por llave, sellar—, no cuanto vale nada. Las cifras son
 * D-02.
 */
@DisplayName("Proceso batch — conjunto de parametros de un ejercicio (#247 §2)")
class AbrirConjuntoDeParametrosTest {

    /** Un valor inventado. No representa ninguna UIT, ningun tramo y ninguna alicuota. */
    private static final String VALOR_FICTICIO = "1.000000";

    private static final LocalDate DESDE = LocalDate.of(2026, 1, 1);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static AdministrarParametros administrar;
    private static ImportarParametrosDelConjunto importar;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @TempDir private static Path directorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240301", "Municipalidad del conjunto batch");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);

        AdministrarParametros objetivo =
                new AdministrarParametros(
                        new ParametrosRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, RELOJ), RELOJ);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        administrar = (AdministrarParametros) fabrica.getProxy();

        // El importador recibe el proxy, no el objetivo: es lo que hace que cada fila abra su
        // propia transaccion, que es la propiedad que se prueba mas abajo.
        //
        // Y el importador va tambien envuelto en su propio proxy, aunque hoy no tenga ninguna
        // anotacion que interceptar: sin eso, anotar `importar` con @Transactional —el defecto que
        // #328 documenta— no cambiaria nada en esta prueba y la propiedad quedaria sin verificar,
        // mientras que en produccion, donde es un @Service, si envolveria el archivo entero.
        ProxyFactory delImportador =
                new ProxyFactory(new ImportarParametrosDelConjunto(administrar));
        delImportador.setProxyTargetClass(true);
        delImportador.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        importar = (ImportarParametrosDelConjunto) delImportador.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @Nested
    @DisplayName("Abrir la version")
    class Abrir {

        @Test
        @DisplayName("abre el conjunto, dice su identificador y deja el contexto limpio")
        void abreYDiceElIdentificador() throws IOException, SQLException {
            ListAppender<ILoggingEvent> registro = escuchar();

            proceso(datos(2026, 0, null, false)).run(null);

            long conjunto = ultimoConjuntoDe(2026);
            assertThat(lineas(registro))
                    .as(
                            "es el N de `cargar-arancel-vial.sh --conjunto-id N`: si no se puede"
                                    + " extraer del registro, el paso siguiente no tiene entrada")
                    .contains("CONJUNTO_ID=" + conjunto);
            assertThat(estadoDe(conjunto)).isEqualTo("ABIERTO");
            assertThat(TenantContext.actualSiHay())
                    .as("el proceso batch no deja el contexto fijado para lo que corra despues")
                    .isEmpty();
        }

        @Test
        @DisplayName("una segunda corrida del mismo ejercicio abre la version siguiente")
        void laSegundaCorridaAbreLaSiguienteVersion() throws IOException, SQLException {
            proceso(datos(2032, 0, null, false)).run(null);
            long primero = ultimoConjuntoDe(2032);
            proceso(datos(2032, 0, null, false)).run(null);
            long segundo = ultimoConjuntoDe(2032);

            assertThat(segundo).isNotEqualTo(primero);
            assertThat(versionDe(segundo))
                    .as("la version se calcula, no se recibe: dos versiones 2 no se pueden dar")
                    .isEqualTo(versionDe(primero) + 1);
        }
    }

    @Nested
    @DisplayName("Componer el conjunto desde el archivo")
    class Componer {

        /** El manifiesto de cuadros que este repositorio versiona, tal como se despliega. */
        private static final Path MANIFIESTO_DE_CUADROS =
                Path.of("../../docs/10-negocio/valores-normativos/publicacion/cuadros-2026.csv")
                        .toAbsolutePath()
                        .normalize();

        /** Los dos archivos del corpus que este proceso compone, tal como se despliegan. */
        private static final List<Path> DESPLEGADOS =
                List.of(
                        Path.of(
                                "../../docs/10-negocio/valores-normativos/publicacion/"
                                        + "parametros-2026.csv"),
                        MANIFIESTO_DE_CUADROS);

        @Test
        @DisplayName("el manifiesto de cuadros que se despliega se compone entero, sin rechazos")
        void elManifiestoDesplegadoSeCompone() throws IOException, SQLException {
            // Este proceso lee POR POSICION —campos 0, 1 y 2— y no por cabecera, igual que
            // FilaPublicable y FilaDelManifiesto. `cuadros-2026.csv` tenia `cuadro` en la primera
            // columna, asi que componerlo leia `tipo = VALOR_REFERENCIAL`, `clave =
            // TABLA_VALORES_REFERENCIALES` y una fecha que decia «2026»: rechazaba TODAS sus filas
            // y sellaba el conjunto sin la edicion dentro, que es sellar el nombre del cuadro sin
            // su contenido. El sintoma no se parece a la causa —el conjunto queda sellado y el
            // cuadro publicado; lo unico que falta es el renglon que los une—, y por eso el paso 5
            // del instructivo se comprueba aqui en vez de confiarse a un comentario.
            for (Path archivo : DESPLEGADOS) {
                assertThat(archivo.toAbsolutePath().normalize()).exists();
            }
            // Las cabeceras de las dos ediciones del manifiesto, publicadas como las publica
            // PublicarCuadros: sin ellas no habria nada que componer y esto pasaria en verde
            // rechazando las dos filas por otro motivo.
            publicarFila("TABLA_VALORES_REFERENCIALES", "2026", LocalDate.of(2026, 1, 1));
            publicarFila("TABLA_DEPRECIACION", "ANEXO-I", LocalDate.of(2016, 7, 23));

            proceso(datos(2040, 0, MANIFIESTO_DE_CUADROS.toString(), false)).run(null);

            long conjunto = ultimoConjuntoDe(2040);
            assertThat(parametrosDe(conjunto))
                    .as(
                            "el conjunto sella la EDICION del cuadro, no sus miles de filas: si el"
                                    + " manifiesto no se puede componer, sellar congela un nombre"
                                    + " que no apunta a nada")
                    .containsExactlyInAnyOrder(
                            "TABLA_VALORES_REFERENCIALES:2026", "TABLA_DEPRECIACION:ANEXO-I");
        }

        @Test
        @DisplayName("incorpora los parametros nombrados por llave y se leen por conjunto")
        void incorporaLosParametrosNombrados() throws IOException, SQLException {
            publicar("COMPUESTO_A");
            publicar("COMPUESTO_B");
            publicarSinClave("FICTICIO_SIN_CLAVE");
            String archivo =
                    escribir(
                            "compone.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,COMPUESTO_A,2026-01-01
                            FICTICIO,COMPUESTO_B,2026-01-01
                            FICTICIO_SIN_CLAVE,,2026-01-01
                            """);

            proceso(datos(2033, 0, archivo, false)).run(null);

            long conjunto = ultimoConjuntoDe(2033);
            assertThat(parametrosDe(conjunto))
                    .as(
                            "la fila sin clave tambien entra: `clave = NULL` no habria devuelto"
                                    + " ninguna fila ni habria fallado")
                    .containsExactlyInAnyOrder(
                            "FICTICIO:COMPUESTO_A",
                            "FICTICIO:COMPUESTO_B",
                            "FICTICIO_SIN_CLAVE:null");
        }

        @Test
        @DisplayName("una fila que nombra lo que no esta publicado no se lleva a la valida")
        void unaFilaInvalidaNoSeLlevaALaValida() throws IOException, SQLException {
            publicar("AISLADA_1");
            publicar("AISLADA_2");
            String archivo =
                    escribir(
                            "por-fila.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,AISLADA_1,2026-01-01
                            FICTICIO,NO_PUBLICADA,2026-01-01
                            FICTICIO,AISLADA_2,2026-01-01
                            """);

            proceso(datos(2034, 0, archivo, false)).run(null);

            long conjunto = ultimoConjuntoDe(2034);
            assertThat(parametrosDe(conjunto))
                    .as(
                            "cada fila abre su propia transaccion: con una envolvente, la fila que"
                                    + " revienta se lleva por delante a la valida que la seguia"
                                    + " (#328)")
                    .containsExactlyInAnyOrder("FICTICIO:AISLADA_1", "FICTICIO:AISLADA_2");
        }

        @Test
        @DisplayName("dos parametros homonimos rechazan la fila en vez de elegir uno")
        void dosHomonimosRechazanLaFila() throws IOException, SQLException {
            publicar("HOMONIMA");
            publicar("HOMONIMA");
            String archivo =
                    escribir(
                            "homonimos.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,HOMONIMA,2026-01-01
                            """);

            proceso(datos(2035, 0, archivo, false)).run(null);

            long conjunto = ultimoConjuntoDe(2035);
            assertThat(parametrosDe(conjunto))
                    .as(
                            "parametro_tributario no tiene unicidad sobre (tipo, clave,"
                                    + " vigencia_desde): quedarse con «el primero» sellaria un valor"
                                    + " que nadie eligio")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Sellar, y solo cuando se pide")
    class Sellar {

        @Test
        @DisplayName("con la bandera queda sellado, y despues no admite ninguna escritura")
        void conLaBanderaQuedaSellado() throws IOException, SQLException {
            publicar("SELLADA_1");
            publicar("SELLADA_2");
            String archivo =
                    escribir(
                            "sella.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,SELLADA_1,2026-01-01
                            """);

            proceso(datos(2036, 0, archivo, true)).run(null);

            long conjunto = ultimoConjuntoDe(2036);
            assertThat(estadoDe(conjunto)).isEqualTo("SELLADO");
            assertThat(usuarioSelladoDe(conjunto))
                    .as("el sellado es un acto administrativo: queda con fecha y con nombre")
                    .isEqualTo("proceso-de-prueba");

            TenantContext.fijar(new MunicipalidadId(municipalidad));
            assertThatThrownBy(
                            () ->
                                    administrar.agregarParametroPublicado(
                                            conjunto,
                                            new LlaveDeParametro("FICTICIO", "SELLADA_2", DESDE),
                                            Observacion.de("Intento de agregar a un sellado")))
                    .as("lo impide el disparador de V9, no la aplicacion")
                    .hasMessageContaining("sellado");
        }

        @Test
        @DisplayName("sin la bandera el conjunto queda abierto: sellar nunca es implicito")
        void sinLaBanderaQuedaAbierto() throws IOException, SQLException {
            publicar("NO_SELLADA");
            String archivo =
                    escribir(
                            "no-sella.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,NO_SELLADA,2026-01-01
                            """);

            proceso(datos(2037, 0, archivo, false)).run(null);

            assertThat(estadoDe(ultimoConjuntoDe(2037)))
                    .as("un conjunto sellado no se modifica: sellarlo se pide, no se deduce")
                    .isEqualTo("ABIERTO");
        }

        @Test
        @DisplayName("no sella un conjunto compuesto a medias")
        void noSellaUnConjuntoCompuestoAMedias() throws IOException, SQLException {
            publicar("MEDIAS_1");
            String archivo =
                    escribir(
                            "a-medias.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,MEDIAS_1,2026-01-01
                            FICTICIO,MEDIAS_QUE_NO_EXISTE,2026-01-01
                            """);

            assertThatThrownBy(() -> proceso(datos(2038, 0, archivo, true)).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("a medias");

            assertThat(estadoDe(ultimoConjuntoDe(2038)))
                    .as(
                            "el que falta no se nota al sellar sino el dia que una regla lo pide,"
                                    + " con el padron ya emitido")
                    .isEqualTo("ABIERTO");
        }

        @Test
        @DisplayName("sella un conjunto ya abierto, que es el paso siguiente al arancel")
        void sellaUnConjuntoYaAbierto() throws IOException, SQLException {
            publicar("POSTERIOR");
            proceso(datos(2039, 0, null, false)).run(null);
            long conjunto = ultimoConjuntoDe(2039);

            // Entre una corrida y otra es donde va `cargar-arancel-vial.sh --conjunto-id N`.
            String archivo =
                    escribir(
                            "posterior.csv",
                            """
                            tipo,clave,vigenciaDesde
                            FICTICIO,POSTERIOR,2026-01-01
                            """);
            proceso(datos(0, conjunto, archivo, true)).run(null);

            assertThat(estadoDe(conjunto)).isEqualTo("SELLADO");
            assertThat(parametrosDe(conjunto)).containsExactly("FICTICIO:POSTERIOR");
        }
    }

    @Nested
    @DisplayName("Lo que el proceso exige antes de tocar la base")
    class LoQueExige {

        @Test
        @DisplayName("#202: corre solo en el perfil batch")
        void correSoloEnElPerfilBatch() {
            Profile perfil = AbrirConjuntoDeParametros.class.getAnnotation(Profile.class);

            assertThat(perfil)
                    .as(
                            "sin perfil correria tambien en el proceso web, y ese contenedor tendria"
                                    + " dentro el camino mas corto entre una peticion HTTP y el"
                                    + " sellado de un ejercicio")
                    .isNotNull();
            assertThat(perfil.value()).containsExactly("batch");
        }

        @Test
        @DisplayName(
                "o se abre una version, o se opera sobre una abierta: nunca las dos ni ninguna")
        void exigeExactamenteUnModo() {
            assertThatThrownBy(() -> datos(0, 0, null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactamente uno");
            assertThatThrownBy(() -> datos(2026, 7, null, false))
                    .as("serian dos conjuntos, y uno quedaria a medio componer")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactamente uno");
        }

        @Test
        @DisplayName("abrir y sellar sin archivo es sellar un conjunto vacio")
        void abrirYSellarSinArchivoSeRechaza() {
            assertThatThrownBy(() -> datos(2026, 0, null, true))
                    .as(
                            "se rechaza antes de abrir para no dejar en la base una version que"
                                    + " nadie va a poder sellar")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vacio");
        }
    }

    // ------------------------------------------------------------------

    private static AbrirConjuntoDeParametros proceso(DatosDelConjunto datos) {
        return new AbrirConjuntoDeParametros(administrar, importar, datos);
    }

    private static DatosDelConjunto datos(
            int ejercicio, long conjuntoId, String archivo, boolean sellar) {
        return new DatosDelConjunto(
                municipalidad,
                ejercicio,
                conjuntoId,
                archivo,
                sellar,
                "proceso-de-prueba",
                "Apertura del conjunto de parametros en la prueba");
    }

    private static ListAppender<ILoggingEvent> escuchar() {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(AbrirConjuntoDeParametros.class);
        ListAppender<ILoggingEvent> apendice = new ListAppender<>();
        apendice.start();
        registro.setLevel(Level.INFO);
        registro.addAppender(apendice);
        return apendice;
    }

    private static List<String> lineas(ListAppender<ILoggingEvent> registro) {
        return registro.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String escribir(String nombre, String contenido) {
        try {
            Path archivo = directorio.resolve(nombre);
            Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
            return archivo.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Publica un parametro <b>ficticio</b> de ambito nacional con el rol que corresponde.
     *
     * <p>Va por {@code rol_carga_parametros} y no por la aplicacion a proposito: la aplicacion solo
     * tiene {@code SELECT} sobre {@code parametro_tributario} (V7), y usar aqui el camino legitimo
     * deja constancia de cual es.
     */
    private static void publicar(String clave) throws SQLException {
        publicarFila("FICTICIO", clave);
    }

    private static void publicarSinClave(String tipo) throws SQLException {
        publicarFila(tipo, null);
    }

    private static void publicarFila(String tipo, String clave) throws SQLException {
        publicarFila(tipo, clave, DESDE);
    }

    private static void publicarFila(String tipo, String clave, LocalDate desde)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL, ?, ?,"
                                        + " ?::numeric, ?, 'Valor ficticio de prueba; no representa"
                                        + " ninguna norma', 'carga', 'aprueba')")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setString(3, VALOR_FICTICIO);
            sentencia.setDate(4, java.sql.Date.valueOf(desde));
            sentencia.executeUpdate();
            carga.commit();
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

    private static long ultimoConjuntoDe(int ejercicio) throws SQLException {
        return Long.parseLong(
                dato(
                        "SELECT id FROM conjunto_parametros WHERE municipalidad_id = "
                                + municipalidad
                                + " AND ejercicio = "
                                + ejercicio
                                + " ORDER BY version DESC LIMIT 1"));
    }

    private static String estadoDe(long conjunto) throws SQLException {
        return dato("SELECT estado FROM conjunto_parametros WHERE id = " + conjunto);
    }

    private static int versionDe(long conjunto) throws SQLException {
        return Integer.parseInt(
                dato("SELECT version FROM conjunto_parametros WHERE id = " + conjunto));
    }

    private static String usuarioSelladoDe(long conjunto) throws SQLException {
        return dato("SELECT usuario_sellado FROM conjunto_parametros WHERE id = " + conjunto);
    }

    /**
     * Las llaves {@code tipo:clave} que quedaron en el conjunto, leidas como las lee el sistema.
     */
    private static List<String> parametrosDe(long conjunto) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            List<ParametroTributario> parametros =
                    new TransactionTemplate(gestor)
                            .execute(
                                    estado ->
                                            new ParametrosRepositoryJdbc(jdbc)
                                                    .parametrosDe(conjunto));
            return java.util.Objects.requireNonNull(parametros).stream()
                    .map(p -> p.tipo() + ":" + p.clave())
                    .toList();
        } finally {
            TenantContext.limpiar();
        }
    }

    private static String dato(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return resultado.getString(1);
        }
    }
}
