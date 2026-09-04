package kamayuk.rentas.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * La guarda de las cargas de demostracion (#290), sin base de datos: lo que decide es el regimen de
 * la instalacion, y ese ya se prueba contra PostgreSQL en {@code RegimenDeLaInstalacionJdbcTest}.
 *
 * <p>Lo que se verifica aqui es que la guarda <b>muerde</b> —una instalacion que no es de
 * demostracion no deja pasar— y que pregunta <b>dentro de una transaccion</b>: sin ella, {@code
 * current_setting('app.municipalidad_id')} no tiene valor y la pregunta no se puede responder.
 */
@DisplayName("#290 — Sembrar datos ficticios solo en una instalacion de demostracion")
class SoloEnDemostracionTest {

    @Test
    @DisplayName("una instalacion de demostracion deja pasar")
    void unaInstalacionDeDemostracionDejaPasar() {
        SoloEnDemostracion guarda = new SoloEnDemostracion(RegimenDeLaInstalacion.DEMOSTRACION);

        assertThatCode(() -> guarda.exigirlo("ocho contribuyentes inventados"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una instalacion real no deja pasar, y el mensaje dice que se iba a sembrar")
    void unaInstalacionRealNoDejaPasar() {
        SoloEnDemostracion guarda = new SoloEnDemostracion(RegimenDeLaInstalacion.REAL);

        assertThatThrownBy(() -> guarda.exigirlo("ocho contribuyentes inventados"))
                .isInstanceOf(SoloEnDemostracion.NoEsInstalacionDeDemostracion.class)
                .hasMessageContaining("es_demostracion")
                .hasMessageContaining("ocho contribuyentes inventados");
    }

    @Test
    @DisplayName("la pregunta va dentro de una transaccion de solo lectura")
    void laPreguntaVaDentroDeUnaTransaccion() throws NoSuchMethodException {
        // Sin la transaccion, RegimenDeLaInstalacionJdbc pregunta por una conexion que no
        // trae el SET LOCAL app.municipalidad_id y la consulta falla; con SET SESSION en
        // vez de SET LOCAL, responderia por la municipalidad de otra peticion (regla 3).
        // Que sea de solo lectura importa aparte: una guarda que abriera una transaccion
        // de escritura seria un sitio mas desde el que escribir sin observacion.
        Method exigirlo = SoloEnDemostracion.class.getMethod("exigirlo", String.class);
        Transactional anotacion = exigirlo.getAnnotation(Transactional.class);

        assertThat(anotacion)
                .as("la guarda abre su propia transaccion: en batch no hay otra")
                .isNotNull();
        assertThat(anotacion.readOnly()).isTrue();
    }
}
