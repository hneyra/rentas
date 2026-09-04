package kamayuk.rentas.parametros.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
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
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.PublicacionDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.parametros.infraestructura.PublicacionDeParametrosJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * El proceso batch que publica valores normativos en {@code parametro_tributario} (#188, #247 §4),
 * contra PostgreSQL real.
 *
 * <p>Es el eslabon que cerraba la cadena: {@code AbrirConjuntoDeParametros} sabia componer y
 * sellar, pero componer nombra parametros <b>ya publicados</b> y nada los publicaba, asi que el
 * conjunto de un ejercicio no podia sellarse en ningun ambiente.
 *
 * <h2>Dos conexiones, y esa es la prueba mas importante de aqui</h2>
 *
 * <p>La publicacion va por {@code rol_carga_parametros} y la composicion por {@code sgtm_app},
 * porque asi estan repartidos los privilegios (V7) y porque asi lo exige la separacion de funciones
 * de REQ-03. La prueba lo hace con dos {@code DataSource} distintos, no con uno de administrador:
 * con un superusuario todo esto pasaria en verde sin verificar nada.
 *
 * <h2>Las cifras</h2>
 *
 * <p>Las de los casos de mecanismo son <b>ficticias</b> y estan marcadas como tales. Las reales
 * aparecen en un solo caso, y no escritas aqui sino <b>leidas del derivado del corpus</b> que el
 * repositorio versiona: lo que ese caso demuestra es que el archivo que se despliega publica, no
 * cuanto vale nada. Las cifras son D-02a, y su exactitud la comprueba {@code
 * docs/10-negocio/verificar-publicacion.mjs} contra la norma.
 */
@DisplayName("Proceso batch — publicacion de valores normativos (#188, #247 §4)")
class PublicarParametrosTest {

    /** Un valor inventado. No representa ninguna UIT, ningun tramo y ninguna alicuota. */
    private static final String VALOR_FICTICIO = "1.000000";

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC);

    /** El derivado que este repositorio versiona, tal como se despliega. */
    private static final Path DERIVADO =
            Path.of("../../docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv")
                    .toAbsolutePath()
                    .normalize();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static PublicacionDeParametrosJdbc publicacion;
    private static AdministrarParametros administrar;
    private static ImportarParametrosDelConjunto importar;
    private static JdbcClient jdbcApp;
    private static LectorDeParametrosSellados lector;

    @TempDir private static Path directorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240401", "Municipalidad de la publicacion");

        // El que publica: rol_carga_parametros, el unico que puede escribir parametro_tributario.
        DriverManagerDataSource carga = new DriverManagerDataSource();
        carga.setUrl(base.url());
        carga.setUsername(BaseDeDatosDePrueba.CARGA_PARAMETROS);
        carga.setPassword(base.clave(BaseDeDatosDePrueba.CARGA_PARAMETROS));
        publicacion = new PublicacionDeParametrosJdbc(JdbcClient.create(carga));

        // El que compone y sella: sgtm_app, que sobre parametro_tributario solo tiene SELECT.
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        jdbcApp = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        ParametrosRepositoryJdbc repositorio = new ParametrosRepositoryJdbc(jdbcApp);
        administrar =
                (AdministrarParametros)
                        conProxyTransaccional(
                                new AdministrarParametros(
                                        repositorio, new AuditoriaJdbc(jdbcApp, RELOJ), RELOJ),
                                gestor);
        importar =
                (ImportarParametrosDelConjunto)
                        conProxyTransaccional(
                                new ImportarParametrosDelConjunto(administrar), gestor);
        lector =
                (LectorDeParametrosSellados)
                        conProxyTransaccional(new LectorDeParametrosSellados(repositorio), gestor);
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
        OrigenContext.limpiar();
    }

    /**
     * Los dos contextos que en una peticion salen del token, para los pasos que corren como {@code
     * sgtm_app}. La publicacion NO los necesita: no es de ninguna municipalidad y no escribe
     * auditoria.
     */
    private static void comoLaAplicacion() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(Origen.deProceso("cadena-de-prueba"));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Publicar")
    class Publicar {

        @Test
        @DisplayName("publica el valor con su vigencia, su fuente y las dos firmas del corpus")
        void publicaConLasDosFirmas() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "una.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_A",
                                            "UNA",
                                            "2026-01-01",
                                            "2026-12-31",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            proceso(archivo).run(null);

            assertThat(
                            dato(
                                    "SELECT usuario_carga FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_A'"))
                    .as("la firma que llega a la base es la del corpus, no la del proceso")
                    .isEqualTo("JNA");
            assertThat(
                            dato(
                                    "SELECT usuario_aprueba FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_A'"))
                    .isEqualTo("HNA");
            assertThat(
                            dato(
                                    "SELECT municipalidad_id FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_A'"))
                    .as(
                            "lo que se publica es de ambito nacional: no cuelga de ninguna"
                                    + " municipalidad (ADR-0007)")
                    .isNull();
            assertThat(
                            dato(
                                    "SELECT vigencia_hasta FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_A'"))
                    .isEqualTo("2026-12-31");
        }

        @Test
        @DisplayName("el parametro SIN clave entra, y se encuentra por su llave")
        void elParametroSinClaveEntra() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "sin-clave.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_SIN_CLAVE",
                                            "",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            proceso(archivo).run(null);

            assertThat(
                            dato(
                                    "SELECT count(*) FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_SIN_CLAVE' AND clave IS NULL"))
                    .as("es la forma de la UIT: el tipo con un solo valor, sin clave")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("una fila invalida no se lleva a la valida que la sigue")
        void unaFilaInvalidaNoSeLlevaALaValida() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "por-fila.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_B1",
                                            "",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "FICTICIO_ROTA,,no-es-una-fecha,,1.0,,fuente,x.md,JNA,HNA\n"
                                    + fila(
                                            "FICTICIO_B2",
                                            "",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            proceso(archivo).run(null);

            assertThat(
                            dato(
                                    "SELECT count(*) FROM parametro_tributario"
                                            + " WHERE tipo IN ('FICTICIO_B1','FICTICIO_B2')"))
                    .as(
                            "cada fila es su propia transaccion: con una envolvente, la que revienta"
                                    + " se lleva por delante a la valida que la seguia (#328)")
                    .isEqualTo("2");
            assertThat(
                            dato(
                                    "SELECT count(*) FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_ROTA'"))
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("volver a correr el mismo archivo informa, no duplica ni revienta")
        void laSegundaCorridaNoDuplica() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "idempotente.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_C",
                                            "",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            proceso(archivo).run(null);
            proceso(archivo).run(null);

            assertThat(dato("SELECT count(*) FROM parametro_tributario WHERE tipo = 'FICTICIO_C'"))
                    .as(
                            "dos filas homonimas dejarian el conjunto sin poder decir cual sello:"
                                    + " agregarParametroPublicado rechaza cuando hay mas de una")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("una sola firma no se publica: la base lo rechazaria, y esto antes")
        void unaSolaFirmaNoSePublica() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "misma-firma.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_D",
                                            "",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "JNA")
                                    + "\n");

            proceso(archivo).run(null);

            assertThat(dato("SELECT count(*) FROM parametro_tributario WHERE tipo = 'FICTICIO_D'"))
                    .as("releerse a uno mismo no es verificar (ADR-0007, RNF-092)")
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("con valor_maquina, lo que llega a la base es la forma que el codigo lee")
        void laFormaDeMaquinaEsLaQueSePublica() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "con-maquina.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_PLAZO",
                                            "CON_MAQUINA",
                                            "2026-01-01",
                                            "",
                                            "7",
                                            "siete (7) dias habiles ficticios",
                                            "JNA",
                                            "HNA",
                                            "7 DIAS_HABILES")
                                    + "\n");

            proceso(archivo).run(null);

            String publicado =
                    dato(
                            "SELECT valor_texto FROM parametro_tributario"
                                    + " WHERE tipo = 'FICTICIO_PLAZO' AND clave = 'CON_MAQUINA'");
            assertThat(publicado)
                    .as(
                            "el verbatim se queda en el corpus y en el CSV, que es donde se compara"
                                    + " contra la norma; a la base va lo que el codigo puede leer")
                    .isEqualTo("7 DIAS_HABILES");
            assertThat(Plazo.de(publicado))
                    .as("la ida y la vuelta: lo publicado se vuelve a leer como plazo")
                    .isEqualTo(new Plazo(7, kamayuk.rentas.dominio.UnidadDePlazo.DIAS_HABILES));
        }

        @Test
        @DisplayName("sin ella se publica el verbatim, y Plazo.de lo rechaza: por eso existe #192")
        void elVerbatimDeLaNormaNoSeDejaLeerComoPlazo() throws IOException, SQLException {
            // La razon de ser de la columna, demostrada en vez de afirmada: este es el texto que
            // el art. 14 de la Ley 26979 imprime, publicado tal cual.
            String archivo =
                    escribir(
                            "sin-maquina.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_PLAZO",
                                            "SIN_MAQUINA",
                                            "2026-01-01",
                                            "",
                                            "7",
                                            "siete (7) días hábiles",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            proceso(archivo).run(null);

            String publicado =
                    dato(
                            "SELECT valor_texto FROM parametro_tributario"
                                    + " WHERE tipo = 'FICTICIO_PLAZO' AND clave = 'SIN_MAQUINA'");
            assertThat(publicado)
                    .as("una fila sin la columna publica su texto, como antes de #192")
                    .isEqualTo("siete (7) días hábiles");
            assertThatThrownBy(() -> Plazo.de(publicado))
                    .as(
                            "la lectura no es tolerante a proposito: un plazo interpretado «lo mejor"
                                    + " posible» es plausible y equivocado. Sin la columna, esto"
                                    + " reventaria contando el plazo de una REC-1 en produccion")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cantidad UNIDAD");
        }

        @Test
        @DisplayName("sgtm_app no puede publicar: por eso este proceso no corre con su credencial")
        void laAplicacionNoPuedePublicar() {
            DriverManagerDataSource pool = new DriverManagerDataSource();
            pool.setUrl(base.url());
            pool.setUsername(BaseDeDatosDePrueba.APP);
            pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
            PublicacionDeParametrosJdbc conLaApp =
                    new PublicacionDeParametrosJdbc(JdbcClient.create(pool));

            assertThatThrownBy(() -> conLaApp.publicar(ficticio("FICTICIO_APP"), "JNA", "HNA"))
                    .as(
                            "la aplicacion solo tiene SELECT sobre parametro_tributario (V7): publicar"
                                    + " es de rol_carga_parametros, y no es una convencion")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("La cadena completa del ejercicio")
    class LaCadena {

        @Test
        @DisplayName("publicar, componer, sellar y leer: el conjunto de 2026 ya se puede sellar")
        void publicarComponerSellarYLeer() throws IOException, SQLException {
            String archivo =
                    escribir(
                            "cadena.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_E",
                                            "PRIMERO",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + fila(
                                            "FICTICIO_E",
                                            "SEGUNDO",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");

            // 1. Publicar, con rol_carga_parametros.
            proceso(archivo).run(null);

            // 2. Componer y sellar, con sgtm_app y EL MISMO ARCHIVO: sus tres primeras columnas
            //    son las que ImportarParametrosDelConjunto lee, y las demas las ignora.
            comoLaAplicacion();
            Observacion porque = Observacion.de("Se parametriza el ejercicio de la cadena");
            ConjuntoDeParametros conjunto = administrar.abrirVersion(new Ejercicio(2041), porque);
            long id = java.util.Objects.requireNonNull(conjunto.id());
            try (var lectura = Files.newBufferedReader(Path.of(archivo), StandardCharsets.UTF_8)) {
                assertThat(importar.importar(lectura, id, porque).nuevas())
                        .as(
                                "un solo archivo para los dos pasos: no hay dos listas que puedan"
                                        + " separarse")
                        .isEqualTo(2);
            }
            administrar.sellar(id, porque);

            // 3. Leerlo como lo lee una regla tributaria.
            ParametrosSellados sellados = lector.vigenteEn(new Ejercicio(2041));
            assertThat(sellados.numero("FICTICIO_E", "PRIMERO"))
                    .as(
                            "sin la publicacion, sellar era imposible: exige al menos una fila en"
                                    + " conjunto_parametro_detalle y nada publicaba ninguna")
                    .isPresent();
            assertThat(sellados.numero("FICTICIO_E", "SEGUNDO")).isPresent();
        }

        @Test
        @DisplayName("con el conjunto SELLADO, componer lo publicado despues ya no entra")
        void conElConjuntoSelladoNoEntraNadaMas() throws IOException, SQLException {
            String primero =
                    escribir(
                            "sellado-1.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_F",
                                            "DENTRO",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");
            proceso(primero).run(null);

            comoLaAplicacion();
            Observacion porque = Observacion.de("Se sella el ejercicio y se intenta cambiarlo");
            long id =
                    java.util.Objects.requireNonNull(
                            administrar.abrirVersion(new Ejercicio(2042), porque).id());
            try (var lectura = Files.newBufferedReader(Path.of(primero), StandardCharsets.UTF_8)) {
                importar.importar(lectura, id, porque);
            }
            administrar.sellar(id, porque);

            // La publicacion posterior SI ocurre —parametro_tributario no cuelga de ningun
            // conjunto— y es la composicion la que el disparador de V9 rechaza.
            String segundo =
                    escribir(
                            "sellado-2.csv",
                            cabecera()
                                    + fila(
                                            "FICTICIO_F",
                                            "TARDE",
                                            "2026-01-01",
                                            "",
                                            VALOR_FICTICIO,
                                            "",
                                            "JNA",
                                            "HNA")
                                    + "\n");
            proceso(segundo).run(null);
            assertThat(
                            dato(
                                    "SELECT count(*) FROM parametro_tributario"
                                            + " WHERE tipo = 'FICTICIO_F' AND clave = 'TARDE'"))
                    .isEqualTo("1");

            try (var lectura = Files.newBufferedReader(Path.of(segundo), StandardCharsets.UTF_8)) {
                assertThat(importar.importar(lectura, id, porque).rechazadas())
                        .as(
                                "un conjunto sellado no se modifica: corregirlo exige otra version"
                                        + " (ADR-0007, disparador de V9)")
                        .singleElement()
                        .extracting("motivo")
                        .asString()
                        .contains("sellado");
            }
            assertThat(lector.vigenteEn(new Ejercicio(2042)).numero("FICTICIO_F", "TARDE"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("El derivado del corpus que se despliega")
    class ElDerivado {

        @Test
        @DisplayName(
                "publica las treinta y dos filas del corpus, con las firmas que el corpus dice")
        void publicaElDerivadoDelCorpus() throws IOException, SQLException {
            assertThat(DERIVADO)
                    .as("es el archivo que publicar-parametros.sh monta en el Job")
                    .exists();

            proceso(DERIVADO.toString()).run(null);

            // Ninguna cifra escrita aqui: lo que se compara es la base contra el archivo.
            List<String> delArchivo = llavesDe(DERIVADO);
            // El censo va escrito, y a proposito: anadir una cifra normativa al conjunto de un
            // ejercicio tiene que ser un acto deliberado, no algo que se cuele en un diff. Fueron
            // 22 hasta el 2026-08-30, cuando entraron las diez que ya estaban firmadas en el corpus
            // y nadie habia pasado al derivado —alcabala, espectaculos y el factor de
            // oficializacion— (#438).
            assertThat(delArchivo).hasSize(32);
            for (String llave : delArchivo) {
                String[] partes = llave.split("\\|", -1);
                assertThat(
                                dato(
                                        "SELECT count(*) FROM parametro_tributario"
                                                + " WHERE municipalidad_id IS NULL AND tipo = '"
                                                + partes[0]
                                                + "' AND clave IS NOT DISTINCT FROM "
                                                + (partes[1].isEmpty()
                                                        ? "NULL"
                                                        : "'" + partes[1] + "'")
                                                + " AND vigencia_desde = DATE '"
                                                + partes[2]
                                                + "'"))
                        .as(
                                "la fila %s del derivado tiene que estar publicada, y una sola vez",
                                llave)
                        .isEqualTo("1");
            }
            assertThat(dato("SELECT count(*) FROM parametro_tributario WHERE tipo = 'UIT'"))
                    .as("la UIT es el tipo sin clave: cinco ejercicios distinguidos por vigencia")
                    .isEqualTo("5");
            assertThat(
                            dato(
                                    "SELECT count(DISTINCT usuario_aprueba) FROM parametro_tributario"
                                            + " WHERE tipo LIKE 'TRAMO%' OR tipo LIKE 'DEDUCCION%'"))
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("los plazos del derivado se vuelven a leer como Plazo: la ida y la vuelta")
        void losPlazosDelDerivadoSeLeenComoPlazo() throws IOException, SQLException {
            // 1. Publicar el archivo que se despliega. Volver a correrlo no duplica.
            proceso(DERIVADO.toString()).run(null);

            // 2. Componer y sellar el ejercicio con EL MISMO archivo.
            comoLaAplicacion();
            Observacion porque = Observacion.de("Se parametriza el ejercicio con el derivado");
            long id =
                    java.util.Objects.requireNonNull(
                            administrar.abrirVersion(new Ejercicio(2043), porque).id());
            try (var lectura = Files.newBufferedReader(DERIVADO, StandardCharsets.UTF_8)) {
                assertThat(importar.importar(lectura, id, porque).rechazadas())
                        .as("todo lo publicado tiene que poder componer el conjunto")
                        .isEmpty();
            }
            administrar.sellar(id, porque);

            // 3. Leerlo como lo lee una regla, y volver a convertirlo en plazo.
            ParametrosSellados sellados = lector.vigenteEn(new Ejercicio(2043));
            List<FilaCsv> plazos = plazosDelDerivado();
            assertThat(plazos)
                    .as("si el derivado se queda sin filas PLAZO, esta prueba no prueba nada")
                    .isNotEmpty();
            for (FilaCsv fila : plazos) {
                String clave = fila.campos().get(1);
                String maquina = fila.campos().get(10);
                assertThat(sellados.texto("PLAZO", clave))
                        .as("PLAZO:%s tiene que estar en el conjunto sellado", clave)
                        .contains(maquina);
                Plazo plazo = Plazo.de(sellados.texto("PLAZO", clave).orElseThrow());
                assertThat(new BigDecimal(plazo.cantidad()))
                        .as(
                                "la cantidad que se lee es la cifra verificada de la fila, no otra"
                                        + " escrita al lado")
                        .isEqualByComparingTo(new BigDecimal(fila.campos().get(4)));
            }
        }
    }

    @Nested
    @DisplayName("Lo que el proceso exige antes de tocar la base")
    class LoQueExige {

        @Test
        @DisplayName("#202: corre solo en el perfil batch, y su adaptador de escritura tambien")
        void correSoloEnElPerfilBatch() {
            Profile delProceso = PublicarParametros.class.getAnnotation(Profile.class);
            Profile delAdaptador = PublicacionDeParametrosJdbc.class.getAnnotation(Profile.class);

            assertThat(delProceso)
                    .as(
                            "sin perfil, el contenedor que atiende peticiones tendria dentro el camino"
                                    + " mas corto entre una peticion HTTP y la publicacion de una cifra")
                    .isNotNull();
            assertThat(delProceso.value()).containsExactly("batch");
            assertThat(delAdaptador)
                    .as(
                            "un bean de escritura en el perfil web seria un camino que existe y no"
                                    + " funciona: la credencial del proceso web no puede ejecutarlo")
                    .isNotNull();
            assertThat(delAdaptador.value()).containsExactly("batch");
        }

        @Test
        @DisplayName("sin archivo no hay nada que publicar, y se dice antes de arrancar")
        void sinArchivoNoArranca() {
            assertThatThrownBy(() -> new DatosDeLaPublicacion("  ", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sgtm.publicacion-parametros.archivo");
        }
    }

    // ------------------------------------------------------------------

    private static PublicarParametros proceso(String archivo) {
        return new PublicarParametros(
                publicacion, new DatosDeLaPublicacion(archivo, "publicacion-de-prueba"));
    }

    private static Object conProxyTransaccional(Object objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return fabrica.getProxy();
    }

    private static String cabecera() {
        return "tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,valor_texto,"
                + "documento_fuente,archivo_del_corpus,transcribio,verifico,valor_maquina\n";
    }

    /** Una fila SIN forma de maquina: lo que se publica es su texto, como antes de #192. */
    private static String fila(
            String tipo,
            String clave,
            String desde,
            String hasta,
            String numerico,
            String texto,
            String transcribio,
            String verifico) {
        return fila(tipo, clave, desde, hasta, numerico, texto, transcribio, verifico, "");
    }

    private static String fila(
            String tipo,
            String clave,
            String desde,
            String hasta,
            String numerico,
            String texto,
            String transcribio,
            String verifico,
            String maquina) {
        return String.join(
                        ",",
                        tipo,
                        clave,
                        desde,
                        hasta,
                        numerico,
                        texto,
                        "Valor ficticio de prueba; no representa ninguna norma",
                        "ficticio.md",
                        transcribio,
                        verifico,
                        maquina)
                + "\n";
    }

    /** Las filas de tipo {@code PLAZO} del derivado, leidas como las lee el proceso. */
    private static List<FilaCsv> plazosDelDerivado() throws IOException {
        try (var lectura = Files.newBufferedReader(DERIVADO, StandardCharsets.UTF_8)) {
            return LectorDeFilasCsv.leer(lectura).stream()
                    .filter(f -> f.campos().get(0).equals("PLAZO"))
                    .toList();
        }
    }

    private static kamayuk.rentas.parametros.dominio.ParametroTributario ficticio(String tipo) {
        return new kamayuk.rentas.parametros.dominio.ParametroTributario(
                null,
                tipo,
                null,
                new kamayuk.rentas.dominio.ValorNormativo(new BigDecimal(VALOR_FICTICIO)),
                null,
                new kamayuk.rentas.dominio.Vigencia(LocalDate.of(2026, 1, 1), null),
                "Valor ficticio de prueba; no representa ninguna norma");
    }

    /** Las llaves {@code tipo|clave|vigenciaDesde} del derivado, leidas del propio archivo. */
    private static List<String> llavesDe(Path archivo) throws IOException {
        return Files.readAllLines(archivo, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.stripLeading().startsWith("#"))
                .skip(1)
                .map(l -> l.split(",", -1))
                .map(c -> c[0] + "|" + c[1] + "|" + c[2])
                .toList();
    }

    private static String escribir(String nombre, String contenido) throws IOException {
        Path archivo = directorio.resolve(nombre);
        Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
        return archivo.toString();
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

    private static String dato(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return resultado.getString(1);
        }
    }

    @Nested
    @DisplayName("Sin conexion no hay filas rechazadas: hay una corrida fallida (#435)")
    class SinConexion {

        /**
         * El defecto que este caso fija, medido contra {@code stg} el 2026-08-29.
         *
         * <p>{@code rol_carga_parametros} seguia {@code NOLOGIN} —{@code 20-asignar-claves.sh} solo
         * corre al inicializar el motor, y ese cluster se habia creado antes del issue #387—, y
         * como {@code CannotGetJdbcConnectionException} es una {@code DataAccessException}, el
         * {@code catch} generico la contaba como un rechazo de fila: la corrida termino con {@code
         * PUBLICADAS=0 RECHAZADAS=22}, un aviso por fila que <b>culpaba a las firmas</b>, y codigo
         * de salida 0. La causa real —«role "rol_carga_parametros" is not permitted to log in»— no
         * aparecia en ninguna linea de la salida.
         */
        @Test
        @DisplayName(
                "una credencial que no conecta corta la corrida y nombra la causa, no las firmas")
        void unaCredencialQueNoConectaCortaLaCorrida() {
            PublicacionDeParametros sinPoderConectarse =
                    new PublicacionDeParametros() {
                        @Override
                        public java.util.List<ParametroTributario> publicados(
                                LlaveDeParametro llave) {
                            return java.util.List.of();
                        }

                        @Override
                        public long publicar(
                                ParametroTributario parametro,
                                String transcribio,
                                String verifico) {
                            throw new org.springframework.jdbc.CannotGetJdbcConnectionException(
                                    "FATAL: role \"rol_carga_parametros\" is not permitted to log in");
                        }
                    };
            PublicarParametros proceso =
                    new PublicarParametros(
                            sinPoderConectarse,
                            new DatosDeLaPublicacion("/no/importa.csv", "prueba"));

            String csv =
                    "tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,valor_texto,"
                            + "documento_fuente,archivo_del_corpus,transcribio,verifico,valor_maquina\n"
                            + "FICTICIO_SIN_CONEXION,,2026-01-01,,1,,fuente de la prueba,uit.md,JNA,HNA,\n"
                            + "FICTICIO_SIN_CONEXION,OTRA,2026-01-01,,2,,fuente de la prueba,uit.md,JNA,HNA,\n";

            assertThatThrownBy(() -> proceso.publicar(new java.io.StringReader(csv)))
                    .as(
                            "sin conexion no hay «2 filas rechazadas»: hay una corrida que no pudo"
                                    + " empezar. Contarlo como rechazo por fila produce un diagnostico"
                                    + " plausible y equivocado —y con codigo de salida 0—")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NO es una fila rechazada")
                    .hasMessageContaining("asignar-claves.sh");
        }
    }
}
