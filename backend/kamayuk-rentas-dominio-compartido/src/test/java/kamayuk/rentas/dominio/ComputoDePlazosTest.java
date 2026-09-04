package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #39 — El plazo, el calendario y la exigibilidad, <b>sin base y sin reloj</b>.
 *
 * <p>Es lo que la regla 6 exige de una regla que produce fechas con efecto juridico: recalcular en
 * 2037 la exigibilidad de un valor notificado en 2027 tiene que dar el mismo dia. Ninguna prueba de
 * esta clase levanta contexto ni toca PostgreSQL, y ninguna llama a {@code LocalDate.now()}.
 */
@DisplayName("#39 — Plazos, dias habiles y exigibilidad")
class ComputoDePlazosTest {

    @Nested
    @DisplayName("Plazo: la cantidad no viaja sin su unidad")
    class DelPlazo {

        @Test
        @DisplayName("se lee del texto del parametro: cantidad y unidad")
        void seLeeDelTexto() {
            assertThat(Plazo.de("20 DIAS_HABILES"))
                    .isEqualTo(new Plazo(20, UnidadDePlazo.DIAS_HABILES));
            assertThat(Plazo.de("  4   anios  ")).isEqualTo(new Plazo(4, UnidadDePlazo.ANIOS));
        }

        @Test
        @DisplayName("un parametro sin unidad se rechaza, no se interpreta")
        void sinUnidadSeRechaza() {
            // Leer "20" como dias calendario o como habiles son dos fechas distintas. Adivinar
            // produciria un plazo plausible y equivocado, que es el modo de falla que nadie ve.
            assertThatThrownBy(() -> Plazo.de("20"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cantidad UNIDAD");
            assertThatThrownBy(() -> Plazo.de("20 SEMANAS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unidad de plazo desconocida");
            assertThatThrownBy(() -> Plazo.de("veinte DIAS_HABILES"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no es un numero entero");
        }

        @Test
        @DisplayName("veinte habiles y veinte calendario no caen el mismo dia")
        void habilesYCalendarioDifieren() {
            // Lunes 2026-03-02.
            LocalDate lunes = LocalDate.of(2026, 3, 2);
            CalendarioHabil calendario = CalendarioHabil.sinFeriados();

            LocalDate habiles = Plazo.de("20 DIAS_HABILES").vencimientoDesde(lunes, calendario);
            LocalDate corridos = Plazo.de("20 DIAS_CALENDARIO").vencimientoDesde(lunes, calendario);

            assertThat(corridos).isEqualTo(LocalDate.of(2026, 3, 22));
            assertThat(habiles).isEqualTo(LocalDate.of(2026, 3, 30));
            assertThat(habiles).isAfter(corridos);
        }
    }

    @Nested
    @DisplayName("Calendario habil")
    class DelCalendario {

        @Test
        @DisplayName("sabado y domingo no son habiles, y eso no depende de ningun dato")
        void elFinDeSemanaNoEsHabil() {
            CalendarioHabil calendario = CalendarioHabil.sinFeriados();
            assertThat(calendario.esHabil(LocalDate.of(2026, 3, 6))).isTrue(); // viernes
            assertThat(calendario.esHabil(LocalDate.of(2026, 3, 7))).isFalse(); // sabado
            assertThat(calendario.esHabil(LocalDate.of(2026, 3, 8))).isFalse(); // domingo
            assertThat(calendario.esHabil(LocalDate.of(2026, 3, 9))).isTrue(); // lunes
        }

        @Test
        @DisplayName("un feriado declarado corre el vencimiento un dia")
        void elFeriadoCorreElVencimiento() {
            LocalDate lunes = LocalDate.of(2026, 3, 2);
            Plazo cinco = Plazo.de("5 DIAS_HABILES");

            LocalDate sinFeriado = cinco.vencimientoDesde(lunes, CalendarioHabil.sinFeriados());
            LocalDate conFeriado =
                    cinco.vencimientoDesde(
                            lunes, new CalendarioHabil(Set.of(LocalDate.of(2026, 3, 4))));

            assertThat(sinFeriado).isEqualTo(LocalDate.of(2026, 3, 9));
            assertThat(conFeriado).isEqualTo(LocalDate.of(2026, 3, 10));
        }

        @Test
        @DisplayName("el dia habil siguiente salta el fin de semana entero")
        void elSiguienteHabilSaltaElFinDeSemana() {
            CalendarioHabil calendario = CalendarioHabil.sinFeriados();
            assertThat(calendario.siguienteHabil(LocalDate.of(2026, 3, 6)))
                    .isEqualTo(LocalDate.of(2026, 3, 9));
        }
    }

    @Nested
    @DisplayName("Exigibilidad: art. 106, el dia habil siguiente")
    class DeLaExigibilidad {

        private static final Plazo VEINTE_HABILES = Plazo.de("20 DIAS_HABILES");

        @Test
        @DisplayName("no surte efecto el mismo dia de la diligencia, sino el habil siguiente")
        void surteEfectoElDiaHabilSiguiente() {
            // Se notifica el viernes: el art. 106 dice "desde el dia habil siguiente", que aqui
            // es el lunes, no el sabado.
            Exigibilidad exigibilidad =
                    Exigibilidad.derivarDe(
                            LocalDate.of(2026, 3, 6),
                            VEINTE_HABILES,
                            CalendarioHabil.sinFeriados());

            assertThat(exigibilidad.surteEfectoDesde()).isEqualTo(LocalDate.of(2026, 3, 9));
        }

        @Test
        @DisplayName("es exigible el dia SIGUIENTE al vencimiento, no el dia en que vence")
        void esExigibleElDiaSiguienteAlVencimiento() {
            Exigibilidad exigibilidad =
                    Exigibilidad.derivarDe(
                            LocalDate.of(2026, 3, 6),
                            VEINTE_HABILES,
                            CalendarioHabil.sinFeriados());

            // El dia en que vence el plazo, el deudor todavia puede reclamar.
            assertThat(exigibilidad.exigibleA(exigibilidad.venceElPlazo())).isFalse();
            assertThat(exigibilidad.exigibleDesde())
                    .isEqualTo(exigibilidad.venceElPlazo().plusDays(1));
            assertThat(exigibilidad.exigibleA(exigibilidad.exigibleDesde())).isTrue();
        }

        @Test
        @DisplayName("el mismo plazo, la misma fecha: recalcular diez anios despues da lo mismo")
        void esReproducible() {
            LocalDate diligencia = LocalDate.of(2027, 5, 11);
            Exigibilidad primera =
                    Exigibilidad.derivarDe(
                            diligencia, VEINTE_HABILES, CalendarioHabil.sinFeriados());
            Exigibilidad segunda =
                    Exigibilidad.derivarDe(
                            diligencia, VEINTE_HABILES, CalendarioHabil.sinFeriados());
            assertThat(segunda).isEqualTo(primera);
        }

        @Test
        @DisplayName("un plazo distinto mueve la exigibilidad: no hay ninguna constante detras")
        void elPlazoMandaSobreLaFecha() {
            LocalDate diligencia = LocalDate.of(2026, 3, 6);
            CalendarioHabil calendario = CalendarioHabil.sinFeriados();

            LocalDate conVeinte =
                    Exigibilidad.derivarDe(diligencia, VEINTE_HABILES, calendario).exigibleDesde();
            LocalDate conSiete =
                    Exigibilidad.derivarDe(diligencia, Plazo.de("7 DIAS_HABILES"), calendario)
                            .exigibleDesde();

            assertThat(conSiete).isBefore(conVeinte);
        }
    }
}
