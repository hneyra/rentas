package kamayuk.rentas.valores.dominio;

import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Las declaraciones de prescripcion.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2), y no hay ninguno que actualice ni que borre:
 * una resolucion no se edita.
 */
public interface PrescripcionRepository {

    /**
     * Guarda la solicitud con el computo de cada ejercicio y los hechos alegados, en una sola
     * operacion.
     *
     * @param prescripcion {@link Prescripcion#esNueva()} tiene que ser verdadero
     */
    Prescripcion insertar(Prescripcion prescripcion);

    Optional<Prescripcion> porId(long id);

    /**
     * La relacion de declaraciones que pide el criterio, paginada (#674, RF-094).
     *
     * <p>Devuelve {@link PrescripcionEnLista} y no {@link Prescripcion}: la fila de una relacion no
     * lleva el computo entero de cada ejercicio ni los hechos alegados, que son dos consultas mas
     * por fila y pertenecen a la resolucion, no al listado.
     */
    Pagina<PrescripcionEnLista> buscar(CriterioDePrescripciones criterio, Paginacion paginacion);

    /**
     * Los pares (tributo, ejercicio) que alguna resolucion declaro prescritos para ese
     * contribuyente, sumando todas las que tiene (#337).
     *
     * <p>Es el conjunto contra el que {@link CoberturaDeLaPrescripcion} mide un valor. Acumulado y
     * no de una sola resolucion, porque la cobertura se completa entre varias: el PREDIAL 2021 de
     * una y el 2022 de la siguiente cubren entre las dos el valor que formaliza los dos. Sale de lo
     * que ya se guarda —{@code prescripcion} y {@code prescripcion_ejercicio} con {@code
     * prescrita}—, sin migracion; un ejercicio que la resolucion computo y NO prescribio no entra.
     */
    Set<ObligacionPrescrita> obligacionesPrescritasDe(long contribuyenteId);
}
