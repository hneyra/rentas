package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#46/#47 — Papeleta")
class PapeletaTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);

    @Test
    @DisplayName("una papeleta nueva no tiene id, y nace IMPUESTA")
    void unaPapeletaNuevaNoTieneIdYNaceImpuesta() {
        Papeleta papeleta = transitoDe("PT-0001", "ABC-123");

        assertThat(papeleta.esNueva()).isTrue();
        assertThat(papeleta.estado()).isEqualTo(EstadoDePapeleta.IMPUESTA);
        assertThat(papeleta.familia()).isEqualTo(Familia.TRANSITO);
    }

    @Test
    @DisplayName("una papeleta de transito exige placa (papeleta_familia_ck)")
    void unaPapeletaDeTransitoExigePlaca() {
        assertThatThrownBy(
                        () ->
                                Papeleta.nuevaTransito(
                                        "PT-0002",
                                        1L,
                                        FECHA,
                                        null,
                                        "Av. Grau",
                                        "  ",
                                        null,
                                        null,
                                        null,
                                        null,
                                        1L,
                                        Dinero.de("5500"),
                                        Alicuota.de("8"),
                                        Dinero.de("440"),
                                        Alicuota.de("100"),
                                        Dinero.de("440"),
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sin observacion no se construye (regla 10)")
    void sinObservacionNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                Papeleta.nuevaTransito(
                                        "PT-0003",
                                        1L,
                                        FECHA,
                                        null,
                                        "Av. Grau",
                                        "ABC-123",
                                        null,
                                        null,
                                        null,
                                        null,
                                        1L,
                                        Dinero.de("5500"),
                                        Alicuota.de("8"),
                                        Dinero.de("440"),
                                        Alicuota.de("100"),
                                        Dinero.de("440"),
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("conNumero conserva el desglose, y solo cambia el numero")
    void conNumeroConservaElDesglose() {
        Papeleta original = transitoConId("PT-0004", "ABC-123", 1L);

        Papeleta renumerada = original.conNumero("PT-0004-B");

        assertThat(renumerada.numero()).isEqualTo("PT-0004-B");
        assertThat(renumerada.id()).isEqualTo(original.id());
        assertThat(renumerada.importeAPagar()).isEqualTo(original.importeAPagar());
        assertThat(renumerada.baseImponible()).isEqualTo(original.baseImponible());
    }

    @Test
    @DisplayName("no se cambia el numero de una papeleta que no esta guardada")
    void noSeCambiaElNumeroDeUnaPapeletaSinGuardar() {
        Papeleta sinGuardar = transitoDe("PT-0005", "ABC-123");

        assertThatThrownBy(() -> sinGuardar.conNumero("PT-0005-B"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("una papeleta administrativa exige contribuyente o predio (papeleta_familia_ck)")
    void unaPapeletaAdministrativaExigeContribuyenteOPredio() {
        assertThatThrownBy(() -> administrativaDe(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una papeleta administrativa se admite sin notificacion previa (#47 AC1)")
    void unaPapeletaAdministrativaSeAdmiteSinNotificacionPrevia() {
        Papeleta papeleta = administrativaDe(100L, null, null);

        assertThat(papeleta.familia()).isEqualTo(Familia.ADMINISTRATIVA);
        assertThat(papeleta.notificacionPreviaId()).isNull();
        assertThat(papeleta.placa()).isNull();
    }

    @Test
    @DisplayName("una papeleta administrativa se admite solo con predio, sin contribuyente")
    void unaPapeletaAdministrativaSeAdmiteSoloConPredio() {
        Papeleta papeleta = administrativaDe(null, 200L, null);

        assertThat(papeleta.contribuyenteId()).isNull();
        assertThat(papeleta.predioId()).isEqualTo(200L);
    }

    private static Papeleta transitoDe(String numero, String placa) {
        return Papeleta.nuevaTransito(
                numero,
                1L,
                FECHA,
                null,
                "Av. Grau",
                placa,
                null,
                null,
                null,
                null,
                1L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                OBSERVACION);
    }

    private static Papeleta administrativaDe(
            Long contribuyenteId, Long predioId, Long notificacionPreviaId) {
        return Papeleta.nuevaAdministrativa(
                "PA-0001",
                1L,
                FECHA,
                null,
                "Av. Grau",
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                1L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                OBSERVACION);
    }

    private static Papeleta transitoConId(String numero, String placa, long id) {
        Papeleta nueva = transitoDe(numero, placa);
        return new Papeleta(
                id,
                nueva.familia(),
                nueva.numero(),
                nueva.codigoInfraccionId(),
                nueva.fechaInfraccion(),
                nueva.horaInfraccion(),
                nueva.lugar(),
                nueva.placa(),
                nueva.vehiculoId(),
                nueva.licenciaConducir(),
                nueva.infractorId(),
                nueva.propietarioId(),
                nueva.contribuyenteId(),
                nueva.predioId(),
                nueva.notificacionPreviaId(),
                1L,
                nueva.baseImponible(),
                nueva.porcentajeInfraccion(),
                nueva.importeInfraccion(),
                nueva.porcentajeACobrar(),
                nueva.importeAPagar(),
                nueva.importeConBeneficio(),
                nueva.estado(),
                "prueba",
                nueva.observacion());
    }
}
