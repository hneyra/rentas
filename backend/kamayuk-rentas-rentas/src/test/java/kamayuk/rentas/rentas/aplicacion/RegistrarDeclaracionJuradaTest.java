package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.rentas.dominio.PlantillaDeNumeroDeDeclaracion;
import kamayuk.rentas.rentas.dominio.TipoDeDeclaracion;
import kamayuk.rentas.rentas.infraestructura.DeclaracionJuradaRepositoryJdbc;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los cuatro actos de la declaracion jurada contra PostgreSQL real (RF-023, #28, #365).
 *
 * <p>Lo que da valor a esta prueba y ningun doble puede dar:
 *
 * <ul>
 *   <li><b>El numero lo pone el sistema.</b> El correlativo sale de {@code dj_correlativo} con un
 *       UPSERT atomico, y diez hilos que presentan a la vez tienen que producir diez numeros
 *       distintos. La carrera se mide <b>tambien sobre el ordinal aislado</b>, que es la leccion de
 *       #44: con el caso de uso entero, quien serializa podria ser otro indice y el ordinal seguir
 *       roto sin que nada se ponga rojo.
 *   <li><b>El arranque del correlativo sobre lo historico.</b> La fila de {@code dj_correlativo} no
 *       la siembra la migracion —el migrador no tiene contexto de tenant y {@code
 *       declaracion_jurada} tiene RLS con FORCE—: la crea la primera peticion, por encima del mayor
 *       numero del ejercicio.
 *   <li>{@code fechaLimite} sale de un conjunto <b>sellado</b> de verdad, y un ejercicio sellado
 *       sin el parametro falla <b>nombrando la llave</b> {@code PLAZO:DECLARACION_JURADA} (regla
 *       5).
 *   <li><b>La maquina de estados, entera</b>, y con las dos capas que la sostienen: el dominio, que
 *       produce el mensaje, y el disparador de V54, que es lo unico que ven dos peticiones
 *       simultaneas.
 *   <li><b>Lo que de una DJ presentada se puede cambiar.</b> Desde V54 {@code sgtm_app} solo tiene
 *       {@code UPDATE} sobre {@code estado}: se comprueba con SQL directo, que es la unica forma de
 *       comprobar un privilegio.
 * </ul>
 */
@DisplayName("RF-023 — Presentar, rectificar, observar y anular declaraciones juradas")
class RegistrarDeclaracionJuradaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate PLAZO = LocalDate.of(2026, 6, 30);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long vecina;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static DeclaracionJuradaRepositoryJdbc repositorio;
    private static RegistrarDeclaracionJurada registrar;

    /** Para que los codigos de contribuyente y de predio de cada prueba no se pisen. */
    private static int siguiente = 1;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("270101", "Municipalidad de la declaracion jurada");
        vecina = crearMunicipalidad("270102", "Municipalidad vecina");
        sellarPlazo(EJERCICIO, PLAZO);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new DeclaracionJuradaRepositoryJdbc(jdbc);

        LectorDeParametros parametros =
                envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)));
        registrar =
                envolver(
                        new RegistrarDeclaracionJurada(
                                repositorio,
                                PlantillaDeNumeroDeDeclaracion.POR_OMISION,
                                parametros,
                                new FichaFija(),
                                new PadronDePrueba(),
                                new AuditoriaJdbc(jdbc, RELOJ)));
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

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Presentacion")
    class Presentacion {

        @Test
        @DisplayName("una DJ dentro del plazo se guarda sin marca de fuera de plazo")
        void unaDjDentroDelPlazoSeGuarda() throws SQLException {
            String codigo = nuevoContribuyente();

            DeclaracionJurada guardada =
                    registrar.registrar(
                            EJERCICIO,
                            codigo,
                            TipoDeDeclaracion.HR,
                            null,
                            null,
                            LocalDate.of(2026, 3, 1),
                            Observacion.de("Primera inscripcion del predio"));

            assertThat(guardada.id()).isNotNull();
            assertThat(guardada.fechaLimite()).isEqualTo(PLAZO);
            assertThat(guardada.fueraDePlazo()).isFalse();

            // porNumero es lo que usa DeclaracionJuradaController: el contrato de djNro (RF-023).
            DeclaracionJurada porNumero =
                    transaccion.execute(
                            estado ->
                                    repositorio
                                            .porNumero(guardada.numero(), EJERCICIO)
                                            .orElseThrow());
            assertThat(porNumero).isNotNull();
            assertThat(porNumero.id()).isEqualTo(guardada.id());

            assertThat(
                            filas(
                                    "SELECT count(*) FROM auditoria WHERE tabla ="
                                            + " 'declaracion_jurada' AND operacion = 'ALTA' AND"
                                            + " observacion LIKE '%inscripcion del predio%'"))
                    .isPositive();
        }

        @Test
        @DisplayName("el numero lo pone el sistema: el llamador no lo propone ni lo puede proponer")
        void elNumeroLoPoneElSistema() throws SQLException {
            DeclaracionJurada guardada = presentar(LocalDate.of(2026, 3, 1));

            assertThat(guardada.numero())
                    .as(
                            "la plantilla de D-09 es DJ-{ejercicio}-{correlativo:6}; el numero"
                                    + " externo de mesa de partes, si lo hay, es otra cosa")
                    .matches("DJ-2026-\\d{6}");
        }

        @Test
        @DisplayName("una DJ presentada despues del plazo parametrizado se marca fuera de plazo")
        void unaDjFueraDePlazoSeMarca() throws SQLException {
            DeclaracionJurada guardada = presentar(LocalDate.of(2026, 7, 15));

            assertThat(guardada.fueraDePlazo())
                    .as("15 de julio es posterior al 30 de junio parametrizado")
                    .isTrue();

            Boolean marcada =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT fuera_de_plazo FROM declaracion_jurada"
                                                            + " WHERE id = :id")
                                            .param("id", guardada.id())
                                            .query(Boolean.class)
                                            .single());
            assertThat(marcada)
                    .as("la marca tambien queda en la fila, no solo en memoria")
                    .isTrue();
        }

        @Test
        @DisplayName(
                "el predio con ficha vigente enlaza el fichaCatastralId que devuelve LectorDeFichas")
        void elPredioConFichaEnlazaLaFicha() throws SQLException {
            String codigo = nuevoContribuyente();
            long predio = crearPredio(nuevoCodigoCatastral());
            long ficha = crearFichaCatastral(predio);

            DeclaracionJurada guardada =
                    registrar.registrar(
                            EJERCICIO,
                            codigo,
                            TipoDeDeclaracion.PR,
                            predio,
                            null,
                            LocalDate.of(2026, 3, 1),
                            Observacion.de("Predial con ficha ya registrada"));

            assertThat(guardada.fichaCatastralId()).isEqualTo(ficha);
        }

        @Test
        @DisplayName("un predio sin ficha vigente guarda la DJ sin ficha enlazada")
        void unPredioSinFichaNoEnlazaNada() throws SQLException {
            String codigo = nuevoContribuyente();
            long predio = crearPredio(nuevoCodigoCatastral());

            DeclaracionJurada guardada =
                    registrar.registrar(
                            EJERCICIO,
                            codigo,
                            TipoDeDeclaracion.PR,
                            predio,
                            null,
                            LocalDate.of(2026, 3, 1),
                            Observacion.de("Predio sin ficha catastral registrada todavia"));

            assertThat(guardada.fichaCatastralId()).isNull();
        }

        @Test
        @DisplayName("un codigo de contribuyente que no esta en el padron no registra nada")
        void unContribuyenteInexistenteNoRegistra() {
            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            EJERCICIO,
                                            "NO-EXISTE",
                                            TipoDeDeclaracion.HR,
                                            null,
                                            null,
                                            LocalDate.of(2026, 3, 1),
                                            Observacion.de("No deberia llegar a escribirse")))
                    .isInstanceOf(RegistrarDeclaracionJurada.ContribuyenteInexistente.class);
        }

        @Test
        @DisplayName("un ejercicio sin ningun conjunto sellado falla al resolver el plazo")
        void sinConjuntoSelladoFalla() throws SQLException {
            String codigo = nuevoContribuyente();

            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            new Ejercicio(2031),
                                            codigo,
                                            TipoDeDeclaracion.HR,
                                            null,
                                            null,
                                            LocalDate.of(2031, 3, 1),
                                            Observacion.de(
                                                    "El ejercicio 2031 no tiene ningun conjunto"
                                                            + " sellado")))
                    .as(
                            "calcular con un conjunto abierto daria una cifra que manana puede ser otra")
                    .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class);
        }

        @Test
        @DisplayName(
                "un ejercicio sellado sin el parametro de plazo falla nombrando la llave"
                        + " PLAZO:DECLARACION_JURADA")
        void selladoSinElParametroDePlazoFalla() throws SQLException {
            String codigo = nuevoContribuyente();
            sellarSinPlazo(new Ejercicio(2032));

            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            new Ejercicio(2032),
                                            codigo,
                                            TipoDeDeclaracion.HR,
                                            null,
                                            null,
                                            LocalDate.of(2032, 3, 1),
                                            Observacion.de(
                                                    "El ejercicio 2032 esta sellado, pero sin el"
                                                            + " plazo de DJ")))
                    .as(
                            "sin plazo no hay con que comparar: inventar uno clasificaria mal cada"
                                    + " DJ que se registre (regla 5)")
                    .isInstanceOf(RegistrarDeclaracionJurada.PlazoSinParametrizar.class)
                    .hasMessageContaining("PLAZO:DECLARACION_JURADA");
        }
    }

    @Nested
    @DisplayName("La numeracion (#365, decision 1)")
    class Numeracion {

        @Test
        @DisplayName("el correlativo del ejercicio avanza de uno en uno")
        void elCorrelativoAvanza() {
            long primero = correlativoDe(new Ejercicio(2040));
            long segundo = correlativoDe(new Ejercicio(2040));

            assertThat(segundo - primero).isEqualTo(1);
        }

        @Test
        @DisplayName("cada ejercicio tiene su serie: la de 2041 no continua la de 2040")
        void cadaEjercicioTieneSuSerie() {
            correlativoDe(new Ejercicio(2040));
            correlativoDe(new Ejercicio(2040));

            assertThat(correlativoDe(new Ejercicio(2041)))
                    .as("la serie se reinicia con el año, y por eso la plantilla exige {ejercicio}")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(
                "la primera peticion de un ejercicio arranca POR ENCIMA del mayor numero historico")
        void arrancaPorEncimaDeLoHistorico() throws SQLException {
            // Exactamente lo que deja una migracion de D-04: filas ya numeradas, y ninguna fila en
            // dj_correlativo. Sembrar el contador en la migracion es imposible -el migrador no
            // tiene contexto de tenant y declaracion_jurada tiene RLS con FORCE-, asi que el
            // arranque tiene que resolverlo la primera peticion, que si lo tiene.
            Ejercicio heredado = new Ejercicio(2042);
            sembrarDeclaracionHistorica("DJ-2042-000042", heredado);
            sembrarDeclaracionHistorica("000017", heredado);

            assertThat(correlativoDe(heredado))
                    .as(
                            "con el arranque en 1, la primera DJ del ejercicio compondria"
                                    + " DJ-2042-000001 y chocaria con la historica en cuanto"
                                    + " llegara a la 42")
                    .isEqualTo(43);
        }

        @Test
        @DisplayName("el correlativo de la municipalidad vecina no ve el de esta")
        void elCorrelativoNoCruzaLaFrontera() throws SQLException {
            Ejercicio compartido = new Ejercicio(2043);
            sembrarDeclaracionHistorica("DJ-2043-000900", compartido);
            assertThat(correlativoDe(compartido)).isEqualTo(901);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(vecina));

            assertThat(correlativoDe(compartido))
                    .as(
                            "la subconsulta del arranque corre bajo RLS: la vecina no ve las 900"
                                    + " declaraciones de al lado")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("diez hilos pidiendo el ordinal a la vez sacan diez ordinales DISTINTOS")
        void diezHilosSacanDiezOrdinales() throws Exception {
            // La leccion de #44 y #52: se mide EL ORDINAL, aislado. Con el caso de uso entero,
            // quien serializa podria ser dj_numero_uq -que rechaza el numero repetido- y el
            // contador seguir roto sin que nada se ponga rojo.
            Ejercicio delOrdinal = new Ejercicio(2044);
            Set<Long> ordinales = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                OrigenContext.fijar(new Origen("cajero", null, null));
                                return ordinales.add(correlativoDe(delOrdinal));
                            });

            assertThat(exitos).isEqualTo(10);
            assertThat(ordinales)
                    .as(
                            "con SELECT + UPDATE en vez del UPSERT, los diez leen el mismo y se"
                                    + " reparten cuatro o cinco numeros")
                    .hasSize(10);
        }

        @Test
        @DisplayName("diez presentaciones simultaneas producen diez DJ con diez numeros distintos")
        void diezPresentacionesSimultaneas() throws Exception {
            Ejercicio delAtropello = new Ejercicio(2026);
            String codigo = nuevoContribuyente();
            long antes = filas("SELECT count(*) FROM declaracion_jurada WHERE ejercicio = 2026");

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                OrigenContext.fijar(new Origen("cajero", null, null));
                                registrar.registrar(
                                        delAtropello,
                                        codigo,
                                        TipoDeDeclaracion.HR,
                                        null,
                                        null,
                                        LocalDate.of(2026, 3, 1),
                                        Observacion.de("Presentacion simultanea de la prueba"));
                                return true;
                            });

            assertThat(exitos).as("ninguna de las diez se pierde").isEqualTo(10);
            assertThat(
                            filas("SELECT count(*) FROM declaracion_jurada WHERE ejercicio = 2026")
                                    - antes)
                    .isEqualTo(10);
            assertThat(
                            filas(
                                    "SELECT count(DISTINCT numero) FROM declaracion_jurada"
                                            + " WHERE ejercicio = 2026"))
                    .as("y los diez numeros son distintos: dj_numero_uq es la red, no el mecanismo")
                    .isEqualTo(
                            filas(
                                    "SELECT count(*) FROM declaracion_jurada WHERE ejercicio = 2026"));
        }
    }

    @Nested
    @DisplayName("Rectificacion")
    class Rectificacion {

        @Test
        @DisplayName(
                "rectificar deja la anterior SUSTITUIDA y crea otra PRESENTADA: las dos filas quedan")
        void rectificarDejaLasDosFilas() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));

            DeclaracionJurada rectificatoria =
                    registrar.rectificar(
                            original.numero(),
                            EJERCICIO,
                            null,
                            null,
                            LocalDate.of(2026, 4, 1),
                            Observacion.de("Se corrige el area declarada por error"));

            assertThat(rectificatoria.tipo()).isEqualTo(TipoDeDeclaracion.RECTIFICATORIA);
            assertThat(rectificatoria.djRectificaId()).isEqualTo(original.id());
            assertThat(rectificatoria.numero())
                    .as("la rectificatoria toma el correlativo siguiente, no el de la anterior")
                    .isNotEqualTo(original.numero());

            DeclaracionJurada anteriorEnBase = enBase(original.id());
            assertThat(anteriorEnBase.estado()).isEqualTo(EstadoDeDeclaracion.SUSTITUIDA);
            assertThat(anteriorEnBase.numero())
                    .as("regla 4: la anterior no se edita, solo cambia su estado")
                    .isEqualTo(original.numero());
            assertThat(anteriorEnBase.fechaPresentacion()).isEqualTo(original.fechaPresentacion());

            assertThat(enBase(rectificatoria.id()).estado())
                    .isEqualTo(EstadoDeDeclaracion.PRESENTADA);
        }

        @Test
        @DisplayName("rectificar una DJ inexistente falla")
        void rectificarUnaDjInexistenteFalla() {
            assertThatThrownBy(
                            () ->
                                    registrar.rectificar(
                                            "DJ-2026-999999",
                                            EJERCICIO,
                                            null,
                                            null,
                                            LocalDate.of(2026, 4, 1),
                                            Observacion.de("No deberia llegar a escribirse")))
                    .isInstanceOf(RegistrarDeclaracionJurada.DeclaracionInexistente.class);
        }

        @Test
        @DisplayName("una DJ ya sustituida no se vuelve a rectificar")
        void unaSustituidaNoSeRectificaDosVeces() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.rectificar(
                    original.numero(),
                    EJERCICIO,
                    null,
                    null,
                    LocalDate.of(2026, 4, 1),
                    Observacion.de("Primera rectificatoria"));

            assertThatThrownBy(
                            () ->
                                    registrar.rectificar(
                                            original.numero(),
                                            EJERCICIO,
                                            null,
                                            null,
                                            LocalDate.of(2026, 5, 1),
                                            Observacion.de("Segunda rectificatoria de la misma")))
                    .as(
                            "con dos rectificatorias vivas, ninguna consulta podria decir cual es"
                                    + " la que el contribuyente declara hoy")
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }

        @Test
        @DisplayName("una DJ anulada no se rectifica: rectificarla la reviviria")
        void unaAnuladaNoSeRectifica() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.anular(
                    original.numero(), EJERCICIO, Observacion.de("Se anula por duplicidad"));

            assertThatThrownBy(
                            () ->
                                    registrar.rectificar(
                                            original.numero(),
                                            EJERCICIO,
                                            null,
                                            null,
                                            LocalDate.of(2026, 5, 1),
                                            Observacion.de("Rectificatoria de una anulada")))
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }

        @Test
        @DisplayName("una OBSERVADA si se rectifica: es para lo que sirve observarla")
        void unaObservadaSeRectifica() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.observar(
                    original.numero(),
                    EJERCICIO,
                    Observacion.de("El area declarada no cuadra con la ficha"));

            DeclaracionJurada rectificatoria =
                    registrar.rectificar(
                            original.numero(),
                            EJERCICIO,
                            null,
                            null,
                            LocalDate.of(2026, 5, 1),
                            Observacion.de("El contribuyente subsana la observacion"));

            assertThat(rectificatoria.djRectificaId()).isEqualTo(original.id());
            assertThat(enBase(original.id()).estado()).isEqualTo(EstadoDeDeclaracion.SUSTITUIDA);
        }

        @Test
        @DisplayName("diez rectificatorias de la misma DJ: el indice deja UNA")
        void diezRectificatoriasDejanUna() throws Exception {
            // Se miden LAS FILAS, no el caso de uso entero (#44, #52): el disparador de estado
            // terminal serializa la carrera completa -la segunda que llega ve SUSTITUIDA y
            // aborta-, asi que la prueba que mide dj_rectifica_uq tiene que insertar diez filas
            // que SOLO compartan dj_rectifica_id. Sin el indice unico salen diez.
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            long anteriorId = original.id();

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                OrigenContext.fijar(new Origen("cajero", null, null));
                                transaccion.execute(
                                        estado ->
                                                repositorio.insertar(
                                                        enBase(anteriorId)
                                                                .rectificadaPor(
                                                                        "R"
                                                                                + java.util
                                                                                        .UUID
                                                                                        .randomUUID()
                                                                                        .toString()
                                                                                        .substring(
                                                                                                0,
                                                                                                18),
                                                                        null,
                                                                        null,
                                                                        null,
                                                                        LocalDate.of(2026, 4, 1),
                                                                        PLAZO,
                                                                        Observacion.de(
                                                                                "Carrera de"
                                                                                        + " rectificatorias"))));
                                return true;
                            });

            assertThat(exitos)
                    .as("diez rectificatorias sobre la misma DJ son diez versiones vigentes")
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM declaracion_jurada WHERE dj_rectifica_id"
                                            + " = "
                                            + anteriorId))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Los actos de la administracion (#365, decision 2)")
    class ActosDeLaAdministracion {

        @Test
        @DisplayName("observar deja la DJ OBSERVADA, con su fila de auditoria")
        void observarDejaLaDjObservada() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));

            DeclaracionJurada observada =
                    registrar.observar(
                            original.numero(),
                            EJERCICIO,
                            Observacion.de("El area declarada no cuadra con la ficha catastral"));

            assertThat(observada.estado()).isEqualTo(EstadoDeDeclaracion.OBSERVADA);
            assertThat(enBase(original.id()).estado()).isEqualTo(EstadoDeDeclaracion.OBSERVADA);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM auditoria WHERE tabla ="
                                            + " 'declaracion_jurada' AND operacion = 'MODIFICACION' AND"
                                            + " observacion LIKE '%no cuadra con la ficha catastral%'"))
                    .as("regla 10: sin observacion no se guarda, y la observacion queda")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("anular deja la DJ ANULADA, con su fila de auditoria")
        void anularDejaLaDjAnulada() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));

            DeclaracionJurada anulada =
                    registrar.anular(
                            original.numero(),
                            EJERCICIO,
                            Observacion.de("Se anula: el contribuyente presento dos veces"));

            assertThat(anulada.estado()).isEqualTo(EstadoDeDeclaracion.ANULADA);
            assertThat(enBase(original.id()).estado()).isEqualTo(EstadoDeDeclaracion.ANULADA);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM auditoria WHERE tabla ="
                                            + " 'declaracion_jurada' AND operacion = 'MODIFICACION' AND"
                                            + " observacion LIKE '%presento dos veces%'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("una OBSERVADA si se puede anular")
        void unaObservadaSePuedeAnular() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.observar(original.numero(), EJERCICIO, Observacion.de("Se objeta el area"));

            assertThat(
                            registrar
                                    .anular(
                                            original.numero(),
                                            EJERCICIO,
                                            Observacion.de("Y despues se anula entera"))
                                    .estado())
                    .isEqualTo(EstadoDeDeclaracion.ANULADA);
        }

        @Test
        @DisplayName("observar dos veces la misma DJ no es un acto: es un error")
        void observarDosVecesFalla() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.observar(original.numero(), EJERCICIO, Observacion.de("Se objeta el area"));

            assertThatThrownBy(
                            () ->
                                    registrar.observar(
                                            original.numero(),
                                            EJERCICIO,
                                            Observacion.de("Se vuelve a objetar lo mismo")))
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }

        @Test
        @DisplayName("una ANULADA no revive: ni se observa ni se vuelve a anular")
        void unaAnuladaNoRevive() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.anular(original.numero(), EJERCICIO, Observacion.de("Se anula la primera"));

            assertThatThrownBy(
                            () ->
                                    registrar.observar(
                                            original.numero(),
                                            EJERCICIO,
                                            Observacion.de("Observando una anulada")))
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
            assertThatThrownBy(
                            () ->
                                    registrar.anular(
                                            original.numero(),
                                            EJERCICIO,
                                            Observacion.de("Anulando una anulada")))
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }

        @Test
        @DisplayName("una SUSTITUIDA tampoco admite actos: los lleva su rectificatoria")
        void unaSustituidaNoAdmiteActos() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.rectificar(
                    original.numero(),
                    EJERCICIO,
                    null,
                    null,
                    LocalDate.of(2026, 4, 1),
                    Observacion.de("Se sustituye"));

            assertThatThrownBy(
                            () ->
                                    registrar.anular(
                                            original.numero(),
                                            EJERCICIO,
                                            Observacion.de("Anulando la sustituida")))
                    .as(
                            "anular la sustituida en vez de la vigente dejaria en pie justo la que"
                                    + " se queria retirar")
                    .isInstanceOf(DeclaracionJurada.TransicionIlegal.class);
        }
    }

    @Nested
    @DisplayName("Lo que la base impide, y no un if de Java (V54)")
    class LoQueImpideLaBase {

        @Test
        @DisplayName("sgtm_app no puede cambiar el numero de una DJ presentada")
        void noSePuedeCambiarElNumero() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));

            assertThatThrownBy(
                            () ->
                                    porSql(
                                            "UPDATE declaracion_jurada SET numero = 'OTRO' WHERE id"
                                                    + " = "
                                                    + original.id()))
                    .as(
                            "el contribuyente firma el papel y se lo lleva: si la base lo puede"
                                    + " reescribir, el papel y el sistema dicen cosas distintas")
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            error ->
                                    assertThat(((SQLException) error).getSQLState())
                                            .as("42501 es «privilegio insuficiente»")
                                            .isEqualTo("42501"));
        }

        @Test
        @DisplayName("ni la fecha, ni el tipo, ni el predio, ni la marca de fuera de plazo")
        void tampocoLasDemasColumnas() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 7, 15));
            long id = original.id();

            for (String columna :
                    List.of(
                            "fecha_presentacion = DATE '2026-01-01'",
                            "tipo = 'PU'",
                            "predio_id = NULL",
                            "fuera_de_plazo = false",
                            "contribuyente_id = contribuyente_id")) {
                assertThatThrownBy(
                                () ->
                                        porSql(
                                                "UPDATE declaracion_jurada SET "
                                                        + columna
                                                        + " WHERE id = "
                                                        + id))
                        .as("columna: " + columna)
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("el estado si se puede cambiar: es lo unico que un acto mueve")
        void elEstadoSiSePuedeCambiar() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));

            porSql(
                    "UPDATE declaracion_jurada SET estado = 'OBSERVADA' WHERE id = "
                            + original.id());

            assertThat(enBase(original.id()).estado()).isEqualTo(EstadoDeDeclaracion.OBSERVADA);
        }

        @Test
        @DisplayName("un estado terminal no revive ni por SQL directo")
        void unEstadoTerminalNoReviveNiPorSql() throws SQLException {
            DeclaracionJurada original = presentar(LocalDate.of(2026, 3, 1));
            registrar.anular(original.numero(), EJERCICIO, Observacion.de("Se anula"));

            assertThatThrownBy(
                            () ->
                                    porSql(
                                            "UPDATE declaracion_jurada SET estado = 'PRESENTADA'"
                                                    + " WHERE id = "
                                                    + original.id()))
                    .as(
                            "el disparador de V54 es lo unico que ven dos peticiones simultaneas"
                                    + " que leyeron las dos el mismo estado anterior")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no admite mas actos");
        }
    }

    // ------------------------------------------------------------------

    private static DeclaracionJurada presentar(LocalDate fecha) throws SQLException {
        return registrar.registrar(
                EJERCICIO,
                nuevoContribuyente(),
                TipoDeDeclaracion.HR,
                null,
                null,
                fecha,
                Observacion.de("Declaracion presentada en ventanilla"));
    }

    private static DeclaracionJurada enBase(Long id) {
        DeclaracionJurada leida =
                transaccion.execute(estado -> repositorio.findById(id).orElseThrow());
        return java.util.Objects.requireNonNull(leida);
    }

    private static long correlativoDe(Ejercicio ejercicio) {
        Long ultimo = transaccion.execute(estado -> repositorio.siguienteCorrelativo(ejercicio));
        return java.util.Objects.requireNonNull(ultimo);
    }

    private static long filas(String sql) {
        Long total = transaccion.execute(estado -> jdbc.sql(sql).query(Long.class).single());
        return total == null ? 0L : total;
    }

    /** Una sentencia como {@code sgtm_app}, con el contexto de tenant fijado a mano. */
    private static void porSql(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static int aLaVez(int cuantos, Callable<Boolean> accion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (ExecutorService hilos = Executors.newFixedThreadPool(cuantos)) {
            for (int i = 0; i < cuantos; i++) {
                resultados.add(
                        hilos.submit(
                                () -> {
                                    salida.await(10, TimeUnit.SECONDS);
                                    try {
                                        return accion.call();
                                    } finally {
                                        TenantContext.limpiar();
                                        OrigenContext.limpiar();
                                    }
                                }));
            }
            salida.countDown();
            int exitos = 0;
            for (Future<Boolean> resultado : resultados) {
                try {
                    if (Boolean.TRUE.equals(resultado.get(60, TimeUnit.SECONDS))) {
                        exitos++;
                    }
                } catch (ExecutionException rechazada) {
                    // La peticion que el indice rechaza no cuenta, y no se traga: que UNA entre y
                    // nueve reboten es exactamente lo que estas pruebas miden.
                }
            }
            return exitos;
        }
    }

    /**
     * Un {@link LectorDeFichas} de prueba: fijo, sin base de datos. Lo que prueba este archivo es
     * que {@code RegistrarDeclaracionJurada} lo consulta con el predio y la fecha correctos y
     * guarda lo que devuelve —no la resolucion de vigencia, que es de {@code catastro}—.
     */
    private static final class FichaFija implements LectorDeFichas {

        @Override
        public java.util.Optional<kamayuk.rentas.dominio.AreaM2> areaDeLaVersion(long fichaId) {
            // El area de una version concreta la lee la deteccion de omisos (#49), no la DJ.
            throw new UnsupportedOperationException("esta prueba no lee superficies");
        }

        @Override
        public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
            return jdbc().sql(
                            "SELECT id FROM ficha_catastral WHERE predio_id = :predioId AND"
                                    + " vigencia_desde <= :fecha AND (vigencia_hasta IS NULL OR"
                                    + " vigencia_hasta >= :fecha)")
                    .param("predioId", predioId)
                    .param("fecha", fecha)
                    .query(Long.class)
                    .optional();
        }
    }

    /**
     * El padron de contribuyentes, resuelto contra la misma base y bajo la misma transaccion. Lo
     * que esta prueba verifica no es la busqueda por aproximacion —esa vive en {@code
     * contribuyentes}— sino que el codigo se resuelva <b>dentro</b> del acto.
     */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("esta prueba no busca por aproximacion");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return jdbc().sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social FROM"
                                    + " contribuyente WHERE codigo_contribuyente = :codigo")
                    .param("codigo", codigo)
                    .query(
                            (fila, numero) ->
                                    new ResumenDeContribuyente(
                                            fila.getLong("id"),
                                            fila.getString("codigo_contribuyente"),
                                            fila.getString("nombre_razon_social"),
                                            "DNI 00000000"))
                    .optional();
        }

        @Override
        public java.util.Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            throw new UnsupportedOperationException("esta prueba no resuelve titulares en lote");
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("esta prueba no lee domicilios");
        }
    }

    private static JdbcClient jdbc() {
        return jdbc;
    }

    private static void sellarPlazo(Ejercicio ejercicio, LocalDate plazo) throws SQLException {
        long parametro = parametroDePlazo(ejercicio, plazo);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbcSellado = JdbcClient.create(pool);
        TenantTransactionManager gestorSellado = new TenantTransactionManager(pool);

        AdministrarParametros administrar =
                envolverCon(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbcSellado),
                                new AuditoriaJdbc(jdbcSellado, RELOJ),
                                RELOJ),
                        gestorSellado);

        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("carga.parametros", null, null));
        try {
            ConjuntoDeParametros conjunto =
                    administrar.abrirVersion(
                            ejercicio, Observacion.de("Se abre el ejercicio para el plazo de DJ"));
            administrar.agregarParametro(
                    conjunto.id(),
                    parametro,
                    Observacion.de("Se incorpora el plazo de declaracion jurada"));
            administrar.sellar(
                    conjunto.id(), Observacion.de("Se sella el ejercicio para poder registrar"));
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    /**
     * Sella un conjunto del ejercicio sin el parametro {@code PLAZO/DECLARACION_JURADA}: hace falta
     * al menos un parametro para sellar (un conjunto vacio no se puede sellar), y aqui se agrega
     * uno que no es el que {@code RegistrarDeclaracionJurada} busca.
     */
    private static void sellarSinPlazo(Ejercicio ejercicio) throws SQLException {
        long otroParametro = parametroDistintoDelPlazo(ejercicio);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbcSellado = JdbcClient.create(pool);
        TenantTransactionManager gestorSellado = new TenantTransactionManager(pool);

        AdministrarParametros administrar =
                envolverCon(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbcSellado),
                                new AuditoriaJdbc(jdbcSellado, RELOJ),
                                RELOJ),
                        gestorSellado);

        // A diferencia de sellarPlazo (que corre en @BeforeAll, sin contexto ambiente), este
        // metodo lo invoca un test ya dentro de @BeforeEach: el TenantContext y el OrigenContext
        // los fija fijarContexto(), y aqui no se tocan para no dejarlos vacios cuando el test
        // continua con registrar.registrar(...) despues de llamar a este metodo.
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(
                        ejercicio, Observacion.de("Se abre el ejercicio sin el plazo de DJ"));
        administrar.agregarParametro(
                conjunto.id(),
                otroParametro,
                Observacion.de("Se incorpora un parametro que no es el plazo de DJ"));
        administrar.sellar(
                conjunto.id(),
                Observacion.de("Se sella el ejercicio, deliberadamente sin el plazo de DJ"));
    }

    private static long parametroDistintoDelPlazo(Ejercicio ejercicio) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL,"
                                        + " 'FICTICIO', ?, 1.000000, DATE '2026-01-01', 'Valor"
                                        + " ficticio de prueba; no representa ninguna norma',"
                                        + " 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, "SIN_PLAZO_" + ejercicio.valor());
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolverCon(T objetivo, TenantTransactionManager gestorPropio) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(
                        gestorPropio, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /**
     * Publica el parametro de texto con el rol que corresponde: la aplicacion solo tiene {@code
     * SELECT} sobre {@code parametro_tributario} (V7); publicar es trabajo de {@code
     * rol_carga_parametros}.
     */
    private static long parametroDePlazo(Ejercicio ejercicio, LocalDate plazo) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL, 'PLAZO',"
                                        + " 'DECLARACION_JURADA', ?, ?, 'Plazo ficticio de prueba;"
                                        + " no representa ninguna ordenanza', 'carga',"
                                        + " 'aprueba') RETURNING id")) {
            sentencia.setString(1, plazo.toString());
            sentencia.setObject(2, LocalDate.of(ejercicio.valor(), 1, 1));
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

    private static synchronized String nuevoContribuyente() throws SQLException {
        String codigo = String.format("DJ-%05d", siguiente++);
        crearContribuyente(codigo, String.format("8%07d", siguiente));
        return codigo;
    }

    private static synchronized String nuevoCodigoCatastral() {
        return String.format("270101001001001%08d", siguiente++);
    }

    /** Una DJ ya numerada, como la que deja una migracion de D-04: sin pasar por el correlativo. */
    private static void sembrarDeclaracionHistorica(String numero, Ejercicio ejercicio)
            throws SQLException {
        String codigo = nuevoContribuyente();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                                    + " contribuyente_id, tipo, fecha_presentacion, fecha_limite,"
                                    + " usuario_registro, observacion) SELECT ?, ?, ?, c.id, 'HR',"
                                    + " ?, ?, 'migracion', 'Fila heredada del sistema anterior'"
                                    + " FROM contribuyente c WHERE c.codigo_contribuyente = ?")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, numero);
                sentencia.setInt(3, ejercicio.valor());
                sentencia.setObject(4, LocalDate.of(ejercicio.valor(), 3, 1));
                sentencia.setObject(5, LocalDate.of(ejercicio.valor(), 6, 30));
                sentencia.setString(6, codigo);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static long crearContribuyente(String codigo, String dni) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearPredio(String codigoRefCatastral) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 123')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigoRefCatastral);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearFichaCatastral(long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA HABITACION', DATE"
                                    + " '2026-01-01', 'DECLARACION_JURADA', 'DJ de prueba',"
                                    + " 'Se registra la ficha para la prueba', 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
