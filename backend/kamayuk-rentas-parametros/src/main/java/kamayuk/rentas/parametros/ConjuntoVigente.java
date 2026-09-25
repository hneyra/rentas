package kamayuk.rentas.parametros;

import java.util.Objects;

/**
 * Que conjunto sellado rige <b>y</b> que dice, salidos de <b>una sola</b> resolucion (#361).
 *
 * <h2>Por que existe</h2>
 *
 * <p>Hasta #361 {@link LectorDeParametros} publicaba ese hecho partido en dos preguntas: {@link
 * LectorDeParametros#vigenteEn} devolvia los parametros sin su identificador, y {@link
 * LectorDeParametros#conjuntoVigenteEn} el identificador sin los parametros. Quien necesitaba las
 * dos cosas —los parametros para calcular y el identificador para guardarlo— las pedia por
 * separado, y en produccion cada pregunta es una resolucion independiente: va por red a {@code
 * normativa} y, si no contesta, se repliega por su cuenta al ultimo conjunto cacheado. Una
 * determinacion predial resolvia cuatro veces; entre la primera y la cuarta cabian un sellado nuevo
 * o un repliegue a medias, y la fila guardaba el identificador de un conjunto que no habia dado los
 * tramos. Nada fallaba: la determinacion decia haber salido de un conjunto que no la produjo
 * (ARQ-09 §3).
 *
 * <p>Este record es la unica fuente de verdad de las dos cosas juntas. Una vez fijado el
 * identificador, los parametros se leen por el —{@link LectorDeParametros#porConjunto}—, que ya no
 * decide nada: no hay segunda resolucion que pueda contestar otro conjunto.
 *
 * <p>La municipalidad no viaja aqui: sale del token y la fija {@code SET LOCAL} (regla 2).
 *
 * @param identificador el conjunto que rige; es el que se guarda en la fila que lo uso
 * @param parametros lo que ese conjunto dice, leido por su identificador
 */
public record ConjuntoVigente(
        IdentificadorDeConjunto identificador, ParametrosSellados parametros) {

    public ConjuntoVigente {
        Objects.requireNonNull(identificador, "Un conjunto vigente tiene su identificador");
        Objects.requireNonNull(parametros, "Un conjunto vigente tiene sus parametros");
    }

    /** El identificador como lo guarda {@code determinacion.conjunto_id}. */
    public long id() {
        return identificador.valor();
    }
}
