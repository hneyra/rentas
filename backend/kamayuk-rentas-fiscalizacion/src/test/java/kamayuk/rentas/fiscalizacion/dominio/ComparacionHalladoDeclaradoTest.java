package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado.LoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado.LoHallado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El contraste hallado/declarado (#49, RF-053, RF-055). Función pura: sin base y sin reloj.
 *
 * <p>El grupo «Omiso no es extemporáneo» es el AC 3 del issue.
 */
@DisplayName("#49 — Contraste hallado/declarado")
class ComparacionHalladoDeclaradoTest {

    private static final AreaM2 DECLARADA = AreaM2.de("120.00");
    private static final AreaM2 AMPLIADA = AreaM2.de("300.00");

    @Nested
    @DisplayName("Omiso no es extemporaneo (AC 3)")
    class OmisoNoEsExtemporaneo {

        @Test
        @DisplayName("quien no declaro es OMISO")
        void quienNoDeclaroEsOmiso() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.nada(), LoHallado.de(AMPLIADA, null)))
                    .isEqualTo(CondicionFiscalizada.OMISO);
        }

        @Test
        @DisplayName("quien declaro fuera de plazo NO es omiso: si lo declarado coincide, CONFORME")
        void quienDeclaroFueraDePlazoNoEsOmiso() {
            CondicionFiscalizada condicion =
                    ComparacionHalladoDeclarado.condicion(
                            LoDeclarado.fueraDePlazo(DECLARADA, "CASA_HABITACION"),
                            LoHallado.de(DECLARADA, "CASA_HABITACION"));

            assertThat(condicion)
                    .as(
                            "presentar tarde y no presentar son cosas distintas: lo primero es la"
                                    + " multa del art. 176, lo segundo una determinacion de oficio")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }

        @Test
        @DisplayName("quien declaro fuera de plazo y de menos es SUBVALUADOR, no OMISO")
        void quienDeclaroFueraDePlazoYDeMenosEsSubvaluador() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.fueraDePlazo(DECLARADA, null),
                                    LoHallado.de(AMPLIADA, null)))
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
        }

        @Test
        @DisplayName("el tipo impide construir «no declaro pero declaro fuera de plazo»")
        void elTipoImpideElContrasentido() {
            assertThatThrownBy(() -> new LoDeclarado(false, true, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cosas distintas");
        }
    }

    @Nested
    @DisplayName("Superficies")
    class Superficies {

        @Test
        @DisplayName("mas area hallada que declarada es SUBVALUADOR")
        void masAreaHalladaEsSubvaluador() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.enPlazo(DECLARADA, null),
                                    LoHallado.de(AMPLIADA, null)))
                    .isEqualTo(CondicionFiscalizada.SUBVALUADOR);
        }

        @Test
        @DisplayName("declarar de MAS no es un hallazgo contra el contribuyente")
        void declararDeMasNoEsUnHallazgo() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.enPlazo(AMPLIADA, null),
                                    LoHallado.de(DECLARADA, null)))
                    .as("la fiscalizacion busca lo que se dejo de declarar, no lo contrario")
                    .isEqualTo(CondicionFiscalizada.CONFORME);
        }

        @Test
        @DisplayName("la diferencia nunca es negativa, y sin los dos lados no existe")
        void laDiferenciaNuncaEsNegativa() {
            assertThat(ComparacionHalladoDeclarado.diferenciaDeArea(DECLARADA, AMPLIADA))
                    .isEqualTo(AreaM2.de("180.00"));
            assertThat(ComparacionHalladoDeclarado.diferenciaDeArea(AMPLIADA, DECLARADA))
                    .isEqualTo(AreaM2.CERO);
            assertThat(ComparacionHalladoDeclarado.diferenciaDeArea(null, AMPLIADA))
                    .as("sin las dos superficies no hay diferencia; cero diria que coincidieron")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Uso y no ubicado")
    class UsoYNoUbicado {

        @Test
        @DisplayName("misma area y otro uso es USO_DISTINTO")
        void mismaAreaYOtroUso() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.enPlazo(DECLARADA, "CASA_HABITACION"),
                                    LoHallado.de(DECLARADA, "COMERCIO")))
                    .isEqualTo(CondicionFiscalizada.USO_DISTINTO);
        }

        @Test
        @DisplayName("no ubicado gana a todo: no se afirma sobre lo que no se vio")
        void noUbicadoGanaATodo() {
            assertThat(
                            ComparacionHalladoDeclarado.condicion(
                                    LoDeclarado.nada(), LoHallado.noUbicado()))
                    .as("sin haberlo verificado, «omiso» seria una acusacion sin sustento")
                    .isEqualTo(CondicionFiscalizada.NO_UBICADO);
        }

        @Test
        @DisplayName("un predio no ubicado no puede traer area ni uso hallados")
        void noUbicadoNoTraeArea() {
            assertThatThrownBy(() -> new LoHallado(false, DECLARADA, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
