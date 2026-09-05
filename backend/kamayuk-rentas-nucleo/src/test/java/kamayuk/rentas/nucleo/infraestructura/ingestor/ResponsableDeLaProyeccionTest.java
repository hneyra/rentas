package kamayuk.rentas.nucleo.infraestructura.ingestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Una alerta a una persona con nombre» (ADR-0026 §4), sujeto por el arranque.
 *
 * <h2>Por que hace falta esta clase de prueba, medido</h2>
 *
 * <p>La bateria de ingestion mide que el aviso <b>llegue</b>, y con eso no basta: quitar la
 * exigencia de que el canal sea una direccion a la que se pueda entregar <b>no pone nada en rojo
 * alli</b> —esa prueba configura una direccion http de verdad, asi que la guarda no llega a
 * dispararse—. Lo que la exigencia impide es la instalacion que configura «jefe.rentas@municipio» y
 * cree que ha configurado una alerta.
 */
class ResponsableDeLaProyeccionTest {

    @Test
    @DisplayName("sin nombre o sin canal, el ingestor no arranca")
    void sinResponsableNoArranca() {
        assertThatThrownBy(() -> new ResponsableDeLaProyeccion("", "https://avisos/aqui"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A UNA PERSONA CON NOMBRE");
        assertThatThrownBy(() -> new ResponsableDeLaProyeccion("Jefe de Catastro", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kamayuk.rentas.ingestor.responsable");
    }

    @Test
    @DisplayName("y el canal tiene que ser una direccion a la que se pueda ENTREGAR")
    void elCanalTieneQuePoderRecibir() {
        // Es la diferencia deliberada con `ResponsableDeLaConciliacion` de `caja`, cuyo canal es
        // texto libre — y por eso P5D dejo su alerta declarada como «construida y no medida».
        assertThatThrownBy(
                        () ->
                                new ResponsableDeLaProyeccion(
                                        "Responsable de Catastro",
                                        "jefe.catastro@municipio.gob.pe"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http(s)")
                .hasMessageContaining("construida y no medida");
    }

    @Test
    @DisplayName("con nombre y canal entregable, arranca y los publica")
    void conNombreYCanalArranca() {
        ResponsableDeLaProyeccion responsable =
                new ResponsableDeLaProyeccion(
                        " Responsable de Catastro ", " https://avisos.municipio/gob ");
        assertThat(responsable.nombre()).isEqualTo("Responsable de Catastro");
        assertThat(responsable.canal()).isEqualTo("https://avisos.municipio/gob");
        assertThat(responsable.toString()).contains("Responsable de Catastro").contains("https://");
    }
}
