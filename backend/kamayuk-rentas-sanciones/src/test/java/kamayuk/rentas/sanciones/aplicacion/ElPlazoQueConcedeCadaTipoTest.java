package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * #410 — {@link PlazosDeSancionesParametrizados.Vigentes#queConcede}: qué plazo concede cada
 * resolución de gerencia, sin base de datos.
 *
 * <p>Las tres llaves se siembran con <b>cifras distintas</b> —y ninguna igual a la de otra—: con la
 * misma cifra en dos, responder con la llave de otro tipo daría el mismo plazo y la prueba no lo
 * vería. La prueba JDBC de la corrida, {@code ElPlazoQueConcedeCadaResolucionJdbcTest}, cubre la
 * RIS y la ordinaria de punta a punta; aquí se fija además la sancionadora, que ningún caso de esa
 * clase notifica, y lo que ocurre si la llave del recurso falta.
 */
@DisplayName("#410 — El plazo que concede cada tipo de resolucion de gerencia")
class ElPlazoQueConcedeCadaTipoTest {

    private static final LocalDate UN_DIA = LocalDate.of(2026, 8, 3);

    private static final Map<String, String> LAS_TRES =
            Map.of(
                    "RG_ORDINARIA_CUMPLIMIENTO", "7 DIAS_HABILES",
                    "RG_RECURSO", "15 DIAS_HABILES",
                    "DESCARGO_PAPELETA", "5 DIAS_HABILES");

    @Test
    @DisplayName("la ordinaria concede el plazo de PAGO: 7 dias habiles, rotulado asi")
    void laOrdinariaConcedeElDePago() {
        PlazosDeSancionesParametrizados.PlazoConcedido concedido =
                vigentes(LAS_TRES).queConcede(TipoDeResolucionDeGerencia.ORDINARIA);

        assertThat(concedido.rotulo()).isEqualTo("Plazo de pago");
        assertThat(concedido.plazo()).hasToString("7 DIAS_HABILES");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = TipoDeResolucionDeGerencia.class,
            names = {"SANCIONADORA", "ADMINISTRATIVA"})
    @DisplayName("la sancionadora y la RIS conceden el de IMPUGNARLAS: 15 dias habiles")
    void lasDemasConcedenElDeImpugnar(TipoDeResolucionDeGerencia tipo) {
        PlazosDeSancionesParametrizados.PlazoConcedido concedido =
                vigentes(LAS_TRES).queConcede(tipo);

        assertThat(concedido.rotulo()).isEqualTo("Plazo para impugnar");
        assertThat(concedido.plazo())
                .as(
                        "vencido sin recurso, el acto queda firme: contar el plazo de pago de la"
                                + " ordinaria lo daria por firme ocho dias habiles despues de"
                                + " notificado, con el recurso todavia abierto")
                .hasToString("15 DIAS_HABILES");
    }

    @Test
    @DisplayName("sin la llave del recurso, la RIS no se inventa un plazo: nombra la que falta")
    void sinLaDelRecursoNombraLaLlave() {
        PlazosDeSancionesParametrizados.Vigentes vigentes =
                vigentes(
                        Map.of(
                                "RG_ORDINARIA_CUMPLIMIENTO", "7 DIAS_HABILES",
                                "DESCARGO_PAPELETA", "5 DIAS_HABILES"));

        assertThatThrownBy(() -> vigentes.queConcede(TipoDeResolucionDeGerencia.ADMINISTRATIVA))
                .as(
                        "y no cae en el de la ordinaria, que esta ahi sellado: tomarlo seria el"
                                + " defecto de #410 con otra forma")
                .isInstanceOfSatisfying(
                        PlazosDeSancionesParametrizados.PlazoSinParametrizar.class,
                        falta -> assertThat(falta.llave()).contains("PLAZO:RG_RECURSO"));
    }

    // ------------------------------------------------------------------

    private static PlazosDeSancionesParametrizados.Vigentes vigentes(Map<String, String> plazos) {
        return new PlazosDeSancionesParametrizados(new ConjuntoDeLaPrueba(plazos))
                .aLaFechaDe(UN_DIA);
    }

    /** Un {@link LectorDeParametros} con un solo conjunto sellado: el de la siembra. */
    private record ConjuntoDeLaPrueba(Map<String, String> plazos) implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            plazos.forEach((clave, valor) -> constructor.texto("PLAZO", clave, valor));
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(2026));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1L);
        }
    }
}
