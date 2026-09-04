package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.comun.verificaciones.ReglasDeArquitectura;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.web.GuardiaDeParametros;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El contrato y el controlador tienen que decir lo mismo sobre <b>por donde viajan los datos</b>
 * (#399).
 *
 * <p>{@link ContratoDeApiTest} compara <b>rutas</b>: si el verbo y el camino existen en los dos
 * lados, da la operacion por publicada. Eso deja pasar un desajuste que no se ve hasta integrar y
 * que no produce ningun error de compilacion en ninguna de las dos mitades: el contrato declara un
 * dato {@code in: query} y el controlador lo lee del <b>cuerpo</b>. La peticion que la interfaz
 * sabe construir —los filtros de una pantalla viajan por la URL, en las 134— llega entonces con ese
 * dato nulo, y el backend contesta «falta el objetivo del calculo» o, peor, calcula sobre lo que no
 * se le pidio.
 *
 * <p>Es exactamente lo que le pasaba a {@code POST /rentas/vehicular/calculo} desde #32: estaba en
 * {@code IMPLEMENTADAS}, el recuento decia que existia, y ninguna pantalla podia llamarla.
 *
 * <h2>El otro desajuste, el que ni siquiera llega al cuerpo (#544)</h2>
 *
 * <p>Un parametro que el contrato declara {@code in: query} y que <b>ningun metodo lee</b> no da
 * error de ninguna clase: Spring lo ignora, la consulta sale sin acotar y el listado vuelve entero.
 * Es peor que un 422, porque quien filtro cree estar mirando una parte. Le pasaba a {@code accion}
 * en la bitacora, medido sobre 1 441 filas. Y su reverso —un {@code @RequestParam} que el contrato
 * no declara— es un filtro que funciona y que ninguna pantalla puede mandar, porque el frontend
 * solo manda lo que el contrato tiene.
 *
 * <h2>Las cinco comprobaciones</h2>
 *
 * <ul>
 *   <li>{@link #POR_LA_CONSULTA} es la tabla de las operaciones cuyos datos <b>tienen</b> que poder
 *       mandarse por la consulta. Se comprueba en <b>las dos direcciones</b>: el contrato los
 *       declara {@code in: query} y el controlador los lee con {@code @RequestParam}. Separar
 *       cualquiera de las dos mitades pone la prueba en rojo.
 *   <li>La regla general: <b>ningun dato que el contrato declare {@code in: query} puede leerse
 *       solo del cuerpo</b>. Se aplica a todo controlador publicado, con la lista {@link
 *       #EL_MISMO_DESAJUSTE_TODAVIA_ABIERTO} de las que lo arrastran y todavia no se han corregido
 *       —cada una con su motivo—. Esa lista es el censo de la deuda: se acorta, nunca se alarga sin
 *       decir por que.
 *   <li>{@link #LOS_DOS_DICEN_LO_MISMO}, las operaciones cuyos filtros tienen que coincidir letra
 *       por letra en los dos lados. Tambien en las dos direcciones.
 *   <li>El censo de los filtros que no filtran, que <b>no puede crecer</b>: {@link
 *       #elCensoDeFiltrosQueNoFiltranNoCrece}, en operaciones y en parametros.
 *   <li>Y el vocabulario: cuando el contrato publica un {@code enum} para un filtro, tiene que ser
 *       el del enumerado que la base guarda, o el contrato pasa a ser una segunda copia que nadie
 *       compara con la primera.
 * </ul>
 *
 * <h2>Lo que cambio con #539</h2>
 *
 * <p>El censo de «filtros que no filtran» dejo de describir un silencio y pasa a describir un
 * <b>rechazo</b>: desde que {@code GuardiaDeParametros} esta puesto, mandar uno de esos filtros no
 * devuelve el listado entero, contesta 422 nombrandolo. Los 146 que {@link
 * #loQueElBordeRechazaSoloBaja} cuenta son, literalmente, los filtros que el contrato promete y el
 * servidor no acepta. Eso los hace mas urgentes, no menos: la cifra solo baja, y baja de dos
 * maneras —implementando el filtro, o retirandolo del contrato—.
 *
 * <p>Y aparece una tercera direccion, {@link #laFormaDeCadaParametroLaEntiendeLaGuarda}: la guarda
 * enumera lo que cada handler admite a partir de su firma, asi que una forma de enlace que no sepa
 * leer convertiria un parametro legitimo en un 422. Las formas se le ensenan antes de usarlas.
 */
@DisplayName("Por donde viajan los datos: contrato y controlador (docs/50-api)")
class ParametrosDeLaConsultaTest {

    private static final String RAIZ = "/rentas/api/v1";

    /**
     * Lo que <b>tiene</b> que poder viajar por la consulta, operacion por operacion.
     *
     * <p>Una entrada aqui es una promesa de las dos mitades a la vez, y por eso cuesta una linea:
     * el contrato declara esos parametros {@code in: query} y el controlador los lee de ahi. Que el
     * controlador los acepte <b>ademas</b> en el cuerpo no es un incumplimiento —es lo que hacen
     * {@code PredialController} y {@code VehicularController}, y lo que deja funcionar al cliente
     * viejo—; lo que no puede es leerlos <b>solo</b> del cuerpo.
     */
    private static final Map<String, Set<String>> POR_LA_CONSULTA =
            Map.ofEntries(
                    // #395 — la capa web de la determinacion predial. Los dos filtros que la
                    // pantalla dibuja y que deciden la cifra: de quien y de que ano.
                    //
                    // #541 anade `ejercicio`, que es **el mismo dato con su nombre canonico**: se
                    // llamaba `ano` en la consulta y `ejercicio` en el cuerpo, y un cliente tenia
                    // que saber cual toca en cada mitad. Los tres se prometen aqui, que es lo que
                    // impide arreglar una mitad y dejar la otra.
                    Map.entry(
                            "POST /rentas/predial/calculo-individual",
                            Set.of("codContribuyente", "ano", "ejercicio")),
                    // #399 — el calculo vehicular. Los tres filtros de la pantalla: los dos que
                    // resuelven el objetivo (placa o contribuyente) y el ejercicio. `simulacion` no
                    // esta y no es un olvido: no identifica lo que se calcula, decide si la
                    // operacion escribe, asi que va en el cuerpo (ver VehicularController).
                    Map.entry(
                            "POST /rentas/vehicular/calculo",
                            Set.of("placa", "codContribuyente", "ejercicio")),
                    // ------------------------------------------------------------------
                    // #425 — las nueve que quedaban. De cada operacion se promete lo que su
                    // cuerpo ya llevaba y el contrato declara `in: query`; los demas parametros
                    // de consulta que el contrato les pone son filtros de la GRILLA de la misma
                    // pantalla —el generador los deriva de la opcion, no del verbo— y este POST
                    // no filtra ninguna lista, asi que prometerlos aqui seria prometer nada.
                    // ------------------------------------------------------------------
                    // El expediente cuyas costas se liquidan: sin el no hay nada que liquidar.
                    Map.entry("POST /coactiva/liquidaciones-costas", Set.of("nroExpedCoact")),
                    // El dia al que se proyecta la deuda que se IMPRIME en la REC (regla 9): es la
                    // cifra que el obligado se lleva en la mano.
                    Map.entry("POST /coactiva/rec/impresion", Set.of("proyectarInteresAl")),
                    // De que es el programa de fiscalizacion.
                    Map.entry("POST /fiscalizacion/programas", Set.of("tipo")),
                    // La conclusion del acta vehicular. Es opcional, y ahi estaba lo peor del
                    // desajuste: el acta entraba con 201 y SIN hallazgo.
                    Map.entry("POST /fiscalizacion/vehicular", Set.of("hallazgo")),
                    // El numero de la notificacion administrativa previa.
                    Map.entry(
                            "POST /infracciones/administrativas/notificaciones", Set.of("numero")),
                    // A quien se le cobra en la ventanilla de tasas. La cobranza tributaria de al
                    // lado no lo declara `in: query` en el contrato, y por eso no esta aqui.
                    Map.entry("POST /tesoreria/caja/tasas", Set.of("codContribuyente")),
                    // El escrito y la papeleta que impugna.
                    Map.entry("POST /transito/descargos", Set.of("nDeExpediente", "papeleta")),
                    // Quien diligencio y con que resultado: de esto depende que la deuda quede
                    // exigible.
                    Map.entry(
                            "POST /valores/{nro}/notificacion", Set.of("notificador", "resultado")),
                    // La UNICA de las nueve que ya estaba conectada (#332). Se corrigio sin tocar
                    // la pantalla: gana el cuerpo, asi que la peticion que `escrituras.ts` manda
                    // hoy —los tres dentro de la tabla `cuotas`, aplanada— sigue produciendo el
                    // mismo movimiento, y ademas se puede llamar como el contrato promete. El
                    // alta hermana no esta porque su operacion no declara ningun parametro de
                    // consulta: su pantalla no dibuja filtros.
                    Map.entry(
                            "POST /rentas/deuda/bajas",
                            Set.of("codContribuyente", "tributo", "ano")));

    /**
     * Las operaciones que todavia leen del cuerpo un dato que el contrato declara de consulta.
     *
     * <p>Es el censo que #399 destapo al medirlo: el desajuste del calculo vehicular no era unico,
     * eran <b>nueve</b>. <b>Hoy esta vacio</b>: #425 corrigio las ocho que quedaban y, con ellas,
     * la novena —{@code POST /rentas/deuda/bajas}, la unica conectada— sin tocar su pantalla,
     * porque gana el cuerpo. Las nueve pasaron a {@link #POR_LA_CONSULTA}, que promete las dos
     * mitades a la vez.
     *
     * <p>Vacio no quiere decir que la comprobacion deje de morder: la que muerde es {@link
     * #ningunDatoDeConsultaSeLeeSoloDelCuerpo}, que se aplica a <b>todo</b> controlador publicado y
     * solo perdona lo que este mapa nombre. Esta lista es la valvula, y cuanto mas vacia este,
     * menos perdona.
     *
     * <p><b>No se alarga sin motivo escrito.</b> Anadir una entrada aqui es declarar que una
     * pantalla no va a poder llamar a su operacion.
     */
    private static final Map<String, Set<String>> EL_MISMO_DESAJUSTE_TODAVIA_ABIERTO = Map.of();

    /**
     * Las operaciones cuyos filtros <b>tienen que coincidir letra por letra</b> en las dos mitades
     * (#544).
     *
     * <p>El desajuste que esto vigila es el que las tres comprobaciones de #399 no podian ver,
     * porque todas parten del cuerpo: aqui el dato no viaja mal, viaja <b>a ningun sitio</b>. La
     * pantalla dibuja el filtro porque el contrato lo declara —el generador de tipos del frontend
     * lo expone y {@code parametrosDeBusqueda} lo deja pasar—, quien atiende lo teclea, la peticion
     * sale con el, y el total no se mueve. No hay error, ni 422, ni pagina vacia: sale <b>todo</b>,
     * que es exactamente la lectura que quien filtra cree haber descartado. Y al reves: un
     * parametro que el controlador lee y el contrato no declara es un filtro que funciona y que
     * <b>ninguna pantalla puede mandar</b>.
     *
     * <p>Las dos mitades le pasaban a la vez a {@code GET /seguridad/auditoria}, medido sobre 1 441
     * filas de la municipalidad 1: {@code ?accion=ALTA} devolvia las 1 441, y {@code tabla} y
     * {@code operacion} —que si acotan desde #13— no estaban publicados.
     *
     * <p><b>Es una promesa por operacion, como {@link #POR_LA_CONSULTA}, y no la regla general</b>:
     * la regla general no se puede encender hoy, y eso esta medido en {@link
     * #elCensoDeFiltrosQueNoFiltranNoCrece}. Una entrada aqui compromete las dos direcciones a la
     * vez y cuesta una linea; se anade cuando su operacion se revisa.
     */
    private static final Set<String> LOS_DOS_DICEN_LO_MISMO =
            Set.of(
                    "GET /seguridad/auditoria",
                    // #541 — las dos lecturas de Rentas que se revisaron con el. La de
                    // arbitrios declaraba cinco parametros y el controlador leia dos:
                    // «Ejercicio» se tecleaba y no acotaba —solo entendia `anio`— y «Zona» y
                    // «Uso» no los leia nadie. Ahora los cinco se leen: el ejercicio acota, y los
                    // dos desplegables se **rechazan con 422** porque los valores que ofrecen no
                    // existen en el sistema (ver ArbitriosController). Rechazar tambien es leer,
                    // y es lo que separa un filtro que dice que no de uno que se traga la
                    // pregunta. La de predios ya leia los suyos y se compromete aqui por lo mismo:
                    // es la pareja que #541 revisa.
                    "GET /rentas/arbitrios",
                    "GET /rentas/predios",
                    // #576 — las dos determinaciones de Rentas que declaraban filtros que
                    // ningun controlador leia. `predial_individual` declaraba los tres de la
                    // DECLARACION JURADA que motiva el calculo y `espectaculos` los cuatro de
                    // una busqueda que no existe; los siete se retiran con SUPRIMIDOS y las dos
                    // operaciones se comprometen aqui, que es lo que hace que devolver
                    // cualquiera de ellos al contrato ponga la prueba en rojo NOMBRANDOLO. Sin
                    // esto, la comprobacion no distingue «lo lee» de «no hay nada que leer».
                    "POST /rentas/predial/calculo-individual",
                    "POST /rentas/espectaculos",
                    // #674 — la relacion de prescripciones declaradas. Estrena controlador
                    // con esta promesa puesta desde el primer dia, por lo mismo que el plano
                    // catastral: sus cuatro filtros son exactamente los cuatro que el
                    // contrato declara, y comprometerlos cuando la operacion nace no cuesta
                    // nada. `resultado` se RECHAZA con 422 si no es uno de los tres, que
                    // tambien es leerlo.
                    "GET /coactiva/prescripcion",
                    // #583 — quien tiene un privilegio sobre un acceso. Estrena
                    // controlador, y comprometer las dos direcciones cuando la operacion
                    // nace no cuesta nada: su unico filtro propio es `privilegio`, que el
                    // controlador lee y RECHAZA con 422 si no es uno de los siete —y
                    // rechazar tambien es leer—. La otra lectura de ese issue, la de lo
                    // configurado, no declara ningun parametro de consulta y su promesa es
                    // que siga sin declararlo: un filtro nuevo ahi tendria que leerlo
                    // alguien.
                    "GET /seguridad/accesos/{codigo}/usuarios",
                    "GET /seguridad/usuarios/{id}/permisos/configurados",
                    // #618 — el catalogo de ventanillas. Estrena controlador, y su promesa es la
                    // mas estrecha que hay: NO declara ningun parametro propio, solo el dialecto
                    // de la paginacion, y su controlador lee exactamente esos cuatro. Comprometer
                    // las dos direcciones cuando la operacion nace no cuesta nada, y lo que fija
                    // es que un filtro nuevo ahi —«activa», «codigo»— tenga que leerlo alguien:
                    // desde #539 uno declarado y no leido no se ignora, se contesta con 422.
                    "GET /tesoreria/cajas");

    /**
     * Cuantas operaciones arrastran hoy cada mitad del desajuste. Medido, no estimado (#544).
     *
     * <p>Son el techo de un censo que no se puede escribir entrada por entrada sin convertir cada
     * PR ajeno en un ejercicio de contabilidad: el contrato esta <b>derivado del prototipo</b>
     * (#312), asi que declara los filtros que cada pantalla dibuja, y los controladores se
     * escribieron despues leyendo los suyos. De ahi salen las dos cifras.
     *
     * <p>Lo que estas dos constantes garantizan es que <b>no crezcan</b>: un filtro nuevo que no
     * filtra, o uno que se lee sin publicar, ponen la prueba en rojo con la operacion dentro. Y
     * bajan solas segun cada modulo se revisa; la que baja se ajusta en el mismo PR, que es donde
     * se sabe por que.
     *
     * <p><b>Y las dos se MIDEN, no se razonan</b>: #546 puso 62 en la primera contando las
     * operaciones que su cambio sacaba del censo, y la medida es 61 — con el techo de 62 la guarda
     * se quedaba con una holgura de una operacion, y devolver al contrato los tres parametros del
     * cruce registral la dejaba en VERDE. La cifra sale de correr la prueba con el techo en 0 y
     * leer el «but was» que imprime.
     *
     * <p><b>#576 baja las tres</b>: {@code predial_individual} y {@code espectaculos} dejan de
     * declarar filtros que nadie lee —los siete se retiran con {@code SUPRIMIDOS}— y las dos entran
     * ademas en {@link #LOS_DOS_DICEN_LO_MISMO}, que es lo que impide que vuelvan. Las tres cifras
     * se midieron poniendo el techo a 0 y leyendo el «but was», nunca restando a mano: entre medias
     * se mezclaron otros issues y la resta habria dado otro numero.
     *
     * <p>#546 baja las dos. {@code POST /fiscalizacion/vehicular} declaraba los tres filtros del
     * cruce registral —{@code placa}, {@code ejercicio}, {@code origenDelCruce}— y ese cruce no
     * existe: se retiran con {@code SUPRIMIDOS}. {@code GET /fiscalizacion/estado-cuenta} declaraba
     * los filtros de una pantalla de PAPELETAS y la paginacion de una grilla que no tiene, y no
     * declaraba {@code fechaDeConsulta} —el parametro de la regla 9 y el unico, con el
     * contribuyente, que la operacion lee—: la brecha en las <b>dos</b> direcciones a la vez, que
     * es lo que hace que baje tambien la segunda cifra.
     */
    /**
     * Baja a 60 con #548: {@code POST /tesoreria/caja/tasas} declaraba {@code partida} y {@code
     * conceptoTupa}, y su controlador solo enlaza {@code codContribuyente}. Los dos acotan el
     * catalogo del TUPA —la tabla «Conceptos a cobrar» del prototipo—, que esta operacion no
     * devuelve: es el POST que COBRA los conceptos que llegan en el cuerpo. Se retiraron del
     * contrato ({@code SUPRIMIDOS} del generador) en vez de leerlos, porque leerlos aqui no podria
     * cambiar ni una fila de la respuesta.
     *
     * <p><b>Las dos cifras estan MEDIDAS, no contadas</b>, poniendo el techo a 0 y leyendo el «but
     * was»: 60 y 18 sobre el arbol ya mezclado con #546. Es la leccion que ese issue aprendio por
     * las malas —su techo se puso contando y quedo en 62 donde la medida era 61, y con esa holgura
     * de una sola operacion la mutacion que este criterio existe para cazar no mordia—.
     */
    private static final int OPERACIONES_CON_FILTRO_QUE_NADIE_LEE = 57;

    private static final int OPERACIONES_QUE_LEEN_UN_FILTRO_SIN_PUBLICAR = 18;

    /**
     * Y cuantos <b>parametros</b>, que es la cifra que #539 pide medir.
     *
     * <p>Contar operaciones deja pasar la mitad del caso: una operacion que ya arrastra un filtro
     * que nadie lee puede ganar un segundo sin mover el recuento. Medido sobre el arbol de hoy
     * —despues de #546, que retiro siete parametros y bajo los dos techos de operaciones—: 218
     * parametros en las 61 operaciones, y 38 en las 18 de la otra mitad. Medido poniendo el techo a
     * 0 y leyendo el «but was», nunca contando a mano: es la leccion que #546 aprendio por las
     * malas, con un techo de 62 donde la medida era 61 y una holgura de una operacion en la que la
     * mutacion no mordia.
     */
    private static final int PARAMETROS_CON_FILTRO_QUE_NADIE_LEE = 205;

    private static final int PARAMETROS_QUE_SE_LEEN_SIN_PUBLICAR = 38;

    /**
     * De esos, cuantos <b>rechaza hoy el borde</b> (#539).
     *
     * <p>Desde que {@code GuardiaDeParametros} esta puesto, un filtro que el contrato declara y
     * ningun controlador lee ya no se ignora: se contesta 422 nombrandolo. La excepcion son los
     * cuatro nombres de la paginacion, que se admiten siempre; de ahi que esta cifra sea menor que
     * la de arriba —218 menos los 72 de paginacion—.
     *
     * <p>Es la medida de la <b>promesa rota</b>: 146 filtros que el contrato publica y el servidor
     * rechaza, en 58 operaciones —tres de las 61 solo arrastraban nombres de paginacion, que se
     * admiten siempre—. Baja de dos maneras y las dos son buenas: implementando el filtro —pasa a
     * leerse— o retirandolo del contrato en {@code generar-openapi.mjs} (SUPRIMIDOS). Lo que no
     * puede es subir: un filtro nuevo que nadie lee nace roto.
     */
    private static final int PARAMETROS_QUE_EL_BORDE_RECHAZA = 133;

    /** Una ruta del contrato: {@code "/ruta":} con dos espacios de sangria. */
    private static final Pattern RUTA_DEL_CONTRATO = Pattern.compile("  \"(/[^\"]*)\":");

    /** Un verbo dentro de la ruta actual, con cuatro espacios de sangria. */
    private static final Pattern VERBO_DEL_CONTRATO =
            Pattern.compile("    (get|post|put|patch|delete):");

    /** El nombre de un parametro, con ocho espacios de sangria: {@code - name: placa}. */
    private static final Pattern NOMBRE_DEL_PARAMETRO = Pattern.compile("        - name: (\\S+)");

    /** Donde viaja ese parametro: {@code in: query} con diez. */
    private static final Pattern DONDE_VIAJA = Pattern.compile("          in: (\\S+)");

    @Test
    @DisplayName("el contrato se lee, y trae parametros de consulta que comparar")
    void elContratoSeLee() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();

        assertThat(contrato).as("sin operaciones no hay nada que comparar").hasSizeGreaterThan(100);
        assertThat(contrato.get("POST /rentas/vehicular/calculo"))
                .as("la pantalla del calculo vehicular declara sus tres filtros")
                .containsExactlyInAnyOrder("placa", "codContribuyente", "ejercicio");
    }

    @Test
    @DisplayName("lo que viaja por la consulta lo declara el contrato como parametro de consulta")
    void elContratoLoDeclaraDeConsulta() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();

        Map<String, Set<String>> faltan = new TreeMap<>();
        POR_LA_CONSULTA.forEach(
                (operacion, exigidos) -> {
                    Set<String> declarados = contrato.getOrDefault(operacion, Set.of());
                    Set<String> sinDeclarar = new TreeSet<>(exigidos);
                    sinDeclarar.removeAll(declarados);
                    if (!sinDeclarar.isEmpty()) {
                        faltan.put(operacion, sinDeclarar);
                    }
                });

        assertThat(faltan)
                .as(
                        "el contrato tiene que declarar «in: query» lo que el controlador lee de la"
                                + " consulta. Si se quitan de ahi, la pantalla deja de poder mandarlos y"
                                + " nadie se entera hasta integrar. El contrato se corrige en"
                                + " docs/50-api/generar-openapi.mjs, nunca a mano")
                .isEmpty();
    }

    @Test
    @DisplayName("y el controlador los lee de la consulta, no solo del cuerpo")
    void elControladorLosLeeDeLaConsulta() {
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> faltan = new TreeMap<>();
        POR_LA_CONSULTA.forEach(
                (operacion, exigidos) -> {
                    Handler handler = publicados.get(operacion);
                    assertThat(handler)
                            .as("la operacion %s no esta publicada", operacion)
                            .isNotNull();
                    Set<String> sinLeer = new TreeSet<>(exigidos);
                    sinLeer.removeAll(handler == null ? Set.of() : handler.deLaConsulta());
                    if (!sinLeer.isEmpty()) {
                        faltan.put(operacion, sinLeer);
                    }
                });

        assertThat(faltan)
                .as(
                        "estos datos los declara el contrato «in: query» y el controlador no los lee"
                                + " de la consulta: la peticion que la interfaz sabe construir llegaria"
                                + " con ellos nulos. Se leen con @RequestParam, y se puede seguir"
                                + " aceptando el cuerpo (ver PredialController y VehicularController)")
                .isEmpty();
    }

    @Test
    @DisplayName("ningun dato del contrato «in: query» se lee solo del cuerpo")
    void ningunDatoDeConsultaSeLeeSoloDelCuerpo() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> desajustados = new TreeMap<>();
        publicados.forEach(
                (operacion, handler) -> {
                    Set<String> soloEnElCuerpo = new TreeSet<>(handler.delCuerpo());
                    soloEnElCuerpo.retainAll(contrato.getOrDefault(operacion, Set.of()));
                    soloEnElCuerpo.removeAll(handler.deLaConsulta());
                    soloEnElCuerpo.removeAll(
                            EL_MISMO_DESAJUSTE_TODAVIA_ABIERTO.getOrDefault(operacion, Set.of()));
                    if (!soloEnElCuerpo.isEmpty()) {
                        desajustados.put(operacion, soloEnElCuerpo);
                    }
                });

        assertThat(desajustados)
                .as(
                        "el contrato declara estos datos «in: query» y el controlador solo los lee"
                                + " del cuerpo. Es el defecto de #399: la operacion figura publicada y"
                                + " ninguna pantalla puede llamarla. O el controlador los lee tambien de"
                                + " la consulta, o se anotan en EL_MISMO_DESAJUSTE_TODAVIA_ABIERTO con"
                                + " su motivo")
                .isEmpty();
    }

    @Test
    @DisplayName("el censo de lo abierto no miente: cada entrada sigue estando desajustada")
    void elCensoNoMiente() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> yaCorregidas = new TreeMap<>();
        EL_MISMO_DESAJUSTE_TODAVIA_ABIERTO.forEach(
                (operacion, nombres) -> {
                    Handler handler = publicados.get(operacion);
                    assertThat(handler)
                            .as("%s ya no se publica: sobra del censo", operacion)
                            .isNotNull();
                    Set<String> sinDesajuste = new TreeSet<>(nombres);
                    if (handler != null) {
                        sinDesajuste.retainAll(contrato.getOrDefault(operacion, Set.of()));
                        sinDesajuste.retainAll(handler.delCuerpo());
                        sinDesajuste.removeAll(handler.deLaConsulta());
                    }
                    if (!sinDesajuste.equals(new TreeSet<>(nombres))) {
                        yaCorregidas.put(operacion, new TreeSet<>(nombres));
                    }
                });

        assertThat(yaCorregidas)
                .as(
                        "estas entradas del censo ya no describen ningun desajuste: se corrigieron y"
                                + " nadie las quito. Una lista de deuda que no se acorta deja de decir"
                                + " cuanta deuda hay")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que el contrato declara de estas operaciones lo lee su controlador")
    void loQueElContratoDeclaraLoLeeElControlador() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> huerfanos = new TreeMap<>();
        for (String operacion : LOS_DOS_DICEN_LO_MISMO) {
            Handler handler = publicados.get(operacion);
            assertThat(handler).as("la operacion %s no esta publicada", operacion).isNotNull();
            Set<String> sinLeer = new TreeSet<>(contrato.getOrDefault(operacion, Set.of()));
            if (handler != null) {
                sinLeer.removeAll(handler.deLaConsulta());
                // Lo que se lee del cuerpo teniendolo declarado «in: query» es el desajuste de
                // #399, y lo cuenta la comprobacion de arriba: aqui no se dice dos veces.
                sinLeer.removeAll(handler.delCuerpo());
            }
            if (!sinLeer.isEmpty()) {
                huerfanos.put(operacion, sinLeer);
            }
        }

        assertThat(huerfanos)
                .as(
                        "el contrato declara estos parametros y el controlador no los lee de ningun"
                                + " sitio: la pantalla los dibuja, quien atiende los teclea, viajan, y"
                                + " el listado sale entero. Un filtro que no filtra es peor que uno que"
                                + " no existe. O el controlador los lee, o se retiran del contrato en"
                                + " docs/50-api/generar-openapi.mjs (SUPRIMIDOS), nunca a mano en el"
                                + " yaml")
                .isEmpty();
    }

    @Test
    @DisplayName("y lo que su controlador lee lo declara el contrato, o nadie puede mandarlo")
    void loQueElControladorLeeLoDeclaraElContrato() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> sinPublicar = new TreeMap<>();
        for (String operacion : LOS_DOS_DICEN_LO_MISMO) {
            Handler handler = publicados.get(operacion);
            assertThat(handler).as("la operacion %s no esta publicada", operacion).isNotNull();
            Set<String> ocultos =
                    handler == null ? new TreeSet<>() : new TreeSet<>(handler.deLaConsulta());
            ocultos.removeAll(contrato.getOrDefault(operacion, Set.of()));
            if (!ocultos.isEmpty()) {
                sinPublicar.put(operacion, ocultos);
            }
        }

        assertThat(sinPublicar)
                .as(
                        "el controlador lee estos parametros de la consulta y el contrato no los"
                                + " declara: el frontend solo manda lo que el contrato tiene, asi que"
                                + " son filtros que funcionan y ninguna pantalla puede usar. Se"
                                + " publican en docs/50-api/generar-openapi.mjs (DEL_BACKEND), nunca a"
                                + " mano en el yaml")
                .isEmpty();
    }

    @Test
    @DisplayName("y el censo de los filtros que no filtran no crece")
    void elCensoDeFiltrosQueNoFiltranNoCrece() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> queNadieLee = new TreeMap<>();
        Map<String, Set<String>> sinPublicar = new TreeMap<>();
        publicados.forEach(
                (operacion, handler) -> {
                    if (!contrato.containsKey(operacion)) {
                        // Una ruta entera sin publicar es cosa de ContratoDeApiTest.
                        return;
                    }
                    Set<String> sinLeer = new TreeSet<>(contrato.get(operacion));
                    sinLeer.removeAll(handler.deLaConsulta());
                    sinLeer.removeAll(handler.delCuerpo());
                    if (!sinLeer.isEmpty()) {
                        queNadieLee.put(operacion, sinLeer);
                    }
                    Set<String> ocultos = new TreeSet<>(handler.deLaConsulta());
                    ocultos.removeAll(contrato.get(operacion));
                    if (!ocultos.isEmpty()) {
                        sinPublicar.put(operacion, ocultos);
                    }
                });

        assertThat(queNadieLee)
                .as(
                        "estas operaciones declaran un filtro que ningun controlador lee: se teclea"
                                + " y no filtra. La cifra es la medida de #544 y solo puede bajar; si"
                                + " sube, la operacion nueva esta ahi dentro")
                .hasSizeLessThanOrEqualTo(OPERACIONES_CON_FILTRO_QUE_NADIE_LEE);
        assertThat(sinPublicar)
                .as(
                        "estas operaciones leen de la consulta un parametro que el contrato no"
                                + " declara: filtra y ninguna pantalla puede mandarlo. Misma regla: la"
                                + " cifra solo baja")
                .hasSizeLessThanOrEqualTo(OPERACIONES_QUE_LEEN_UN_FILTRO_SIN_PUBLICAR);

        assertThat(cuantosParametros(queNadieLee))
                .as(
                        "y en parametros, que es lo que #539 pide medir: contar operaciones deja"
                                + " pasar el filtro numero dos de una operacion que ya arrastraba uno")
                .isLessThanOrEqualTo(PARAMETROS_CON_FILTRO_QUE_NADIE_LEE);
        assertThat(cuantosParametros(sinPublicar))
                .isLessThanOrEqualTo(PARAMETROS_QUE_SE_LEEN_SIN_PUBLICAR);
    }

    @Test
    @DisplayName("y el borde rechaza hoy 146 filtros que el contrato publica: la cifra solo baja")
    void loQueElBordeRechazaSoloBaja() throws IOException {
        Map<String, Set<String>> contrato = parametrosDeConsultaDelContrato();
        Map<String, Handler> publicados = handlersPublicados();

        Map<String, Set<String>> rechazados = new TreeMap<>();
        publicados.forEach(
                (operacion, handler) -> {
                    if (!contrato.containsKey(operacion)) {
                        return;
                    }
                    Set<String> sinLeer = new TreeSet<>(contrato.get(operacion));
                    sinLeer.removeAll(handler.deLaConsulta());
                    sinLeer.removeAll(handler.delCuerpo());
                    // El unico que la guarda perdona siempre.
                    sinLeer.removeAll(GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION);
                    if (!sinLeer.isEmpty()) {
                        rechazados.put(operacion, sinLeer);
                    }
                });

        assertThat(cuantosParametros(rechazados))
                .as(
                        "el contrato publica estos filtros y, desde #539, el borde los contesta con"
                                + " 422 nombrandolos: son la promesa rota, medida. Se cierra"
                                + " implementando el filtro o retirandolo del contrato en"
                                + " docs/50-api/generar-openapi.mjs (SUPRIMIDOS). Si esta cifra sube,"
                                + " es que se publico un filtro nuevo que nadie lee: nace roto, y"
                                + " ahora ademas se nota. Operaciones afectadas: %s",
                        rechazados.keySet())
                .isLessThanOrEqualTo(PARAMETROS_QUE_EL_BORDE_RECHAZA);
    }

    @Test
    @DisplayName("todo parametro de handler tiene una forma que la guarda de #539 sabe leer")
    void laFormaDeCadaParametroLaEntiendeLaGuarda() {
        List<String> desconocidas = new ArrayList<>();
        for (Method metodo : handlersConCuerpo()) {
            for (Parameter parametro : metodo.getParameters()) {
                if (formaConocida(parametro)) {
                    continue;
                }
                desconocidas.add(
                        metodo.getDeclaringClass().getSimpleName()
                                + "#"
                                + metodo.getName()
                                + " recibe un "
                                + parametro.getType().getSimpleName()
                                + (parametro.getAnnotation(RequestParam.class) == null
                                        ? " sin anotar"
                                        : " como @RequestParam, que recoge la consulta entera")
                                + ": GuardiaDeParametros no puede enumerar los nombres que aporta");
            }
        }

        assertThat(desconocidas)
                .as(
                        "GuardiaDeParametros compone lo que la operacion admite de tres sitios: sus"
                                + " @RequestParam, los componentes del record que Spring le compone de"
                                + " la consulta, y el `params` de su mapeo. Una forma de enlace que no"
                                + " sea ninguna de esas —un String suelto sin anotar, que Spring"
                                + " enlaza por su nombre igual (#431); un @RequestParam Map, que"
                                + " recoge la consulta entera— aporta nombres que la guarda no ve, y"
                                + " entonces los rechaza: la operacion dejaria de admitir un parametro"
                                + " que su propia firma si lee. Si hace falta una forma nueva, se"
                                + " ensena a la guarda ANTES de usarla")
                .isEmpty();
    }

    /**
     * Las formas de enlace que la guarda sabe enumerar, mas las que no aportan ningun nombre.
     *
     * <p>{@code HttpServletRequest} entra en las segundas y es el unico caso: {@code
     * ConsultaController} lo recibe para <b>reenviar</b> la peticion entera en su 307, no para leer
     * un nombre que su firma no declare.
     */
    private static boolean formaConocida(Parameter parametro) {
        if (parametro.getAnnotation(RequestParam.class) != null) {
            // Un @RequestParam de tipo Map recoge TODA la consulta y no declara ni un nombre: la
            // guarda no tendria nada que enumerar y rechazaria lo que ese metodo si lee.
            return !Map.class.isAssignableFrom(parametro.getType());
        }
        if (parametro.getAnnotations().length > 0) {
            return true;
        }
        return parametro.getType().isRecord()
                || jakarta.servlet.http.HttpServletRequest.class.isAssignableFrom(
                        parametro.getType());
    }

    private static int cuantosParametros(Map<String, Set<String>> porOperacion) {
        return porOperacion.values().stream().mapToInt(Set::size).sum();
    }

    @Test
    @DisplayName("el vocabulario que el contrato publica es el que la bitacora puede guardar")
    void elVocabularioPublicadoEsElDelEnumerado() throws IOException {
        // El primer eslabon de tres: `Operacion` (V5) → el `enum` del contrato → el
        // desplegable de la pantalla, que compara contra este mismo `enum`
        // (`pantallas/seguridad/auditoria.test.tsx`). Sin esta prueba, el contrato
        // seria una segunda copia del vocabulario que nadie compara con la primera,
        // que es el hueco que #192 documento para las llaves de los parametros: una
        // palabra que se queda vieja no da error, da una consulta que no encuentra
        // nada.
        assertThat(vocabularioDeLaOperacionEnElContrato())
                .as(
                        "el `enum` del parametro «operacion» de GET /seguridad/auditoria tiene que"
                                + " ser el enumerado Operacion, letra por letra. Se declara en"
                                + " docs/50-api/generar-openapi.mjs (DEL_BACKEND), nunca a mano")
                .containsExactly(
                        Arrays.stream(Operacion.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    @DisplayName("y el de los tres desplegables de fiscalizacion es el de SUS enumerados (#546)")
    void elVocabularioDeFiscalizacionEsElDeSusEnumerados() throws IOException {
        // El mismo eslabon que el de arriba, en el modulo donde #546 midio cinco
        // desplegables que no cuadraban -y hasta CERO coincidencias de seis-. Lo que
        // hace falta comprobar no es que la lista sea «razonable» sino que sea la del
        // enumerado LETRA POR LETRA: parecerse no es serlo, y aqui el que se parece
        // entra con 422 -o, en el acta, con 201 y sin hallazgo-.
        assertThat(vocabularioDelContrato("/fiscalizacion/omisos", "condicion"))
                .as("«Condicion» de fisc_omisos es CondicionFiscalizada")
                .containsExactly(nombresDe(CondicionFiscalizada.values()));
        assertThat(vocabularioDelContrato("/fiscalizacion/resultados", "hallazgo"))
                .as("«Hallazgo» de fisc_resultados tambien: lo que filtra es lo DERIVADO")
                .containsExactly(nombresDe(CondicionFiscalizada.values()));
        assertThat(vocabularioDelContrato("/fiscalizacion/resultados", "estado"))
                .as("«Estado» de fisc_resultados es EstadoDeLiquidacion, y no existe «Reclamado»")
                .containsExactly(nombresDe(EstadoDeLiquidacion.values()));
        assertThat(vocabularioDelContrato("/fiscalizacion/vehicular", "hallazgo"))
                .as(
                        "y el del acta es Hallazgo, que desde #599 son CINCO: el acta ya consigna"
                                + " el uso observado (uso_hallado, V76), asi que USO_DISTINTO se"
                                + " puede anotar")
                .containsExactly(nombresDe(Hallazgo.values()));
    }

    @Test
    @DisplayName("y el de «quien tiene un privilegio» son los siete de Privilegio (#583)")
    void elVocabularioDelPrivilegioEsElDeSuEnumerado() throws IOException {
        // El mismo eslabon, y aqui el parametro es ADEMAS obligatorio: una palabra
        // que no sea una de las siete no da una pagina vacia sino 422 enumerandolas.
        // Sin esta prueba el contrato seria una segunda copia de la lista que nadie
        // compara con la primera, y un privilegio anadido al enumerado se quedaria
        // fuera del desplegable sin que nada lo dijera (#192).
        assertThat(vocabularioDelContrato("/seguridad/accesos/{codigo}/usuarios", "privilegio"))
                .as(
                        "los SIETE privilegios del manual (cap. 4, RF-121), letra por letra. Se"
                                + " declara en docs/50-api/generar-openapi.mjs (VOCABULARIOS), nunca"
                                + " a mano")
                .containsExactly(nombresDe(Privilegio.values()));
    }

    @Test
    @DisplayName("y los dos vocabularios de fiscalizacion siguen siendo DOS, aunque digan lo mismo")
    void hallazgoNoEsCondicionFiscalizada() {
        // Esta guarda existe para impedir que alguien «unifique» los dos enumerados, que
        // es el arreglo comodo ante una discrepancia futura -el que #436 tuvo que impedir
        // por escrito con las partidas del cuadro y las de la ficha-. Hasta #599 lo decia
        // contando: cuatro y cinco. Ahora los dos tienen los MISMOS cinco nombres, asi que
        // contar ya no distingue nada y lo que se guarda es que sigan siendo dos tipos.
        //
        // Y siguen siendolo por lo que cada uno es: `Hallazgo` es lo que una PERSONA anota
        // en el acta y `CondicionFiscalizada` lo que el sistema DERIVA comparando los dos
        // lados. Uno puede equivocarse y el otro no: un acta puede decir CONFORME sobre un
        // predio cuya area hallada supera la declarada, y la liquidacion lo clasificara
        // SUBVALUADOR igual, porque la condicion sale de las superficies y no de la
        // casilla. Fundirlos borraria esa diferencia y haria del acta la ultima palabra.
        assertThat(nombresDe(Hallazgo.values()))
                .as(
                        "los dos vocabularios coinciden desde #599, cuando el acta gano donde"
                                + " consignar el uso observado (uso_hallado, V76)")
                .containsExactly(nombresDe(CondicionFiscalizada.values()));

        // Coincidir no es ser el mismo tipo, y esto es lo que lo mide: el acta ANOTA un
        // `Hallazgo` y la linea de liquidacion lleva la `CondicionFiscalizada` DERIVADA.
        // Sustituir uno por el otro «ya que dicen lo mismo» cambia el tipo de uno de los dos
        // componentes, y entonces esta comprobacion lo dice nombrandolo.
        assertThat(tipoDelComponente(ActaFiscalizacion.class, "hallazgo"))
                .as("lo que el acta ANOTA es un Hallazgo, no la condicion derivada")
                .isEqualTo(Hallazgo.class);
        assertThat(tipoDelComponente(LineaDeLiquidacion.class, "condicion"))
                .as(
                        "y lo que la linea de liquidacion lleva es la CondicionFiscalizada que"
                                + " ComparacionHalladoDeclarado derivo de los dos lados")
                .isEqualTo(CondicionFiscalizada.class);

        // Y el uso hallado es lo que sostiene el quinto valor: sin la columna, un acta que
        // lo anota afirma un hallazgo que no puede sustentar, que es por lo que #546 se
        // nego a anadirlo. La guarda vive en el dominio y otra vez en la base.
        assertThat(nombresDe(Hallazgo.values())).contains("USO_DISTINTO");
    }

    @Test
    @DisplayName("todo cuerpo publicado es un record, o la regla de arriba deja de verlo")
    void todoCuerpoPublicadoEsUnRecord() {
        List<String> sinForma = new ArrayList<>();
        for (Method metodo : handlersConCuerpo()) {
            for (Parameter parametro : metodo.getParameters()) {
                if (parametro.getAnnotation(RequestBody.class) == null
                        || parametro.getType().isRecord()) {
                    continue;
                }
                sinForma.add(
                        metodo.getDeclaringClass().getSimpleName()
                                + "#"
                                + metodo.getName()
                                + " lee el cuerpo como "
                                + parametro.getType().getSimpleName());
            }
        }

        assertThat(sinForma)
                .as(
                        "la regla de arriba compara los campos del cuerpo con los parametros de"
                                + " consulta del contrato, y los campos de un cuerpo solo se pueden"
                                + " enumerar si es un record. Un cuerpo leido como Map o como clase con"
                                + " setters no aporta ningun nombre: la comprobacion pasaria en VERDE"
                                + " sin mirar nada, que es la forma en que esta regla dejaria de"
                                + " proteger sin que nadie se entere. Ademas es la «lista blanca» que"
                                + " cada controlador declara: lo que no esta en el record no entra")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** El tipo declarado de un componente de un {@code record}, por su nombre. */
    private static Class<?> tipoDelComponente(Class<?> registro, String componente) {
        for (java.lang.reflect.RecordComponent candidato : registro.getRecordComponents()) {
            if (candidato.getName().equals(componente)) {
                return candidato.getType();
            }
        }
        throw new AssertionError(
                registro.getSimpleName() + " ya no declara el componente '" + componente + "'");
    }

    /** Lo que un handler publicado sabe leer: de la consulta, y del cuerpo. */
    private record Handler(Set<String> deLaConsulta, Set<String> delCuerpo) {

        /** Los dos handlers de una misma operacion, sumados. */
        Handler unido(Handler otro) {
            Set<String> consulta = new TreeSet<>(deLaConsulta);
            consulta.addAll(otro.deLaConsulta());
            Set<String> cuerpo = new TreeSet<>(delCuerpo);
            cuerpo.addAll(otro.delCuerpo());
            return new Handler(consulta, cuerpo);
        }
    }

    /** El {@code enum} que el contrato declara para el filtro «operacion» de la bitacora. */
    private static List<String> vocabularioDeLaOperacionEnElContrato() throws IOException {
        String yaml =
                Files.readString(
                        raizDelRepositorio().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);
        int ruta = yaml.indexOf("  \"/seguridad/auditoria\":");
        assertThat(ruta).as("la ruta de la bitacora esta en el contrato").isPositive();
        int parametro = yaml.indexOf("- name: operacion", ruta);
        assertThat(parametro).as("y declara el filtro «operacion»").isPositive();

        Matcher vocabulario =
                Pattern.compile("enum: \\[([^\\]]+)\\]").matcher(yaml.substring(parametro));
        assertThat(vocabulario.find()).as("con su vocabulario dentro").isTrue();
        return Arrays.stream(vocabulario.group(1).split(",")).map(String::strip).toList();
    }

    /**
     * El {@code enum} que el contrato declara para un parametro de una ruta (#546).
     *
     * <p>Se lee del YAML y no de la tabla del generador a proposito: lo que un cliente ve es el
     * contrato comprometido, y comprobar el generador contra si mismo no diria nada.
     */
    private static List<String> vocabularioDelContrato(String ruta, String parametro)
            throws IOException {
        String yaml =
                Files.readString(
                        raizDelRepositorio().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);
        int desde = yaml.indexOf("  \"" + ruta + "\":");
        assertThat(desde).as("la ruta %s esta en el contrato", ruta).isPositive();
        int donde = yaml.indexOf("- name: " + parametro, desde);
        assertThat(donde).as("%s declara el parametro %s", ruta, parametro).isPositive();
        Matcher vocabulario =
                Pattern.compile("enum: \\[([^\\]]+)\\]").matcher(yaml.substring(donde));
        assertThat(vocabulario.find())
                .as(
                        "el parametro %s de %s tiene que publicar su vocabulario: se declara en"
                                + " docs/50-api/generar-openapi.mjs (VOCABULARIOS), nunca a mano",
                        parametro, ruta)
                .isTrue();
        return Arrays.stream(vocabulario.group(1).split(",")).map(String::strip).toList();
    }

    private static String[] nombresDe(Enum<?>[] valores) {
        return Arrays.stream(valores).map(Enum::name).toArray(String[]::new);
    }

    private static Map<String, Set<String>> parametrosDeConsultaDelContrato() throws IOException {
        List<String> lineas =
                Files.readAllLines(
                        raizDelRepositorio().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);

        Map<String, Set<String>> porOperacion = new TreeMap<>();
        String rutaActual = null;
        String operacionActual = null;
        for (int i = 0; i < lineas.size(); i++) {
            String linea = lineas.get(i);
            Matcher ruta = RUTA_DEL_CONTRATO.matcher(linea);
            if (ruta.matches()) {
                rutaActual = ruta.group(1);
                operacionActual = null;
                continue;
            }
            Matcher verbo = VERBO_DEL_CONTRATO.matcher(linea);
            if (verbo.matches() && rutaActual != null) {
                operacionActual = verbo.group(1).toUpperCase(Locale.ROOT) + " " + rutaActual;
                porOperacion.computeIfAbsent(operacionActual, clave -> new TreeSet<>());
                continue;
            }
            Matcher nombre = NOMBRE_DEL_PARAMETRO.matcher(linea);
            if (nombre.matches() && operacionActual != null && viajaEnLaConsulta(lineas, i)) {
                porOperacion.get(operacionActual).add(nombre.group(1));
            }
        }
        return porOperacion;
    }

    /**
     * El {@code in:} del parametro que empieza en esa linea.
     *
     * <p>Se mira hacia adelante en vez de con un solo regex porque el parametro son varias lineas
     * —{@code name}, {@code in}, {@code required}, {@code description}, {@code schema}— y el orden
     * no es parte del contrato.
     */
    private static boolean viajaEnLaConsulta(List<String> lineas, int desde) {
        for (int i = desde + 1; i < lineas.size() && lineas.get(i).startsWith("          "); i++) {
            Matcher donde = DONDE_VIAJA.matcher(lineas.get(i));
            if (donde.matches()) {
                return "query".equals(donde.group(1));
            }
        }
        return false;
    }

    /** Los metodos publicados de todo {@code @RestController}, sin agrupar por operacion. */
    private static List<Method> handlersConCuerpo() {
        List<Method> handlers = new ArrayList<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            Class<?> tipo = clase.reflect();
            if (!AnnotatedElementUtils.hasAnnotation(tipo, RestController.class)) {
                continue;
            }
            for (Method metodo : tipo.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class)
                        != null) {
                    handlers.add(metodo);
                }
            }
        }
        return handlers;
    }

    private static Map<String, Handler> handlersPublicados() {
        Map<String, Handler> publicados = new LinkedHashMap<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            Class<?> tipo = clase.reflect();
            if (!AnnotatedElementUtils.hasAnnotation(tipo, RestController.class)) {
                continue;
            }
            RequestMapping deLaClase =
                    AnnotatedElementUtils.findMergedAnnotation(tipo, RequestMapping.class);
            String base = deLaClase == null ? "" : primero(deLaClase.path());

            for (Method metodo : tipo.getDeclaredMethods()) {
                RequestMapping mapeo =
                        AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeo == null) {
                    continue;
                }
                String ruta = sinRaiz(base + primero(mapeo.path()));
                Handler handler = new Handler(nombresDeConsulta(metodo), nombresDelCuerpo(metodo));
                for (RequestMethod verbo : verbos(mapeo)) {
                    // Una misma operacion puede tener dos handlers: el contrato publica UNA ruta y
                    // el controlador la parte por `params = "formato"` —el listado en JSON y el
                    // mismo listado como documento—. Lo que la operacion sabe leer es la union de
                    // los dos; quedarse con el ultimo declarado convertiria el orden del codigo
                    // fuente en parte de la comprobacion.
                    publicados.merge(verbo.name() + " " + ruta, handler, Handler::unido);
                }
            }
        }
        return publicados;
    }

    /**
     * Lo que el metodo lee de la consulta: sus {@code @RequestParam} y lo que Spring le enlaza.
     *
     * <p>Los dos, y no solo el primero: un parametro <b>sin anotar</b> cuyo tipo es un record —
     * {@link kamayuk.rentas.web.ParametrosDePaginacion} en las 100 lecturas paginadas— lo compone
     * Spring de la consulta, componente a componente. Contarlo solo por su anotacion diria que
     * ningun controlador lee {@code pagina}, y el contrato la declara en todas.
     */
    private static Set<String> nombresDeConsulta(Method metodo) {
        Set<String> nombres = new LinkedHashSet<>();
        for (Parameter parametro : metodo.getParameters()) {
            RequestParam anotacion = parametro.getAnnotation(RequestParam.class);
            if (anotacion != null) {
                String declarado =
                        anotacion.name().isEmpty() ? anotacion.value() : anotacion.name();
                nombres.add(declarado.isEmpty() ? parametro.getName() : declarado);
                continue;
            }
            if (parametro.getAnnotations().length == 0 && parametro.getType().isRecord()) {
                for (RecordComponent componente : parametro.getType().getRecordComponents()) {
                    nombres.add(componente.getName());
                }
            }
        }
        return nombres;
    }

    /**
     * Los campos del {@code @RequestBody}, cuando es un record.
     *
     * <p>Un record es la forma que tienen todos los cuerpos del proyecto —la «lista blanca» de cada
     * controlador— y sus componentes son, letra por letra, las claves del JSON. Un cuerpo que no
     * sea un record no aporta nombres: no hay nada que comparar, y suponerlos seria peor.
     */
    private static Set<String> nombresDelCuerpo(Method metodo) {
        Set<String> nombres = new LinkedHashSet<>();
        for (Parameter parametro : metodo.getParameters()) {
            if (parametro.getAnnotation(RequestBody.class) == null) {
                continue;
            }
            Class<?> tipo = parametro.getType();
            if (!tipo.isRecord()) {
                continue;
            }
            for (RecordComponent componente : tipo.getRecordComponents()) {
                nombres.add(componente.getName());
            }
        }
        return nombres;
    }

    private static Set<RequestMethod> verbos(RequestMapping mapeo) {
        Set<RequestMethod> verbos = new LinkedHashSet<>(List.of(mapeo.method()));
        if (verbos.isEmpty()) {
            verbos.add(RequestMethod.GET);
        }
        return verbos;
    }

    private static String primero(String[] rutas) {
        return rutas.length == 0 ? "" : rutas[0];
    }

    private static String sinRaiz(String ruta) {
        return ruta.startsWith(RAIZ) ? ruta.substring(RAIZ.length()) : ruta;
    }

    private static Path raizDelRepositorio() {
        Path actual = Path.of("").toAbsolutePath();
        List<Path> intentos = new ArrayList<>();
        while (actual != null) {
            intentos.add(actual);
            if (Files.exists(actual.resolve("docs/50-api/openapi/rentas-v1.yaml"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException(
                "No se encontro la raiz del repositorio buscando hacia arriba: " + intentos);
    }
}
