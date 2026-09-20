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

    // ==================================================================
    //  #267 — la unica transicion que este sistema escribe
    // ==================================================================

    @Test
    @DisplayName("anular mueve el estado y NADA mas")
    void anularMueveElEstadoYNadaMas() {
        Papeleta impuesta = transitoConId("PT-0100", "ABC-123", 7L);

        Papeleta anulada = impuesta.anulada();

        assertThat(anulada.estado()).isEqualTo(EstadoDePapeleta.ANULADA);
        assertThat(anulada.id()).isEqualTo(impuesta.id());
        assertThat(anulada.numero()).isEqualTo(impuesta.numero());
        assertThat(anulada.placa()).isEqualTo(impuesta.placa());
        assertThat(anulada.fechaInfraccion()).isEqualTo(impuesta.fechaInfraccion());
        assertThat(anulada.lugar()).isEqualTo(impuesta.lugar());
        assertThat(anulada.importeAPagar())
                .as("regla 4: no se borra ni se edita, la papeleta se sigue leyendo entera")
                .isEqualTo(impuesta.importeAPagar());
        assertThat(anulada.importeInfraccion()).isEqualTo(impuesta.importeInfraccion());
        assertThat(anulada.usuarioRegistro()).isEqualTo(impuesta.usuarioRegistro());
    }

    /**
     * La siembra NO es uniforme a proposito: uno por cada estado en el que ya no se debe nada, y
     * uno de los que si. Con una sola papeleta {@code IMPUESTA} esta prueba pasaria con la guarda
     * entera borrada, que es el modo de fallo que #243 midio cuatro veces.
     */
    @Test
    @DisplayName("de los tres estados en que ya no se debe nada, no se anula: uno por uno")
    void desdeLosTresEstadosTerminalesNoSeAnula() {
        for (EstadoDePapeleta terminal : EstadoDePapeleta.values()) {
            if (terminal.seDebe()) {
                continue;
            }
            Papeleta muerta = transitoEn("PT-01" + terminal.ordinal(), terminal);
            assertThatThrownBy(muerta::anulada)
                    .as("desde %s no se anula", terminal)
                    .isInstanceOf(Papeleta.TransicionIlegal.class)
                    .hasMessageContaining(terminal.name());
        }
        assertThat(
                        java.util.Arrays.stream(EstadoDePapeleta.values())
                                .filter(estado -> !estado.seDebe())
                                .count())
                .as("si algun dia son menos de tres, esta prueba dejaria de ejercerlos todos")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("de los cuatro estados en que todavia se debe, si se anula: uno por uno")
    void desdeLosCuatroEstadosVivosSiSeAnula() {
        int vivos = 0;
        for (EstadoDePapeleta vivo : EstadoDePapeleta.values()) {
            if (!vivo.seDebe()) {
                continue;
            }
            vivos++;
            assertThat(transitoEn("PT-02" + vivo.ordinal(), vivo).anulada().estado())
                    .as("desde %s si se anula", vivo)
                    .isEqualTo(EstadoDePapeleta.ANULADA);
        }
        assertThat(vivos).isEqualTo(4);
    }

    private static Papeleta transitoEn(String numero, EstadoDePapeleta estado) {
        Papeleta base = transitoConId(numero, "ABC-123", 9L);
        return new Papeleta(
                base.id(),
                base.familia(),
                base.numero(),
                base.codigoInfraccionId(),
                base.fechaInfraccion(),
                base.horaInfraccion(),
                base.lugar(),
                base.placa(),
                base.vehiculoId(),
                base.licenciaConducir(),
                base.infractorId(),
                base.propietarioId(),
                base.contribuyenteId(),
                base.predioId(),
                base.notificacionPreviaId(),
                base.obligadoId(),
                base.baseImponible(),
                base.porcentajeInfraccion(),
                base.importeInfraccion(),
                base.porcentajeACobrar(),
                base.importeAPagar(),
                base.importeConBeneficio(),
                estado,
                base.usuarioRegistro(),
                base.observacion());
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
