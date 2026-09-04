package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.aplicacion.FichasDelPadronCatastro;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.PrediosSinConciliar;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeConciliacion.FichaConciliada;
import kamayuk.rentas.rentas.dominio.ConciliacionRepository.ResumenDeConciliacion;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.rentas.dominio.TipoDeDeclaracion;
import kamayuk.rentas.rentas.infraestructura.ConciliacionRepositoryJdbc;
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
 * #344 — La conciliacion catastro-rentas contra PostgreSQL de verdad, como {@code sgtm_app}
 * (ADR-0015).
 *
 * <p>Lo que esta clase defiende, y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El predicado, sobre las dos tablas de verdad.</b> Un predio esta conciliado a un
 *       ejercicio cuando existe una {@code declaracion_jurada} de ese ejercicio, con su mismo
 *       {@code predio_id}, en estado {@code PRESENTADA} u {@code OBSERVADA}. Se comprueba en las
 *       dos direcciones —el mismo predio conciliado en su ejercicio y no conciliado en otro— y
 *       estado por estado.
 *   <li><b>El falso omiso</b>, que es lo que este issue existe para no repetir: una DJ con {@code
 *       ficha_catastral_id} <b>nulo</b> y {@code predio_id} puesto tiene que salir conciliada.
 *       Derivar de la columna de V19 —nullable por diseño, y nula en toda fila anterior a esa
 *       migracion— acusaria de omiso a quien declaro.
 *   <li><b>Que la rectificatoria cuente una vez y en el predio correcto</b>, incluso cuando cambia
 *       de predio: {@code rectificadaPor} recibe el predio del llamador, asi que que la sustituida
 *       y su sustituta compartan predio <b>no es un invariante</b>.
 *   <li><b>El rastro del filtro «No»</b>: una fila de {@code auditoria} con operacion {@code
 *       ACCESO}, escrita en la misma transaccion que la lectura. Y que «Todas» y «Si» no dejen
 *       ninguna.
 *   <li><b>El aislamiento</b>: con el contexto de la municipalidad B, ni una fila de A, y la
 *       declaracion de B no concilia el predio homonimo de A.
 *   <li><b>Que la lectura tenga contexto de tenant.</b> El caso de uso se envuelve en un proxy
 *       transaccional <b>de verdad</b> —y sus colaboradores no— para que lo que se verifique sea su
 *       anotacion: sin {@code @Transactional} no hay {@code SET LOCAL} y RLS falla en vez de
 *       devolver filas.
 * </ul>
 */
