package kamayuk.rentas.nucleo.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La regla del artículo 31 del TUO de la LTM, sin base y sin reloj (regla 6, #329): el ejercicio es
 * de quien era propietario del vehículo <b>al 1 de enero</b>.
 *
 * <p>Los contribuyentes son A, B y C; el titular de hoy es siempre el último adquiriente de la
 * cadena, que es lo que {@code RegistrarTransferencia} deja escrito en {@code vehiculo}.
 */
@DisplayName("#329 — PropietarioAlPrimeroDeEnero: el art. 31, como funcion pura")
class PropietarioAlPrimeroDeEneroTest {

    private static final long A = 11L;
    private static final long B = 22L;
    private static final long C = 33L;
    private static final long VEHICULO = 7L;
    private static final Ejercicio DE_2026 = new Ejercicio(2026);

    @Test
    @DisplayName("sin transferencias, el titular de hoy")
    void sinTransferenciasEsElTitularDeHoy() {
        assertThat(PropietarioAlPrimeroDeEnero.de(A, List.of(), DE_2026)).isEqualTo(A);
    }

    @Test
    @DisplayName("vendido a mitad del ejercicio: el ejercicio es del vendedor")
    void vendidoDentroDelEjercicioEsDelVendedor() {
        List<Transferencia> historico = List.of(de(1L, A, B, LocalDate.of(2026, 6, 10)));

        assertThat(PropietarioAlPrimeroDeEnero.de(B, historico, DE_2026)).isEqualTo(A);
        assertThat(PropietarioAlPrimeroDeEnero.de(B, historico, new Ejercicio(2027)))
                .as("el adquirente es contribuyente desde el 1 de enero del anio siguiente")
                .isEqualTo(B);
    }

    @Test
    @DisplayName("vendido el ejercicio anterior: el ejercicio ya es del comprador")
    void vendidoAntesDelEjercicioEsDelComprador() {
        List<Transferencia> historico = List.of(de(1L, A, B, LocalDate.of(2025, 11, 30)));

        assertThat(PropietarioAlPrimeroDeEnero.de(B, historico, DE_2026)).isEqualTo(B);
    }

    @Test
    @DisplayName("vendido el mismo 1 de enero: el propietario de ese dia ya es el comprador")
    void vendidoElPrimeroDeEneroEsDelComprador() {
        List<Transferencia> historico = List.of(de(1L, A, B, LocalDate.of(2026, 1, 1)));

        assertThat(PropietarioAlPrimeroDeEnero.de(B, historico, DE_2026)).isEqualTo(B);
    }

    @Test
    @DisplayName("vendido el 2 de enero: todavia es del vendedor")
    void vendidoElDosDeEneroEsDelVendedor() {
        List<Transferencia> historico = List.of(de(1L, A, B, LocalDate.of(2026, 1, 2)));

        assertThat(PropietarioAlPrimeroDeEnero.de(B, historico, DE_2026)).isEqualTo(A);
    }

    @Test
    @DisplayName(
            "dos ventas en el ejercicio: es de quien lo tenia al 1 de enero, no del intermedio")
    void dosVentasEnElEjercicioEsDelPrimerTransferente() {
        List<Transferencia> historico =
                List.of(
                        de(1L, A, B, LocalDate.of(2026, 3, 1)),
                        de(2L, B, C, LocalDate.of(2026, 6, 10)));

        assertThat(PropietarioAlPrimeroDeEnero.de(C, historico, DE_2026)).isEqualTo(A);
    }

    @Test
    @DisplayName("la cadena se lee por fecha, no por el orden en que llega")
    void laCadenaSeLeePorFechaYNoPorElOrdenDeLaLista() {
        List<Transferencia> historico =
                List.of(
                        de(2L, B, C, LocalDate.of(2026, 6, 10)),
                        de(1L, A, B, LocalDate.of(2026, 3, 1)));

        assertThat(PropietarioAlPrimeroDeEnero.de(C, historico, DE_2026)).isEqualTo(A);
    }

    @Test
    @DisplayName("dos ventas el mismo dia: desempata el orden en que se registraron")
    void dosVentasElMismoDiaDesempataElRegistro() {
        List<Transferencia> historico =
                List.of(
                        de(9L, B, C, LocalDate.of(2026, 6, 10)),
                        de(8L, A, B, LocalDate.of(2026, 6, 10)));

        assertThat(PropietarioAlPrimeroDeEnero.de(C, historico, DE_2026)).isEqualTo(A);
    }

    @Test
    @DisplayName("una cadena anterior y otra dentro: cuenta la primera posterior al 1 de enero")
    void soloCuentanLasPosterioresAlPrimeroDeEnero() {
        List<Transferencia> historico =
                List.of(
                        de(1L, A, B, LocalDate.of(2025, 5, 1)),
                        de(2L, B, C, LocalDate.of(2026, 8, 1)));

        assertThat(PropietarioAlPrimeroDeEnero.de(C, historico, DE_2026)).isEqualTo(B);
        assertThat(PropietarioAlPrimeroDeEnero.de(C, historico, new Ejercicio(2025))).isEqualTo(A);
    }

    // ------------------------------------------------------------------

    private static Transferencia de(long id, long transferente, long adquiriente, LocalDate fecha) {
        return new Transferencia(
                id,
                ObjetoDeTransferencia.VEHICULO,
                null,
                VEHICULO,
                transferente,
                adquiriente,
                TipoTransferencia.COMPRA_VENTA,
                fecha,
                Dinero.de("60000.00"),
                Porcentaje.total(),
                false,
                "Tarjeta de propiedad",
                Observacion.de("Compraventa de prueba"),
                "jefe.rentas");
    }
}
