package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #413 — El papel de la resolucion que deja la multa sin efecto no la declara exigible.
 *
 * <p>Hasta #413 el cuadro de deuda decia «TOTAL EXIGIBLE 428.00» y el plazo de pago justo en la
 * resolucion que, en la misma transaccion, da de baja esos 428,00. Y ese papel es el que {@code
 * EmitirDocumento} sella y reimprime igual diez años despues. La siembra que distingue es el par:
 * la MISMA deuda con {@code SE_DEJA_SIN_EFECTO} y con {@code SE_MANTIENE}. Un arreglo que vaciara
 * el cuadro de todas las resoluciones pasaria el primero y fallaria el segundo.
 */
@DisplayName("#413 — El papel de la resolucion que extingue no dice que se debe")
class ModeloDeLaResolucionDeGerenciaTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 4, 1);

    private static final ObligacionPublica DEUDA =
            new ObligacionPublica(
                    "PAPELETA",
                    new Ejercicio(2026),
                    null,
                    null,
                    FECHA,
                    Dinero.de("428.00"),
                    Dinero.CERO,
                    Dinero.CERO,
                    Dinero.CERO,
                    "ORDINARIA");

    @Test
    @DisplayName(
            "SE_DEJA_SIN_EFECTO: ningun «TOTAL EXIGIBLE 428.00», saldo 0.00 y sin plazo de pago")
    void laQueExtingueNoLaDeclaraExigible() {
        ModeloDeDocumento papel = modelo(EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);
        Tabla cuadro = papel.tablas().get(0);

        assertThat(cuadro.titulo()).isEqualTo("Deuda que se deja sin efecto al " + FECHA);
        assertThat(cuadro.filas())
                .as("el papel sellado no puede declarar exigible lo que el mismo acto extingue")
                .doesNotContain(List.of("TOTAL EXIGIBLE", "428.00"))
                .contains(List.of("TOTAL QUE SE DEJA SIN EFECTO", "428.00"))
                .contains(List.of("SALDO EXIGIBLE TRAS ESTA RESOLUCION", "0.00"));
        assertThat(papel.cabecera())
                .extracting(Campo::etiqueta)
                .as("no hay nada que pagar: no se concede plazo de pago")
                .doesNotContain("Plazo de pago");
    }

    @Test
    @DisplayName("SE_MANTIENE, sobre la misma deuda: sigue «TOTAL EXIGIBLE 428.00» y el plazo")
    void laQueMantieneSigueDiciendoLoQueSeDebe() {
        ModeloDeDocumento papel = modelo(EfectoSobreLaMulta.SE_MANTIENE);
        Tabla cuadro = papel.tablas().get(0);

        assertThat(cuadro.titulo()).isEqualTo("Deuda actualizada al " + FECHA);
        assertThat(cuadro.filas()).contains(List.of("TOTAL EXIGIBLE", "428.00"));
        assertThat(papel.cabecera()).extracting(Campo::etiqueta).contains("Plazo de pago");
    }

    private static ModeloDeDocumento modelo(EfectoSobreLaMulta efecto) {
        return ModeloDeLaResolucionDeGerencia.de(
                papeleta(),
                TipoDeResolucionDeGerencia.ORDINARIA,
                "PENA GARCIA, LUIS",
                "C-0007",
                "DNI 12345678",
                null,
                null,
                SentidoDelFallo.FUNDADO,
                efecto,
                null,
                new PlazosDeSancionesParametrizados.PlazoConcedido(
                        "Plazo de pago", Plazo.de("7 DIAS_HABILES")),
                DEUDA,
                FECHA,
                "Se resuelve el recurso de la prueba");
    }

    private static Papeleta papeleta() {
        return Papeleta.nuevaTransito(
                "PT-0413",
                1L,
                LocalDate.of(2026, 2, 10),
                null,
                "Av. Grau",
                "ABC123",
                null,
                null,
                null,
                null,
                7L,
                Dinero.de("5350"),
                Alicuota.de("8"),
                Dinero.de("428.00"),
                Alicuota.de("100"),
                Dinero.de("428.00"),
                null,
                Observacion.de("Papeleta de la prueba"));
    }
}
