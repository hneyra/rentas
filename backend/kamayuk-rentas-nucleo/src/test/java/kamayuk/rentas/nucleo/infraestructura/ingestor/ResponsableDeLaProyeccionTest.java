package kamayuk.rentas.nucleo.infraestructura.ingestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.plataforma.ResponsableDeOperacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Una alerta a una persona con nombre» (ADR-0026 §4), sujeto por el arranque.
 *
 * <h2>Por que hace falta esta clase de prueba, medido</h2>
 *
 * <p>La bateria de ingestion mide que el aviso <b>llegue</b> a una direccion http de verdad. Lo que
 * no puede ver es la otra mitad: <b>con que canal arranca el ingestor</b>. Hasta rentas#70 esta
 * clase exigia http(s) y la prueba de aqui lo fijaba — y ese era el defecto: los dos stacks
 * declaran un correo, {@code operaciones@example.pe}, y el {@code CronJob} no arrancaba en ningun
 * ambiente. Ahora fija lo contrario, con los otros tres consumidores: cualquier canal arranca, y
 * solo uno http(s) se entrega.
 *
 * <p>Desde #377 la clase es {@link ResponsableDeOperacion}, en {@code plataforma}, la misma para
 * los dos consumidores de buzon; lo que sigue siendo del ingestor es el mensaje que nombra SUS
 * propiedades, y eso es lo que se mide aqui a traves de {@code ConfiguracionDelIngestor}.
 */
class ResponsableDeLaProyeccionTest {

    @Test
    @DisplayName("sin nombre o sin canal, el ingestor no arranca")
    void sinResponsableNoArranca() {
        assertThatThrownBy(
                        () ->
                                ConfiguracionDelIngestor.responsableDeLaProyeccion(
                                        "", "https://avisos/aqui"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A UNA PERSONA CON NOMBRE");
        assertThatThrownBy(
                        () ->
                                ConfiguracionDelIngestor.responsableDeLaProyeccion(
                                        "Jefe de Catastro", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kamayuk.rentas.ingestor.responsable");
    }

    @Test
    @DisplayName("un correo arranca y no se entrega; una direccion http(s) arranca y se entrega")
    void unCorreoArrancaYSoloUnaDireccionSeEntrega() {
        // El valor que los dos stacks declaran de verdad (`kamayuk:canalDeOperacion`). Con la regla
        // vieja esto lanzaba, y el `CronJob` del ingestor no arrancaba en ningun ambiente (#70).
        ResponsableDeOperacion conCorreo =
                ConfiguracionDelIngestor.responsableDeLaProyeccion(
                        "Equipo de operacion", "operaciones@example.pe");
        assertThat(conCorreo.seLeEntrega())
                .as("un correo no se puede entregar con un POST: solo se nombra en el ERROR")
                .isFalse();

        assertThat(
                        ConfiguracionDelIngestor.responsableDeLaProyeccion(
                                        "Equipo", "https://avisos.municipio/gob")
                                .seLeEntrega())
                .as("una direccion https SI se entrega, que es lo que C-8 mide ejecutandolo")
                .isTrue();
        assertThat(
                        ConfiguracionDelIngestor.responsableDeLaProyeccion(
                                        "Equipo", "http://avisos.local/x")
                                .seLeEntrega())
                .isTrue();
    }

    @Test
    @DisplayName("con nombre y canal entregable, arranca y los publica")
    void conNombreYCanalArranca() {
        ResponsableDeOperacion responsable =
                ConfiguracionDelIngestor.responsableDeLaProyeccion(
                        " Responsable de Catastro ", " https://avisos.municipio/gob ");
        assertThat(responsable.nombre()).isEqualTo("Responsable de Catastro");
        assertThat(responsable.canal()).isEqualTo("https://avisos.municipio/gob");
        assertThat(responsable.toString()).contains("Responsable de Catastro").contains("https://");
    }
}
