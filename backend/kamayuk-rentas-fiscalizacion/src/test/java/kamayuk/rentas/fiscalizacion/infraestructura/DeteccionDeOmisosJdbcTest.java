package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.catastro.aplicacion.TitularesDelPredioCatastro;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Omisos y subvaluadores contra PostgreSQL de verdad, conectado como {@code sgtm_app} (#49, #545).
 *
 * <p>Antes de #545 esta detección se probaba con dobles en memoria, y por eso no podía ver su
 * defecto principal: el filtro de condición se aplicaba <b>después</b> de paginar, así que {@code
 * ?condicion=SUBVALUADOR} devolvía «cero filas, de veinticinco, en nueve páginas» y la prueba que
 * lo miraba —{@code elFiltroDeCondicionNoAlteraElTotal}— lo daba por bueno. La consulta vive ahora
 * en el motor, y aquí se mide contra él.
 *
 * <p>Se conecta como {@code sgtm_app} y no con la conexión por omisión de la base de prueba porque
 * <b>un superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}</b>: la prueba de
 * aislamiento pasaría en verde sin verificar nada (DAT-01 §0, primer hallazgo).
 *
 * <p>El caso de uso se envuelve en un proxy transaccional construido con {@link
 * AnnotationTransactionAttributeSource} —obedeciendo a la anotación, como el contenedor—:
 * envolverlo en un {@code TransactionTemplate} incondicional dejaría pasar la mutación de quitarle
 * el {@code @Transactional}, que es el modo de fallo que #486 documentó.
 */
