package kamayuk.rentas.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kamayuk.rentas.plataforma.ViajeDeRed.ConTransaccionAbierta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * #450 — La guarda de la costura con la red: avisa en produccion, falla en las pruebas.
 *
 * <p>Las dos ramas se prueban con el modo explicito, sin tocar la propiedad de sistema: la que
 * ponen las pruebas del arbol es {@code fallar}, y cambiarla aqui la cambiaria para todas las que
 * corran en el mismo proceso.
 */
@DisplayName("#450 — ningun viaje de red con una conexion tomada")
class ViajeDeRedTest {

    @AfterEach
    void cerrarLaTransaccion() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("sin transaccion abierta no dice nada, en ninguno de los dos modos")
    void sinTransaccionNoDiceNada() {
        assertThatCode(
                        () -> {
                            ViajeDeRed.comprobar(
                                    "caja", "/recibos/1", "leer", ConTransaccionAbierta.FALLAR);
                            ViajeDeRed.comprobar(
                                    "caja", "/recibos/1", "leer", ConTransaccionAbierta.AVISAR);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("con una transaccion abierta, en las pruebas falla nombrando vecino y ruta")
    void enLasPruebasFalla() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(
                        () ->
                                ViajeDeRed.comprobar(
                                        "catastro",
                                        "/predios/7/ficha",
                                        "leer la ficha",
                                        ConTransaccionAbierta.FALLAR))
                .isInstanceOf(ViajeDeRed.ConLaConexionTomada.class)
                .hasMessageContaining("#450")
                .hasMessageContaining("`catastro`")
                .hasMessageContaining("/predios/7/ficha");
    }

    @Test
    @DisplayName("y en produccion deja un WARN con el vecino y la ruta, y deja salir")
    void enProduccionAvisa() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Logger registro = (Logger) LoggerFactory.getLogger(ViajeDeRed.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            assertThatCode(
                            () ->
                                    ViajeDeRed.comprobar(
                                            "normativa",
                                            "/conjuntos?ejercicio=2026",
                                            "resolver el conjunto",
                                            ConTransaccionAbierta.AVISAR))
                    .as(
                            "un llamador que el diagnostico no vio no puede dejar de atender por"
                                    + " esto: el defecto es de rendimiento, no de datos")
                    .doesNotThrowAnyException();
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list)
                .singleElement()
                .satisfies(
                        aviso -> {
                            assertThat(aviso.getLevel()).isEqualTo(Level.WARN);
                            assertThat(aviso.getFormattedMessage())
                                    .contains("#450")
                                    .contains("`normativa`")
                                    .contains("/conjuntos?ejercicio=2026");
                        });
    }

    @Test
    @DisplayName("las pruebas del arbol corren en el modo que falla")
    void lasPruebasCorrenEnElModoQueFalla() {
        assertThat(ConTransaccionAbierta.configurado())
                .as(
                        "lo pone `kamayuk.pruebas` en cada tarea de prueba; sin eso, la guarda solo"
                                + " avisaria y ningun llamador nuevo saldria rojo")
                .isEqualTo(ConTransaccionAbierta.FALLAR);
    }
}