@DisplayName("#344 — La conciliacion catastro-rentas contra PostgreSQL")
class ConciliacionCatastroRentasJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final LocalDate HOY = LocalDate.of(2026, 8, 28);
    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);
    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final Ejercicio E2025 = new Ejercicio(2025);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    /**
     * La del recuento, y aparte a proposito: el resumen cuenta el padron ENTERO de su
     * municipalidad, asi que compartir la de las demas pruebas haria que el resultado dependiera
     * del orden en que JUnit las ejecute.
     */
    private static long municipalidadDelRecuento;

    /**
     * Una sin nada, para poder afirmar un cero. Con la municipalidad vecina no se puede: otras
     * pruebas del archivo le siembran predios, asi que el resultado dependeria del orden.
     */
    private static long municipalidadSinPadron;

    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static DeclaracionJuradaRepositoryJdbc declaraciones;
    private static ConsultaDeConciliacion consulta;
    private static ConsultaDeConciliacion sinTransaccion;

    /** Para que los codigos de referencia catastral de cada prueba no se pisen entre si. */
    private static int siguienteCodigo = 1;

    private static int siguienteNumeroDeDj = 1;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270301", "Municipalidad de la conciliacion");
        municipalidadB = crearMunicipalidad("270302", "Municipalidad vecina");
        municipalidadDelRecuento = crearMunicipalidad("270303", "Municipalidad del recuento");
        municipalidadSinPadron = crearMunicipalidad("270304", "Municipalidad sin padron");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        declaraciones = new DeclaracionJuradaRepositoryJdbc(jdbc);

        // Los colaboradores van SIN proxy: la unica transaccion posible es la que abre la
        // anotacion del caso de uso, que es lo que esta prueba quiere verificar.
        sinTransaccion =
                new ConsultaDeConciliacion(
                        new FichasDelPadronCatastro(
                                new ConsultaDeFichas(
                                        new FichaCatastralRepositoryJdbc(jdbc), new PadronVacio())),
                        declaraciones,
                        new ConciliacionRepositoryJdbc(jdbc),
                        new AuditoriaJdbc(jdbc, RELOJ),
                        RELOJ);
        consulta = envolver(sinTransaccion, gestor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
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
        OrigenContext.fijar(new Origen("jefe.catastro", "PC-01", "10.0.0.9"));
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El derivado")
    class Derivado {

        @Test
        @DisplayName("un predio con declaracion del ejercicio sale conciliado, y dice a que año")
        void unPredioConDeclaracionSaleConciliado() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            declarar(municipalidadA, predio, E2026, numeroDeDj("DJ"));

            FichaConciliada fila = unica(buscar(codigo, E2026));

            assertThat(fila.conciliada()).isTrue();
            assertThat(fila.conciliadaA())
                    .as(
                            "no existe «conciliada»: existe conciliadaA(ejercicio), y la columna de"
                                    + " la pantalla se rotula con el (regla 9, RNF-075)")
                    .isEqualTo(E2026);
        }

        @Test
        @DisplayName("el mismo predio, preguntado por otro ejercicio, no concilia")
        void elMismoPredioEnOtroEjercicioNoConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            declarar(municipalidadA, predio, E2026, numeroDeDj("DJ"));

            FichaConciliada fila = unica(buscar(codigo, E2025));

            assertThat(fila.conciliada())
                    .as("el padron afecto se rehace cada ejercicio: la DJ de 2026 no concilia 2025")
                    .isFalse();
            assertThat(fila.conciliadaA()).isEqualTo(E2025);
        }

        @Test
        @DisplayName("un predio sin ninguna declaracion no concilia")
        void unPredioSinDeclaracionNoConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            crearPredioConFicha(municipalidadA, codigo);

            assertThat(unica(buscar(codigo, E2026)).conciliada()).isFalse();
        }

        @Test
        @DisplayName("la declaracion de OTRO predio no concilia este")
        void laDeclaracionDeOtroPredioNoConciliaEste() throws SQLException {
            String codigoDeclarado = nuevoCodigo();
            String codigoMudo = nuevoCodigo();
            long declarado = crearPredioConFicha(municipalidadA, codigoDeclarado);
            crearPredioConFicha(municipalidadA, codigoMudo);
            declarar(municipalidadA, declarado, E2026, numeroDeDj("DJ"));

            assertThat(unica(buscar(codigoDeclarado, E2026)).conciliada()).isTrue();
            assertThat(unica(buscar(codigoMudo, E2026)).conciliada())
                    .as(
                            "el cruce es por predio_id; sin el, cualquier DJ conciliaria cualquier ficha")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("El falso omiso")
    class FalsoOmiso {

        @Test
        @DisplayName("una DJ con ficha_catastral_id NULO y predio_id puesto sale CONCILIADA")
        void unaDeclaracionSinFichaCatastralConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);

            // Exactamente la fila que produce ventanilla antes de que el predio tenga ficha, y
            // la que dejo la migracion: toda fila anterior a V19 tiene la columna nula.
            declarar(municipalidadA, predio, E2026, numeroDeDj("DJ-SF"), null);

            assertThat(unica(buscar(codigo, E2026)).conciliada())
                    .as(
                            "derivar de ficha_catastral_id —nullable por diseño y nula en toda fila"
                                    + " anterior a V19— pondria esta fila como «no conciliada», que"
                                    + " es acusar de omiso a quien declaro")
                    .isTrue();
        }

        @Test
        @DisplayName("y la columna sigue nula en la base: no se rellena por el camino")
        void laColumnaSigueNula() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            String numero = numeroDeDj("DJ-SF2");
            declarar(municipalidadA, predio, E2026, numero, null);

            Long ficha =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT ficha_catastral_id FROM"
                                                            + " declaracion_jurada WHERE numero ="
                                                            + " :numero")
                                            .param("numero", numero)
                                            .query(Long.class)
                                            .optional()
                                            .orElse(null));

            assertThat(ficha)
                    .as("si la prueba la rellenara, no estaria probando el caso que dice probar")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Los estados, uno por uno")
    class Estados {

        @Test
        @DisplayName(
                "OBSERVADA concilia: la administracion objeto el contenido, no la presentacion")
        void observadaConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            long dj = declarar(municipalidadA, predio, E2026, numeroDeDj("DJ-OBS"));
            cambiarEstado(municipalidadA, dj, "OBSERVADA");

            assertThat(unica(buscar(codigo, E2026)).conciliada())
                    .as(
                            "negarle la conciliacion diria «este predio no genera deuda predial» de"
                                    + " uno que si la genera: el falso omiso otra vez")
                    .isTrue();
        }

        @Test
        @DisplayName("ANULADA no concilia: dejo de sustentar nada")
        void anuladaNoConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            long dj = declarar(municipalidadA, predio, E2026, numeroDeDj("DJ-ANU"));
            cambiarEstado(municipalidadA, dj, "ANULADA");

            assertThat(unica(buscar(codigo, E2026)).conciliada()).isFalse();
        }

        @Test
        @DisplayName("SUSTITUIDA sola no concilia: sin sustituta no queda declaracion en pie")
        void sustituidaSolaNoConcilia() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            long dj = declarar(municipalidadA, predio, E2026, numeroDeDj("DJ-SUS"));
            transaccion.execute(estado -> declaraciones.marcar(dj, EstadoDeDeclaracion.SUSTITUIDA));

            assertThat(unica(buscar(codigo, E2026)).conciliada()).isFalse();
        }

        @Test
        @DisplayName("SUSTITUIDA con su rectificatoria concilia UNA vez, no dos")
        void sustituidaConRectificatoriaConciliaUnaVez() throws SQLException {
            String codigo = nuevoCodigo();
            long predio = crearPredioConFicha(municipalidadA, codigo);
            long original = declarar(municipalidadA, predio, E2026, numeroDeDj("DJ-REC"));
            rectificar(municipalidadA, original, predio, numeroDeDj("DJ-REC2"));

            Pagina<FichaConciliada> pagina = buscar(codigo, E2026);

            assertThat(pagina.contenido())
                    .as("dos filas en declaracion_jurada, un predio, una fila en la grilla")
                    .hasSize(1);
            assertThat(unica(pagina).conciliada()).isTrue();
            assertThat(prediosConciliados(Set.of(predio), E2026))
                    .as("y el conjunto lo trae una vez: contar la sustituida lo duplicaria")
                    .containsExactly(predio);
        }

        @Test
        @DisplayName(
                "la rectificatoria que CAMBIA de predio mueve la conciliacion, y no la duplica")
        void laRectificatoriaQueCambiaDePredio() throws SQLException {
            String codigoOriginal = nuevoCodigo();
            String codigoNuevo = nuevoCodigo();
            long original = crearPredioConFicha(municipalidadA, codigoOriginal);
            long nuevo = crearPredioConFicha(municipalidadA, codigoNuevo);

            long dj = declarar(municipalidadA, original, E2026, numeroDeDj("DJ-MOV"));
            // rectificadaPor recibe el predio del llamador: que la sustituida y su sustituta
            // compartan predio_id NO es un invariante (revision de #347).
            rectificar(municipalidadA, dj, nuevo, numeroDeDj("DJ-MOV2"));

            assertThat(unica(buscar(codigoOriginal, E2026)).conciliada())
                    .as("el predio que se declaro por error deja de conciliar por esa cadena")
                    .isFalse();
            assertThat(unica(buscar(codigoNuevo, E2026)).conciliada())
                    .as("y el que la rectificatoria declara pasa a conciliar")
                    .isTrue();
            assertThat(prediosConciliados(Set.of(original, nuevo), E2026))
                    .as("ninguno de los dos sale dos veces, y el original no sale")
                    .containsExactly(nuevo);
        }
    }

    @Nested
    @DisplayName("El filtro de la pantalla")
    class Filtro {

        @Test
        @DisplayName("«Si» trae solo los declarados y «No» solo los que faltan")
        void elFiltroParteLaGrilla() throws SQLException {
            String tramo = nuevoTramo();
            long declarado = crearPredioConFicha(municipalidadA, tramo + "1");
            crearPredioConFicha(municipalidadA, tramo + "2");
            declarar(municipalidadA, declarado, E2026, numeroDeDj("DJ-FIL"));

            assertThat(codigosDe(consulta.todas(porCodigo(tramo), E2026, HOY, unaPagina())))
                    .containsExactlyInAnyOrder(tramo + "1", tramo + "2");
            assertThat(codigosDe(consulta.conciliadas(porCodigo(tramo), E2026, HOY, unaPagina())))
                    .containsExactly(tramo + "1");
            assertThat(codigosDe(consulta.noConciliadas(porCodigo(tramo), E2026, HOY, unaPagina())))
                    .as("«No» es la lista de los predios que no generan deuda predial")
                    .containsExactly(tramo + "2");
        }
    }

    @Nested
    @DisplayName("El rastro del filtro «No» (ADR-0015 §2.3)")
    class Rastro {

        @Test
        @DisplayName("pedir los que faltan deja una fila de ACCESO en la bitacora")
        void pedirLosQueFaltanDejaFilaDeAcceso() throws SQLException {
            String tramo = nuevoTramo();
            crearPredioConFicha(municipalidadA, tramo + "1");

            long antes = accesosRegistrados();
            consulta.noConciliadas(porCodigo(tramo), E2026, HOY, unaPagina());

            assertThat(accesosRegistrados() - antes)
                    .as(
                            "en manos equivocadas esta lista es el mapa de a quien no le va a llegar"
                                    + " recibo: quien la pide deja su nombre")
                    .isEqualTo(1);
            assertThat(ultimoAcceso())
                    .containsEntry("tabla", "declaracion_jurada")
                    .containsEntry("usuario_id", "jefe.catastro")
                    .containsEntry("clave", "conciliacion=NO;ejercicio=2026");
        }

        @Test
        @DisplayName("«Todas» y «Si» no dejan ninguna: dicen quien esta dentro, no quien falta")
        void todasYSiNoDejanRastro() throws SQLException {
            String tramo = nuevoTramo();
            crearPredioConFicha(municipalidadA, tramo + "1");

            long antes = accesosRegistrados();
            consulta.todas(porCodigo(tramo), E2026, HOY, unaPagina());
            consulta.conciliadas(porCodigo(tramo), E2026, HOY, unaPagina());

            assertThat(accesosRegistrados()).isEqualTo(antes);
        }

        @Test
        @DisplayName("la fila cae en el ejercicio del ACTO, no en el consultado")
        void laFilaCaeEnElEjercicioDelActo() throws SQLException {
            String tramo = nuevoTramo();
            crearPredioConFicha(municipalidadA, tramo + "1");

            consulta.noConciliadas(porCodigo(tramo), E2025, LocalDate.of(2025, 6, 30), unaPagina());

            assertThat(ultimoAcceso())
                    .as(
                            "la bitacora se particiona por el ejercicio del acto —2026, el del reloj"
                                    + " inyectado—, y consultar el padron de 2025 es un acto de 2026")
                    .containsEntry("ejercicio", "2026")
                    .containsEntry("clave", "conciliacion=NO;ejercicio=2025");
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades")
    class Aislamiento {

        @Test
        @DisplayName(
                "con el contexto de B no se ve ni una ficha de A, ni conciliada ni sin declarar")
        void conElContextoDeBNoSeVeNadaDeA() throws SQLException {
            String tramo = nuevoTramo();
            long declarado = crearPredioConFicha(municipalidadA, tramo + "1");
            crearPredioConFicha(municipalidadA, tramo + "2");
            declarar(municipalidadA, declarado, E2026, numeroDeDj("DJ-RLS"));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            assertThat(consulta.todas(porCodigo(tramo), E2026, HOY, unaPagina()).contenido())
                    .as("la prueba corre como sgtm_app, que es a quien la politica RLS aplica")
                    .isEmpty();
            assertThat(
                            consulta.noConciliadas(porCodigo(tramo), E2026, HOY, unaPagina())
                                    .contenido())
                    .as("ni por el filtro que lista a los que faltan")
                    .isEmpty();
        }

        @Test
        @DisplayName("la declaracion de B no concilia el predio homonimo de A")
        void laDeclaracionDeBNoConciliaElPredioDeA() throws SQLException {
            String codigo = nuevoCodigo();
            crearPredioConFicha(municipalidadA, codigo);

            // El mismo codigo de referencia catastral en la otra municipalidad, y declarado.
            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            long deB = crearPredioConFicha(municipalidadB, codigo);
            declarar(municipalidadB, deB, E2026, numeroDeDj("DJ-B"));
            assertThat(unica(buscar(codigo, E2026)).conciliada()).isTrue();

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            assertThat(unica(buscar(codigo, E2026)).conciliada())
                    .as("dos padrones distintos con el mismo codigo, y cada uno con su respuesta")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("El contexto de tenant")
    class ContextoDeTenantDeLaLectura {

        @Test
        @DisplayName("sin transaccion no hay SET LOCAL, y RLS falla en vez de devolver filas")
        void sinTransaccionNoHayContexto() throws SQLException {
            String codigo = nuevoCodigo();
            crearPredioConFicha(municipalidadA, codigo);

            assertThatThrownBy(
                            () -> sinTransaccion.todas(porCodigo(codigo), E2026, HOY, unaPagina()))
                    .as(
                            "es el defecto que la marcha blanca destapo en GET /catastro/vias: la"
                                    + " anotacion del caso de uso es lo unico que abre la"
                                    + " transaccion, y sin ella la politica no se puede evaluar")
                    .isInstanceOf(Exception.class);
        }
    }

    /**
     * Hace correr al ingestor de la proyeccion de catastro (P5C).
     *
     * <p>Desde la separacion, el recuento de la conciliacion lee {@code predio_ref} y {@code
     * ficha_ref} y no las tablas del vecino: es la mitad de {@code PENDIENTE-CRUCE-01} que va con
     * la otra —la grilla y el recuento tienen que contar la MISMA poblacion (#564), y dos
     * proyecciones distintas darian dos cifras del mismo dia—.
     */
    private static void ingestar(long municipalidadId) throws SQLException {
        kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, municipalidadId);
    }

    @Nested
    @DisplayName("La grilla pagina y cuenta LO FILTRADO (#631)")
    class LaGrillaCuentaLoFiltrado {

        /** Dos que declararon y uno que no, en la municipalidad del recuento. */
        @BeforeEach
        void sembrarElPadron() throws SQLException {
            TenantContext.fijar(new MunicipalidadId(municipalidadDelRecuento));
            if (contarPredios(municipalidadDelRecuento) > 0) {
                return;
            }
            long uno = crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            long dos = crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            declarar(municipalidadDelRecuento, uno, E2026, numeroDeDj("DJ-GRI"));
            // La segunda queda OBSERVADA, que TAMBIEN concilia. Sin ella, cambiar los estados
            // que cuentan en una de las dos consultas no cambia ninguna cifra y la mutacion
            // de la coherencia no muerde: la prueba mediria una casualidad del juego de datos.
            long observada = declarar(municipalidadDelRecuento, dos, E2026, numeroDeDj("DJ-GRI"));
            cambiarEstado(municipalidadDelRecuento, observada, "OBSERVADA");
            ingestar(municipalidadDelRecuento);
        }

        @AfterEach
        void volverAA() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
        }

        @Test
        @DisplayName("«No» dice 1 elemento, no 3: el total describe la poblacion filtrada")
        void elTotalEsElDeLaPoblacionFiltrada() {
            Pagina<FichaConciliada> sinConciliar =
                    consulta.noConciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(1));

            assertThat(sinConciliar.totalElementos())
                    .as(
                            "hasta #631 el filtro se aplicaba sobre la pagina ya devuelta y el"
                                    + " total seguia siendo el del padron: «722 paginas, 14 422"
                                    + " elementos» y cero filas en todas")
                    .isEqualTo(1);
            assertThat(
                            consulta.conciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(1))
                                    .totalElementos())
                    .isEqualTo(2);
            assertThat(consulta.todas(SIN_CRITERIO, E2026, HOY, unaPagina(1)).totalElementos())
                    .as("«Todas» sigue siendo el padron entero, que es lo que dice ser")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("la ultima pagina del filtro trae filas, y la siguiente no")
        void laUltimaPaginaTraeFilas() {
            Pagina<FichaConciliada> primera =
                    consulta.conciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(1));

            assertThat(primera.totalPaginas()).isEqualTo(2);
            assertThat(
                            consulta.conciliadas(
                                            SIN_CRITERIO,
                                            E2026,
                                            HOY,
                                            new Paginacion(
                                                    primera.totalPaginas() - 1,
                                                    1,
                                                    "codRefCatastral",
                                                    Paginacion.Direccion.ASCENDENTE))
                                    .contenido())
                    .as("un paginador por el que se navega entero sin encontrar una fila miente")
                    .hasSize(1);
            assertThat(
                            consulta.conciliadas(
                                            SIN_CRITERIO,
                                            E2026,
                                            HOY,
                                            new Paginacion(
                                                    primera.totalPaginas(),
                                                    1,
                                                    "codRefCatastral",
                                                    Paginacion.Direccion.ASCENDENTE))
                                    .contenido())
                    .isEmpty();
        }

        @Test
        @DisplayName("la grilla y el recuento cuentan la misma poblacion, y eso no se confia")
        void laGrillaYElRecuentoCuadran() {
            ResumenDeConciliacion resumen = consulta.resumen(E2026, HOY);

            assertThat(
                            consulta.noConciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(1))
                                    .totalElementos())
                    .as(
                            "son dos consultas distintas —una agrega y la otra pagina— y si los"
                                    + " estados que hacen vigente una declaracion se separan, las"
                                    + " dos cifras dejan de cuadrar sin que ninguna parezca mal")
                    .isEqualTo(resumen.noConciliados());
            assertThat(
                            consulta.conciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(1))
                                    .totalElementos())
                    .isEqualTo(resumen.conciliados());
            assertThat(consulta.todas(SIN_CRITERIO, E2026, HOY, unaPagina(1)).totalElementos())
                    .isEqualTo(resumen.total());
        }

        @Test
        @DisplayName("y las filas siguen siendo las que el filtro dice")
        void lasFilasSiguenSiendoLasDelFiltro() {
            assertThat(consulta.noConciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(20)).contenido())
                    .hasSize(1)
                    .allSatisfy(fila -> assertThat(fila.conciliada()).isFalse());
            assertThat(consulta.conciliadas(SIN_CRITERIO, E2026, HOY, unaPagina(20)).contenido())
                    .hasSize(2)
                    .allSatisfy(fila -> assertThat(fila.conciliada()).isTrue());
        }
    }

    @Nested
    @DisplayName("El recuento, que la grilla no podia dar (#564)")
    class Recuento {

        /** Dos predios que declararon y uno que no, en una municipalidad para ellos solos. */
        @BeforeEach
        void sembrarElPadron() throws SQLException {
            TenantContext.fijar(new MunicipalidadId(municipalidadDelRecuento));
            if (contarPredios(municipalidadDelRecuento) > 0) {
                return;
            }
            long uno = crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            long dos = crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            crearPredioConFicha(municipalidadDelRecuento, nuevoCodigo());
            declarar(municipalidadDelRecuento, uno, E2026, numeroDeDj("DJ-REC"));
            declarar(municipalidadDelRecuento, dos, E2026, numeroDeDj("DJ-REC"));
            ingestar(municipalidadDelRecuento);
        }

        @AfterEach
        void volverAA() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
        }

        @Test
        @DisplayName("dice 1 sin conciliar sobre 3, no 3 sobre 3")
        void diceUnoSobreTres() {
            ResumenDeConciliacion resumen = consulta.resumen(E2026, HOY);

            assertThat(resumen.total()).isEqualTo(3);
            assertThat(resumen.conciliados()).isEqualTo(2);
            assertThat(resumen.noConciliados())
                    .as(
                            "hasta #564 la grilla contestaba el padron entero con cualquiera de los"
                                    + " tres filtros, y el panel pintaba «sin conciliar: 3» encima"
                                    + " de «3 predios en el padron»: una acusacion de omision a"
                                    + " todo el distrito")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("#549 — el frente de Catastro publica los que FALTAN, no el padron entero")
        void elFrenteDeCatastroPublicaLosQueFaltan() {
            // AC 2.4 por construccion: el puerto del panel NO cuenta por su cuenta, llama a
            // este mismo resumen. Lo que esta prueba sujeta es CUAL de sus tres cifras
            // publica: `total` seria «14 422 predios sin conciliar» sobre un padron de
            // 14 422, que es exactamente la acusacion de omision que #564 vino a cerrar.
            PrediosSinConciliar puerto = new PrediosSinConciliarRentas(consulta);
            ResumenDeConciliacion resumen = consulta.resumen(E2026, HOY);

            assertThat(puerto.cuantosA(E2026, HOY)).isEqualTo(resumen.noConciliados()).isEqualTo(1);
            assertThat(puerto.cuantosA(E2026, HOY))
                    .as("y no el total del padron, ni los que si declararon")
                    .isNotEqualTo(resumen.total())
                    .isNotEqualTo(resumen.conciliados());
        }

        @Test
        @DisplayName("#549 — y el frente lleva su ejercicio: con otro, faltan todos")
        void elFrenteLlevaSuEjercicio() {
            PrediosSinConciliar puerto = new PrediosSinConciliarRentas(consulta);

            assertThat(puerto.cuantosA(E2025, HOY))
                    .as("la DJ de 2026 no concilia 2025: el padron afecto se rehace cada año")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("el recuento lleva su ejercicio, y con otro ejercicio no concilia nadie")
        void llevaSuEjercicio() {
            ResumenDeConciliacion deDosMilVeintiseis = consulta.resumen(E2026, HOY);
            ResumenDeConciliacion deDosMilVeinticinco = consulta.resumen(E2025, HOY);

            assertThat(deDosMilVeintiseis.ejercicio()).isEqualTo(E2026);
            assertThat(deDosMilVeintiseis.aLaFecha()).isEqualTo(HOY);
            assertThat(deDosMilVeinticinco.conciliados())
                    .as(
                            "no existe «conciliado»: existe «conciliado a 2026». El padron afecto"
                                    + " se rehace cada año y la DJ de 2026 no concilia 2025")
                    .isZero();
            assertThat(deDosMilVeinticinco.noConciliados()).isEqualTo(3);
        }

        @Test
        @DisplayName("el total del recuento es el mismo que el de la grilla, y eso no se confia")
        void elTotalEsElDeLaGrilla() {
            long deLaGrilla =
                    consulta.todas(
                                    new BusquedaDeFichas(null, null, null, null, null),
                                    E2026,
                                    HOY,
                                    unaPagina())
                            .totalElementos();

            assertThat(consulta.resumen(E2026, HOY).total())
                    .as(
                            "el recuento vive en rentas y lee dos tablas de catastro (ver"
                                    + " ConciliacionRepository): el riesgo que eso arrastra es que"
                                    + " la poblacion que CUENTA deje de ser la que la grilla"
                                    + " LISTA, y ninguna de las dos cifras pareceria mal")
                    .isEqualTo(deLaGrilla);
        }

        @Test
        @DisplayName("contar no deja fila en la bitacora: dice cuantos, no cuales")
        void contarNoDejaRastro() throws SQLException {
            long antes = accesosRegistrados();

            consulta.resumen(E2026, HOY);
            consulta.resumen(E2026, HOY);

            assertThat(accesosRegistrados())
                    .as(
                            "«No» deja rastro porque NOMBRA —es el mapa de a quien no le va a"
                                    + " llegar recibo—; un recuento no nombra a nadie, y auditar"
                                    + " cada pintada del panel llenaria la bitacora de filas que no"
                                    + " contestan la pregunta que la bitacora existe para"
                                    + " contestar")
                    .isEqualTo(antes);
        }

        @Test
        @DisplayName("no ve el padron de las otras municipalidades")
        void noVeLasOtras() {
            TenantContext.fijar(new MunicipalidadId(municipalidadSinPadron));

            assertThat(consulta.resumen(E2026, HOY).total())
                    .as(
                            "los tres predios se sembraron en la del recuento, y el resto del"
                                    + " archivo siembra en A y en B; RLS es lo unico que sostiene"
                                    + " que este cero sea cero")
                    .isZero();
        }

        @Test
        @DisplayName("sin transaccion no cuenta: falla, no devuelve cero")
        void sinTransaccionFalla() {
            assertThatThrownBy(() -> sinTransaccion.resumen(E2026, HOY))
                    .as(
                            "sin SET LOCAL la politica RLS no se puede evaluar; un cero seria un"
                                    + " «ningun predio en el padron» que nadie distinguiria")
                    .isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------

    private static long contarPredios(long municipalidad) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement("SELECT count(*) FROM predio");
                    ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static Pagina<FichaConciliada> buscar(String codigo, Ejercicio ejercicio) {
        return consulta.todas(porCodigo(codigo), ejercicio, HOY, unaPagina());
    }

    private static BusquedaDeFichas porCodigo(String codigo) {
        return new BusquedaDeFichas(codigo, null, null, null, null);
    }

    /** Sin ningun filtro del usuario: lo unico que acota es la conciliacion. */
    private static final BusquedaDeFichas SIN_CRITERIO =
            new BusquedaDeFichas(null, null, null, null, null);

    private static Paginacion unaPagina(int tamano) {
        return new Paginacion(0, tamano, "codRefCatastral", Paginacion.Direccion.ASCENDENTE);
    }

    private static Paginacion unaPagina() {
        return new Paginacion(0, 20, "codRefCatastral", Paginacion.Direccion.ASCENDENTE);
    }

    private static FichaConciliada unica(Pagina<FichaConciliada> pagina) {
        assertThat(pagina.contenido()).hasSize(1);
        return pagina.contenido().get(0);
    }

    private static List<String> codigosDe(Pagina<FichaConciliada> pagina) {
        return pagina.contenido().stream()
                .map(fila -> fila.ficha().codigoReferenciaCatastral())
                .toList();
    }

    private static Set<Long> prediosConciliados(Set<Long> predios, Ejercicio ejercicio) {
        Set<Long> resultado =
                transaccion.execute(
                        estado -> declaraciones.prediosConDeclaracionVigente(predios, ejercicio));
        return resultado == null ? Set.of() : resultado;
    }

    private static long accesosRegistrados() {
        Long total =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT count(*) FROM auditoria"
                                                        + " WHERE operacion = 'ACCESO' AND tabla ="
                                                        + " 'declaracion_jurada'")
                                        .query(Long.class)
                                        .single());
        return total == null ? 0 : total;
    }

    private static Map<String, String> ultimoAcceso() {
        Map<String, String> fila =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT ejercicio::text AS ejercicio, tabla, clave,"
                                                        + " usuario_id, observacion FROM auditoria"
                                                        + " WHERE operacion = 'ACCESO' AND tabla ="
                                                        + " 'declaracion_jurada' ORDER BY id DESC"
                                                        + " LIMIT 1")
                                        .query(
                                                (rs, n) ->
                                                        Map.of(
                                                                "ejercicio",
                                                                rs.getString("ejercicio"),
                                                                "tabla",
                                                                rs.getString("tabla"),
                                                                "clave",
                                                                rs.getString("clave"),
                                                                "usuario_id",
                                                                rs.getString("usuario_id"),
                                                                "observacion",
                                                                rs.getString("observacion")))
                                        .single());
        return fila == null ? Map.of() : fila;
    }

    /** Un numero de DJ corto: la columna admite 20 caracteres, y el catastral solo ya son 23. */
    private static synchronized String numeroDeDj(String prefijo) {
        return prefijo + "-" + String.format("%06d", siguienteNumeroDeDj++);
    }

    private static synchronized String nuevoCodigo() {
        return String.format("27030100100100100%06d", siguienteCodigo++);
    }

    /** Un prefijo comun a varios predios de la misma prueba, para filtrarlos juntos. */
    private static synchronized String nuevoTramo() {
        return String.format("27030100100100200%05d", siguienteCodigo++);
    }

    private static long declarar(
            long municipalidad, long predioId, Ejercicio ejercicio, String numero)
            throws SQLException {
        return declarar(
                municipalidad, predioId, ejercicio, numero, fichaDe(municipalidad, predioId));
    }

    private static long declarar(
            long municipalidad,
            long predioId,
            Ejercicio ejercicio,
            String numero,
            Long fichaCatastralId)
            throws SQLException {
        DeclaracionJurada guardada =
                transaccion.execute(
                        estado ->
                                declaraciones.insertar(
                                        DeclaracionJurada.nueva(
                                                numero,
                                                ejercicio,
                                                contribuyenteDe(municipalidad),
                                                TipoDeDeclaracion.HR,
                                                predioId,
                                                null,
                                                fichaCatastralId,
                                                LocalDate.of(ejercicio.valor(), 2, 10),
                                                LocalDate.of(ejercicio.valor(), 2, 28),
                                                Observacion.de(
                                                        "Declaracion presentada en ventanilla"))));
        return java.util.Objects.requireNonNull(guardada).id();
    }

    private static void rectificar(
            long municipalidad, long declaracionId, long predioId, String numero) {
        transaccion.execute(
                estado -> {
                    DeclaracionJurada original =
                            declaraciones
                                    .findById(declaracionId)
                                    .orElseThrow(() -> new IllegalStateException("no esta"));
                    declaraciones.insertar(
                            original.rectificadaPor(
                                    numero,
                                    predioId,
                                    null,
                                    null,
                                    LocalDate.of(original.ejercicio().valor(), 5, 20),
                                    original.fechaLimite(),
                                    Observacion.de("Rectificatoria del predio declarado")));
                    return declaraciones.marcar(declaracionId, EstadoDeDeclaracion.SUSTITUIDA);
                });
    }

    private static void cambiarEstado(long municipalidad, long declaracionId, String estado)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement("UPDATE declaracion_jurada SET estado = ? WHERE id = ?")) {
                sentencia.setString(1, estado);
                sentencia.setLong(2, declaracionId);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** El predio con su ficha unica vigente: sin ficha no hay fila en la grilla. */
    private static long crearPredioConFicha(long municipalidad, String codigo) throws SQLException {
        long predio = crearPredio(municipalidad, codigo);
        crearFicha(municipalidad, predio);
        return predio;
    }

    private static long crearPredio(long municipalidad, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, "AV. CONCILIACION " + codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void crearFicha(long municipalidad, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, ?, 'CASA HABITACION', ?,"
                                    + " 'DECLARACION_JURADA', 'DJ-SIEMBRA', 'Siembra de la"
                                    + " prueba', 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setBigDecimal(3, new BigDecimal("120.00"));
                sentencia.setObject(4, ALTA);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static @org.jspecify.annotations.Nullable Long fichaDe(
            long municipalidad, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT id FROM ficha_catastral WHERE predio_id = ? AND tipo = 'UNICA'"
                                    + " ORDER BY version DESC LIMIT 1")) {
                sentencia.setLong(1, predioId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    return resultado.next() ? resultado.getLong(1) : null;
                }
            }
        }
    }

    /** Un solo declarante por municipalidad: quien declara no es lo que esta prueba mide. */
    private static final Map<Long, Long> DECLARANTES = new java.util.HashMap<>();

    private static synchronized long contribuyenteDe(long municipalidad) {
        return DECLARANTES.computeIfAbsent(
                municipalidad,
                id -> {
                    try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                        ContextoDeTenant.fijar(app, id);
                        try (PreparedStatement sentencia =
                                app.prepareStatement(
                                        "INSERT INTO contribuyente (municipalidad_id,"
                                                + " codigo_contribuyente, tipo_documento,"
                                                + " numero_documento, tipo_persona,"
                                                + " nombre_razon_social, usuario_registro)"
                                                + " VALUES (?, ?, 'DNI', ?, 'NATURAL',"
                                                + " 'DECLARANTE DE PRUEBA', 'siembra') RETURNING id")) {
                            sentencia.setLong(1, id);
                            sentencia.setString(2, "C-" + id);
                            sentencia.setString(3, String.format("4%07d", id));
                            try (ResultSet resultado = sentencia.executeQuery()) {
                                resultado.next();
                                long contribuyente = resultado.getLong(1);
                                app.commit();
                                return contribuyente;
                            }
                        }
                    } catch (SQLException fallo) {
                        throw new IllegalStateException("no se pudo sembrar el declarante", fallo);
                    }
                });
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

    /** El padron no hace falta: ninguna de estas pruebas filtra por titular. */
    private static final class PadronVacio implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }
}
