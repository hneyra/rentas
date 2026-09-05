package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kamayuk.rentas.catastro.prueba.CatastroEnMemoria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P5C — A quien se le cobra el arbitrio, resuelto por el PUERTO y no por la tabla.
 *
 * <h2>Que sustituye, y que se conserva</h2>
 *
 * <p>Sustituye a {@code TitularPrincipalRepositoryJdbcTest}, que media el mismo criterio contra
 * {@code titularidad} — una tabla que `V6` retiro de esta base (P5C, cierra {@code
 * PENDIENTE-CRUCE-04}). Que la titularidad se resuelva bien a una fecha es de {@code catastro} y
 * sus pruebas viven alli; lo que sigue siendo de {@code rentas}, y es lo que esta clase mide, es
 * <b>a cual de los titulares se le cobra</b>: el de mayor porcentaje, con un orden TOTAL.
 *
 * <h2>El desempate cambio, y esta prueba lo dice</h2>
 *
 * <p>El SQL desempataba por {@code id ASC} de la fila de titularidad. El puerto no publica ese
 * identificador, asi que se desempata por {@code contribuyenteId}. Lo que cambia es a cual de dos
 * copropietarios EMPATADOS se le cobra; lo que no puede cambiar —y es lo que se mide— es que la
 * eleccion sea la misma en dos corridas.
 */
@DisplayName("P5C — El titular principal, por el puerto de catastro")
class TitularPrincipalPorElPuertoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 30);

    @Test
    @DisplayName("con un solo titular, es ese")
    void conUnSoloTitular() {
        CatastroEnMemoria catastro = new CatastroEnMemoria().conTitular(1L, 501L, "100");

        assertThat(new TitularPrincipalPorElPuerto(catastro.titulares()).principalDe(1L, HOY))
                .contains(501L);
    }

    @Test
    @DisplayName("con varios, el de mayor porcentaje: el arbitrio se le cobra a quien mas tiene")
    void elDeMayorPorcentaje() {
        CatastroEnMemoria catastro =
                new CatastroEnMemoria()
                        .conTitular(2L, 501L, "30")
                        .conTitular(2L, 502L, "70")
                        .conTitular(2L, 503L, "0.5");

        assertThat(new TitularPrincipalPorElPuerto(catastro.titulares()).principalDe(2L, HOY))
                .contains(502L);
    }

    @Test
    @DisplayName("empatados, la eleccion es la MISMA en dos corridas: el orden es total")
    void elEmpateSeDesempataSiempreIgual() {
        // Lo que importa de un empate no es a cual de los dos, sino que no cambie: dos corridas
        // de la misma emision no pueden cobrarle el arbitrio a personas distintas.
        CatastroEnMemoria catastro =
                new CatastroEnMemoria().conTitular(3L, 900L, "50").conTitular(3L, 800L, "50");

        TitularPrincipalPorElPuerto titular = new TitularPrincipalPorElPuerto(catastro.titulares());

        assertThat(titular.principalDe(3L, HOY))
                .isEqualTo(titular.principalDe(3L, HOY))
                .isPresent();
    }

    @Test
    @DisplayName("un predio sin titular vigente no devuelve a nadie, y no es un error")
    void sinTitularNoHayNadie() {
        // Son 4 977 de los 14 422 predios de Catacaos (#586, #690): el caso corriente, no el raro.
        assertThat(
                        new TitularPrincipalPorElPuerto(new CatastroEnMemoria().titulares())
                                .principalDe(4L, HOY))
                .isEmpty();
    }

    @Test
    @DisplayName("la fecha VIAJA: quien era titular en marzo no es el de hoy")
    void laFechaViaja() {
        // #24 y #366: resolver con el reloj devolveria al comprador de julio. La resolucion es de
        // `catastro`; lo que se mide aqui es que la fecha que le llega sea la que se pidio.
        CatastroEnMemoria catastro =
                new CatastroEnMemoria()
                        .conTitularEntre(
                                5L,
                                501L,
                                "PROPIETARIO_UNICO",
                                "100",
                                LocalDate.of(2020, 1, 1),
                                LocalDate.of(2026, 6, 30))
                        .conTitularEntre(
                                5L,
                                502L,
                                "PROPIETARIO_UNICO",
                                "100",
                                LocalDate.of(2026, 7, 1),
                                null);

        TitularPrincipalPorElPuerto titular = new TitularPrincipalPorElPuerto(catastro.titulares());

        assertThat(titular.principalDe(5L, LocalDate.of(2026, 3, 31))).contains(501L);
        assertThat(titular.principalDe(5L, LocalDate.of(2026, 9, 1))).contains(502L);
    }
}
