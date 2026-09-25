package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.OrigenDeDeterminacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — Determinacion: nuevaVehicular, nuevaAlcabala y nuevaEspectaculos")
class DeterminacionTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("una determinacion vehicular lleva vehiculoId y nunca predioId")
    void unaDeterminacionVehicularLlevaVehiculoIdYNuncaPredioId() {
        Determinacion vehicular =
                Determinacion.nuevaVehicular(
                        EJERCICIO,
                        1L,
                        2L,
                        3L,
                        Dinero.de("1000"),
                        Dinero.de("10"),
                        List.of("VEHICULAR_ALICUOTA"));

        assertThat(vehicular.tributo()).isEqualTo("VEHICULAR");
        assertThat(vehicular.vehiculoId()).isEqualTo(2L);
        assertThat(vehicular.predioId()).isNull();
        assertThat(vehicular.esNueva()).isTrue();
    }

    @Test
    @DisplayName("un vehicular sin vehiculoId no se puede construir")
    void unVehicularSinVehiculoIdNoSePuedeConstruir() {
        assertThatThrownBy(
                        () ->
                                new Determinacion(
                                        null,
                                        EJERCICIO,
                                        "VEHICULAR",
                                        null,
                                        1L,
                                        null,
                                        null,
                                        3L,
                                        Dinero.de("1000"),
                                        Dinero.de("10"),
                                        List.of("VEHICULAR_ALICUOTA"),
                                        OrigenDeDeterminacion.ORDINARIA,
                                        EstadoDeDeterminacion.BORRADOR,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vehiculoId");
    }

    @Test
    @DisplayName("una determinacion de alcabala lleva predioId y nunca vehiculoId")
    void unaDeterminacionDeAlcabalaLlevaPredioIdYNuncaVehiculoId() {
        Determinacion alcabala =
                Determinacion.nuevaAlcabala(
                        EJERCICIO,
                        1L,
                        5L,
                        3L,
                        Dinero.de("50000"),
                        Dinero.de("120"),
                        List.of("ALCABALA_ALICUOTA"));

        assertThat(alcabala.tributo()).isEqualTo("ALCABALA");
        assertThat(alcabala.predioId()).isEqualTo(5L);
        assertThat(alcabala.vehiculoId()).isNull();
    }

    @Test
    @DisplayName("una determinacion de espectaculos no lleva predio ni vehiculo")
    void unaDeterminacionDeEspectaculosNoLlevaPredioNiVehiculo() {
        Determinacion espectaculos =
                Determinacion.nuevaEspectaculos(
                        EJERCICIO,
                        1L,
                        3L,
                        Dinero.de("10000"),
                        Dinero.de("1000"),
                        List.of("ESPECTACULO_ALICUOTA:CINEMATOGRAFICO"));

        assertThat(espectaculos.tributo()).isEqualTo("ESPECTACULOS");
        assertThat(espectaculos.predioId()).isNull();
        assertThat(espectaculos.vehiculoId()).isNull();
    }

    @Test
    @DisplayName(
            "las reglas aplicadas de un tributo que no es predial no exigen el formato RT-xxx: citan"
                    + " la llave del parametro")
    void lasReglasDeUnTributoNoPredialNoExigenFormatoRtXxx() {
        Determinacion vehicular =
                Determinacion.nuevaVehicular(
                        EJERCICIO,
                        1L,
                        2L,
                        3L,
                        Dinero.de("1000"),
                        Dinero.de("10"),
                        List.of("VEHICULAR_ALICUOTA"));

        assertThat(vehicular.reglasAplicadas()).containsExactly("VEHICULAR_ALICUOTA");
    }

    @Test
    @DisplayName("una regla aplicada en blanco no se admite, ni siquiera fuera del predial")
    void unaReglaEnBlancoNoSeAdmiteFueraDelPredial() {
        assertThatThrownBy(
                        () ->
                                Determinacion.nuevaVehicular(
                                        EJERCICIO,
                                        1L,
                                        2L,
                                        3L,
                                        Dinero.de("1000"),
                                        Dinero.de("10"),
                                        List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("#234 — una determinacion predial nueva no se puede construir sin su modalidad")
    void unaPredialNuevaNoSePuedeConstruirSinModalidad() {
        assertThatThrownBy(
                        () ->
                                Determinacion.nuevaPredial(
                                        EJERCICIO,
                                        1L,
                                        3L,
                                        Dinero.de("1000"),
                                        Dinero.de("10"),
                                        List.of("RT-011"),
                                        null))
                .as(
                        "la base no lo puede exigir —un CHECK no distingue una fila de hoy de una"
                                + " anterior a V21—, asi que lo exige el unico constructor que hay")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modalidad");
    }

    @Test
    @DisplayName("#234 — y la que si la lleva la conserva, sin normalizar nada por su cuenta")
    void unaPredialNuevaConservaSuModalidad() {
        Determinacion alContado =
                Determinacion.nuevaPredial(
                        EJERCICIO,
                        1L,
                        3L,
                        Dinero.de("1000"),
                        Dinero.de("10"),
                        List.of("RT-011"),
                        ModalidadDelPredial.CONTADO);

        assertThat(alContado.modalidad()).isEqualTo(ModalidadDelPredial.CONTADO);
    }

    @Test
    @DisplayName("#234 — el cronograma es del predial: un vehicular con modalidad no se construye")
    void soloElPredialLlevaModalidad() {
        assertThatThrownBy(
                        () ->
                                new Determinacion(
                                        null,
                                        EJERCICIO,
                                        "VEHICULAR",
                                        null,
                                        1L,
                                        null,
                                        2L,
                                        3L,
                                        Dinero.de("1000"),
                                        Dinero.de("10"),
                                        List.of("VEHICULAR_ALICUOTA"),
                                        OrigenDeDeterminacion.ORDINARIA,
                                        EstadoDeDeterminacion.BORRADOR,
                                        null,
                                        ModalidadDelPredial.TRIMESTRAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("determinacion_modalidad_solo_predial_ck");
    }
}
