package kamayuk.rentas.parametros.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #25 AC-3 — {@code normativa} ausente no impide usar lo que ya esta sellado aqui.
 *
 * <p>Es lo que ADR-0025 §Consecuencias promete —«{@code normativa} puede caerse sin detener nada
 * que ya haya resuelto su conjunto; solo bloquea abrir una corrida nueva»— y lo que el mensaje de
 * {@link PublicadorDeNormativa.NormativaInalcanzable} lleva escrito desde P5B. Hasta #25 <b>este
 * controlador no tenia ninguna prueba</b>, aunque su {@code build.gradle.kts} declarara {@code
 * spring-test} para escribirla: el andamio estaba y la prueba no.
 *
 * <p>Se prueba contra el <b>puerto</b> y no contra PostgreSQL a proposito: lo que se decide aqui es
 * si el controlador se repliega o deja subir la excepcion, y eso no depende de ninguna tabla. Que
 * la copia local conteste de verdad —y con RLS— lo mide {@code SinNormativaFronteraTest} contra un
 * motor real.
 */
@DisplayName("#25 — Las senias del ejercicio, con `normativa` caido")
class EjercicioParametrizadoControllerTest {

    private static final int EJERCICIO = 2026;

    /** El conjunto que este sistema YA tiene descargado en su copia local. */
    private static final long CONJUNTO_LOCAL = 77L;

    private static final int VERSION_LOCAL = 3;

    private static PublicadorDeNormativa.NormativaInalcanzable caida(MotivoDeInalcanzable motivo) {
        return new PublicadorDeNormativa.NormativaInalcanzable(
                motivo, "resolver el conjunto de " + EJERCICIO, null);
    }

    @Nested
    @DisplayName("Con `normativa` caido")
    class ConNormativaCaido {

        @Test
        @DisplayName("AC-3 — si el conjunto ya esta descargado, contesta 200 con sus senias")
        void seSirveLoQueYaEstaDescargado() {
            var controlador =
                    new EjercicioParametrizadoController(
                            new LectorQueNoAlcanzaNormativa(
                                    MotivoDeInalcanzable.NO_CONTESTA,
                                    new LectorDeParametros.ConjuntoYaDescargado(
                                            CONJUNTO_LOCAL,
                                            new Ejercicio(EJERCICIO),
                                            VERSION_LOCAL)));

            EjercicioParametrizadoController.EjercicioParametrizadoResource senias =
                    controlador.ejercicio(EJERCICIO);

            assertThat(senias.ejercicio()).isEqualTo(EJERCICIO);
            assertThat(senias.sellado()).isTrue();
            assertThat(senias.conjuntoId()).isEqualTo(CONJUNTO_LOCAL);
            assertThat(senias.version()).isEqualTo(VERSION_LOCAL);
        }

        @Test
        @DisplayName("y da igual por que no se alcanza: sin configurar se repliega igual")
        void tambienSiLaUrlNoEstaConfigurada() {
            var controlador =
                    new EjercicioParametrizadoController(
                            new LectorQueNoAlcanzaNormativa(
                                    MotivoDeInalcanzable.SIN_CONFIGURAR,
                                    new LectorDeParametros.ConjuntoYaDescargado(
                                            CONJUNTO_LOCAL,
                                            new Ejercicio(EJERCICIO),
                                            VERSION_LOCAL)));

            assertThat(controlador.ejercicio(EJERCICIO).conjuntoId()).isEqualTo(CONJUNTO_LOCAL);
        }

        @Test
        @DisplayName(
                "EL CONTRASTE: sin nada descargado NO se contesta «no esta sellado», se propaga")
        void sinNadaDescargadoSePropaga() {
            var controlador =
                    new EjercicioParametrizadoController(
                            new LectorQueNoAlcanzaNormativa(
                                    MotivoDeInalcanzable.NO_CONTESTA, null));

            // Un `sellado: false` aqui seria una frase falsa —«esta municipalidad no ha
            // parametrizado el ejercicio»— indistinguible de la verdadera, y mandaria a buscar
            // una ordenanza cuando lo que falta es un despliegue. Solo resolver uno NUEVO falla.
            assertThatThrownBy(() -> controlador.ejercicio(EJERCICIO))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class);
        }
    }

    @Nested
    @DisplayName("Con `normativa` sano, nada cambia")
    class ConNormativaSano {

        @Test
        @DisplayName("contesta las senias del conjunto vigente, sin mirar la copia local")
        void contestaLoVigente() {
            var controlador = new EjercicioParametrizadoController(new LectorSano());

            EjercicioParametrizadoController.EjercicioParametrizadoResource senias =
                    controlador.ejercicio(EJERCICIO);

            assertThat(senias.sellado()).isTrue();
            assertThat(senias.conjuntoId()).isEqualTo(91L);
            assertThat(senias.version()).isEqualTo(5);
        }

        @Test
        @DisplayName("y un ejercicio sin sellar sigue siendo un 200 diciendo que no")
        void sinSellarSigueSiendoDoscientos() {
            var controlador = new EjercicioParametrizadoController(new LectorSinSellar());

            EjercicioParametrizadoController.EjercicioParametrizadoResource senias =
                    controlador.ejercicio(EJERCICIO);

            assertThat(senias.sellado()).isFalse();
            assertThat(senias.conjuntoId()).isNull();
            assertThat(senias.version()).isNull();
        }
    }

    // ------------------------------------------------------------------
    //  Dobles del puerto
    // ------------------------------------------------------------------

    /** `normativa` no contesta, y la copia local tiene lo que se le diga (o nada). */
    private record LectorQueNoAlcanzaNormativa(
            MotivoDeInalcanzable motivo, LectorDeParametros.@Nullable ConjuntoYaDescargado local)
            implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            throw caida(motivo);
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw caida(motivo);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            throw caida(motivo);
        }

        @Override
        public Optional<ConjuntoYaDescargado> loQueYaEstaDescargado(Ejercicio ejercicio) {
            return Optional.ofNullable(local);
        }
    }

    /** `normativa` contesta y el conjunto 91 esta descargado. */
    private static final class LectorSano implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return porConjunto(IdentificadorDeConjunto.de(91L));
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return ParametrosSellados.de(new Ejercicio(EJERCICIO), 5).construir();
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(91L);
        }
    }

    /** `normativa` contesta y dice que ese ejercicio no tiene conjunto sellado. */
    private static final class LectorSinSellar implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            throw new EjercicioSinSellar(ejercicio);
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw new EjercicioSinSellar(new Ejercicio(EJERCICIO));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            throw new EjercicioSinSellar(ejercicio);
        }
    }
}
