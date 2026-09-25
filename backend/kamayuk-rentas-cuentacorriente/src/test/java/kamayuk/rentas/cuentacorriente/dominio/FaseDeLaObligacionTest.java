package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La fase de una obligacion a una fecha, sin Spring, sin base y sin reloj (#363, regla 6).
 *
 * <p>Cada siembra distingue el ultimo del maximo o el corte del «todo el libro»: la muestra
 * uniforme —obligaciones que solo avanzan de fase, con corte a hoy— da lo mismo con las cuatro
 * definiciones y no prueba ninguna.
 */
@DisplayName("#363 — La fase de la obligacion a la fecha de corte")
class FaseDeLaObligacionTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate MARZO = LocalDate.of(2026, 3, 1);
    private static final LocalDate ABRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAYO = LocalDate.of(2026, 5, 1);
    private static final LocalDate JUNIO = LocalDate.of(2026, 6, 1);

    @Test
    @DisplayName("ORDINARIA → CONVENIO → ORDINARIA da ORDINARIA: la del ultimo, no la maxima")
    void laDelUltimoNoLaMaxima() {
        List<Asiento> libro =
                List.of(
                        asiento(1L, Fase.ORDINARIA, 1, MARZO),
                        asiento(2L, Fase.CONVENIO, 1, ABRIL),
                        asiento(3L, Fase.ORDINARIA, 1, MAYO));

        assertThat(FaseDeLaObligacion.a(libro, JUNIO)).isEqualTo(Fase.ORDINARIA);
    }

    @Test
    @DisplayName("con corte anterior al pase a valor da ORDINARIA; el mismo dia del pase, VALOR")
    void elCorteDejaFueraLoPosterior() {
        List<Asiento> libro =
                List.of(asiento(1L, Fase.ORDINARIA, 0, MARZO), asiento(2L, Fase.VALOR, 0, JUNIO));

        assertThat(FaseDeLaObligacion.a(libro, LocalDate.of(2026, 5, 15)))
                .as("al 15 de mayo el pase del 1 de junio no habia ocurrido")
                .isEqualTo(Fase.ORDINARIA);
        assertThat(FaseDeLaObligacion.a(libro, JUNIO))
                .as("el corte incluye su dia: fechaValor <= corte")
                .isEqualTo(Fase.VALOR);
    }

    @Test
    @DisplayName("el ultimo sale del identificador, no de la fecha valor ni del orden de la lista")
    void elUltimoSaleDelIdentificador() {
        // El par de un movimiento de fase comparte fecha valor, y la lista puede llegar en
        // cualquier orden: solo el identificador dice cual se asento despues.
        List<Asiento> libro =
                List.of(
                        asiento(9L, Fase.ORDINARIA, 1, ABRIL),
                        asiento(7L, Fase.COACTIVA, 1, ABRIL));

        assertThat(FaseDeLaObligacion.a(libro, JUNIO)).isEqualTo(Fase.ORDINARIA);
    }

    @Test
    @DisplayName(
            "entre cuotas gana la mas avanzada: la ultima de cada una, y la maxima entre ellas")
    void entreCuotasGanaLaMasAvanzada() {
        // La cuota 1 fue a VALOR y volvio; la 2 esta en VALOR hoy. Con «el ultimo de toda la
        // obligacion» saldria ORDINARIA (id 4 es de la cuota 1).
        List<Asiento> libro =
                List.of(
                        asiento(1L, Fase.ORDINARIA, 1, MARZO),
                        asiento(2L, Fase.VALOR, 1, ABRIL),
                        asiento(3L, Fase.VALOR, 2, ABRIL),
                        asiento(4L, Fase.ORDINARIA, 1, MAYO));

        assertThat(FaseDeLaObligacion.a(libro, JUNIO)).isEqualTo(Fase.VALOR);
    }

    @Test
    @DisplayName("sin ningun asiento a la fecha la obligacion esta en ORDINARIA, como al nacer")
    void sinAsientosALaFechaEsOrdinaria() {
        List<Asiento> libro = List.of(asiento(1L, Fase.COACTIVA, 0, JUNIO));

        assertThat(FaseDeLaObligacion.a(libro, MAYO)).isEqualTo(Fase.ORDINARIA);
        assertThat(FaseDeLaObligacion.a(List.of(), MAYO)).isEqualTo(Fase.ORDINARIA);
    }

    @Test
    @DisplayName(
            "con el corte despues de todo el libro, da lo que la proyeccion: una regla, no dos")
    void conElCorteAlFinalDaLoQueLaProyeccion() {
        List<Asiento> libro =
                List.of(
                        asiento(1L, Fase.ORDINARIA, 1, MARZO),
                        asiento(2L, Fase.CONVENIO, 1, ABRIL),
                        asiento(3L, Fase.COACTIVA, 1, MAYO),
                        asiento(4L, Fase.VALOR, 2, ABRIL),
                        asiento(5L, Fase.ORDINARIA, 2, MAYO));

        Fase deLaProyeccion =
                ProyeccionDelSaldo.de(libro, Instant.parse("2026-06-01T00:00:00Z")).stream()
                        .map(SaldoProyectado::fase)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();

        assertThat(deLaProyeccion).isEqualTo(Fase.COACTIVA);
        assertThat(FaseDeLaObligacion.a(libro, JUNIO)).isEqualTo(deLaProyeccion);
    }

    // ------------------------------------------------------------------

    private static Asiento asiento(long id, Fase fase, int periodo, LocalDate fechaValor) {
        return new Asiento(
                id,
                EJERCICIO,
                1L,
                "PREDIAL",
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                fase,
                periodo,
                null,
                null,
                null,
                Dinero.de(100),
                fechaValor,
                "EM-2026-0363",
                null,
                null,
                null,
                null);
    }
}
