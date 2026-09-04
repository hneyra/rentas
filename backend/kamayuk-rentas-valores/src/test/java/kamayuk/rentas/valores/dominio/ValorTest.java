package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #37 — sin base de datos: validaciones e inmutabilidad de {@link Valor}. */
@DisplayName("#37 — Valor")
class ValorTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Observacion OBSERVACION = Observacion.de("Emision de prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 1);

    @Test
    @DisplayName("un valor nuevo no tiene id, y esNuevo lo dice")
    void unValorNuevoNoTieneId() {
        Valor valor = valorDe("OP-2026-000001", Dinero.de(500));

        assertThat(valor.esNuevo()).isTrue();
        assertThat(valor.id()).isNull();
        assertThat(valor.estado()).isEqualTo(EstadoDeValor.EMITIDO);
    }

    @Test
    @DisplayName("total es la suma de las cuatro partes, no una quinta cifra guardada aparte")
    void totalEsLaSumaDeLasCuatroPartes() {
        Valor valor =
                new Valor(
                        null,
                        TipoValor.ORDEN_DE_PAGO,
                        "OP-2026-000001",
                        EJERCICIO,
                        1L,
                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                        Dinero.de(100),
                        Dinero.de(20),
                        Dinero.de(5),
                        Dinero.de(1),
                        HOY,
                        EstadoDeValor.EMITIDO,
                        HOY,
                        null,
                        OBSERVACION);

        assertThat(valor.total()).isEqualTo(Dinero.de(126));
    }

    @Test
    @DisplayName("rechaza un numero vacio")
    void rechazaNumeroVacio() {
        assertThatThrownBy(() -> valorDe("  ", Dinero.de(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza un insoluto negativo")
    void rechazaInsolutoNegativo() {
        assertThatThrownBy(() -> valorDe("OP-2026-000001", Dinero.de(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exige la observacion del usuario (regla 10)")
    void exigeObservacion() {
        assertThatThrownBy(
                        () ->
                                new Valor(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        "OP-2026-000001",
                                        EJERCICIO,
                                        1L,
                                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                        Dinero.de(500),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        HOY,
                                        EstadoDeValor.EMITIDO,
                                        HOY,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("desgloseDe suma el detalle, sin confiar en que lo haga quien lo construye")
    void desgloseDeSumaElDetalle() {
        List<ValorDetalle> detalle =
                List.of(
                        ValorDetalle.nuevo(
                                "PREDIAL",
                                EJERCICIO,
                                null,
                                10L,
                                null,
                                null,
                                Dinero.de(300),
                                Dinero.de(10),
                                Dinero.CERO,
                                Dinero.CERO),
                        ValorDetalle.nuevo(
                                "ARBITRIO",
                                EJERCICIO,
                                null,
                                10L,
                                null,
                                null,
                                Dinero.de(150),
                                Dinero.CERO,
                                Dinero.de(5),
                                Dinero.CERO));

        Valor.Desglose desglose = Valor.desgloseDe(detalle);

        assertThat(desglose.total()).isEqualTo(Dinero.de(465));
        assertThat(desglose.insoluto()).isEqualTo(Dinero.de(450));
    }

    @Test
    @DisplayName("desgloseDe de una lista vacia es cero")
    void desgloseDeVacioEsCero() {
        assertThat(Valor.desgloseDe(List.of()).total()).isEqualTo(Dinero.CERO);
    }

    private static Valor valorDe(String numero, Dinero insoluto) {
        return new Valor(
                null,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                EJERCICIO,
                1L,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                insoluto,
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                HOY,
                EstadoDeValor.EMITIDO,
                HOY,
                null,
                OBSERVACION);
    }
}
