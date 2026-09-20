package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ResumenDeLaCartera;
import org.jspecify.annotations.Nullable;

/**
 * El resumen de la cartera coactiva, tal como sale por HTTP (#272, RF-100).
 *
 * <h2>Por que existe: un rotulo que prometia expedientes abiertos sobre otra cifra</h2>
 *
 * <p>La hoja {@code coa-panel} dibujaba «Expedientes abiertos» con el {@code totalElementos} de
 * {@code GET /coactiva/deudas}. Esa cifra cuenta <b>expedientes</b> —la consulta devuelve una fila
 * por expediente y no por deuda—, asi que la unidad era correcta; lo que no lo era es el adjetivo:
 * cuenta <b>todos</b> los del criterio, concluidos incluidos, y ademas cuenta los que la propia
 * respuesta descarta por no tener nada que cobrar. Aqui viajan los dos numeros por separado —{@link
 * #expedientes} y {@link #abiertos}— y con su nombre.
 *
 * <h2>Las etapas son disjuntas, y por eso no suman los abiertos</h2>
 *
 * <p>El estado es el del <b>ultimo</b> movimiento con estado, asi que un expediente esta en una
 * etapa y solo en una: el que tiene la medida trabada cuenta en {@code conMedidaCautelar} y no en
 * {@code conRecNotificada}. {@link #abiertos} no es la suma de las tres etapas que el panel nombra
 * —faltan {@code REC1_EMITIDA}, {@code REC2_EMITIDA} y {@code SUSPENDIDO}—, y {@link #porEtapa} es
 * lo que permite cuadrarlo sin tener que adivinarlo.
 *
 * <h2>Sin ninguna cifra de dinero, a proposito</h2>
 *
 * <p>Ver {@code ConsultaDeExpedientes.resumenDeLaCartera}: la deuda de un expediente se compone
 * leyendo el libro del obligado, y sumarla sobre la cartera entera costaria una lectura por
 * expediente y contaria dos veces la obligacion que dos expedientes del mismo obligado
 * formalizaran. Un recuento no lleva simbolo de moneda; que no haya ningun importe es lo que impide
 * que alguien lo lea como si lo llevara.
 *
 * @param aLaFecha el dia de la lectura. El estado se deriva del ultimo movimiento y el repositorio
 *     no sabe reconstruirlo a una fecha pasada, asi que esta fecha es cuando se miro y no una fecha
 *     de corte (mismo criterio que {@code ExpedientesSinRec})
 * @param ejercicio el ejercicio por el que se filtro, o nulo si se contaron todos
 * @param expedientes cuantos hay en total, <b>concluidos incluidos</b>
 * @param abiertos cuantos no estan concluidos. Un suspendido cuenta aqui: el procedimiento esta
 *     detenido, no terminado
 * @param sinRec cuantos siguen en {@code INICIADO}: importados y sin REC-1 dictada. Es la misma
 *     definicion que {@code ExpedientesSinRec} publica para el panel de aterrizaje
 * @param conRecNotificada cuantos estan en {@code REC1_NOTIFICADA}
 * @param conMedidaCautelar cuantos estan en {@code MEDIDA_CAUTELAR}
 * @param porEtapa las <b>siete</b> etapas con su recuento, en el orden del procedimiento. Las que
 *     no tienen ningun expediente salen con cero y no se omiten
 */
public record ResumenDeLaCarteraResource(
        LocalDate aLaFecha,
        @Nullable Integer ejercicio,
        long expedientes,
        long abiertos,
        long sinRec,
        long conRecNotificada,
        long conMedidaCautelar,
        List<Etapa> porEtapa) {

    public static ResumenDeLaCarteraResource de(
            ResumenDeLaCartera resumen, @Nullable Integer ejercicio, LocalDate aLaFecha) {

        List<Etapa> etapas = new ArrayList<>();
        for (EstadoDelExpediente etapa : EstadoDelExpediente.values()) {
            etapas.add(
                    new Etapa(etapa.name(), etapa.codigo(), etapa.etiqueta(), resumen.en(etapa)));
        }
        return new ResumenDeLaCarteraResource(
                aLaFecha,
                ejercicio,
                resumen.expedientes(),
                resumen.abiertos(),
                resumen.en(EstadoDelExpediente.INICIADO),
                resumen.en(EstadoDelExpediente.REC1_NOTIFICADA),
                resumen.en(EstadoDelExpediente.MEDIDA_CAUTELAR),
                List.copyOf(etapas));
    }

    /**
     * Una etapa del procedimiento con cuantos expedientes hay en ella.
     *
     * @param etapa el nombre de la constante, para que la interfaz enrute sin traducir
     * @param codigo el codigo del manual —{@code 011}, {@code 012}, {@code 021}…—
     * @param etiqueta como lo escribe la pantalla {@code expediente_historial}
     * @param expedientes cuantos hay; cero es un hecho medido, no un hueco
     */
    public record Etapa(String etapa, String codigo, String etiqueta, long expedientes) {}
}
