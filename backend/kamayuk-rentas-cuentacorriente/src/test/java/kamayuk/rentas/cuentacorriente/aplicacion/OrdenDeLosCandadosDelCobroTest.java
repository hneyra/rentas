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
 * #431 — <b>el orden de los candados de un cobro no depende de como llegaron sus lineas</b>, y
 * tampoco cuando dos lineas solo se distinguen por el deudor.
 *
 * <p>Hasta #431 un cobro tenia un solo deudor y {@code ORDEN_ESTABLE} no lo miraba: todas las
 * claves lo compartian. Desde que un recibo junta ordenes de deudores distintos, dos condominos del
 * mismo predio producen dos claves que <b>solo</b> difieren en el deudor. Sin el desempate, el
 * orden entre ellas es el de llegada —el ordenamiento es estable—, y dos cobranzas que se solapan y
 * marcan a los dos condominos en orden contrario piden los mismos dos candados al reves: se abrazan
 * y las dos esperan.
 *
 * <p>La carrera no se provoca aqui: lo que se afirma es su causa, que es el orden en que se llama a
 * {@link SaldoRepository#bloquear}. Un repositorio que anota cada candado basta, y el libro vacio
 * hace que el cobro termine en {@code SinDeudaQueAbonar} <b>despues</b> de bloquear, sin escribir
 * nada.
 */
@DisplayName("#431 — el orden de los candados de un cobro con varios deudores")
class OrdenDeLosCandadosDelCobroTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 9, 23);

    private static final long A = 101L;

    private static final long B = 202L;

    @Test
    @DisplayName(
            "dos condominos del mismo predio recibidos como [B, A] se bloquean como [A, B]:"
                    + " desempata el deudor")
    void losCondominosSeBloqueanPorDeudor() {
        SaldosQueAnotanLosCandados saldos = new SaldosQueAnotanLosCandados();

        cobrar(saldos, List.of(predial(B, 10L), predial(A, 10L)));

        assertThat(saldos.bloqueados)
                .as(
                        "las dos claves solo difieren en el deudor: sin desempatar por el, el"
                                + " orden es el de llegada, y otra cobranza con [A, B] pediria"
                                + " los mismos candados al reves")
                .containsExactly(clave(A, 10L), clave(B, 10L));
    }

    // ------------------------------------------------------------------

    private static void cobrar(SaldoRepository saldos, List<ObligacionDelDeudor> obligaciones) {
        AsientoRepository asientos = sinUso(AsientoRepository.class);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        RegistroDeAbonosCuentaCorriente registro =
                new RegistroDeAbonosCuentaCorriente(
                        asientos,
                        saldos,
                        new RegistrarAsiento(
                                asientos,
                                saldos,
                                sinUso(Auditoria.class),
                                Clock.fixed(Instant.parse("2026-09-23T14:00:00Z"), ZoneOffset.UTC)),
                        calculo,
                        new PoliticaDeRedondeo(2, RoundingMode.HALF_UP));

        // Con el libro vacio no hay nada que abonar: el cobro sale DESPUES de pedir los candados,
        // que es lo unico que esta prueba mira.
        assertThatThrownBy(
                        () ->
                                registro.abonarPagoIntegro(
                                        obligaciones,
                                        Dinero.de("500.00"),
                                        HOY,
                                        "RECIBO 001-431-L",
                                        Observacion.de("prueba del orden de los candados")))
                .isInstanceOf(RegistroDeAbonos.SinDeudaQueAbonar.class);
    }

    private static ObligacionDelDeudor predial(long deudor, long predio) {
        return new ObligacionDelDeudor(
                deudor, new SeleccionDeObligacion("PREDIAL", EJERCICIO, predio, null));
    }

    private static ClaveDeObligacion clave(long deudor, long predio) {
        return new ClaveDeObligacion(deudor, "PREDIAL", EJERCICIO, predio, null);
    }

    /** Anota el orden de los candados y deja el libro vacio. Nada mas se le pide. */
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
            throw new UnsupportedOperationException("el cobro no busca una cuota suelta");
        }

        @Override
        public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
            throw new UnsupportedOperationException("el cobro no lee el padron entero");
        }

        @Override
        public void proyectar(SaldoProyectado saldo) {
            throw new UnsupportedOperationException("con el libro vacio no se proyecta nada");
        }
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
