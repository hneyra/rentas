package kamayuk.rentas.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Vehiculo}, funcion pura sin base ni reloj (#25, #26).
 *
 * <p>Lo unico que este dominio calcula sin depender de D-02 es {@link Vehiculo#rangoDeAfectacion}
 * —tres ejercicios desde el siguiente al de la inscripcion— y {@link Vehiculo#afectoEn}, que
 * consulta ese mismo rango. {@code consulta_vehiculos} (#25) se apoya en los dos para su columna
 * «Afectación», y por eso vale la pena fijar aqui los tres bordes del intervalo, no solo el caso
 * feliz.
 */
@DisplayName("Vehiculo#rangoDeAfectacion — tres ejercicios desde el siguiente a la inscripcion")
class VehiculoTest {

    private static final Ejercicio INSCRIPCION_2024 = new Ejercicio(2024);

    @Test
    @DisplayName("el rango va del ejercicio siguiente a la inscripcion, tres ejercicios enteros")
    void elRangoVaDelSiguienteALaInscripcionTresEjercicios() {
        Vehiculo vehiculo = nuevo(INSCRIPCION_2024);
        Vehiculo.RangoDeAfectacion rango = vehiculo.rangoDeAfectacion();

        assertThat(rango.desde()).isEqualTo(new Ejercicio(2025));
        assertThat(rango.hasta()).isEqualTo(new Ejercicio(2027));
    }

    @Test
    @DisplayName("el ejercicio de la inscripcion todavia no esta afecto")
    void elEjercicioDeLaInscripcionNoEstaAfecto() {
        Vehiculo vehiculo = nuevo(INSCRIPCION_2024);
        assertThat(vehiculo.afectoEn(new Ejercicio(2024))).isFalse();
    }

    @Test
    @DisplayName("el siguiente ejercicio, y los dos que le siguen, si estan afectos")
    void losTresEjerciciosSiguientesEstanAfectos() {
        Vehiculo vehiculo = nuevo(INSCRIPCION_2024);
        assertThat(vehiculo.afectoEn(new Ejercicio(2025))).isTrue();
        assertThat(vehiculo.afectoEn(new Ejercicio(2026))).isTrue();
        assertThat(vehiculo.afectoEn(new Ejercicio(2027))).isTrue();
    }

    @Test
    @DisplayName("el cuarto ejercicio ya no esta afecto")
    void elCuartoEjercicioYaNoEstaAfecto() {
        Vehiculo vehiculo = nuevo(INSCRIPCION_2024);
        assertThat(vehiculo.afectoEn(new Ejercicio(2028)))
                .as("2025, 2026 y 2027 son los tres; 2028 ya no")
                .isFalse();
    }

    @Test
    @DisplayName("un vehiculo no se inscribe antes de fabricarse")
    void unVehiculoNoSeInscribeAntesDeFabricarse() {
        assertThatThrownBy(
                        () ->
                                Vehiculo.nuevo(
                                        Placa.de("T1E-100"),
                                        1L,
                                        "TOYOTA",
                                        "YARIS",
                                        "M1",
                                        new Ejercicio(2024),
                                        new Ejercicio(2023)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anterior al de fabricacion");
    }

    private static Vehiculo nuevo(Ejercicio inscripcion) {
        return Vehiculo.nuevo(
                Placa.de("T1E-100"), 1L, "TOYOTA", "YARIS", "M1", inscripcion, inscripcion);
    }
}
