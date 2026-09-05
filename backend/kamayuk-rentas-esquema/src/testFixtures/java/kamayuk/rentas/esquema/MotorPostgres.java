package kamayuk.rentas.esquema;

import java.util.Locale;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Resuelve el PostgreSQL contra el que corre la prueba de aislamiento.
 *
 * <p>Por omision levanta un contenedor con Testcontainers, que es lo que exige CAL-01 §2: una base
 * en memoria no tiene RLS y daria falsos verdes.
 *
 * <p>Admite apuntar a un PostgreSQL ya existente mediante {@code kamayuk.pruebas.postgres.url} (o
 * la variable de entorno {@code KAMAYUK_PRUEBAS_POSTGRES_URL}) para los entornos donde no se puede
 * descargar la imagen. Lo que NO admite es saltarse la prueba: sin motor, falla. Una prueba
 * bloqueante que se omite sola es peor que no tenerla, porque el build queda verde.
 *
 * <p>La conexion que entrega este objeto es de <b>superusuario</b> y sirve solo para provisionar.
 * Ninguna verificacion de aislamiento debe usarla: un superusuario omite RLS incluso con {@code
 * FORCE ROW LEVEL SECURITY} (DAT-01 §0, hallazgo 1).
 */
public final class MotorPostgres implements AutoCloseable {

    /**
     * La misma PostgreSQL 16 con PostGIS dentro (ADR-0021): {@code crear-roles.sql} instala la
     * extension antes de la primera migracion, y {@code postgres:16-alpine} no la trae, asi que con
     * esa imagen el aprovisionamiento falla con «extension "postgis" is not available».
     */
    private static final String IMAGEN_POR_OMISION = "postgis/postgis:16-3.4-alpine";

    /**
     * La version mayor de PostgreSQL contra la que este producto se prueba y se despliega.
     *
     * <p>No es una preferencia: es la que declaran los ambientes y CI. {@code Pulumi.prod.yaml},
     * {@code Pulumi.stg.yaml}, los dos {@code compose} de la plataforma, el guion de respaldo y el
     * {@link #IMAGEN_POR_OMISION} de aqui dicen todos {@code postgis/postgis:16-3.4-alpine}.
     *
     * <p><b>Por que hay una guarda y no solo una declaracion.</b> Porque nada la comprobaba. El
     * camino de Testcontainers fija la imagen, pero la salida de emergencia —{@code
     * kamayuk.pruebas.postgres.url}, la que se usa en toda maquina sin Docker— apunta al motor que
     * tenga quien construye, y en una maquina con varias versiones instaladas el {@code postgres}
     * del PATH puede ser cualquiera. Medido en la maquina donde se escribio esto: {@code psql
     * --version} devuelve 18.6 mientras el producto despliega 16.
     */
    static final int MAJOR_SOPORTADA = 16;

