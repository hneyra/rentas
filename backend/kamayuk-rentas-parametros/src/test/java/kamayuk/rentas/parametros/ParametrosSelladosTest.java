package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.dominio.Vigencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #379 — El conjunto sellado conserva la vigencia de cada fila.
 *
 * <p>En el conjunto de un ejercicio entra toda fila que se solape con el ano, asi que «esta en el
 * conjunto» no dice si rige un dia concreto. Lo que estas pruebas fijan es la forma de la respuesta
 * a esa pregunta: la vigencia sellada si la hay, {@link Vigencia#SIEMPRE} para una fila sin fechas,
 * y nada para una llave que el conjunto no publica.
 *
 * <p>Que la vigencia llegue desde el snapshot de {@code normativa} —que {@code
 * LectorDeParametrosCacheados.armar} no la tire— lo mide {@code DeudasConBeneficioJdbcTest}, con el
 * lector de produccion y una campana de marzo a junio.
 *
 * <p>Los valores son <b>ficticios</b> (regla 5): lo que se verifica es el camino.
 */
@DisplayName("#379 — El conjunto sellado conserva la vigencia de cada fila")
class ParametrosSelladosTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Vigencia DE_MARZO_A_JUNIO =
            new Vigencia(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30));

    @Test
    @DisplayName("la fila con vigencia sellada la devuelve tal cual")
    void laVigenciaSelladaSaleTalCual() {
        ParametrosSellados conjunto =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("BENEFICIO_FICTICIO", "AMNISTIA", ValorNormativo.de("50"))
                        .vigencia("BENEFICIO_FICTICIO", "AMNISTIA", DE_MARZO_A_JUNIO)
                        .construir();

        assertThat(conjunto.vigenciaDe("BENEFICIO_FICTICIO", "AMNISTIA"))
                .contains(DE_MARZO_A_JUNIO);
        assertThat(conjunto.vigenciaDe("BENEFICIO_FICTICIO", "AMNISTIA").orElseThrow())
                .satisfies(v -> assertThat(v.vigenteEn(LocalDate.of(2026, 4, 15))).isTrue())
                .satisfies(v -> assertThat(v.vigenteEn(LocalDate.of(2026, 8, 28))).isFalse());
    }

    @Test
    @DisplayName("y conservarla no cambia ninguna lectura de las de siempre")
    void noCambiaLasLecturas() {
        ParametrosSellados conjunto =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("BENEFICIO_FICTICIO", "AMNISTIA", ValorNormativo.de("50"))
                        .texto("BENEFICIO_FICTICIO", "AMNISTIA", "TOTAL")
                        .vigencia("BENEFICIO_FICTICIO", "AMNISTIA", DE_MARZO_A_JUNIO)
                        .construir();

        assertThat(conjunto.numero("BENEFICIO_FICTICIO", "AMNISTIA"))
                .contains(ValorNormativo.de("50"));
        assertThat(conjunto.texto("BENEFICIO_FICTICIO", "AMNISTIA")).contains("TOTAL");
        assertThat(conjunto.clavesDe("BENEFICIO_FICTICIO")).containsExactly("AMNISTIA");
    }

    @Test
    @DisplayName("una fila sin fechas rige siempre, que es lo que dicen sus dos extremos nulos")
    void sinFechasRigeSiempre() {
        ParametrosSellados conjunto =
                ParametrosSellados.de(EJERCICIO, 1)
                        .texto("MODO_FICTICIO", null, "HALF_UP")
                        .construir();

        assertThat(conjunto.vigenciaDe("MODO_FICTICIO", null)).contains(Vigencia.SIEMPRE);
    }

    @Test
    @DisplayName("una llave que el conjunto no publica no tiene vigencia: no hay nada que rija")
    void loQueNoEstaNoTieneVigencia() {
        ParametrosSellados conjunto =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("BENEFICIO_FICTICIO", "AMNISTIA", ValorNormativo.de("50"))
                        .construir();

        assertThat(conjunto.vigenciaDe("BENEFICIO_FICTICIO", "OTRA")).isEmpty();
    }
}
