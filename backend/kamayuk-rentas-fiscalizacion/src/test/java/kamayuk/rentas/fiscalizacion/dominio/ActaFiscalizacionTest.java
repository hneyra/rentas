package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#45 — ActaFiscalizacion")
class ActaFiscalizacionTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final LocalDate VISITA = LocalDate.of(2026, 3, 15);

    @Test
    @DisplayName("un acta predial nueva no tiene id, y es predial")
    void unActaPredialNuevaNoTieneIdYEsPredial() {
        ActaFiscalizacion acta =
                ActaFiscalizacion.nuevaPredial(
                        1L,
                        1,
                        1L,
                        1L,
                        1L,
                        VISITA,
                        "J. Perez",
                        Hallazgo.CONFORME,
                        AreaM2.de("120.00"),
                        null,
                        "sin novedad",
                        OBSERVACION);

        assertThat(acta.esNueva()).isTrue();
        assertThat(acta.esPredial()).isTrue();
        assertThat(acta.estado()).isEqualTo(EstadoDeActa.ABIERTA);
    }

    @Test
    @DisplayName("un acta vehicular nunca lleva ficha ni area")
    void unActaVehicularNuncaLlevaFichaNiArea() {
        ActaFiscalizacion acta =
                ActaFiscalizacion.nuevaVehicular(
                        1L,
                        1,
                        1L,
                        1L,
                        VISITA,
                        "J. Perez",
                        Hallazgo.OMISO,
                        "no declarado",
                        OBSERVACION);

        assertThat(acta.esPredial()).isFalse();
        assertThat(acta.fichaId()).isNull();
        assertThat(acta.areaHallada()).isNull();
        assertThat(acta.vehiculoId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("un acta no puede ser de predio y de vehiculo a la vez, ni de ninguno")
    void unActaNoPuedeSerDeLosDosNiDeNinguno() {
        assertThatThrownBy(
                        () ->
                                new ActaFiscalizacion(
                                        null,
                                        1L,
                                        1,
                                        1L,
                                        1L,
                                        1L,
                                        null,
                                        VISITA,
                                        "J. Perez",
                                        null,
                                        null,
                                        null,
                                        null,
                                        EstadoDeActa.ABIERTA,
                                        OBSERVACION))
                .as("predio y vehiculo a la vez")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                new ActaFiscalizacion(
                                        null,
                                        1L,
                                        1,
                                        1L,
                                        null,
                                        null,
                                        null,
                                        VISITA,
                                        "J. Perez",
                                        null,
                                        null,
                                        null,
                                        null,
                                        EstadoDeActa.ABIERTA,
                                        OBSERVACION))
                .as("ni predio ni vehiculo")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("solo un acta predial referencia una ficha")
    void soloUnActaPredialReferenciaUnaFicha() {
        assertThatThrownBy(
                        () ->
                                new ActaFiscalizacion(
                                        null,
                                        1L,
                                        1,
                                        1L,
                                        null,
                                        1L,
                                        5L,
                                        VISITA,
                                        "J. Perez",
                                        null,
                                        null,
                                        null,
                                        null,
                                        EstadoDeActa.ABIERTA,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sin observacion no se construye (regla 10)")
    void sinObservacionNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                ActaFiscalizacion.nuevaVehicular(
                                        1L, 1, 1L, 1L, VISITA, "J. Perez", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("la version empieza en 1")
    void laVersionEmpiezaEn1() {
        assertThatThrownBy(
                        () ->
                                ActaFiscalizacion.nuevaVehicular(
                                        1L, 0, 1L, 1L, VISITA, "J. Perez", null, null, OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("#599 — el uso hallado, y el quinto valor que sostiene")
    class ElUsoHallado {

        @Test
        @DisplayName("un acta predial lo consigna, y lo devuelve recortado")
        void unActaPredialLoConsigna() {
            ActaFiscalizacion acta = predialCon(Hallazgo.USO_DISTINTO, "  COMERCIO  ");

            assertThat(acta.usoHallado()).isEqualTo("COMERCIO");
        }

        @Test
        @DisplayName("el vacio es «no se consigno», no una cadena vacia")
        void elVacioEsQueNoSeConsigno() {
            assertThat(predialCon(Hallazgo.CONFORME, "   ").usoHallado()).isNull();
        }

        @Test
        @DisplayName("un acta que anota USO_DISTINTO sin el uso observado no se construye")
        void usoDistintoSinUsoNoSeConstruye() {
            assertThatThrownBy(() -> predialCon(Hallazgo.USO_DISTINTO, null))
                    .as(
                            "es el defecto que #546 nombro al negarse a anadir el valor: un acta"
                                    + " que afirma un hallazgo que no puede sustentar")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USO_DISTINTO");
        }

        @Test
        @DisplayName("un acta vehicular no consigna uso hallado: un vehiculo no declara uso")
        void unActaVehicularNoConsignaUso() {
            assertThatThrownBy(
                            () ->
                                    new ActaFiscalizacion(
                                            null,
                                            1L,
                                            1,
                                            1L,
                                            null,
                                            7L,
                                            null,
                                            VISITA,
                                            "J. Perez",
                                            Hallazgo.CONFORME,
                                            null,
                                            "COMERCIO",
                                            null,
                                            EstadoDeActa.ABIERTA,
                                            OBSERVACION))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("uso hallado");
        }

        @Test
        @DisplayName("y por eso un acta vehicular tampoco puede anotar USO_DISTINTO")
        void unActaVehicularNoPuedeAnotarUsoDistinto() {
            // No hace falta un invariante propio: USO_DISTINTO exige el uso observado y el acta
            // vehicular no lo puede llevar, asi que las dos reglas juntas lo impiden. Esta
            // prueba fija esa consecuencia, que es la que `LiquidarFiscalizacion` da por cierta
            // al no traducir el hallazgo a ninguna condicion vehicular.
            assertThatThrownBy(
                            () ->
                                    ActaFiscalizacion.nuevaVehicular(
                                            1L,
                                            1,
                                            1L,
                                            7L,
                                            VISITA,
                                            "J. Perez",
                                            Hallazgo.USO_DISTINTO,
                                            null,
                                            OBSERVACION))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USO_DISTINTO");
        }

        @Test
        @DisplayName("el uso no se reescribe: se compara ignorando mayusculas, no normalizando")
        void elUsoNoSeReescribe() {
            assertThat(predialCon(Hallazgo.USO_DISTINTO, "Casa habitación").usoHallado())
                    .as(
                            "el lado declarado es ficha_catastral.uso, texto libre por"
                                    + " municipalidad: reescribirlo aqui cambiaria lo que el acta"
                                    + " dice que se vio")
                    .isEqualTo("Casa habitación");
        }

        private ActaFiscalizacion predialCon(
                Hallazgo hallazgo, @org.jspecify.annotations.Nullable String usoHallado) {
            return ActaFiscalizacion.nuevaPredial(
                    1L,
                    1,
                    1L,
                    1L,
                    null,
                    VISITA,
                    "J. Perez",
                    hallazgo,
                    AreaM2.de("120.00"),
                    usoHallado,
                    null,
                    OBSERVACION);
        }
    }
}
