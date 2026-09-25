package kamayuk.rentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import kamayuk.rentas.dominio.ZonaHoraria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * El dia que el sistema calcula es el de la municipalidad, y no el del servidor que atiende la
 * peticion (#316).
 *
 * <h2>Que se mide</h2>
 *
 * <p>{@code backend/*&#47;src/main} tiene {@code MEDIDO: 118 LocalDate.now(reloj) en el codigo de
 * src/main} —vencimientos, plazos, cuentas de dias, la fecha que consta en un acto—, y todos leen
 * el {@link Clock} de {@link KamayukAplicacion#reloj()}. {@code LocalDate.now(Clock)} trunca con la
 * zona <b>del reloj</b>, asi que el dia de esos sitios es el que diga la zona de ese bean. Esta
 * prueba toma el bean de produccion, conserva su zona, le fija el instante y pregunta que dia es.
 *
 * <h2>Por que en la franja de las 19:00 a la medianoche, y con la JVM en otra zona</h2>
 *
 * <p>Es la franja en que el dia de Catacaos y el de UTC <b>discrepan</b>: a mediodia los dos dicen
 * lo mismo, y una muestra a mediodia sale verde con el defecto dentro —la muestra uniforme de
 * #273—. Por eso cada caso comprueba primero que en UTC su instante es <b>el dia siguiente</b>: si
 * alguien moviera la muestra fuera de la franja, esa premisa se caeria antes que la afirmacion.
 *
 * <p>Y la JVM se pone en otra zona porque el defecto era leer la del sistema operativo ({@code
 * Clock.systemDefaultZone()}): en un puesto de desarrollo que ya estuviera en la zona del producto,
 * la prueba saldria verde sin arreglar nada. Se prueba con UTC —la zona de casi todo contenedor— y
 * con otras dos, al este de UTC, para que ninguna coincidencia la ponga verde.
 *
 * <p>Los instantes se escriben en UTC a proposito: la zona del producto se nombra en {@link
 * ZonaHoraria} y en ningun otro sitio, tampoco aqui.
 */
@DisplayName("#316 — El dia del reloj es el de la municipalidad, no el del servidor")
class ElDiaDelRelojEsElDelProductoTest {

    private TimeZone zonaDeLaJvm;

    @BeforeEach
    void guardarLaZonaDeLaJvm() {
        zonaDeLaJvm = TimeZone.getDefault();
    }

    @AfterEach
    void restaurarLaZonaDeLaJvm() {
        TimeZone.setDefault(zonaDeLaJvm);
    }

    @ParameterizedTest(name = "a las {1} de Catacaos ({0}), con la JVM en {2}, es {3}")
    @CsvSource({
        // instante en UTC,       hora local, zona de la JVM, dia local
        "2026-09-23T00:00:00Z, 19:00, UTC, 2026-09-22",
        "2026-09-23T02:30:00Z, 21:30, UTC, 2026-09-22",
        "2026-09-23T04:59:59Z, 23:59:59, UTC, 2026-09-22",
        "2026-09-23T02:30:00Z, 21:30, Europe/Berlin, 2026-09-22",
        "2026-09-23T02:30:00Z, 21:30, Asia/Tokyo, 2026-09-22",
        // La noche de fin de anio: el dia equivocado es ademas el ejercicio equivocado
        "2027-01-01T03:15:00Z, 22:15, UTC, 2026-12-31",
    })
    void elDiaEsElDeLaMunicipalidad(
            String instante, String horaLocal, String zonaDelServidor, String diaLocal) {
        Instant ahora = Instant.parse(instante);
        LocalDate esperado = LocalDate.parse(diaLocal);

        assertThat(LocalDate.ofInstant(ahora, ZoneOffset.UTC))
                .as(
                        "la premisa: en UTC este instante ya es el dia siguiente. Si no, la"
                                + " muestra esta fuera de la franja de las 19:00 a la medianoche"
                                + " y no distingue nada")
                .isEqualTo(esperado.plusDays(1));

        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zonaDelServidor)));
        Clock delBean = new KamayukAplicacion().reloj();
        Clock aEsaHora = Clock.fixed(ahora, delBean.getZone());

        assertThat(LocalDate.now(aEsaHora))
                .as(
                        "LocalDate.now(reloj) —126 sitios de src/main— a las %s de la"
                                + " municipalidad, con la JVM en %s: el reloj del bean tiene que"
                                + " llevar ZonaHoraria.DEL_PRODUCTO, no la zona del servidor",
                        horaLocal, zonaDelServidor)
                .isEqualTo(esperado)
                .isEqualTo(ZonaHoraria.diaDe(ahora));
    }
}
