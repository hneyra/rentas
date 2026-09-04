package kamayuk.rentas.sanciones.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de las dos generaciones masivas de valores por papeletas (#53, RF-066, RF-073).
 * <b>Lista blanca</b>: lo que no está aquí no entra.
 *
 * <p>Exactamente uno de {@code papeletas} o el par {@code desde}/{@code hasta} tiene que venir: es
 * el «por selección o por rango» de la pantalla. Los dos a la vez se rechazan en vez de que uno
 * gane en silencio —el que gane dependería del orden en que el controlador los mire, y quien opere
 * no sabría cuál—.
 *
 * <p>Aquí <b>no</b> hay ningún campo para el número del valor, ni para una serie, ni para un
 * correlativo de arranque, y su ausencia es el primer criterio de aceptación de #53 escrito en el
 * transporte: el número lo pone {@code valor_correlativo} (V26). Si entrara por el cuerpo, el día
 * que alguien quisiera «una serie propia para las multas» le bastaría con mandar otro texto.
 *
 * @param papeletas los números marcados en la grilla, para la corrida por selección
 * @param desde primer día de infracción del rango
 * @param hasta último día del rango
 * @param fechaCriterio a qué fecha se evalúa la deuda y el plazo; si falta, hoy (regla 9)
 * @param observacion por qué se registra (regla 10, RNF-052)
 */
public record PeticionDeCorridaDeValores(
        @Nullable List<String> papeletas,
        @Nullable String desde,
        @Nullable String hasta,
        @Nullable String fechaCriterio,
        @Nullable String observacion) {}
