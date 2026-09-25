package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.DeclaracionesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.PadronDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ParametrosDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CorreccionDeLinea;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.nucleo.DeclaracionDelEjercicio;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Liquidar y reliquidar (#49, AC 1, AC 2, AC 4 y AC 5).
 *
 * <p>Sin base de datos: lo que se verifica aquí es la orquestación —qué conjunto se fija, qué
 * versión se encadena, qué se escribe y qué no—. Lo que la base garantiza por su cuenta —RLS,
 * unicidad, privilegios— lo verifica {@code LiquidacionJdbcTest} contra PostgreSQL real.
 */
@DisplayName("#49 — Liquidar y reliquidar")
class LiquidarYReliquidarTest {

    private static final Observacion OBSERVACION = Observacion.de("Se liquida para la prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final Ejercicio E2025 = new Ejercicio(2025);
    private static final long PREDIO = 20L;
    private static final long CONTRIBUYENTE = 10L;
    private static final long FICHA_DECLARADA = 700L;
    private static final long FICHA_VIGENTE = 900L;
    private static final long CONJUNTO_2024 = 41L;
    private static final long CONJUNTO_2025 = 42L;

    private ActasEnMemoria actas;
    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ParametrosDeMentira parametros;
    private PadronDeMentira catastro;
    private DeclaracionesDeMentira rentas;
    private LiquidarFiscalizacion liquidar;
    private ReliquidarFiscalizacion reliquidar;
    private ConsultaDeLiquidaciones consulta;
    private long actaId;

    @BeforeEach
    void armar() {
        actas = new ActasEnMemoria();
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        parametros =
                new ParametrosDeMentira()
                        .sellar(2024, CONJUNTO_2024, 1)
                        .sellar(2025, CONJUNTO_2025, 1);
        catastro =
                new PadronDeMentira()
                        .conFicha(FICHA_DECLARADA, AreaM2.de("120.00"))
                        .conFicha(FICHA_VIGENTE, AreaM2.de("300.00"))
                        .conCaracteristicas(
                                PREDIO, "CASA_HABITACION", AreaM2.de("300.00"), FICHA_VIGENTE);
        rentas =
                new DeclaracionesDeMentira()
                        .con(
                                PREDIO,
                                new DeclaracionDelEjercicio(
                                        1L,
                                        "DJ-0001",
                                        E2024,
                                        CONTRIBUYENTE,
                                        LocalDate.of(2024, 2, 20),
                                        false,
                                        FICHA_DECLARADA));

        liquidar =
                new LiquidarFiscalizacion(
                        actas,
                        liquidaciones,
                        movimientos,
                        parametros,
                        catastro,
                        catastro,
                        rentas,
                        registro -> {});
        reliquidar = new ReliquidarFiscalizacion(actas, liquidaciones, liquidar);
        consulta = new ConsultaDeLiquidaciones(liquidaciones, movimientos);

        actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                FICHA_VIGENTE,
                                LocalDate.of(2026, 3, 1),
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                "ampliacion no declarada",
                                OBSERVACION));
        liquidaciones.actaDe(actaId, CONTRIBUYENTE);
    }

    @Nested
    @DisplayName("Cada linea fija su conjunto sellado (AC 1)")
    class ConjuntoSellado {

        @Test
        @DisplayName("una linea por ejercicio, y cada una con el conjunto sellado del suyo")
        void unaLineaPorEjercicioConSuConjunto() {
            Liquidacion emitida = liquidarDe(E2024, E2025);

            List<LineaDeLiquidacion> lineas = liquidaciones.lineasDe(emitida.identificador());
            assertThat(lineas).hasSize(2);
            assertThat(lineas)
                    .as("los parametros de 2024 no son los de 2025")
                    .anySatisfy(
                            linea -> {
                                assertThat(linea.ejercicio()).isEqualTo(E2024);
                                assertThat(linea.conjuntoId()).isEqualTo(CONJUNTO_2024);
                            })
                    .anySatisfy(
                            linea -> {
                                assertThat(linea.ejercicio()).isEqualTo(E2025);
                                assertThat(linea.conjuntoId()).isEqualTo(CONJUNTO_2025);
                            });
        }

        @Test
        @DisplayName("sellar otra version despues NO altera la liquidacion emitida")
        void sellarOtraVersionNoAlteraLoEmitido() {
            Liquidacion emitida = liquidarDe(E2024, E2024);
            long conjuntoAlEmitir =
                    liquidaciones.lineasDe(emitida.identificador()).get(0).conjuntoId();

            // Se sella la version 2 del mismo ejercicio, como pasaria al corregir un arancel.
            parametros.sellar(2024, 77L, 2);

            LineaDeLiquidacion linea = liquidaciones.lineasDe(emitida.identificador()).get(0);
            assertThat(linea.conjuntoId())
                    .as("la liquidacion emitida sigue apuntando al conjunto con el que se emitio")
                    .isEqualTo(conjuntoAlEmitir)
                    .isEqualTo(CONJUNTO_2024);
        }

        @Test
        @DisplayName("la valorizacion lee POR CONJUNTO, nunca «el vigente del ejercicio»")
        void laValorizacionLeePorConjunto() {
            Liquidacion emitida = liquidarDe(E2024, E2024);
            LineaDeLiquidacion linea = liquidaciones.lineasDe(emitida.identificador()).get(0);
            parametros.sellar(2024, 77L, 2);

            InsumosNormativosDeLaLiquidacion insumos =
                    new InsumosNormativosDeLaLiquidacion(parametros);
            parametros.conjuntosPedidos.clear();
            parametros.ejerciciosResueltos.clear();

            assertThatThrownBy(() -> insumos.de(linea))
                    .as("D-02a no ha entregado la UIT: falla nombrando la llave")
                    .isInstanceOf(
                            kamayuk.rentas.parametros.ParametrosSellados.ParametroAusente.class)
                    .hasMessageContaining("UIT");

            assertThat(parametros.conjuntosPedidos)
                    .as("pregunta por el conjunto que la linea fijo")
                    .containsExactly(CONJUNTO_2024);
            assertThat(parametros.ejerciciosResueltos)
                    .as("y no resuelve «el vigente de 2024», que hoy seria el 77 (ARQ-09 §3)")
                    .isEmpty();
        }

        @Test
        @DisplayName("un ejercicio sin conjunto sellado detiene la liquidacion, nombrandolo")
        void unEjercicioSinSellarDetieneLaLiquidacion() {
            assertThatThrownBy(() -> liquidarDe(new Ejercicio(2023), E2024))
                    .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class)
                    .hasMessageContaining("2023");

            assertThat(liquidaciones.versionesDeActa(actaId))
                    .as("y no deja media liquidacion escrita")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("El contraste y lo que no escribe (AC 4)")
    class ContrasteYEscrituras {

        @Test
        @DisplayName("compara la ficha que la DJ referencia contra la que el catastro tiene hoy")
        void comparaLaFichaDeclaradaContraLaVigente() {
            Liquidacion emitida = liquidarDe(E2024, E2024);
            LineaDeLiquidacion linea = liquidaciones.lineasDe(emitida.identificador()).get(0);

            assertThat(linea.areaDeclarada()).isEqualTo(AreaM2.de("120.00"));
            assertThat(linea.areaHallada()).isEqualTo(AreaM2.de("300.00"));
            assertThat(linea.condicion()).isEqualTo(CondicionFiscalizada.SUBVALUADOR);
            assertThat(linea.diferenciaDeArea()).isEqualTo(AreaM2.de("180.00"));
        }

        @Test
        @DisplayName("sin declaracion del ejercicio, OMISO")
        void sinDeclaracionOmiso() {
            Liquidacion emitida = liquidarDe(E2025, E2025);
            assertThat(liquidaciones.lineasDe(emitida.identificador()).get(0).condicion())
                    .isEqualTo(CondicionFiscalizada.OMISO);
        }

        @Test
        @DisplayName("la liquidacion sale sin un solo importe (D-02a, #198)")
        void sinUnSoloImporte() {
            Liquidacion emitida = liquidarDe(E2024, E2025);
            assertThat(liquidaciones.lineasDe(emitida.identificador()))
                    .allSatisfy(
                            linea -> {
                                assertThat(linea.baseDeclarada()).isNull();
                                assertThat(linea.baseHallada()).isNull();
                                assertThat(linea.insolutoOmitido()).isNull();
                                assertThat(linea.multaTributaria()).isNull();
                            });
        }

        @Test
        @DisplayName("los puertos de catastro y rentas son de solo lectura, salvo la transferencia")
        void losPuertosSonDeSoloLectura() {
            // AC 4 de #49: «nada de esto escribe en catastro ni en rentas». Se comprueba en el
            // TIPO: los cuatro puertos que la liquidacion usa no declaran un solo metodo que
            // escriba, asi que no hay camino desde aqui al padron ni a las declaraciones.
            assertThat(metodosDe(kamayuk.rentas.catastro.TitularesDelPredio.class))
                    .as(
                            "«estaEnElPadron» entra con #680: pregunta si el identificador apunta a"
                                    + " una fila, que es lo que separa «ese predio no existe» de"
                                    + " «existe y no lo reclama nadie». Lee, no escribe")
                    .containsExactlyInAnyOrder("de", "deVarios", "estaEnElPadron");
            assertThat(metodosDe(kamayuk.rentas.catastro.LectorDeFichas.class))
                    .containsExactlyInAnyOrder("fichaVigenteEn", "areaDeLaVersion");
            assertThat(metodosDe(kamayuk.rentas.catastro.LectorDeCaracteristicas.class))
                    .containsExactly("de");
            assertThat(metodosDe(kamayuk.rentas.nucleo.DeclaracionesDelEjercicio.class))
                    .containsExactly("dePredios");

            // Con #52 la afirmacion se afina, y esta linea es la que la afina: `catastro` publica
            // desde entonces UN puerto que escribe, con UN metodo, y es la transferencia. Que
            // siga teniendo uno solo importa: cada metodo nuevo aqui seria un camino nuevo por el
            // que lo hallado entra al padron.
            assertThat(metodosDe(kamayuk.rentas.catastro.TransferenciaDeFiscalizacion.class))
                    .as(
                            "la unica escritura de fiscalizacion hacia catastro, y es una (ARQ-01 §3.5)")
                    .containsExactly("inscribirLoHallado");

            // Y que solo la use la transferencia no se comprueba aqui —seria comprobar el
            // codigo de esta clase mirandolo—: lo comprueba
            // `SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION` sobre el bytecode de todo el
            // contexto, con dos clases de muestra que la violan.
        }

        @Test
        @DisplayName("liquidar dos veces el mismo acta se rechaza: lo que toca es reliquidar")
        void liquidarDosVecesSeRechaza() {
            liquidarDe(E2024, E2024);
            assertThatThrownBy(() -> liquidarDe(E2024, E2024))
                    .isInstanceOf(LiquidarFiscalizacion.ActaYaLiquidada.class)
                    .hasMessageContaining("reliquidar");
        }
    }

    @Nested
    @DisplayName("#599 — el uso hallado sale del acta, no de quien liquida")
    class ElUsoHalladoSaleDelActa {

        @Test
        @DisplayName("con el uso observado en el acta, la linea lo copia y la condicion lo dice")
        void laLineaCopiaElUsoDelActa() {
            long conUso =
                    actas.sembrar(
                            ActaFiscalizacion.nuevaPredial(
                                    1L,
                                    2,
                                    CONTRIBUYENTE,
                                    PREDIO,
                                    FICHA_VIGENTE,
                                    LocalDate.of(2026, 3, 1),
                                    "J. Perez",
                                    Hallazgo.USO_DISTINTO,
                                    AreaM2.de("120.00"),
                                    "COMERCIO",
                                    "vivienda convertida en bodega",
                                    OBSERVACION));
            liquidaciones.actaDe(conUso, CONTRIBUYENTE);

            Liquidacion emitida =
                    liquidar.liquidar(
                            conUso,
                            E2024,
                            E2024,
                            TipoDeFiscalizacion.CIERTA,
                            "Uso distinto al declarado",
                            HOY,
                            OBSERVACION);

            LineaDeLiquidacion linea = liquidaciones.lineasDe(emitida.identificador()).get(0);
            assertThat(linea.usoDeclarado()).isEqualTo("CASA_HABITACION");
            assertThat(linea.usoHallado())
                    .as(
                            "hasta #599 esto llegaba como argumento de liquidar: lo tecleaba quien"
                                    + " liquidaba, no quien visito")
                    .isEqualTo("COMERCIO");
            assertThat(linea.condicion()).isEqualTo(CondicionFiscalizada.USO_DISTINTO);
        }

        @Test
        @DisplayName("y sin uso en el acta la linea no se lo inventa: el area manda")
        void sinUsoEnElActaLaLineaNoSeLoInventa() {
            // El contraste que impide «arreglarlo» poniendo cualquier cosa: el acta de `armar()`
            // no consigna uso, asi que la linea sale sin el y la condicion la deciden las areas.
            Liquidacion emitida = liquidarDe(E2024, E2024);
            LineaDeLiquidacion linea = liquidaciones.lineasDe(emitida.identificador()).get(0);

            assertThat(linea.usoHallado()).isNull();
            assertThat(linea.condicion()).isEqualTo(CondicionFiscalizada.SUBVALUADOR);
        }
    }

    @Nested
    @DisplayName("La reliquidacion (AC 2) y el historico (AC 5)")
    class ReliquidacionEHistorico {

        @Test
        @DisplayName(
                "deja las dos versiones, la segunda referencia la primera, y explica el cambio")
        void dejaLasDosVersionesYExplica() {
            Liquidacion primera = liquidarDe(E2024, E2024);

            ReliquidarFiscalizacion.Resultado resultado =
                    reliquidar.reliquidar(
                            primera.numero(),
                            E2024,
                            E2024,
                            TipoDeFiscalizacion.CIERTA,
                            "Reinspeccion: el area medida era la del lote, no la construida",
                            List.of(
                                    new CorreccionDeLinea(
                                            E2024, null, AreaM2.de("180.00"), null, null)),
                            HOY,
                            OBSERVACION);

            assertThat(liquidaciones.versionesDeActa(actaId)).hasSize(2);
            assertThat(resultado.liquidacion().version()).isEqualTo(2);
            assertThat(resultado.liquidacion().liquidacionAnteriorId())
                    .isEqualTo(primera.identificador());
            assertThat(liquidaciones.lineasDe(primera.identificador()).get(0).areaHallada())
                    .as("la version anterior no cambia")
                    .isEqualTo(AreaM2.de("300.00"));
            assertThat(resultado.diferencia().cambios())
                    .anySatisfy(
                            cambio -> {
                                assertThat(cambio.concepto()).contains("area hallada");
                                assertThat(cambio.antes()).isEqualTo("300.00 m2");
                                assertThat(cambio.despues()).isEqualTo("180.00 m2");
                            });
        }

        @Test
        @DisplayName("la reliquidacion HEREDA el conjunto sellado de la linea anterior")
        void heredaElConjuntoSellado() {
            Liquidacion primera = liquidarDe(E2024, E2024);
            parametros.sellar(2024, 77L, 2);

            ReliquidarFiscalizacion.Resultado resultado =
                    reliquidar.reliquidar(
                            primera.numero(),
                            E2024,
                            E2024,
                            TipoDeFiscalizacion.CIERTA,
                            "Area corregida",
                            List.of(
                                    new CorreccionDeLinea(
                                            E2024, null, AreaM2.de("180.00"), null, null)),
                            HOY,
                            OBSERVACION);

            assertThat(
                            liquidaciones
                                    .lineasDe(resultado.liquidacion().identificador())
                                    .get(0)
                                    .conjuntoId())
                    .as(
                            "una reliquidacion corrige el contraste, no el marco normativo:"
                                    + " resolverlo otra vez mezclaria dos correcciones en una")
                    .isEqualTo(CONJUNTO_2024);
        }

        @Test
        @DisplayName("la condicion se recalcula, no se recibe")
        void laCondicionSeRecalcula() {
            Liquidacion primera = liquidarDe(E2024, E2024);

            ReliquidarFiscalizacion.Resultado resultado =
                    reliquidar.reliquidar(
                            primera.numero(),
                            E2024,
                            E2024,
                            TipoDeFiscalizacion.CIERTA,
                            "El area hallada era la declarada",
                            List.of(
                                    new CorreccionDeLinea(
                                            E2024, null, AreaM2.de("120.00"), null, null)),
                            HOY,
                            OBSERVACION);

            assertThat(
                            liquidaciones
                                    .lineasDe(resultado.liquidacion().identificador())
                                    .get(0)
                                    .condicion())
                    .as("con las dos superficies iguales ya no hay subvaluacion")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }

        @Test
        @DisplayName("reliquidar una version ya sustituida se rechaza")
        void reliquidarUnaVersionYaSustituidaSeRechaza() {
            Liquidacion primera = liquidarDe(E2024, E2024);
            reliquidar.reliquidar(
                    primera.numero(),
                    E2024,
                    E2024,
                    TipoDeFiscalizacion.CIERTA,
                    "primera correccion",
                    List.of(),
                    HOY,
                    OBSERVACION);

            assertThatThrownBy(
                            () ->
                                    reliquidar.reliquidar(
                                            primera.numero(),
                                            E2024,
                                            E2024,
                                            TipoDeFiscalizacion.CIERTA,
                                            "segunda correccion sobre la primera",
                                            List.of(),
                                            HOY,
                                            OBSERVACION))
                    .isInstanceOf(ReliquidarFiscalizacion.NoEsLaUltimaVersion.class);
        }

        @Test
        @DisplayName("el historico reconstruye el proceso completo, con su diferencia (AC 5)")
        void elHistoricoReconstruyeElProceso() {
            Liquidacion primera = liquidarDe(E2024, E2024);
            reliquidar.reliquidar(
                    primera.numero(),
                    E2024,
                    E2024,
                    TipoDeFiscalizacion.CIERTA,
                    "Area corregida",
                    List.of(new CorreccionDeLinea(E2024, null, AreaM2.de("180.00"), null, null)),
                    HOY,
                    OBSERVACION);

            List<ConsultaDeLiquidaciones.VersionDelProceso> proceso =
                    consulta.historicoDeActa(actaId);

            assertThat(proceso).hasSize(2);
            assertThat(proceso.get(0).version().liquidacion().version()).isEqualTo(1);
            assertThat(proceso.get(0).diferencia())
                    .as("la primera no tiene con que compararse")
                    .isNull();
            assertThat(proceso.get(1).diferencia()).isNotNull();
            assertThat(proceso.get(1).diferencia().cambios())
                    .anySatisfy(cambio -> assertThat(cambio.concepto()).contains("area hallada"));
            assertThat(proceso).allSatisfy(v -> assertThat(v.version().historial()).isNotEmpty());
            assertThat(proceso.get(1).version().estado()).isEqualTo(EstadoDeLiquidacion.ABIERTA);
        }

        @Test
        @DisplayName(
                "ampliar el periodo a un ejercicio que la version anterior no cubria se rechaza")
        void ampliarElPeriodoSeRechaza() {
            Liquidacion primera = liquidarDe(E2024, E2024);

            assertThatThrownBy(
                            () ->
                                    reliquidar.reliquidar(
                                            primera.numero(),
                                            E2024,
                                            E2025,
                                            TipoDeFiscalizacion.CIERTA,
                                            "ampliando",
                                            List.of(),
                                            HOY,
                                            OBSERVACION))
                    .isInstanceOf(ReliquidarFiscalizacion.EjercicioSinLineaAnterior.class)
                    .hasMessageContaining("2025");
        }
    }

    @Nested
    @DisplayName("#339 — una visita anulada no sostiene ninguna liquidacion")
    class UnActaAnuladaNoSeLiquida {

        @Test
        @DisplayName("un acta ANULADA sin liquidacion no se liquida: ActaAnulada, y nada escrito")
        void unActaAnuladaNoSeLiquida() {
            // Camino 1 del issue: nada la sostenia, asi que `exigirQueNadaLaSostenga` la dejo
            // anular. Hasta #339 liquidar solo miraba que no tuviera ya una liquidacion.
            anularLaVisita();

            assertThatThrownBy(() -> liquidarDe(E2024, E2024))
                    .as("liquidar una visita que la administracion declaro invalida")
                    .isInstanceOf(ActaFiscalizacion.ActaAnulada.class)
                    .hasMessageContaining("El acta " + actaId + " esta anulada")
                    .hasMessageContaining("levantar otra acta");

            assertThat(liquidaciones.versionesDeActa(actaId))
                    .as("y no deja ninguna liquidacion escrita")
                    .isEmpty();
        }

        @Test
        @DisplayName(
                "una liquidacion ANULADA cuya acta se anulo despues no se reliquida: ActaAnulada")
        void unaLiquidacionAnuladaConElActaAnuladaNoSeReliquida() {
            // Camino 2 del issue, en el orden que `AnularActaFiscalizacion` prescribe: primero la
            // liquidacion, despues la visita. Y despues el consejo de
            // `CambiarEstadoDeLaLiquidacion` —«corregir una anulada es reliquidar»—, que hasta
            // #339 hacia nacer una v2 ABIERTA sobre una visita muerta.
            Liquidacion primera = liquidarDe(E2024, E2024);
            anularLaLiquidacion(primera);
            anularLaVisita();

            assertThatThrownBy(() -> reliquidarSinCorregir(primera))
                    .isInstanceOf(ActaFiscalizacion.ActaAnulada.class)
                    .hasMessageContaining("El acta " + actaId + " esta anulada");

            assertThat(liquidaciones.versionesDeActa(actaId))
                    .as("no nace ninguna version 2")
                    .hasSize(1);
        }

        @Test
        @DisplayName(
                "el control: la misma acta ABIERTA se liquida, y su liquidacion anulada se"
                        + " reliquida")
        void conElActaAbiertaSeLiquidaYSeReliquida() {
            // La MISMA siembra que las dos de arriba menos la anulacion de la visita: lo que
            // separa el rojo del verde es el estado del acta y nada mas.
            Liquidacion primera = liquidarDe(E2024, E2024);
            anularLaLiquidacion(primera);

            ReliquidarFiscalizacion.Resultado resultado = reliquidarSinCorregir(primera);

            assertThat(resultado.liquidacion().version()).isEqualTo(2);
            assertThat(liquidaciones.versionesDeActa(actaId)).hasSize(2);
        }

        private void anularLaVisita() {
            new AnularActaFiscalizacion(
                            actas,
                            liquidaciones,
                            movimientos,
                            new ResolucionesEnMemoria(),
                            registro -> {})
                    .anular(actaId, HOY, OBSERVACION);
            assertThat(actas.findById(actaId).orElseThrow().estado())
                    .as("la siembra: la visita quedo anulada")
                    .isEqualTo(EstadoDeActa.ANULADA);
        }

        private void anularLaLiquidacion(Liquidacion liquidacion) {
            movimientos.insertar(
                    MovimientoDeLiquidacion.cambioDeEstado(
                            liquidacion.identificador(),
                            EstadoDeLiquidacion.ANULADA,
                            HOY,
                            "Se deja sin efecto",
                            OBSERVACION));
        }

        private ReliquidarFiscalizacion.Resultado reliquidarSinCorregir(Liquidacion anterior) {
            return reliquidar.reliquidar(
                    anterior.numero(),
                    E2024,
                    E2024,
                    TipoDeFiscalizacion.CIERTA,
                    "Se corrige la liquidacion anulada",
                    List.of(),
                    HOY,
                    OBSERVACION);
        }
    }

    @Nested
    @DisplayName("#340 — reliquidar parte de los lados que la base implica, no de sus nulos")
    class LaCorreccionParteDeLaBase {

        // La siembra que hasta #340 no existia: `laCondicionSeRecalcula` reliquida una linea
        // predial con las DOS areas presentes, que es justo la muestra en la que «presento
        // declaracion», «ubicado» y «es un vehiculo» no importan. Cada caso de aqui es una de las
        // tres entradas que la linea no guarda y que el recalculo reinventaba.

        private static final long VEHICULO = 55L;
        private static final long PREDIO_SIN_FICHA = 21L;
        private static final long FICHA_FUERA_DE_LA_PROYECCION = 999L;

        @ParameterizedTest(name = "vehiculo {0}")
        @EnumSource(
                value = Hallazgo.class,
                names = {"CONFORME", "SUBVALUADOR", "NO_UBICADO", "OMISO"})
        @DisplayName("una linea vehicular corregida sin ningun campo conserva su condicion")
        void unaLineaVehicularSinCamposConservaSuCondicion(Hallazgo hallazgo) {
            // OMISO es el control: es lo que el recalculo de antes devolvia para CUALQUIER
            // vehiculo, asi que con el sale verde antes y despues.
            long vehicular = sembrarVehicular(hallazgo);
            Liquidacion primera = liquidarActa(vehicular, E2024);
            CondicionFiscalizada antes =
                    liquidaciones.lineasDe(primera.identificador()).get(0).condicion();
            assertThat(antes)
                    .as("la siembra: la linea nace con el hallazgo del acta")
                    .isEqualTo(CondicionFiscalizada.porNombre(hallazgo.name()));

            Liquidacion segunda =
                    reliquidarCon(primera, E2024, correccion(E2024, null, null, null, null));

            assertThat(liquidaciones.lineasDe(segunda.identificador()).get(0).condicion())
                    .as(
                            "un vehiculo no tiene area ni uso: una correccion vacia no puede"
                                    + " convertirlo en OMISO")
                    .isEqualTo(antes);
        }

        @Test
        @DisplayName("una linea vehicular corregida con un area se rechaza, y no nace la v2")
        void unaLineaVehicularConAreaSeRechaza() {
            long vehicular = sembrarVehicular(Hallazgo.CONFORME);
            Liquidacion primera = liquidarActa(vehicular, E2024);

            assertThatThrownBy(
                            () ->
                                    reliquidarCon(
                                            primera,
                                            E2024,
                                            correccion(
                                                    E2024, null, AreaM2.de("150.00"), null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vehicul")
                    .hasMessageContaining("areaHallada");
            assertThat(liquidaciones.versionesDeActa(vehicular)).hasSize(1);
        }

        @Test
        @DisplayName("un predio NO_UBICADO sin DJ, corregido sin campos, sigue NO_UBICADO")
        void noUbicadoSinDeclaracionSigueNoUbicado() {
            long noUbicada = sembrarPredial(PREDIO, Hallazgo.NO_UBICADO, null);
            // 2025 no tiene DJ: la linea nace sin areas ni usos.
            Liquidacion primera = liquidarActa(noUbicada, E2025);
            LineaDeLiquidacion base = liquidaciones.lineasDe(primera.identificador()).get(0);
            assertThat(base.condicion()).isEqualTo(CondicionFiscalizada.NO_UBICADO);
            assertThat(base.areaDeclarada()).isNull();
            assertThat(base.usoDeclarado()).isNull();

            Liquidacion segunda =
                    reliquidarCon(primera, E2025, correccion(E2025, null, null, null, null));

            assertThat(liquidaciones.lineasDe(segunda.identificador()).get(0).condicion())
                    .as("nadie encontro el predio: no se le puede tratar como omiso")
                    .isEqualTo(CondicionFiscalizada.NO_UBICADO);
        }

        @Test
        @DisplayName("un predio NO_UBICADO con DJ, corregido sin campos, sigue NO_UBICADO")
        void noUbicadoConDeclaracionSigueNoUbicado() {
            long noUbicada = sembrarPredial(PREDIO, Hallazgo.NO_UBICADO, null);
            // 2024 tiene DJ con ficha y uso: la linea nace con lo declarado y nada hallado.
            Liquidacion primera = liquidarActa(noUbicada, E2024);
            LineaDeLiquidacion base = liquidaciones.lineasDe(primera.identificador()).get(0);
            assertThat(base.condicion()).isEqualTo(CondicionFiscalizada.NO_UBICADO);
            assertThat(base.areaDeclarada()).isEqualTo(AreaM2.de("120.00"));

            Liquidacion segunda =
                    reliquidarCon(primera, E2024, correccion(E2024, null, null, null, null));

            assertThat(liquidaciones.lineasDe(segunda.identificador()).get(0).condicion())
                    .as("CONFORME diria que se ubico y se midio, y no se hizo ninguna de las dos")
                    .isEqualTo(CondicionFiscalizada.NO_UBICADO);
        }

        @Test
        @DisplayName("corregir lo hallado de un predio NO_UBICADO se rechaza: es otra visita")
        void corregirLoHalladoDeUnNoUbicadoSeRechaza() {
            long noUbicada = sembrarPredial(PREDIO, Hallazgo.NO_UBICADO, null);
            Liquidacion primera = liquidarActa(noUbicada, E2024);

            assertThatThrownBy(
                            () ->
                                    reliquidarCon(
                                            primera,
                                            E2024,
                                            correccion(
                                                    E2024, null, AreaM2.de("150.00"), null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NO_UBICADO")
                    .hasMessageContaining("otra visita");
            assertThat(liquidaciones.versionesDeActa(noUbicada)).hasSize(1);
        }

        @Test
        @DisplayName(
                "quien declaro sin area ni uso resolubles, corregido con un area hallada, sigue"
                        + " CONFORME")
        void declaranteSinAreaResolubleSigueConforme() {
            // La DJ 2024 esta presentada, pero su ficha no esta en la proyeccion de catastro y el
            // predio no tiene caracteristicas: declaro y no hay nada que comparar (#166).
            rentas.con(
                    PREDIO_SIN_FICHA,
                    new DeclaracionDelEjercicio(
                            2L,
                            "DJ-0002",
                            E2024,
                            CONTRIBUYENTE,
                            LocalDate.of(2024, 2, 21),
                            false,
                            FICHA_FUERA_DE_LA_PROYECCION));
            long declarante = sembrarPredial(PREDIO_SIN_FICHA, Hallazgo.CONFORME, null);
            Liquidacion primera = liquidarActa(declarante, E2024);
            LineaDeLiquidacion base = liquidaciones.lineasDe(primera.identificador()).get(0);
            assertThat(base.condicion()).isEqualTo(CondicionFiscalizada.CONFORME);
            assertThat(base.areaDeclarada()).isNull();
            assertThat(base.usoDeclarado()).isNull();

            Liquidacion segunda =
                    reliquidarCon(
                            primera,
                            E2024,
                            correccion(E2024, null, AreaM2.de("150.00"), null, null));

            LineaDeLiquidacion corregida = liquidaciones.lineasDe(segunda.identificador()).get(0);
            assertThat(corregida.areaHallada()).isEqualTo(AreaM2.de("150.00"));
            assertThat(corregida.condicion())
                    .as(
                            "el AC 3 de #49: tratar como omiso a quien declaro produce una"
                                    + " determinacion que se anula en reclamacion")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }

        @Test
        @DisplayName("el control: un OMISO corregido con lo declarado SI se compara")
        void unOmisoCorregidoConLoDeclaradoSeCompara() {
            // La otra mitad de «presento declaracion»: lo que la corrección trae explicitamente
            // tambien cuenta. El acta de `armar()` midio 300 m2 y 2025 no tiene DJ.
            Liquidacion primera = liquidarDe(E2025, E2025);
            assertThat(liquidaciones.lineasDe(primera.identificador()).get(0).condicion())
                    .isEqualTo(CondicionFiscalizada.OMISO);

            Liquidacion segunda =
                    reliquidarCon(
                            primera,
                            E2025,
                            correccion(E2025, AreaM2.de("120.00"), null, null, null));

            assertThat(liquidaciones.lineasDe(segunda.identificador()).get(0).condicion())
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
        }

        @Test
        @DisplayName("y un OMISO corregido solo en lo hallado sigue OMISO")
        void unOmisoCorregidoEnLoHalladoSigueOmiso() {
            Liquidacion primera = liquidarDe(E2025, E2025);

            Liquidacion segunda =
                    reliquidarCon(
                            primera,
                            E2025,
                            correccion(E2025, null, AreaM2.de("180.00"), null, null));

            assertThat(liquidaciones.lineasDe(segunda.identificador()).get(0).condicion())
                    .isEqualTo(CondicionFiscalizada.OMISO);
        }

        private long sembrarVehicular(Hallazgo hallazgo) {
            long id =
                    actas.sembrar(
                            ActaFiscalizacion.nuevaVehicular(
                                    1L,
                                    1,
                                    CONTRIBUYENTE,
                                    VEHICULO,
                                    LocalDate.of(2026, 3, 1),
                                    "J. Perez",
                                    hallazgo,
                                    null,
                                    OBSERVACION));
            liquidaciones.actaDe(id, CONTRIBUYENTE);
            return id;
        }

        private long sembrarPredial(long predio, Hallazgo hallazgo, @Nullable AreaM2 areaHallada) {
            long id =
                    actas.sembrar(
                            ActaFiscalizacion.nuevaPredial(
                                    1L,
                                    2,
                                    CONTRIBUYENTE,
                                    predio,
                                    null,
                                    LocalDate.of(2026, 3, 1),
                                    "J. Perez",
                                    hallazgo,
                                    areaHallada,
                                    null,
                                    null,
                                    OBSERVACION));
            liquidaciones.actaDe(id, CONTRIBUYENTE);
            return id;
        }

        private Liquidacion liquidarActa(long id, Ejercicio ejercicio) {
            return liquidar.liquidar(
                    id,
                    ejercicio,
                    ejercicio,
                    TipoDeFiscalizacion.CIERTA,
                    "Hallazgo de la visita",
                    HOY,
                    OBSERVACION);
        }

        private Liquidacion reliquidarCon(
                Liquidacion anterior, Ejercicio ejercicio, CorreccionDeLinea correccion) {
            return reliquidar
                    .reliquidar(
                            anterior.numero(),
                            ejercicio,
                            ejercicio,
                            TipoDeFiscalizacion.CIERTA,
                            "Correccion de la prueba",
                            List.of(correccion),
                            HOY,
                            OBSERVACION)
                    .liquidacion();
        }

        private CorreccionDeLinea correccion(
                Ejercicio ejercicio,
                @Nullable AreaM2 areaDeclarada,
                @Nullable AreaM2 areaHallada,
                @Nullable String usoDeclarado,
                @Nullable String usoHallado) {
            return new CorreccionDeLinea(
                    ejercicio, areaDeclarada, areaHallada, usoDeclarado, usoHallado);
        }
    }

    // ------------------------------------------------------------------

    private Liquidacion liquidarDe(Ejercicio desde, Ejercicio hasta) {
        return liquidar.liquidar(
                actaId,
                desde,
                hasta,
                TipoDeFiscalizacion.CIERTA,
                "Ampliacion detectada en inspeccion",
                HOY,
                OBSERVACION);
    }

    private static List<String> metodosDe(Class<?> puerto) {
        return java.util.Arrays.stream(puerto.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();
    }
}
