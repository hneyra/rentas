package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.predial.Tramo;
import kamayuk.rentas.rentas.dominio.predial.TramosProgresivosAcumulativos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * P5B, criterio de aceptacion 2 — <b>el mismo centimo antes y despues de la extraccion</b>.
 *
 * <h2>Que compara, y por que esto es «el padron»</h2>
 *
 * <p>Escribe un archivo con todo lo que decide una cifra del predial: las 33 filas del conjunto
 * sellado tal como llegan al calculo, el cuadro que de ellas se compone —UIT, tramos, minimo
 * imponible y politicas de redondeo— y, para catorce autovaluos fijos, la base imponible y el
 * impuesto que salen. El archivo se compara con el que produce <b>el mismo codigo antes de P5B</b>,
 * byte a byte.
 *
 * <p>Las cifras no se inventan: salen de {@code parametros-2026.csv}, el derivado publicable del
 * corpus verificado a doble firma. Y no se leen del archivo directamente: se <b>publican</b>, se
 * componen en un conjunto, se sella, y se vuelven a leer por el camino de produccion. Ese camino es
 * exactamente el que P5B cambio —antes una tabla de esta base, ahora la copia local de un conjunto
 * descargado—, asi que es el que hay que medir.
 *
 * <p><b>Los catorce autovaluos son fijos y estan a la vista.</b> Cubren los tres tramos del art. 13
 * y sus dos fronteras exactas, mas el borde del minimo imponible: si algo cambiara la resolucion de
 * una cifra, la frontera es donde se ve primero.
 *
 * <h2>Por que un archivo y no un {@code assertEquals}</h2>
 *
 * <p>Porque el «antes» esta en otro arbol de git. Esta clase corre igual en los dos —los nombres y
 * las firmas que usa son los mismos— y lo que se compara es su salida. Un {@code assertEquals} solo
 * podria comparar contra un valor escrito a mano, y escribirlo a mano es exactamente lo que este
 * criterio existe para no tener que hacer.
 */
