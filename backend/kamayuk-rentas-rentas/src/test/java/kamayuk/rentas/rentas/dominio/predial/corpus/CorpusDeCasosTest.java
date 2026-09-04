package kamayuk.rentas.rentas.dominio.predial.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.CaracteristicasDeLaPartida;
import kamayuk.rentas.parametros.CatalogoDeReglas;
import kamayuk.rentas.parametros.Concepto;
import kamayuk.rentas.parametros.EntradaDeCalculo;
import kamayuk.rentas.parametros.EstadoDelCalculo;
import kamayuk.rentas.parametros.IdentificadorDeRegla;
import kamayuk.rentas.parametros.MotorDeReglas;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.parametros.ReglaDeAgregacion;
import kamayuk.rentas.parametros.ReglaTributaria;
import kamayuk.rentas.parametros.ResultadoDelCalculo;
import kamayuk.rentas.parametros.ResultadoDelContribuyente;
import kamayuk.rentas.rentas.aplicacion.CuadroPredialParametrizado;
import kamayuk.rentas.rentas.dominio.predial.MinimoImponible;
import kamayuk.rentas.rentas.dominio.predial.RT001ValorDeTerreno;
import kamayuk.rentas.rentas.dominio.predial.RT011BaseImponibleDelContribuyente;
import kamayuk.rentas.rentas.dominio.predial.TramosProgresivosAcumulativos;
import kamayuk.rentas.rentas.parametros.DerivadoPublicado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El corpus de casos de NEG-05, con sus <b>tres primeras cifras cerradas</b> (E-5, #201, #188).
 *
 * <p>#30 pedia «casos de prueba con las cifras esperadas en blanco». Escrito asi, un corpus sin
 * cifras no verifica nada: es una lista de deseos. Hay una forma en que si verifica: <b>se deja en
 * blanco la cifra, no las aristas del grafo</b>. Con parametros ficticios se comprueba que un caso
 * aplica exactamente las reglas que declara, produce exactamente los conceptos que declara, y que
 * los parametros que declara son los que la regla pide <b>de verdad</b> —recogidos corriendola con
 * un conjunto vacio, no escritos a mano—.
 *
 * <h2>Y desde #188, tres casos ya no tienen la cifra en blanco</h2>
 *
 * <p>Los dos del cuadro de tramos del articulo 13 y el del minimo imponible. Sus parametros —la
 * UIT, los tres tramos, los dos limites y el minimo— estan transcritos del TUO LTM, firmados a dos
 * manos (ADR-0007) y publicados en {@code parametros-2026.csv}, asi que <b>la comparacion al
 * centimo se hace contra lo que el sistema publica</b> y no contra un valor ficticio: un caso con
 * cifra compone su conjunto con {@link
 * kamayuk.rentas.rentas.parametros.DerivadoPublicado#conjuntoCon}, que falla nombrando la llave que
 * el corpus no respalda. Comparar contra un arancel inventado pasaria en verde, que es peor que no
 * tener la cifra.
 *
 * <p>Y para lo que todavia no se puede correr, el corpus sigue siendo un <b>libro mayor</b>: cada
 * caso dice quien lo impide, y las cuentas cuadran en las dos direcciones. Un caso que se declara
 * sin regla y cuya regla si esta registrada pone esta prueba en rojo; uno que se declara fuera del
 * motor nombrando una clase que no existe, tambien.
 */
@DisplayName("Corpus de casos de NEG-05 (E-5)")
class CorpusDeCasosTest {

    /**
     * Las reglas que NEG-05 §2 <b>define</b>.
     *
     * <p>GOB-03 y #30 hablan de «{@code RT-001}…{@code RT-016}», que suena a dieciseis. Son
     * <b>doce</b>: NEG-05 §2 no define {@code RT-006} a {@code RT-009}, y los identificadores
     * quedan sin usar. Esta lista es el inventario real, y por eso la prueba exige un archivo por
     * cada una de estas y no por un rango.
     */
    private static final List<String> REGLAS_DE_NEG05 =
            List.of(
                    "RT-001", "RT-002", "RT-003", "RT-004", "RT-005", "RT-010", "RT-011", "RT-012",
                    "RT-013", "RT-014", "RT-015", "RT-016");

    /**
     * Los casos borde que NEG-05 §2 enumera, uno por uno.
     *
     * <p>NEG-05 §6 pide «resolucion de los <b>14</b> casos borde de §2» y en §2 hay
     * <b>diecisiete</b>: tres en RT-001, cuatro en el bloque RT-002/RT-004, cuatro en RT-011,
     * cuatro en RT-012 y dos en RT-014. El corpus los enumera para que el numero deje de estar en
     * discusion.
     */
    private static final List<String> CASOS_BORDE_DE_NEG05 =
            List.of(
                    "dos-vias-con-arancel-distinto",
                    "predio-sin-arancel-asignado",
                    "terreno-sin-habilitacion-urbana",
                    "construccion-en-curso",
                    "mas-de-un-material-predominante",
                    "antiguedad-mayor-al-maximo",
                    "ampliacion-con-antiguedad-distinta",
                    "copropiedad",
                    "predios-en-varias-municipalidades",
                    "sucesion-indivisa",
                    "poseedor-sin-titulo",
                    "pensionista-con-mas-de-un-predio",
                    "pensionista-a-mitad-de-ejercicio",
                    "deduccion-mayor-que-la-base",
                    "concurrencia-de-deducciones",
                    "base-cero-por-deduccion",
                    "predio-inafecto");

    /**
     * Las reglas registradas hoy. El corpus se mide contra esto, no contra lo que deberia haber.
     */
    private static final List<ReglaTributaria> REGLAS_DE_PARTIDA =
            List.of(new RT001ValorDeTerreno());

    private static final List<ReglaDeAgregacion> REGLAS_DE_AGREGACION =
            List.of(new RT011BaseImponibleDelContribuyente());

    /** «A centimo», ADR-0018. No es una cifra tributaria: es la escala del importe. */
    private static final int ESCALA_DE_ADR_0018 = 2;

    private static final RoundingMode MODO_DE_ADR_0018 = RoundingMode.HALF_UP;

    /**
     * El redondeo que ADR-0018 decidio, y por eso ya no se llama ficticio.
     *
     * <p>Lo era mientras D-03 seguia abierta en sus tres partes. Se cerraron el 2026-08-28: se
     * redondea <b>al cierre de cada regla, a centimo y {@code HALF_UP}</b>, y los intermedios
     * corren sin redondear. Cubre los catorce puntos porque el corpus no sabe cual cierra cada
     * regla, y esa es la unica parte que sigue siendo una aproximacion.
     *
     * <p><b>No sale del conjunto sellado, y deberia.</b> ADR-0018 §Consecuencias dice que las filas
     * {@code REDONDEO:‹punto›} del piloto se pueden publicar por el mismo camino que todo
     * parametro; hoy el derivado no publica ninguna, asi que {@code PoliticasDeRedondeoSelladas}
     * fallaria con {@code SinPuntosObservados} y el corpus no podria comparar nada. La prueba
     * {@link #elDerivadoTodaviaNoPublicaElRedondeo()} se pone roja el dia que se publiquen, para
     * que quien lo haga venga aqui a leerlas del conjunto en vez de dejar dos verdades.
     */
    private static final PoliticasDeRedondeo REDONDEO_DE_ADR_0018 = redondeoDeAdr0018();

    private static final ValorNormativo VALOR_FICTICIO = ValorNormativo.de("1");

    /** El unico ejercicio cuyo derivado esta publicado y firmado hoy. */
    private static final int EJERCICIO_PUBLICADO = 2026;

    /**
     * Cuantos casos tienen ya su cifra cerrada.
     *
     * <p>Es el contador de #188 y no un detalle: cada uno exige que su parametro este transcrito,
     * firmado a dos manos y publicado en el derivado. Hoy son los dos del cuadro de tramos y el del
     * minimo, todos del articulo 13 del TUO LTM.
     */
    private static final int CIFRAS_CERRADAS = 3;

    private static List<CasoDelCorpus> todosLosCasos() {
        List<CasoDelCorpus> casos = new ArrayList<>();
        for (String regla : REGLAS_DE_NEG05) {
            casos.addAll(LectorDelCorpus.de(regla));
        }
        return casos;
    }

    // ------------------------------------------------------------------
    // Cobertura: que el corpus cubra lo que NEG-05 describe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cada regla que NEG-05 define tiene su archivo, con al menos un caso")
    void cadaReglaTieneSuArchivo() {
        for (String regla : REGLAS_DE_NEG05) {
            assertThat(LectorDelCorpus.de(regla)).describedAs("casos de %s", regla).isNotEmpty();
        }
    }

    @Test
    @DisplayName("cada caso borde de NEG-05 §2 tiene su fila, y ninguna fila inventa uno")
    void cadaCasoBordeTieneSuFila() {
        Set<String> declarados = new TreeSet<>();
        for (CasoDelCorpus caso : todosLosCasos()) {
            caso.casoBorde().ifPresent(declarados::add);
        }

        assertThat(declarados)
                .describedAs("casos borde cubiertos por el corpus")
                .containsExactlyInAnyOrderElementsOf(CASOS_BORDE_DE_NEG05);
    }

    @Test
    @DisplayName("el identificador de cada caso corresponde al archivo en el que vive")
    void elIdentificadorCorrespondeAlArchivo() {
        for (String regla : REGLAS_DE_NEG05) {
            for (CasoDelCorpus caso : LectorDelCorpus.de(regla)) {
                assertThat(caso.caso()).startsWith(regla + "-c");
                assertThat(caso.regla()).isEqualTo(regla);
            }
        }
    }

    // ------------------------------------------------------------------
    // El libro mayor: que el estado declarado sea el real
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un caso «sin regla» cuya regla si esta registrada pone esto en rojo")
    void loQueSeDeclaraSinReglaNoEstaRegistrado() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.estado().clase() != EstadoDelCaso.Clase.SIN_REGLA) {
                continue;
            }
            List<String> queFaltan =
                    caso.reglasEsperadas().stream()
                            .filter(regla -> !estaRegistrada(regla))
                            .toList();
            assertThat(queFaltan)
                    .describedAs(
                            "%s se declara SIN_REGLA (%s) y todas las reglas que declara —%s— estan"
                                    + " registradas en el motor: el caso ya se puede correr, y el"
                                    + " corpus se quedo viejo",
                            caso.caso(), caso.estado(), caso.reglasEsperadas())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("un caso «fuera del motor» nombra una clase que existe y que el motor no registra")
    void loQueSeDeclaraFueraDelMotorExiste() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.estado().clase() != EstadoDelCaso.Clase.FUERA_DEL_MOTOR) {
                continue;
            }
            try {
                Class.forName(caso.estado().detalle());
            } catch (ClassNotFoundException noExiste) {
                fail(
                        "%s dice que su regla vive en %s y esa clase no existe"
                                .formatted(caso.caso(), caso.estado().detalle()));
            }
            for (String regla : caso.reglasEsperadas()) {
                assertThat(estaRegistrada(regla))
                        .describedAs(
                                "%s se declara fuera del motor y %s si esta registrada en el",
                                caso.caso(), regla)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("un caso «sin criterio» es un caso borde de NEG-05, no una regla que falta")
    void loQueNoTieneCriterioEsUnCasoBorde() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.estado().clase() != EstadoDelCaso.Clase.SIN_CRITERIO) {
                continue;
            }
            assertThat(caso.casoBorde())
                    .describedAs(
                            "%s dice que no hay criterio decidido; si no es un caso borde de"
                                    + " NEG-05 §2, lo que falta es otra cosa",
                            caso.caso())
                    .isPresent();
            assertThat(CASOS_BORDE_DE_NEG05).contains(caso.casoBorde().orElseThrow());
        }
    }

    @Test
    @DisplayName("todo caso ejecutable declara reglas que el motor tiene registradas")
    void loEjecutableEstaRegistrado() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.estado().clase() != EstadoDelCaso.Clase.EJECUTABLE
                    && caso.estado().clase() != EstadoDelCaso.Clase.FALLA_ESPERADA) {
                continue;
            }
            assertThat(caso.reglasEsperadas())
                    .describedAs("reglas de %s", caso.caso())
                    .isNotEmpty()
                    .allSatisfy(
                            regla ->
                                    assertThat(estaRegistrada(regla))
                                            .describedAs(
                                                    "%s se declara %s y %s no esta registrada",
                                                    caso.caso(), caso.estado(), regla)
                                            .isTrue());
        }
    }

    // ------------------------------------------------------------------
    // Las aristas: lo que si se puede comprobar hoy, sin una sola cifra
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "un caso ejecutable aplica exactamente sus reglas y produce exactamente sus conceptos")
    void lasAristasDeclaradasSonLasQueElMotorRecorre() {
        for (CasoDelCorpus caso : ejecutables()) {
            Corrida corrida = correr(caso);

            assertThat(corrida.reglasAplicadas())
                    .describedAs("reglas aplicadas en %s", caso.caso())
                    .containsExactlyInAnyOrderElementsOf(caso.reglasEsperadas());
            assertThat(corrida.conceptosProducidos())
                    .describedAs("conceptos producidos en %s", caso.caso())
                    .containsExactlyInAnyOrderElementsOf(caso.conceptosEsperados());
        }
    }

    @Test
    @DisplayName("los parametros que un caso declara son los que la regla pide de verdad")
    void losParametrosDeclaradosSonLosQueLaReglaPide() {
        for (CasoDelCorpus caso : ejecutables()) {
            assertThat(correr(caso).parametrosPedidos())
                    .describedAs(
                            "parametros que %s pide de verdad, recogidos corriendola con un"
                                    + " conjunto vacio",
                            caso.caso())
                    .containsExactlyInAnyOrderElementsOf(caso.parametrosRequeridos());
        }
    }

    @Test
    @DisplayName("un caso que debe fallar falla, y con la excepcion que declara")
    void loQueDebeFallarFalla() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.estado().clase() != EstadoDelCaso.Clase.FALLA_ESPERADA) {
                continue;
            }
            String excepcion = caso.estado().detalle();
            assertThatThrownBy(() -> correrSinRellenarParametros(caso))
                    .describedAs("%s debe fallar con %s", caso.caso(), excepcion)
                    .satisfies(
                            fallo ->
                                    assertThat(fallo.getClass().getSimpleName())
                                            .isEqualTo(excepcion));
        }
    }

    @Test
    @DisplayName("una cifra esperada sin su fuente no se admite")
    void ningunaCifraSinFuente() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.esperado().isPresent()) {
                assertThat(caso.fuenteDelEsperado())
                        .describedAs(
                                "%s trae una cifra esperada sin decir de donde sale; sin fuente no"
                                        + " se distingue de una inventada",
                                caso.caso())
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("una cifra esperada se compara al centimo contra el conjunto que se publica")
    void laCifraEsperadaSeComparaAlCentimo() {
        for (CasoDelCorpus caso : todosLosCasos()) {
            if (caso.esperado().isEmpty()) {
                continue;
            }
            assertThat(caso.estado().clase())
                    .describedAs(
                            "%s trae una cifra esperada y no se puede correr: nadie la"
                                    + " comprobaria",
                            caso.caso())
                    .isIn(EstadoDelCaso.Clase.EJECUTABLE, EstadoDelCaso.Clase.FUERA_DEL_MOTOR);

            // Los parametros salen del derivado publicable, no de VALOR_FICTICIO: comparar al
            // centimo contra un arancel inventado no probaria nada y pasaria en verde, que es peor
            // que no tener la cifra. `conjuntoCon` falla nombrando la llave que el corpus no
            // respalda, y por eso una cifra solo se cierra cuando su parametro ya esta firmado.
            Dinero calculado = calcularConLoPublicado(caso);

            assertThat(calculado)
                    .describedAs(
                            "%s, al centimo, con %s",
                            caso.caso(), caso.fuenteDelEsperado().orElseThrow())
                    .isEqualTo(Dinero.de(caso.esperado().orElseThrow()));
        }
    }

    @Test
    @DisplayName(
            "la base del contribuyente no es la de cada predio: los tramos corren una sola vez")
    void laBaseDelContribuyenteNoEsLaDeCadaPredio() {
        CasoDelCorpus agregado = caso("RT-013-c02");
        Dinero base = Dinero.de(agregado.entradas().get("BASE_AFECTA"));
        Dinero porPredio = new Dinero(base.valor().divide(java.math.BigDecimal.valueOf(3)));

        Dinero delContribuyente = calcularConLoPublicado(agregado);
        Dinero unoAUno = Dinero.CERO;
        for (int predio = 0; predio < 3; predio++) {
            unoAUno = unoAUno.mas(tramosSobre(agregado, porPredio));
        }

        // Es AC1 de #30 con cifras reales, y hasta hoy no se podia escribir. Los tramos del
        // articulo 13 corren sobre la base DEL CONTRIBUYENTE: calculada predio por predio, los tres
        // se quedan en el primer tramo y el error es sistematico a la baja en todo el padron
        // (NEG-05 §1). Ninguna cifra del recibo lo diria.
        assertThat(delContribuyente)
                .describedAs("la base agregada cruza al segundo tramo")
                .isEqualTo(Dinero.de(agregado.esperado().orElseThrow()));
        assertThat(unoAUno)
                .describedAs("predio por predio, ninguno de los tres sale del primer tramo")
                .isLessThan(delContribuyente);
    }

    @Test
    @DisplayName(
            "el derivado todavia no publica el redondeo: el dia que lo haga, esto se pone rojo")
    void elDerivadoTodaviaNoPublicaElRedondeo() {
        // ADR-0018 §Consecuencias deja publicables las filas REDONDEO:‹punto› del piloto. Mientras
        // no lo esten, el corpus compara con la politica que el ADR decidio, escrita arriba. El dia
        // que se publiquen hay que leerlas del conjunto —dos verdades sobre el mismo redondeo es
        // lo que ARQ-09 §3 evita—, y esta prueba es el recordatorio que no se olvida.
        assertThat(DerivadoPublicado.numerosVigentesEn(EJERCICIO_PUBLICADO).keySet())
                .noneMatch(llave -> llave.startsWith(PoliticasDeRedondeoSelladas.TIPO + "|"));
    }

    @Test
    @DisplayName("el corpus dice cuantos casos siguen sin cifra, y por quien esperan")
    void elCorpusDiceCuantoFalta() {
        List<CasoDelCorpus> casos = todosLosCasos();
        long sinCifra = casos.stream().filter(CasoDelCorpus::sinCifra).count();
        Map<String, Long> porEstado =
                casos.stream()
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        c ->
                                                c.estado().clase()
                                                        + (c.estado().detalle().isEmpty()
                                                                ? ""
                                                                : ":" + c.estado().detalle()),
                                        java.util.TreeMap::new,
                                        java.util.stream.Collectors.counting()));

        System.out.println("Corpus de casos de NEG-05: " + casos.size() + " casos");
        System.out.println("  SIN_CIFRA: " + sinCifra + " — bajar este numero es #188 avanzando");
        porEstado.forEach((estado, cuantos) -> System.out.println("  " + estado + ": " + cuantos));

        assertThat(casos).describedAs("el corpus no puede estar vacio").isNotEmpty();

        // Ya no son todos. Las tres cifras cerradas son las de las dos reglas del articulo 13
        // —los tramos y el minimo—, cuyos parametros estan publicados y firmados a dos manos. Las
        // demas siguen esperando a D-11, a los dos cuadros de GOB-03 y a los valores de ordenanza
        // (D-02b). Este numero es el libro mayor de #188: bajarlo cuesta transcribir y firmar, y
        // subirlo sin querer —borrando una cifra ya cerrada— pone esto rojo.
        assertThat(casos.size() - sinCifra)
                .describedAs("casos con la cifra cerrada y comparada al centimo")
                .isEqualTo(CIFRAS_CERRADAS);
        assertThat(sinCifra)
                .describedAs("y los que siguen esperando a que su cifra se pueda cerrar")
                .isEqualTo(casos.size() - CIFRAS_CERRADAS);
    }

    // ------------------------------------------------------------------
    // Correr un caso
    // ------------------------------------------------------------------

    private record Corrida(
            List<String> reglasAplicadas,
            List<String> conceptosProducidos,
            Map<String, Dinero> valores,
            List<String> parametrosPedidos) {}

    /**
     * Calcula el caso con los parametros que el corpus <b>publica</b>, no con los ficticios.
     *
     * <p>El conjunto se compone con <b>exactamente</b> las llaves que el caso declara, y con los
     * valores del derivado: declararlas de menos falla al construir el cuadro —no pasa en verde con
     * lo que otro caso dejo cargado— y declarar una que el corpus no respalda falla nombrandola.
     */
    private Dinero calcularConLoPublicado(CasoDelCorpus caso) {
        // La rama de EJECUTABLE no la ejercita ningun caso todavia, y no es codigo muerto: es la
        // que corre el dia que una regla del motor tenga su cifra. Hoy la unica registrada es
        // RT-001, y su arancel lo fija la ordenanza de cada municipalidad (D-02b), asi que no esta
        // en el derivado —y por eso su caso no puede llevar importe—.
        if (caso.estado().clase() == EstadoDelCaso.Clase.EJECUTABLE) {
            Corrida corrida = correrCon(caso, conjuntoDelCaso(caso), REDONDEO_DE_ADR_0018);
            Concepto ultimo =
                    Concepto.de(
                            caso.conceptosEsperados().get(caso.conceptosEsperados().size() - 1));
            Dinero valor = corrida.valores().get(ultimo.nombre());
            assertThat(valor)
                    .describedAs("%s no produjo %s", caso.caso(), ultimo.nombre())
                    .isNotNull();
            return valor;
        }
        return cierreFueraDelMotor(caso, cuadroDelCaso(caso));
    }

    /** El desglose del articulo 13 sobre otra base, con el mismo cuadro publicado del caso. */
    private Dinero tramosSobre(CasoDelCorpus caso, Dinero base) {
        CuadroPredialParametrizado.Vigente cuadro = cuadroDelCaso(caso);
        return TramosProgresivosAcumulativos.calcular(base, cuadro.tramos(), REDONDEO_DE_ADR_0018);
    }

    /**
     * Cierra el calculo de una regla que vive fuera del motor, con el cuadro del ejercicio.
     *
     * <p>{@code RT-013} y {@code RT-014} transforman un valor ya agregado en otro, un tercer caso
     * que {@code MotorDeReglas} no cubre; sus clases lo dicen en su javadoc. Que el corpus sepa
     * correrlas es lo que permite que sus casos lleven cifra: sin esto habria que registrarlas en
     * el motor solo para poder compararlas, que es cambiar el diseno para satisfacer una prueba.
     *
     * <p>Un caso fuera del motor que traiga cifra y cuya regla no este aqui falla nombrandola.
     */
    private Dinero cierreFueraDelMotor(
            CasoDelCorpus caso, CuadroPredialParametrizado.Vigente cuadro) {
        return switch (caso.regla()) {
            case "RT-013" ->
                    TramosProgresivosAcumulativos.calcular(
                            Dinero.de(caso.entradas().get("BASE_AFECTA")),
                            cuadro.tramos(),
                            REDONDEO_DE_ADR_0018);
            case "RT-014" ->
                    MinimoImponible.aplicar(
                            Dinero.de(caso.entradas().get("IMPUESTO_CALCULADO")),
                            cuadro.minimoImponible());
            default ->
                    throw new IllegalStateException(
                            caso.caso()
                                    + " trae una cifra esperada y vive fuera del motor, pero nadie"
                                    + " sabe correr "
                                    + caso.regla()
                                    + ": sin eso la cifra no se comprobaria");
        };
    }

    private CuadroPredialParametrizado.Vigente cuadroDelCaso(CasoDelCorpus caso) {
        Ejercicio ejercicio = new Ejercicio(caso.ejercicio());
        return new CuadroPredialParametrizado(
                        DerivadoPublicado.conjuntoCon(
                                ejercicio, Set.copyOf(caso.parametrosRequeridos())))
                .vigenteEn(ejercicio);
    }

    private ParametrosSellados conjuntoDelCaso(CasoDelCorpus caso) {
        Ejercicio ejercicio = new Ejercicio(caso.ejercicio());
        return DerivadoPublicado.conjuntoCon(ejercicio, Set.copyOf(caso.parametrosRequeridos()))
                .vigenteEn(ejercicio);
    }

    private CasoDelCorpus caso(String identificador) {
        return todosLosCasos().stream()
                .filter(c -> c.caso().equals(identificador))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("El corpus no tiene " + identificador));
    }

    private List<CasoDelCorpus> ejecutables() {
        return todosLosCasos().stream()
                .filter(c -> c.estado().clase() == EstadoDelCaso.Clase.EJECUTABLE)
                .toList();
    }

    /**
     * Corre el caso rellenando con valores ficticios los parametros que la regla vaya pidiendo, y
     * devuelve <b>que pidio</b>. Es lo que convierte la columna {@code parametros_requeridos} en
     * verificada: nadie la escribe a mano, se recoge.
     */
    private Corrida correr(CasoDelCorpus caso) {
        Set<String> pedidos = new LinkedHashSet<>();
        for (int intento = 0; intento <= pedidos.size() + 20; intento++) {
            try {
                Corrida corrida = correrCon(caso, pedidos);
                return new Corrida(
                        corrida.reglasAplicadas(),
                        corrida.conceptosProducidos(),
                        corrida.valores(),
                        List.copyOf(pedidos));
            } catch (ParametrosSellados.ParametroAusente falta) {
                if (!pedidos.add(falta.llave().orElseThrow())) {
                    throw falta;
                }
            }
        }
        throw new IllegalStateException(
                "El caso " + caso.caso() + " sigue pidiendo parametros despues de 20 vueltas");
    }

    private Corrida correrSinRellenarParametros(CasoDelCorpus caso) {
        return correrCon(caso, Set.of());
    }

    private Corrida correrCon(CasoDelCorpus caso, Set<String> parametros) {
        return correrCon(
                caso, sellados(new Ejercicio(caso.ejercicio()), parametros), REDONDEO_DE_ADR_0018);
    }

    private Corrida correrCon(
            CasoDelCorpus caso, ParametrosSellados sellados, PoliticasDeRedondeo redondeo) {
        Ejercicio ejercicio = new Ejercicio(caso.ejercicio());
        EntradaDeCalculo entrada =
                new EntradaDeCalculo(
                        ejercicio,
                        estadoDeclarado(caso),
                        caracteristicas(caso),
                        sellados,
                        redondeo);

        boolean agrega = caso.reglasEsperadas().stream().anyMatch(this::esAgregacion);
        MotorDeReglas motor = new MotorDeReglas(catalogo());
        List<String> reglas = new ArrayList<>();
        List<String> conceptos = new ArrayList<>();
        Map<String, Dinero> valores = new java.util.LinkedHashMap<>();

        if (agrega) {
            ResultadoDelContribuyente resultado = motor.aplicarAlContribuyente(List.of(entrada));
            reglas.addAll(resultado.reglasComoTexto());
            for (ResultadoDelCalculo porPartida : resultado.porPartida()) {
                anadirProducidos(caso, porPartida.estado(), conceptos, valores);
            }
            anadirProducidos(caso, resultado.agregado(), conceptos, valores);
        } else {
            ResultadoDelCalculo resultado = motor.aplicarA(entrada);
            resultado.reglasAplicadas().forEach(r -> reglas.add(r.valor()));
            anadirProducidos(caso, resultado.estado(), conceptos, valores);
        }
        return new Corrida(
                List.copyOf(reglas), List.copyOf(conceptos), Map.copyOf(valores), List.of());
    }

    /** Lo que el calculo produjo: el estado menos lo que la partida ya traia declarado. */
    private void anadirProducidos(
            CasoDelCorpus caso,
            EstadoDelCalculo estado,
            List<String> conceptos,
            Map<String, Dinero> valores) {
        for (Concepto concepto : estado.conceptos()) {
            if (!caso.entradas().containsKey(concepto.nombre())) {
                conceptos.add(concepto.nombre());
                estado.valor(concepto).ifPresent(v -> valores.put(concepto.nombre(), v));
            }
        }
    }

    /**
     * El catalogo con el que se corre. <b>Todas</b> las reglas registradas de la fase que toca, no
     * solo las que el caso declara: si el motor aplicara una que el caso no espera, la prueba tiene
     * que verlo.
     */
    private CatalogoDeReglas catalogo() {
        CatalogoDeReglas catalogo = CatalogoDeReglas.vacio();
        for (ReglaTributaria regla : REGLAS_DE_PARTIDA) {
            catalogo = catalogo.con(regla);
        }
        for (ReglaDeAgregacion regla : REGLAS_DE_AGREGACION) {
            catalogo = catalogo.con(regla);
        }
        return catalogo;
    }

    private EstadoDelCalculo estadoDeclarado(CasoDelCorpus caso) {
        EstadoDelCalculo estado = EstadoDelCalculo.vacio();
        for (Map.Entry<String, String> entrada : caso.entradas().entrySet()) {
            estado = estado.mas(Concepto.de(entrada.getKey()), Dinero.de(entrada.getValue()));
        }
        return estado;
    }

    private CaracteristicasDeLaPartida caracteristicas(CasoDelCorpus caso) {
        if (caso.caracteristicas().isEmpty()) {
            return CaracteristicasDeLaPartida.ninguna();
        }
        CaracteristicasDeLaPartida.Constructor constructor = null;
        for (Map.Entry<String, String> par : caso.caracteristicas().entrySet()) {
            constructor =
                    constructor == null
                            ? CaracteristicasDeLaPartida.de(par.getKey(), par.getValue())
                            : constructor.y(par.getKey(), par.getValue());
        }
        return java.util.Objects.requireNonNull(constructor).construir();
    }

    private ParametrosSellados sellados(Ejercicio ejercicio, Set<String> llaves) {
        ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
        for (String llave : llaves) {
            int corte = llave.indexOf(':');
            String tipo = corte < 0 ? llave : llave.substring(0, corte);
            String clave = corte < 0 ? null : llave.substring(corte + 1);
            constructor.numero(tipo, clave, VALOR_FICTICIO);
        }
        return constructor.construir();
    }

    private boolean esAgregacion(String regla) {
        return REGLAS_DE_AGREGACION.stream()
                .anyMatch(r -> r.identificador().equals(IdentificadorDeRegla.de(regla)));
    }

    private boolean estaRegistrada(String regla) {
        IdentificadorDeRegla identificador = IdentificadorDeRegla.de(regla);
        return REGLAS_DE_PARTIDA.stream().anyMatch(r -> r.identificador().equals(identificador))
                || REGLAS_DE_AGREGACION.stream()
                        .anyMatch(r -> r.identificador().equals(identificador));
    }

    private static PoliticasDeRedondeo redondeoDeAdr0018() {
        PoliticasDeRedondeo.Constructor constructor = PoliticasDeRedondeo.construir();
        for (PuntoDeRedondeo punto : PuntoDeRedondeo.values()) {
            constructor.en(punto, new PoliticaDeRedondeo(ESCALA_DE_ADR_0018, MODO_DE_ADR_0018));
        }
        return constructor.construir();
    }
}