    /**
     * El motivo por el que esa version mayor no se admite, o vacio si se admite.
     *
     * <p>Es una funcion pura y no una consulta a proposito: asi {@code VersionDelMotorTest} la
     * puede ejercitar con 15, 16, 17 y 18 sin tener cuatro motores instalados. Lo que hace falta
     * medir contra un motor de verdad se midio una vez y esta escrito en {@code
     * C-4-postgresql-18.md}; lo que esta prueba sujeta es que la decision siga puesta.
     *
     * <p><b>Los dos lados no dicen lo mismo, y no deben.</b> De 17 en adelante hay un defecto
     * medido y reproducible; por debajo de 16 lo unico que hay es que nadie lo ha probado. Dar el
     * mismo mensaje a las dos cosas haria creer que la segunda tambien esta rota.
     */
    static java.util.Optional<String> motivoDeVersionNoSoportada(int major) {
        if (major == MAJOR_SOPORTADA) {
            return java.util.Optional.empty();
        }
        if (major > MAJOR_SOPORTADA) {
            return java.util.Optional.of(
                    "PostgreSQL "
                            + major
                            + ", y este producto se prueba y se despliega contra PostgreSQL "
                            + MAJOR_SOPORTADA
                            + " (postgis/postgis:16-3.4-alpine, en los dos Pulumi, en los dos"
                            + " compose y en la imagen por omision de este mismo motor)."
                            + " De 17 en adelante NO es solo que no este probado: PostgreSQL 17"
                            + " restringe el search_path de CREATE INDEX a pg_catalog, pg_temp, y"
                            + " nombre_normalizado resuelve por search_path tanto la funcion"
                            + " unaccent como el literal 'unaccent'::regdictionary. Medido: el"
                            + " baseline de rentas muere en V1 linea 2923 y el del monolito en V11"
                            + " linea 44, los dos con «text search dictionary \"unaccent\" does not"
                            + " exist ... during inlining». C-4 arreglo la funcion, pero con una"
                            + " migracion NUEVA —que repara la restauracion logica en 16 y no puede"
                            + " salvar a V1, porque corre despues—, asi que rentas sigue sin aplicar"
                            + " en 18. El motivo entero, con lo que se midio, esta en"
                            + " infrastructure/docs/00-gobierno/C-4-postgresql-18.md");
        }
        return java.util.Optional.of(
                "PostgreSQL "
                        + major
                        + ", que es mas antigua que la "
                        + MAJOR_SOPORTADA
                        + " contra la que este producto se prueba y se despliega. No hay ningun"
                        + " defecto medido en ella: lo que no hay es una sola corrida que lo"
                        + " demuestre, y las pruebas de plan (#313, #536, #561, #565) afirman"
                        + " planes que el planificador de otra version puede no producir");
    }

