package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.catastro.prueba.PrediosDelEscenario;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.AcreditacionEnElPadron;
import kamayuk.rentas.contribuyentes.aplicacion.AcreditacionJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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

/**
 * #57 — La situacion del ciudadano contra PostgreSQL de verdad, conectada como {@code kamayuk_app}
 * (RF-131, ADR-0020).
 *
 * <h2>Lo que esta clase defiende y ninguna prueba con dobles puede</h2>
 *
 * <ul>
 *   <li><b>Que la union de las ramas no cruce el aislamiento.</b> Con dos municipalidades sembradas
 *       y la misma persona en las dos, la respuesta trae las dos y <b>ninguna fila de una
 *       tercera</b>. Con un doble esto solo probaria que el doble coincide consigo mismo; el
 *       aislamiento lo aplica la politica RLS, y la politica RLS solo existe en la base.
 *       <p><i>Rotura:</i> conectar el pool como superusuario. Un superusuario omite RLS incluso con
 *       {@code FORCE ROW LEVEL SECURITY}, y entonces cada rama ve el padron entero: la persona
 *       «aparece» en municipalidades donde no esta.
 *   <li><b>Que el contexto se limpie entre ramas.</b> Una rama que lanza no puede dejar el contexto
 *       de su municipalidad puesto: la siguiente devolveria <b>datos reales bajo la etiqueta
 *       equivocada</b>, que es la fuga que no se ve.
 *   <li><b>Que ninguna conexion vuelva al pool con {@code app.municipalidad_id} puesto.</b>
 *   <li><b>Que la fecha de corte sea una sola</b> para todas las ramas (regla 9, RNF-075).
 *   <li><b>Que se audite donde se lee y en ningun otro sitio.</b> La municipalidad donde la persona
 *       no figura no recibe ninguna fila: el sondeo del padron no es un acceso.
 * </ul>
 */
@DisplayName("#57 — La situacion del ciudadano contra PostgreSQL")
class SituacionDelCiudadanoJdbcTest {