@DisplayName("P5B AC 2 — El padron recalculado, al centimo")
class PadronRecalculadoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /**
     * Donde se publica el valor normativo antes de componerlo.
     *
     * <p>Es lo <b>unico</b> que difiere entre los dos arboles, y por eso entra por propiedad y no
     * por codigo: antes de P5B la tabla se llamaba {@code parametro_tributario} y estaba en esta
     * base; despues se fue a `normativa` y las pruebas escriben su escenario en {@code
     * parametro_tributario_de_prueba}. Todo lo demas de esta clase —las firmas, el cuadro, la
     * regla— es identico en los dos, que es lo que hace comparable la salida.
     */
    private static final String TABLA_DE_PUBLICACION =
            System.getProperty("kamayuk.padron.tabla", "parametro_tributario_de_prueba");

    /** Donde se deja el archivo. Se compara entre arboles, no dentro de uno. */
    private static final Path SALIDA =
            Path.of(System.getProperty("kamayuk.padron.salida", "build/padron-recalculado.csv"));

    /**
     * Los catorce autovaluos, en soles.
     *
     * <p>Cubren los tres tramos del art. 13, sus dos fronteras exactas —15 y 60 UIT— y el borde del
     * minimo imponible (0,6 % de la UIT). Una frontera es donde una resolucion equivocada se ve
     * primero: un tramo que empieza un centimo antes cambia el impuesto de quien esta justo ahi y
     * de nadie mas.
     */
    private static final String[] AUTOVALUOS = {
        "0.00",
        "1000.00",
        "50000.00",
        "82500.00",
        "82500.01",
        "100000.00",
        "200000.00",
        "329999.99",
        "330000.00",
        "330000.01",
        "500000.00",
        "1000000.00",
        "2500000.00",
        "9999999.99"
    };

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static LectorDeParametros lector;
    private static AdministrarParametros administrar;
    private static TenantTransactionManager gestor;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("292001", "Municipalidad del padron");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        ParametrosRepositoryJdbc repositorio = new ParametrosRepositoryJdbc(jdbc);

        administrar =
                envolver(
                        new AdministrarParametros(
                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ));
        lector = envolver(new LectorDeParametrosSellados(repositorio));
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
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

    @Test
    @DisplayName("las cifras del corpus, publicadas, selladas, releidas y aplicadas")
    void elPadronAlCentimo() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
        try {
            long conjunto = publicarYSellarElCorpus();
            List<String> lineas = new ArrayList<>();

            // 1. El conjunto tal como llega al calculo. Es la mitad que P5B cambio de sitio.
            CuadroPredialParametrizado cuadro = new CuadroPredialParametrizado(lector);
            CuadroPredialParametrizado.Vigente vigente = cuadro.delConjunto(EJERCICIO, conjunto);

            lineas.add("seccion,clave,valor");
            lineas.add("cuadro,uit," + vigente.uit().valor().toPlainString());
            lineas.add(
                    "cuadro,minimoImponible," + vigente.minimoImponible().valor().toPlainString());
            int orden = 0;
            for (Tramo tramo : vigente.tramos()) {
                lineas.add(
                        "tramo,"
                                + orden++
                                + ","
                                + (tramo.limiteSuperior() == null
                                        ? "sin-tope"
                                        : tramo.limiteSuperior().valor().toPlainString())
                                + "|"
                                + tramo.alicuota().valor().toPlainString());
            }
            lineas.add("cuadro,redondeo," + vigente.redondeo().toString());

            // 2. Y el impuesto de cada autovaluo, que es lo que llega al recibo.
            for (String autovaluo : AUTOVALUOS) {
                lineas.add("padron," + autovaluo + "," + impuestoDe(vigente, Dinero.de(autovaluo)));
            }

            Files.createDirectories(SALIDA.toAbsolutePath().getParent());
            Files.write(SALIDA, String.join("\n", lineas).getBytes(StandardCharsets.UTF_8));

            assertThat(lineas)
                    .as("si esto quedara vacio, el archivo comparado no probaria nada")
                    .hasSizeGreaterThan(AUTOVALUOS.length);
            System.out.println(">>> PADRON ESCRITO EN " + SALIDA.toAbsolutePath());
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    /**
     * El impuesto del art. 13 sobre una base, con el cuadro del conjunto.
     *
     * <p><b>La cuenta la hace la regla de produccion</b>, {@link TramosProgresivosAcumulativos},
     * con las politicas de redondeo del mismo conjunto: escribirla aqui a mano habria medido esta
     * clase y no el sistema. Lo unico que este metodo pone es el minimo imponible, que el art. 13
     * fija aparte.
     *
     * <p>No se llama a {@code DeterminarPredial} porque aquel exige padron, predios y titularidad
     * —el escenario entero— y lo que este criterio compara es que el CUADRO llegue igual: las
     * reglas son puras y P5B no toca ni una. Si el cuadro cambiara un centimo, la frontera de tramo
     * lo delata.
     */
    private static String impuestoDe(CuadroPredialParametrizado.Vigente vigente, Dinero base) {
        Dinero porTramos =
                TramosProgresivosAcumulativos.calcular(base, vigente.tramos(), vigente.redondeo());
        Dinero minimo = vigente.minimoImponible();
        Dinero impuesto =
                base.esCero() || porTramos.valor().compareTo(minimo.valor()) >= 0
                        ? porTramos
                        : minimo;
        return impuesto.valor().toPlainString();
    }

    /** Publica las filas del derivado, las compone en un conjunto y lo sella. */
    private static long publicarYSellarElCorpus() throws Exception {
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(
                        EJERCICIO, Observacion.de("Se abre el ejercicio del AC 2"));
        // Las columnas van POR POSICION, como en el resto del proyecto: el encabezado del
        // derivado es tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,...
        for (FilaCsv fila : filasDelDerivado()) {
            List<String> campos = fila.campos();
            String tipo = campos.get(0);
            String clave = campos.get(1);
            String numero = campos.size() > 4 ? campos.get(4) : "";
            if (numero == null || numero.isBlank()) {
                continue;
            }
            long parametro = publicarParametro(tipo, clave, numero, campos.get(2), campos.get(3));
            administrar.agregarParametro(
                    conjunto.id(), parametro, Observacion.de("Se compone la fila " + tipo));
        }
        // Las dos filas de REDONDEO que el corpus NO trae: D-03c sigue abierta y ningun punto del
        // SRTM del MEF esta observado todavia (#203). Se declaran aqui, fijas y a la vista, porque
        // sin ellas el calculo falla por diseno —y lo que este criterio compara es que el resto
        // llegue igual, no la politica—. Son identicas en los dos arboles.
        for (PuntoDeRedondeo punto :
                List.of(PuntoDeRedondeo.IMPUESTO_POR_TRAMO, PuntoDeRedondeo.CUOTA)) {
            long redondeo = publicarRedondeo(punto, 2, "HALF_UP");
            administrar.agregarParametro(
                    conjunto.id(), redondeo, Observacion.de("Se observa el punto " + punto));
        }
        administrar.sellar(conjunto.id(), Observacion.de("Se sella el ejercicio del AC 2"));
        return conjunto.id();
    }

    private static List<FilaCsv> filasDelDerivado() throws IOException {
        try (Reader lectura =
                Files.newBufferedReader(
                        CorpusDeNormativa.derivadoPublicable(), StandardCharsets.UTF_8)) {
            return LectorDeFilasCsv.leer(lectura);
        }
    }

    /** Una politica de redondeo: escala en el numerico, modo en el texto. */
    private static long publicarRedondeo(PuntoDeRedondeo punto, int escala, String modo)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO "
                                        + TABLA_DE_PUBLICACION
                                        + " (municipalidad_id, tipo, clave, valor_numerico,"
                                        + " valor_texto, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, CAST(? AS numeric), ?,"
                                        + " DATE '2026-01-01', 'Escala y modo ficticios de la"
                                        + " prueba; D-03c sigue abierta', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, PoliticasDeRedondeoSelladas.TIPO);
            sentencia.setString(2, punto.name());
            sentencia.setString(3, String.valueOf(escala));
            sentencia.setString(4, modo);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long publicarParametro(
            String tipo, String clave, String numero, String desde, String hasta)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO "
                                        + TABLA_DE_PUBLICACION
                                        + " (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, vigencia_hasta,"
                                        + " documento_fuente, usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, CAST(? AS numeric),"
                                        + " CAST(? AS date), CAST(? AS date),"
                                        + " 'Derivado publicable del corpus', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave == null || clave.isBlank() ? null : clave);
            sentencia.setString(3, numero);
            sentencia.setString(4, desde == null || desde.isBlank() ? null : desde);
            sentencia.setString(5, hasta == null || hasta.isBlank() ? null : hasta);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
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
