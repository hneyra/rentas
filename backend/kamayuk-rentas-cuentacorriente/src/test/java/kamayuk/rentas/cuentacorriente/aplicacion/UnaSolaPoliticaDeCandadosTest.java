package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #364 — <b>el convenio y la cobranza piden los candados de las mismas obligaciones en el mismo
 * orden</b>, porque el orden es uno solo y no lo elige ningun llamador.
 *
 * <p>Hasta #364 cada escritor traia su comparador. La cobranza ordenaba las obligaciones por
 * tributo, ejercicio y unidad; el convenio ordenaba las <b>cuotas</b> por tributo, ejercicio,
 * <b>periodo</b> y unidad, y bloqueaba cada obligacion la primera vez que aparecia. Con el periodo
 * delante de la unidad, un contribuyente que debe arbitrios del predio 3 en las cuotas 4 a 6 y del
 * predio 9 en las cuotas 1 a 3 ve al convenio bloquear el 9 y despues el 3, y a la cobranza el 3 y
 * despues el 9: cada transaccion tiene el candado que la otra espera, y PostgreSQL aborta una con
 * {@code 40P01} que sale en 500.
 *
 * <p><b>La siembra es la que distingue.</b> Con los dos predios en las mismas cuotas —la muestra
 * uniforme— el primer periodo empata y desempata la unidad: los dos comparadores dan el mismo orden
 * y la prueba no ve nada.
 *
 * <p>La carrera no se provoca aqui, como en {@link OrdenDeLosCandadosDelCobroTest}: se afirma su
 * causa, la secuencia de {@link SaldoRepository#bloquear}. El libro vacio hace que el cobro termine
 * en {@code SinDeudaQueAbonar} y el convenio sin mover nada, los dos <b>despues</b> de bloquear.
 */
@DisplayName("#364 — una sola politica de candados para el convenio y la cobranza")
class UnaSolaPoliticaDeCandadosTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 9, 26);

    private static final long C = 364L;

    private static final long PREDIO_3 = 3L;

    private static final long PREDIO_9 = 9L;

    /**
     * Las cuotas del convenio: el predio 3 en las cuotas 4 a 6, el predio 9 en las 1 a 3.
     *
     * <p>En el orden en que un convenio las guarda —el de {@code deudaAcogible}, periodo delante de
     * la unidad—, y la cobranza recibe las obligaciones al reves: asi tampoco un {@code
     * bloquearEnOrden} que se limitara a deduplicar en el orden de llegada pasaria por bueno.
     */
    private static final List<DeudaAcogida> DEL_CONVENIO =
            List.of(
                    arbitrio(PREDIO_9, 1),
                    arbitrio(PREDIO_9, 2),
                    arbitrio(PREDIO_9, 3),
                    arbitrio(PREDIO_3, 4),
                    arbitrio(PREDIO_3, 5),
                    arbitrio(PREDIO_3, 6));

    @Test
    @DisplayName(
            "devolver un convenio y abonar un pago sobre el predio 3 (cuotas 4-6) y el 9 (cuotas"
                    + " 1-3) piden los mismos candados en el mismo orden")
    void devolverYCobrarBloqueanIgual() {
        List<ClaveDeObligacion> delCobro = candadosDelCobro();
        List<ClaveDeObligacion> deLaDevolucion =
                candadosDelConvenio(
                        (acogimiento) ->
                                acogimiento.devolver(
                                        C,
                                        DEL_CONVENIO,
                                        HOY,
                                        "RESOLUCION DE QUIEBRE 364",
                                        Observacion.de("prueba del orden de los candados")));

        assertThat(deLaDevolucion)
                .as(
                        "el convenio bloqueaba en el orden de las cuotas, con el periodo delante"
                                + " de la unidad: el predio 9 (cuota 1) antes que el 3 (cuota 4),"
                                + " al reves que la cobranza, y las dos transacciones se abrazan")
                .containsExactlyElementsOf(delCobro)
                .containsExactly(obligacion(PREDIO_3), obligacion(PREDIO_9));
    }

    @Test
    @DisplayName("acoger y cobrar sobre la misma siembra tambien piden los candados igual")
    void acogerYCobrarBloqueanIgual() {
        List<ClaveDeObligacion> delCobro = candadosDelCobro();
        List<ClaveDeObligacion> delAcogimiento =
                candadosDelConvenio(
                        (acogimiento) ->
                                acogimiento.acoger(
                                        C,
                                        DEL_CONVENIO,
                                        HOY,
                                        "CONVENIO 364",
                                        Observacion.de("prueba del orden de los candados")));

        assertThat(delAcogimiento)
                .as("acoger es el mismo motor que devolver, y el cruce con la cobranza el mismo")
                .containsExactlyElementsOf(delCobro);
    }

    @Test
    @DisplayName(
            "bloquearEnOrden pide cada obligacion una vez y en el mismo orden, llegue como llegue")
    void laPoliticaNoDependeDeLaLlegada() {
        SaldosQueAnotanLosCandados alDerecho = new SaldosQueAnotanLosCandados();
        SaldosQueAnotanLosCandados alReves = new SaldosQueAnotanLosCandados();

        alDerecho.bloquearEnOrden(
                List.of(obligacion(PREDIO_3), obligacion(PREDIO_9), obligacion(PREDIO_3)));
        alReves.bloquearEnOrden(
                List.of(obligacion(PREDIO_9), obligacion(PREDIO_3), obligacion(PREDIO_9)));

        assertThat(alReves.bloqueados)
                .as("el orden lo pone la politica, no quien llama: sin repetidas y el mismo")
                .containsExactly(obligacion(PREDIO_3), obligacion(PREDIO_9))
                .containsExactlyElementsOf(alDerecho.bloqueados);
    }

    // ------------------------------------------------------------------

    /** Lo que pide la cobranza que llega del buzon con las dos obligaciones. */
    private static List<ClaveDeObligacion> candadosDelCobro() {
        SaldosQueAnotanLosCandados saldos = new SaldosQueAnotanLosCandados();
        AsientoRepository asientos = libroVacio();
        RegistroDeAbonosCuentaCorriente registro =
                new RegistroDeAbonosCuentaCorriente(
                        asientos,
                        saldos,
                        registrar(asientos, saldos),
                        new CalculoDeDeuda(new SinAcumulacion()),
                        redondeo());

        assertThatThrownBy(
                        () ->
                                registro.abonarPagoIntegro(
                                        List.of(deudorDe(PREDIO_3), deudorDe(PREDIO_9)),
                                        Dinero.de("600.00"),
                                        HOY,
                                        "RECIBO 001-364",
                                        Observacion.de("prueba del orden de los candados")))
                .isInstanceOf(RegistroDeAbonos.SinDeudaQueAbonar.class);
        return List.copyOf(saldos.bloqueados);
    }

    private interface Movimiento {
        MovimientoAsentado sobre(AcogimientoAConvenioCuentaCorriente acogimiento);
    }

    /** Lo que pide el convenio al moverse, en cualquiera de los dos sentidos. */
    private static List<ClaveDeObligacion> candadosDelConvenio(Movimiento movimiento) {
        SaldosQueAnotanLosCandados saldos = new SaldosQueAnotanLosCandados();
        AsientoRepository asientos = libroVacio();
        AcogimientoAConvenioCuentaCorriente acogimiento =
                new AcogimientoAConvenioCuentaCorriente(
                        asientos,
                        saldos,
                        registrar(asientos, saldos),
                        new CalculoDeDeuda(new SinAcumulacion()),
                        redondeo());

        // Con el libro vacio ninguna cuota tiene nada pendiente: se bloquea y no se mueve nada.
        assertThat(movimiento.sobre(acogimiento).movidas()).isEmpty();
        return List.copyOf(saldos.bloqueados);
    }

    private static DeudaAcogida arbitrio(long predio, int cuota) {
        return new DeudaAcogida(
                "ARBITRIO",
                EJERCICIO,
                cuota,
                predio,
                null,
                "CONVENIO",
                HOY,
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    private static ObligacionDelDeudor deudorDe(long predio) {
        return new ObligacionDelDeudor(
                C, new SeleccionDeObligacion("ARBITRIO", EJERCICIO, predio, null));
    }

    private static ClaveDeObligacion obligacion(long predio) {
        return new ClaveDeObligacion(C, "ARBITRIO", EJERCICIO, predio, null);
    }

    private static RegistrarAsiento registrar(AsientoRepository asientos, SaldoRepository saldos) {
        return new RegistrarAsiento(
                asientos,
                saldos,
                sinUso(Auditoria.class),
                Clock.fixed(Instant.parse("2026-09-26T14:00:00Z"), ZoneOffset.UTC));
    }

    private static PoliticaDeRedondeo redondeo() {
        return new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
    }

    /** Anota el orden de los candados y deja la proyeccion vacia. Nada mas se le pide. */
    private static final class SaldosQueAnotanLosCandados implements SaldoRepository {

        private final List<ClaveDeObligacion> bloqueados = new ArrayList<>();

        @Override
        public int bloquear(ClaveDeObligacion obligacion) {
            bloqueados.add(obligacion);
            return 0;
        }

        @Override
        public List<SaldoProyectado> deLaObligacion(ClaveDeObligacion obligacion) {
            return List.of();
        }

        @Override
        public Optional<SaldoProyectado> buscar(ClaveDeSaldo clave) {
            throw new UnsupportedOperationException("ninguno de los dos busca una cuota suelta");
        }

        @Override
        public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
            throw new UnsupportedOperationException("ninguno de los dos lee el padron entero");
        }

        @Override
        public void proyectar(SaldoProyectado saldo) {
            throw new UnsupportedOperationException("con el libro vacio no se proyecta nada");
        }
    }

    /** Un libro sin asientos: {@code deLaObligacion} contesta vacio y lo demas no se usa. */
    private static AsientoRepository libroVacio() {
        return (AsientoRepository)
                Proxy.newProxyInstance(
                        AsientoRepository.class.getClassLoader(),
                        new Class<?>[] {AsientoRepository.class},
                        (proxy, metodo, argumentos) -> {
                            if (metodo.getName().equals("deLaObligacion")) {
                                return List.of();
                            }
                            throw new UnsupportedOperationException(
                                    "AsientoRepository."
                                            + metodo.getName()
                                            + " no deberia llamarse en esta prueba");
                        });
    }

    /** Un colaborador que el camino probado no llega a usar: si lo usara, la prueba lo dice. */
    private static <T> T sinUso(Class<T> tipo) {
        return tipo.cast(
                Proxy.newProxyInstance(
                        tipo.getClassLoader(),
                        new Class<?>[] {tipo},
                        (proxy, metodo, argumentos) -> {
                            throw new UnsupportedOperationException(
                                    tipo.getSimpleName()
                                            + "."
                                            + metodo.getName()
                                            + " no deberia llamarse en esta prueba");
                        }));
    }
}
