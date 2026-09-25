package kamayuk.rentas.plataforma;

/**
 * Como quedo la cola de un buzon al terminar una vuelta: vacia, al dia, o BLOQUEADA en su cabeza
 * (#377).
 *
 * <h2>Por que no basta con «sin progreso»</h2>
 *
 * <p>Los dos consumidores paraban de dar vueltas en cuanto una no resolvia nada, y las dos veces
 * con razon: lo que no se acusa se vuelve a servir, y dar cincuenta vueltas sobre lo mismo es
 * avisar cincuenta veces de lo mismo (#54). Pero «sin progreso» junta dos casos que no se parecen:
 *
 * <ul>
 *   <li>lo que queda sin resolver es <b>todo lo que hay</b>: esperar no para a nadie, y la corrida
 *       puede acabar bien;
 *   <li>lo que queda sin resolver <b>llena la pagina y hay mas detras</b>: la pagina siguiente —la
 *       de esta corrida y la de todas las siguientes— son los mismos, y lo de detras no se lee
 *       nunca. Eso es una cola parada, y hasta #377 salia en verde.
 * </ul>
 *
 * <p>Se distinguen con una cifra que ya viajaba y nadie miraba: cuantos quedan en el emisor.
 */
public sealed interface EstadoDeLaCola {

    /** No habia nada que leer. */
    EstadoDeLaCola VACIA = new Vacia();

    /** Lo que habia se resolvio, o lo que queda sin resolver es todo lo que hay. */
    EstadoDeLaCola AL_DIA = new AlDia();

    /**
     * El estado al terminar una vuelta.
     *
     * @param leidos cuantos trajo la pagina
     * @param resueltos cuantos de ellos se acusaron —aplicados, repetidos, ajenos o apartados—
     * @param detras cuantos quedan en el emisor DETRAS de esta pagina, sin contarla
     * @param secuenciaDeCabeza la secuencia del primero que no se resolvio: es el que ocupa la
     *     cabeza, y lo que hay que ir a mirar
     */
    static EstadoDeLaCola alTerminarLaVuelta(
            int leidos, int resueltos, long detras, long secuenciaDeCabeza) {
        if (leidos == 0) {
            return VACIA;
        }
        if (resueltos == 0 && detras > 0) {
            return new Bloqueada(secuenciaDeCabeza, leidos, detras);
        }
        return AL_DIA;
    }

    /** Si hay algo detras de la cabeza que no se va a leer. */
    default boolean bloqueada() {
        return this instanceof Bloqueada;
    }

    /** No habia nada que leer. */
    record Vacia() implements EstadoDeLaCola {
        @Override
        public String toString() {
            return "VACIA";
        }
    }

    /** Lo leido se resolvio, o lo que queda sin resolver es todo lo que hay. */
    record AlDia() implements EstadoDeLaCola {
        @Override
        public String toString() {
            return "AL_DIA";
        }
    }

    /**
     * La pagina entera se quedo sin resolver y el emisor tiene mas detras: la vuelta siguiente trae
     * lo mismo, y lo de detras no se lee nunca.
     *
     * @param secuenciaDeCabeza la del primero de la pagina que no se resolvio
     * @param enLaCabeza cuantos ocupan la pagina sin resolverse
     * @param detras cuantos esperan detras sin poder leerse
     */
    record Bloqueada(long secuenciaDeCabeza, int enLaCabeza, long detras)
            implements EstadoDeLaCola {

        public Bloqueada {
            if (enLaCabeza <= 0 || detras <= 0) {
                throw new IllegalArgumentException(
                        "Una cola bloqueada tiene algo en la cabeza y algo detras: "
                                + enLaCabeza
                                + " y "
                                + detras);
            }
        }

        @Override
        public String toString() {
            return "BLOQUEADA en la secuencia "
                    + secuenciaDeCabeza
                    + ": "
                    + enLaCabeza
                    + " sin resolver en la cabeza y "
                    + detras
                    + " esperando detras sin poder leerse";
        }
    }
}
