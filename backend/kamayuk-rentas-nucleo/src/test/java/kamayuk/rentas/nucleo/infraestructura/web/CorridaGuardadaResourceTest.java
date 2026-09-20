package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Los dos agregados que la corrida sabe, publicados como campo</b> (#271).
 *
 * <h2>Que se mide y por que hacia falta</h2>
 *
 * <p>El panel del modulo —«Estado de la emision»— dibuja «Cuentas emitidas» y «Monto determinado»
 * arriba, como cifra suelta, y los dos estaban en {@code corrida_predial} desde #523 sin salir por
 * la API mas que <b>dentro</b> de una fila de {@link CorridaPredialResource.Etapa}. Mientras fuera
 * asi, la unica manera de pintarlos era leer la segunda fila de la tabla en el navegador, que es
 * exactamente lo que la regla de {@code conectores.ts} prohibe: el dia que la corrida tenga dos
 * etapas de determinacion, o que una se renombre, el campo seguiria ensenando una cifra plausible y
 * equivocada, en verde.
 *
 * <h2>La muestra NO es uniforme, a proposito</h2>
 *
 * <p>Los cuatro enteros de esta corrida son distintos —{@code id} 7, {@code leidos} 9 999, {@code
 * determinados} 9 417, {@code observados} 582— porque una muestra con cifras que coinciden no
 * distingue «publica los determinados» de «publica los leidos», ni de «publica los observados». Con
 * ellos iguales, tres implementaciones pasarian la misma prueba.
 */
@DisplayName("GET /rentas/predial/corridas/ultima — los agregados de la corrida (#271)")
class CorridaGuardadaResourceTest {

    private static final int LEIDOS = 9_999;
    private static final int DETERMINADOS = 9_417;
    private static final int FUERA = LEIDOS - DETERMINADOS;
    private static final String EMITIDO = "9418204.60";

    /** Una corrida ya escrita, con las cuatro cifras separadas entre si. */
    private static CorridaDeEmision corrida() {
        return new CorridaDeEmision(
                7L,
                new Ejercicio(2026),
                "TODOS",
                null,
                null,
                null,
                "TRIMESTRAL",
                false,
                "2026 v1",
                LEIDOS,
                DETERMINADOS,
                Dinero.de(EMITIDO),
                LocalDate.of(2026, 1, 28),
                List.of());
    }

    @Test
    @DisplayName("las cuentas emitidas son los DETERMINADOS, no los leidos ni los observados")
    void lasCuentasEmitidasSonLosDeterminados() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());

        assertThat(resource.determinados())
                .as("es la columna `determinados` de la corrida, no una cuenta recompuesta")
                .isEqualTo(DETERMINADOS);
        assertThat(resource.determinados()).isNotEqualTo(LEIDOS).isNotEqualTo(FUERA);
        assertThat(resource.observados()).isEqualTo(FUERA);
    }

    @Test
    @DisplayName("el monto determinado sale TAL CUAL, sin redondear ni formatear")
    void elMontoSaleTalCual() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());

        // Texto y no un numero de coma flotante (regla 1, RNF-055): `9418204.60` en un `double`
        // vuelve como `9418204.6`, y el centimo perdido no da error en ningun sitio.
        assertThat(resource.montoEmitido()).isEqualTo(EMITIDO);
    }

    /**
     * <b>Un hecho, publicado una vez.</b>
     *
     * <p>La etapa «Determinados» y el campo de arriba dicen lo mismo de la misma corrida. Si
     * salieran de dos sitios distintos, la que se leyera al abrir la pantalla seria la que nadie
     * compara — que es el motivo por el que {@link CorridaGuardadaResource} compone las etapas de
     * los mismos campos y no al reves.
     */
    @Test
    @DisplayName("el campo y su etapa dicen lo mismo, porque salen del mismo campo de la corrida")
    void elCampoYSuEtapaDicenLoMismo() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());
        CorridaPredialResource.Etapa determinacion = resource.etapas().get(1);

        assertThat(determinacion.etapa()).isEqualTo("Determinados");
        assertThat(determinacion.registros()).isEqualTo(resource.determinados());
        assertThat(determinacion.monto()).isEqualTo(resource.montoEmitido());
    }

    /**
     * <b>El tercer campo del panel sigue sin publicarse, y esto lo deja escrito</b> (D-02b).
     *
     * <p>«Derecho de emision» no esta aqui porque {@code corrida_predial} no lo sella: la corrida
     * lo <i>aplica</i> dentro del total de cada contribuyente y no guarda su valor. Lo unico que
     * sella es el <b>nombre</b> del conjunto —{@code "2026 v1"}—, que no es el {@code conjuntoId}
     * que hace falta para volver a leerlo, y en una corrida que no determino a nadie es la cadena
     * vacia.
     *
     * <p>Esta prueba es la que se pone roja el dia que alguien lo anada leyendolo del conjunto
     * vigente HOY: entonces hay que anadir la columna a la corrida, no el campo al recurso.
     */
    @Test
    @DisplayName("el derecho de emision NO se publica: la corrida no lo sella")
    void elDerechoDeEmisionNoSePublica() {
        List<String> campos =
                Arrays.stream(CorridaGuardadaResource.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList();

        assertThat(campos)
                .as(
                        "publicarlo exigiria leerlo del conjunto de hoy, que no tiene por que ser aquel")
                .noneMatch(nombre -> nombre.toLowerCase(java.util.Locale.ROOT).contains("derecho"));
        assertThat(CorridaGuardadaResource.de(corrida()).conjunto())
                .as("lo que la corrida sella es el NOMBRE del conjunto, no su identificador")
                .isEqualTo("2026 v1");
    }
}
