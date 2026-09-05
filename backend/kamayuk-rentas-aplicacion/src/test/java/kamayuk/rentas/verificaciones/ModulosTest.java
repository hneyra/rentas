package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kamayuk.rentas.SgtmAplicacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Limites entre modulos (ADR-0003, ARQ-01 §4). Bloqueante.
 *
 * <p>Sin esto, "monolito modular" degrada a monolito en pocos meses: nada impide que un contexto
 * llame a las clases internas de otro, y cuando se nota ya hay cincuenta llamadas que desenredar.
 */
@DisplayName("ADR-0003 — Limites entre modulos")
class ModulosTest {

    private static final ApplicationModules MODULOS = ApplicationModules.of(SgtmAplicacion.class);

    @Test
    @DisplayName("los modulos esperados estan detectados")
    void losModulosEsperadosEstanDetectados() {
        List<String> detectados =
                MODULOS.stream().map(m -> m.getIdentifier().toString()).sorted().toList();

        // Si Modulith no detectara ningun modulo, verify() pasaria sin comprobar nada.
        //
        // Heredado del SRTM y verificado alli: un paquete con solo package-info.java
        // NO es un modulo para Modulith, hace falta al menos un tipo. Hoy los doce
        // contextos tienen codigo, asi que la lista exige los doce: si uno dejara de
        // detectarse, esta prueba lo nombraria.
        assertThat(detectados)
                .as("los modulos que ya tienen codigo")
                .contains(
                        "dominio",
                        "compartido",
                        "plataforma",
                        "persistencia",
                        "auditoria",
                        "documentos",
                        "web",
                        "catastro",
                        "contribuyentes",
                        "parametros",
                        "fiscalizacion",
                        "valores",
                        "coactiva",
                        "licencias",
                        "seguridad",
                        "cuentacorriente",
                        // El contexto acotado `rentas`, que se llama `nucleo` desde R-N:
                        // «kamayuk-rentas-rentas» repetia el nombre del sistema y la direccion
                        // pidio quitarlo. El identificador que Modulith detecta es el ULTIMO
                        // segmento del paquete, asi que renombrar el paquete lo renombra aqui — y
                        // esta lista es lo que lo puso en rojo al hacerlo.
                        "nucleo",
                        "sanciones",
                        // #56: el panel de recaudacion. No es un contexto acotado —ARQ-01 §3
                        // fija doce— pero si es un modulo para Modulith, y eso es lo que
                        // hace comprobable el AC 3: si el panel tocara un tipo interno de
                        // cuentacorriente o de tesoreria, verify() lo nombraria.
                        "indicadores",
                        "tesoreria");
    }

    @Test
    @DisplayName("no hay dependencias no declaradas ni ciclos entre modulos")
    void noHayDependenciasNoDeclaradasNiCiclos() {
        MODULOS.verify();
    }
}
