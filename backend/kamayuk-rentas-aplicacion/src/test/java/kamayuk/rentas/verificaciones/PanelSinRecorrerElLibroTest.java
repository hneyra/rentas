package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.coactiva.ExpedientesSinRec;
import kamayuk.rentas.cuentacorriente.CarteraDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.indicadores.aplicacion.ConsultaDeTrabajoParado;
import kamayuk.rentas.indicadores.aplicacion.PanelDeRecaudacion;
import kamayuk.rentas.rentas.PrediosSinConciliar;
import kamayuk.rentas.sanciones.PapeletasSinNotificar;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.valores.ValoresSinNotificar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 4 de #56 — el panel no recorre el libro, y AC 3 — no toca mas que APIs publicas.
 *
 * <h2>Por que es una prueba estructural y no una medicion</h2>
 *
 * <p>Medir lo que cuesta el panel con un padron de verdad exigiria sembrar decenas de miles de
 * obligaciones, y una prueba asi tarda minutos y falla por motivos que no son el que mide. Lo que
 * si se puede afirmar y comprobar es la <b>forma</b>: que no exista ninguna manera de pedir el
 * padron entero desde aqui. Mientras no exista, no se puede cometer.
 *
 * <p>Es el mismo criterio de {@code ReportesDeVolumenTest} (AC 5 de #53), y el mismo con el que se
 * comprueba que ningun metodo de dominio recibe {@code municipalidadId}: no se mide si alguien lo
 * usa mal, se comprueba que no se puede.
 *
 * <p>La pantalla de inicio es ademas la que <b>todo el mundo</b> abre al entrar. Un panel que
 * recorriera el libro no seria una lentitud aislada: seria la conexion que la ventanilla necesita,
 * ocupada por cada persona que enciende el sistema por la mañana.
 */
@DisplayName("#56 — AC 4: el panel agrega, no recorre")
class PanelSinRecorrerElLibroTest {

    /**
     * Los puertos que el panel puede inyectar. El dia que aparezca un cuarto hay que añadirlo aqui.
     *
     * <p>Que cueste una linea es deliberado, igual que en {@code TIPOS_AJENOS_QUE_SOLO_SE_LEEN}: el
     * diff dice que API publica nueva mira el panel, y quien la añade tiene que comprobar que lo
     * que devuelve esta agregado.
     */
    private static final List<Class<?>> PUERTOS_DEL_PANEL =
            List.of(
                    RecaudacionDelLibro.class,
                    CarteraDelLibro.class,
                    AvanceDeCaja.class,
                    // Los cuatro frentes del trabajo parado (#549). Cada uno costo una linea
                    // aqui y otra en `kamayuk-rentas-indicadores/build.gradle.kts`, con su motivo.
                    PapeletasSinNotificar.class,
                    ValoresSinNotificar.class,
                    ExpedientesSinRec.class,
                    PrediosSinConciliar.class);

    /**
     * Las dos lecturas del panel. Las dos se revisan igual, y por lo mismo.
     *
     * <p>{@code ConsultaDeTrabajoParado} entro con #549 y es la segunda lectura de la misma
     * pantalla de aterrizaje: cuatro consultas mas a cuatro modulos mas. Si esta prueba solo mirara
     * {@code PanelDeRecaudacion}, la mitad del coste de la pantalla que todo el mundo abre al
     * entrar no la vigilaria nadie.
     */
    private static final List<Class<?>> LECTURAS_DEL_PANEL =
            List.of(PanelDeRecaudacion.class, ConsultaDeTrabajoParado.class);

    /**
     * Los tipos que son <b>una fila del padron</b>: tantos como obligaciones o asientos hay.
     *
     * <p>La distincion con una linea de agregado es la que sostiene esta prueba: una lista de filas
     * crece con el padron; una lista de {@code CargoDeUnTributo} crece con el numero de <b>tributos
     * distintos</b>, y la agrego PostgreSQL antes de devolverla.
     */
    private static final Set<String> TIPOS_DE_FILA =
            Set.of(
                    "ObligacionPublica",
                    "MovimientoDelLibro",
                    "Asiento",
                    "SaldoProyectado",
                    "ConvenioDelContribuyente",
                    "ReciboDeTramite",
                    // Las filas de los cuatro frentes de #549: una por papeleta, por valor,
                    // por expediente y por predio. Cada una de esas listas crece con el
                    // padron; el recuento que sus puertos devuelven, no.
                    "PapeletaDelPadron",
                    "ValorEnConsulta",
                    "ExpedienteEnConsulta",
                    "FichaConciliada");

    @Test
    @DisplayName("las lecturas del panel solo inyectan puertos publicos, y son los enumerados")
    void elPanelSoloInyectaPuertosPublicos() {
        for (Class<?> lectura : LECTURAS_DEL_PANEL) {
            Constructor<?>[] constructores = lectura.getDeclaredConstructors();
            assertThat(constructores)
                    .as("%s: un componente de Spring con un solo constructor", lectura.getName())
                    .hasSize(1);

            List<Class<?>> colaboradores = List.of(constructores[0].getParameterTypes());

            assertThat(colaboradores)
                    .as("si esto quedara vacio, la prueba pasaria sin comprobar nada")
                    .isNotEmpty();
            assertThat(colaboradores)
                    .as(
                            "%s no tiene modelo propio: lo unico que puede inyectar son APIs"
                                    + " publicas de otros modulos. Un repositorio, un JdbcClient o"
                                    + " un DataSource aqui serian el panel leyendo tablas ajenas"
                                    + " (AC 3)",
                            lectura.getSimpleName())
                    .isSubsetOf(PUERTOS_DEL_PANEL);
        }
    }

    @Test
    @DisplayName("todo lo que esos puertos devuelven ya viene agregado por el motor")
    void todoLoQueDevuelvenVieneAgregado() {
        List<String> devuelvenFilas = new ArrayList<>();
        List<String> revisados = new ArrayList<>();

        for (Class<?> puerto : puertosRevisados()) {
            for (Method metodo : puerto.getMethods()) {
                revisados.add(puerto.getSimpleName() + "#" + metodo.getName());
                String devuelve = metodo.getGenericReturnType().getTypeName();
                if (TIPOS_DE_FILA.stream().anyMatch(devuelve::contains)) {
                    devuelvenFilas.add(puerto.getSimpleName() + "#" + metodo.getName());
                }
            }
        }

        assertThat(revisados).as("sin metodos que revisar no hay nada que comprobar").isNotEmpty();
        assertThat(devuelvenFilas)
                .as(
                        "un metodo del panel que devuelva filas del padron es la manera de recorrer"
                                + " el libro en cada carga de la pantalla que todo el mundo abre al"
                                + " entrar: con treinta mil obligaciones se lleva por delante la"
                                + " conexion que la ventanilla necesita")
                .isEmpty();
    }

    @Test
    @DisplayName("las dos lecturas del panel son transaccionales y de solo lectura")
    void laLecturaEsTransaccionalYDeSoloLectura() throws Exception {
        exigirLecturaTransaccional(
                PanelDeRecaudacion.class.getMethod(
                        "del",
                        kamayuk.rentas.dominio.Ejercicio.class,
                        java.time.LocalDate.class,
                        java.time.Instant.class));
        exigirLecturaTransaccional(
                ConsultaDeTrabajoParado.class.getMethod(
                        "del",
                        kamayuk.rentas.dominio.Ejercicio.class,
                        java.time.LocalDate.class,
                        java.time.Instant.class,
                        Set.class));
    }

    private static void exigirLecturaTransaccional(Method del) {
        Transactional transaccional = del.getAnnotation(Transactional.class);

        assertThat(transaccional)
                .as(
                        "sin transaccion no hay SET LOCAL, y sin el la politica RLS no puede evaluar"
                                + " app.municipalidad_id: la consulta falla. Y con cuatro"
                                + " transacciones separadas, cada cifra saldria de un instante"
                                + " distinto")
                .isNotNull();
        assertThat(transaccional.readOnly())
                .as("un panel no escribe, y declararlo lo dice tambien al motor")
                .isTrue();
    }

    @Test
    @DisplayName("la cartera la agrega el motor, y la corta a la fecha que se le pide")
    void laCarteraLaAgregaElMotorYLaCortaALaFecha() throws Exception {
        // Hasta #639 esta comprobacion miraba `SaldoRepositoryJdbc`: la cartera salia de la
        // proyeccion, que no sabe aplicar una fecha de corte. Lo que #56 defendia sigue en
        // pie —la suma la hace PostgreSQL, no un bucle en Java— y se le suma lo que #639
        // encontro que faltaba: el corte. Sin el, la cifra es la misma preguntando por enero
        // que por diciembre, y estampar en ella la fecha del panel es afirmar que en enero ya
        // se debia la cuota de noviembre (regla 9).
        java.nio.file.Path fuente =
                raizDelBackend()
                        .resolve(
                                "kamayuk-rentas-cuentacorriente/src/main/java/kamayuk/rentas/cuentacorriente")
                        .resolve("infraestructura/AsientoRepositoryJdbc.java");
        assertThat(fuente).as("la fuente tiene que existir para poder revisarla").exists();

        String codigo =
                java.nio.file.Files.readString(fuente, java.nio.charset.StandardCharsets.UTF_8);
        int desde = codigo.indexOf("public List<PendienteAgregado> pendientePorTributo(");
        assertThat(desde).as("la consulta de la cartera tiene que existir aqui").isGreaterThan(0);
        String consulta = codigo.substring(desde, codigo.indexOf("\n    }", desde));

        assertThat(consulta)
                .as("la suma y el conteo los hace PostgreSQL, no un bucle en Java")
                .contains("sum(insoluto)")
                .contains("count(*)")
                .contains("GROUP BY tributo");
        assertThat(consulta)
                .as(
                        "y la corta a la fecha de corte: sin esta condicion la cartera incluye la"
                                + " cuota que aun no vence y la cifra no cambia con la fecha (#639)")
                .contains("a.fecha_valor <= :aLaFecha");
    }

    /**
     * Los puertos que hay que revisar: los enumerados <b>y</b> los que el panel inyecta de verdad.
     *
     * <p>La union, no una de las dos cosas. Solo con la lista, añadir un puerto que devuelve filas
     * y olvidarse de anotarlo dejaria la comprobacion en verde; solo con el constructor, vaciar la
     * lista dejaria de comprobar nada. Con las dos, la unica manera de que esto pase en verde es
     * que no exista ningun metodo que devuelva el padron.
     */
    private static List<Class<?>> puertosRevisados() {
        java.util.LinkedHashSet<Class<?>> puertos =
                new java.util.LinkedHashSet<>(PUERTOS_DEL_PANEL);
        for (Class<?> lectura : LECTURAS_DEL_PANEL) {
            for (Constructor<?> constructor : lectura.getDeclaredConstructors()) {
                puertos.addAll(List.of(constructor.getParameterTypes()));
            }
        }
        return List.copyOf(puertos);
    }

    /** La raiz de {@code backend/}, mirando hacia arriba desde el directorio de trabajo. */
    private static java.nio.file.Path raizDelBackend() {
        java.nio.file.Path actual = java.nio.file.Path.of("").toAbsolutePath();
        while (actual != null) {
            if (java.nio.file.Files.exists(actual.resolve("settings.gradle.kts"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro la raiz del backend");
    }
}
