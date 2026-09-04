package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dos corridas de prueba contra el <b>mismo</b> cluster de PostgreSQL (#698).
 *
 * <p>Es el camino sin Docker que documenta {@code backend/README.md}: cada tarea crea su propia
 * base, pero las cuatro filas de {@code pg_authid} son del cluster y las comparten todas. Con una
 * clave aleatoria por tarea, la segunda le cambiaba la clave a la primera y la primera moria con
 * {@code FATAL: password authentication failed}, que acusa a la base, a la rama o al aislamiento —a
 * cualquier cosa menos a la corrida de al lado.
 *
 * <p><b>Por que la prueba monta un cluster compartido en vez de confiar en el de la corrida:</b>
 * con Testcontainers cada {@code MotorPostgres.iniciar()} levanta su propio motor, asi que dos
 * provisionamientos seguidos <i>no</i> comparten roles y la prueba pasaria en verde sin verificar
 * nada —el mismo modo de fallo contra el que existe la prueba de aislamiento—. Aqui se toma el
 * motor de la corrida como si fuera el cluster ajeno y se apunta a el con las mismas propiedades
 * que documenta el README, de modo que la prueba mide lo que dice medir tanto en CI (contenedor)
 * como por el camino sin Docker (motor externo).
 */
@DisplayName("#698 — Dos corridas de prueba contra el mismo cluster")
class ProvisionamientoCompartidoTest {

    @Test
    @DisplayName("la segunda corrida no le cambia la clave a la primera")
    void laSegundaNoLePisaLaClaveALaPrimera() throws Exception {
        conUnClusterCompartido(
                cluster -> {
                    try (MotorPostgres primera = MotorPostgres.iniciar();
                            MotorPostgres segunda = MotorPostgres.iniciar()) {
                        assertThat(List.of(primera, segunda, cluster))
                                .as(
                                        "el candado que las serializa es de la BASE, asi que las"
                                                + " tres tienen que citarse en la misma se escriba como"
                                                + " se escriba su URL; si cada una lo toma en la suya,"
                                                + " no se excluyen y vuelve el choque de catalogo")
                                .allSatisfy(
                                        motor -> {
                                            assertThat(motor.urlDeCoordinacion())
                                                    .isEqualTo(cluster.urlDeCoordinacion());
                                            assertThat(nombreDeLaBase(motor.urlDeCoordinacion()))
                                                    .isEqualTo(MotorPostgres.BASE_DE_COORDINACION);
                                        });

                        Map<String, String> deLaPrimera =
                                BaseDeDatosDePrueba.provisionarRoles(primera);
                        Map<String, String> deLaSegunda =
                                BaseDeDatosDePrueba.provisionarRoles(segunda);

                        for (String rol : BaseDeDatosDePrueba.ROLES) {
                            assertThatCode(
                                            () ->
                                                    BaseDeDatosDePrueba.abrir(
                                                                    primera.url(),
                                                                    rol,
                                                                    deLaPrimera.get(rol))
                                                            .close())
                                    .as(
                                            "la primera corrida sigue viva y tiene que poder entrar"
                                                    + " como "
                                                    + rol
                                                    + " despues de que la segunda provisione")
                                    .doesNotThrowAnyException();
                        }

                        assertThat(deLaSegunda)
                                .as(
                                        "las dos apuntan al mismo cluster, y cambiar la clave"
                                                + " de un rol es del cluster: si no sale igual, la"
                                                + " ultima en escribir deja fuera a la otra")
                                .isEqualTo(deLaPrimera);
                    }
                });
    }

    @Test
    @DisplayName("cuatro corridas provisionando a la vez no chocan en el catalogo")
    void cuatroALaVezNoChocanEnElCatalogo() throws Exception {
        conUnClusterCompartido(
                cluster -> {
                    List<MotorPostgres> motores = new ArrayList<>();
                    try {
                        // Los motores se crean en serie: dos CREATE DATABASE a la vez chocan por
                        // la plantilla —«source database template1 is being accessed by other
                        // users»— y ese rojo no es el que esta prueba mide.
                        for (int i = 0; i < 4; i++) {
                            motores.add(MotorPostgres.iniciar());
                        }
                        List<Throwable> fallos = provisionarALaVez(motores);
                        assertThat(fallos)
                                .as(
                                        "el catalogo choca igual aunque las cuatro escriban"
                                                + " el MISMO valor: «tuple concurrently updated» si"
                                                + " los roles ya estan, y la unicidad de pg_authid"
                                                + " al crearlos si el cluster es nuevo. Lo que lo"
                                                + " evita es el candado, no la clave derivada")
                                .isEmpty();
                    } finally {
                        motores.forEach(MotorPostgres::close);
                    }
                });
    }

    @Test
    @DisplayName("un 28P01 nombra la causa, y cualquier otro fallo pasa tal cual")
    void elMensajeNombraLaCausa() throws Exception {
        try (BaseDeDatosDePrueba base = BaseDeDatosDePrueba.provisionar()) {
            // Una clave que no es la del rol produce un 28P01 de verdad, dicho por el motor, sin
            // tocarle la clave a nadie: cambiarla aqui seria hacerle a las demas corridas de esta
            // misma maquina exactamente lo que el issue denuncia.
            assertThatThrownBy(
                            () ->
                                    BaseDeDatosDePrueba.abrir(
                                            base.url(), BaseDeDatosDePrueba.APP, "no-es-la-clave"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("otra corrida de pruebas")
                    .hasMessageContaining("del CLUSTER, no de la base")
                    .hasMessageContaining(BaseDeDatosDePrueba.APP)
                    .hasMessageNotContaining("password authentication failed");

            SQLException ajeno =
                    BaseDeDatosDePrueba.traducir(
                            BaseDeDatosDePrueba.APP, new SQLException("no such database", "3D000"));
            assertThat(ajeno.getMessage())
                    .as(
                            "traducir cualquier fallo taparia el que importa: un motor apagado o"
                                    + " una base que no existe tienen que seguir diciendo lo suyo")
                    .isEqualTo("no such database");
        }
    }

    @Test
    @DisplayName("la clave sale del cluster y del rol, no de un sorteo")
    void laClaveSeDerivaYNoSeSortea() {
        String unaVez = BaseDeDatosDePrueba.claveDeRol(7_654_321L, "admin", "sgtm_app");

        assertThat(BaseDeDatosDePrueba.claveDeRol(7_654_321L, "admin", "sgtm_app"))
                .as("dos tareas de la misma corrida tienen que derivar lo mismo")
                .isEqualTo(unaVez);
        assertThat(BaseDeDatosDePrueba.claveDeRol(7_654_322L, "admin", "sgtm_app"))
                .as("otro cluster —otro contenedor— no comparte clave con este")
                .isNotEqualTo(unaVez);
        assertThat(BaseDeDatosDePrueba.claveDeRol(7_654_321L, "otra", "sgtm_app"))
                .as("quien no tiene la credencial de administrador no puede derivarla")
                .isNotEqualTo(unaVez);
        assertThat(BaseDeDatosDePrueba.claveDeRol(7_654_321L, "admin", "sgtm_owner"))
                .as("cuatro roles, cuatro claves: una sola las haria intercambiables")
                .isNotEqualTo(unaVez);
        assertThat(unaVez).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("un solo sitio del arbol le cambia la clave a un rol")
    void unSoloSitioEscribeLaClaveDelRol() throws Exception {
        // El patron se compone a trozos para que este archivo no lo contenga: si no, el
        // escaner se encontraria a si mismo y la lista blanca tendria que taparlo.
        String sentencia = "ALTER" + " ROLE";
        String laClave = "PASS" + "WORD";
        List<String> archivos = new ArrayList<>();
        try (var rutas = java.nio.file.Files.walk(java.nio.file.Path.of(".."))) {
            for (java.nio.file.Path ruta : rutas.toList()) {
                String nombre = ruta.toString();
                if (!nombre.endsWith(".java") || nombre.contains("/build/")) {
                    continue;
                }
                String fuente = java.nio.file.Files.readString(ruta);
                if (fuente.contains(sentencia) && fuente.contains(laClave)) {
                    archivos.add(ruta.getFileName().toString());
                }
            }
        }

        assertThat(archivos)
                .as(
                        "cada copia de «%s ... %s» es una clave mas escrita sobre el CLUSTER,"
                                + " y con su propio criterio: la que tenia MigradorTest sorteaba la"
                                + " suya y le cambiaba la clave a las demas corridas, que es #698"
                                + " escrito por segunda vez",
                        sentencia, laClave)
                .containsExactly("BaseDeDatosDePrueba.java");
    }

    // ------------------------------------------------------------------

    /** El nombre de la base de una URL JDBC, sin sus parametros. */
    private static String nombreDeLaBase(String url) {
        String sinParametros = url.split("\\?", 2)[0];
        return sinParametros.substring(sinParametros.lastIndexOf('/') + 1);
    }

    /**
     * Provisiona con los cuatro motores a la vez y devuelve lo que haya fallado.
     *
     * <p>Se captura {@code Exception} a proposito: lo que se mide es <b>cuantas</b> corridas
     * simultaneas fallan y con que, asi que una que se caiga no puede llevarse por delante la
     * medida de las otras tres.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static List<Throwable> provisionarALaVez(List<MotorPostgres> motores)
            throws InterruptedException {
        List<Throwable> fallos = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch salida = new CountDownLatch(1);
        List<Thread> hilos = new ArrayList<>();
        for (MotorPostgres motor : motores) {
            Thread hilo =
                    new Thread(
                            () -> {
                                try {
                                    salida.await();
                                    BaseDeDatosDePrueba.provisionarRoles(motor);
                                } catch (Exception e) {
                                    fallos.add(e);
                                }
                            });
            hilo.start();
            hilos.add(hilo);
        }
        salida.countDown();
        for (Thread hilo : hilos) {
            hilo.join();
        }
        return fallos;
    }

    /**
     * Toma el motor de esta corrida como si fuera el cluster compartido del README y apunta a el
     * las propiedades del camino sin Docker, para que lo que se provisione dentro comparta roles.
     */
    private static void conUnClusterCompartido(PruebaDelCluster prueba) throws Exception {
        try (MotorPostgres cluster = MotorPostgres.iniciar()) {
            Map<String, String> antes = ajustesActuales();
            try {
                System.setProperty("kamayuk.pruebas.postgres.url", cluster.url());
                System.setProperty("kamayuk.pruebas.postgres.usuario", cluster.usuarioAdmin());
                System.setProperty("kamayuk.pruebas.postgres.clave", cluster.claveAdmin());
                prueba.correr(cluster);
            } finally {
                restaurar(antes);
            }
        }
    }

    /** Como {@code Runnable}, pero puede lanzar: aqui todo lo interesante lanza SQLException. */
    private interface PruebaDelCluster {
        void correr(MotorPostgres cluster) throws Exception;
    }

    private static Map<String, String> ajustesActuales() {
        Map<String, String> ajustes = new LinkedHashMap<>();
        for (String nombre :
                List.of(
                        "kamayuk.pruebas.postgres.url",
                        "kamayuk.pruebas.postgres.usuario",
                        "kamayuk.pruebas.postgres.clave")) {
            String valor = System.getProperty(nombre);
            ajustes.put(nombre, valor == null ? "" : valor);
        }
        return ajustes;
    }

    private static void restaurar(Map<String, String> ajustes) {
        ajustes.forEach(
                (nombre, valor) -> {
                    if (valor.isEmpty()) {
                        System.clearProperty(nombre);
                    } else {
                        System.setProperty(nombre, valor);
                    }
                });
    }
}