@DisplayName("#545 — Omisos y subvaluadores, contra PostgreSQL")
class DeteccionDeOmisosJdbcTest {

    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Paginacion PRIMERA = Paginacion.de(0, 20, "codRefCatastral");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static PlatformTransactionManager gestor;
    private static JdbcClient jdbc;
    private static DeteccionDeOmisos deteccion;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250601", "Municipalidad de omisos A");
        municipalidadB = crearMunicipalidad("250602", "Municipalidad de omisos B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        gestor = new TenantTransactionManager(pool);
        jdbc = JdbcClient.create(pool);
        deteccion =
                new DeteccionDeOmisos(
                        new DeteccionRepositoryJdbc(jdbc),
                        envolver(new TitularesDelPredioCatastro(new CatastroRepositoryJdbc(jdbc))));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @BeforeEach
    void enA() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @Nested
    @DisplayName("El filtro de condicion acota el CONJUNTO, no la pagina (#545 defecto 1)")
    class ElFiltroAcotaElConjunto {

        @Test
        @DisplayName("pedir SUBVALUADOR devuelve los subvaluadores, y el total es el de ellos")
        void pedirSubvaluadorDevuelveLosSubvaluadores() {
            String sector = sembrarSector("F1");
            // Tres omisos, un conforme y un subvaluador: cinco predios en el sector.
            long titular = sembrarContribuyente("F1-T", "70600001");
            sembrarOmiso(sector, titular, "F1a");
            sembrarOmiso(sector, titular, "F1b");
            sembrarOmiso(sector, titular, "F1c");
            sembrarConforme(sector, titular, "F1d");
            sembrarSubvaluador(sector, titular, "F1e");

            Pagina<FilaDeOmisos> pagina = detectar(sector, CondicionFiscalizada.SUBVALUADOR);

            assertThat(pagina.contenido())
                    .as("el filtro entra en el WHERE, asi que la pagina trae lo pedido")
                    .hasSize(1);
            assertThat(pagina.contenido().get(0).condicion())
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
            assertThat(pagina.totalElementos())
                    .as(
                            "y el sobre cuenta lo filtrado: antes decia 5 con la pagina vacia, que"
                                    + " es indistinguible de «no hay subvaluadores»")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la segunda pagina de un filtro trae la segunda fila, no ninguna")
        void laSegundaPaginaDeUnFiltroTraeSuFila() {
            String sector = sembrarSector("F2");
            long titular = sembrarContribuyente("F2-T", "70600002");
            sembrarConforme(sector, titular, "F2a");
            sembrarOmiso(sector, titular, "F2b");
            sembrarConforme(sector, titular, "F2c");
            sembrarOmiso(sector, titular, "F2d");

            // Por `codRefCatastral`, que es el nombre que la fila PUBLICA (#546). Hasta
            // entonces esta prueba ordenaba por `predioId`, un campo que ninguna fila lleva:
            // la lista blanca lo admitia y ningun cliente podia verlo. El orden sigue siendo
            // total —cada predio sembrado tiene su codigo— y `predio_id` sigue rompiendo los
            // empates, ahora como desempate y no como campo ofrecido.
            Pagina<FilaDeOmisos> primera =
                    detectar(
                            sector,
                            CondicionFiscalizada.OMISO,
                            Paginacion.de(0, 1, "codRefCatastral"));
            Pagina<FilaDeOmisos> segunda =
                    detectar(
                            sector,
                            CondicionFiscalizada.OMISO,
                            Paginacion.de(1, 1, "codRefCatastral"));

            assertThat(primera.contenido())
                    .as("la primera pagina de un filtro trae la primera fila que lo cumple")
                    .hasSize(1);
            assertThat(segunda.contenido())
                    .as("filtrando despues de paginar, la pagina 2 salia vacia sin patron")
                    .hasSize(1);
            assertThat(segunda.totalElementos()).isEqualTo(2);
            assertThat(primera.contenido().get(0).predioId())
                    .isNotEqualTo(segunda.contenido().get(0).predioId());
        }

        @Test
        @DisplayName("sin filtro salen las tres condiciones que un cruce de gabinete produce")
        void sinFiltroSalenTodas() {
            String sector = sembrarSector("F3");
            long titular = sembrarContribuyente("F3-T", "70600003");
            sembrarOmiso(sector, titular, "F3a");
            sembrarConforme(sector, titular, "F3b");
            sembrarSubvaluador(sector, titular, "F3c");

            Pagina<FilaDeOmisos> pagina = detectar(sector, null);

            assertThat(pagina.totalElementos()).isEqualTo(3);
            assertThat(pagina.contenido())
                    .extracting(FilaDeOmisos::condicion)
                    .containsExactlyInAnyOrder(
                            CondicionFiscalizada.OMISO,
                            CondicionFiscalizada.CONFORME,
                            CondicionFiscalizada.SUBVALUADOR);
        }

        @Test
        @DisplayName("una condicion que este cruce no puede producir devuelve cero, y lo dice")
        void unaCondicionImposibleDevuelveCero() {
            String sector = sembrarSector("F4");
            long titular = sembrarContribuyente("F4-T", "70600004");
            sembrarOmiso(sector, titular, "F4a");

            Pagina<FilaDeOmisos> pagina = detectar(sector, CondicionFiscalizada.NO_UBICADO);

            assertThat(pagina.contenido()).isEmpty();
            assertThat(pagina.totalElementos())
                    .as("cero filas Y cero elementos: la respuesta no se contradice")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("La fila es el PREDIO, con sus titulares dentro (#545 defecto 3)")
    class UnaFilaPorPredio {

        @Test
        @DisplayName("un predio con dos conyuges al 50 % sale UNA vez, con los dos titulares")
        void dosConyugesUnaSolaFila() {
            String sector = sembrarSector("T1");
            long uno = sembrarContribuyente("T1-A", "70600011");
            long otro = sembrarContribuyente("T1-B", "70600012");
            long predioId = sembrarPredio(sector, "T1a");
            sembrarFicha(predioId, "300.00");
            sembrarTitularidad(predioId, uno, "50.00");
            sembrarTitularidad(predioId, otro, "50.00");

            Pagina<FilaDeOmisos> pagina = detectar(sector, null);

            assertThat(pagina.contenido())
                    .as("antes salian dos filas con el mismo codigo de referencia catastral")
                    .hasSize(1);
            assertThat(pagina.totalElementos()).isEqualTo(1);
            assertThat(pagina.contenido().get(0).titulares()).containsExactly(uno, otro);
        }

        @Test
        @DisplayName("y los titulares vienen de mayor a menor porcentaje")
        void losTitularesVienenPorPorcentaje() {
            String sector = sembrarSector("T2");
            long menor = sembrarContribuyente("T2-A", "70600013");
            long mayor = sembrarContribuyente("T2-B", "70600014");
            long predioId = sembrarPredio(sector, "T2a");
            sembrarFicha(predioId, "300.00");
            sembrarTitularidad(predioId, menor, "30.00");
            sembrarTitularidad(predioId, mayor, "70.00");

            FilaDeOmisos fila = detectar(sector, null).contenido().get(0);

            assertThat(fila.titulares()).containsExactly(mayor, menor);
            assertThat(fila.titularPrincipal())
                    .as("quien solo puede visitar a uno visita al de mayor cuota")
                    .hasValue(mayor);
        }
    }

    @Nested
    @DisplayName("El predio SIN titular entra en la deteccion (#545 defecto 4)")
    class ElPredioSinTitular {

        @Test
        @DisplayName("un predio sin titularidad vigente sale, y sale con la lista vacia")
        void unPredioSinTitularSale() {
            String sector = sembrarSector("S1");
            long predioId = sembrarPredio(sector, "S1a");
            sembrarFicha(predioId, "300.00");

            Pagina<FilaDeOmisos> pagina = detectar(sector, null);

            assertThat(pagina.contenido())
                    .as(
                            "con el JOIN interno de titularidad estos predios no entraban: 4 977 de"
                                    + " los 14 422 de Catacaos, y son los que hay que fiscalizar")
                    .hasSize(1);
            assertThat(pagina.contenido().get(0).predioId()).isEqualTo(predioId);
            assertThat(pagina.contenido().get(0).titulares()).isEmpty();
            assertThat(pagina.contenido().get(0).titularPrincipal()).isEmpty();
        }

        @Test
        @DisplayName("y uno cuya titularidad se cerro ANTES de la fecha de corte, tambien")
        void unPredioConTitularidadCerradaSale() {
            String sector = sembrarSector("S2");
            long titular = sembrarContribuyente("S2-T", "70600021");
            long predioId = sembrarPredio(sector, "S2a");
            sembrarFicha(predioId, "300.00");
            cerrarTitularidad(sembrarTitularidad(predioId, titular, "100.00"));

            Pagina<FilaDeOmisos> pagina = detectar(sector, null);

            assertThat(pagina.contenido())
                    .as("el predio cuyo titular ya no lo es tampoco puede desaparecer")
                    .hasSize(1);
            assertThat(pagina.contenido().get(0).titulares()).isEmpty();
        }
    }

    @Nested
    @DisplayName("La condicion transcrita al SQL dice lo mismo que la funcion pura")
    class LaCondicionCoincideConLaFuncionPura {

        /**
         * El contraste que #397 dejó como patrón: la expresión del {@code CASE} y {@link
         * ComparacionHalladoDeclarado} tienen que dar lo mismo. Si alguien cambia una de las dos,
         * esta prueba lo dice.
         *
         * <p><b>Los dos lados entran de la SIEMBRA, no de la fila devuelta.</b> Resolver el lado
         * declarado a partir de lo que la consulta contestó sería circular: una consulta que dijera
         * «OMISO» de quien declaró se estaría dando la razón a sí misma. Aquí cada caso declara lo
         * que se sembró —si declaró, con qué área, y cuál es el área vigente— y la función pura
         * contesta con eso.
         */
        @Test
        @DisplayName("los cinco casos del cruce de gabinete coinciden, uno por uno")
        void coincidenCasoPorCaso() {
            String sector = sembrarSector("C1");
            long titular = sembrarContribuyente("C1-T", "70600031");

            List<Sembrado> casos = new ArrayList<>();
            casos.add(
                    new Sembrado(
                            "no declaro",
                            sembrarOmiso(sector, titular, "C1a"),
                            false,
                            null,
                            AreaM2.de("300.00")));
            casos.add(
                    new Sembrado(
                            "declaro la ficha vigente",
                            sembrarConforme(sector, titular, "C1b"),
                            true,
                            AreaM2.de("300.00"),
                            AreaM2.de("300.00")));
            casos.add(
                    new Sembrado(
                            "declaro la ficha anterior, mas pequena",
                            sembrarSubvaluador(sector, titular, "C1c"),
                            true,
                            AreaM2.de("120.00"),
                            AreaM2.de("300.00")));

            // Declaro MAS area de la que el catastro tiene hoy: declaro de mas, no es hallazgo.
            long declaroDeMas = sembrarPredio(sector, "C1d");
            long fichaGrande = sembrarFicha(declaroDeMas, "500.00");
            cerrarFicha(fichaGrande);
            // Desde 2021: la version nueva empieza cuando la anterior deja de regir. Sembrarla
            // desde 2020 como la cerrada dejaba las dos cubriendo todo 2020, que es el padron
            // imposible que `ficha_vigencias_no_se_pisan` (V72, #669) rechaza.
            sembrarFichaEntre(declaroDeMas, "200.00", DESDE_LA_SIGUIENTE, null);
            sembrarTitularidad(declaroDeMas, titular, "100.00");
            sembrarDeclaracion(declaroDeMas, titular, fichaGrande, false, "PRESENTADA");
            casos.add(
                    new Sembrado(
                            "declaro de mas",
                            declaroDeMas,
                            true,
                            AreaM2.de("500.00"),
                            AreaM2.de("200.00")));

            // Declaro sin ficha que la sustente: no hay comparacion posible.
            long sinFichaDeclarada = sembrarPredio(sector, "C1e");
            sembrarFicha(sinFichaDeclarada, "300.00");
            sembrarTitularidad(sinFichaDeclarada, titular, "100.00");
            sembrarDeclaracion(sinFichaDeclarada, titular, null, false, "PRESENTADA");
            casos.add(
                    new Sembrado(
                            "declaro sin ficha que la sustente",
                            sinFichaDeclarada,
                            true,
                            null,
                            AreaM2.de("300.00")));

            List<FilaDeOmisos> filas = detectar(sector, null).contenido();
            assertThat(filas).hasSize(casos.size());

            for (Sembrado caso : casos) {
                FilaDeOmisos fila =
                        filas.stream()
                                .filter(f -> f.predioId() == caso.predioId())
                                .findFirst()
                                .orElseThrow();

                assertThat(fila.areaCatastral()).as(caso.nombre()).isEqualTo(caso.areaCatastral());
                assertThat(fila.areaDeclarada()).as(caso.nombre()).isEqualTo(caso.areaDeclarada());
                assertThat(fila.condicion())
                        .as("%s: la transcripcion SQL y la funcion pura", caso.nombre())
                        .isEqualTo(caso.condicionDelDominio());
            }

            // Y lo que cada caso significa, dicho una vez: si el dia de manana alguien cambiara
            // LAS DOS a la vez, la comparacion de arriba seguiria en verde y esto no.
            assertThat(condicionDe(sector, casos.get(0).predioId()))
                    .isEqualTo(CondicionFiscalizada.OMISO);
            assertThat(condicionDe(sector, casos.get(1).predioId()))
                    .isEqualTo(CondicionFiscalizada.CONFORME);
            assertThat(condicionDe(sector, casos.get(2).predioId()))
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
            assertThat(condicionDe(sector, declaroDeMas))
                    .as("declarar de mas no es subvaluar: eso es otro procedimiento")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
            assertThat(condicionDe(sector, sinFichaDeclarada))
                    .as("sin ficha declarada no hay superficie que contrastar")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }

        /** Un caso tal como se sembro, que es de donde sale el lado declarado. */
        private record Sembrado(
                String nombre,
                long predioId,
                boolean declaro,
                @Nullable AreaM2 areaDeclarada,
                @Nullable AreaM2 areaCatastral) {

            CondicionFiscalizada condicionDelDominio() {
                ComparacionHalladoDeclarado.LoDeclarado declarado =
                        declaro
                                ? new ComparacionHalladoDeclarado.LoDeclarado(
                                        true, false, areaDeclarada, null)
                                : ComparacionHalladoDeclarado.LoDeclarado.nada();
                return ComparacionHalladoDeclarado.condicion(
                        declarado, ComparacionHalladoDeclarado.LoHallado.de(areaCatastral, null));
            }
        }
    }

    @Nested
    @DisplayName("Lo que ya decia el AC 3 de #49, ahora en el motor")
    class ElExtemporaneoNoEsOmiso {

        @Test
        @DisplayName("quien declaro fuera de plazo NO es omiso, y la fila lo dice aparte")
        void elExtemporaneoNoEsOmiso() {
            String sector = sembrarSector("P1");
            long titular = sembrarContribuyente("P1-T", "70600041");
            long predioId = sembrarPredio(sector, "P1a");
            long ficha = sembrarFicha(predioId, "300.00");
            sembrarTitularidad(predioId, titular, "100.00");
            sembrarDeclaracion(predioId, titular, ficha, true, "PRESENTADA");

            FilaDeOmisos fila = detectar(sector, null).contenido().get(0);

            assertThat(fila.condicion())
                    .as("declarar tarde es la multa del art. 176, no una determinacion de oficio")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
            assertThat(fila.declaroFueraDePlazo()).isTrue();
        }

        @Test
        @DisplayName("una declaracion SUSTITUIDA o ANULADA no cuenta: el predio vuelve a OMISO")
        void unaDeclaracionNoVigenteNoCuenta() {
            String sector = sembrarSector("P2");
            long titular = sembrarContribuyente("P2-T", "70600042");
            long sustituida = sembrarPredio(sector, "P2a");
            long anulada = sembrarPredio(sector, "P2b");
            sembrarTitularidad(sustituida, titular, "100.00");
            sembrarTitularidad(anulada, titular, "100.00");
            sembrarDeclaracion(
                    sustituida, titular, sembrarFicha(sustituida, "300.00"), false, "SUSTITUIDA");
            sembrarDeclaracion(anulada, titular, sembrarFicha(anulada, "300.00"), false, "ANULADA");

            assertThat(condicionDe(sector, sustituida)).isEqualTo(CondicionFiscalizada.OMISO);
            assertThat(condicionDe(sector, anulada)).isEqualTo(CondicionFiscalizada.OMISO);
        }

        @Test
        @DisplayName("con dos declaraciones vigentes manda la mas reciente")
        void conDosDeclaracionesMandaLaMasReciente() {
            String sector = sembrarSector("P3");
            long titular = sembrarContribuyente("P3-T", "70600043");
            long predioId = sembrarPredio(sector, "P3a");
            long vieja = sembrarFicha(predioId, "120.00");
            cerrarFicha(vieja);
            long vigente = sembrarFichaEntre(predioId, "300.00", DESDE_LA_SIGUIENTE, null);
            sembrarTitularidad(predioId, titular, "100.00");
            // La primera declaro la ficha pequena; la rectificatoria declaro la vigente.
            sembrarDeclaracion(
                    predioId, titular, vieja, false, "PRESENTADA", LocalDate.of(2024, 2, 20));
            sembrarDeclaracion(
                    predioId, titular, vigente, false, "PRESENTADA", LocalDate.of(2024, 8, 20));

            assertThat(condicionDe(sector, predioId))
                    .as("comparar contra la vieja acusaria de subvaluacion a quien ya corrigio")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }
    }

    @Nested
    @DisplayName("Las tres paginas del filtro, recorridas enteras (#545 AC 2 y AC 3)")
    class TresPaginasRecorridasEnteras {

        /** Veintiun predios: con tamano 7 son tres paginas del padron. */
        private static final int PREDIOS = 21;

        private static final int TAMANO_DEL_PADRON = 7;

        /**
         * Donde caen los subvaluadores dentro del orden del codigo de referencia catastral.
         *
         * <p>Hay <b>en las tres paginas del padron</b> —0-6, 7-13 y 14-20—, que es lo que el AC 2
         * exige; y estan <b>agrupados</b>, que es lo que hace medible el AC 3: recorriendo el
         * resultado filtrado de tres en tres, la segunda pagina de un filtro roto sale vacia y
         * quien recorre se para ahi. Repartirlos uno de cada tres los dejaria a uno por pagina y la
         * rotura no perderia ni una fila —solo mentiria en el total—, que es la mitad del defecto.
         */
        private static final Set<Integer> SUBVALUADORES_EN = Set.of(0, 1, 2, 9, 10, 18, 19);

        @Test
        @DisplayName(
                "el total del filtro es el de los subvaluadores del PADRON, no el de la pagina")
        void elTotalEsElDelPadronYNoElDeLaPagina() {
            String sector = sembrarSector("G1");
            List<Long> subvaluadores = sembrarVeintiunPredios(sector, "G1-T", "70600071");

            Pagina<FilaDeOmisos> padron = detectar(sector, null, deTamano(TAMANO_DEL_PADRON, 0));
            assertThat(padron.totalElementos()).isEqualTo(PREDIOS);
            assertThat(padron.totalPaginas())
                    .as("el padron son tres paginas, que es lo que el AC 2 exige medir")
                    .isEqualTo(3);

            long enLaPrimeraPaginaDelPadron =
                    padron.contenido().stream()
                            .filter(fila -> fila.condicion() == CondicionFiscalizada.SUBVALUADOR)
                            .count();

            Pagina<FilaDeOmisos> filtrada =
                    detectar(
                            sector,
                            CondicionFiscalizada.SUBVALUADOR,
                            deTamano(TAMANO_DEL_PADRON, 0));

            assertThat(filtrada.totalElementos())
                    .as(
                            "recalcular el total sobre la pagina traida daria %d —los subvaluadores"
                                    + " de la primera pagina del padron— y «pagina 1 de 1» sobre un"
                                    + " padron de %d",
                            enLaPrimeraPaginaDelPadron, PREDIOS)
                    .isEqualTo(subvaluadores.size());
            assertThat(enLaPrimeraPaginaDelPadron)
                    .as("y el arreglo comodo no se distinguiria si la pagina ya trajera todos")
                    .isLessThan(subvaluadores.size());
        }

        @Test
        @DisplayName("recorrer TODAS las paginas devuelve exactamente los subvaluadores sembrados")
        void recorrerTodasLasPaginasLosDevuelveTodos() {
            String sector = sembrarSector("G2");
            List<Long> sembrados = sembrarVeintiunPredios(sector, "G2-T", "70600072");

            int tamano = 3;
            List<Long> recorridas = new ArrayList<>();
            List<Integer> vacias = new ArrayList<>();
            Pagina<FilaDeOmisos> primera =
                    detectar(sector, CondicionFiscalizada.SUBVALUADOR, deTamano(tamano, 0));
            int paginas = primera.totalPaginas();
            long total = primera.totalElementos();

            for (int numero = 0; numero < paginas; numero++) {
                Pagina<FilaDeOmisos> pagina =
                        numero == 0
                                ? primera
                                : detectar(
                                        sector,
                                        CondicionFiscalizada.SUBVALUADOR,
                                        deTamano(tamano, numero));
                if (pagina.estaVacia()) {
                    // Lo que hace cualquiera que recorre un listado: una pagina vacia es «no hay
                    // mas». Con el filtro aplicado despues de paginar, esa pagina llega en medio.
                    vacias.add(numero);
                    break;
                }
                pagina.contenido().forEach(fila -> recorridas.add(fila.predioId()));
            }

            List<Long> faltan = new ArrayList<>(sembrados);
            faltan.removeAll(recorridas);
            assertThat(faltan)
                    .as(
                            "recorriendo las %d paginas que el sobre anuncia faltan %d de los %d"
                                    + " subvaluadores sembrados; se trajeron %d filas",
                            paginas, faltan.size(), sembrados.size(), recorridas.size())
                    .isEmpty();
            assertThat(recorridas)
                    .as("una fila repetida es un predio contado dos veces en la muestra")
                    .doesNotHaveDuplicates();
            assertThat(vacias)
                    .as("quien pasa a la pagina 2 encontraba «otras cero, o algunas, sin patron»")
                    .isEmpty();
            assertThat(total).isEqualTo(sembrados.size());
            assertThat(paginas)
                    .as("siete subvaluadores de tres en tres son tres paginas")
                    .isEqualTo(3);
            assertThat(recorridas).containsExactlyInAnyOrderElementsOf(sembrados);
        }

        private Paginacion deTamano(int tamano, int pagina) {
            return Paginacion.de(pagina, tamano, "codRefCatastral");
        }

        /**
         * Veintiun predios en el sector y los identificadores de los siete subvaluadores. El orden
         * de siembra es el del codigo de referencia catastral, asi que {@link #SUBVALUADORES_EN}
         * dice exactamente donde cae cada uno al paginar.
         */
        private List<Long> sembrarVeintiunPredios(String sector, String codigo, String dni) {
            long titular = sembrarContribuyente(codigo, dni);
            List<Long> subvaluadores = new ArrayList<>();
            for (int i = 0; i < PREDIOS; i++) {
                String sufijo = sector + "-" + i;
                if (SUBVALUADORES_EN.contains(i)) {
                    subvaluadores.add(sembrarSubvaluador(sector, titular, sufijo));
                } else if (i % 2 == 0) {
                    sembrarOmiso(sector, titular, sufijo);
                } else {
                    sembrarConforme(sector, titular, sufijo);
                }
            }
            return List.copyOf(subvaluadores);
        }
    }

    @Nested
    @DisplayName("La fecha de corte sale del parametro, no del reloj (#545 AC 7, regla 9)")
    class LaFechaDeCorteManda {

        @Test
        @DisplayName("dos fechas sobre el MISMO padron dan dos respuestas distintas")
        void dosFechasDosRespuestas() {
            String sector = sembrarSector("D1");
            long antiguo = sembrarContribuyente("D1-A", "70600081");
            long nuevo = sembrarContribuyente("D1-B", "70600082");
            long predioId = sembrarPredio(sector, "D1a");

            // La ficha de 120 m2 rige hasta 2024; desde 2025 rige una de 300 m2.
            long declarada =
                    sembrarFichaEntre(
                            predioId,
                            "120.00",
                            LocalDate.of(2020, 1, 1),
                            LocalDate.of(2024, 12, 31));
            sembrarFichaEntre(predioId, "300.00", LocalDate.of(2025, 1, 1), null);
            // Y el predio cambio de dueno el mismo dia.
            sembrarTitularidadEntre(
                    predioId, antiguo, LocalDate.of(2020, 1, 1), LocalDate.of(2024, 12, 31));
            sembrarTitularidadEntre(predioId, nuevo, LocalDate.of(2025, 1, 1), null);
            sembrarDeclaracion(predioId, antiguo, declarada, false, "PRESENTADA");

            FilaDeOmisos en2024 = unicaFila(sector, LocalDate.of(2024, 6, 30));
            FilaDeOmisos hoy = unicaFila(sector, HOY);

            assertThat(en2024.condicion())
                    .as("en 2024 declaro exactamente la ficha que regia")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
            assertThat(en2024.areaCatastral()).isEqualTo(AreaM2.de("120.00"));
            assertThat(en2024.titulares()).containsExactly(antiguo);

            assertThat(hoy.condicion())
                    .as("hoy rige una ficha mayor que la declarada: eso es subvaluar")
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
            assertThat(hoy.areaCatastral()).isEqualTo(AreaM2.de("300.00"));
            assertThat(hoy.titulares())
                    .as("resolver con el reloj devolveria al comprador de 2025 (#24, #366)")
                    .containsExactly(nuevo);
        }

        private FilaDeOmisos unicaFila(String sector, LocalDate aLaFecha) {
            Pagina<FilaDeOmisos> pagina = detectarA(sector, aLaFecha);
            assertThat(pagina.contenido()).hasSize(1);
            return pagina.contenido().get(0);
        }
    }

    @Nested
    @DisplayName("P5C / AC 2 — la pagina y el conteo son del MISMO conjunto filtrado")
    class LaPaginaYElConteoSonDelMismoConjunto {

        /**
         * El caso de #631, reproducido sobre la proyeccion local (P5C).
         *
         * <p><b>Que fallo entonces y por que.</b> La conciliacion componia el resultado en memoria:
         * pedia a catastro la pagina del padron y descartaba despues las filas que no cumplian el
         * predicado —que lo conoce {@code rentas} y no el vecino—. El sobre seguia llevando el
         * {@code totalElementos} del padron ENTERO, asi que sobre Catacaos contestaba «722 paginas,
         * 14 422 elementos» y <b>cero filas en todas</b>: una respuesta que afirma dos cosas
         * incompatibles y no se puede recorrer.
         *
         * <p><b>Por que no puede volver a pasar.</b> Porque el predicado esta en el MISMO {@code
         * WHERE} que la pagina y el {@code count(*)}: {@code PAGINA} y {@code CONTEO_CON_CONDICION}
         * se componen del mismo texto ({@code INTERIOR} + {@code FILTRO_DE_CONDICION}), asi que no
         * pueden decir cosas distintas. Es lo que la proyeccion local compra, y lo que un puerto
         * HTTP no puede dar.
         */
        @Test
        @DisplayName("las dos cifras cuadran, y recorrer las paginas devuelve exactamente esas")
        void elSobreYLasFilasDicenLoMismo() {
            String sector = sembrarSector("P5C");
            long titular = sembrarContribuyente("P5C-T", "70600090");

            // Siete predios, de los que SOLO TRES son subvaluadores. Los otros cuatro declararon
            // exactamente lo que consta, que es el caso corriente del padron.
            for (int i = 0; i < 3; i++) {
                long predio = sembrarPredio(sector, "P5Csub" + i);
                // La version que DECLARO, ya cerrada, y la que rige hoy: 300 contra 120 es
                // exactamente lo que `ComparacionHalladoDeclarado` llama subvaluar. Las dos
                // vigencias no se pisan —`ficha_vigencias_no_se_pisan`, V72— y ese detalle
                // importa aqui: un padron con dos versiones abiertas no se puede escribir.
                long declarada =
                        sembrarFichaEntre(
                                predio,
                                "120.00",
                                LocalDate.of(2019, 1, 1),
                                LocalDate.of(2019, 12, 31));
                sembrarFicha(predio, "300.00");
                sembrarTitularidad(predio, titular, "100.00");
                sembrarDeclaracion(predio, titular, declarada, false, "PRESENTADA");
            }
            for (int i = 0; i < 4; i++) {
                long predio = sembrarPredio(sector, "P5Ccon" + i);
                long ficha = sembrarFicha(predio, "150.00");
                sembrarTitularidad(predio, titular, "100.00");
                sembrarDeclaracion(predio, titular, ficha, false, "PRESENTADA");
            }

            Pagina<FilaDeOmisos> primera =
                    detectar(
                            sector,
                            CondicionFiscalizada.SUBVALUADOR,
                            Paginacion.de(0, 2, "codRefCatastral"));

            assertThat(primera.totalElementos())
                    .as(
                            "el sobre cuenta LO FILTRADO. En #631 contaba el padron entero: «722"
                                    + " paginas, 14 422 elementos» sobre cero filas")
                    .isEqualTo(3);

            // Y recorrerlas de verdad, que es lo que #631 no podia: la respuesta anunciaba
            // paginas que no existian.
            List<String> recorridos = new java.util.ArrayList<>();
            for (int pagina = 0; pagina < primera.totalPaginas(); pagina++) {
                for (FilaDeOmisos fila :
                        detectar(
                                        sector,
                                        CondicionFiscalizada.SUBVALUADOR,
                                        Paginacion.de(pagina, 2, "codRefCatastral"))
                                .contenido()) {
                    recorridos.add(fila.codigoReferenciaCatastral());
                }
            }
            assertThat(recorridos)
                    .as(
                            "recorriendo las %d paginas que el sobre anuncia salen las %d filas que dice",
                            primera.totalPaginas(), primera.totalElementos())
                    .hasSize(3)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("y una condicion sin ninguna fila dice cero, no el padron")
        void laCondicionImposibleDiceCero() {
            String sector = sembrarSector("P5Cv");
            long titular = sembrarContribuyente("P5Cv-T", "70600091");
            long predio = sembrarPredio(sector, "P5Cv0");
            long ficha = sembrarFicha(predio, "150.00");
            sembrarTitularidad(predio, titular, "100.00");
            sembrarDeclaracion(predio, titular, ficha, false, "PRESENTADA");

            Pagina<FilaDeOmisos> vacia =
                    detectar(
                            sector,
                            CondicionFiscalizada.OMISO,
                            Paginacion.de(0, 20, "codRefCatastral"));

            assertThat(vacia.totalElementos())
                    .as("cero es una respuesta; «722 paginas de nada» no lo es")
                    .isZero();
            assertThat(vacia.totalPaginas()).isZero();
            assertThat(vacia.contenido()).isEmpty();
        }
    }

    @Nested
    @DisplayName("El sector, la baja y el aislamiento")
    class SectorBajaYAislamiento {

        @Test
        @DisplayName("el filtro de sector acota, y su total es el del sector")
        void elFiltroDeSectorAcota() {
            String uno = sembrarSector("X1");
            String otro = sembrarSector("X2");
            long titular = sembrarContribuyente("X-T", "70600051");
            sembrarOmiso(uno, titular, "X1a");
            sembrarOmiso(otro, titular, "X2a");
            sembrarOmiso(otro, titular, "X2b");

            assertThat(detectar(uno, null).totalElementos()).isEqualTo(1);
            assertThat(detectar(otro, null).totalElementos()).isEqualTo(2);
        }

        @Test
        @DisplayName("un predio dado de baja no se detecta: no hay nada que fiscalizar")
        void unPredioDadoDeBajaNoSeDetecta() {
            String sector = sembrarSector("X3");
            long titular = sembrarContribuyente("X3-T", "70600052");
            long predioId = sembrarOmiso(sector, titular, "X3a");
            darDeBaja(predioId);

            assertThat(detectar(sector, null).contenido()).isEmpty();
        }

        @Test
        @DisplayName("la prueba se conecta como sgtm_app, no como superusuario")
        void seConectaComoSgtmApp() {
            assertThat(usuarioDelPool())
                    .as(
                            "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                    + " todo lo de este archivo pasaria sin verificar nada (DAT-01 §0)."
                                    + " Con sgtm_owner NO basta: FORCE lo sujeta a la politica igual,"
                                    + " asi que la rotura clasica escrita con el dueño sale VERDE")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }

        @Test
        @DisplayName("los predios de B no se detectan desde A")
        void losPrediosDeBNoSeDetectanDesdeA() {
            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            String sector = sembrarSector("Z1", municipalidadB);
            long titular = sembrarContribuyente("Z1-T", "70600061", municipalidadB);
            sembrarOmiso(sector, titular, "Z1a", municipalidadB);
            TenantContext.limpiar();

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            assertThat(detectar(sector, null).contenido())
                    .as("fuga de filas de la municipalidad B hacia A")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Hace correr al ingestor antes de preguntar (P5C).
     *
     * <p>Desde P5C la deteccion lee la <b>proyeccion local</b> de catastro y no sus tablas: la
     * consulta que paginaba y contaba cruzando cuatro tablas del vecino ya no puede existir con dos
     * bases (ADR-0029). Estas pruebas siguen sembrando predios y fichas como siempre, y esta
     * llamada es el paso que en produccion dispara un evento.
     *
     * <p>Va justo antes de preguntar, y no al final de cada siembra, a proposito: asi el desfase de
     * la proyeccion es <b>visible</b> en el codigo de la prueba. Una siembra que proyectara sola
     * dejaria creer que la proyeccion se actualiza en la misma transaccion, que es exactamente lo
     * que no pasa.
     */
    private static Pagina<FilaDeOmisos> detectarA(@Nullable String sector, LocalDate aLaFecha) {
        ingestar(municipalidadA);
        return envolver(deteccion).detectar(E2024, sector, null, aLaFecha, PRIMERA);
    }

    private static void ingestar(long municipalidadId) {
        try {
            kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, municipalidadId);
        } catch (java.sql.SQLException noSePudo) {
            throw new IllegalStateException("No se pudo proyectar el catastro", noSePudo);
        }
    }

    private static Pagina<FilaDeOmisos> detectar(
            @Nullable String sector, @Nullable CondicionFiscalizada condicion) {
        return detectar(sector, condicion, PRIMERA);
    }

    private static Pagina<FilaDeOmisos> detectar(
            @Nullable String sector,
            @Nullable CondicionFiscalizada condicion,
            Paginacion paginacion) {
        ingestar(municipalidadA);
        return envolver(deteccion).detectar(E2024, sector, condicion, HOY, paginacion);
    }

    /** Con que rol habla el pool que la deteccion usa. Ver {@code seConectaComoSgtmApp}. */
    private static String usuarioDelPool() {
        return jdbc.sql("SELECT current_user").query(String.class).single();
    }

    private static CondicionFiscalizada condicionDe(String sector, long predioId) {
        Optional<FilaDeOmisos> fila =
                detectar(sector, null).contenido().stream()
                        .filter(f -> f.predioId() == predioId)
                        .findFirst();
        return fila.orElseThrow(
                        () -> new AssertionError("el predio " + predioId + " no se detecto"))
                .condicion();
    }

    /** Envuelve el objetivo en un proxy transaccional que OBEDECE a la anotacion. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Siembra ----------

    private static long sembrarOmiso(String sector, long titular, String sufijo) {
        return sembrarOmiso(sector, titular, sufijo, municipalidadA);
    }

    private static long sembrarOmiso(
            String sector, long titular, String sufijo, long municipalidadId) {
        long predioId = sembrarPredio(sector, sufijo, municipalidadId);
        sembrarFicha(predioId, "300.00", municipalidadId);
        sembrarTitularidad(predioId, titular, "100.00", municipalidadId);
        return predioId;
    }

    private static long sembrarConforme(String sector, long titular, String sufijo) {
        long predioId = sembrarPredio(sector, sufijo, municipalidadA);
        long ficha = sembrarFicha(predioId, "300.00", municipalidadA);
        sembrarTitularidad(predioId, titular, "100.00", municipalidadA);
        sembrarDeclaracion(predioId, titular, ficha, false, "PRESENTADA");
        return predioId;
    }

    private static long sembrarSubvaluador(String sector, long titular, String sufijo) {
        long predioId = sembrarPredio(sector, sufijo, municipalidadA);
        long declarada = sembrarFicha(predioId, "120.00", municipalidadA);
        cerrarFicha(declarada);
        sembrarFichaEntre(predioId, "300.00", DESDE_LA_SIGUIENTE, null);
        sembrarTitularidad(predioId, titular, "100.00", municipalidadA);
        sembrarDeclaracion(predioId, titular, declarada, false, "PRESENTADA");
        return predioId;
    }

    private static String sembrarSector(String codigo) {
        return sembrarSector(codigo, municipalidadA);
    }

    private static String sembrarSector(String codigo, long municipalidadId) {
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO sector (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidadId,
                codigo);
        return codigo;
    }

    private static long sembrarPredio(String sectorCodigo, String sufijo) {
        return sembrarPredio(sectorCodigo, sufijo, municipalidadA);
    }

    private static long sembrarPredio(String sectorCodigo, String sufijo, long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector WHERE municipalidad_id = ? AND codigo = ?))"
                        + " RETURNING id",
                municipalidadId,
                codigoCatastralDe(sufijo),
                municipalidadId,
                sectorCodigo);
    }

    private static long sembrarFicha(long predioId, String area) {
        return sembrarFicha(predioId, area, municipalidadA);
    }

    private static long sembrarFicha(long predioId, String area, long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, ?, 'CASA_HABITACION', DATE '2020-01-01',"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement(),
                new java.math.BigDecimal(area));
    }

    /**
     * Una version de ficha con su vigencia declarada, para las pruebas que preguntan a dos fechas
     * (AC 7). {@link #sembrarFicha} deja la vigencia abierta desde 2020, que es lo que basta cuando
     * todas las preguntas son a la misma fecha.
     */
    private static long sembrarFichaEntre(
            long predioId, String area, LocalDate desde, @Nullable LocalDate hasta) {
        return ejecutarComoApp(
                municipalidadA,
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, vigencia_hasta, origen,"
                        + " documento_origen, observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, ?, 'CASA_HABITACION', ?, ?,"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidadA,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement(),
                new java.math.BigDecimal(area),
                desde,
                hasta);
    }

    /** El dia en que empieza la version que sigue a una cerrada por {@link #cerrarFicha}. */
    private static final LocalDate DESDE_LA_SIGUIENTE = LocalDate.of(2021, 1, 1);

    private static void cerrarFicha(long fichaId) {
        conElOwner(
                municipalidadA,
                "UPDATE ficha_catastral SET vigencia_hasta = DATE '2020-12-31' WHERE id = ?",
                fichaId);
    }

    private static long sembrarTitularidad(long predioId, long contribuyenteId, String porcentaje) {
        return sembrarTitularidad(predioId, contribuyenteId, porcentaje, municipalidadA);
    }

    private static long sembrarTitularidad(
            long predioId, long contribuyenteId, String porcentaje, long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, 'COPROPIETARIO', ?, DATE '2020-01-01', 'DOC-PRUEBA')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                contribuyenteId,
                new java.math.BigDecimal(porcentaje));
    }

    /**
     * Una titularidad con su vigencia declarada. La cerrada se inserta <b>ya cerrada</b>: el
     * disparador diferido suma solo las que tienen {@code vigencia_hasta IS NULL}, asi que dejar
     * dos abiertas al 100 % la haria saltar al confirmar.
     */
    private static long sembrarTitularidadEntre(
            long predioId, long contribuyenteId, LocalDate desde, @Nullable LocalDate hasta) {
        return ejecutarComoApp(
                municipalidadA,
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, vigencia_hasta, documento_origen)"
                        + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', 100.00, ?, ?, 'DOC-PRUEBA')"
                        + " RETURNING id",
                municipalidadA,
                predioId,
                contribuyenteId,
                desde,
                hasta);
    }

    private static void cerrarTitularidad(long titularidadId) {
        conElOwner(
                municipalidadA,
                "UPDATE titularidad SET vigencia_hasta = DATE '2021-12-31' WHERE id = ?",
                titularidadId);
    }

    private static void darDeBaja(long predioId) {
        conElOwner(
                municipalidadA, "UPDATE predio SET estado = 'DADO_DE_BAJA' WHERE id = ?", predioId);
    }

    private static void sembrarDeclaracion(
            long predioId,
            long contribuyenteId,
            @Nullable Long fichaId,
            boolean fueraDePlazo,
            String estado) {
        sembrarDeclaracion(
                predioId,
                contribuyenteId,
                fichaId,
                fueraDePlazo,
                estado,
                LocalDate.of(2024, 2, 20));
    }

    private static void sembrarDeclaracion(
            long predioId,
            long contribuyenteId,
            @Nullable Long fichaId,
            boolean fueraDePlazo,
            String estado,
            LocalDate presentacion) {
        ejecutarComoApp(
                municipalidadA,
                "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                        + " contribuyente_id, tipo, predio_id, ficha_catastral_id,"
                        + " fecha_presentacion, fecha_limite, fuera_de_plazo, estado,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, ?, 2024, ?, 'PU', ?, ?, ?, DATE '2024-02-28', ?, ?,"
                        + " 'siembra', 'Siembra de la prueba') RETURNING id",
                municipalidadA,
                "DJ-" + SIGUIENTE_DJ.getAndIncrement(),
                contribuyenteId,
                predioId,
                fichaId,
                presentacion,
                fueraDePlazo,
                estado);
    }

    private static long sembrarContribuyente(String codigo, String dni) {
        return sembrarContribuyente(codigo, dni, municipalidadA);
    }

    private static long sembrarContribuyente(String codigo, String dni, long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona,"
                        + " nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    // ---------- Fontaneria ----------

    private static final AtomicInteger SIGUIENTE_CATASTRAL = new AtomicInteger(600000);
    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);
    private static final AtomicInteger SIGUIENTE_DJ = new AtomicInteger(1);
    private static final java.util.concurrent.ConcurrentHashMap<String, String> CODIGOS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Codigo catastral de relleno: el dominio {@code cod_catastral} exige 18-25 digitos. */
    private static String codigoCatastralDe(String sufijo) {
        return CODIGOS.computeIfAbsent(
                sufijo, s -> String.format("%018d", SIGUIENTE_CATASTRAL.getAndIncrement()));
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

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /**
     * Lo escribe el owner: {@code sgtm_app} no tiene {@code UPDATE} sobre estas tablas —cerrar una
     * ficha o una titularidad es un acto del dominio, no de la siembra de una prueba—.
     */
    private static void conElOwner(long municipalidadId, String sql, Object... valores) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidadId);
            try (PreparedStatement sentencia = owner.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                sentencia.executeUpdate();
                owner.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
