package kamayuk.rentas.sanciones;

import kamayuk.rentas.dominio.Dinero;

/**
 * Cuantas papeletas de transito siguen vivas sin que se les haya emitido nada, y cuanto suman
 * (#549).
 *
 * <p>Es la <b>API publica</b> de {@code sanciones} para el panel de trabajo parado, y devuelve un
 * <b>agregado</b>: un recuento y una suma, nunca la lista. Un puerto que devolviera las papeletas
 * dejaria a la pantalla de aterrizaje recorriendo el padron entero en cada carga, que es lo que el
 * AC 4 de #56 prohibe y lo que {@code PanelSinRecorrerElLibroTest} comprueba.
 *
 * <h2>«Sin notificar» NO es {@code estado = IMPUESTA}, y eso hubo que medirlo</h2>
 *
 * <p>{@code EstadoDePapeleta} declara la secuencia {@code IMPUESTA → NOTIFICADA → …} y {@code
 * FaseDelProcedimiento} la describe, pero <b>ningun codigo de produccion escribe {@code
 * NOTIFICADA}</b>: el censo de los usos del enumerado en {@code src/main} da {@code IMPUESTA} —el
 * estado con el que nace—, {@code PAGADA}, {@code ANULADA} y {@code PRESCRITA}, y ninguna otra. Asi
 * que contar {@code estado = IMPUESTA} contaria «las que no estan pagadas, anuladas ni prescritas»
 * y lo publicaria bajo el rotulo «sin notificar»: una cifra plausible y equivocada, que incluiria
 * todas las que ya se notificaron en la calle.
 *
 * <p>Lo que el sistema <b>si</b> sabe de la notificacion de una papeleta es indirecto y consta: una
 * papeleta se cobra por su <b>resolucion de multa</b>, que se emite (#53) y se notifica (#39). Una
 * papeleta a la que nadie le ha emitido nada no se le ha podido notificar nada. Por eso el criterio
 * es <b>sin valor emitido y todavia exigible</b> —{@code conValorEmitido = FALSE} y {@code
 * soloPendientes}—, que es exactamente el complemento del padron {@code transito_padron_coactiva}
 * ({@code conValorEmitido = TRUE}) y el mismo conjunto que {@code CriterioDePadron.candidatos}
 * selecciona para una corrida masiva.
 *
 * <p>Es decir: <b>el frente son las papeletas que estan esperando su corrida</b>. Cuando alguien
 * escriba el acto que registra la notificacion de la papeleta en si, este criterio se estrecha; lo
 * que no se puede hacer hoy es decir que se cuenta algo que no consta.
 *
 * <p>Por que cuesta dinero: sin emitir no se pueden cobrar, y el plazo de prescripcion les corre
 * igual.
 */
public interface PapeletasSinNotificar {

    /**
     * El recuento y la suma de las papeletas de transito vivas y sin valor emitido.
     *
     * <p>Sin fecha de corte, y a proposito: el estado de la papeleta y la existencia de su valor
     * son los de hoy —no hay columna con la que reconstruir los de una fecha pasada—, asi que
     * pedirla con una fecha seria pedir algo que la tabla no puede contestar. Quien la publica le
     * pone la fecha de la lectura, que es lo que esa cifra describe.
     */
    PapeletasImpuestas sinNotificar();

    /**
     * Cuantas hay y cuanto suman.
     *
     * @param cuantas el recuento; cero cuando no hay ninguna
     * @param importe la suma de {@code importe_a_pagar}; cero cuando no hay ninguna, nunca nulo —la
     *     suma de nada es cero, y eso es un hecho que se puede afirmar
     */
    record PapeletasImpuestas(long cuantas, Dinero importe) {

        public PapeletasImpuestas {
            if (cuantas < 0) {
                throw new IllegalArgumentException("Un recuento no es negativo: " + cuantas);
            }
            java.util.Objects.requireNonNull(importe, "La suma de nada es cero, no ausente");
        }
    }
}
