package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #366 — La regla con nombre, sin base de datos: que se cumple por el <b>mismo tipo</b> y que la
 * fase la sostiene un valor vivo de <b>cualquiera</b>.
 *
 * <p>La siembra distingue: el contribuyente tiene una OP viva sobre el PREDIAL 2025 del predio 7,
 * una RD <b>anulada</b> sobre la misma obligacion, y una RD viva sobre <b>otro</b> predio. Una
 * regla que no mirara el tipo, el estado o la unidad contestaria distinto en alguna de las cuatro
 * preguntas.
 */
@DisplayName("#366 — ObligacionYaFormalizada")
class ObligacionYaFormalizadaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2025);
    private static final SelectorDeObligacion DEL_PREDIO_7 =
            new SelectorDeObligacion("PREDIAL", EJERCICIO, 7L, null);

    @Test
    @DisplayName("se cumple para el tipo del valor vivo, y no para otro")
    void seCumpleSoloParaElMismoTipo() {
        ValoresEnMemoria valores = new ValoresEnMemoria();
        Valor op =
                valores.con(
                        valor(TipoValor.ORDEN_DE_PAGO, "OP-1", EstadoDeValor.EMITIDO), linea(7L));
        valores.con(
                valor(TipoValor.RESOLUCION_DE_DETERMINACION, "RD-1", EstadoDeValor.ANULADO),
                linea(7L));
        valores.con(
                valor(TipoValor.RESOLUCION_DE_DETERMINACION, "RD-2", EstadoDeValor.EMITIDO),
                linea(8L));

        ObligacionYaFormalizada.Formalizacion evaluada =
                new ObligacionYaFormalizada(valores).de(7L, DEL_PREDIO_7);

        assertThat(evaluada.porUnValorDe(TipoValor.ORDEN_DE_PAGO)).contains(op);
        assertThat(evaluada.seCumplePara(TipoValor.RESOLUCION_DE_DETERMINACION))
                .as("la RD anulada ya no formaliza, y la viva es de otro predio")
                .isFalse();
        assertThat(evaluada.yaEstaEnFaseValor())
                .as("la OP viva ya movio la deuda a VALOR, sea cual sea el tipo que se emita")
                .isTrue();
    }

    @Test
    @DisplayName("sin ningun valor vivo, ni se cumple ni la deuda esta en VALOR")
    void sinValorVivoNadaSeCumple() {
        ValoresEnMemoria valores = new ValoresEnMemoria();
        valores.con(valor(TipoValor.ORDEN_DE_PAGO, "OP-1", EstadoDeValor.PAGADO), linea(7L));

        ObligacionYaFormalizada.Formalizacion evaluada =
                new ObligacionYaFormalizada(valores).de(7L, DEL_PREDIO_7);

        assertThat(evaluada.seCumplePara(TipoValor.ORDEN_DE_PAGO)).isFalse();
        assertThat(evaluada.yaEstaEnFaseValor()).isFalse();
    }

    private static Valor valor(TipoValor tipo, String numero, EstadoDeValor estado) {
        LocalDate emision = LocalDate.of(2026, 3, 15);
        return new Valor(
                null,
                tipo,
                numero,
                new Ejercicio(2026),
                7L,
                tipo.baseLegal(),
                Dinero.de(100),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                emision,
                estado,
                emision,
                null,
                Observacion.de("Se emite para la prueba"));
    }

    private static ValorDetalle linea(long predioId) {
        return ValorDetalle.nuevo(
                "PREDIAL",
                EJERCICIO,
                null,
                predioId,
                null,
                null,
                Dinero.de(100),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }
}
