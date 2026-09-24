package kamayuk.rentas.valores.dominio;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * De que ejercicios es la deuda que un hecho interrumpe o suspende (#334).
 *
 * <p>Los arts. 45 y 46 del TUO del Codigo Tributario interrumpen o suspenden el plazo de <b>una
 * deuda concreta</b>: el pago parcial del predial 2019 interrumpe la prescripcion del 2019, no la
 * del 2020 ni la del 2021. Hasta #334 ninguna capa guardaba esa pertenencia —ni el hecho, ni el
 * cuerpo de la peticion, ni {@code prescripcion_hecho}—, y el caso de uso aplicaba la misma lista a
 * todos los ejercicios del rango: se negaban prescripciones que procedian y una solicitud legitima
 * podia salir 422.
 *
 * <p><b>«Sin declarar» existe, y no significa «todos».</b> Suponer «todos» es exactamente el
 * defecto. Un hecho puede llegar sin alcance por dos caminos, y ninguno lo resuelve este tipo:
 *
 * <ul>
 *   <li>En la peticion, cuando la solicitud es de <b>un solo</b> ejercicio: ahi no hay a donde mas
 *       pertenecer, y {@code DeclararPrescripcion} lo completa con ese ejercicio. Si el rango tiene
 *       mas de uno, lo rechaza nombrando el hecho.
 *   <li>Al releer una resolucion anterior a {@code V25}, que se guardo sin esta columna. Su computo
 *       se conserva como se resolvio —aplicando cada hecho a todo el rango—, y el alcance se
 *       publica en blanco en vez de reconstruido.
 * </ul>
 *
 * @param ejercicios en orden; vacio solo en «sin declarar»
 */
public record AlcanceDelHecho(SortedSet<Ejercicio> ejercicios) {

    private static final AlcanceDelHecho SIN_DECLARAR = new AlcanceDelHecho(new TreeSet<>());

    public AlcanceDelHecho {
        Objects.requireNonNull(ejercicios, "El alcance es un conjunto; vacio si no se declaro");
        ejercicios = Collections.unmodifiableSortedSet(new TreeSet<>(ejercicios));
    }

    /** Un hecho del que no consta a que ejercicio pertenece. Ver la cabecera. */
    public static AlcanceDelHecho sinDeclarar() {
        return SIN_DECLARAR;
    }

    /**
     * El alcance declarado.
     *
     * @throws IllegalArgumentException si viene vacio: declarar un alcance es nombrar al menos un
     *     ejercicio; lo que no nombra ninguno es {@link #sinDeclarar()}, y se dice asi
     */
    public static AlcanceDelHecho de(Collection<Ejercicio> ejercicios) {
        Objects.requireNonNull(ejercicios, "El alcance declarado necesita sus ejercicios");
        if (ejercicios.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un hecho declara al menos un ejercicio al que pertenece; si no consta, no se"
                            + " declara");
        }
        return new AlcanceDelHecho(new TreeSet<>(ejercicios));
    }

    public static AlcanceDelHecho de(Ejercicio... ejercicios) {
        return de(List.of(ejercicios));
    }

    public boolean declarado() {
        return !ejercicios.isEmpty();
    }

    /** Si el hecho actua sobre el computo de ese ejercicio. Sin declarar, sobre ninguno. */
    public boolean alcanza(Ejercicio ejercicio) {
        return ejercicios.contains(ejercicio);
    }

    @Override
    public String toString() {
        return declarado() ? ejercicios.toString() : "sin declarar";
    }
}
