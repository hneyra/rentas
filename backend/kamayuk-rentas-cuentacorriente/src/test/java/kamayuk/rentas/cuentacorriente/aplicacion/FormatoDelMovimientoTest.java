package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.RangoDeCuotas;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El papel de un alta o una baja que abarca varias cuotas (#538).
 *
 * <p>Es una funcion pura y se prueba como tal. Lo que mide no es que el PDF se dibuje —eso lo mide
 * {@code SaldoYMovimientosTest} emitiendolo de verdad— sino que <b>diga lo que sustenta</b>: una
 * nota de cargo por «cuotas 1 a 4» que imprimiera «Cuota: 1» y el total de una sola cuota es un
 * papel que se notifica al contribuyente y no coincide con lo que se asento. La deuda existiria, la
 * cifra del libro seria la correcta, y el unico sitio donde se veria la diferencia es el que nadie
 * vuelve a mirar.
 */
@DisplayName("#538 — La nota de abono y la de cargo dicen que cuotas cubren")
class FormatoDelMovimientoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("la cabecera nombra el rango entero, no la primera cuota")
    void laCabeceraNombraElRango() {
        ModeloDeDocumento modelo = notaDe(new RangoDeCuotas(1, 4));

        assertThat(modelo.cabecera())
                .extracting(Campo::etiqueta, Campo::valor)
                .contains(org.assertj.core.groups.Tuple.tuple("Cuota", "1 a 4"));
    }

    @Test
    @DisplayName("la obligacion anual se llama por su nombre, no «cuota 0»")
    void laAnualSeLlamaAnual() {
        assertThat(notaDe(RangoDeCuotas.ANUAL).cabecera())
                .extracting(Campo::etiqueta, Campo::valor)
                .contains(org.assertj.core.groups.Tuple.tuple("Cuota", "Anual"));
    }

    @Test
    @DisplayName("cada linea del detalle dice a que cuota se imputa")
    void cadaLineaDiceSuCuota() {
        Tabla detalle = notaDe(new RangoDeCuotas(1, 3)).tablas().get(0);

        assertThat(detalle.columnas()).startsWith("Cuota");
        assertThat(detalle.filas())
                .as("tres cuotas por una parte con importe son tres lineas, una por cuota")
                .hasSize(3)
                .extracting(fila -> fila.get(0))
                .containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("el total del papel es el del acto entero, no el de una cuota")
    void elTotalEsElDelActo() {
        assertThat(notaDe(new RangoDeCuotas(1, 4)).subtitulo())
                .as("cuatro cuotas de 100,00 son 400,00: imprimir 100,00 seria otro papel")
                .isEqualTo("Total: 400.00");
        assertThat(notaDe(RangoDeCuotas.deUnaSola(2)).subtitulo()).isEqualTo("Total: 100.00");
    }

    // ------------------------------------------------------------------

    /** La nota tal como la compone el caso de uso: la plantilla, el rango y todos sus asientos. */
    private static ModeloDeDocumento notaDe(RangoDeCuotas cuotas) {
        MovimientoDeDeuda plantilla =
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.ALTA,
                        new ClaveDeSaldo(7L, "PREDIAL", EJERCICIO, cuotas.desde(), null, null),
                        Dinero.de("100.00"),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        FECHA,
                        "RD-2026-000418",
                        null);

        List<Asiento> asentados = new ArrayList<>();
        for (MovimientoDeDeuda deLaCuota : plantilla.enCadaCuota(cuotas)) {
            asentados.addAll(deLaCuota.enAsientos());
        }
        return FormatoDelMovimiento.de(plantilla, cuotas, List.copyOf(asentados), "00000006550");
    }
}