    /**
     * Exige que el motor sea el de {@link #MAJOR_SOPORTADA}, y lo dice cuando no lo es.
     *
     * <p>Es lo que separa un mensaje que se entiende de «text search dictionary "unaccent" does not
     * exist» a mitad de una migracion, que es lo que hoy recibe quien apunta la salida de
     * emergencia a un PostgreSQL 18 y que no se parece en nada a su causa.
     *
     * <p><b>No tiene puerta de escape</b>, por lo mismo que no la tiene saltarse la prueba de
     * aislamiento: una que se pudiera poner con una propiedad seria la que alguien pone para que el
     * build deje de quejarse. Medir otra version se hace fuera de Gradle —asi se midio C-4—, no
     * apagando esto.
     */
    private void exigirVersionSoportada() {
        String numero = unaCadena(url, "SHOW server_version_num", usuarioAdmin, claveAdmin);
        int major;
        try {
            major = Integer.parseInt(numero.trim()) / 10000;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "No se pudo leer la version del motor de prueba: SHOW server_version_num"
                            + " devolvio «"
                            + numero
                            + "». Sin saber contra que version se prueba, un verde no dice nada",
                    e);
        }
        motivoDeVersionNoSoportada(major)
                .ifPresent(
                        motivo -> {
                            throw new IllegalStateException("El motor de prueba es " + motivo);
                        });
    }

    /**
     * La codificacion que la base de prueba declara, sea cual sea la del anfitrion.
     *
     * <p>{@code CREATE DATABASE} a secas hereda la de {@code template1}, y esa la fija {@code
     * initdb} con el <i>locale</i> del entorno: basta que quien creo el cluster no tuviera {@code
     * LANG} puesto para que quede en {@code SQL_ASCII} sin que nada lo diga (#706, de once
     * clusteres locales tres estaban asi).
     *
     * <p>Y entonces el sintoma no se parece a su causa: {@code ViaRepositoryJdbc} (#565) cierra el
     * rango de prefijo con {@code chr(1114111)} —el ultimo punto de codigo de Unicode—, en {@code
     * SQL_ASCII} ese caracter no existe, y lo que se ve son cinco pruebas de {@code
     * BusquedaDelCatalogoVialTest} en rojo diciendo «requested character too large for encoding».
     */
    private static final String CODIFICACION = "UTF8";

    /**
     * La intercalacion y el tipo de caracter de la base de prueba.
     *
     * <p><b>Las dos mitades de esta cadena no valen lo mismo, y se midio cual es la que decide.</b>
     *
     * <p>La <i>intercalacion</i> —el orden— es orden de byte tanto en {@code C} como en {@code
     * C.UTF-8}: {@code 'a' < 'B'} da falso en las dos. Y los indices de prefijo se declaran {@code
     * text_pattern_ops} (V14, V66), que ordena por byte pase lo que pase, asi que las pruebas de
     * plan (#313, #536, #561, #565) no dependen de esta eleccion.
     *
     * <p>Quien decide es el <b>tipo de caracter</b>. Con {@code LC_CTYPE 'C'} sobre una base UTF-8,
     * {@code lower} y {@code upper} solo conocen el ASCII: medido, {@code lower('CAÑETE')} devuelve
     * {@code 'caÑete'} y {@code upper('ñ')} devuelve {@code 'ñ'}. Eso rompe el filtro por uso de
     * {@code FichaCatastralRepositoryJdbc}, que compara {@code upper(translate(f.uso, …))} contra
     * lo que la pantalla manda ya en mayusculas —y que deja la {@code ñ} sin plegar a proposito,
     * para que «AÑO» y «ANO» no sean la misma palabra—: un uso con {@code ñ} («DISEÑO», «CAMPIÑA»)
     * deja de encontrarse y la respuesta son cero filas, que se lee como «no hay ninguna ficha
     * asi».
     *
     * <p>Por eso se declara una que si conoce el UTF-8, y por eso <b>no hay repliegue a {@code
     * C}</b> cuando falta: replegarse cambiaria un fallo ruidoso por ese silencio.
     *
     * <p>Y por eso lo sujeta {@code CodificacionDeLaBaseDePruebaTest} y no el banco de pruebas:
     * medido, con {@code INTERCALACION = "C"} las <b>417</b> pruebas de catastro pasan en verde
     * —las cuatro de plan de #565 incluidas—, asi que hoy nadie mas notaria el cambio.
     */
    private static final String INTERCALACION = "C.UTF-8";

    /**
     * La base donde se citan todas las corridas para serializar el provisionamiento de los roles
     * (#698). {@code initdb} la crea en todo cluster de PostgreSQL, incluido el de Testcontainers,
     * y por eso vale como punto de cita: no depende de como cada quien escriba su URL.
     */
    static final String BASE_DE_COORDINACION = "postgres";

    private final PostgreSQLContainer<?> contenedor;
    private final String url;
    private final String usuarioAdmin;
    private final String claveAdmin;
    private String urlDeMantenimiento;
    private String nombreDeLaBase;

    private MotorPostgres(
            PostgreSQLContainer<?> contenedor, String url, String usuario, String clave) {
        this.contenedor = contenedor;
        this.url = url;
        this.usuarioAdmin = usuario;
        this.claveAdmin = clave;
    }

    /**
     * El motor, comprobado.
     *
     * <p>La comprobacion vale para los <b>dos</b> caminos y no solo para el externo: la imagen
     * tambien se puede cambiar con {@code kamayuk.pruebas.postgres.imagen}, y una que no fuera
     * UTF-8 traeria el mismo defecto por la otra puerta.
     *
     * <p>Si falla, el motor se cierra antes de relanzar: {@code BaseDeDatosDePrueba.provisionar}
     * solo puede cerrar lo que ya tiene en la mano, y aqui todavia no lo tiene, asi que sin esto
     * quedaria el contenedor levantado o la base creada.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public static MotorPostgres iniciar() {
        MotorPostgres motor = resolver();
        try {
            motor.exigirVersionSoportada();
            motor.exigirCodificacionUtf8();
            return motor;
        } catch (RuntimeException e) {
            motor.close();
            throw e;
        }
    }

    private static MotorPostgres resolver() {
        String urlExterna = ajuste("kamayuk.pruebas.postgres.url");
        if (urlExterna != null && !urlExterna.isBlank()) {
            return conMotorExterno(
                    urlExterna,
                    obligatorio("kamayuk.pruebas.postgres.usuario"),
                    obligatorio("kamayuk.pruebas.postgres.clave"));
        }
        String imagen = ajuste("kamayuk.pruebas.postgres.imagen");
        PostgreSQLContainer<?> contenedor =
                new PostgreSQLContainer<>(
                        nombreDeImagen(
                                imagen == null || imagen.isBlank() ? IMAGEN_POR_OMISION : imagen));
        contenedor.start();
        return new MotorPostgres(
                contenedor,
                contenedor.getJdbcUrl(),
                contenedor.getUsername(),
                contenedor.getPassword());
    }

    /**
     * El nombre de la imagen, declarando la compatibilidad de PostGIS con {@code postgres}.
     *
     * <p>{@code PostgreSQLContainer} exige que la imagen se llame {@code postgres} o que se declare
     * sustituta suya, y {@code postgis/postgis} no se llama asi: sin esto, cada prueba de base
     * muere en su {@code @BeforeAll} con «Failed to verify that image … is a compatible substitute
     * for 'postgres'», que no se parece en nada a su causa.
     *
     * <p>La declaracion es <b>solo para las imagenes de PostGIS</b>, y no un {@code
     * asCompatibleSubstituteFor} indiscriminado: la comprobacion de Testcontainers existe para
     * atrapar una imagen que no es PostgreSQL, y desactivarla del todo cambiaria un fallo claro por
     * uno raro. {@code postgis/postgis} SI es la PostgreSQL oficial con extensiones encima, que es
     * exactamente el caso que Testcontainers pide declarar.
     */
    private static DockerImageName nombreDeImagen(String imagen) {
        DockerImageName nombre = DockerImageName.parse(imagen);
        return imagen.startsWith("postgis/postgis")
                ? nombre.asCompatibleSubstituteFor("postgres")
                : nombre;
    }

    public String url() {
        return url;
    }

    public String usuarioAdmin() {
        return usuarioAdmin;
    }

    public String claveAdmin() {
        return claveAdmin;
    }

    /**
     * La base sobre la que se coordina el provisionamiento de los roles.
     *
     * <p>Existe porque los candados de asesoramiento de PostgreSQL son <b>de la base</b> —medido:
     * dos sesiones en bases distintas toman la misma clave a la vez sin esperarse— y los roles son
     * <b>del cluster</b>. Tomar el candado en la base recien creada de esta corrida no excluiria a
     * nadie, porque nadie mas se conecta a ella (#698).
     *
     * <p>Y no basta con volver a la URL que dieron: dos tareas pueden apuntar al mismo cluster
     * nombrando bases de mantenimiento distintas —{@code /postgres} y {@code /sgtm}— y volverian a
     * no verse. El punto de cita tiene que ser el mismo se escriba como se escriba la URL, asi que
     * es {@link #BASE_DE_COORDINACION}, la que {@code initdb} crea en todo cluster.
     */
    String urlDeCoordinacion() {
        return reemplazarBaseDeDatos(
                urlDeMantenimiento != null ? urlDeMantenimiento : url, BASE_DE_COORDINACION);
    }

    @Override
    public void close() {
        if (contenedor != null) {
            contenedor.stop();
            return;
        }
        if (urlDeMantenimiento != null) {
            ejecutar(
                    urlDeMantenimiento,
                    "DROP DATABASE IF EXISTS " + nombreDeLaBase + " WITH (FORCE)",
                    "No se pudo borrar la base de prueba " + nombreDeLaBase);
        }
    }

    /**
     * Crea una base nueva para esta corrida sobre un motor ya existente.
     *
     * <p>Testcontainers entrega un motor limpio por contenedor. Sin esto, la salida de emergencia
     * no daria la misma garantia: dos modulos de prueba apuntando a la misma URL compartirian base,
     * se pisarian las migraciones y los datos sembrados, y el fallo apareceria como un choque de
     * claves unicas en lugar de como lo que es.
     */
    private static MotorPostgres conMotorExterno(String urlBase, String usuario, String clave) {
        String nombre = "sgtm_prueba_" + UUID.randomUUID().toString().substring(0, 8);
        crearBase(urlBase, nombre, usuario, clave);
        MotorPostgres motor =
                new MotorPostgres(null, reemplazarBaseDeDatos(urlBase, nombre), usuario, clave);
        motor.urlDeMantenimiento = urlBase;
        motor.nombreDeLaBase = nombre;
        return motor;
    }

    /** Cambia el nombre de la base en una URL JDBC, conservando host, puerto y parametros. */
    static String reemplazarBaseDeDatos(String url, String nombre) {
        int inicioDeParametros = url.indexOf('?');
        String sinParametros = inicioDeParametros < 0 ? url : url.substring(0, inicioDeParametros);
        String parametros = inicioDeParametros < 0 ? "" : url.substring(inicioDeParametros);
        int ultimaBarra = sinParametros.lastIndexOf('/');
        if (ultimaBarra < 0) {
            throw new IllegalArgumentException("URL JDBC sin nombre de base: " + url);
        }
        return sinParametros.substring(0, ultimaBarra + 1) + nombre + parametros;
    }

    /**
     * Crea la base declarando su codificacion, y explica el fallo cuando el anfitrion no la da.
     *
     * <p>{@code TEMPLATE template0} no es un adorno, y se midio: sin el, sobre un cluster {@code
     * SQL_ASCII} la sentencia se rechaza con «new encoding (UTF8) is incompatible with the encoding
     * of the template database (SQL_ASCII)». Desde {@code template1} PostgreSQL solo deja copiar la
     * codificacion que esa plantilla ya tiene, que es justo la que aqui NO se quiere heredar;
     * {@code template0} esta vacia y admite que se le declare otra.
     *
     * <p>El fallo que queda posible es que la intercalacion declarada no exista en el sistema
     * —medido: {@code en_US.UTF-8} no esta en esta maquina y {@code CREATE DATABASE} la rechaza—, y
     * ahi el mensaje de PostgreSQL habla de {@code LC_COLLATE} sin decir contra que cluster se
     * estaba probando. Por eso se envuelve nombrando lo que el anfitrion tiene y lo que hay que
     * hacer.
     */
    private static void crearBase(String urlBase, String nombre, String usuario, String clave) {
        String sentencia = sentenciaDeCreacion(nombre);
        try (java.sql.Connection conexion =
                        java.sql.DriverManager.getConnection(urlBase, usuario, clave);
                java.sql.Statement statement = conexion.createStatement()) {
            statement.execute(sentencia);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(
                    "No se pudo crear la base de prueba "
                            + nombre
                            + " declarando "
                            + CODIFICACION
                            + " / "
                            + INTERCALACION
                            + ". El anfitrion tiene "
                            + comoEstaLaPlantilla(urlBase, usuario, clave)
                            + ", y la base de prueba no la hereda a proposito (#706). Si lo que"
                            + " falta es la intercalacion, hay que instalarla en el sistema"
                            + " (en Debian, locale-gen); replegarse a C no vale, porque con ese"
                            + " tipo de caracter lower y upper dejan de conocer la enye",
                    e);
        }
    }

    /**
     * La sentencia que crea la base de prueba, con las tres cosas que declara.
     *
     * <p>Es un metodo y no texto en linea para que {@code CodificacionDeLaBaseDePruebaTest} pueda
     * leer <b>esta</b> sentencia y no una copia suya. La comprobacion de que la base resultante es
     * UTF-8 solo puede fallar contra un anfitrion que no lo sea, asi que en un cluster UTF-8 —el de
     * CI— pasaria en verde diga lo que diga la sentencia; lo que si se puede comprobar en cualquier
     * maquina es que la sentencia siga declarando lo que #706 midio.
     */
    static String sentenciaDeCreacion(String nombre) {
        return "CREATE DATABASE "
                + nombre
                + " TEMPLATE template0 ENCODING '"
                + CODIFICACION
                + "' LC_COLLATE '"
                + INTERCALACION
                + "' LC_CTYPE '"
                + INTERCALACION
                + "'";
    }

    /**
     * Exige que la base contra la que se va a probar sea UTF-8, y lo dice cuando no lo es.
     *
     * <p>Es lo que separa un mensaje que se arregla en un minuto de cinco rojos en {@code
     * BusquedaDelCatalogoVialTest} hablando de Unicode (#706). Muerde, y esta medido: sobre un
     * cluster {@code SQL_ASCII}, quitandole a {@code crearBase} la declaracion los 417 casos del
     * modulo de catastro caen en 30 clases con ESTE mensaje; quitando ademas esta guarda —el estado
     * anterior a #706— quedan 5 rojos, todos en {@code BusquedaDelCatalogoVialTest} y todos
     * diciendo «requested character too large for encoding: 1114111», con los otros 412 en verde.
     *
     * <p>Treinta rojos claros por cinco confusos es el cambio que se quiso: <b>no</b> se puede
     * evitar que sean pruebas de catastro las que se pongan rojas, porque el motor lo resuelve cada
     * modulo en su propio {@code @BeforeAll}; lo que se puede es que digan cual es la causa.
     *
     * <p>Comprueba <b>solo la codificacion</b>. La intercalacion no se exige porque la del
     * contenedor de CI no se ha medido, y una comprobacion que afirma lo que no se ha medido pone
     * en rojo el camino bueno.
     */
    private void exigirCodificacionUtf8() {
        String codificacion = unaCadena(url, "SHOW server_encoding", usuarioAdmin, claveAdmin);
        if (!CODIFICACION.equalsIgnoreCase(codificacion)) {
            throw new IllegalStateException(
                    "La base de prueba quedo en "
                            + codificacion
                            + " y las pruebas necesitan "
                            + CODIFICACION
                            + ": en SQL_ASCII no existe chr(1114111), con el que #565 cierra el"
                            + " rango de prefijo del catalogo vial. Si el motor lo levanta"
                            + " Testcontainers, la imagen de kamayuk.pruebas.postgres.imagen no es"
                            + " UTF-8; si es externo, la base se creo sin declarar su codificacion"
                            + " (#706)");
        }
    }

    /** El primer valor de la primera fila, o {@code "desconocida"} si no se puede consultar. */
    private static String unaCadena(String url, String consulta, String usuario, String clave) {
        try (java.sql.Connection conexion =
                        java.sql.DriverManager.getConnection(url, usuario, clave);
                java.sql.Statement statement = conexion.createStatement();
                java.sql.ResultSet filas = statement.executeQuery(consulta)) {
            return filas.next() ? filas.getString(1) : "desconocida";
        } catch (java.sql.SQLException e) {
            return "desconocida";
        }
    }

    /**
     * Como esta {@code template1} en el anfitrion, para poder nombrarlo en el mensaje del fallo.
     */
    private static String comoEstaLaPlantilla(String url, String usuario, String clave) {
        return unaCadena(
                url,
                "SELECT pg_encoding_to_char(encoding) || ' / ' || datcollate"
                        + " FROM pg_database WHERE datname = 'template1'",
                usuario,
                clave);
    }

    private void ejecutar(String url, String sentencia, String mensaje) {
        ejecutar(url, sentencia, mensaje, usuarioAdmin, claveAdmin);
    }

    private static void ejecutar(
            String url, String sentencia, String mensaje, String usuario, String clave) {
        try (java.sql.Connection conexion =
                        java.sql.DriverManager.getConnection(url, usuario, clave);
                java.sql.Statement statement = conexion.createStatement()) {
            statement.execute(sentencia);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(mensaje, e);
        }
    }

    private static String ajuste(String nombre) {
        String valor = System.getProperty(nombre);
        if (valor != null) {
            return valor;
        }
        return System.getenv(nombre.toUpperCase(Locale.ROOT).replace('.', '_'));
    }

    private static String obligatorio(String nombre) {
        String valor = ajuste(nombre);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Falta "
                            + nombre
                            + ": al fijar kamayuk.pruebas.postgres.url hay que dar tambien"
                            + " usuario y clave de un rol con privilegios de superusuario");
        }
        return valor;
    }
}
