package kamayuk.rentas.tesoreria.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.tesoreria.SolicitudDeConvenioCoactivo;
import kamayuk.rentas.tesoreria.dobles.AcogimientoDeMentira;
import kamayuk.rentas.tesoreria.dobles.ConveniosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Cronograma;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #562 — La frontera del modulo traduce «falta publicar una cifra» antes de que cruce.
 *
 * <p>{@code POST /coactiva/convenios} contestaba <b>500 {@code ERROR_INTERNO} con identificador de
 * incidencia</b> cuando el ejercicio del convenio no tenia conjunto sellado, o cuando el conjunto
 * no traia el interes, el maximo de cuotas o la politica de redondeo de la cuota. Con D-02a y D-03c
 * abiertas ese es el estado <i>normal</i> del sistema, asi que el fraccionamiento coactivo entero
 * era inalcanzable y cada intento dejaba una incidencia en el registro de errores del servidor.
 *
 * <p><b>La traduccion tiene que vivir aqui y no en el controlador de coactiva.</b> Las seis
 * excepciones que lo dicen viven en {@code tesoreria.aplicacion} y en {@code parametros}; las de
 * tesoreria estan en un subpaquete, asi que {@code coactiva} no las puede nombrar sin depender de
 * un tipo no expuesto —Spring Modulith lo rechaza, y #51 lo midio—. Es el mismo motivo por el que
 * {@code RegistrarPreconvenio.SinDeudaQueFraccionar} ya salia como {@code
 * SinDeudaCoactivaQueFraccionar} desde #42.
 *
 * <p>Esta prueba mide el puerto <b>real</b>. La de capa web de coactiva usa un doble del puerto, y
 * un doble puede prometer cualquier cosa: sin esto, nada garantizaria que el adaptador de verdad
 * lanza lo que aquel finge.
 */
