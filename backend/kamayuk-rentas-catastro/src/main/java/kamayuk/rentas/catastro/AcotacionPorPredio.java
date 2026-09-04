package kamayuk.rentas.catastro;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Acota la grilla de fichas a un conjunto de predios, o a su complemento (#631).
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para que {@code rentas} pueda servir la conciliacion <b>paginando y contando lo filtrado</b>.
 * Hasta #631 la componia en memoria: catastro devolvia la pagina del padron y rentas descartaba las
 * filas que no cumplian, de modo que {@code totalElementos} seguia siendo el del padron entero.
 * Medido sobre Catacaos: {@code conciliadaConRentas=Si} contestaba «722 paginas, 14 422 elementos»
 * y <b>cero filas en todas</b>.
 *
 * <p><b>Y por eso el conjunto viaja, en vez de duplicar la consulta.</b> La alternativa era que
 * rentas escribiera su propia version de la grilla —cruzando la frontera para leer {@code predio},
 * {@code ficha_catastral} y {@code titularidad}—, y eso es exactamente lo que el javadoc de {@link
 * FichasDelPadron} descarta: duplicaria «en otro SQL, que envejeceria aparte, la resolucion de la
 * version vigente a una fecha». Con la acotacion, la condicion entra en el <b>mismo</b> {@code
 * WHERE} que ya se ejecuta, asi que la pagina y el {@code count(*)} no pueden separarse: son la
 * misma consulta.
 *
 * <p><b>Lo que cuesta, dicho</b>: los identificadores viajan por el proceso. Son los predios que
 * declararon el ejercicio —cero en Catacaos hoy, y como mucho el padron entero en una municipalidad
 * madura—, o sea del orden de decenas de miles de {@code long} en el caso peor. Se pagan una vez
 * por peticion y no una por fila; la alternativa costaba una consulta correcta y una cifra falsa.
 *
 * @param modo si el conjunto incluye, excluye o no acota
 * @param predios los identificadores; vacio con {@link Modo#TODOS}
 */
public record AcotacionPorPredio(Modo modo, Set<Long> predios) {

    /** Que hace el conjunto. */
    public enum Modo {
        /** No acota: la grilla entera. */
        TODOS,
        /** Solo los predios del conjunto. Con el conjunto vacio, ninguna fila. */
        SOLO_ESTOS,
        /** Todos menos los del conjunto. Con el conjunto vacio, la grilla entera. */
        TODOS_MENOS_ESTOS
    }

    public AcotacionPorPredio {
        Objects.requireNonNull(modo, "La acotacion necesita su modo");
        predios =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(predios, "vacio, no nulo")));
        if (modo == Modo.TODOS && !predios.isEmpty()) {
            throw new IllegalArgumentException(
                    "«Todos» no acota por ningun predio: un conjunto con modo TODOS es una"
                            + " acotacion que no acota, y quien la construyo esperaba que si");
        }
    }

    public static AcotacionPorPredio ninguna() {
        return new AcotacionPorPredio(Modo.TODOS, Set.of());
    }

    public static AcotacionPorPredio soloEstos(Collection<Long> predios) {
        return new AcotacionPorPredio(Modo.SOLO_ESTOS, Set.copyOf(predios));
    }

    public static AcotacionPorPredio todosMenosEstos(Collection<Long> predios) {
        return predios.isEmpty()
                ? ninguna()
                : new AcotacionPorPredio(Modo.TODOS_MENOS_ESTOS, Set.copyOf(predios));
    }

    /** Si esta acotacion no puede devolver ninguna fila: «solo estos» sin ninguno. */
    public boolean noPuedeTraerNada() {
        return modo == Modo.SOLO_ESTOS && predios.isEmpty();
    }
}
