package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/coactiva/liquidaciones-costas} (RF-104). <b>Lista blanca</b>: lo
 * que no esta aqui no entra.
 *
 * <p><b>No hay ningun importe.</b> Ni el monto de una costa, ni el total. La pantalla del prototipo
 * muestra «Monto (S/)» y «Total (S/)» y son <b>de salida</b>: el arancel los pone. Que aqui no
 * entren es lo que impide que un cliente mal escrito —o uno que lo intenta— mande los suyos y el
 * libro los asiente sin discutir (regla 5, D-02c).
 *
 * <p><b>Tampoco entra el tributo.</b> La costa procesal se imputa siempre a {@code COSTAS
 * PROCESALES}; el desplegable de la pantalla ofrece ademas «GASTOS DE EJECUCION», que #42 deja
 * fuera a proposito (vease {@code LiquidacionDeCostas#TRIBUTO}).
 *
 * @param nroExpedCoact el numero impreso del expediente cuyo procedimiento se liquida
 * @param fecha el dia de la liquidacion, en ISO; si falta, hoy
 * @param actos que actos se liquidan; vacio o ausente significa «todos los que queden pendientes y
 *     el arancel tarife»
 * @param observacion por que se liquida (regla 10)
 */
public record PeticionDeLiquidacionDeCostas(
        @Nullable String nroExpedCoact,
        @Nullable String fecha,
        @Nullable List<Long> actos,
        @Nullable String observacion) {}
