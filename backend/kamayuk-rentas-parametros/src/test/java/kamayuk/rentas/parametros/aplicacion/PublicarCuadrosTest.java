package kamayuk.rentas.parametros.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.PublicacionDeCuadros;
import kamayuk.rentas.parametros.infraestructura.PublicacionDeCuadrosJdbc;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * El proceso batch que publica un cuadro normativo nacional (D-13, ADR-0017; #188), contra
 * PostgreSQL real.
 *
 * <h2>Una sola conexion, y es la que no puede ser otra</h2>
 *
 * <p>Todo lo de aqui va por {@code rol_carga_parametros}: desde V55 es la unica credencial que
 * puede escribir {@code valor_referencial_vehiculo}. Con un superusuario esto pasaria en verde sin
 * verificar nada —ni la politica RLS, ni el privilegio, ni el disparador—, que es el precedente que
 * CLAUDE.md pone por delante de todo lo demas.
 *
 * <h2>Las cifras</h2>
 *
 * <p>Las de los casos de mecanismo son <b>ficticias</b> y estan escritas en un CSV temporal que la
 * prueba fabrica: no representan ningun valor referencial de ningun vehiculo. El derivado real del
 * corpus aparece en un solo caso —{@link #elManifiestoQueSeDespliegaSeVerificaConSuHuella()}—, y lo
 * que ese caso demuestra es que el archivo que se despliega es reproducible, no cuanto vale nada.
 */
@DisplayName("Proceso batch — publicacion de cuadros normativos (D-13, #188)")
class PublicarCuadrosTest {

    /** El manifiesto que este repositorio versiona, tal como se despliega. */
    private static final Path MANIFIESTO =
            Path.of("../../docs/10-negocio/valores-normativos/publicacion/cuadros-2026.csv")
                    .toAbsolutePath()
                    .normalize();

    private static final String CABECERA =
            "tipo,clave,vigencia_desde,vigencia_hasta,documento_fuente,archivo_de_filas,"
                    + "sha256,archivo_del_corpus,transcribio,verifico,cuadro";

    /** El derivado de la tabla de depreciacion que este repositorio versiona (H-15, V57). */
    private static final Path DERIVADO_DE_DEPRECIACION =
            Path.of(
                            "../../docs/10-negocio/valores-normativos/fuentes/"
                                    + "depreciacion-rnt-2016/depreciacion.csv")
                    .toAbsolutePath()
                    .normalize();

    private static final String CABECERA_DE_DEPRECIACION =
            "tabla,material,estado_conservacion,antiguedad_hasta,porcentaje";

    private static final String CABECERA_DEL_ANEXO =
            "categoria,marca,modelo_anterior,modelo,valor_1,valor_2,valor_3";

    private static BaseDeDatosDePrueba base;
    private static PublicacionDeCuadros publicacion;
    private static JdbcClient jdbc;
    private static PublicarCuadros proceso;

    @TempDir private static Path directorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        DriverManagerDataSource carga = new DriverManagerDataSource();
        carga.setUrl(base.url());
        carga.setUsername(BaseDeDatosDePrueba.CARGA_PARAMETROS);
        carga.setPassword(base.clave(BaseDeDatosDePrueba.CARGA_PARAMETROS));
        jdbc = JdbcClient.create(carga);
        publicacion = new PublicacionDeCuadrosJdbc(jdbc);
        proceso =
                new PublicarCuadros(
                        publicacion, new DatosDelCuadro(MANIFIESTO.toString(), "prueba"));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("publica el cuadro entero, con municipalidad_id nulo, y cierra la edicion")
    void publicaElCuadroEnteroYCierraLaEdicion() throws IOException {
        Path manifiesto = manifiestoCon("2001-01-01", filasFicticias("MARCA-A", "MODELO-A"));

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        // Una linea del anexo son tres filas del cuadro: un valor por ano de fabricacion.
        assertThat(informe.nuevas()).isEqualTo(3);
        assertThat(informe.rechazadas()).isEmpty();

        assertThat(filasDe("MODELO-A")).isEqualTo(3);
        assertThat(municipalidadesDistintasDe("MODELO-A"))
                .as("un cuadro nacional no lleva municipalidad: es de todas (ARQ-09 §2.1)")
                .isZero();

        PublicacionDeCuadros.Edicion edicion =
                publicacion
                        .edicionPublicada(
                                new LlaveDeParametro(
                                        "TABLA_FICTICIA",
                                        "2001",
                                        java.time.LocalDate.parse("2001-01-01")))
                        .orElseThrow();
        assertThat(edicion.cerrada())
                .as(
                        "cerrar la edicion es lo que impide que crezca despues de que un conjunto"
                                + " la selle")
                .isTrue();
    }

    @Test
    @DisplayName("volver a correr el mismo manifiesto no duplica: la edicion ya esta cerrada")
    void volverACorrerNoDuplica() throws IOException {
        Path manifiesto = manifiestoCon("2002-01-01", filasFicticias("MARCA-B", "MODELO-B"));

        proceso.publicar(manifiesto);
        InformeDeImportacion segunda = proceso.publicar(manifiesto);

        assertThat(segunda.nuevas()).isZero();
        assertThat(segunda.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("ya esta publicada y cerrada");
        assertThat(filasDe("MODELO-B")).isEqualTo(3);
    }

    @Test
    @DisplayName("un archivo de filas con la huella cambiada no publica ni una fila")
    void unArchivoConOtraHuellaNoPublicaNada() throws IOException {
        Path filas = filasFicticias("MARCA-C", "MODELO-C");
        Path manifiesto =
                escribir(
                        "manifiesto-huella-mala.csv",
                        CABECERA
                                + "\nTABLA_FICTICIA,2003,2003-01-01,2003-12-31,"
                                + "Norma de mentira 000-0000-XX,"
                                + filas.getFileName()
                                + ","
                                + "0".repeat(64)
                                + ",vehicular-valores-referenciales-2026.md,JNA,HNA,"
                                + "VALOR_REFERENCIAL\n");

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("no es el que el corpus firmo");
        assertThat(filasDe("MODELO-C")).isZero();
    }

    @Test
    @DisplayName("un cuadro que el proceso no sabe publicar se rechaza nombrando el motivo")
    void unCuadroQueNoSePuedePublicarSeRechaza() throws IOException {
        // El ejemplo era DEPRECIACION hasta que V57 le dio a `depreciacion` su columna de uso y
        // paso a ser publicable (H-15). El que queda es el de valores unitarios, y por dos motivos
        // que este proceso no puede resolver: la R.M. anual publica un cuadro por region y el
        // corpus solo trae Costa, y su archivo volvio a TRANSCRITO al cotejarlo contra el Anexo I.2
        // real (GOB-03, H-14).
        Path filas = filasFicticias("MARCA-D", "MODELO-D");
        Path manifiesto =
                escribir(
                        "manifiesto-otro-cuadro.csv",
                        CABECERA
                                + "\nTABLA_FICTICIA,2004,2004-01-01,2004-12-31,"
                                + "Norma de mentira 000-0000-XX,"
                                + filas.getFileName()
                                + ","
                                + huella(filas)
                                + ",valores-unitarios-2026.md,JNA,HNA,VALOR_UNITARIO\n");

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("no es un cuadro que este proceso sepa publicar");
    }

    @Test
    @DisplayName("dos firmas iguales no publican: releerse a uno mismo no es verificar")
    void dosFirmasIgualesNoPublican() throws IOException {
        Path filas = filasFicticias("MARCA-E", "MODELO-E");
        Path manifiesto =
                escribir(
                        "manifiesto-una-firma.csv",
                        CABECERA
                                + "\nTABLA_FICTICIA,2005,2005-01-01,2005-12-31,"
                                + "Norma de mentira 000-0000-XX,"
                                + filas.getFileName()
                                + ","
                                + huella(filas)
                                + ",vehicular-valores-referenciales-2026.md,JNA,JNA,"
                                + "VALOR_REFERENCIAL\n");

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("releerse a uno mismo no es verificar");
    }

    // ------------------------------------------------------------------
    // El cuadro de depreciacion: las cuatro tablas del Anexo I (H-15, V57)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("las cuatro tablas del Anexo I entran enteras, y ninguna lleva municipalidad")
    void lasCuatroTablasDelAnexoEntranEnteras() throws IOException {
        Path manifiesto = manifiestoDeDepreciacion("2011-01-01");

        InformeDeImportacion informe = proceso.publicar(manifiesto);
        long edicion = edicionDeDepreciacion("2011-01-01");

        assertThat(informe.rechazadas()).isEmpty();
        assertThat(informe.nuevas())
                .as(
                        "el derivado que se despliega trae las celdas tabuladas de las cuatro"
                                + " tablas, y todas tienen que entrar")
                .isEqualTo(filasDelDerivado());
        assertThat(filasDeDepreciacion(edicion)).isEqualTo(filasDelDerivado());
        assertThat(tablasDeDepreciacion(edicion))
                .as("el Anexo I publica cuatro tablas, una por uso de la edificacion")
                .containsExactly("01", "02", "03", "04");
        assertThat(municipalidadesDeDepreciacion(edicion))
                .as("un cuadro nacional no lleva municipalidad: es de todas (ARQ-09 §2.1)")
                .isZero();
    }

    @Test
    @DisplayName("sin el uso en la clave, tres de cada cuatro filas se perderian en silencio")
    void sinElUsoLasCuatroTablasColapsanEnUna() throws IOException {
        proceso.publicar(manifiestoDeDepreciacion("2012-01-01"));
        long edicion = edicionDeDepreciacion("2012-01-01");

        int conUso = filasDeDepreciacion(edicion);
        Integer sinUso =
                jdbc.sql(
                                "SELECT count(*) FROM (SELECT DISTINCT material,"
                                        + " estado_conservacion, antiguedad_hasta FROM"
                                        + " depreciacion WHERE publicacion_id = :edicion) sin_uso")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();

        // Esto es H-15 medido, no razonado: con la clave anterior a V57 —(material, estado,
        // antiguedad)— la unicidad se habria quedado con la primera tabla que llegara y habria
        // descartado las otras tres SIN UN SOLO ERROR. La cifra de la izquierda es lo que la norma
        // publica; la de la derecha, lo que habria entrado.
        assertThat(sinUso)
                .as("las combinaciones que quedan al quitar el uso de la clave")
                .isLessThan(conUso);
        assertThat(conUso - java.util.Objects.requireNonNull(sinUso))
                .as("filas del Anexo que la clave anterior habria descartado en silencio")
                .isEqualTo(365);

        Integer discrepantes =
                jdbc.sql(
                                "SELECT count(*) FROM (SELECT material, estado_conservacion,"
                                        + " antiguedad_hasta FROM depreciacion"
                                        + " WHERE publicacion_id = :edicion GROUP BY 1, 2, 3"
                                        + " HAVING count(DISTINCT porcentaje) > 1) discrepantes")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();
        assertThat(discrepantes)
                .as(
                        "y no serian filas de sobra: de las %s combinaciones, estas tienen un"
                                + " porcentaje DISTINTO segun el uso, que es depreciar una oficina"
                                + " con el de una vivienda",
                        sinUso)
                .isEqualTo(120);
    }

    @Test
    @DisplayName("«mas de 50 anios» entra sin tope, y no con un centinela que parezca un tope")
    void elTramoAbiertoEntraSinTope() throws IOException {
        proceso.publicar(manifiestoDeDepreciacion("2013-01-01"));
        long edicion = edicionDeDepreciacion("2013-01-01");

        Integer sinTope =
                jdbc.sql(
                                "SELECT count(*) FROM depreciacion WHERE publicacion_id = :edicion"
                                        + " AND antiguedad_hasta IS NULL")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();
        Integer maximo =
                jdbc.sql(
                                "SELECT max(antiguedad_hasta) FROM depreciacion"
                                        + " WHERE publicacion_id = :edicion")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();

        // Cuatro tablas x doce combinaciones de material y estado, menos las ocho que el Anexo no
        // tabula en el tramo abierto.
        assertThat(sinTope).isEqualTo(40);
        assertThat(maximo)
                .as(
                        "el mayor tope tabulado es «hasta 50»; un centinela para el tramo abierto"
                                + " se leeria igual que un tope de verdad")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("una celda que el Anexo no tabula no esta, y no esta con cero")
    void unaCeldaSinTabularNoSePublica() throws IOException {
        proceso.publicar(manifiestoDeDepreciacion("2014-01-01"));
        long edicion = edicionDeDepreciacion("2014-01-01");

        // Tabla 01, Liviano/Adobe, Malo: el Anexo tabula hasta «30 anios» y desde ahi pone `*`
        // —«el perito fija los porcentajes no tabulados»—. Publicar esas celdas con cero seria no
        // depreciar una construccion ruinosa (#48: una celda que falta no vale cero).
        assertThat(porcentajeDe(edicion, "01", "Liviano/Adobe", "Malo", 30))
                .as("la ultima celda que el Anexo si tabula de esa fila")
                .isNotNull();
        assertThat(porcentajeDe(edicion, "01", "Liviano/Adobe", "Malo", 35))
                .as("la primera que marca con asterisco: no existe, no vale cero")
                .isNull();
        assertThat(porcentajeDe(edicion, "01", "Liviano/Adobe", "Malo", null))
                .as("«mas de 50 anios» de esa misma fila tambien lleva asterisco")
                .isNull();
    }

    @Test
    @DisplayName("el tramo abierto no se puede duplicar, aunque su tope sea nulo")
    void elTramoAbiertoNoSeDuplica() throws IOException {
        Path filas =
                escribir(
                        "depreciacion-tramo-abierto-dos-veces.csv",
                        CABECERA_DE_DEPRECIACION
                                + "\n01,Concreto,Bueno,,7\n01,Concreto,Bueno,,9\n");
        Path manifiesto = manifiestoDeDepreciacionCon("2017-01-01", filas);

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        // Sin NULLS NOT DISTINCT en depreciacion_uq (V57), las dos filas entrarian: PostgreSQL
        // considera distintos dos nulos por omision, asi que el tramo abierto de la misma
        // combinacion podria quedar cargado dos veces con porcentajes distintos y ninguna consulta
        // diria cual es el bueno.
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("mas de 50 anios");
    }

    @Test
    @DisplayName("una fila del cuadro con una tabla que el Anexo no publica se rechaza")
    void unaTablaQueElAnexoNoPublicaSeRechaza() throws IOException {
        Path filas =
                escribir(
                        "depreciacion-tabla-05.csv",
                        CABECERA_DE_DEPRECIACION + "\n05,Concreto,Bueno,10,7\n");
        Path manifiesto = manifiestoDeDepreciacionCon("2015-01-01", filas);

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("no es una de las cuatro tablas del Anexo I");
    }

    @Test
    @DisplayName("una fila del cuadro sin porcentaje se rechaza: no se publica con cero")
    void unaFilaSinPorcentajeSeRechaza() throws IOException {
        Path filas =
                escribir(
                        "depreciacion-sin-porcentaje.csv",
                        CABECERA_DE_DEPRECIACION + "\n01,Concreto,Bueno,10,\n");
        Path manifiesto = manifiestoDeDepreciacionCon("2016-01-01", filas);

        InformeDeImportacion informe = proceso.publicar(manifiesto);

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas())
                .singleElement()
                .extracting(InformeDeImportacion.FilaRechazada::motivo, STRING)
                .contains("no se publica con cero");
    }

    @Test
    @DisplayName("el manifiesto que se despliega existe y su archivo de filas conserva su huella")
    void elManifiestoQueSeDespliegaSeVerificaConSuHuella() throws IOException {
        // No publica: comprueba que el archivo versionado sigue siendo analizable y que el archivo
        // de filas que nombra sigue teniendo la huella declarada. Lo que las cifras valen es D-02a,
        // y lo comprueba docs/10-negocio/verificar-cuadros.mjs contra el corpus.
        assertThat(MANIFIESTO).exists();
        List<String> lineas = Files.readAllLines(MANIFIESTO, StandardCharsets.UTF_8);
        assertThat(lineas).anyMatch(linea -> linea.endsWith(",VALOR_REFERENCIAL"));
        assertThat(lineas).anyMatch(linea -> linea.endsWith(",DEPRECIACION"));
    }

    // ------------------------------------------------------------------
    // Fixtures. Ninguna cifra de aqui es un valor referencial real.
    // ------------------------------------------------------------------

    private static final org.assertj.core.api.InstanceOfAssertFactory<
                    String, org.assertj.core.api.AbstractStringAssert<?>>
            STRING = org.assertj.core.api.InstanceOfAssertFactories.STRING;

    private static Path filasFicticias(String marca, String modelo) throws IOException {
        return escribir(
                "anexo-" + modelo + ".csv",
                CABECERA_DEL_ANEXO
                        + "\nA1,"
                        + marca
                        + ",,"
                        + modelo
                        + ",\"1,000\",\"900\",\"800\"\n");
    }

    private static Path manifiestoCon(String desde, Path filas) throws IOException {
        String ejercicio = desde.substring(0, 4);
        return escribir(
                "manifiesto-" + ejercicio + ".csv",
                CABECERA
                        + "\nTABLA_FICTICIA,"
                        + ejercicio
                        + ","
                        + desde
                        + ","
                        + ejercicio
                        + "-12-31,Norma de mentira 000-0000-XX,"
                        + filas.getFileName()
                        + ","
                        + huella(filas)
                        + ",vehicular-valores-referenciales-2026.md,JNA,HNA,"
                        + "VALOR_REFERENCIAL\n");
    }

    private static Path escribir(String nombre, String contenido) throws IOException {
        Path archivo = directorio.resolve(nombre);
        Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
        return archivo;
    }

    private static String huella(Path archivo) throws IOException {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(archivo)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Esta JVM no trae SHA-256", e);
        }
    }

    /**
     * El derivado real del corpus, copiado tal cual al directorio temporal.
     *
     * <p>Es el unico caso en que estas pruebas cargan cifras de verdad, y es a proposito: cargar el
     * anexo vehicular de verdad fue lo que destapo los dos defectos que V55 corrigio, y ninguna
     * revision los habria visto. Se copia byte a byte —la huella se recalcula sobre la copia— para
     * que el manifiesto temporal pueda nombrarlo como hermano suyo sin tocar el desplegado.
     */
    private static Path derivadoDeDepreciacion() throws IOException {
        Path copia = directorio.resolve("depreciacion-del-corpus.csv");
        if (!Files.exists(copia)) {
            Files.copy(DERIVADO_DE_DEPRECIACION, copia);
        }
        return copia;
    }

    /** Cuantas filas trae el derivado desplegado, contadas ahi y no escritas aqui. */
    private static int filasDelDerivado() throws IOException {
        try (var lineas = Files.lines(DERIVADO_DE_DEPRECIACION, StandardCharsets.UTF_8)) {
            return (int) lineas.filter(linea -> !linea.isBlank()).count() - 1; // menos la cabecera
        }
    }

    private static Path manifiestoDeDepreciacion(String desde) throws IOException {
        return manifiestoDeDepreciacionCon(desde, derivadoDeDepreciacion());
    }

    private static Path manifiestoDeDepreciacionCon(String desde, Path filas) throws IOException {
        String ejercicio = desde.substring(0, 4);
        return escribir(
                "manifiesto-depreciacion-" + ejercicio + ".csv",
                CABECERA
                        + "\nTABLA_FICTICIA_DEPRECIACION,"
                        + ejercicio
                        + ","
                        + desde
                        + ",,Norma de mentira 000-0000-XX,"
                        + filas.getFileName()
                        + ","
                        + huella(filas)
                        + ",depreciacion.md,JNA,HNA,DEPRECIACION\n");
    }

    /**
     * La edicion que ese manifiesto abrio. Cada caso publica la suya y cuenta la suya: las cuatro
     * comparten una sola base, y contar la tabla entera haria que el orden de ejecucion decidiera
     * el resultado.
     */
    private static long edicionDeDepreciacion(String desde) {
        return publicacion
                .edicionPublicada(
                        new LlaveDeParametro(
                                "TABLA_FICTICIA_DEPRECIACION",
                                desde.substring(0, 4),
                                java.time.LocalDate.parse(desde)))
                .orElseThrow()
                .id();
    }

    private static int filasDeDepreciacion(long edicion) {
        Integer cuantas =
                jdbc.sql("SELECT count(*) FROM depreciacion WHERE publicacion_id = :edicion")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();
        return cuantas == null ? 0 : cuantas;
    }

    private static List<String> tablasDeDepreciacion(long edicion) {
        return jdbc.sql(
                        "SELECT DISTINCT uso FROM depreciacion WHERE publicacion_id = :edicion"
                                + " ORDER BY uso")
                .param("edicion", edicion)
                .query(String.class)
                .list();
    }

    private static int municipalidadesDeDepreciacion(long edicion) {
        Integer cuantas =
                jdbc.sql(
                                "SELECT count(municipalidad_id) FROM depreciacion"
                                        + " WHERE publicacion_id = :edicion")
                        .param("edicion", edicion)
                        .query(Integer.class)
                        .single();
        return cuantas == null ? 0 : cuantas;
    }

    /** El porcentaje de una celda, o {@code null} si el Anexo no la tabula. */
    private static @Nullable BigDecimal porcentajeDe(
            long edicion,
            String uso,
            String material,
            String estado,
            @Nullable Integer antiguedadHasta) {
        return jdbc.sql(
                        "SELECT porcentaje FROM depreciacion WHERE publicacion_id = :edicion"
                                + " AND uso = :uso"
                                + " AND material = :material AND estado_conservacion = :estado"
                                + " AND antiguedad_hasta IS NOT DISTINCT FROM :antiguedad")
                .param("edicion", edicion)
                .param("uso", uso)
                .param("material", material)
                .param("estado", estado)
                .param("antiguedad", antiguedadHasta)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private static int filasDe(String modelo) {
        Integer cuantas =
                jdbc.sql("SELECT count(*) FROM valor_referencial_vehiculo WHERE modelo = :modelo")
                        .param("modelo", modelo)
                        .query(Integer.class)
                        .single();
        return cuantas == null ? 0 : cuantas;
    }

    private static int municipalidadesDistintasDe(String modelo) {
        Integer cuantas =
                jdbc.sql(
                                "SELECT count(municipalidad_id) FROM valor_referencial_vehiculo"
                                        + " WHERE modelo = :modelo")
                        .param("modelo", modelo)
                        .query(Integer.class)
                        .single();
        return cuantas == null ? 0 : cuantas;
    }
}
