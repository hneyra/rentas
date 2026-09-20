package kamayuk.rentas.coactiva.dominio;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Cuantos expedientes coactivos hay en cada etapa del procedimiento (#272, RF-100).
 *
 * <h2>Las siete etapas son DISJUNTAS, y eso es lo que hace que el rotulo no mienta</h2>
 *
 * <p>El estado de un expediente es el del <b>ultimo</b> movimiento que lleve estado ({@link
 * EstadoDelExpediente#delHistorial}), asi que un expediente esta en una etapa y solo en una. Un
 * expediente con la medida cautelar trabada cuenta en {@link EstadoDelExpediente#MEDIDA_CAUTELAR} y
 * <b>no</b> en {@link EstadoDelExpediente#REC1_NOTIFICADA}, aunque su REC este notificada: lo que
 * este resumen contesta es «en que punto esta», que es la pregunta que el desplegable «Etapa» de
 * {@code coactiva_expedientes} hace con esas mismas palabras.
 *
 * <p>Por eso {@link #abiertos()} <b>no</b> es la suma de tres etapas: hay expedientes abiertos en
 * {@code REC1_EMITIDA}, en {@code REC2_EMITIDA} y en {@code SUSPENDIDO} que no estan en ninguna de
 * las tres que el panel nombra. Quien quiera cuadrar la cuenta tiene las siete en {@link
 * #porEstado()}, que es justo para lo que viajan.
 *
 * <h2>«Abierto» es «no concluido», y no es lo mismo que «todos»</h2>
 *
 * <p>{@link #expedientes()} cuenta los del criterio, concluidos incluidos; {@link #abiertos()}
 * descuenta los que {@link EstadoDelExpediente#estaConcluido()} declara terminados. Tener los dos
 * es deliberado: el panel de {@code coa-panel} dibujaba «Expedientes abiertos» con el primero
 * (#272), y sin los dos numeros publicados no hay forma de ver que no son el mismo.
 *
 * <p>Funcion pura (regla 6): entra el recuento por estado y salen las cuentas. Sin base, sin reloj
 * y sin configuracion; la fecha de la lectura la pone quien lo publica.
 *
 * @param porEstado cuantos expedientes hay en cada etapa. Siempre las <b>siete</b>, con cero donde
 *     no hay ninguno: una etapa ausente y una etapa vacia se leen igual, y solo una de las dos es
 *     un hecho
 */
public record ResumenDeLaCartera(Map<EstadoDelExpediente, Long> porEstado) {

    public ResumenDeLaCartera {
        Objects.requireNonNull(porEstado, "El resumen cuenta por etapa, aunque no haya ninguna");
        Map<EstadoDelExpediente, Long> completo = new EnumMap<>(EstadoDelExpediente.class);
        for (EstadoDelExpediente etapa : EstadoDelExpediente.values()) {
            completo.put(etapa, 0L);
        }
        for (Map.Entry<EstadoDelExpediente, Long> contado : porEstado.entrySet()) {
            EstadoDelExpediente etapa =
                    Objects.requireNonNull(contado.getKey(), "Un recuento es de una etapa");
            long cuantos = Objects.requireNonNull(contado.getValue(), "Un recuento nunca es nulo");
            if (cuantos < 0) {
                throw new IllegalArgumentException(
                        "Un recuento de expedientes no puede ser negativo: "
                                + etapa
                                + " = "
                                + cuantos);
            }
            completo.put(etapa, cuantos);
        }
        porEstado = Collections.unmodifiableMap(completo);
    }

    /** Sin ningun expediente: las siete etapas en cero. */
    public static ResumenDeLaCartera vacio() {
        return new ResumenDeLaCartera(Map.of());
    }

    /** Cuantos expedientes estan en esa etapa. */
    public long en(EstadoDelExpediente etapa) {
        Objects.requireNonNull(etapa, "Se pregunta por una etapa concreta");
        // El constructor completa las siete, asi que nunca falta ninguna; `getOrDefault` esta
        // por el desempaquetado y no porque se espere un hueco.
        return porEstado.getOrDefault(etapa, 0L);
    }

    /** Cuantos expedientes hay en total, <b>concluidos incluidos</b>. */
    public long expedientes() {
        long total = 0;
        for (long cuantos : porEstado.values()) {
            total += cuantos;
        }
        return total;
    }

    /**
     * Cuantos siguen abiertos: todos menos los concluidos.
     *
     * <p>Un expediente suspendido sigue abierto —el procedimiento esta detenido, no terminado—, y
     * por eso se cuenta aqui. El unico que sale es el que {@link
     * EstadoDelExpediente#estaConcluido()} declara terminado, que es el mismo predicado con el que
     * {@code CambiarEstadoDelExpediente} se niega a mover un expediente concluido.
     */
    public long abiertos() {
        long total = 0;
        for (Map.Entry<EstadoDelExpediente, Long> etapa : porEstado.entrySet()) {
            if (!etapa.getKey().estaConcluido()) {
                total += etapa.getValue();
            }
        }
        return total;
    }
}
