package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.PendienteDeUnTributo;
import kamayuk.rentas.cuentacorriente.aplicacion.CarteraDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ReconstruirSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Agregacion;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeudaPorContribuyente;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #639 — la cartera del panel y {@code consulta_deuda} publican la misma magnitud y la publican
 * igual, contra PostgreSQL de verdad.
 *
 * <h2>Que midio la investigacion antes de tocar codigo</h2>
 *
 * <p>El issue reportaba una diferencia de <b>S/ 2 441,55</b>, entera en PREDIAL, entre el indicador
 * «Cartera pendiente» del panel (S/ 13 783,75) y la suma de {@code GET /consultas/deuda} sobre los
 * 16 contribuyentes del padron de demostracion (S/ 11 342,20), y daba tres hipotesis sin poder
 * distinguirlas. Sembrado el libro de {@code infra/carga-de-datos/ejemplos/deuda.csv} contra
 * PostgreSQL 16 y medidas las dos consultas, las cifras salen <b>identicas a las del issue</b> y la
 * diferencia es una sola cosa:
 *
 * <ul>
 *   <li><b>La hipotesis del titulo es la correcta, y es la unica.</b> Los 2 441,55 son las siete
 *       cuotas de PREDIAL con {@code fecha_valor = 2026-11-30} —la cuarta, que al 1 de setiembre de
 *       2026 <b>todavia no vence</b>—: 148,30 + 62,75 + 45,10 + 97,60 + 412,85 + 631,40 + 1 043,55.
 *       {@code CalculoDeDeuda#deudaActualizadaA} descarta todo asiento posterior al corte (regla
 *       9); {@code saldo_proyectado} <b>no puede</b> hacerlo, porque netea la obligacion entera y
 *       no tiene ninguna columna con la fecha valor de sus asientos.
 *   <li><b>La primera hipotesis del cuerpo —la que mas preocupaba— es falsa, y ademas es
 *       imposible.</b> No hay «deuda con un {@code codContribuyente} que el padron no lista»: cada
 *       centimo de la diferencia esta explicado por las cuotas de arriba, y un asiento con un
 *       contribuyente que no existe <b>no se puede escribir</b> —{@code asiento_contribuyente_fk} y
 *       {@code saldo_contribuyente_fk} (V2)—. Lo que si puede existir es deuda de un contribuyente
 *       <b>dado de baja</b>, y a esa se llega: {@code GET /rentas/contribuyentes} lo lista ({@code
 *       soloActivos = false}) y {@code contribuyentePorCodigo} lo resuelve sin mirar {@code
 *       activo}. Las dos cosas se ejercen aqui (AC 3, AC 4).
 * </ul>
 *
 * <h2>Cual es la fuente (AC 2)</h2>
 *
 * <p><b>El libro, con la regla de {@code CalculoDeDeuda}: netear {@code INSOLUTO} hasta la fecha de
 * corte.</b> {@code consulta_deuda} la aplica obligacion por obligacion en Java; la cartera la
 * aplica al padron entero en SQL, porque un agregado de decenas de miles de filas no se puede traer
 * a memoria en cada carga de la pantalla de inicio (AC 4 de #56). Son dos escrituras de la misma
 * regla —como el {@code CASE} del «Estado» de la infraccion administrativa (#397) o el de omisos
 * (#545)—, y lo que impide que vuelvan a divergir es <b>esta prueba</b>: compara las dos sobre el
 * mismo libro sembrado, obligacion por obligacion y al centimo.
 *
 * <p>Se compara el <b>insoluto</b> y no el total, y eso es deliberado: la cartera publica el
 * principal pendiente y {@code consulta_deuda} publica ademas el reajuste y el interes proyectados.
 * Hoy las dos cifras coinciden porque la unica {@code PoliticaDeMora} implementada no acumula nada
 * (D-02a), asi que comparar totales pasaria en verde <b>por un motivo que dejaria de ser cierto</b>
 * el dia que se cierre esa decision.
 *
 * <p>Se conecta como {@code kamayuk_app}, no como el superusuario ni como {@code kamayuk_owner}:
 * con {@code FORCE ROW LEVEL SECURITY} el dueno tambien queda sujeto a la politica, asi que esa
 * mutacion pasaria en verde sin demostrar nada (#537, #545, #601).
 */
@DisplayName("#639 — La cartera del panel cuadra con consulta_deuda")
class CarteraCuadraConLaConsultaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Ejercicio SIGUIENTE = new Ejercicio(2027);

    /**
     * La fecha de corte de la medicion, elegida para que la cuarta cuota del PREDIAL —vencimiento
     * 2026-11-30— <b>todavia no haya vencido</b>: es exactamente el escenario de #639.
     */
    private static final LocalDate CORTE = LocalDate.of(2026, 9, 1);

    /** Un corte del ejercicio siguiente, para medir el filtro de ejercicio por separado. */
    private static final LocalDate CORTE_2027 = LocalDate.of(2027, 6, 30);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static CarteraDelLibroCuentaCorriente cartera;
    private static ConsultarDeuda consulta;
    private static JdbcClient jdbc;

    /** Los codigos del padron sembrado, en el orden en que se siembran. */
    private static final List<String> PADRON =
            List.of("C-0001", "C-0002", "C-0003", "C-0009", "C-0010");

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("230211", "Municipalidad de la cartera A");
        municipalidadB = crearMunicipalidad("230212", "Municipalidad de la cartera B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        cartera = new CarteraDelLibroCuentaCorriente(asientos);
        consulta =
                new ConsultarDeuda(
                        asientos,
                        saldos,
                        new CalculoDeDeuda(new SinAcumulacion()),
                        new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                        Clock.fixed(
                                CORTE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        OrigenContext.fijar(new Origen("cartera.pruebas", null, null));
        sembrar(municipalidadA, new ReconstruirSaldo(asientos, saldos, Clock.systemUTC()));
        sembrar(municipalidadB, new ReconstruirSaldo(asientos, saldos, Clock.systemUTC()));
        OrigenContext.limpiar();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  AC 1 y AC 5 — las dos lecturas dan la misma cifra, y lo dicen con las dos
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — las dos lecturas dan la misma cifra")
    class LasDosDanLaMismaCifra {

        @Test
        @DisplayName("el total de la cartera es la suma de consulta_deuda sobre todo el padron")
        void elTotalCuadra() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Dinero delPanel = carteraDe(EJERCICIO, CORTE).total();
            Dinero deLaConsulta = sumaDeLaConsulta(EJERCICIO, CORTE);

            // AC 5: la asercion dice LAS DOS CIFRAS, no un booleano. Si alguna de las dos
            // consultas pierde la condicion que las alinea, el mensaje trae los dos numeros
            // y su diferencia, que es lo unico con lo que se puede diagnosticar.
            assertThat(delPanel)
                    .as(
                            "cartera del panel %s vs suma de consulta_deuda %s (diferencia %s)",
                            delPanel, deLaConsulta, delPanel.menos(deLaConsulta))
                    .isEqualTo(deLaConsulta);
            assertThat(delPanel).isEqualTo(Dinero.de("1099.90"));
        }

        @Test
        @DisplayName("y cuadran tributo a tributo, no solo en el total")
        void cuadranTributoATributo() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Map<String, Dinero> delPanel = new LinkedHashMap<>();
            for (PendienteDeUnTributo linea : carteraDe(EJERCICIO, CORTE).lineas()) {
                delPanel.put(linea.tributo(), linea.pendiente());
            }

            Map<String, Dinero> deLaConsulta = new LinkedHashMap<>();
            for (ObligacionConDeuda obligacion : obligacionesDelPadron(EJERCICIO, CORTE)) {
                if (obligacion.deuda().insoluto().esPositivo()) {
                    deLaConsulta.merge(
                            obligacion.tributo(), obligacion.deuda().insoluto(), Dinero::mas);
                }
            }

            // Un total que cuadra puede esconder dos tributos que se compensan: el panel se
            // lee por tributo y ahi es donde se ve la diferencia (el issue la encontro asi).
            assertThat(delPanel)
                    .as("panel %s vs consulta %s", delPanel, deLaConsulta)
                    .isEqualTo(deLaConsulta);
            assertThat(delPanel)
                    .containsExactly(
                            Map.entry("ARBITRIO", Dinero.de("73.00")),
                            Map.entry("PREDIAL", Dinero.de("644.90")),
                            Map.entry("VEHICULAR", Dinero.de("382.00")));
        }

        @Test
        @DisplayName("y la cartera cuenta obligaciones, no cuotas")
        void cuentaObligacionesYNoCuotas() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            // El PREDIAL de C-0001 son tres cuotas vencidas y UNA obligacion. Antes de #639
            // la cartera contaba filas de `saldo_proyectado` —una por cuota— y la nota del
            // panel decia «53 obligaciones» de un padron que tiene 21: la palabra prometia
            // algo que la cifra no era.
            assertThat(carteraDe(EJERCICIO, CORTE).obligaciones())
                    .isEqualTo(obligacionesConDeudaDelPadron(EJERCICIO, CORTE));
            assertThat(carteraDe(EJERCICIO, CORTE).obligaciones()).isEqualTo(4);
        }

        @Test
        @DisplayName("#639 — la cuota que aun no vence es la diferencia entera")
        void laCuotaQueAunNoVenceEsLaDiferencia() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Dinero conElCorte = carteraDe(EJERCICIO, CORTE).total();
            Dinero sinElCorte = carteraDe(EJERCICIO, LocalDate.of(2026, 12, 31)).total();

            // Es el defecto del issue reproducido al centimo: sin la fecha de corte, la
            // cartera incluye la cuota con fecha valor 2026-11-30 y sale mas alta que
            // cualquier cifra que la ventanilla pueda cobrar hoy.
            assertThat(sinElCorte.menos(conElCorte))
                    .as("al 31/12 la cartera es %s y al %s es %s", sinElCorte, CORTE, conElCorte)
                    .isEqualTo(Dinero.de("148.30"));
        }
    }

    // ------------------------------------------------------------------
    //  AC 3 y AC 4 — la deuda que el padron no lista
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 3 y AC 4 — a toda la deuda de la cartera se llega por su codigo")
    class SeLlegaATodaLaDeuda {

        @Test
        @DisplayName("la deuda de un contribuyente dado de baja se cuenta y se puede abrir")
        void laDeudaDeUnoDeBajaSeCuentaYSeAbre() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            // C-0009 esta con activo = false y debe 200,00. Entra en la cartera —una baja
            // del padron no extingue la deuda— y `consulta_deuda` la resuelve por su codigo,
            // porque `contribuyentePorCodigo` no mira `activo`. Si lo mirara, esos 200,00
            // seguirian contando en el total de la municipalidad y no se podrian abrir desde
            // ninguna pantalla: dinero que nadie puede cobrar.
            Dinero suya = sumaDeLaConsultaDe("C-0009", EJERCICIO, CORTE);

            assertThat(suya).isEqualTo(Dinero.de("200.00"));
            assertThat(estaActivo("C-0009")).as("y esta dado de baja de verdad").isFalse();
            assertThat(carteraDe(EJERCICIO, CORTE).total())
                    .as("los 200,00 estan dentro del total del panel")
                    .isEqualTo(sumaDeLaConsulta(EJERCICIO, CORTE));
        }

        @Test
        @DisplayName("un asiento de un contribuyente que no existe no se puede escribir")
        void unAsientoHuerfanoNoSePuedeEscribir() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            // La hipotesis que mas preocupaba al issue —«un codContribuyente que no existe en
            // contribuyente»— no es que no se de: es que no se puede dar. Lo garantiza
            // `asiento_contribuyente_fk` (V2), y se ejerce por SQL directo y no por el caso
            // de uso, porque lo que se mide es la restriccion y no la guarda de Java.
            assertThatThrownBy(
                            () ->
                                    transaccion.executeWithoutResult(
                                            estado ->
                                                    jdbc.sql(
                                                                    "INSERT INTO"
                                                                            + " cuenta_corriente_asiento"
                                                                            + " (municipalidad_id,"
                                                                            + " ejercicio,"
                                                                            + " contribuyente_id,"
                                                                            + " tributo, concepto,"
                                                                            + " tipo, monto,"
                                                                            + " fecha_valor,"
                                                                            + " documento_origen,"
                                                                            + " usuario_id) VALUES"
                                                                            + " (current_setting('app.municipalidad_id')::bigint,"
                                                                            + " 2026, 987654321,"
                                                                            + " 'PREDIAL', 'INSOLUTO',"
                                                                            + " 'CARGO', 100.00, DATE"
                                                                            + " '2026-03-01', 'HUERFANO',"
                                                                            + " 'prueba')")
                                                            .update()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------
    //  AC 6 — el contraste: cada contribuyente sigue viendo solo lo suyo
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 6 — cada contribuyente ve solo lo suyo")
    class CadaUnoVeSoloLoSuyo {

        @Test
        @DisplayName("la consulta de uno no trae ni una obligacion de otro")
        void laConsultaDeUnoNoTraeLaDeOtro() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            List<ObligacionConDeuda> deC0001 = obligacionesDe("C-0001", CORTE);
            List<ObligacionConDeuda> deC0002 = obligacionesDe("C-0002", CORTE);

            // Hacer que la consulta devuelva tambien lo ajeno dejaria el AC 1 en verde —el
            // importe de una obligacion que no es suya sale en cero, porque `filaDe` resuelve
            // los asientos por el codigo del criterio— y romperia el aislamiento por
            // contribuyente sin mover una sola cifra. Lo unico que lo caza es mirar QUE
            // obligaciones lista cada uno.
            assertThat(deC0001)
                    .extracting(ObligacionConDeuda::tributo)
                    .as("C-0001 solo tiene PREDIAL, de 2026 y de 2027")
                    .containsOnly("PREDIAL");
            assertThat(deC0001).hasSize(2);
            assertThat(deC0002).extracting(ObligacionConDeuda::tributo).containsExactly("ARBITRIO");
            assertThat(deC0002)
                    .singleElement()
                    .satisfies(
                            obligacion -> {
                                assertThat(obligacion.predioId()).isEqualTo(101L);
                                assertThat(obligacion.deuda().insoluto())
                                        .isEqualTo(Dinero.de("73.00"));
                            });
        }

        @Test
        @DisplayName("y la suma del padron no cuenta dos veces ninguna obligacion")
        void nadieCuentaDosVeces() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            List<String> claves = new ArrayList<>();
            for (String codigo : PADRON) {
                for (ObligacionConDeuda obligacion : obligacionesDe(codigo, CORTE)) {
                    claves.add(
                            codigo
                                    + "/"
                                    + obligacion.tributo()
                                    + "/"
                                    + obligacion.ejercicio()
                                    + "/"
                                    + obligacion.predioId()
                                    + "/"
                                    + obligacion.vehiculoId());
                }
            }

            assertThat(claves).doesNotHaveDuplicates();
            assertThat(claves).hasSize(6);
        }
    }

    // ------------------------------------------------------------------
    //  Lo demas que separaba a las dos, medido por separado
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Las otras tres condiciones que las alinean")
    class LasOtrasCondiciones {

        @Test
        @DisplayName("el ejercicio: la cartera de 2027 no arrastra el libro de 2026")
        void elEjercicioAcota() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            Dinero delPanel = carteraDe(SIGUIENTE, CORTE_2027).total();
            Dinero deLaConsulta = sumaDeLaConsulta(SIGUIENTE, CORTE_2027);

            // Al 30/06/2027 todo el libro de 2026 esta vencido, asi que sin el filtro de
            // ejercicio la cartera de 2027 se llevaria por delante el ejercicio anterior
            // entero y seguiria pareciendo una cifra razonable.
            assertThat(delPanel)
                    .as("cartera 2027 %s vs suma de consulta_deuda %s", delPanel, deLaConsulta)
                    .isEqualTo(deLaConsulta);
            assertThat(delPanel).isEqualTo(Dinero.de("500.00"));
        }

        @Test
        @DisplayName("la fase: la obligacion con una cuota en VALOR entra entera en las dos")
        void laFaseNoDejaFueraNingunaCuota() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            // El ARBITRIOS de C-0002 tiene la cuota 1 en ORDINARIA y la 2 en VALOR. Ninguna
            // de las dos lecturas filtra por fase, y por eso las dos dicen 73,00: acotar por
            // una sola fase dejaria fuera 36,50 de deuda viva en un lado y no en el otro.
            assertThat(pendienteDe("ARBITRIO", EJERCICIO, CORTE)).isEqualTo(Dinero.de("73.00"));
            assertThat(sumaDeLaConsultaDe("C-0002", EJERCICIO, CORTE))
                    .isEqualTo(Dinero.de("73.00"));
        }

        @Test
        @DisplayName("el pago en exceso no es cartera, y la consulta si lo enseña")
        void elPagoEnExcesoNoEsCartera() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            // C-0010 pago 150,00 de una cuota de 100,00: su obligacion neta −50,00. La
            // cartera no la cuenta —restarla taparia con el saldo a favor de uno la deuda de
            // otro (#56)— y `consulta_deuda` la publica, porque es un hecho de su libro. Es
            // la UNICA diferencia que queda entre las dos, y por eso la suma del AC 1 se hace
            // sobre las obligaciones con insoluto positivo.
            assertThat(sumaDeLaConsultaDe("C-0010", EJERCICIO, CORTE))
                    .as("la suma positiva no lo cuenta")
                    .isEqualTo(Dinero.CERO);
            assertThat(obligacionesDe("C-0010", CORTE))
                    .singleElement()
                    .satisfies(
                            obligacion ->
                                    assertThat(obligacion.deuda().insoluto())
                                            .as("y la ventanilla lo ve, con su signo")
                                            .isEqualTo(Dinero.de("-10.00")));
            assertThat(carteraDe(EJERCICIO, CORTE).total())
                    .as(
                            "la cartera no baja por el saldo a favor de C-0010, y tampoco sube:"
                                    + " agrupada por CUOTA contaria los 40,00 de la segunda y dejaria"
                                    + " fuera los −50,00 de la primera")
                    .isEqualTo(Dinero.de("1099.90"));
        }
    }

    // ------------------------------------------------------------------
    //  El aislamiento, que es lo que sostiene todo lo demas
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Aislamiento — la cartera de A no suma nada de B")
    class AislamientoEntreMunicipalidades {

        @Test
        @DisplayName("las dos municipalidades sembradas igual dan cada una su propia cifra")
        void cadaUnaVeLaSuya() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Dinero deA = carteraDe(EJERCICIO, CORTE).total();

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            Dinero deB = carteraDe(EJERCICIO, CORTE).total();

            assertThat(deA).isEqualTo(Dinero.de("1099.90"));
            assertThat(deB).isEqualTo(Dinero.de("1099.90"));
            assertThat(deA.mas(deB))
                    .as("las dos juntas son 2199,80: cada panel ve 1099,90, no el doble")
                    .isEqualTo(Dinero.de("2199.80"));
        }

        @Test
        @DisplayName("y el aislamiento lo pone la politica, no el rol con el que se conecta")
        void elSuperusuarioVeLasDos() throws SQLException {
            // El centinela de #545: si un cambio de fixture devolviera la conexion al
            // superusuario del cluster —el unico que omite RLS con FORCE ROW LEVEL SECURITY,
            // que `kamayuk_owner` no omite—, cada cifra de esta clase saldria al doble y ninguna
            // pareceria mal.
            try (Connection admin = base.conexionAdmin();
                    PreparedStatement sentencia =
                            admin.prepareStatement(
                                    "SELECT count(DISTINCT municipalidad_id)"
                                            + " FROM cuenta_corriente_asiento"
                                            + " WHERE tributo = 'PREDIAL'")) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    assertThat(fila.getLong(1)).isEqualTo(2);
                }
            }

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Long municipalidades =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(DISTINCT municipalidad_id)"
                                                            + " FROM cuenta_corriente_asiento"
                                                            + " WHERE tributo = 'PREDIAL'")
                                            .query(Long.class)
                                            .single());
            assertThat(municipalidades).isEqualTo(1);

            String rol =
                    transaccion.execute(
                            estado -> jdbc.sql("SELECT current_user").query(String.class).single());
            assertThat(rol)
                    .as("y se mide con el rol de la aplicacion, no con el dueno de las tablas")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }
    }

    // ------------------------------------------------------------------
    //  Lo que las dos lecturas contestan, cada una por su camino
    // ------------------------------------------------------------------

    private static CarteraPendiente carteraDe(Ejercicio ejercicio, LocalDate aLaFecha) {
        return transaccion.execute(estado -> cartera.pendientePorTributo(ejercicio, aLaFecha));
    }

    private static Dinero pendienteDe(String tributo, Ejercicio ejercicio, LocalDate aLaFecha) {
        return carteraDe(ejercicio, aLaFecha).lineas().stream()
                .filter(linea -> linea.tributo().equals(tributo))
                .map(PendienteDeUnTributo::pendiente)
                .findFirst()
                .orElse(Dinero.CERO);
    }

    /** La suma de {@code consulta_deuda} sobre todo el padron, recorriendo sus paginas. */
    private static Dinero sumaDeLaConsulta(Ejercicio ejercicio, LocalDate aLaFecha) {
        Dinero total = Dinero.CERO;
        for (String codigo : PADRON) {
            total = total.mas(sumaDeLaConsultaDe(codigo, ejercicio, aLaFecha));
        }
        return total;
    }

    private static Dinero sumaDeLaConsultaDe(
            String codigo, Ejercicio ejercicio, LocalDate aLaFecha) {
        Dinero total = Dinero.CERO;
        for (ObligacionConDeuda obligacion : obligacionesDe(codigo, aLaFecha)) {
            if (obligacion.ejercicio().equals(ejercicio)
                    && obligacion.deuda().insoluto().esPositivo()) {
                total = total.mas(obligacion.deuda().insoluto());
            }
        }
        return total;
    }

    private static List<ObligacionConDeuda> obligacionesDelPadron(
            Ejercicio ejercicio, LocalDate aLaFecha) {
        List<ObligacionConDeuda> todas = new ArrayList<>();
        for (String codigo : PADRON) {
            for (ObligacionConDeuda obligacion : obligacionesDe(codigo, aLaFecha)) {
                if (obligacion.ejercicio().equals(ejercicio)) {
                    todas.add(obligacion);
                }
            }
        }
        return todas;
    }

    private static long obligacionesConDeudaDelPadron(Ejercicio ejercicio, LocalDate aLaFecha) {
        return obligacionesDelPadron(ejercicio, aLaFecha).stream()
                .filter(obligacion -> obligacion.deuda().insoluto().esPositivo())
                .count();
    }

    /**
     * Todas las obligaciones del contribuyente, recorriendo las paginas de dos en dos.
     *
     * <p>De dos en dos a proposito: sumar sobre la <b>pagina devuelta</b> en vez de sobre todas las
     * obligaciones es como #25 dejo el resumen de la consulta unificada en la cuarta parte de la
     * deuda, y aqui produciria exactamente el desajuste que este issue investiga.
     */
    private static List<ObligacionConDeuda> obligacionesDe(String codigo, LocalDate aLaFecha) {
        List<ObligacionConDeuda> todas = new ArrayList<>();
        int pagina = 0;
        while (true) {
            final int actual = pagina;
            Pagina<ObligacionConDeuda> leida =
                    transaccion.execute(
                            estado ->
                                    consulta.porContribuyente(
                                            new CriterioDeDeudaPorContribuyente(
                                                    codigo,
                                                    aLaFecha,
                                                    null,
                                                    // Esta prueba cuadra la cartera contra la
                                                    // consulta POR OBLIGACION, que es lo que la
                                                    // lectura devolvia -y sigue devolviendo- sin
                                                    // `porPeriodo` (#551).
                                                    Agregacion.POR_OBLIGACION),
                                            new Paginacion(
                                                    actual,
                                                    2,
                                                    "ejercicio",
                                                    Paginacion.Direccion.ASCENDENTE)));
            todas.addAll(leida.contenido());
            if (todas.size() >= leida.totalElementos() || leida.contenido().isEmpty()) {
                return todas;
            }
            pagina++;
        }
    }

    private static boolean estaActivo(String codigo) {
        return Boolean.TRUE.equals(
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT activo FROM contribuyente"
                                                        + " WHERE codigo_contribuyente = :codigo")
                                        .param("codigo", codigo)
                                        .query(Boolean.class)
                                        .single()));
    }

    // ------------------------------------------------------------------
    //  Siembra: el libro de la municipalidad de demostracion, en pequeño
    // ------------------------------------------------------------------

    /**
     * El mismo libro en las dos municipalidades: es lo que hace que una fuga se vea.
     *
     * <p>La forma sale de {@code infra/carga-de-datos/ejemplos/deuda.csv}, que es lo que el issue
     * midio: el PREDIAL en cuatro cuotas trimestrales con la ultima venciendo el 30 de noviembre,
     * los arbitrios por predio, el vehicular como obligacion anual sin periodo. A eso se le suman
     * los dos casos que el AC 4 pide y el pago en exceso.
     */
    private static void sembrar(long municipalidadId, ReconstruirSaldo reconstruir) {
        TenantContext.fijar(new MunicipalidadId(municipalidadId));

        long c1 = crearContribuyente(municipalidadId, "C-0001", "50211001", true);
        long c2 = crearContribuyente(municipalidadId, "C-0002", "50211002", true);
        long c3 = crearContribuyente(municipalidadId, "C-0003", "50211003", true);
        long c9 = crearContribuyente(municipalidadId, "C-0009", "50211009", false);
        long c10 = crearContribuyente(municipalidadId, "C-0010", "50211010", true);

        transaccion.executeWithoutResult(
                estado -> {
                    // C-0001: cuatro cuotas de PREDIAL. La cuarta vence el 30 de noviembre y
                    // al corte del 1 de setiembre TODAVIA NO ES DEUDA: es el caso de #639.
                    cargo(c1, EJERCICIO, "PREDIAL", 1, "148.30", LocalDate.of(2026, 2, 28));
                    cargo(c1, EJERCICIO, "PREDIAL", 2, "148.30", LocalDate.of(2026, 5, 31));
                    cargo(c1, EJERCICIO, "PREDIAL", 3, "148.30", LocalDate.of(2026, 8, 31));
                    cargo(c1, EJERCICIO, "PREDIAL", 4, "148.30", LocalDate.of(2026, 11, 30));
                    // Y una obligacion del ejercicio siguiente, para medir ese filtro.
                    cargo(c1, SIGUIENTE, "PREDIAL", 1, "500.00", LocalDate.of(2027, 2, 28));

                    // C-0002: arbitrios de un predio, con la segunda cuota ya en fase VALOR.
                    asientos.registrar(
                            asiento(
                                    c2,
                                    EJERCICIO,
                                    "ARBITRIO",
                                    Concepto.INSOLUTO,
                                    TipoAsiento.CARGO,
                                    Fase.ORDINARIA,
                                    1,
                                    101L,
                                    null,
                                    "36.50",
                                    LocalDate.of(2026, 1, 31)));
                    asientos.registrar(
                            asiento(
                                    c2,
                                    EJERCICIO,
                                    "ARBITRIO",
                                    Concepto.INSOLUTO,
                                    TipoAsiento.CARGO,
                                    Fase.VALOR,
                                    2,
                                    101L,
                                    null,
                                    "36.50",
                                    LocalDate.of(2026, 2, 28)));

                    // C-0003: el vehicular es anual —sin periodo— y cuelga de una placa.
                    asientos.registrar(
                            asiento(
                                    c3,
                                    EJERCICIO,
                                    "VEHICULAR",
                                    Concepto.INSOLUTO,
                                    TipoAsiento.CARGO,
                                    Fase.ORDINARIA,
                                    null,
                                    null,
                                    201L,
                                    "382.00",
                                    LocalDate.of(2026, 2, 28)));

                    // C-0009: dado de baja del padron, y con deuda viva (AC 4).
                    cargo(c9, EJERCICIO, "PREDIAL", 1, "200.00", LocalDate.of(2026, 3, 31));

                    // C-0010: pago en exceso. Pago 150,00 de una cuota de 100,00 y debe 40,00
                    // de la siguiente, asi que su OBLIGACION neta −10,00 y su CUOTA 2 neta
                    // +40,00. Es lo que separa agrupar por obligacion de agrupar por cuota, que
                    // es como la cartera contaba hasta #639.
                    cargo(c10, EJERCICIO, "PREDIAL", 1, "100.00", LocalDate.of(2026, 2, 28));
                    cargo(c10, EJERCICIO, "PREDIAL", 2, "40.00", LocalDate.of(2026, 6, 30));
                    asientos.registrar(
                            asiento(
                                    c10,
                                    EJERCICIO,
                                    "PREDIAL",
                                    Concepto.INSOLUTO,
                                    TipoAsiento.ABONO,
                                    Fase.ORDINARIA,
                                    1,
                                    null,
                                    null,
                                    "150.00",
                                    LocalDate.of(2026, 3, 31)));
                });

        // La proyeccion se rehace con el codigo de produccion: `consulta_deuda` la usa como
        // indice para descubrir que obligaciones tiene el contribuyente (#23).
        for (long titular : new long[] {c1, c2, c3, c9, c10}) {
            transaccion.executeWithoutResult(estado -> reconstruir.deContribuyente(titular));
        }
        TenantContext.limpiar();
    }

    private static void cargo(
            long titular,
            Ejercicio ejercicio,
            String tributo,
            Integer periodo,
            String monto,
            LocalDate fechaValor) {
        asientos.registrar(
                asiento(
                        titular,
                        ejercicio,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        periodo,
                        null,
                        null,
                        monto,
                        fechaValor));
    }

    private static Asiento asiento(
            long titular,
            Ejercicio ejercicio,
            String tributo,
            Concepto concepto,
            TipoAsiento tipo,
            Fase fase,
            Integer periodo,
            Long predioId,
            Long vehiculoId,
            String monto,
            LocalDate fechaValor) {
        return Asiento.nuevo(
                        ejercicio,
                        titular,
                        tributo,
                        concepto,
                        tipo,
                        fase,
                        periodo,
                        predioId,
                        vehiculoId,
                        null,
                        Dinero.de(monto),
                        fechaValor,
                        "EM-" + ejercicio + "-0001")
                .conMotivo("siembra de #639");
    }

    // ------------------------------------------------------------------

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

    private static long crearContribuyente(
            long municipalidadId, String codigo, String dni, boolean activo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, activo, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, CARTERA', ?,"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.setBoolean(4, activo);
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
}