    private static PrediosDelContribuyente predios;
    private static Auditoria auditoria;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 8, 29);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    /**
     * Un documento distinto por prueba, y no uno fijo.
     *
     * <p>Lo exige {@code contribuyente_documento_uq}: <b>una</b> fila por municipalidad y
     * documento. Es la restriccion sobre la que se apoya todo el recorrido —por eso cada rama puede
     * componer sin elegir entre candidatas— y aqui obliga a que cada prueba pregunte por una
     * persona propia. Las tres municipalidades se comparten; las personas, no.
     */
    private static DocumentoIdentidad documentoDePrueba() {
        return DocumentoIdentidad.dni(
                String.format("%08d", 30_000_000 + CONTADOR.incrementAndGet()));
    }

    /** Fija el sujeto de la peticion, que es lo que hace el filtro del portal. */
    private static DocumentoIdentidad preguntaPor(DocumentoIdentidad documento) {
        CiudadanoContext.limpiar();
        CiudadanoContext.fijar(documento);
        OrigenContext.limpiar();
        OrigenContext.fijar(new Origen(documento.numero(), null, null));
        return documento;
    }

    private static BaseDeDatosDePrueba base;
    private static long sullana;
    private static long catacaos;
    private static long piura;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    private static ConsultaDelCiudadano consulta;
    private static RamaDelCiudadano rama;
    private static RecorridoPorMunicipalidades recorrido;
    private static RegistrarAsiento registrarAsiento;
    private static AcreditacionEnElPadron acreditacion;
    private static ConsultaDeDeudaPublica deuda;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        // Tres, y la persona figura en dos: sin la tercera, «todas las activas» y «las
        // dos donde figura» serian el mismo conjunto y la prueba no distinguiria.
        sullana = crearMunicipalidad("270601", "Municipalidad de Sullana, prueba");
        catacaos = crearMunicipalidad("270104", "Municipalidad de Catacaos, prueba");
        piura = crearMunicipalidad("270101", "Municipalidad donde no figura, prueba");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        armar(pool);
    }

    /** El cableado de produccion, sobre el pool que se le de. */
    private static void armar(DataSource pool) {
        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);

        auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        deuda =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));

        acreditacion = envolver(new AcreditacionJdbc(new ContribuyenteRepositoryJdbc(jdbc)));
        predios = new PrediosDelEscenario(jdbc);

        rama = envolver(new RamaDelCiudadano(acreditacion, deuda, predios, auditoria, RELOJ));
        recorrido = new RecorridoPorMunicipalidades(jdbc, gestor);
        consulta = new ConsultaDelCiudadano(recorrido, rama, RELOJ);
    }

    /**
     * Envuelve el objeto en un proxy transaccional <b>de verdad</b>.
     *
     * <p>Lo que se quiere verificar es la anotacion del codigo de produccion: si la prueba abriera
     * las transacciones, quitarsela a {@link RamaDelCiudadano#leer} no pondria nada en rojo.
     */
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
    void fijarUnSujetoCualquiera() {
        // Cada prueba fija el suyo con `preguntaPor`; esto solo garantiza que nunca se
        // corre sin sujeto, que es lo que el filtro del portal impide en produccion.
        preguntaPor(documentoDePrueba());
    }

    @AfterEach
    void limpiar() {
        CiudadanoContext.limpiar();
        OrigenContext.limpiar();
        TenantContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("La union de las ramas, y lo que no cruza")
    class LaUnion {

        @Test
        @DisplayName("con la misma persona en dos municipalidades, salen las dos y ninguna tercera")
        void lasDosDondeFiguraYNingunaMas() {
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-1", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-1", quien);
            // En la tercera hay padron, pero de OTRA persona: es lo que separa «no
            // figura» de «no hay nadie».
            crearContribuyente(piura, "CIU-C-1", documentoDePrueba());
            cargar(sullana, enSullana, "PREDIAL", "100.00");
            cargar(catacaos, enCatacaos, "ARBITRIO", "50.00");

            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(HOY);

            assertThat(situacion.municipalidades())
                    .as("las dos donde figura, y la tercera no")
                    .hasSize(2);
            assertThat(situacion.municipalidades().stream().map(m -> m.ubigeo()).toList())
                    .containsExactlyInAnyOrder("270601", "270104");
            assertThat(situacion.recorridas())
                    .as("se recorrieron las tres activas: no figurar no es no visitarla")
                    .isGreaterThanOrEqualTo(3);
            assertThat(situacion.sinRegistros()).isFalse();
        }

        @Test
        @DisplayName("cada rama trae **su** codigo de contribuyente, no el de la otra")
        void cadaRamaTraeSuCodigo() {
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-2", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-2", quien);
            cargar(sullana, enSullana, "PREDIAL", "10.00");
            cargar(catacaos, enCatacaos, "PREDIAL", "20.00");

            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(HOY);

            // La misma persona tiene un codigo distinto en cada municipalidad, y es el
            // que figura en SU recibo: cruzarlos seria darle el codigo de otra ventanilla.
            assertThat(codigoEn(situacion, "270601")).isEqualTo("CIU-A-2");
            assertThat(codigoEn(situacion, "270104")).isEqualTo("CIU-B-2");
        }

        @Test
        @DisplayName("la deuda de una municipalidad no se le suma a la otra")
        void laDeudaNoSeCruza() {
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-3", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-3", quien);
            cargar(sullana, enSullana, "PREDIAL", "300.00");
            cargar(catacaos, enCatacaos, "PREDIAL", "700.00");

            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(HOY);

            assertThat(totalEn(situacion, "270601")).isEqualTo(Dinero.de("300.00"));
            assertThat(totalEn(situacion, "270104")).isEqualTo(Dinero.de("700.00"));
            // Y el consolidado es la suma de las dos, hecha por el servidor (RNF-083).
            assertThat(situacion.totalConsolidado()).contains(Dinero.de("1000.00"));
        }

        @Test
        @DisplayName("quien no figura en ninguna lo oye asi, y no como «no debes nada»")
        void sinRegistrosEnNinguna() {
            // Un documento que nadie tiene en ninguna de las tres.
            preguntaPor(DocumentoIdentidad.dni("99999999"));

            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(HOY);

            assertThat(situacion.sinRegistros()).isTrue();
            assertThat(situacion.municipalidades()).isEmpty();
            // Y hay total: se pudieron mirar todas. Cero es la respuesta correcta
            // cuando de verdad no hay nada; lo que no puede haber es un cero cuando
            // falta una rama, y eso lo prueba `LoQueFalla`.
            assertThat(situacion.totalConsolidado()).contains(Dinero.CERO);
        }
    }

    @Nested
    @DisplayName("Una sola fecha, y los predios de cada uno")
    class LaFechaYLosPredios {

        @Test
        @DisplayName("las dos ramas responden a la MISMA fecha de corte")
        void laMismaFechaEnLasDos() {
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-4", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-4", quien);
            cargar(sullana, enSullana, "PREDIAL", "10.00");
            cargar(catacaos, enCatacaos, "PREDIAL", "10.00");

            LocalDate deAyer = HOY.minusDays(1);
            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(deAyer);

            assertThat(situacion.aLaFecha()).isEqualTo(deAyer);
            for (ConsultaDelCiudadano.EnMunicipalidad municipalidad : situacion.municipalidades()) {
                assertThat(municipalidad.situacion().resumen().aLaFecha())
                        .as("cada rama a la fecha que se le dio, no a la del reloj (regla 9)")
                        .isEqualTo(deAyer);
                for (var obligacion : municipalidad.situacion().obligaciones()) {
                    assertThat(obligacion.fecha()).isEqualTo(deAyer);
                }
            }
        }

        @Test
        @DisplayName("cada predio viene con SU porcentaje, y solo el de esa municipalidad")
        void losPrediosSonLosSuyos() {
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-5", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-5", quien);
            long predioA = crearPredio(sullana, "27060100010001000000001");
            long predioB = crearPredio(catacaos, "27010400010001000000002");
            // Copropietario al 50 %: es el caso que ADR-0019 nombra, y el que hace
            // visible que lo publicado es SU porcion y no la del predio entero.
            crearTitularidad(sullana, predioA, enSullana, "COPROPIETARIO", "50.0000");
            crearTitularidad(catacaos, predioB, enCatacaos, "PROPIETARIO_UNICO", "100.0000");

            ConsultaDelCiudadano.Situacion situacion = consulta.situacion(HOY);

            var deSullana = municipalidad(situacion, "270601").situacion().predios();
            assertThat(deSullana).hasSize(1);
            assertThat(deSullana.get(0).codigoReferenciaCatastral())
                    .isEqualTo("27060100010001000000001");
            assertThat(deSullana.get(0).porcentajeTitularidad().valor())
                    .as("el suyo, no el 100 % del predio (ADR-0019)")
                    .isEqualByComparingTo("50.0000");

            var deCatacaos = municipalidad(situacion, "270104").situacion().predios();
            assertThat(deCatacaos).hasSize(1);
            assertThat(deCatacaos.get(0).codigoReferenciaCatastral())
                    .isEqualTo("27010400010001000000002");
        }
    }

    @Nested
    @DisplayName("Lo que falla, y lo que se lleva por delante")
    class LoQueFalla {

        @Test
        @DisplayName("una rama que revienta no tumba las demas, **y no hay total**")
        void unaRamaQueRevientaNoTumbaLasDemas() {
            long enSullana =
                    crearContribuyente(sullana, "CIU-A-6", preguntaPor(documentoDePrueba()));
            cargar(sullana, enSullana, "PREDIAL", "400.00");

            // Una rama que lanza en la primera municipalidad que toque. Es lo que le
            // pasaria de verdad a la que no tenga conjunto sellado (EjercicioSinSellar).
            var contadas = new AtomicInteger();
            RecorridoPorMunicipalidades.Resultado<ConsultaDelCiudadano.EnMunicipalidad> resultado =
                    recorrido.recorrer(
                            municipalidad -> {
                                if (contadas.incrementAndGet() == 1) {
                                    throw new IllegalStateException("esta municipalidad no se lee");
                                }
                                return rama.leer(HOY)
                                        .map(
                                                situacion ->
                                                        new ConsultaDelCiudadano.EnMunicipalidad(
                                                                municipalidad.ubigeo(),
                                                                municipalidad.nombre(),
                                                                situacion));
                            });

            assertThat(resultado.fallidas()).hasSize(1);
            assertThat(resultado.completo())
                    .as("con una rama sin leer no se puede totalizar")
                    .isFalse();
            // Y las demas se leyeron: la que falla no interrumpe el recorrido.
            assertThat(resultado.recorridas()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("**y entonces la situacion no trae total**, ni siquiera cero")
        void sinTodasLasRamasNoHayTotal() {
            /* La mutacion que este caso existe para cazar: quitarle a
            `totalConsolidado()` la guarda de `noLeidas` y sumar lo que haya.
            Pasaba en VERDE con las trece pruebas de esta clase, porque la de
            arriba mira `Resultado.completo()` del recorrido y no la situacion
            compuesta —que es lo que sale por HTTP—. Un total al que le falta una
            municipalidad es un importe plausible y equivocado, y un cero seria
            peor: diria que no debe nada. */
            DocumentoIdentidad quien = preguntaPor(documentoDePrueba());
            long enSullana = crearContribuyente(sullana, "CIU-A-10", quien);
            long enCatacaos = crearContribuyente(catacaos, "CIU-B-10", quien);
            cargar(sullana, enSullana, "PREDIAL", "400.00");
            cargar(catacaos, enCatacaos, "PREDIAL", "600.00");

            ConsultaDelCiudadano conUnaRamaRota =
                    new ConsultaDelCiudadano(recorrido, ramaQueFallaEnLaPrimera(), RELOJ);
            ConsultaDelCiudadano.Situacion situacion = conUnaRamaRota.situacion(HOY);

            assertThat(situacion.noLeidas()).as("se dice cual falta").hasSize(1);
            assertThat(situacion.totalConsolidado())
                    .as("sin todas las ramas no se puede decir un total")
                    .isEmpty();
            // Y lo que si se leyo se muestra: la que falla no se lleva a las demas.
            assertThat(situacion.municipalidades())
                    .as("la municipalidad que si se pudo leer sigue saliendo")
                    .hasSize(1);
        }

        @Test
        @DisplayName("**el contexto se limpia entre ramas**, aunque la rama lance")
        void elContextoSeLimpiaEntreRamas() {
            // Sin la limpieza, la rama siguiente correria con el contexto de la
            // anterior: datos reales bajo la etiqueta equivocada.
            recorrido.recorrer(
                    municipalidad -> {
                        throw new IllegalStateException("ninguna se lee");
                    });

            assertThat(TenantContext.actualSiHay())
                    .as("al salir del recorrido no puede quedar contexto de nadie")
                    .isEmpty();
        }

        @Test
        @DisplayName("y el recorrido se niega a correr si ya hay contexto puesto")
        void seNiegaSiYaHayContexto() {
            // Recorrer desde dentro de una peticion de ventanilla dejaria el contexto de
            // esa peticion cambiado a mitad de camino.
            TenantContext.fijar(new MunicipalidadId(sullana));

            assertThatThrownBy(() -> recorrido.recorrer(municipalidad -> Optional.empty()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ya tiene contexto");
        }

        @Test
        @DisplayName("ninguna conexion vuelve al pool con app.municipalidad_id puesto")
        void ningunaConexionVuelveContaminada() {
            crearContribuyente(sullana, "CIU-A-7", preguntaPor(documentoDePrueba()));
            consulta.situacion(HOY);

            // `SET LOCAL` muere con la transaccion; el recorrido abre una por rama. Si
            // alguien lo cambiara por `SET SESSION` —o fijara el parametro fuera de una
            // transaccion—, la conexion volveria al pool con el contexto de la ultima
            // municipalidad recorrida.
            String contexto =
                    jdbc.sql("SELECT current_setting('app.municipalidad_id', true)")
                            .query(String.class)
                            .optional()
                            .orElse("");

            assertThat(contexto)
                    .as("la conexion siguiente leeria por la municipalidad de la rama anterior")
                    .isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("La auditoria: donde se lee, y en ningun otro sitio")
    class LaAuditoria {

        @Test
        @DisplayName("la municipalidad donde figura recibe su fila de ACCESO")
        void dondeFiguraSeAudita() {
            long enSullana =
                    crearContribuyente(sullana, "CIU-A-8", preguntaPor(documentoDePrueba()));
            cargar(sullana, enSullana, "PREDIAL", "1.00");
            int antes = accesosEn(sullana, "CIU-A-8");

            consulta.situacion(HOY);

            assertThat(accesosEn(sullana, "CIU-A-8")).isEqualTo(antes + 1);
        }

        @Test
        @DisplayName("**la municipalidad donde NO figura no recibe ninguna**")
        void dondeNoFiguraNoSeAudita() {
            // El sondeo del padron no es un acceso: auditarlo convertiria la bitacora de
            // cada municipio en una forma de saber que alguien existe en otro.
            crearContribuyente(sullana, "CIU-A-9", preguntaPor(documentoDePrueba()));
            int antes = accesosEn(piura, null);

            consulta.situacion(HOY);

            assertThat(accesosEn(piura, null))
                    .as("aqui no se leyo nada suyo, asi que aqui no queda rastro")
                    .isEqualTo(antes);
        }
    }

    /**
     * **La rotura que demuestra que esto verifica algo**: como superusuario, cada rama ve el padron
     * entero y la persona «aparece» donde no esta.
     *
     * <p>No se deja activa —seria una prueba que contradice al resto—, pero se escribe aqui para
     * que quede dicho como se comprobo: sustituyendo el usuario del pool en {@link #armar} por el
     * superusuario de Testcontainers, {@code lasDosDondeFiguraYNingunaMas} devuelve <b>tres</b>
     * municipalidades donde debe devolver dos. Un superusuario omite RLS incluso con {@code FORCE
     * ROW LEVEL SECURITY} (DAT-01 §0, primer hallazgo).
     */
    @Test
    @DisplayName("el pool de la prueba es kamayuk_app, no el superusuario")
    void elPoolNoEsSuperusuario() {
        // Sin esto, todo lo de arriba pasaria en verde sin haber verificado ningun
        // aislamiento: es la leccion heredada del SRTM y la que hace que esta clase valga.
        String usuario = jdbc.sql("SELECT current_user").query(String.class).single();

        assertThat(usuario).isEqualTo(BaseDeDatosDePrueba.APP);
        assertThat(
                        jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                                .query(Boolean.class)
                                .single())
                .as("un superusuario omite RLS y dejaria esta clase comprobando nada")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    /**
     * La rama de produccion, con la primera municipalidad rota.
     *
     * <p>Es lo que le pasa de verdad a una municipalidad sin conjunto sellado ({@code
     * EjercicioSinSellar}), que es el estado de <b>todas</b> hoy para el calculo. Se envuelve la
     * rama real en vez de sustituirla: lo que se prueba es como se compone la situacion cuando una
     * rama lanza, no un doble de la composicion.
     */
    private static RamaDelCiudadano ramaQueFallaEnLaPrimera() {
        AtomicInteger vueltas = new AtomicInteger();
        return new RamaDelCiudadano(
                documento -> {
                    if (vueltas.incrementAndGet() == 1) {
                        throw new IllegalStateException("esta municipalidad no se puede leer");
                    }
                    return acreditacion.de(documento);
                },
                deuda,
                predios,
                auditoria,
                RELOJ);
    }

    private static ConsultaDelCiudadano.EnMunicipalidad municipalidad(
            ConsultaDelCiudadano.Situacion situacion, String ubigeo) {
        return situacion.municipalidades().stream()
                .filter(m -> m.ubigeo().equals(ubigeo))
                .findFirst()
                .orElseThrow(() -> new AssertionError("la situacion no trae " + ubigeo));
    }

    private static String codigoEn(ConsultaDelCiudadano.Situacion situacion, String ubigeo) {
        return municipalidad(situacion, ubigeo).situacion().contribuyente().codigo();
    }

    private static Dinero totalEn(ConsultaDelCiudadano.Situacion situacion, String ubigeo) {
        return municipalidad(situacion, ubigeo).situacion().resumen().total();
    }

    /** Un cargo asentado en la municipalidad que se diga, con su propio contexto. */
    private static void cargar(long muni, long contribuyenteId, String tributo, String importe) {
        TenantContext.fijar(new MunicipalidadId(muni));
        try {
            registrarAsiento.asentar(
                    Asiento.nuevo(
                            EJERCICIO,
                            contribuyenteId,
                            tributo,
                            Concepto.INSOLUTO,
                            TipoAsiento.CARGO,
                            Fase.ORDINARIA,
                            null,
                            null,
                            null,
                            null,
                            Dinero.de(importe),
                            HOY.minusMonths(1),
                            "SIEMBRA-" + CONTADOR.incrementAndGet()),
                    Observacion.de("Siembra de la prueba del portal del ciudadano"));
        } finally {
            TenantContext.limpiar();
        }
    }

    private static int accesosEn(long muni, String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            String sql =
                    "SELECT count(*) FROM auditoria WHERE operacion = 'ACCESO'"
                            + " AND tabla = 'contribuyente'"
                            + (codigo == null ? "" : " AND clave = ?");
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                if (codigo != null) {
                    sentencia.setString(1, "contribuyente=" + codigo);
                }
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getInt(1);
                }
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo contar la auditoria", noSePudo);
        }
    }

    private static long crearContribuyente(long muni, String codigo, DocumentoIdentidad documento) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id,"
                                    + " codigo_contribuyente, tipo_documento, numero_documento,"
                                    + " tipo_persona, nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, ?, ?, 'NATURAL',"
                                    + " 'CIUDADANO DE LA PRUEBA', 'siembra') RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento.tipo().name());
                sentencia.setString(4, documento.numero());
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo sembrar el contribuyente", noSePudo);
        }
    }

    private static long crearPredio(long muni, String codigo) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, muni);
                sentencia.setString(2, codigo);
                sentencia.setString(3, "AV. GRAU " + CONTADOR.incrementAndGet());
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo sembrar el predio", noSePudo);
        }
    }

    private static void crearTitularidad(
            long muni, long predioId, long contribuyenteId, String condicion, String porcentaje) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, 'SIEMBRA')")) {
                sentencia.setLong(1, muni);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setString(4, condicion);
                sentencia.setBigDecimal(5, new BigDecimal(porcentaje));
                sentencia.setObject(6, HOY.minusYears(1));
                sentencia.executeUpdate();
            }
            owner.commit();
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo sembrar la titularidad", noSePudo);
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
}