@DisplayName("#562 — El puerto de fraccionamiento coactivo traduce lo que falta publicar")
class FraccionamientoCoactivoTesoreriaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final Ejercicio SELLADO = new Ejercicio(2026);

    /** El ejercicio cuyo conjunto nadie ha sellado todavia (D-02a). */
    private static final int SIN_SELLAR = 2027;

    /** El ejercicio sellado que no parametriza ningun punto de redondeo (D-03c). */
    private static final int SIN_REDONDEO = 2028;

    /** El ejercicio sellado que no trae el interes de fraccionamiento. */
    private static final int SIN_INTERES = 2029;

    /**
     * El ejercicio sellado que observa un punto de redondeo, y no el de la cuota (#633).
     *
     * <p>#562 puso {@code PoliticasDeRedondeo.PuntoSinPolitica} en el {@code catch} de este puerto
     * y <b>no la sembro</b>: el escenario que la produce no es {@link #SIN_REDONDEO} —ahi falla el
     * lector, antes de preguntar por ningun punto— sino este.
     */
    private static final int SIN_EL_PUNTO_DE_LA_CUOTA = 2030;

    private static final SeleccionDeObligacion PREDIAL =
            new SeleccionDeObligacion("PREDIAL", SELLADO, null, null);

    private final AcogimientoDeMentira libro =
            new AcogimientoDeMentira().con(PREDIAL, "COACTIVA", Dinero.de("500.00"), HOY);

    @Test
    @DisplayName("con todo publicado, el cronograma sale y el puerto no traduce nada")
    void conTodoPublicadoSaleElCronograma() {
        assertThat(puerto().simular(solicitud(2026)).cronograma()).isNotEmpty();
    }

    @Test
    @DisplayName("sin conjunto sellado sale CondicionesSinPublicar, nombrando el ejercicio")
    void sinConjuntoSelladoTraduce() {
        assertThatThrownBy(() -> puerto().simular(solicitud(SIN_SELLAR)))
                .as(
                        "sin esta traduccion la excepcion cruza el limite del modulo y coactiva no"
                                + " la puede nombrar: la unica salida seria seguir en 500")
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasMessageContaining("2027")
                .hasCauseInstanceOf(LectorDeParametros.EjercicioSinSellar.class);
    }

    @Test
    @DisplayName("y al registrar tambien: son dos caminos y los dos cruzan el limite")
    void alRegistrarTambienTraduce() {
        assertThatThrownBy(
                        () ->
                                puerto().registrar(
                                                solicitud(SIN_SELLAR),
                                                null,
                                                Observacion.de("Se registra el convenio coactivo")))
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasMessageContaining("2027");
    }

    @Test
    @DisplayName("sin la llave del interes se traduce igual, y el mensaje la nombra")
    void sinLaLlaveDelInteresNombraLaLlave() {
        assertThatThrownBy(() -> puerto().simular(solicitud(SIN_INTERES)))
                .as("hay conjunto y le falta una cifra: lo que se nombra es la llave, no el ano")
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasMessageContaining("INTERES_FRACCIONAMIENTO:ORDINARIO")
                .hasCauseInstanceOf(CondicionesParametrizadas.CondicionSinParametrizar.class);
    }

    @Test
    @DisplayName("y sin ningun punto de redondeo observado, tambien (D-03c)")
    void sinPuntosDeRedondeoTraduce() {
        assertThatThrownBy(() -> puerto().simular(solicitud(SIN_REDONDEO)))
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasCauseInstanceOf(PoliticasDeRedondeoSelladas.SinPuntosObservados.class);
    }

    @Test
    @DisplayName("#633 — y con puntos observados pero sin el de la cuota, tambien")
    void sinElPuntoDeLaCuotaTraduce() {
        assertThatThrownBy(() -> puerto().simular(solicitud(SIN_EL_PUNTO_DE_LA_CUOTA)))
                .as(
                        "#562 puso el tipo en el catch y no lo sembro: el escenario no es «sin"
                                + " ninguna fila REDONDEO» sino «sin la fila del punto que se pide»")
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasMessageContaining("REDONDEO:CUOTA")
                .hasCauseInstanceOf(PoliticasDeRedondeoSelladas.PuntoSinObservar.class);
    }

    @Test
    @DisplayName("#633 — y al registrar tambien: son dos caminos y los dos cruzan el limite")
    void sinElPuntoDeLaCuotaTraduceTambienAlRegistrar() {
        assertThatThrownBy(
                        () ->
                                puerto().registrar(
                                                solicitud(SIN_EL_PUNTO_DE_LA_CUOTA),
                                                null,
                                                Observacion.de(
                                                        "Fraccionamiento coactivo de la prueba")))
                .isInstanceOf(FraccionamientoCoactivo.CondicionesSinPublicar.class)
                .hasCauseInstanceOf(PoliticasDeRedondeoSelladas.PuntoSinObservar.class);
    }

    @Test
    @DisplayName("lo que NO es falta de una cifra sigue cruzando tal cual")
    void loQueNoEsFaltaDeCifraNoSeDisfraza() {
        AcogimientoDeMentira sinDeuda = new AcogimientoDeMentira();

        assertThatThrownBy(() -> puerto(sinDeuda).simular(solicitud(2026)))
                .as(
                        "la traduccion es de seis excepciones nombradas, no de RuntimeException: lo"
                                + " demas conserva su significado")
                .isInstanceOf(FraccionamientoCoactivo.SinDeudaCoactivaQueFraccionar.class);
    }

    @Test
    @DisplayName("#433 — mas cuotas que el maximo vigente: CondicionesInadmisibles, no un 500")
    void demasiadasCuotasSeTraduce() {
        assertThatThrownBy(() -> puerto().simular(solicitud(7L, 2026, 13, "20")))
                .as("el maximo sellado es 12: por tesoreria es 422, y por coactiva salia 500")
                .isInstanceOf(FraccionamientoCoactivo.CondicionesInadmisibles.class)
                .hasCauseInstanceOf(CondicionesDelConvenio.DemasiadasCuotas.class);
    }

    @Test
    @DisplayName("#433 — una inicial del 100 %: nada que fraccionar, y se traduce igual")
    void nadaQueFraccionarSeTraduce() {
        assertThatThrownBy(
                        () ->
                                puerto().registrar(
                                                solicitud(7L, 2026, 6, "100"),
                                                null,
                                                Observacion.de("Se registra el convenio coactivo")))
                .isInstanceOf(FraccionamientoCoactivo.CondicionesInadmisibles.class)
                .hasCauseInstanceOf(Cronograma.NadaQueFraccionar.class);
    }

    @Test
    @DisplayName("#433 — la clave de otro obligado: ClaveEnConflicto, que sale 409")
    void laClaveDeOtroSeTraduce() {
        FraccionamientoCoactivo puerto = puerto();
        Observacion porque = Observacion.de("Se registra el convenio coactivo");
        puerto.registrar(solicitud(7L, 2026, 6, "20"), "CLAVE-433", porque);

        assertThatThrownBy(
                        () -> puerto.registrar(solicitud(8L, 2026, 6, "20"), "CLAVE-433", porque))
                .as("la misma clave para otro contribuyente: por tesoreria 409, por coactiva 500")
                .isInstanceOf(FraccionamientoCoactivo.ClaveEnConflicto.class)
                .hasCauseInstanceOf(RegistrarPreconvenio.ClaveDeOtraPeticion.class);
    }

    // ------------------------------------------------------------------

    private FraccionamientoCoactivo puerto() {
        return puerto(libro);
    }

    private static FraccionamientoCoactivo puerto(AcogimientoDeMentira acogimiento) {
        return new FraccionamientoCoactivoTesoreria(
                new RegistrarPreconvenio(
                        new ConveniosEnMemoria(),
                        acogimiento,
                        new CondicionesParametrizadas(new ParametrosDeLaPrueba()),
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ));
    }

    private static SolicitudDeConvenioCoactivo solicitud(int ejercicioDelConvenio) {
        return solicitud(7L, ejercicioDelConvenio, 6, "20");
    }

    private static SolicitudDeConvenioCoactivo solicitud(
            long contribuyente, int ejercicioDelConvenio, int cuotas, String inicial) {
        LocalDate fecha = LocalDate.of(ejercicioDelConvenio, 3, 16);
        return new SolicitudDeConvenioCoactivo(
                contribuyente,
                List.of(PREDIAL),
                fecha,
                fecha,
                cuotas,
                Alicuota.de(inicial),
                fecha.plusMonths(1),
                null);
    }

    /**
     * Un lector que sella 2026, no sella 2027, sella 2028 sin ningun punto de redondeo, sella 2029
     * sin el interes de fraccionamiento y sella 2030 con un punto que no es el de la cuota (#633).
     *
     * <p>Las cinco situaciones son reales y se distinguen por el ejercicio, que es exactamente por
     * lo que {@link CondicionesParametrizadas} pregunta.
     */
    private static final class ParametrosDeLaPrueba implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            if (ejercicio.valor() == SIN_SELLAR) {
                throw new EjercicioSinSellar(ejercicio);
            }
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            if (ejercicio.valor() != SIN_INTERES) {
                constructor.numero("INTERES_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("1"));
            }
            constructor.numero(
                    "CUOTAS_MAXIMAS_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("12"));
            if (ejercicio.valor() == SIN_EL_PUNTO_DE_LA_CUOTA) {
                constructor
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                ValorNormativo.de("2"))
                        .texto(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                RoundingMode.HALF_UP.name());
            } else if (ejercicio.valor() != SIN_REDONDEO) {
                constructor
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.CUOTA.name(),
                                ValorNormativo.de("2"))
                        .texto(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.CUOTA.name(),
                                RoundingMode.HALF_UP.name());
            }
            return constructor.construir();
        }

        /**
         * El conjunto de cada ejercicio, por su identificador.
         *
         * <p>Hasta #361 los cuatro ejercicios contestaban el MISMO identificador —1— con cuatro
         * contenidos distintos, y {@code porConjunto} devolvia siempre el de {@link #SELLADO}: un
         * doble incoherente que solo funcionaba porque {@code CondicionesParametrizadas} pedia los
         * parametros por ejercicio. Desde #361 los lee por el identificador que resolvio, asi que
         * cada ejercicio tiene el suyo: 2026 sigue siendo el 1.
         */
        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(SELLADO.valor() - 1 + (int) identificador.valor()));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            if (ejercicio.valor() == SIN_SELLAR) {
                throw new EjercicioSinSellar(ejercicio);
            }
            return IdentificadorDeConjunto.de(ejercicio.valor() - SELLADO.valor() + 1L);
        }
    }
}
