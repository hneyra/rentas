package kamayuk.rentas.parametros.aplicacion;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hay que saber para abrir, componer o sellar el conjunto de parametros de un ejercicio
 * (#247 §2).
 *
 * <h2>O se abre una version, o se opera sobre una ya abierta: nunca las dos</h2>
 *
 * <p>La secuencia real tiene un paso ajeno en medio —abrir la version, cargar el arancel de ese
 * ejercicio con {@code cargar-arancel-vial.sh --conjunto-id N}, y solo entonces sellar—, asi que un
 * proceso que solo supiera abrir dejaria el sellado sin camino. Se admiten los dos modos y se exige
 * exactamente uno: con {@code ejercicio} abre la version siguiente; con {@code conjuntoId} opera
 * sobre la que ya existe.
 *
 * <p>Que no se admita es abrir <b>y</b> nombrar un conjunto: serian dos conjuntos y uno de los dos
 * quedaria a medio componer, sin que el registro dijera cual.
 *
 * @param municipalidadId identificador ya existente de la municipalidad cuyo ejercicio se
 *     parametriza
 * @param ejercicio el ejercicio del que se abre una version nueva; 0 si se opera sobre una abierta
 * @param conjuntoId la version ya abierta sobre la que se opera; 0 si se abre una nueva
 * @param archivo ruta al CSV {@code tipo,clave,vigenciaDesde} que nombra los parametros ya
 *     publicados que componen el ejercicio; nulo si esta corrida no compone
 * @param sellar si ademas se sella el conjunto al terminar. Nunca implicito: un conjunto sellado no
 *     se modifica (V9), y la unica salida de un sellado equivocado es otra version
 * @param usuarioDelProceso con que nombre firma la auditoria lo que hace este proceso; es tambien
 *     el que queda como {@code usuario_sellado}
 * @param observacion el «por que» del acto (regla 10, ADR-0008)
 */
@ConfigurationProperties("sgtm.conjunto-parametros")
public record DatosDelConjunto(
        long municipalidadId,
        int ejercicio,
        long conjuntoId,
        @Nullable String archivo,
        boolean sellar,
        String usuarioDelProceso,
        String observacion) {

    public DatosDelConjunto {
        if (municipalidadId < 1) {
            throw new IllegalArgumentException(
                    "Falta sgtm.conjunto-parametros.municipalidad-id, o no es un identificador"
                            + " valido");
        }
        boolean abre = ejercicio > 0;
        boolean opera = conjuntoId > 0;
        if (abre == opera) {
            throw new IllegalArgumentException(
                    "Hay que dar sgtm.conjunto-parametros.ejercicio —para abrir una version— o"
                            + " sgtm.conjunto-parametros.conjunto-id —para operar sobre una ya"
                            + " abierta—, y exactamente uno de los dos");
        }
        archivo = archivo == null || archivo.isBlank() ? null : archivo.strip();
        if (sellar && abre && archivo == null) {
            // Lo rechazaria igual AdministrarParametros.sellar, pero lo rechaza aqui para que el
            // proceso no llegue a abrir una version que nadie va a poder sellar y que queda en la
            // base ocupando un numero.
            throw new IllegalArgumentException(
                    "Abrir una version y sellarla sin componerla es sellar un conjunto vacio: diria"
                            + " que el ejercicio esta parametrizado cuando el calculo no encontraria"
                            + " ni un valor");
        }
        usuarioDelProceso =
                usuarioDelProceso == null || usuarioDelProceso.isBlank()
                        ? "conjunto-parametros"
                        : usuarioDelProceso;
        observacion =
                observacion == null || observacion.isBlank()
                        ? "Apertura del conjunto de parametros del ejercicio"
                        : observacion;
    }

    /** Si esta corrida abre una version nueva, en vez de operar sobre una ya abierta. */
    public boolean abreVersion() {
        return ejercicio > 0;
    }
}
