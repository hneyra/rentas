package kamayuk.rentas.licencias.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.catastro.prueba.TerritorioEnMemoria;
import kamayuk.rentas.licencias.dominio.CompatibilidadConLaZona;
import kamayuk.rentas.licencias.dominio.ComprobacionDelTerritorio;
import kamayuk.rentas.licencias.dominio.OrigenDeLaZona;
import kamayuk.rentas.licencias.dominio.RespuestaDelTerritorio;
import kamayuk.rentas.licencias.dominio.TerritorioDeLaLicencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Lo que el territorio contesta, y lo que con eso se decide (#43, AC-2 a AC-5).
 *
 * <p>Sin base de datos y sin Spring: {@link ComprobarElTerritorio} son tres lecturas y una
 * composicion, y {@link CompatibilidadConLaZona} es una funcion pura. Lo que se mide aqui es que
 * las cuatro respuestas de {@code catastro} lleguen <b>distinguibles</b> y que ninguna ausencia se
 * lea como una respuesta favorable. Que la emision se niegue de verdad lo mide {@code
 * LicenciaDeFuncionamientoJdbcTest} contra PostgreSQL.
 */
@DisplayName("#43 — el territorio se pregunta antes de autorizar")
class ElTerritorioDecideLaLicenciaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final long PREDIO = 4200L;

    @Nested
    @DisplayName("La compatibilidad del giro con la zona")
    class LaCompatibilidad {

        @Test
        @DisplayName("la zona esta entre las que el giro declara")
        void cabe() {
            assertThat(CompatibilidadConLaZona.evaluar("CZ", "CZ, RDM, RDA"))
                    .isEqualTo(CompatibilidadConLaZona.COMPATIBLE);
        }

        @Test
        @DisplayName("y si no esta, no cabe")
        void noCabe() {
            assertThat(CompatibilidadConLaZona.evaluar("I2", "CZ, RDM, RDA"))
                    .isEqualTo(CompatibilidadConLaZona.INCOMPATIBLE);
        }

        @Test
        @DisplayName("no se compara por «contiene»: RDM no cabe en RDMA")
        void noSeComparaPorSubcadena() {
            assertThat(CompatibilidadConLaZona.evaluar("RDM", "RDMA, CZ"))
                    .as(
                            "una licencia autorizada porque una zona es subcadena de otra es un"
                                    + " acierto por casualidad, y el vocabulario de los dos lados no"
                                    + " esta normalizado")
                    .isEqualTo(CompatibilidadConLaZona.INCOMPATIBLE);
        }

        @Test
        @DisplayName("y si el giro no declara zonas, NO se puede decidir: no es ni si ni no")
        void sinZonasDeclaradasNoSePuedeDecidir() {
            assertThat(CompatibilidadConLaZona.evaluar("CZ", null))
                    .as(
                            "que la municipalidad no lo haya clasificado no significa que el giro"
                                    + " no quepa en ninguna zona (D-02b)")
                    .isEqualTo(CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR);
            assertThat(CompatibilidadConLaZona.evaluar("CZ", "   "))
                    .isEqualTo(CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR);
            assertThat(CompatibilidadConLaZona.evaluar(null, "CZ, RDM"))
                    .as("y sin zona del territorio tampoco hay con que comparar")
                    .isEqualTo(CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR);
        }
    }

    @Nested
    @DisplayName("Los cuatro desenlaces de preguntar, y ninguno se colapsa (AC-5)")
    class LosCuatroDesenlaces {

        @Test
        @DisplayName("el territorio contesta las tres cosas y ninguna se opone")
        void todoEnRegla() {
            ComprobacionDelTerritorio comprobacion =
                    comprobar(
                            new TerritorioEnMemoria().conTodoEnRegla(PREDIO, "CZ", "ORD-2024-01"),
                            "CZ, RDM");

            assertThat(comprobacion.zona()).isEqualTo(RespuestaDelTerritorio.RESPONDIO);
            assertThat(comprobacion.zonaDelTerritorio()).isEqualTo("CZ");
            assertThat(comprobacion.ordenanzaDeLaZona())
                    .as("sin la ordenanza, una denegacion por zona no se puede notificar")
                    .isEqualTo("ORD-2024-01");
            assertThat(comprobacion.aLaFecha())
                    .as("la fecha con que se resolvio viaja dentro (regla 9)")
                    .isEqualTo(HOY);
            assertThat(comprobacion.todoComprobadoYFavorable()).isTrue();
            assertThat(comprobacion.motivo()).isNull();
        }

        @Test
        @DisplayName("el predio NO CONSTA, y eso no es «no hay riesgo»")
        void noConsta() {
            ComprobacionDelTerritorio comprobacion =
                    comprobar(new TerritorioEnMemoria(), "CZ, RDM");

            assertThat(comprobacion.zona()).isEqualTo(RespuestaDelTerritorio.NO_CONSTA);
            assertThat(comprobacion.riesgo()).isEqualTo(RespuestaDelTerritorio.NO_CONSTA);
            assertThat(comprobacion.hayRiesgoNoMitigable())
                    .as(
                            "es `false` porque no hay hecho que afirmar, y por eso"
                                    + " `riesgoNoMitigableComprobado()` es lo que decide y no este"
                                    + " campo")
                    .isFalse();
            assertThat(comprobacion.riesgoNoMitigableComprobado()).isFalse();
            assertThat(comprobacion.todoComprobadoYFavorable())
                    .as("no salio nada malo NO es lo mismo que salio bien (AC-4)")
                    .isFalse();
            assertThat(comprobacion.motivo()).contains("no consta");
        }

        @Test
        @DisplayName("`catastro` no contesta, y tampoco es «no hay riesgo»")
        void noSePudoPreguntar() {
            ComprobacionDelTerritorio comprobacion =
                    comprobar(new TerritorioEnMemoria().caido(), "CZ, RDM");

            assertThat(comprobacion.zona()).isEqualTo(RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR);
            assertThat(comprobacion.riesgo())
                    .isEqualTo(RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR);
            assertThat(comprobacion.itse()).isEqualTo(RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR);
            assertThat(comprobacion.todoComprobadoYFavorable()).isFalse();
            assertThat(comprobacion.motivo()).contains("no se pudo preguntar");
        }

        @Test
        @DisplayName("y «no consta» y «no se pudo preguntar» NO dicen lo mismo")
        void lasDosAusenciasSeDistinguen() {
            ComprobacionDelTerritorio noConsta = comprobar(new TerritorioEnMemoria(), "CZ");
            ComprobacionDelTerritorio caido = comprobar(new TerritorioEnMemoria().caido(), "CZ");

            assertThat(noConsta.zona()).isNotEqualTo(caido.zona());
            assertThat(noConsta.motivo())
                    .as(
                            "una se arregla cargando el plano y la otra levantando el despliegue:"
                                    + " decir la equivocada manda a mirar donde no es")
                    .isNotEqualTo(caido.motivo());
        }

        @Test
        @DisplayName("sin predio no se pregunta, que es la cuarta y no es un fallo de nadie")
        void sinPredioNoSePregunta() {
            ComprobacionDelTerritorio comprobacion =
                    new ComprobarElTerritorio(new TerritorioEnMemoria(), new TerritorioEnMemoria())
                            .de(null, HOY, "CZ");

            assertThat(comprobacion.zona()).isEqualTo(RespuestaDelTerritorio.NO_SE_PREGUNTO);
            assertThat(comprobacion.motivo()).contains("no declara predio");
        }
    }

    @Nested
    @DisplayName("Lo que se guarda en la licencia (AC-3)")
    class LoQueSeGuarda {

        @Test
        @DisplayName("cuando el territorio contesto, es el que sostiene el acto")
        void mandaElTerritorio() {
            TerritorioDeLaLicencia guardado =
                    TerritorioDeLaLicencia.de(
                            comprobar(
                                    new TerritorioEnMemoria()
                                            .conTodoEnRegla(PREDIO, "CZ", "ORD-2024-01"),
                                    "CZ"));

            assertThat(guardado.origen()).isEqualTo(OrigenDeLaZona.TERRITORIO);
            assertThat(guardado.zonaDelTerritorio()).isEqualTo("CZ");
        }

        @Test
        @DisplayName("y cuando no contesto, sostiene la declarada — y queda dicho que no se pudo")
        void mandaLaDeclarada() {
            TerritorioDeLaLicencia guardado =
                    TerritorioDeLaLicencia.de(comprobar(new TerritorioEnMemoria().caido(), "CZ"));

            assertThat(guardado.origen()).isEqualTo(OrigenDeLaZona.DECLARADA);
            assertThat(guardado.zonaDelTerritorio()).isNull();
            assertThat(guardado.comprobacion())
                    .as(
                            "«se autorizo sin comprobar» y «se comprobo y salio bien» tienen que"
                                    + " ser distinguibles dentro de dos anos")
                    .contains("no se pudo preguntar");
        }

        @Test
        @DisplayName("y una licencia no puede decir que se sostiene en una zona que no trae")
        void noSePuedeAfirmarUnaComprobacionQueNoSeHizo() {
            assertThatThrownBy(
                            () ->
                                    new TerritorioDeLaLicencia(
                                            null, null, OrigenDeLaZona.TERRITORIO, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sin el codigo con que se comprobo");
        }
    }

    @Nested
    @DisplayName("El tipo no deja afirmar un hecho que no vino (AC-4)")
    class ElTipoNoDejaAfirmarLoQueNoVino {

        @Test
        @DisplayName("una consulta que no respondio no puede traer riesgo no mitigable")
        void unRiesgoQueNadieContesto() {
            assertThatThrownBy(
                            () ->
                                    new ComprobacionDelTerritorio(
                                            HOY,
                                            RespuestaDelTerritorio.NO_CONSTA,
                                            null,
                                            null,
                                            RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR,
                                            true,
                                            RespuestaDelTerritorio.NO_CONSTA,
                                            0,
                                            CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lo que no se pudo preguntar no se sabe");
        }

        @Test
        @DisplayName("ni una zona que nadie contesto")
        void unaZonaQueNadieContesto() {
            assertThatThrownBy(
                            () ->
                                    new ComprobacionDelTerritorio(
                                            HOY,
                                            RespuestaDelTerritorio.NO_CONSTA,
                                            "CZ",
                                            null,
                                            RespuestaDelTerritorio.RESPONDIO,
                                            false,
                                            RespuestaDelTerritorio.RESPONDIO,
                                            0,
                                            CompatibilidadConLaZona.COMPATIBLE,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no puede sostener una licencia");
        }

        @Test
        @DisplayName("y la que si respondio se construye sin protestar: el contraste")
        void loQueSiRespondioSeConstruye() {
            assertThatCode(
                            () ->
                                    new ComprobacionDelTerritorio(
                                            HOY,
                                            RespuestaDelTerritorio.RESPONDIO,
                                            "CZ",
                                            "ORD-2024-01",
                                            RespuestaDelTerritorio.RESPONDIO,
                                            true,
                                            RespuestaDelTerritorio.RESPONDIO,
                                            2,
                                            CompatibilidadConLaZona.COMPATIBLE,
                                            null))
                    .as("sin esto, las dos de arriba pasarian con un tipo que no admitiera nada")
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------

    private static ComprobacionDelTerritorio comprobar(
            TerritorioEnMemoria territorio, String zonasDelGiro) {
        return new ComprobarElTerritorio(territorio, territorio).de(PREDIO, HOY, zonasDelGiro);
    }
}
