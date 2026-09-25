package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.comun.verificaciones.ReglasDeArquitectura;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ninguna operacion publica una hora de la que no se pueda saber a que zona pertenece (#188).
 *
 * <h2>Por que hace falta una guarda y no basta con el contrato</h2>
 *
 * <p>#188 cambio los siete campos de hora de las cinco operaciones que los publicaban: eran {@link
 * Instant} —{@code 2026-08-13T14:41:12Z}, o sea UTC— y son {@link OffsetDateTime} —{@code
 * 2026-08-13T09:41:12-05:00}, la hora de la municipalidad con su desfase encima, via {@link
 * kamayuk.rentas.dominio.ZonaHoraria#conSuDesfase(Instant)}—.
 *
 * <p><b>Y {@code docs/50-api/formas-de-la-api.json} no lo nota</b>, medido: {@link
 * FormaDeLaRespuesta} reduce {@code Instant}, {@code OffsetDateTime}, {@code LocalDateTime} y
 * {@code LocalTime} a la <b>misma</b> hoja, {@code «instante»}. El archivo salio byte a byte igual
 * antes y despues del cambio. Eso esta bien para lo que ese archivo hace —comparar
 * <i>estructura</i> entre dos lenguajes— y significa que <b>nada</b> se pondria rojo si manana un
 * {@code Resource} volviera a declarar un {@code Instant}: la pantalla pintaria las 14:41 de un
 * recibo anulado a las 09:41, igual que antes de #188 y esta vez sin decir «UTC».
 *
 * <p>Asi que la regresion la vigila esto, que no mira el contrato sino los tipos de retorno de los
 * controladores, que es donde vive la diferencia.
 *
 * <h2>Que prohibe, exactamente</h2>
 *
 * <ul>
 *   <li>{@link Instant}: su JSON acaba en {@code Z}, y quien lo lee tiene que saber donde esta la
 *       municipalidad para restar. Es el defecto de #188.
 *   <li>{@link LocalDateTime} y {@link LocalTime}: publican los digitos de la hora <b>sin</b>
 *       desfase, que es peor que UTC — el cliente vuelve a adivinar y esta vez sin saber que
 *       adivina. Estan aqui aunque hoy no los use nadie: la lista de lo prohibido se escribe
 *       entera, no segun lo que haya.
 * </ul>
 *
 * <p>Lo que <b>no</b> prohibe: los {@code ZoneOffset.UTC} de {@code AplicarUnEventoDeIdentidad} y
 * {@code ValorMasivoRepositoryJdbc}. Ninguno sale por HTTP —tres son parametros de un {@code
 * INSERT} y el cuarto mapea una columna a un record de dominio que ningun controlador devuelve—, y
 * por eso esta guarda no los ve: mira lo que se publica, no lo que se escribe.
 *
 * <h2>Y el tipo no basta (#317)</h2>
 *
 * <p>{@code registro.fecha().atOffset(ZoneOffset.UTC)} en lugar de {@code
 * ZonaHoraria.conSuDesfase(registro.fecha())} deja el campo como {@code OffsetDateTime} —el tipo
 * correcto— y lo publica con {@code Z}. Las dos primeras pruebas no lo ven, porque miran el tipo.
 * Lo mira {@link #ningunaHoraPublicadaEligeSuDesfaseAMano()}, que lee el <b>bytecode</b> de lo que
 * se publica, y lo miran, campo por campo, las pruebas de cada {@code Resource} que afirman el
 * {@code -05:00} con una hora de entre las 19:00 y la medianoche.
 */
@DisplayName("#188 — Ninguna hora se publica sin su desfase")
class NingunaHoraSePublicaSinSuDesfaseTest {

    /**
     * Los tipos de hora que no pueden salir por HTTP, con el motivo que se imprime en el rojo.
     *
     * <p>{@code java.util.Date} y {@code java.sql.Timestamp} no estan porque no son tipos de
     * frontera en este arbol —la regla de ArchUnit de la libreria comun ya los mantiene fuera de
     * {@code dominio}—, y anadir a una lista tipos que nadie puede escribir no protege nada.
     */
    /**
     * Los tipos cuyo {@code toString()} escribe una hora con la zona que traiga el valor (#327).
     *
     * <p>Por nombre y no por {@code Class}: es con lo que ArchUnit nombra el dueno de la llamada.
     */
    private static final Set<String> HORAS_QUE_NO_SE_ESCRIBEN_A_MANO =
            Set.of(
                    Instant.class.getName(),
                    OffsetDateTime.class.getName(),
                    ZonedDateTime.class.getName(),
                    LocalDateTime.class.getName());

    private static final Map<Class<?>, String> PROHIBIDOS =
            Map.of(
                    Instant.class,
                    "sale en UTC («…Z») y deja la resta al cliente (#188)",
                    LocalDateTime.class,
                    "publica la hora SIN desfase: el cliente vuelve a adivinar",
                    LocalTime.class,
                    "publica la hora SIN desfase ni dia");

    @Test
    @DisplayName("las cinco operaciones que publican una hora la publican con su desfase")
    void lasHorasPublicadasLlevanSuDesfase() {
        List<String> encontradas = new ArrayList<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            recorrer(
                    endpoint.getValue().getGenericReturnType(),
                    endpoint.getKey(),
                    "",
                    new LinkedHashSet<>(),
                    encontradas);
        }

        assertThat(encontradas)
                .as(
                        "una hora publicada tiene que llevar su desfase: usa"
                                + " ZonaHoraria.conSuDesfase(instante) en el Resource y declara el"
                                + " campo como OffsetDateTime. El contrato NO lo caza —las cuatro"
                                + " formas salen como «instante» en formas-de-la-api.json—, asi que si"
                                + " esto se salta no se pone rojo nada mas.")
                .isEmpty();
    }

    @Test
    @DisplayName("y hay horas que mirar: sin campos de hora esta prueba no diria nada")
    void hayHorasQueMirar() {
        // Una prueba que barre buscando lo prohibido sale verde tambien cuando no barre nada.
        // Este contador es lo que distingue «no hay ningun Instant publicado» de «el recorrido
        // no entra en los Resource»: si el resolutor se rompiera, las dos pruebas se caerian.
        List<String> conDesfase = new ArrayList<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            recorrer(
                    endpoint.getValue().getGenericReturnType(),
                    endpoint.getKey(),
                    "",
                    new LinkedHashSet<>(),
                    conDesfase,
                    OffsetDateTime.class);
        }

        assertThat(conDesfase)
                .as(
                        "las siete horas que #188 convirtio, en sus cinco operaciones, y las tres"
                                + " que salian como texto en UTC y #327 convirtio: «recibidoEn» y"
                                + " «aplicadoEn» de POST /pagos y «historialDePlacas[].fecha» de"
                                + " GET /rentas/vehiculos/{placa}. Si esta lista se vacia, el"
                                + " recorrido dejo de entrar en los Resource y la prueba de arriba"
                                + " esta saliendo verde sin mirar nada")
                .hasSize(10);
    }

    /**
     * Ninguna hora se escribe como texto fuera de {@link kamayuk.rentas.dominio.ZonaHoraria}
     * (#327).
     *
     * <h2>El camino que las dos pruebas de arriba no miran</h2>
     *
     * <p>Las dos de arriba miran el <b>tipo</b> de lo que se publica. Un campo {@code String} les
     * es invisible, y por ahi salieron tres horas en UTC —{@code pago.recibidoEn().toString()},
     * {@code cambio.fecha().toString()}— que {@code formas-de-la-api.json} tipaba {@code «texto»} y
     * la interfaz, que exige el desfase, habria rechazado. Y por el mismo camino salia la «Fecha de
     * ingreso» del acta de internamiento, que no es una respuesta JSON sino <b>un documento que se
     * entrega</b>: {@code 2026-03-05T01:00:00Z} para un ingreso de las 20:00 del dia 4. Ninguna de
     * las cuatro tenia una prueba que la viera.
     *
     * <p>Asi que esto no mira nombres ni tipos de campo, sino <b>el bytecode</b>: una llamada a
     * {@code toString()} sobre un {@link Instant}, un {@link OffsetDateTime}, un {@link
     * ZonedDateTime} o un {@link LocalDateTime} en el codigo de produccion. El tipo del receptor
     * esta en la instruccion, asi que no hay que adivinarlo leyendo texto. Medido antes de
     * escribirla, en todo {@code backend/*&#47;src/main}: <b>cinco</b> llamadas, y las cinco eran
     * los cuatro sitios de #327 (el acta tiene dos, la de ingreso y la de movimiento).
     *
     * <p><b>Por que no por el nombre del campo.</b> Es la otra forma que proponia el issue —todo
     * {@code String} que acabe en {@code En} o empiece por {@code fecha}—, y medida da mas de cien
     * campos, casi todos un {@code LocalDate} escrito como texto, que es un dia y no una hora: la
     * lista de excepciones seria mas larga que lo que vigila, y un campo nuevo mal nombrado pasaria
     * igual.
     *
     * <p><b>Lo que deja pasar, y por que.</b> {@link LocalTime#toString()}: la {@code
     * horaInfraccion} de una papeleta es la hora que el inspector escribio en ella, en el reloj de
     * la calle, y no un instante que haya que situar en una zona — no hay nada que convertir, y
     * publicarla tal cual es lo correcto. Y lo que no deja huella de su tipo en el bytecode —{@code
     * String.valueOf(instante)}, {@code "" + instante}— no lo ve: hoy no hay ninguno, y el remedio
     * es el mismo, {@link kamayuk.rentas.dominio.ZonaHoraria#textoConSuDesfase(Instant)}.
     */
    @Test
    @DisplayName("#327 — y ninguna hora se escribe como texto sin pasar por ZonaHoraria")
    void ningunaHoraSeEscribeComoTexto() {
        List<String> encontradas = new ArrayList<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            for (JavaMethodCall llamada : clase.getMethodCallsFromSelf()) {
                String dueno = llamada.getTargetOwner().getName();
                if (HORAS_QUE_NO_SE_ESCRIBEN_A_MANO.contains(dueno)
                        && "toString".equals(llamada.getName())) {
                    encontradas.add(
                            llamada.getOrigin().getFullName()
                                    + ":"
                                    + llamada.getLineNumber()
                                    + " llama a "
                                    + llamada.getTargetOwner().getSimpleName()
                                    + ".toString()");
                }
            }
        }

        assertThat(encontradas)
                .as(
                        "una hora escrita con toString() sale en la zona que traiga el valor"
                                + " —en UTC, «…Z», si viene de un Instant o de pgjdbc—. En un"
                                + " Resource, declara el campo OffsetDateTime y usa"
                                + " ZonaHoraria.conSuDesfase(instante); en un documento, usa"
                                + " ZonaHoraria.textoConSuDesfase(instante)")
                .isEmpty();
    }

    @Test
    @DisplayName("y ese recorrido ve las llamadas: sin verlas, la de arriba no diria nada")
    void elRecorridoDelBytecodeVeLasLlamadas() {
        // La de arriba sale verde tambien si no recorre nada. Estas son las llamadas que TIENEN
        // que estar —los Resource de #188 y #327 publican asi—, y si el recorrido dejara de
        // encontrarlas, la de arriba estaria saliendo verde a ciegas.
        List<String> alaZonaDelProducto = new ArrayList<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            for (JavaMethodCall llamada : clase.getMethodCallsFromSelf()) {
                if ("kamayuk.rentas.dominio.ZonaHoraria".equals(llamada.getTargetOwner().getName())
                        && "conSuDesfase".equals(llamada.getName())) {
                    alaZonaDelProducto.add(llamada.getOrigin().getFullName());
                }
            }
        }

        assertThat(alaZonaDelProducto)
                .as("las llamadas a ZonaHoraria.conSuDesfase desde el codigo de produccion")
                .anySatisfy(origen -> assertThat(origen).contains("PagoResource"))
                .anySatisfy(origen -> assertThat(origen).contains("CambioDePlacaResource"))
                .anySatisfy(origen -> assertThat(origen).contains("AuditoriaResource"));
    }

    /**
     * Lo que se publica no le pone a una hora un desfase elegido a mano (#317).
     *
     * <h2>El hueco entre las dos pruebas de tipo y la de {@code toString()}</h2>
     *
     * <p>Las dos primeras pruebas miran el <b>tipo</b> de cada campo publicado. Un {@code Resource}
     * que escriba {@code registro.fecha().atOffset(ZoneOffset.UTC)} —porque es lo que compila— deja
     * el tipo bien puesto, {@code OffsetDateTime}, y publica {@code …Z}. Medido en #317 con esa
     * mutacion en {@code SesionResource.de}: {@code :kamayuk-rentas-seguridad:test} y esta clase
     * salian <b>verdes</b>, 142 y 4 pruebas, 0 fallos. (La misma mutacion en {@code
     * AuditoriaResource}, la que el issue midio, ya caia desde #327, pero por casualidad: {@link
     * #elRecorridoDelBytecodeVeLasLlamadas()} nombra a ese {@code Resource} entre los que tienen
     * que llamar a {@code ZonaHoraria}, y a los otros cuatro no.)
     *
     * <h2>Que prohibe, y donde</h2>
     *
     * <p>Toda llamada a un metodo de {@code java.time} que <b>devuelva un {@code
     * OffsetDateTime}</b> —{@code Instant.atOffset}, {@code OffsetDateTime.ofInstant}, {@code
     * withOffsetSameInstant}, {@code ZonedDateTime.toOffsetDateTime}, {@code OffsetDateTime.now}…—
     * desde el codigo de <b>lo que se publica</b>: los controladores y cada record al que llega el
     * tipo de retorno de una operacion, que es donde se construye lo que viaja. El unico camino que
     * queda para poner un {@code OffsetDateTime} en un campo publicado es {@link
     * kamayuk.rentas.dominio.ZonaHoraria#conSuDesfase(Instant)}, cuyo dueno no es {@code
     * java.time}. Por el tipo de retorno y no por el nombre del metodo: la lista de las formas de
     * fabricar un desfase se escribe entera sin tener que enumerarla.
     *
     * <h2>Por que ahi y no en todo el arbol</h2>
     *
     * <p>Medido antes de escribirla, en todo {@code backend/*&#47;src/main}: la llamada aparece en
     * {@code ZonaHoraria} (la suya), en los cuatro {@code atOffset(ZoneOffset.UTC)} inocuos de #273
     * —tres parametros de un {@code INSERT} en {@code AplicarUnEventoDeIdentidad} y el mapeo de una
     * columna en {@code ValorMasivoRepositoryJdbc}—, en tres {@code OffsetDateTime.now(reloj)} que
     * son parametros JDBC ({@code AuditoriaJdbc}, {@code CorridaDeEmisionRepositoryJdbc}, {@code
     * CacheDeSnapshotsJdbc}) y en un {@code OffsetDateTime.of} de {@code DatosDePrueba}, la siembra
     * del esquema: <b>nueve</b>, y <b>ninguna</b> dentro de lo que se publica (274 clases). Barrer
     * el arbol entero seria ruido: ocho excepciones para vigilar cero casos, y cada parametro JDBC
     * nuevo una mas. Lo que las separa no es el texto de la llamada sino <b>si sale por HTTP</b>, y
     * eso es exactamente el alcance de abajo — lo comprueba {@link
     * #lasHorasQueNoSalenPorHttpQuedanFuera()}.
     *
     * <p><b>Lo que no ve.</b> Un {@code OffsetDateTime} que llegue ya hecho —el de pgjdbc, que
     * viene en UTC— y se publique tal cual no deja llamada que leer. Ese es el caso del historial
     * de placas, y lo cubre su prueba de campo ({@code VehiculoResourceTest}), como cubren las
     * suyas las otras nueve horas: la guarda dice que <b>ningun sitio se aparta</b>, y las de
     * campo, cual.
     */
    @Test
    @DisplayName("#317 — y lo que se publica no le elige a mano el desfase a ninguna hora")
    void ningunaHoraPublicadaEligeSuDesfaseAMano() {
        Set<String> publicadas = clasesQueSePublican();

        List<String> encontradas =
                desfasesElegidosAMano().stream()
                        .filter(llamada -> publicadas.contains(llamada.getOriginOwner().getName()))
                        .map(NingunaHoraSePublicaSinSuDesfaseTest::describir)
                        .toList();

        assertThat(encontradas)
                .as(
                        "una hora publicada lleva el desfase de la zona del producto, no el que"
                                + " se escriba a mano: atOffset(ZoneOffset.UTC) compila, deja el"
                                + " campo como OffsetDateTime y lo publica con «Z». Usa"
                                + " ZonaHoraria.conSuDesfase(instante)")
                .isEmpty();
    }

    @Test
    @DisplayName("#317 — y los desfases que no salen por HTTP quedan fuera, pero se ven")
    void lasHorasQueNoSalenPorHttpQuedanFuera() {
        // La de arriba sale verde tambien si el detector no ve ninguna llamada o si el alcance no
        // contiene ningun Resource. Estas son las dos cosas que TIENEN que estar: los cuatro
        // `atOffset(ZoneOffset.UTC)` de #273 los ve el detector —y el alcance los deja fuera—, y
        // los Resource que publican las diez horas estan dentro del alcance.
        Set<String> publicadas = clasesQueSePublican();
        List<JavaMethodCall> todas = desfasesElegidosAMano();

        assertThat(todas)
                .as(
                        "el detector, sobre todo el codigo de produccion, ve los cuatro inocuos de #273")
                .extracting(
                        llamada ->
                                llamada.getOriginOwner().getSimpleName()
                                        + "."
                                        + llamada.getOrigin().getName()
                                        + " → "
                                        + llamada.getName())
                .contains(
                        "AplicarUnEventoDeIdentidad.apartar → atOffset",
                        "AplicarUnEventoDeIdentidad.marcarComoAplicado → atOffset",
                        "AplicarUnEventoDeIdentidad.miembro → atOffset",
                        "ValorMasivoRepositoryJdbc.aOffset → atOffset");

        assertThat(publicadas)
                .as("el alcance: los controladores y los records que publican")
                .contains(
                        "kamayuk.rentas.seguridad.infraestructura.web.SesionController",
                        "kamayuk.rentas.seguridad.infraestructura.web.SesionController$SesionResource",
                        "kamayuk.rentas.seguridad.infraestructura.web.SesionController$AuditoriaResource",
                        "kamayuk.rentas.seguridad.infraestructura.web.SesionController$RespaldoResource",
                        "kamayuk.rentas.tesoreria.infraestructura.web.PagoController$PagoResource",
                        "kamayuk.rentas.nucleo.infraestructura.web.VehiculoResource$CambioDePlacaResource",
                        "kamayuk.rentas.indicadores.infraestructura.web.PanelResource",
                        "kamayuk.rentas.indicadores.infraestructura.web.TrabajoParadoResource")
                .doesNotContain(
                        "kamayuk.rentas.seguridad.aplicacion.AplicarUnEventoDeIdentidad",
                        "kamayuk.rentas.valores.infraestructura.ValorMasivoRepositoryJdbc");
    }

    /**
     * Cada llamada de produccion a un metodo de {@code java.time} que devuelve un {@link
     * OffsetDateTime}: la forma de elegirle el desfase a una hora sin pasar por {@code
     * ZonaHoraria}.
     */
    private static List<JavaMethodCall> desfasesElegidosAMano() {
        List<JavaMethodCall> llamadas = new ArrayList<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            for (JavaMethodCall llamada : clase.getMethodCallsFromSelf()) {
                if (llamada.getTargetOwner().getPackageName().equals("java.time")
                        && OffsetDateTime.class
                                .getName()
                                .equals(llamada.getTarget().getRawReturnType().getName())) {
                    llamadas.add(llamada);
                }
            }
        }
        return llamadas;
    }

    /**
     * Las clases cuyo codigo construye lo que viaja: cada controlador que publica una operacion y
     * cada record al que llega su tipo de retorno. Con el mismo recorrido que las pruebas de tipo,
     * por lo que dice {@link #recorrer(Type, String, String, Set, List, Class, Set)}.
     */
    private static Set<String> clasesQueSePublican() {
        Set<Class<?>> records = new LinkedHashSet<>();
        Set<String> nombres = new TreeSet<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            nombres.add(endpoint.getValue().getDeclaringClass().getName());
            recorrer(
                    endpoint.getValue().getGenericReturnType(),
                    endpoint.getKey(),
                    "",
                    new LinkedHashSet<>(),
                    new ArrayList<>(),
                    null,
                    records);
        }
        for (Class<?> record : records) {
            nombres.add(record.getName());
        }
        return nombres;
    }

    private static String describir(JavaMethodCall llamada) {
        return llamada.getOrigin().getFullName()
                + ":"
                + llamada.getLineNumber()
                + " llama a "
                + llamada.getTargetOwner().getSimpleName()
                + "."
                + llamada.getName()
                + "(), que le elige el desfase a mano";
    }

    // ------------------------------------------------------------------

    private static void recorrer(
            Type tipo,
            String operacion,
            String camino,
            Set<Class<?>> enCurso,
            List<String> hallazgos) {
        recorrer(tipo, operacion, camino, enCurso, hallazgos, null, new LinkedHashSet<>());
    }

    private static void recorrer(
            Type tipo,
            String operacion,
            String camino,
            Set<Class<?>> enCurso,
            List<String> hallazgos,
            Class<?> buscado) {
        recorrer(tipo, operacion, camino, enCurso, hallazgos, buscado, new LinkedHashSet<>());
    }

    /**
     * Baja por el tipo de retorno anotando cada hoja que interese.
     *
     * <p>Con {@code buscado} nulo anota las prohibidas; con un tipo, anota las de ese tipo. Es el
     * mismo recorrido para las dos preguntas a proposito: dos recorridos escritos aparte acaban
     * discrepando en el caso raro, que es justo donde se esconderia el campo que no se mira. Y por
     * lo mismo es el que dice, en {@code records}, en que records entra: es el alcance de la guarda
     * del desfase (#317).
     */
    private static void recorrer(
            Type tipo,
            String operacion,
            String camino,
            Set<Class<?>> enCurso,
            List<String> hallazgos,
            Class<?> buscado,
            Set<Class<?>> records) {

        if (tipo instanceof TypeVariable<?> || tipo instanceof WildcardType) {
            // Sin el argumento real no hay nada que mirar; `FormaDeLaRespuesta` lo resuelve
            // para el contrato y aqui basta con no bajar por un tipo que no se conoce.
            return;
        }
        if (tipo instanceof ParameterizedType parametrizado) {
            for (Type argumento : parametrizado.getActualTypeArguments()) {
                recorrer(argumento, operacion, camino, enCurso, hallazgos, buscado, records);
            }
            Class<?> crudo = (Class<?>) parametrizado.getRawType();
            if (!Collection.class.isAssignableFrom(crudo) && !Map.class.isAssignableFrom(crudo)) {
                recorrer(crudo, operacion, camino, enCurso, hallazgos, buscado, records);
            }
            return;
        }
        if (!(tipo instanceof Class<?> clase)) {
            return;
        }
        if (buscado == null && PROHIBIDOS.containsKey(clase)) {
            hallazgos.add(
                    operacion
                            + camino
                            + " es un "
                            + clase.getSimpleName()
                            + ": "
                            + PROHIBIDOS.get(clase));
            return;
        }
        if (buscado != null && buscado.equals(clase)) {
            hallazgos.add(operacion + camino);
            return;
        }
        if (!clase.isRecord() || enCurso.contains(clase)) {
            return;
        }
        records.add(clase);
        Set<Class<?>> siguiente = new LinkedHashSet<>(enCurso);
        siguiente.add(clase);
        for (RecordComponent componente : clase.getRecordComponents()) {
            recorrer(
                    componente.getGenericType(),
                    operacion,
                    camino + "." + componente.getName(),
                    siguiente,
                    hallazgos,
                    buscado,
                    records);
        }
    }
}
